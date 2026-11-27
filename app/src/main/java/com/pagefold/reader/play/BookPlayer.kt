package com.pagefold.reader.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.widget.RemoteViews
import android.os.IBinder
import android.os.PowerManager
import com.pagefold.reader.App
import com.pagefold.reader.Main
import com.pagefold.reader.R
import com.pagefold.reader.book.Cue
import com.pagefold.reader.book.Line
import com.pagefold.reader.book.blockAt
import com.pagefold.reader.book.blocksOf
import com.pagefold.reader.book.Reading
import com.pagefold.reader.book.Sentences
import com.pagefold.reader.field.Settings
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Clip
import com.pagefold.reader.voice.Join
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** 一本书的朗读源。为什么不直接塞一个 List<List<Line>>：1550 章的书分完句能有几十万条，一次全建出来就是几百兆。 */
class Feed(
    val book: String,
    /** 书架上那一本的 id。通知点回来时靠它认领是哪一本。 */
    val id: String = "",
    val count: Int,
    val titleOf: (Int) -> String,
    val linesOf: suspend (Int) -> List<Line>,
    /** 这本书的念速 ×100。0 = 由调用方给的 settings.rate 决定。 */
    var rate: Int = 0,
    /** 念到哪儿了：章号、这一章里的第几个字。 */
    val onPos: ((chapter: Int, offset: Int) -> Unit)? = null,
)

/** 播放状态。UI 只读这一个对象。 */
data class PlayState(
    val playing: Boolean = false,
    val loading: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    val title: String = "",
    val current: String = "",
    val error: String? = null,
    val chapter: Int = 0,
    val cachedOnly: Boolean = false,
    /** 当前这句在本章正文里的字符区间。界面拿去高亮。 */
    val srcStart: Int = -1,
    val srcEnd: Int = -1,
    /** 睡眠定时还剩几秒。0 = 没开。 */
    val sleepLeft: Int = 0,
    /** 这一轮念的是哪本书。界面拿它认领高亮，念别人的书就别在自己身上画。 */
    val book: String = "",
)

/** 朗读播放器。边播边攒：正在放第 i 块，后台按「先备多少字」在合成后面的块。 */
class BookPlayer : Service() {

    companion object {
        /** 相邻两块语气抹平的系数。太小跟不上换人，太大等于不平。 */
        private const val SMOOTH = 0.6f

        /** 组里还只有一块时，最多等下一块这么久。 */
        private const val JOIN_WAIT = 220L

        /** 同一副嗓子接着念时补的那一点空。 */
        private const val BREATH = 50L

        const val CH = "reading"
        const val NID = 4711
        const val ACTION_STOP = "com.pagefold.reader.STOP"
        const val ACTION_TOGGLE = "com.pagefold.reader.TOGGLE"
        const val ACTION_NEXT = "com.pagefold.reader.NEXT_CH"
        const val ACTION_PREV = "com.pagefold.reader.PREV_CH"
        const val ACTION_RATE = "com.pagefold.reader.RATE"

        /** 通知上直接点的那几档念速。100 = 原速。 */
        private val RATES = intArrayOf(75, 100, 125, 150, 175, 200)

        const val EXTRA_RATE = "rate"
        const val EXTRA_STOP = "stop"

        val state = MutableStateFlow(PlayState())
        val flow: StateFlow<PlayState> get() = state

        @Volatile
        var holder: BookPlayer? = null
            private set

        /** 这一场停在哪。[src] 是章内字数偏移，跟 [PlayState.srcStart] 同一把尺子。 */
        data class Seat(val id: String, val chapter: Int, val src: Int)

        /**
         * 最后一次停下的位置。服务散了、状态清了，这一格还在 ——
         * 界面按播放时先问它，问不到才退回书架存档。
         */
        @Volatile
        var seat: Seat? = null
            private set

        fun forgetSeat() {
            seat = null
        }

        /** 整本读完（或用户停）时回调。 */
        var onChapterEnd: (() -> Unit)? = null

        /** 换章时回调，参数是整本书里的章号。 */
        var onChapter: ((Int) -> Unit)? = null

        var onProgress: ((index: Int, line: String) -> Unit)? = null

        /** 从界面往播放器塞一句话。没有 Service 实例时也能用。 */
        fun say(msg: String) {
            state.value = state.value.copy(error = msg)
        }

        /** 清掉状态里那句话。错误条四秒自己走，这个字段不清，回这一页会再贴一次。 */
        fun forget() {
            val cur = state.value
            if (cur.error != null) state.value = cur.copy(error = null)
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, BookPlayer::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        private var pending: ((BookPlayer) -> Unit)? = null

        fun run(ctx: Context, block: (BookPlayer) -> Unit) {
            val h = holder
            if (h != null) { block(h); return }
            pending = block
            start(ctx)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BookPlayer::class.java).setAction(ACTION_STOP))
        }

        /** 界面上的「不听了」：连通知一起收掉。没有实例也要把状态清干净。 */
        fun quit() {
            val h = holder
            if (h != null) h.finish() else state.value = PlayState()
        }

        /** 手上这一场念的是哪本书。空串 = 没有这一场。 */
        private fun currentId(): String = holder?.feed?.id.orEmpty()

        /**
         * 这一场是不是这本书。
         *
         * 用书 id 比，不用书名：同名两本、改过名的书，按书名比会认错，
         * 界面就会去认领别人的高亮。
         */
        fun owns(id: String): Boolean =
            id.isNotEmpty() && currentId() == id && state.value.book.isNotEmpty()

        /**
         * 这一场是不是这本书，且真的有东西可放。
         *
         * 比 [owns] 严一层：服务被系统回收又起来时，状态还在、`feed` 是空的，
         * 那时调 `resumeNow()` 什么也不会发生，界面就停在书页上一动不动。
         */
        fun holds(id: String): Boolean =
            id.isNotEmpty() && currentId() == id && state.value.total > 0
    }

    private val job = SupervisorJob()

    /** 兜住「谁都没接住」的异常。合成线程里有一处没包 runCatching 的调用炸了（取嗓子、段落判定、写账本），异常会直冲默认处理器。 */
    private val guard = CoroutineExceptionHandler { _, e ->
        /* 取消不是崩溃。换书、换章、点停止都会 cancel 掉这一场，正在 await 的地方会抛 CancellationException。 */
        if (e is CancellationException) return@CoroutineExceptionHandler
        val stack = android.util.Log.getStackTraceString(e)
        runCatching { File(filesDir, "last-crash-player.txt").writeText(stack) }
        state.value = state.value.copy(
            playing = false, loading = false,
            error = "念到一半断了：" + (e.message ?: e.javaClass.simpleName),
        )
    }

    private val scope = CoroutineScope(job + Dispatchers.Main.immediate + guard)
    private var player: MediaPlayer? = null
    private var loop: Job? = null
    private val lock = Any()

    private var feed: Feed? = null

    /** 这一本书自己的读法，JSON。 */
    @Volatile
    private var tuneJson: String = ""

    /** 现在真正生效的那一套：定音为底，本书的改动盖上去。 */
    private fun now(app: App): Settings {
        val base = app.field.now
        val j = tuneJson
        return if (j.isBlank()) base else com.pagefold.reader.field.Tune.apply(base, j)
    }
    private var settings: Settings = Settings()
    private var voices: List<Voice> = emptyList()
    private var chapterTitle: String = ""

    /** 系统那一层（锁屏、线控、媒体卡片）。 */
    private var media: MediaSession? = null

    /** 通知上那行字：正在念的这一句。跟界面高亮同源，换行才换。 */
    private var noteText: String = ""

    /** 被电话或者别的声音打断过。断了就别自己回来。 */
    private var resumeOnGain = false
    private var cacheOnly = false

    /** 备货的终点章。−1 = 按「往后先备几章」自己算。 */
    @Volatile private var prepEnd = -1
    @Volatile private var bookRate = 100

    /** 手动切句的目标：章节号 + 那一句在本章的字符起点。−1 = 没有待生效的目标。 */
    @Volatile private var seekCh = -1
    @Volatile private var seekTo = -1

    /**
     * 起播的那一行要从章内第几个字开始念。−1 = 从行首。
     *
     * 一行是认人的单位：一段旁白、一句引号，可能有好几句话。
     * 光给行号，声音会从这一段的头开始，按「下一句」听起来就是重念一整段。
     */
    @Volatile private var cutAt = -1

    /** 念到这一章就停在章末。−1 = 一直听。章号是本场的绝对章号。 */
    @Volatile private var stopAt = -1

    /** 这一场念的是哪一本。换了书才按设置重算 [stopAt]，同一本里跳句不动它。 */
    private var stopFor = ""

    /** 这一次暂停是按在句子中间，还是两句之间。决定「播放」是续半句还是念下一句。 */
    @Volatile private var pausedMidClip = false

    /** 下一句该念第几句。歇气的时候指向下一句，不在念的那一句。 */
    @Volatile private var nextIdx = 0

    @Volatile private var curCh = 0
    @Volatile private var gen = 0
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        holder = this
        /* 通知通道必须先于一切建好。 */
        ensureChannel()
        ensureMedia()
        runCatching {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        pending?.let { val act = it; pending = null; act(this) }
    }

    /** 保证通知通道存在。只在「没有」的时候建。 */
    private fun ensureChannel() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "朗读播放", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "念书时的播放控制"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                )
            }
        }
    }

    /**
     * 系统那一层知道这一场在被控制：锁屏、耳机线控、控制中心的媒体卡片都靠它。
     * 没有它，通知就只是一条普通通知，系统不会画传输控件。
     */
    private fun ensureMedia() {
        if (media != null) return
        val m = runCatching {
            MediaSession(this, "Quolune").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@BookPlayer, 0, Intent(this@BookPlayer, Main::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = resumeNow()
                    override fun onPause() = pauseNow()
                    override fun onSkipToNext() = seekLine(1)
                    override fun onSkipToPrevious() = seekLine(-1)
                    override fun onStop() = finish()
                })
                isActive = true
            }
        }.getOrNull() ?: return
        media = m
    }

    private fun syncMedia() {
        val m = media ?: return
        val st = state.value
        runCatching {
            m.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, feed?.book.orEmpty().ifBlank { "Quolune" })
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, chapterTitle.ifBlank { st.title })
                    .build()
            )
            val acts = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            val where = if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            m.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(acts)
                    .setState(where, PlaybackState.PLAYBACK_POSITION_UNKNOWN, bookRate / 100f)
                    .build()
            )
        }
    }

    /** 拔耳机、蓝牙断线：声音不该从外放里接着念。 */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseNow()
        }
    }

    private var am: AudioManager? = null
    private var focusReq: AudioFocusRequest? = null

    /** 来电、别的应用开声时不叠着念。被压低就压低，声音回来再抬高。 */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                pauseNow()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = state.value.playing
                pauseNow()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                runCatching { player?.setVolume(0.3f, 0.3f) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { player?.setVolume(1f, 1f) }
                if (resumeOnGain) {
                    resumeOnGain = false
                    resumeNow()
                }
            }
        }
    }

    private fun askFocus(): Boolean {
        val a = am ?: runCatching { getSystemService(AudioManager::class.java) }.getOrNull() ?: return true
        am = a
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusReq = it }
        return runCatching {
            a.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(true)
    }

    private fun dropFocus() {
        val a = am ?: return
        runCatching { focusReq?.let { a.abandonAudioFocusRequest(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { finish(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_TOGGLE -> {
                if (state.value.playing) pauseNow() else resumeNow()
                return START_STICKY
            }
            ACTION_NEXT -> { seekLine(1); return START_STICKY }
            ACTION_PREV -> { seekLine(-1); return START_STICKY }
            ACTION_RATE -> {
                setRate(intent.getIntExtra(EXTRA_RATE, bookRate))
                return START_STICKY
            }
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ensureChannel()
        runCatching {
            val note = runCatching { buildNote() }.getOrElse { plainNote() }
            if (Build.VERSION.SDK_INT >= 29) {
                // 带上类型：Android 10 起前台服务要跟清单里声明的类型对得上，
                // Android 14 起不带类型会直接抛异常。
                startForeground(NID, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NID, note)
            }
        }
    }

    private fun pi(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, BookPlayer::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun rateText(r: Int = bookRate): String {
        val v = r / 100f
        return if (v == v.toInt().toFloat()) "${v.toInt()}.0×" else "${v}×"
    }

    private fun piRate(r: Int): PendingIntent = PendingIntent.getService(
        this, 9000 + r,
        Intent(this, BookPlayer::class.java)
            .setAction(ACTION_RATE)
            .putExtra(EXTRA_RATE, r),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 收起态与锁屏共用一张版式：标题、章节、这一句、一排带字的键。
     * 锁屏只给一格高度，这张压在 100dp 以内，长按展开之前按键就在。
     */
    private fun compactView(
        res: android.content.res.Resources, title: String, ch: String, line: String, on: Boolean,
    ): RemoteViews {
        val ink = res.getColor(R.color.note_ink, theme)
        val dim = res.getColor(R.color.note_dim, theme)
        val paper = res.getColor(R.color.note_surface, theme)

        val v = RemoteViews(packageName, R.layout.note_compact)
        v.setTextViewText(R.id.nc_title, title)
        v.setTextColor(R.id.nc_title, ink)
        v.setTextViewText(R.id.nc_ch, ch)
        v.setTextColor(R.id.nc_ch, dim)
        v.setTextViewText(R.id.nc_line, line)
        v.setTextColor(R.id.nc_line, ink)

        v.setTextViewText(R.id.nc_prev, "上一句")
        v.setTextColor(R.id.nc_prev, ink)
        v.setTextViewText(R.id.nc_next, "下一句")
        v.setTextColor(R.id.nc_next, ink)
        v.setTextViewText(R.id.nc_close, "退出")
        v.setTextColor(R.id.nc_close, ink)
        v.setTextViewText(R.id.nc_toggle, if (on) "暂停" else "播放")
        v.setTextColor(R.id.nc_toggle, paper)

        v.setOnClickPendingIntent(R.id.nc_prev, pi(ACTION_PREV))
        v.setOnClickPendingIntent(R.id.nc_toggle, pi(ACTION_TOGGLE))
        v.setOnClickPendingIntent(R.id.nc_next, pi(ACTION_NEXT))
        v.setOnClickPendingIntent(R.id.nc_close, pi(ACTION_STOP))
        return v
    }

    private fun buildNote(): Notification {
        val back = Intent(this, Main::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        feed?.id?.takeIf { it.isNotBlank() }?.let { back.putExtra(Main.EXTRA_BOOK, it) }
        val open = PendingIntent.getActivity(
            this, 0, back,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val on = state.value.playing
        val res = createConfigurationContext(resources.configuration).resources
        val ink = res.getColor(R.color.note_ink, theme)
        val dim = res.getColor(R.color.note_dim, theme)
        val paper = res.getColor(R.color.note_surface, theme)

        val title = feed?.book?.ifBlank { "Quolune" } ?: "Quolune"
        val ch = chapterTitle.ifBlank { "朗读中" }
        val line = noteText.ifBlank { "准备中…" }

        val small = compactView(res, title, ch, line, on)

        val full = RemoteViews(packageName, R.layout.note_full)
        full.setTextViewText(R.id.nf_title, title)
        full.setTextColor(R.id.nf_title, ink)
        full.setTextViewText(R.id.nf_ch, ch)
        full.setTextColor(R.id.nf_ch, dim)
        full.setTextViewText(R.id.nf_line, line)
        full.setTextColor(R.id.nf_line, ink)

        full.setTextViewText(R.id.nf_prev, "上一句")
        full.setTextColor(R.id.nf_prev, ink)
        full.setTextViewText(R.id.nf_next, "下一句")
        full.setTextColor(R.id.nf_next, ink)
        full.setTextViewText(R.id.nf_close, "退出")
        full.setTextColor(R.id.nf_close, ink)
        full.setTextViewText(R.id.nf_toggle, if (on) "暂停" else "播放")
        full.setTextColor(R.id.nf_toggle, paper)

        full.setOnClickPendingIntent(R.id.nf_prev, pi(ACTION_PREV))
        full.setOnClickPendingIntent(R.id.nf_toggle, pi(ACTION_TOGGLE))
        full.setOnClickPendingIntent(R.id.nf_next, pi(ACTION_NEXT))
        full.setOnClickPendingIntent(R.id.nf_close, pi(ACTION_STOP))

        /* 五档一字排开，点哪个是哪个。当前那档朱红实底、字取纸色。 */
        val slots = intArrayOf(R.id.nf_r1, R.id.nf_r2, R.id.nf_r3, R.id.nf_r4, R.id.nf_r5)
        for ((i, id) in slots.withIndex()) {
            val r = RATES[i]
            full.setTextViewText(id, rateText(r))
            full.setInt(
                id, "setBackgroundResource",
                if (r == bookRate) R.drawable.note_rate_on else R.drawable.note_rate_off
            )
            full.setTextColor(id, if (r == bookRate) paper else dim)
            full.setOnClickPendingIntent(id, piRate(r))
        }

        /*
         * 锁屏那一份不能靠自绘：MIUI 只给锁屏几个行高，底下那排按键正好被裁掉。
         * 所以主通知挂 MediaStyle —— 锁屏与快捷设置的媒体区由系统画，三个键一定在；
         * 下拉栏仍是自绘纸卡，因为 customContentView 的优先级高于样式。
         */
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(title)
            .setContentText(if (ch.isBlank()) line else "$ch　$line")
            .setCustomContentView(small)
            .setCustomBigContentView(full)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(media?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            /* 通知整块染成当页纸色。自绘的那张不铺底，边上就不会留出一圈白。 */
            .setColor(paper)
            .setColorized(true)
            .setContentIntent(open)
            .setDeleteIntent(pi(ACTION_STOP))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_act_prev),
                    "上一句", pi(ACTION_PREV)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, if (on) R.drawable.ic_act_pause else R.drawable.ic_act_play
                    ),
                    if (on) "暂停" else "继续", pi(ACTION_TOGGLE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_act_next),
                    "下一句", pi(ACTION_NEXT)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_act_close),
                    "退出", pi(ACTION_STOP)
                ).build()
            )
            .build()
    }

    /**
     * 发通知只走这里。自绘版遇到 RemoteViews 不认的方法会抛 RemoteServiceException，
     * 系统直接杀进程。构造失败退回最朴素的版本。
     */
    private fun postNote() {
        val n = runCatching { buildNote() }.getOrElse { plainNote() }
        runCatching { getSystemService(NotificationManager::class.java).notify(NID, n) }
    }

    private fun plainNote(): Notification {
        val on = state.value.playing
        val title = feed?.book?.ifBlank { "Quolune" } ?: "Quolune"
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(title)
            .setContentText(noteText.ifBlank { chapterTitle })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(Notification.PRIORITY_LOW)
        b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_act_prev),
                "上一句", pi(ACTION_PREV)
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, if (on) R.drawable.ic_act_pause else R.drawable.ic_act_play
                ),
                if (on) "暂停" else "继续", pi(ACTION_TOGGLE)
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_act_next),
                "下一句", pi(ACTION_NEXT)
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_act_close),
                "退出", pi(ACTION_STOP)
            ).build()
        )
        return b.build()
    }

    // ───────────────────────── 对外 ─────────────────────────

    /** 从整本书的第 chapter 章、第 from 句开始念。 */
    fun play(
        f: Feed,
        chapter: Int,
        from: Int,
        s: Settings,
        all: List<Voice>,
        cacheOnlyMode: Boolean = false,
        tune: String = "",
        /** 只合成不播时，备到这一章为止。−1 = 按「往后先备几章」自己算。 */
        stopCh: Int = -1,
    ) {
        gen++
        tuneJson = tune
        val my = gen
        feed = f
        curCh = chapter.coerceIn(0, (f.count - 1).coerceAtLeast(0))
        settings = s
        voices = all
        cacheOnly = cacheOnlyMode
        prepEnd = stopCh
        bookRate = when {
            f.rate in 50..300 -> f.rate
            s.rate in 50..300 -> s.rate
            else -> 100
        }
        paused = false
        /* 换了书才重算：同一本里按上一句、跳下一章，不该把用户选的「听几章」冲掉。 */
        if (stopFor != f.id) {
            stopFor = f.id
            val n = s.stopChapters.coerceIn(0, 999)
            stopAt = if (n > 0) (curCh + n - 1).coerceAtLeast(curCh) else -1
        }
        loop?.cancel()
        /* 上一本的声音先掐掉。只 gen++ 不够：那一段是 MediaPlayer 自己在走。
         * 摘监听要排在 stop 前面：stop 会触发完成回调，旧那场会当成「放完了」接着往下走。 */
        runCatching { player?.setOnCompletionListener(null) }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        // 一副嗓子都没开就别往下走了。硬拿写死的名字去念，只会静悄悄失败。
        if (voices.isEmpty()) {
            state.value = state.value.copy(
                playing = false, loading = false,
                error = "一副嗓音都没开。去定音里开一路声源，再来开讲。"
            )
            /* 上一场的通知还挂着，它的键指向已经散掉的那一场。 */
            runCatching { stopForeground(true) }
            return
        }
        nextIdx = from.coerceAtLeast(0)
        /* 起播截断只对这一次 play 有效，取走就清，免得下一次莫名其妙少一截。 */
        val cut = cutAt
        cutAt = -1
        // 备货跑完会撤掉通知；开讲前重新挂前台，不然锁屏就停。
        askFocus()
        if (!cacheOnlyMode) goForeground()
        loop = scope.launch {
            runCatching { run(my, curCh, from.coerceAtLeast(0), cut) }.onFailure { e ->
                /* 取消不是崩溃，也不是「断了」。 */
                if (e is CancellationException) return@onFailure
                val stack = android.util.Log.getStackTraceString(e)
                runCatching { File(filesDir, "last-crash-player.txt").writeText(stack) }
                state.value = state.value.copy(
                    playing = false, loading = false,
                    error = "念到一半断了：" + (e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /** 换倍速。正在念的这一块也立刻跟着变，不用等下一块。 */
    fun setRate(r: Int) {
        val v = r.coerceIn(50, 300)
        bookRate = v
        feed?.rate = v
        val p = player
        if (p != null) runCatching { applyRate(p, curExtra) }
        syncMedia()
        postNote()
    }

    /** 这一块自己那份语速增减，百分比。由块的语气定。 */
    @Volatile private var curExtra = 0

    /** 这台机器能放到多快。头一次设不上去就记下来，之后不再试。 */
    private var topSpeed = 3f

    /**
     * 设这一块的播放速度。设不上去的机型不抛异常，只是不改，所以设完读回来比一比，
     * 不对就往下退，退到原速。这件事不往界面上报。
     */
    private fun applyRate(mp: MediaPlayer, extra: Int = 0) {
        val r = bookRate
        if (r !in 50..300) return
        val mix = (100 + extra.coerceIn(-45, 60)).coerceIn(55, 160)
        val want = (r / 100f * mix / 100f).coerceIn(0.5f, 3f).coerceAtMost(topSpeed)

        fun put(v: Float): Float? = runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed(v).setPitch(1f)
            mp.playbackParams.speed
        }.getOrNull()

        fun ok(got: Float?, v: Float): Boolean =
            got == null || got <= 0f || Math.abs(got - v) <= v * 0.06f + 0.02f

        if (ok(put(want), want)) return

        /* 退一档再试。两倍是多数机型的顶，还不行就按原速。 */
        topSpeed = 2f
        val back = want.coerceAtMost(2f)
        if (ok(put(back), back)) return
        topSpeed = 1f
        put(1f)
    }

    /** 把「停在哪一句」记进座位。服务散了这格还在，界面按播放靠它回到原处。 */
    private fun markSeat() {
        val f = feed ?: return
        val st = state.value
        if (st.srcStart < 0) return
        if (f.id.isBlank()) return
        seat = Seat(f.id, st.chapter, st.srcStart)
    }

    /** 把「念到哪儿」写出去。节流 4 秒（念一句写一次文件没必要），换章、暂停、停下、销毁时强制写。 */
    private var lastPosAt = 0L
    private fun savePos(force: Boolean = false) {
        val f = feed ?: return
        val st = state.value
        if (st.srcStart < 0 || st.total <= 0) return
        markSeat()
        val now = System.currentTimeMillis()
        if (!force && now - lastPosAt < 4000L) return
        lastPosAt = now
        val c = st.chapter
        val off = st.srcStart
        scope.launch(Dispatchers.IO) {
            runCatching { f.onPos?.invoke(c, off) }
            runCatching { Shortcuts.refresh(this@BookPlayer) }
        }
    }

    /** 同步写一次位置。调用方 [finish] / [onDestroy]，要赶在 `scope.cancel()` 之前。 */
    private fun savePosNow() {
        val f = feed ?: return
        val st = state.value
        if (st.srcStart < 0 || st.total <= 0) return
        markSeat()
        runCatching { f.onPos?.invoke(st.chapter, st.srcStart) }
    }

    fun pauseNow() {
        paused = true
        // 记住「是按在句子中间停的」。只有这种情况，按播放才是「接着这句的后半段」。
        // 一句已经念完、正等着下一句的时候按播放，得往前走，不能把上一句重念一遍。
        pausedMidClip = runCatching { player?.isPlaying == true }.getOrDefault(false)
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        state.value = state.value.copy(playing = false)
        updateNote()
        // 暂停这一下必须落档：睡眠定时就是走这条路停的。
        savePos(force = true)
    }

    /**
     * 接着念。落点从 [PlayState] 的句坐标算，不拿块的行号配句的偏移 ——
     * 两者常常不在一行，拼起来就是从这一段的头重念。
     */
    fun resumeNow() {
        val f = feed
        val p = player
        if (p != null && runCatching { p.isPlaying }.getOrDefault(false)) {
            if (!state.value.playing) state.value = state.value.copy(playing = true)
            return
        }
        /* 按在句子中间：那一条音频还在，接着放就是了。 */
        if (p != null && pausedMidClip && runCatching { !p.isPlaying }.getOrDefault(false)) {
            paused = false
            pausedMidClip = false
            askFocus()
            runCatching { p.start() }
            state.value = state.value.copy(playing = true)
            updateNote()
            return
        }
        if (f == null) {
            /* 服务被系统收走了，这一场已经散掉。别说成「播了没声」，让界面重新起一场。 */
            state.value = state.value.copy(
                playing = false, loading = false,
                error = "这一场已经散了，回书房点一次接着听",
            )
            return
        }
        val st = state.value
        val over = st.total > 0 && st.index >= st.total
        val ch = if (over) (st.chapter + 1).coerceAtMost((f.count - 1).coerceAtLeast(0)) else st.chapter
        val off = if (over) 0 else st.srcStart.coerceAtLeast(0)
        runCatching { p?.release() }
        synchronized(lock) { if (player === p) player = null }
        paused = false
        pausedMidClip = false
        scope.launch {
            val lines = withContext(Dispatchers.IO) { f.linesOf(ch) }
            if (lines.isEmpty()) return@launch
            val sents = withContext(Dispatchers.Default) { Sentences.of(lines) }
            val at = sents.getOrNull(Sentences.at(sents, off))
            if (at == null) return@launch
            val line = lines.indexOfLast { it.srcStart <= at.srcStart }.coerceAtLeast(0)
            cutAt = at.srcStart
            play(f, ch, line, settings, voices, cacheOnly)
        }
    }

    fun stopNow() {
        gen++
        paused = false
        loop?.cancel()
        loop = null
        runCatching { player?.stop(); player?.release() }
        player = null
        state.value = PlayState()
    }

    fun jumpChapter(delta: Int) {
        val f = feed ?: return
        seekTo = -1
        cutAt = -1
        val target = (curCh + delta).coerceIn(0, (f.count - 1).coerceAtLeast(0))
        play(f, target, 0, settings, voices, cacheOnly)
    }

    /**
     * 上一句 / 下一句，走句子这一层。连着按时接着上一次的目标往下算。
     * 暂停着按只挪位置，不出声，按播放时从目标句开口。
     */
    fun seekLine(delta: Int) {
        val f = feed ?: return
        val speaking = state.value.playing
        scope.launch {
            var c = curCh
            var lines = withContext(Dispatchers.IO) { f.linesOf(c) }
            if (lines.isEmpty()) return@launch
            var sents = withContext(Dispatchers.Default) { Sentences.of(lines) }
            val base = if (seekCh == c && seekTo >= 0) seekTo else state.value.srcStart.coerceAtLeast(0)
            var to = Sentences.at(sents, base) + delta

            if (to < 0) {
                if (c <= 0) return@launch
                c -= 1
                lines = withContext(Dispatchers.IO) { f.linesOf(c) }
                sents = withContext(Dispatchers.Default) { Sentences.of(lines) }
                if (sents.isEmpty()) return@launch
                to = sents.size - 1
            } else if (to >= sents.size) {
                if (c + 1 >= f.count) return@launch
                c += 1
                lines = withContext(Dispatchers.IO) { f.linesOf(c) }
                sents = withContext(Dispatchers.Default) { Sentences.of(lines) }
                if (sents.isEmpty()) return@launch
                to = 0
            }

            val want = sents[to]
            seekCh = c
            seekTo = want.srcStart

            if (!speaking) {
                runCatching { player?.release() }
                synchronized(lock) { player = null }
                pausedMidClip = false
                if (c != curCh) {
                    curCh = c
                    chapterTitle = f.titleOf(c)
                    onChapter?.invoke(c)
                }
                /* index 归零：它跟本章的块数配对，跨章之后那个数已经不是这一章的。 */
                state.value = state.value.copy(
                    chapter = c, srcStart = want.srcStart, srcEnd = want.srcEnd,
                    current = want.text, index = 0, loading = false,
                )
                seat = Seat(f.id, c, want.srcStart)
                runCatching { f.onPos?.invoke(c, want.srcStart) }
                updateNote(want.text)
                return@launch
            }

            val line = lines.indexOfLast { it.srcStart <= want.srcStart }.coerceAtLeast(0)
            cutAt = want.srcStart
            play(f, c, line, settings, voices, cacheOnly)
        }
    }

    /** 连听几章后停。[n] = 0 一直听，含此刻这一章：1 就是念完本章停。 */
    fun setStopChapters(n: Int) {
        stopAt = if (n > 0) (curCh + n - 1).coerceAtLeast(curCh) else -1
        postNote()
    }

    /**
     * 把 [lines] 第 [at] 行从章内第 [from] 个字处截断，后面的行原样保留。
     * 越界、已经贴到行首都不切 —— 宁可从行首念，也不要抛。
     */
    private fun cutFirst(lines: List<Line>, at: Int, from: Int): List<Line> {
        if (lines.isEmpty()) return lines
        val k = at.coerceIn(0, lines.size - 1)
        val head = lines[k]
        val base = if (head.srcStart >= 0) head.srcStart else 0
        val cut = (from - base).coerceIn(0, head.text.length)
        if (cut <= 0) return lines
        val rest = head.text.substring(cut)
        val lead = rest.length - rest.trimStart().length
        val body = rest.trimStart()
        if (body.isEmpty()) return lines
        return lines.toMutableList().also {
            it[k] = head.copy(text = body, srcStart = base + cut + lead)
        }
    }

    private fun finish() {
        markSeat()
        savePosNow()
        stopNow()
        runCatching { stopForeground(true) }
        dropFocus()
        stopSelf()
    }

    override fun onDestroy() {
        savePosNow()
        stopNow()
        runCatching { unregisterReceiver(noisy) }
        runCatching { media?.isActive = false; media?.release() }
        media = null
        dropFocus()
        if (holder === this) holder = null
        scope.cancel()
        super.onDestroy()
    }

    // ───────────────────────── 整本串起来 ─────────────────────────

    private suspend fun run(my: Int, startCh: Int, from: Int, cut: Int) {
        val app = applicationContext as App
        val f = feed ?: return
        var c = startCh
        var at = from
        var note = "已停"
        var whole = false
        var cached = 0

        while (my == gen && c < f.count) {
            val lines = withContext(Dispatchers.IO) { f.linesOf(c) }
            if (my != gen) return
            if (lines.isEmpty()) { c++; at = 0; continue }

            curCh = c
            chapterTitle = f.titleOf(c)
            onChapter?.invoke(c)
            /* 起播那一章由 [chapterLoop] 一次写好整份状态，这儿不插一手，免得高亮先灭一下。
             * 换章要先把上一章的位置落盘，所以先清成 -1 再存。 */
            if (c != startCh) {
                state.value = state.value.copy(chapter = c, srcStart = -1)
                savePos(force = true)
            }

            /* 从句子中间起念：把起播那一行的前半截切掉，声音才是从这一句开口，
             * 而不是从这一段的头重念。只在第一轮用一次，后面各章都从行首。 */
            val body = if (cut >= 0 && c == startCh) cutFirst(lines, at, cut) else lines

            val done = chapterLoop(my, body, at)
            if (my != gen) return
            if (!done) return

            // 只合成不播：备到指定那一章为止；没指定就按「往后先备几章」。
            if (cacheOnly) {
                cached++
                val stop = prepEnd
                if (stop >= 0) {
                    if (c >= stop) { note = "备好了 $cached 章"; break }
                } else {
                    val want = runCatching { now(app).cacheChapters }.getOrDefault(3).coerceIn(1, 30)
                    if (cached >= want) { note = "备好了 $cached 章"; break }
                }
                if (c + 1 >= f.count) { note = "已经备到全书的最后一章"; break }
                c++
                at = 0
                continue
            }
            if (c + 1 >= f.count) { note = "全书读完"; whole = true; break }

            val stop = stopAt
            if (stop >= 0 && c >= stop) { note = "听完这一章，停了"; break }
            c++
            at = 0
        }

        if (my != gen) return
        state.value = state.value.copy(playing = false, loading = false)
        // 备完就别挂通知了 —— 它不是一场正在念的书。
        if (cacheOnly) {
            runCatching { stopForeground(true) }
            state.value = state.value.copy(error = note)
            return
        }
        updateNote(note)
        if (whole) state.value = state.value.copy(error = null)
        onChapterEnd?.invoke()
    }

    // ───────────────────────── 一章：边播边攒 ─────────────────────────

    /** 返回 true = 这一章完整念完了。 */
    private suspend fun chapterLoop(my: Int, lines: List<Line>, fromLine: Int): Boolean {
        val app = applicationContext as App
        val s = settings
        val synth = app.synth

        val conc = s.concurrency.coerceIn(1, 10)
        val gapMs = s.reqIntervalMs.coerceIn(0, 5000).toLong()
        val charBudget = s.prefetchChars.coerceIn(600, 12000)
        val retries = s.retry.coerceIn(0, 5)
        val holdMs = s.gapMs.coerceIn(0, 3000).toLong()

        val blocks = blocksOf(lines, fromLine)
        if (blocks.isEmpty()) return true
        /* 显示与跳转都按句子走。整章切一次，tracer 里二分定位，不再按行报位置。 */
        val sents = Sentences.of(lines)
        var i = 0

        /* 起播前刚说过的那句话留着。章一开就把状态整个换掉，那句话会在几十毫秒里
         * 闪一下就没，谁也看不清。 */
        val kept = state.value.error
        /* 起播这一句先摆上去：合成还要等几秒，这期间界面和通知都该停在这一句上，
         * 不能空着等声音。从中间起念时取起播那一行所在的句，不取章首。 */
        val headOff = lines.getOrNull(fromLine.coerceIn(0, lines.size - 1))?.srcStart ?: 0
        val head = sents.getOrNull(Sentences.at(sents, headOff.coerceAtLeast(0)))
        state.value = PlayState(
            playing = true, loading = true, index = i, total = blocks.size,
            title = chapterTitle, current = head?.text.orEmpty(), chapter = curCh,
            cachedOnly = cacheOnly,
            srcStart = head?.srcStart ?: -1,
            srcEnd = head?.srcEnd ?: -1,
            book = feed?.book.orEmpty(),
        ).copy(error = kept)
        updateNote(head?.text.orEmpty())

        val slots = ConcurrentHashMap<Int, CompletableDeferred<Clip?>>()
        /** 这一块是哪个音色念的。播完记账要用。 */
        val said = ConcurrentHashMap<Int, String>()
        val queue = Channel<Int>(Channel.UNLIMITED)
        val gate = Mutex()
        var nextAt = 0L

        suspend fun throttle() {
            if (gapMs <= 0) return
            gate.withLock {
                val wait = nextAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                nextAt = System.currentTimeMillis() + gapMs
            }
        }

        /*
         * 块的语气在排进队列那一刻定下：主循环按块序走，前一块算过才有得比。
         * 只抹平不改判——相邻块差得太远，人听得出来一顿一顿。
         */
        val reads = arrayOfNulls<Reading>(blocks.size)
        var emaAt = -1
        var emaRate = 0f
        var emaPitch = 0f
        var emaVol = 0f

        fun cueAt(k: Int, live: Settings): Reading {
            reads[k]?.let { return it }
            val b = blocks[k]
            val prev = if (k > 0) blocks[k - 1].text else null
            val after = if (k + 1 < blocks.size) blocks[k + 1].text else null
            val style = lines[b.from].style.ifBlank {
                if (b.dialog) live.dialogStyle.trim() else live.narratorStyle.trim()
            }
            val raw = runCatching {
                Cue.of(b.text, prev, after, live.style, !live.cue, style, b.dialog)
            }.getOrElse { Reading(Reading.Kind.NARRATE, 0, 0, 0, 300, style) }

            if (emaAt >= 0 && k == emaAt + 1) {
                emaRate = SMOOTH * raw.rate + (1f - SMOOTH) * emaRate
                emaPitch = SMOOTH * raw.pitch + (1f - SMOOTH) * emaPitch
                emaVol = SMOOTH * raw.volume + (1f - SMOOTH) * emaVol
            } else {
                emaRate = raw.rate.toFloat()
                emaPitch = raw.pitch.toFloat()
                emaVol = raw.volume.toFloat()
            }
            emaAt = k
            val out = raw.copy(
                rate = Math.round(emaRate).coerceIn(-45, 60),
                pitch = Math.round(emaPitch).coerceIn(-30, 30),
                volume = Math.round(emaVol).coerceIn(-18, 18),
            )
            reads[k] = out
            return out
        }

        val workers = (0 until conc).map {
            scope.launch(Dispatchers.IO) {
                for (bi in queue) {
                    val slot = slots[bi] ?: continue
                    val block = blocks[bi]
                    val read = reads[bi] ?: cueAt(bi, s)
                    var clip: Clip? = null
                    var who = ""
                    var err: Throwable? = null
                    for (round in 0..retries) {
                        if (my != gen) break
                        val live = runCatching { now(app) }.getOrDefault(s)
                        val v = runCatching { pickVoice(lines[block.from], live, voices) }.getOrNull()
                        if (v == null) { err = java.io.IOException("没有可用的嗓子"); break }
                        throttle()
                        if (my != gen) break
                        /* 同一段原样重发只会拿回同一段坏音频。第二趟起压温度。 */
                        val one = if (round == 0) null
                        else com.pagefold.reader.field.Vt(temp = (50 / (round + 1)).coerceAtLeast(10))
                        val r = runCatching { synth.make(live, v, block.text, read, one = one) }
                        if (r.isSuccess) { clip = r.getOrNull(); who = v.name; err = null; break }
                        err = r.exceptionOrNull()
                        if (round < retries) delay(s.retryDelayMs.toLong().coerceIn(200, 5000))
                    }
                    if (clip != null) {
                        if (who.isNotEmpty()) said[bi] = who
                        slot.complete(clip)
                    } else slot.completeExceptionally(err ?: java.io.IOException("合成失败"))
                }
            }
        }

        var windowEnd = i
        var skipped = 0
        var failStreak = 0
        var lastErr: Throwable? = null
        var aborted = false

        /* 开播一次发满，之后降到一半再补齐：请求成簇发出，不逐块去抢闸门。
         *
         * 按字数算窗口，不按块数：「先备多少字」就是往后攒多少字的音频，
         * 块大好几十块，块小好几百块，用户看的一直是字数。
         */
        fun fill(live: Settings) {
            var have = 0L
            for (k in i until windowEnd) have += blocks[k].text.length
            if (have > charBudget / 2) return
            var acc = have
            var to = windowEnd
            while (to < blocks.size && acc < charBudget) {
                if (to >= windowEnd) {
                    cueAt(to, live)
                    slots[to] = CompletableDeferred()
                    queue.trySend(to)
                }
                acc += blocks[to].text.length
                to++
            }
            windowEnd = to
        }

        try {
            while (my == gen && i < blocks.size) {
                if (paused) { delay(120); continue }
                if (Sleep.fired()) { Sleep.stop(); pauseNow(); state.value = state.value.copy(sleepLeft = 0); return false }
                val live = runCatching { now(app) }.getOrDefault(s)
                fill(live)

                val block = blocks[i]
                /* 块最长六百字，拿它当区间会先亮一整段，等声音起来才收窄到一句。 */
                val at0 = lines[block.from].srcStart.coerceAtLeast(0)
                val s0 = sents.getOrNull(Sentences.at(sents, at0))
                if (my != gen) return false
                state.value = state.value.copy(
                    index = i, current = s0?.text ?: block.text.take(60), loading = true,
                    srcStart = s0?.srcStart ?: lines[block.from].srcStart,
                    srcEnd = s0?.srcEnd ?: lines[block.to - 1].srcEnd,
                )
                savePos()

                /* 同一段正文被切了两遍时只念一次。 */
                if (i > 0 && block.text == blocks[i - 1].text &&
                    lines[block.from].srcStart == lines[blocks[i - 1].from].srcStart
                ) {
                    i++
                    nextIdx = if (i < blocks.size) blocks[i].from else lines.size
                    continue
                }

                val read = cueAt(i, live)

                val clip: Clip? = try {
                    slots[i]?.await()
                } catch (e: CancellationException) {
                    /* 这一场被取消了（换书、停止、换章）。不是「这块念不出来」，别记成失败。 */
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    null
                }
                if (my != gen) return false
                if (clip == null) {
                    skipped++
                    failStreak++
                    i++
                    nextIdx = if (i < blocks.size) blocks[i].from else lines.size
                    /* 连着两块都出不来，说明不是偶发。停下来说清楚，别一路跳过去。 */
                    if (failStreak >= 2) {
                        state.value = state.value.copy(
                            playing = false, loading = false,
                            error = Fail.read(lastErr)
                        )
                        aborted = true
                        break
                    }
                    state.value = state.value.copy(error = "这一块跳过去了：" + Fail.read(lastErr))
                    continue
                }
                failStreak = 0

                if (cacheOnly) {
                    i++
                    nextIdx = if (i < blocks.size) blocks[i].from else lines.size
                    state.value = state.value.copy(index = i)
                    continue
                }

                /* 往后接几块。已经好了就接，快好了等一小会儿，
                 * 好在一次起播里连着放完，中间不起第二个播放器。
                 * 不比嗓子：两个人一来一回，正是断得最厉害的地方。 */
                val group = ArrayList<Int>(Join.MAX_PARTS)
                group.add(i)
                var bytes = clip.file.length()
                var k = i + 1
                while (group.size < Join.MAX_PARTS && k < blocks.size) {
                    val d = slots[k] ?: break
                    /* 只等第一块后面的那一块：组里一块都没有时，等这一下不算白等；
                     * 已经接上几块了就不再等，宁可这一组短些，也别把上一组播完了还干等着。 */
                    val nc = runCatching {
                        when {
                            d.isCompleted -> d.getCompleted()
                            group.size == 1 -> withTimeoutOrNull(JOIN_WAIT) { d.await() }
                            else -> null
                        }
                    }.getOrNull() ?: break
                    if (bytes + nc.file.length() > Join.MAX_BYTES) break
                    if (!Join.same(clip, nc)) break
                    group.add(k)
                    bytes += nc.file.length()
                    k++
                }

                val marks = ArrayList<Long>()
                val joined = if (group.size > 1) {
                    val parts = ArrayList<Clip>(group.size)
                    for (g in group) {
                        val c = runCatching { slots[g]?.getCompleted() }.getOrNull()
                        if (c == null) { parts.clear(); break }
                        parts.add(c)
                    }
                    if (parts.size == group.size)
                        withContext(Dispatchers.IO) {
                            Join.of(
                                parts,
                                File(synth.pendingDir, "join-" + System.nanoTime().toString(36) + ".${clip.ext}"),
                                marks,
                            )
                        }
                    else null
                } else null

                /* 接起来的那一条里，界面上那一行要跟着声音走。段落边界按字节比例摊到每块，
                 * 块内按字匀速插值 —— 一块最多几百字，插出来的位置差不了几个字。 */
                /* 界面那一行要跟着声音走，落回**那一行**上：整块高亮会随念字往后缩，
                 * 看着像字一个个消失。 */
                val marksAt = marks
                val tracer: (Float) -> Unit = { f ->
                    var g = 0
                    var inner = f
                    if (joined != null && marksAt.size == group.size + 1) {
                        val total = marksAt.last().toFloat()
                        val at = f * total
                        while (g < group.size - 1 && at > marksAt[g]) g++
                        val lo = if (g == 0) 0f else marksAt[g - 1].toFloat()
                        val hi = marksAt[g].toFloat()
                        inner = if (hi > lo) ((at - lo) / (hi - lo)).coerceIn(0f, 1f) else 0f
                    }
                    val b = blocks[group[g]]
                    val first = lines[b.from].srcStart
                    val last = lines[b.to - 1].srcEnd
                    val now = if (last > first) (first + (last - first) * inner).toInt() else first
                    val si = Sentences.at(sents, now)
                    if (si >= 0) {
                        val sent = sents[si]
                        state.value = state.value.copy(
                            index = group[g],
                            srcStart = sent.srcStart,
                            srcEnd = sent.srcEnd,
                        )
                        /* 通知上那行字跟着这一句走。换句才换，不按秒刷。 */
                        if (sent.text != noteText) updateNote(sent.text)
                        if (seekTo >= 0 && sent.srcStart >= seekTo) seekTo = -1
                    }
                }

                /* 接起来的一条只能一个速度。取组内平均，别让第一块把整组带偏。 */
                val groupRate = if (group.size > 1) {
                    var sum = 0
                    for (g in group) sum += (reads[g] ?: read).rate
                    sum / group.size
                } else read.rate

                val ok = withContext(Dispatchers.Main) {
                    awaitPlay((joined ?: clip.file).absolutePath, groupRate, tracer) { my == gen }
                }
                runCatching { joined?.delete() }
                if (my != gen) return false
                if (!ok) {
                    skipped++
                    state.value = state.value.copy(error = "这一块放了没声，跳过去了")
                } else {
                    group.forEach { g ->
                        val c = runCatching { slots[g]?.getCompleted() }.getOrNull() ?: return@forEach
                        runCatching { synth.notePlayed(c, blocks[g].text, said[g].orEmpty()) }
                    }
                }

                /* 先推进游标再歇。反过来在歇的途中按暂停，记下的还是刚念完那一块，按播放会再念一遍。 */
                group.forEach { slots.remove(it) }
                i = k
                nextIdx = if (i < blocks.size) blocks[i].from else lines.size
                state.value = state.value.copy(index = i)
                onProgress?.invoke(blocks[k - 1].from, blocks[k - 1].text)

                /* 接起来那一条已经连着放完了，这里只补一点点，别让它整片黏死。 */
                val hold = when {
                    !live.cue -> holdMs
                    k < blocks.size && blocks[k].voice == block.voice -> BREATH
                    else -> read.pauseMs.toLong() / 2
                }
                if (hold > 0) delay(hold)
            }
        } finally {
            runCatching { queue.close() }
            workers.forEach { runCatching { it.cancel() } }
            slots.clear()
        }

        if (my != gen) return false
        if (aborted) return false
        if (cacheOnly) {
            state.value = PlayState(playing = false, total = blocks.size, title = chapterTitle)
                .copy(error = "已缓存 ${blocks.size} 块，跳过 $skipped 块")
            return true
        }
        return i >= blocks.size
    }
    /** 播放一个文件并等它播完。用 prepareAsync，不卡主线程。 */
    private suspend fun awaitPlay(
        path: String,
        extra: Int = 0,
        /**
         * 每 180 毫秒报一次放到了这一条的百分之几。拼接起来的几条要靠它推进界面上那一行。
         */
        tick: ((Float) -> Unit)? = null,
        /** 这一场还是不是当前的。服务换了场次之后，旧的那一条不该再往 [state] 里写。 */
        live: () -> Boolean = { true },
    ): Boolean {
        val mp = MediaPlayer()
        synchronized(lock) { player = mp }
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(path)
            val ready = suspendCancellableCoroutine<Boolean> { cont ->
                var settled = false
                fun settle(ok: Boolean) {
                    if (settled) return
                    settled = true
                    runCatching { cont.resume(ok) }
                }
                mp.setOnPreparedListener { settle(true) }
                mp.setOnErrorListener { _, _, _ -> settle(false); true }
                runCatching { mp.prepareAsync() }.onFailure { settle(false) }
            }
            if (!ready) {
                state.value = state.value.copy(error = "这一块解不开", loading = false)
                return false
            }
            curExtra = extra
            if (paused) {
                runCatching { mp.release() }
                synchronized(lock) { if (player === mp) player = null }
                return true
            }
            pausedMidClip = false
            mp.start()
            /* start 之后再设：没开始放的时候设进去，有的机型不认，读回来还是原速。 */
            applyRate(mp, extra)
            if (live()) {
                state.value = state.value.copy(playing = true, loading = false)
                updateNote()
            }
            coroutineScope {
                val watch = if (tick == null) null else launch {
                    while (isActive) {
                        if (!paused && live()) {
                            val d = runCatching { mp.duration }.getOrDefault(-1)
                            if (d > 0) {
                                val at = runCatching { mp.currentPosition }.getOrDefault(0)
                                runCatching { tick((at.toFloat() / d).coerceIn(0f, 1f)) }
                            }
                        }
                        delay(180)
                    }
                }
                try {
                    suspendWhilePlaying(mp)
                } finally {
                    watch?.cancel()
                }
            }
            runCatching { mp.reset(); mp.release() }
            synchronized(lock) { if (player === mp) player = null }
            true
        } catch (e: CancellationException) {
            runCatching { mp.release() }
            synchronized(lock) { if (player === mp) player = null }
            throw e
        } catch (e: Exception) {
            state.value = state.value.copy(error = "这一块放不出来：${e.message?.take(40)}", loading = false)
            runCatching { mp.release() }
            synchronized(lock) { if (player === mp) player = null }
            false
        }
    }

    private suspend fun suspendWhilePlaying(mp: MediaPlayer) =
        suspendCancellableCoroutine<Unit> { cont ->
            var done = false
            fun finishOnce() {
                if (!done) { done = true; runCatching { cont.resume(Unit) } }
            }
            mp.setOnCompletionListener { finishOnce() }
            mp.setOnErrorListener { _, _, _ -> finishOnce(); true }
            cont.invokeOnCancellation { runCatching { mp.stop() } }
        }

    // ───────────────────────── 音色 ─────────────────────────

    /** 这一句交给谁念。关键：all 是开播那一刻抓的音色表。 */
    private fun pickVoice(line: Line, s: Settings, all: List<Voice>): Voice {
        val named = line.voice.ifBlank { if (line.isDialog) s.defaultVoice else s.narratorVoice }

        Catalog.byName(all, named)?.let { return it }

        val now = runCatching { (application as App).field.now }.getOrDefault(s)
        val fresh = runCatching {
            Catalog.ours(now.engines, (application as App).box.custom())
        }.getOrDefault(emptyList())

        return Catalog.byName(fresh, named)
            ?: Catalog.byName(all, s.defaultVoice)
            ?: Catalog.byName(fresh, now.defaultVoice)
            ?: fresh.firstOrNull()
            ?: all.firstOrNull()
            ?: Catalog.MIMO.first()
    }

    private fun updateNote(text: String? = null) {
        if (text != null) noteText = text
        postNote()
        syncMedia()
    }
}
