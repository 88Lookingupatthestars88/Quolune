package com.pagefold.reader.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.book.Cue
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.PickChip
import com.pagefold.reader.voice.AutoCast
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Masthead
import com.pagefold.reader.kit.Ornament
import com.pagefold.reader.kit.SealMark
import com.pagefold.reader.kit.Ruler
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.kit.tap
import com.pagefold.reader.voice.Audition
import com.pagefold.reader.voice.CloneApi
import com.pagefold.reader.voice.CloneStore
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 情绪档。写了就按写的来，没写就按选的那档。 */
val STYLES = listOf("平静", "温和", "严肃", "激昂", "悲伤", "欢快")

/** 音色页。顶上那张卡是现在用的这一副，左右滑——滑到哪一副就是哪一副，不用再点一下「用」。 */
@Composable
fun VoiceScreen(
    s: Settings,
    app: App,
    list: androidx.compose.foundation.lazy.LazyListState,
) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var custom by remember { mutableStateOf(app.box.custom()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    /* 克隆对话框。跟「新建」分开两个入口：新建是填一句话造一副嗓（小米独家，每次念书现场重生成）；克隆是拿一段录音复刻（做完锁死。 */
    var cloning by remember { mutableStateOf(false) }

    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    /** 正在合成哪一副。合成要几秒，这几秒里按钮不能装成「正在播」。 */
    var hearing by remember { mutableStateOf<String?>(null) }
    var expAsk by remember { mutableStateOf(false) }
    // 正在编辑哪一副（null = 面板关着）
    var editing by remember { mutableStateOf<Voice?>(null) }
    var expAll by remember { mutableStateOf(false) }
    /** 正在调哪一副（null = 那张纸关着）。 */
    var tuning by remember { mutableStateOf<Voice?>(null) }

    DisposableEffect(Unit) {
        onDispose { /* 试听不随页面销毁，见 Audition */ }
    }

    val groups = remember(s.enginesJson, custom, refresh) {
        Catalog.groups(s.engines, custom, emptyList())
    }
    val pool = remember(groups) { groups.flatMap { it.second } }


    /* 「在用哪一副」只有一个答案：先认存下来的名字，认不出就按开着的嗓源给一副。 */
    val currentName = remember(pool, s.defaultVoice, s.enginesJson) {
        AutoCast.resolve(pool, s.engines, s.defaultVoice, dialog = false)
    }
    val currentId = remember(pool, currentName) {
        pool.firstOrNull { it.name == currentName || it.param == currentName }?.id ?: ""
    }

    /* 当前这一副能调什么，以及它现在调成什么样。 */
    val curVoice = pool.firstOrNull { it.id == currentId }
    val curProto = curVoice?.let { protoOf(it, s.engines) }.orEmpty()
    val curModel = curVoice?.let { modelOf(it, s.engines) }.orEmpty()
    val curKnobs = if (curVoice == null) emptyList()
                   else com.pagefold.reader.field.Caps.of(curProto, curModel, curVoice.kind)

    // 没调过这一副，就照全局那套显示 —— 屏幕上看到的必须是「念出来会是什么样」。
    val savedTune = remember(currentId, refresh) {
        if (currentId.isNotEmpty() && app.vtune.has(currentId)) app.vtune.of(currentId)
        else com.pagefold.reader.field.Vt()
    }
    var draft by remember(currentId, refresh) { mutableStateOf(savedTune) }
    val tuneDirty = draft != savedTune

    fun use(v: Voice) {
        // 已经就是它：不重复写盘。判断用名字 —— defaultVoice 这个字段存的就是名字。
        // 旧存档里那副可能存的是英文名（`alex`），现在显示的是「沉稳男声 alex」，
        // 所以 param 也要比一次，不然一进页面就被当成「换了个人」。
        val was = app.field.now.defaultVoice
        // 存下来的名字可能是空的，而界面已经靠兜底亮着一副；这时也要照样写进去。
        if (v.name == was || v.param == was) return
        app.field.edit { it.copy(defaultVoice = v.name, narratorVoice = v.name) }
    }

    fun say(v: Voice) {
        if (Audition.isOn(v.id)) {
            Audition.stop()
            return
        }
        if (hearing != null) return
        scope.launch {
            hearing = v.id
            /* 试听念手上这份，不读上次存下的。 */
            val live = if (v.id == currentId) draft else null
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val line = "这一段，念给你听。"
                    val mood = live?.mood?.ifBlank { s.style } ?: s.style
                    val read = Cue.of(line, null, null, mood, !s.cue)
                    app.synth.make(s, v, line, read, one = live)
                }
            }
            hearing = null
            val clip = res.getOrNull()
            if (clip == null) {
                // 把底层原因摆出来，不再统一说「这个引擎不能使用」。
                msg = com.pagefold.reader.voice.Why.say(res.exceptionOrNull(), v.name)
                return@launch
            }
            // 播没播上、播完没有，由 Audition 说了算；这里只报错。
            if (!Audition.play(v.id, clip.file.absolutePath)) msg = "声音存下来了，播放器解不开这一段"
        }
    }

    // 卡片翻页器放在页面这一层，不放列表项里。
    // 放进 item 就跟着懒加载一起销毁 —— 你滑到下面点一行，上面那张卡根本不在组合里，
    // 想翻也翻不动。提上来之后，列表滚到哪儿它都还醒着。
    val pager = rememberPagerState(
        initialPage = pool.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    ) { pool.size }

    /*
     * 选一副：先把选择写进去，再去翻上面那张卡。
     * 顺序不能反。
     */
    fun pick(v: Voice) {
        use(v)
        val i = pool.indexOfFirst { it.id == v.id }
        if (i < 0) return
        scope.launch {
            // 瞬时跳，不走动画：二十几页的动画要一页页推，一帧一帧都在跟手上的点抢。
            runCatching { pager.scrollToPage(i) }
        }
    }

    /** 点某一行/某张卡上的「调」。 */
    /** 点「调」= 只打开这一副的调法。 */
    fun focus(v: Voice) {
        tuning = v
    }

    /* ── 上面那张卡和下面那一行，怎么才对得上 ──只留一个真相：defaultVoice。 */
    LaunchedEffect(pager, pool) {
        /* 两个状态一起盯，不能只盯 settledPage 再用 isScrollInProgress 去过滤。 */
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (idx, moving) ->
            if (moving) return@collect
            val v = pool.getOrNull(idx) ?: return@collect
            use(v)
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { busy = null; return@rememberLauncherForActivityResult }
        busy = "正在读…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // 先量大小。有人会顺手挑一个上百兆的 TXT，读进内存就是一次假死。
                    val size = ctx.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                    if (size > 8L * 1024 * 1024) return@runCatching 0 to "文件太大"

                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching 0 to "打不开这个文件"
                    app.box.import(decode(bytes))
                }.getOrElse { 0 to (it.message ?: "读不出来") }
            }
            custom = app.box.custom()
            refresh++
            busy = null
            msg = r.second
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) { busy = null; return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val body = if (expAll) app.box.exportAll() else app.box.export()
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                    true
                }.getOrDefault(false)
            }
            busy = null
            msg = if (ok) "已导出" else "写入失败"
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {

            item {
                // 台头两行（主标题 + 一句话），下面跟一行现状：几副、开的是哪路、在用谁。
                val onEngines = enginesOn(s)
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Masthead(title = "声音宝库", word = "让声音寻找它的主人。") {
                        SealMark("音色", 42.dp)
                    }
                    Ornament(Modifier.padding(horizontal = Sz.gutter))
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = Sz.gutter, end = Sz.gutter, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 一副都没有的时候，上面那句题词已经说了。
                        // 这里再报一次「还没有能用的嗓子」，是同一句话说两遍。
                        if (pool.isNotEmpty()) {
                            Text("${pool.size} 副嗓音", fontSize = 11.sp, color = skin.inkDim)
                        }
                        if (onEngines.isNotBlank() && pool.isNotEmpty()) {
                            Spacer(Modifier.width(7.dp))
                            Text("·", fontSize = 11.sp, color = skin.hair)
                            Spacer(Modifier.width(7.dp))
                            Text(onEngines, fontSize = 11.sp, color = skin.inkFaint)
                        }
                        if (pool.isNotEmpty()) {
                            Spacer(Modifier.width(7.dp))
                            Text("·", fontSize = 11.sp, color = skin.hair)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "在用 " + currentName.ifBlank { "未设置" },
                                fontSize = 11.sp, color = skin.flame
                            )
                        }
                    }
                }
            }

            if (pool.isEmpty()) {
                item {
                    Box(Modifier.padding(horizontal = Sz.gutter, vertical = 24.dp)) {

                    }
                }
            } else {
                item { Label("当前在用的这几副") }
                item {
                    Column {
                        HorizontalPager(
                            state = pager,
                            contentPadding = PaddingValues(horizontal = Sz.gutter - 4.dp),
                            pageSpacing = 8.dp,
                            modifier = Modifier.fillMaxWidth().height(132.dp)
                        ) { page ->
                            // 越界保护：pool 在「关一副嗓源」时会变短，
                            // 而 pager 还要再画一帧旧位置 —— 直接 pool[page] 会炸。
                            val v = pool.getOrNull(page) ?: return@HorizontalPager
                            CardVoice(
                                v = v,
                                on = v.id == currentId,
                                playing = Audition.playingId == v.id,
                                loading = hearing == v.id,
                                engines = s.engines,
                                onSay = { say(v) },
                                onUse = { pick(v) },
                                onTune = { focus(v) },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (curVoice != null) {
                    item { Label("这一副怎么念") }
                    item {
                        Column(Modifier.padding(horizontal = Sz.gutter)) {
                            if (curKnobs.isEmpty()) {
                                Card {
                                    Text(
                                        com.pagefold.reader.field.Caps.why(curProto, curModel, curVoice.kind),
                                        fontSize = 11.sp, color = skin.inkFaint, lineHeight = 17.sp,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            } else {
                                TuneRows(
                                    knobs = curKnobs,
                                    proto = curProto,
                                    model = curModel,
                                    draft = draft,
                                    onChange = { draft = it },
                                )
                            }
                            if (tuneDirty) {
                                Spacer(Modifier.height(10.dp))
                                Btn(
                                    "存下",
                                    {
                                        app.vtune.put(currentId, draft)
                                        refresh++
                                        msg = "存下了"
                                    },
                                    BtnKind.PRIMARY,
                                    Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }

                // 全部音色，按嗓子分节
                groups.forEach { (name, list) ->
                    // 标题右边写这一家能给什么，不写「多少副」—— 数字对他没有用。
                    val eg = s.engines.firstOrNull { it.name == name }
                    item {
                        Label(
                            name,
                            eg?.let { com.pagefold.reader.field.Caps.brief(it.protocol, it.modelOr()) }.orEmpty()
                        )
                    }
                    item {
                        Box(Modifier.padding(horizontal = Sz.gutter)) {
                            Card {
                                list.forEachIndexed { i, v ->
                                    if (i > 0) Line()
                                    VoiceRow(
                                        v = v,
                                        on = v.id == currentId,
                                        playing = Audition.playingId == v.id,
                                        loading = hearing == v.id,
                                        engines = s.engines,
                                        onUse = { pick(v) },
                                        onSay = { say(v) },
                                                onTune = { focus(v) },
                                        onEdit = if (v.builtin) null else ({ editing = v })
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 自建
            item { Label("自建", "造一副只限 MiMo") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Card {
                        /* 四个动作，分两行。一行放四个在 360dp 的屏上每个只剩 70dp，「克隆一段」这种四个字会被压成一团。 */
                        /* 两个入口各有各的条件，别摆一颗点下去必然失败的按钮。 */
                        val canDesign = s.engines.any { it.on && it.protocol == "mimo" }
                        val canClone = s.engines.any {
                            it.on && CloneApi.shape(it.protocol, it.base ?: "") != null
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (canDesign || canClone) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (canDesign) {
                                        Btn(
                                            "造一副", { creating = true },
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Default.Add
                                        )
                                    }
                                    if (canClone) {
                                        Btn(
                                            "克隆音色",
                                            { cloning = true },
                                            BtnKind.GHOST,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Btn(
                                    "导入", { importer.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f)
                                )
                                Btn(
                                    "导出",
                                    { expAsk = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // 系统级
            item { Label("本机自带") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Card {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            Text(
                                "把 Quolune 作为手机的朗读引擎",
                                fontSize = 14.sp, color = skin.ink, fontFamily = skin.titleFont
                            )
                            Spacer(Modifier.height(10.dp))
                            Btn(
                                "打开文字转语音设置",
                                {
                                    if (!openTtsSettings(ctx)) msg = "这台机器没有这个设置页"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        if (creating) {
            NewVoice(
                s = s,
                app = app,
                onClose = { creating = false },
                onDone = { v, bad ->
                    custom = app.box.add(v)
                    app.field.edit { it.copy(defaultVoice = v.name, narratorVoice = v.name) }
                    refresh++
                    creating = false
                    msg = if (bad) "已建成。这个引擎不能使用，去定音里填密钥" else "已保存"
                    // 新造的那副在最上面那张卡里，停在底部看不见它。
                    scope.launch { runCatching { list.animateScrollToItem(0) } }
                }
            )
        }

        editing?.let { v ->
            EditVoice(
                app = app,
                v = v,
                onClose = { editing = null },
                onDone = { nv, gone ->
                    custom = app.box.custom()
                    refresh++
                    editing = null
                    msg = if (gone) "已删掉「${v.name}」" else "改好了"
                }
            )
        }

        if (cloning) {
            CloneVoice(
                s = s,
                app = app,
                onClose = { cloning = false },
                onDone = { v, note ->
                    custom = app.box.add(v)
                    app.field.edit { it.copy(defaultVoice = v.name, narratorVoice = v.name) }
                    refresh++
                    cloning = false
                    msg = note
                }
            )
        }

        if (expAsk) {
            Choices(
                "导出哪一些",
                { expAsk = false },
                listOf(
                    "只导自建（${custom.size} 副）" to {
                        if (custom.isEmpty()) {
                            msg = "还没有自建音色"
                            expAsk = false
                        } else {
                            expAll = false
                            expAsk = false
                            busy = "正在写…"
                            exporter.launch("Quolune音色-自建.json")
                        }
                    },
                    "连内置一起（${custom.size + Catalog.MIMO.size} 副）" to {
                        expAll = true
                        expAsk = false
                        busy = "正在写…"
                        exporter.launch("Quolune音色-全部.json")
                    },
                )
            )
        }

        tuning?.let { v ->
            VoiceTuneSheet(
                v = v,
                s = s,
                app = app,
                onClose = { tuning = null },
                onSaved = {
                    tuning = null
                    refresh++
                },
            )
        }

        (busy ?: msg)?.let { Toast(it) { if (busy == null) msg = null } }
    }
}

// ─────────────────────────────────────────────────────────────

/** 认编码。手机上导出的大多是 UTF-8；但用记事本之类存过的包常常是 GBK，里面全是中文名，按 UTF-8 硬读会读出一串问号。 */
private fun decode(bytes: ByteArray): String {
    val utf8 = runCatching {
        java.nio.charset.Charset.forName("UTF-8")
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (utf8 != null) return utf8.removePrefix("\uFEFF")
    return runCatching {
        String(bytes, java.nio.charset.Charset.forName("GBK"))
    }.getOrDefault("")
}

/** 一排点。多了就两边淡出，不再画二十几个小圆。 */
@Composable
private fun Dots(count: Int, at: Int, color: Color, dim: Color) {
    if (count <= 1) return
    val span = 9
    val from = (at - span / 2).coerceIn(0, (count - span).coerceAtLeast(0))
    val to = (from + span).coerceAtMost(count)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        for (i in from until to) {
            val on = i == at
            Box(
                Modifier.padding(horizontal = 2.5.dp).size(if (on) 5.dp else 4.dp)
                    .clip(CircleShape)
                    .background(if (on) color else dim)
            )
        }
    }
}

private fun openTtsSettings(ctx: Context): Boolean {
    val pm = ctx.packageManager
    val flag = Intent.FLAG_ACTIVITY_NEW_TASK

    // 1) 标准入口。系统的「文字转语音输出」设置页。
    runCatching {
        ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(flag))
        return true
    }

    // 2) 各家改名了也能撞上：把系统设置里带 tts 的页面翻出来
    runCatching {
        val acts = pm.getPackageInfo("com.android.settings", PackageManager.GET_ACTIVITIES)
            .activities.orEmpty()
        val names = acts.mapNotNull { it.name }
        val hit = names.firstOrNull { n ->
            val l = n.lowercase()
            (l.contains("tts") || l.contains("texttospeech")) && l.contains("activity")
        } ?: names.firstOrNull { it.contains("TextToSpeech", true) }

        if (hit != null) {
            ctx.startActivity(Intent().setClassName("com.android.settings", hit).addFlags(flag))
            return true
        }
    }

    // 3) 老版本只有这一个出口
    runCatching {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(flag)
        )
        return true
    }
    return false
}

/** 现在开着哪几路嗓源，一行写完。没开就空串。 */
private fun enginesOn(s: Settings): String =
    s.engines.filter { it.on }.map {
        when (it.src) {
            com.pagefold.reader.field.VoiceSource.MIMO -> "MiMo"
            com.pagefold.reader.field.VoiceSource.SYSTEM -> "本机语音"
            com.pagefold.reader.field.VoiceSource.OPENAI -> it.name.ifBlank { "自建" }
        }
    }.joinToString(" · ")

/**
 * 重名就加序号。
 * @return 可以直接用的名字：要么原样，要么带一个序号后缀
 */
internal fun freeName(app: App, want: String, skipId: String = ""): String {
    val w = want.trim().ifBlank { "没名字" }
    val taken = buildSet {
        addAll(app.box.custom().filter { it.id != skipId }.map { it.name })
        addAll(com.pagefold.reader.voice.Catalog.MIMO.map { it.name })
    }
    if (w !in taken) return w
    var i = 2
    while (true) {
        val n = w.take(9) + " " + i
        if (n !in taken) return n
        i++
    }
}

/**
 * 这副音色属于哪个嗓源，显示嗓源名。
 * @param engines 现在的声源表；查不到 id 才退回 kind 上的老名字
 */
private fun nameOf(engine: String, engines: List<com.pagefold.reader.field.EngineCfg>): String {
    engines.firstOrNull { it.id == engine }?.let { return it.name }
    return when (engine) {
        "mimo" -> "MiMo"
        "system" -> "本机"
        else -> "自建"
    }
}

/** 这副音色的类型。三者行为完全不同，必须写在脸上：造   每次念书都按那句描述重新生成，两次出来不完全一样复刻 声纹已经锁住。 */
private fun kindTag(v: Voice, engines: List<com.pagefold.reader.field.EngineCfg>): String = when {
    v.builtin -> ""
    v.kind == "design" -> "造"
    v.kind == "clone" -> "复刻"
    v.kind == "preset" || v.kind == "spoken" -> "名字"
    else -> ""
}

@Composable
private fun Label(name: String, right: String? = null) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(start = Sz.gutter, end = Sz.gutter, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        Spacer(Modifier.weight(1f))
        if (!right.isNullOrBlank()) Text(right, fontSize = 10.sp, color = s.inkFaint)
    }
}

@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(s.rCard))
            .background(s.surface)
            .border(1.dp, s.hairSoft, RoundedCornerShape(s.rCard))
            .padding(horizontal = 14.dp),
        content = content
    )
}

@Composable
internal fun Line() {
    val s = LocalSkin.current
    Box(Modifier.fillMaxWidth().height(0.6.dp).background(s.hairSoft))
}

@Composable
private fun CardVoice(
    v: Voice,
    on: Boolean,
    playing: Boolean,
    /** 正在合成这一副。合成要几秒，这几秒里不能装成「正在播」。 */
    loading: Boolean,
    engines: List<com.pagefold.reader.field.EngineCfg>,
    onSay: () -> Unit,
    onUse: () -> Unit,
    onTune: () -> Unit,
) {
    val tag = kindTag(v, engines)
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(s.rCard))
            .background(if (on) s.flame.copy(alpha = 0.07f) else s.surface)
            .border(
                if (on) 1.2.dp else 1.dp,
                if (on) s.flame.copy(alpha = 0.55f) else s.hairSoft,
                RoundedCornerShape(s.rCard)
            )
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                v.name, fontSize = 23.sp,
                color = if (on) s.flame else s.ink,
                fontWeight = FontWeight.Medium, fontFamily = s.titleFont,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 176.dp)
            )
            if (v.gender == "f" || v.gender == "m") {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (v.gender == "f") "女" else "男",
                    fontSize = 10.sp, color = s.inkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(s.inkFaint.copy(alpha = 0.09f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            if (tag.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    tag,
                    fontSize = 10.sp,
                    color = if (tag == "造") s.warn else s.inkDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(s.inkFaint.copy(alpha = 0.09f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            /* 右上角永远是家名，不写「在用」。 */
            Text(
                nameOf(v.engine, engines),
                fontSize = 10.sp, color = s.inkFaint
            )
        }
        Spacer(Modifier.height(7.dp))
        /* 卡上这一行要把「造出来的」和「复刻的」说清：造的那种每次念书都重新生成，今天和下周可能不是同一个人；复刻的声纹已经锁住。 */
        Text(
            v.desc.ifBlank { nameOf(v.engine, engines) },
            fontSize = 11.sp, color = s.inkDim, lineHeight = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 试听是一颗按钮，不是两个灰字。一行字摆在那儿，没人知道能按。
            Row(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (playing) s.flame else s.flameGhost)
                    .tap { if (!loading) onSay() }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        loading -> "…"
                        playing -> "■"
                        else -> "▶"
                    },
                    fontSize = 7.sp,
                    color = if (playing) s.ground else s.flame,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        loading -> "合成中"
                        playing -> "停"
                        else -> "试听"
                    },
                    fontSize = 11.sp,
                    color = if (playing) s.ground else s.flame,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "调",
                fontSize = 11.sp,
                color = s.inkFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .tap { onTune() }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
            if (!on) {
                Text(
                    "改用这副",
                    fontSize = 11.sp, color = s.inkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .tap { onUse() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    v: Voice,
    on: Boolean,
    playing: Boolean,
    loading: Boolean,
    engines: List<com.pagefold.reader.field.EngineCfg>,
    onUse: () -> Unit,
    onSay: () -> Unit,
    onTune: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val s = LocalSkin.current
    val tag = kindTag(v, engines)
    val from = nameOf(v.engine, engines)
    Row(
        Modifier.fillMaxWidth()
            .tap { onUse() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.name, fontSize = 14.sp,
                    color = if (on) s.flame else s.ink,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (v.gender == "f" || v.gender == "m") {
                    Spacer(Modifier.width(5.dp))
                    Text(if (v.gender == "f") "女" else "男", fontSize = 9.sp, color = s.inkFaint)
                }
                if (tag.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        tag, fontSize = 9.sp,
                        color = if (tag == "造") s.warn else s.inkFaint
                    )
                }
                if (on) {
                    Spacer(Modifier.width(6.dp))
                    Text("在用", fontSize = 9.sp, color = s.flame)
                }
            }
            /* 第二行：家里 + 描述。家名必须在这儿出现一次 —— 上面那一行已经挤不下了，而「这副是谁家的」是用户要换一家重做时唯一的线索。 */
            val sub = listOf(from, v.desc).filter { it.isNotBlank() }.joinToString(" ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    sub, fontSize = 10.sp, color = s.inkFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .border(1.dp, s.hairSoft, CircleShape)
                .tap {
                    if (!loading) onSay()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    loading -> "…"
                    playing -> "■"
                    else -> "▶"
                },
                fontSize = 9.sp, color = if (playing) s.flame else s.inkDim,
                textAlign = TextAlign.Center
            )
        }
        Text(
            "调",
            fontSize = 11.sp, color = s.inkFaint,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .tap { onTune() }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        /* 入口写「编辑」，不画一个 ×。 */
        if (onEdit != null) {
            Text(
                "编辑",
                fontSize = 11.sp, color = s.inkFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .tap { onEdit() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────
// 编辑一副自建音色：改名、改那句话、删掉
// ─────────────────────────────────────────────────────────────

/**
 * 一副自建音色，点开能改什么，取决于它是怎么来的。
 * 把不能改的格子摆出来，用户会以为改了有用。
 */
@Composable
private fun EditVoice(
    app: App,
    v: Voice,
    onClose: () -> Unit,
    /** @param gone true = 这副已经删掉了 */
    onDone: (Voice?, Boolean) -> Unit,
) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    var name by remember(v.id) { mutableStateOf(v.name) }
    var body by remember(v.id) { mutableStateOf(v.param) }
    var hint by remember(v.id) { mutableStateOf<String?>(null) }
    var asking by remember(v.id) { mutableStateOf(false) }

    val shaper = v.kind == "design"

    Overlay(onClose) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.name, fontSize = 17.sp, color = skin.ink,
                    fontWeight = FontWeight.SemiBold, fontFamily = skin.titleFont,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "取消", fontSize = 12.sp, color = skin.inkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .tap { onClose() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("名字", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(5.dp))
            Field(value = name, onChange = { name = it.take(12) }, placeholder = "沈砚")

            if (shaper) {
                Spacer(Modifier.height(12.dp))
                Text("音色描述", fontSize = 11.sp, color = skin.inkFaint)
                Spacer(Modifier.height(5.dp))
                Field(
                    value = body,
                    onChange = { body = it.take(180) },
                    placeholder = "三十多岁的男声，偏低，说话慢，尾音有点哑",
                    minHeight = 84.dp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "改一个字，声音就跟着变。",
                    fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp
                )
            } else if (v.kind == "clone") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "这副是从录音复刻的，声音已经锁在那段录音上了。要换声音，得拿另一段重新克隆。",
                    fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp
                )
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    "这副用的是对面现成的「${v.param}」。声音在那边，这儿只改名字。",
                    fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp
                )
            }

            hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = skin.inkDim)
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Btn("取消", onClose, BtnKind.GHOST, modifier = Modifier.weight(1f))
                Btn(
                    "保存",
                    {
                        val want = name.trim()
                        if (want.isBlank()) { hint = "名字不能空着"; return@Btn }
                        if (shaper && body.isBlank()) { hint = "音色描述不能空着"; return@Btn }

                        /* 名字改了，选中的那一格也得跟着改。 */
                        val now = freeName(app, want, skipId = v.id)
                        val fixed = v.copy(
                            name = now,
                            param = if (shaper) body.trim() else v.param,
                            desc = if (shaper) body.trim().take(24) else v.desc,
                        )
                        app.box.save(app.box.custom().map { if (it.id == v.id) fixed else it })

                        val st = app.field.now
                        if (st.defaultVoice == v.name || st.narratorVoice == v.name) {
                            app.field.edit {
                                it.copy(
                                    defaultVoice = if (it.defaultVoice == v.name) now else it.defaultVoice,
                                    narratorVoice = if (it.narratorVoice == v.name) now else it.narratorVoice,
                                )
                            }
                        }
                        onDone(fixed, false)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // 删除单独一格，缩在最下面，还得多走一步。
            Spacer(Modifier.height(18.dp))
            Line()
            Spacer(Modifier.height(12.dp))
            if (!asking) {
                Text(
                    "删掉这副",
                    fontSize = 12.sp, color = skin.bad,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .tap { asking = true }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )
            } else {
                Text(
                    if (v.kind == "clone") "删掉之后，本机存的录音样本也一起清掉。" else "删掉就没了，重做还得再写一遍。",
                    fontSize = 11.sp, color = skin.inkDim, lineHeight = 16.sp
                )
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Btn("算了", { asking = false }, BtnKind.GHOST, modifier = Modifier.weight(1f))
                    Btn(
                        "删掉",
                        {
                            // 复刻的那副，样本留在 files/clones 里。只删音色不删样本，
                            // 盘上会攒一堆再也用不到的录音 —— 那玩意儿一段就几兆。
                            if (v.kind == "clone") runCatching { CloneStore.drop(ctx, v.param) }
                            app.box.remove(v.id)

                            val st = app.field.now
                            if (st.defaultVoice == v.name) {
                                app.field.edit { it.copy(defaultVoice = "", narratorVoice = "") }
                            }
                            onDone(null, true)
                        },
                        BtnKind.DANGER,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 造一副嗓：一屏填完，建成再说
// ─────────────────────────────────────────────────────────────

@Composable
private fun NewVoice(
    s: Settings,
    app: App,
    onClose: () -> Unit,
    /** @param bad 引擎用不了：音色照样留着，只是现在念不出东西 */
    onDone: (Voice, Boolean) -> Unit,
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("m") }
    var desc by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    Overlay(onClose) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "造一副嗓", fontSize = 17.sp, color = skin.ink,
                    fontWeight = FontWeight.SemiBold, fontFamily = skin.titleFont
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "取消", fontSize = 12.sp, color = skin.inkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .tap { onClose() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("名字", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(5.dp))
            Field(value = name, onChange = { name = it.take(12) }, placeholder = "沈砚")

            Spacer(Modifier.height(12.dp))
            Text("性别", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("m" to "男声", "f" to "女声").forEach { (k, label) ->
                    val on = gender == k
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) skin.flame else skin.surface)
                            .border(
                                1.dp,
                                if (on) Color.Transparent else skin.hairSoft,
                                RoundedCornerShape(9.dp)
                            )
                            .tap { gender = k }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 13.sp, color = if (on) skin.onFlame else skin.inkDim)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("音色描述", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(5.dp))
            Field(
                value = desc,
                onChange = { desc = it.take(180) },
                placeholder = "三十多岁的男声，偏低，说话慢，尾音有点哑",
                minHeight = 84.dp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "这句话就是嗓音本身。改一个字，声音就跟着变。",
                fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp
            )
            Spacer(Modifier.height(5.dp))
            /* 这一句必须写。造出来的不是一副固定的嗓子 —— 每次念书都是拿这句话现生成，服务端那边不存任何东西，两次出来不完全一样。 */
            Text(
                "每次念书都按这句话重新生成，两次未必一模一样。要固定的嗓子，用「克隆一段」。",
                fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp
            )

            hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = skin.inkDim)
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Btn("取消", onClose, BtnKind.GHOST, modifier = Modifier.weight(1f))
                Btn(
                    if (busy) "在建…" else "建好",
                    {
                        if (busy) return@Btn
                        if (name.isBlank()) {
                            hint = "先填名字"
                            return@Btn
                        }
                        if (desc.isBlank()) {
                            hint = "先写一句音色描述"
                            return@Btn
                        }
                        busy = true
                        scope.launch {
                            val v = Voice(
                                id = "u_" + System.currentTimeMillis().toString(36),
                                name = freeName(app, name),
                                kind = "design",
                                engine = "mimo",
                                param = desc,
                                desc = desc.take(24),
                                gender = gender,
                            )
                            // 先落盘。念不出来是引擎的事，不是你填错。
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { app.synth.make(s, v, "这一句，试试嗓音。", null) }.isSuccess
                            }
                            busy = false
                            onDone(v, !ok)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                )
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val s = LocalSkin.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight)
            .clip(RoundedCornerShape(9.dp))
            .background(s.ground)
            .border(1.dp, s.hairSoft, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 13.sp, color = s.inkFaint, lineHeight = 19.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = s.ink, fontSize = 13.sp, lineHeight = 19.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 判断这一副属于哪套协议、会发哪个模型
// ─────────────────────────────────────────────────────────────

/** 这副音色走哪套协议。跟合成层 [com.pagefold.reader.voice.Synth] 一个判法。 */
internal fun protoOf(v: Voice, engines: List<com.pagefold.reader.field.EngineCfg>): String {
    engines.firstOrNull { it.id == v.engine }?.let { return it.protocol }
    return when (v.engine.lowercase()) {
        "system" -> "system"
        else -> "mimo"
    }
}

/** 这副音色这一次真正会发出去的模型名。造、复刻、朗读三格各管一路。 */
internal fun modelOf(v: Voice, engines: List<com.pagefold.reader.field.EngineCfg>): String {
    val e = engines.firstOrNull { it.id == v.engine } ?: return ""
    return when (v.kind) {
        "design" -> e.designOr()
        "clone" -> e.cloneOr()
        else -> e.modelOr()
    }
}

/**
 * 一副音色的所有可调项。顶上那一块和「调」弹出的那张纸用的是同一份，
 * @param knobs 这一副能调哪几样，由它所属的协议和模型决定
 */
@Composable
internal fun TuneRows(
    knobs: List<com.pagefold.reader.field.Knob>,
    proto: String,
    model: String,
    draft: com.pagefold.reader.field.Vt,
    onChange: (com.pagefold.reader.field.Vt) -> Unit,
) {
    val s = LocalSkin.current
    fun has(k: com.pagefold.reader.field.Knob) = k in knobs

    if (has(com.pagefold.reader.field.Knob.MOOD)) {
        Text("调门", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        val custom = draft.mood.isNotBlank() && draft.mood !in STYLES
        var write by remember { mutableStateOf(custom) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickChip("跟随全局", draft.mood.isBlank(), {
                write = false
                onChange(draft.copy(mood = ""))
            }, Modifier.padding(end = 6.dp))
            STYLES.forEach { st ->
                PickChip(st, draft.mood == st, {
                    write = false
                    onChange(draft.copy(mood = if (draft.mood == st) "" else st))
                }, Modifier.padding(end = 6.dp))
            }
            // 六个档不够用的时候自己写一句。这一格一直在这里，不藏在别处。
            PickChip("自己写", write && custom, {
                write = true
                if (draft.mood in STYLES) onChange(draft.copy(mood = ""))
            }, Modifier.padding(end = 6.dp))
        }
        if (write) {
            Spacer(Modifier.height(8.dp))
            Field(
                value = if (custom) draft.mood else "",
                onChange = { onChange(draft.copy(mood = it.take(40))) },
                placeholder = "一句话，越具体越像人。比如：低哑，像刚抽完烟",
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    val sliders = knobs.filter {
        it == com.pagefold.reader.field.Knob.SPEED ||
            it == com.pagefold.reader.field.Knob.PITCH ||
            it == com.pagefold.reader.field.Knob.VOLUME
    }
    if (sliders.isNotEmpty()) {
        Card {
            Column(Modifier.padding(vertical = 2.dp)) {
                if (has(com.pagefold.reader.field.Knob.SPEED)) {
                    Ruler(
                        "语速", draft.speed.toFloat(), 50f, 200f, 5f,
                        { onChange(draft.copy(speed = it.roundToInt())) },
                        low = "0.5×", high = "2.0×",
                        format = { "%.2f×".format(it / 100f) }
                    )
                }
                if (has(com.pagefold.reader.field.Knob.PITCH)) {
                    Ruler(
                        "音调", draft.pitch.toFloat(), 50f, 200f, 5f,
                        { onChange(draft.copy(pitch = it.roundToInt())) },
                        unit = "%", low = "低", high = "高",
                    )
                }
                if (has(com.pagefold.reader.field.Knob.VOLUME)) {
                    Ruler(
                        "音量", draft.volume.toFloat(), 50f, 200f, 5f,
                        { onChange(draft.copy(volume = it.roundToInt())) },
                        unit = "%",
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    val fxKnobs = knobs.filter {
        it == com.pagefold.reader.field.Knob.BRIGHT ||
            it == com.pagefold.reader.field.Knob.SOFT ||
            it == com.pagefold.reader.field.Knob.CLEAR
    }
    if (fxKnobs.isNotEmpty()) {
        Text("嗓子的质地", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Card {
            Column(Modifier.padding(vertical = 2.dp)) {
                if (has(com.pagefold.reader.field.Knob.BRIGHT)) {
                    Ruler(
                        "低沉 ↔ 明亮", draft.bright.toFloat(), 0f, 200f, 5f,
                        { onChange(draft.copy(bright = it.roundToInt())) },
                        low = "更沉", high = "更亮",
                    )
                }
                if (has(com.pagefold.reader.field.Knob.SOFT)) {
                    Ruler(
                        "刚劲 ↔ 轻柔", draft.soft.toFloat(), 0f, 200f, 5f,
                        { onChange(draft.copy(soft = it.roundToInt())) },
                        low = "更刚", high = "更柔",
                    )
                }
                if (has(com.pagefold.reader.field.Knob.CLEAR)) {
                    Ruler(
                        "浑厚 ↔ 清脆", draft.clear.toFloat(), 0f, 200f, 5f,
                        { onChange(draft.copy(clear = it.roundToInt())) },
                        low = "更厚", high = "更脆",
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.TEMP)) {
        Card {
            Column(Modifier.padding(vertical = 2.dp)) {
                Ruler(
                    "采样温度", draft.temp.toFloat(), 0f, 100f, 5f,
                    { onChange(draft.copy(temp = it.roundToInt())) },
                    low = "0.00", high = "1.00",
                    format = { "%.2f".format(it / 100f) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.FX)) {
        Text("音效", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickChip("不加", draft.fx.isBlank(), {
                onChange(draft.copy(fx = ""))
            }, Modifier.padding(end = 6.dp))
            com.pagefold.reader.field.Caps.fxOptions().forEach { (id, cn) ->
                PickChip(cn, draft.fx == id, {
                    onChange(draft.copy(fx = if (draft.fx == id) "" else id))
                }, Modifier.padding(end = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.BOOST)) {
        Text("语种增强", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickChip("不指定", draft.boost.isBlank(), {
                onChange(draft.copy(boost = ""))
            }, Modifier.padding(end = 6.dp))
            listOf("Chinese" to "中文", "Chinese,Yue" to "中粤", "English" to "英文", "auto" to "自动")
                .forEach { (id, cn) ->
                    PickChip(cn, draft.boost == id, {
                        onChange(draft.copy(boost = if (draft.boost == id) "" else id))
                    }, Modifier.padding(end = 6.dp))
                }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.NORM)) {
        Text("文本归一化", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickChip("不处理", draft.norm.isBlank(), {
                onChange(draft.copy(norm = ""))
            }, Modifier.padding(end = 6.dp))
            listOf("basic" to "快", "quality" to "细").forEach { (id, cn) ->
                PickChip(cn, draft.norm == id, {
                    onChange(draft.copy(norm = if (draft.norm == id) "" else id))
                }, Modifier.padding(end = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.DIALECT)) {
        Text("方言", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickChip("不指定", draft.dialect.isBlank(), {
                onChange(draft.copy(dialect = ""))
            }, Modifier.padding(end = 6.dp))
            com.pagefold.reader.field.Caps.dialectOptions().forEach { (id, cn) ->
                PickChip(cn, draft.dialect == id, {
                    onChange(draft.copy(dialect = if (draft.dialect == id) "" else id))
                }, Modifier.padding(end = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.SILENCE)) {
        Card {
            Column(Modifier.padding(vertical = 2.dp)) {
                Ruler(
                    "念完留多久", draft.silence.toFloat(), 0f, 3000f, 100f,
                    { onChange(draft.copy(silence = it.roundToInt())) },
                    unit = "ms", low = "0", high = "3000",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (has(com.pagefold.reader.field.Knob.LANG)) {
        Text("语种", fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.pagefold.reader.field.Caps.langOptions().forEach { lg ->
                PickChip(
                    if (lg == "Auto") "自动" else lg,
                    (draft.lang.ifBlank { "Chinese" }) == lg,
                    { onChange(draft.copy(lang = lg)) },
                    Modifier.padding(end = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 「调」点开的那张纸。里面跟顶上那一块是同一个 [TuneRows]，所以两处永远一致。 */
@Composable
private fun VoiceTuneSheet(
    v: Voice,
    s: Settings,
    app: App,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val skin = LocalSkin.current
    val proto = protoOf(v, s.engines)
    val model = modelOf(v, s.engines)
    val knobs = com.pagefold.reader.field.Caps.of(proto, model, v.kind)

    // 没调过这一副，就从它自己的原速起 —— 不跟读书页的倍速。
    val saved = remember(v.id) {
        if (app.vtune.has(v.id)) app.vtune.of(v.id) else com.pagefold.reader.field.Vt()
    }
    var cur by remember(v.id) { mutableStateOf(saved) }
    val dirty = cur != saved

    /* 这张纸里也能试听。调完不听见，就得先存下、退出去、在列表里再找一次那一行 —— 三步里两步是多余的。 */
    val scope = rememberCoroutineScope()
    val playing = Audition.isOn(v.id)
    var hearing by remember(v.id) { mutableStateOf(false) }
    fun hear() {
        if (Audition.isOn(v.id)) {
            Audition.stop()
            return
        }
        scope.launch {
            hearing = true
            val line = "这一段，念给你听。"
            val clip = withContext(Dispatchers.IO) {
                runCatching {
                    val mood = cur.mood.ifBlank { s.style }
                    val read = Cue.of(line, null, null, mood, !s.cue)
                    app.synth.make(s, v, line, read, one = cur)
                }.getOrNull()
            }
            hearing = false
            if (clip == null) return@launch
            Audition.play(v.id, clip.file.absolutePath)
        }
    }

    Overlay(onClose) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        v.name, fontSize = 17.sp, color = skin.ink,
                        fontWeight = FontWeight.SemiBold, fontFamily = skin.titleFont,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        listOf(nameOf(v.engine, s.engines), v.desc)
                            .filter { it.isNotBlank() }.joinToString(" "),
                        fontSize = 10.sp, color = skin.inkFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "收起", fontSize = 12.sp, color = skin.inkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .tap { onClose() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))

            if (knobs.isEmpty()) {
                Text(
                    com.pagefold.reader.field.Caps.why(proto, model, v.kind),
                    fontSize = 12.sp, color = skin.inkDim, lineHeight = 19.sp,
                )
            } else {
                TuneRows(knobs, proto, model, cur) { cur = it }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Btn(
                    when {
                        hearing -> "合成中"
                        playing -> "停"
                        else -> "试听"
                    },
                    { hear() },
                    BtnKind.GHOST,
                    modifier = Modifier.weight(1f)
                )
                if (dirty) {
                    Btn(
                        "存下",
                        {
                            app.vtune.put(v.id, cur)
                            onSaved()
                        },
                        BtnKind.PRIMARY,
                        Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
