package com.pagefold.reader.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pagefold.reader.App
import com.pagefold.reader.Main
import com.pagefold.reader.R
import com.pagefold.reader.book.Cue
import com.pagefold.reader.book.Line
import com.pagefold.reader.book.Reading
import com.pagefold.reader.field.Settings
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Clip
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 一本书的朗读源。
 *
 * 为什么不直接塞一个 List<List<Line>>：1550 章的书分完句能有几十万条，
 * 一次全建出来就是几百兆。所以章节按需取，读完一章才去要下一章。
 */
class Feed(
    val book: String,
    val count: Int,
    val titleOf: (Int) -> String,
    val linesOf: suspend (Int) -> List<Line>,
    /** 这本书的念速 ×100。0 = 由调用方给的 settings.rate 决定。 */
    var rate: Int = 0,
    /**
     * 念到哪儿了。参数：章号、这一章里的第几个字。
     *
     * **必须挂在播放器上，不能只挂在阅读页上。**
     * 阅读页一离开就被回收了，可声音还在后台念；睡眠定时也是后台到点停的。
     * 「听完再打开显示的是刚开始听的位置」「定时停了不定位到最后」都是这个洞：
     * 写进度那几行写在界面里，界面没了就没人写。
     */
    val onPos: ((chapter: Int, offset: Int) -> Unit)? = null,
)

/**
 * 播放状态。UI 只读这一个对象。
 * chapter 是整本书里的章号，index 是当前章里的第几句。
 */
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

/**
 * 朗读播放器。
 *
 * 结构是**边播边攒**：正在念第 i 句，后台已经按并发数在合成 i+1 … i+prefetch。
 * 一章念完自动进下一章，章与章之间不重建播放器、不退出前台。
 *
 * 取句、合成、重试全在 IO 线程；播放用 prepareAsync，不阻塞主线程。
 */
class BookPlayer : Service() {

    companion object {
        const val CH = "reading"
        const val NID = 4711
        const val ACTION_STOP = "com.pagefold.reader.STOP"
        const val ACTION_TOGGLE = "com.pagefold.reader.TOGGLE"
        const val ACTION_NEXT = "com.pagefold.reader.NEXT_CH"
        const val ACTION_PREV = "com.pagefold.reader.PREV_CH"

        val state = MutableStateFlow(PlayState())
        val flow: StateFlow<PlayState> get() = state

        @Volatile
        var holder: BookPlayer? = null
            private set

        /** 整本读完（或用户停）时回调。 */
        var onChapterEnd: (() -> Unit)? = null

        /** 换章时回调，参数是整本书里的章号。 */
        var onChapter: ((Int) -> Unit)? = null

        var onProgress: ((index: Int, line: String) -> Unit)? = null

        /** 从界面往播放器塞一句话。没有 Service 实例时也能用。 */
        fun say(msg: String) {
            state.value = state.value.copy(error = msg)
        }

        /**
         * 把一句过场话撤掉。
         *
         * 错误条现在自己会走（四秒），但状态里那个字段还留着 ——
         * 退出这一页再回来，同一句话又贴在头顶，看着像刚又出了一次事。
         * 界面那边用完就调这个，把它清干净。
         */
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

        /** 这一场是不是这个人在念。 */
        fun owns(book: String): Boolean {
            val b = state.value.book
            return b.isNotEmpty() && b == book
        }
    }

    private val job = SupervisorJob()

    /**
     * 兜住「谁都没接住」的异常。
     *
     * 合成线程里有一处没包 runCatching 的调用炸了（取嗓子、段落判定、写账本），
     * 异常会直冲默认处理器，界面还在，进程没了。
     *
     * 这里把栈写进 files/last-crash-player.txt，外置目录也放一份，不用 root 就能取；
     * 同时把「断了」写进播放状态，界面能显示出来。
     */
    private val guard = CoroutineExceptionHandler { _, e ->
        /*
         * 取消不是崩溃。换书、换章、点停止都会 cancel 掉这一场，
         * 正在 await 的地方会抛 CancellationException。放过，不写盘、不推到界面。
         */
        if (e is CancellationException) return@CoroutineExceptionHandler
        val stack = android.util.Log.getStackTraceString(e)
        runCatching { File(filesDir, "last-crash-player.txt").writeText(stack) }
        runCatching {
            getExternalFilesDir(null)?.let { File(it, "last-crash-player.txt").writeText(stack) }
        }
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

    /**
     * 这一本书自己的读法，JSON。空 = 全跟定音。
     * 每句都重新摊一遍，所以书内改了立刻生效，不用重开。
     */
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
    private var cacheOnly = false
    @Volatile private var bookRate = 100

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
        /*
         * 通知通道必须先于一切建好。
         *
         * 顺序反过来的话，pending 里的一次 play() 抛异常，通道就永远建不出来；
         * 接着 startForeground 拿一个不存在的通道挂通知，系统判成坏通知，
         * 抛 RemoteServiceException 把进程干掉。这个异常是系统异步丢回主线程的，
         * 包 runCatching 拦不住，只能在源头保证通知合法。
         */
        ensureChannel()
        pending?.let { val act = it; pending = null; act(this) }
    }

    /**
     * 保证通知通道存在。
     *
     * 只在「没有」的时候建。已经有的不动 —— 用户自己调过的响铃、静音、横幅
     * 是他的选择，不该被我们覆盖。
     */
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { finish(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_TOGGLE -> {
                if (state.value.playing) pauseNow() else resumeNow()
                return START_STICKY
            }
            ACTION_NEXT -> { jumpChapter(1); return START_STICKY }
            ACTION_PREV -> { jumpChapter(-1); return START_STICKY }
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ensureChannel()
        runCatching {
            val note = buildNote(null)
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

    private fun buildNote(text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, Main::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        /*
         * 小图标必须是**专门的通知图标**，不能拿桌面图标顶。
         *
         * 桌面那张是彩色的大图（mipmap 里的 PNG）。系统从 8.0 起要求小图标是
         * 单色、带 alpha 的状态栏字形；拿桌面图当小图标，有些系统会判成
         * 「坏通知」，startForeground 直接把进程带走。
         * ic_stat_reading 是 24dp 基准的白柱图，五档密度都放了。
         */
        val b = NotificationCompat.Builder(this, CH)
            .setChannelId(CH)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(feed?.book?.ifBlank { "Quolune" } ?: "Quolune")
            .setContentText(text ?: "准备中…")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // 只有三个位置。上一章、暂停/继续、退出 —— 「退出」必须有，
        // 不然锁屏上按了暂停，就再也关不掉这一场了。
        b.addAction(0, "上一章", pi(ACTION_PREV))
        b.addAction(0, if (state.value.playing) "暂停" else "继续", pi(ACTION_TOGGLE))
        b.addAction(0, "退出", pi(ACTION_STOP))
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
    ) {
        gen++
        tuneJson = tune
        val my = gen
        feed = f
        curCh = chapter.coerceIn(0, (f.count - 1).coerceAtLeast(0))
        settings = s
        voices = all
        cacheOnly = cacheOnlyMode
        bookRate = when {
            f.rate in 50..300 -> f.rate
            s.rate in 50..300 -> s.rate
            else -> 100
        }
        paused = false
        loop?.cancel()
        // 上一本的声音先掐掉。
        //
        // 只 gen++ 是不够的：正在放的那一段是 MediaPlayer 自己在走，
        // 新的一章还在合成，中间这一两秒里，耳朵里还是上一个故事。
        // 用户说的「换了书还在念旧的」，就是这一段。
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        // 一副嗓子都没开就别往下走了。硬拿写死的名字去念，只会静悄悄失败。
        if (voices.isEmpty()) {
            state.value = state.value.copy(
                playing = false, loading = false,
                error = "一副嗓音都没开。去定音里开一路声源，再来开讲。"
            )
            return
        }
        nextIdx = from.coerceAtLeast(0)
        // 备货跑完会撤掉通知；下一次开讲得把前台重新挂上，
        // 否则屏幕一关，系统就把这场收音掐了。
        if (!cacheOnlyMode) goForeground()
        loop = scope.launch {
            runCatching { run(my, curCh, from.coerceAtLeast(0)) }.onFailure { e ->
                /*
                 * 取消不是崩溃，也不是「断了」。直接吞掉，不 rethrow：
                 * 协程已在取消态，往外抛只会在某道 runCatching 边界上变成真异常，
                 * 日志里也会多一堆 JobCancellationException，看着像崩了。
                 */
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

    /** 换倍速。正在念的这一句也立刻跟着变，不用等下一句。 */
    fun setRate(r: Int) {
        val v = r.coerceIn(50, 300)
        bookRate = v
        feed?.rate = v
        val p = player
        if (p != null) runCatching { applyRate(p) }
    }

    private fun applyRate(mp: MediaPlayer) {
        val r = bookRate
        if (r !in 50..300) return
        runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed(r / 100f).setPitch(1f)
        }
        if (r != 100) {
            // 有些机型必须在 start 之后再设一次才认。
            runCatching { if (mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(r / 100f) }
        }
    }

    /**
     * 把「念到哪儿」写出去。
     *
     * 节流 4 秒（念一句写一次文件没必要），换章、暂停、停下、销毁时强制写。
     * 走 IO 线程，不占主线程。
     */
    private var lastPosAt = 0L
    private fun savePos(force: Boolean = false) {
        val f = feed ?: return
        val st = state.value
        if (st.srcStart < 0 || st.total <= 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPosAt < 4000L) return
        lastPosAt = now
        val c = st.chapter
        val off = st.srcStart
        scope.launch(Dispatchers.IO) { runCatching { f.onPos?.invoke(c, off) } }
    }

    fun pauseNow() {
        paused = true
        // 记住「是按在句子中间停的」。只有这种情况，按播放才是「接着这句的后半段」。
        // 一句已经念完、正等着下一句的时候按播放，得往前走，不能把上一句重念一遍。
        pausedMidClip = runCatching { player?.isPlaying == true }.getOrDefault(false)
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        state.value = state.value.copy(playing = false)
        updateNote("已暂停")
        // 暂停这一下必须落档：睡眠定时就是走这条路停的。
        savePos(force = true)
    }

    fun resumeNow() {
        val f = feed
        val p = player
        if (p != null && pausedMidClip && runCatching { !p.isPlaying }.getOrDefault(false)) {
            paused = false
            pausedMidClip = false
            runCatching { p.start() }
            state.value = state.value.copy(playing = true)
            updateNote(state.value.current)
            return
        }
        if (f == null) return
        val st = state.value
        val over = st.total > 0 && st.index >= st.total
        val ch = if (over) (curCh + 1).coerceAtMost((f.count - 1).coerceAtLeast(0)) else curCh
        // 接着往下念，就从句首开始，不往回退。
        // 暂停再播从当前这一句继续。按 rewind 倒退一句的话，每暂停一次就重念一遍。
        // 「刚才那句没听清」是另一件事，用「上一句」那个按钮，不要混进播放键里。
        val at = if (over) 0 else nextIdx.coerceAtLeast(0)
        play(f, ch, at, settings, voices, cacheOnly)
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
        val target = (curCh + delta).coerceIn(0, (f.count - 1).coerceAtLeast(0))
        play(f, target, 0, settings, voices, cacheOnly)
    }

    /** 上一段 / 下一段。按句跳，不按页。 */
    fun seekLine(delta: Int) {
        val f = feed ?: return
        val at = (state.value.index + delta).coerceAtLeast(0)
        if (delta > 0 && state.value.total > 0 && at >= state.value.total) {
            jumpChapter(1)
            return
        }
        play(f, curCh, at, settings, voices, cacheOnly)
    }

    private fun finish() {
        savePos(force = true)
        stopNow()
        runCatching { stopForeground(true) }
        stopSelf()
    }

    override fun onDestroy() {
        savePos(force = true)
        stopNow()
        if (holder === this) holder = null
        scope.cancel()
        super.onDestroy()
    }

    // ───────────────────────── 整本串起来 ─────────────────────────

    private suspend fun run(my: Int, startCh: Int, from: Int) {
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
            // 换回目立刻写一次：这时候正在念的那句属于新的一章。
            state.value = state.value.copy(chapter = c, srcStart = -1)
            savePos(force = true)

            val done = chapterLoop(my, lines, at)
            if (my != gen) return
            if (!done) return

            // 只合成不播的时候，按「往后先备几回」备够那么多回再收手。
            if (cacheOnly) {
                cached++
                val want = runCatching { now(app).cacheChapters }.getOrDefault(3).coerceIn(1, 30)
                if (cached >= want) { note = "备好了 $cached 回"; break }
                if (c + 1 >= f.count) { note = "已经备到全书的最后一回"; break }
                c++
                at = 0
                continue
            }
            if (c + 1 >= f.count) { note = "全书读完"; whole = true; break }

            val live = runCatching { now(app) }.getOrDefault(settings)
            if (live.stopAtChapter || !live.autoNext) { note = "读完本章，已停"; break }
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
    private suspend fun chapterLoop(my: Int, lines: List<Line>, from: Int): Boolean {
        val app = applicationContext as App
        val s = settings
        val synth = app.synth

        val conc = s.concurrency.coerceIn(1, 4)
        val gapMs = s.reqIntervalMs.coerceIn(0, 5000).toLong()
        val ahead = s.prefetch.coerceIn(1, 12)
        val retries = s.retry.coerceIn(0, 5)
        val back = s.context.coerceIn(0, 6)
        val fwd = s.next.coerceIn(0, 6)

        state.value = PlayState(
            playing = true, loading = true, index = from, total = lines.size,
            title = chapterTitle, current = "", chapter = curCh, cachedOnly = cacheOnly,
            book = feed?.book.orEmpty(),
        )
        updateNote(chapterTitle.ifBlank { "朗读中" })

        val slots = ConcurrentHashMap<Int, CompletableDeferred<Clip?>>()
        val queue = Channel<Int>(Channel.UNLIMITED)
        val queued = Collections.synchronizedSet(HashSet<Int>())
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

        val workers = (0 until conc).map {
            scope.launch(Dispatchers.IO) {
                for (i in queue) {
                    val slot = slots[i] ?: continue
                    var clip: Clip? = null
                    var err: Throwable? = null
                    for (round in 0..retries) {
                        if (my != gen) break
                        val line = lines.getOrNull(i)
                        if (line == null) { err = IllegalStateException("没有这一段"); break }
                        throttle()
                        if (my != gen) break
                        val live = runCatching { now(app) }.getOrDefault(s)
                        val v = runCatching { pickVoice(line, live, voices) }.getOrNull()
                        if (v == null) { err = java.io.IOException("没有可用的嗓子"); break }
                        val before = if (back == 0) null else
                            lines.subList((i - back).coerceAtLeast(0), i)
                                .joinToString(" ") { it.text }.ifBlank { null }
                        val after = if (fwd == 0) null else
                            lines.subList(i + 1, (i + 1 + fwd).coerceAtMost(lines.size))
                                .joinToString(" ") { it.text }.ifBlank { null }
                        // 旁白一个调门、对白一个调门，各自为空才跟全局。
                        // 旁白、对白各自的调门，各自为空才回落到全局。
                        val lineStyle =
                            if (line.isDialog) live.dialogStyle.trim()
                            else live.narratorStyle.trim()
                        val read: Reading = runCatching {
                            Cue.of(
                                line.text,
                                lines.getOrNull(i - 1)?.text,
                                lines.getOrNull(i + 1)?.text,
                                live.style,
                                !live.cue,
                                lineStyle,
                            )
                        }.getOrElse { Reading(Reading.Kind.NARRATE, 0, 0, 0, 320, lineStyle) }
                        val r = runCatching { synth.make(live, v, line.text, read, before, after, rate = bookRate) }
                        if (r.isSuccess) { clip = r.getOrNull(); err = null; break }
                        err = r.exceptionOrNull()
                        if (round < retries) delay(s.retryDelayMs.toLong().coerceIn(200, 5000))
                    }
                    if (clip != null) slot.complete(clip)
                    else slot.completeExceptionally(err ?: java.io.IOException("合成失败"))
                }
            }
        }

        var i = from
        var skipped = 0
        var failStreak = 0
        var lastErr: Throwable? = null
        var aborted = false

        try {
            while (my == gen && i < lines.size) {
                if (paused) { delay(120); continue }
                if (Sleep.fired()) { Sleep.stop(); pauseNow(); state.value = state.value.copy(sleepLeft = 0); return false }
                // 每一段都重新读一次设置：中途换嗓子、换语速、换情绪立刻跟上
                val live = runCatching { now(app) }.getOrDefault(s)

                // 把窗口里还没排队的排进去
                for (k in i until (i + ahead + 1).coerceAtMost(lines.size)) {
                    if (queued.add(k)) {
                        slots[k] = CompletableDeferred()
                        queue.trySend(k)
                    }
                }
                // 窗口外的旧槽位清掉，别一直占内存
                slots.keys.filter { it < i }.forEach { slots.remove(it) }

                val line = lines[i]
                // 同一段正文被切了两遍（正则、分段都可能出这事），只念一次。
                // 宁可少念一句，也不能让人听见同一句读两遍。
                val dup = i > 0 && lines[i - 1].text == line.text &&
                    lines[i - 1].srcStart == line.srcStart
                if (dup) { i++; nextIdx = i; continue }
                state.value = state.value.copy(
                    index = i, current = line.text.take(60), loading = true,
                    srcStart = line.srcStart, srcEnd = line.srcEnd,
                )
                savePos()

                // 这里判的是停顿和节奏，必须和 worker 那边用同一档调门，否则同一条音轨
                // 两处判出两个味。
                val read = Cue.of(
                    line.text,
                    lines.getOrNull(i - 1)?.text,
                    lines.getOrNull(i + 1)?.text,
                    live.style,
                    !live.cue,
                    if (line.isDialog) live.dialogStyle.trim() else live.narratorStyle.trim(),
                )

                val clip: Clip? = try {
                    slots[i]?.await()
                } catch (e: CancellationException) {
                    // 这一场被取消了（换书、停止、换章）。不是「这段念不出来」，
                    // 别记成失败，更别算进「连着两段都出不来」。
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
                    // 连着两段都出不来，说明不是偶发。停下来说清楚，别让它一路跳过去。
                    if (failStreak >= 2) {
                        state.value = state.value.copy(
                            playing = false, loading = false,
                            error = Fail.read(lastErr)
                        )
                        aborted = true
                        break
                    }
                    continue
                }
                failStreak = 0

                if (cacheOnly) {
                    i++
                    state.value = state.value.copy(index = i, error = null)
                    continue
                }

                val ok = withContext(Dispatchers.Main) { awaitPlay(clip.file.absolutePath) }
                if (my != gen) return false
                if (!ok) skipped++

                // 先把「下一句是哪句」定下来，再歇。
                //
                // 先推进游标再歇。反过来的话，歇的途中按暂停，记下的还是刚念完那一句。
                // 按播放就从它再念一遍 —— 用户听到的就是同一句读两次。
                i++
                nextIdx = i
                state.value = state.value.copy(index = i, error = null)
                onProgress?.invoke(i, line.text)

                // 段间停顿：按段落判定来，前后不一个味
                val hold = if (live.cue) read.pauseMs.toLong() else live.gapMs.toLong()
                if (hold > 0) delay(hold)
            }
        } finally {
            queued.clear()
            runCatching { queue.close() }
            workers.forEach { runCatching { it.cancel() } }
            slots.clear()
        }

        if (my != gen) return false
        if (aborted) return false
        if (cacheOnly) {
            state.value = PlayState(playing = false, total = lines.size, title = chapterTitle)
                .copy(error = "已缓存 ${lines.size} 段，跳过 $skipped 段")
            return true
        }
        return i >= lines.size
    }

    /** 播放一个文件并等它播完。用 prepareAsync，不卡主线程。 */
    private suspend fun awaitPlay(path: String): Boolean {
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
                state.value = state.value.copy(error = "这段解不开", loading = false)
                return false
            }
            // 倍速。TTS 出来的音源干净，时间拉伸不明显；音高钉住不动。
            applyRate(mp)
            if (paused) {
                runCatching { mp.release() }
                synchronized(lock) { if (player === mp) player = null }
                return true
            }
            pausedMidClip = false
            mp.start()
            state.value = state.value.copy(playing = true, loading = false)
            updateNote(state.value.current)
            suspendWhilePlaying(mp)
            runCatching { mp.reset(); mp.release() }
            synchronized(lock) { if (player === mp) player = null }
            true
        } catch (e: CancellationException) {
            runCatching { mp.release() }
            synchronized(lock) { if (player === mp) player = null }
            throw e
        } catch (e: Exception) {
            state.value = state.value.copy(error = "播放失败：${e.message?.take(40)}", loading = false)
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

    /**
     * 这一句交给谁念。
     *
     * 关键：`all` 是开播那一刻抓的音色表。用户在音色页换了嗓子、或者在定音里
     * 开关了引擎，这张表就过期了 —— 于是「选了正在用的音色，声音还是上一次的」。
     * 所以先按当时的表找；找不到就按**现在**的设置重算一遍。
     */
    private fun pickVoice(line: Line, s: Settings, all: List<Voice>): Voice {
        val named = line.voice.ifBlank { if (line.isDialog) s.defaultVoice else s.narratorVoice }

        Catalog.byName(all, named)?.let { return it }

        val now = runCatching { (application as App).field.now }.getOrDefault(s)
        val fresh = runCatching {
            Catalog.ours(now.engines, (application as App).box.custom(), emptyList())
        }.getOrDefault(emptyList())

        return Catalog.byName(fresh, named)
            ?: Catalog.byName(all, s.defaultVoice)
            ?: Catalog.byName(fresh, now.defaultVoice)
            ?: fresh.firstOrNull()
            ?: all.firstOrNull()
            ?: Catalog.MIMO.first()
    }

    private fun updateNote(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NID, buildNote(text))
        }
    }
}
