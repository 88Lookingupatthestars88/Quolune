package com.pagefold.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.kit.Glyph
import com.pagefold.reader.App
import com.pagefold.reader.field.Bg
import com.pagefold.reader.field.BgSpec
import com.pagefold.reader.field.EngineCfg
import com.pagefold.reader.field.Engines
import com.pagefold.reader.field.Pages
import com.pagefold.reader.field.Presets
import com.pagefold.reader.field.Settings
import com.pagefold.reader.field.TurnMode
import com.pagefold.reader.field.VoiceSource
import com.pagefold.reader.kit.BgImage
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.Hint
import com.pagefold.reader.kit.IconBtn
import com.pagefold.reader.voice.Assign
import com.pagefold.reader.voice.Auth
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Speak
import com.pagefold.reader.voice.Voice
import com.pagefold.reader.voice.Why
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Masthead
import com.pagefold.reader.kit.Ornament
import com.pagefold.reader.kit.Rule
import com.pagefold.reader.kit.Ruler
import com.pagefold.reader.kit.SealMark
import com.pagefold.reader.kit.SegBar
import com.pagefold.reader.kit.Skin
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.kit.ThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────
// 路由
// ─────────────────────────────────────────────────────────────

private const val N_TUNE = 0
private const val N_BG = 2
private const val N_CAST = 5
private const val N_VAULT = 3

@Composable
fun SettingsScreen(
    s: Settings,
    app: App,
    list: androidx.compose.foundation.lazy.LazyListState,
) {
    var node by remember { mutableIntStateOf(N_TUNE) }
    var bgPage by remember { mutableStateOf("shelf") }
    var vault by remember { mutableStateOf(StoreKind.AUDIO) }
    val vaultList = androidx.compose.foundation.lazy.rememberLazyListState()

    androidx.activity.compose.BackHandler(enabled = node != N_TUNE) { node = N_TUNE }

    when (node) {
        N_BG -> BgEditorScreen(page = bgPage, s = s, app = app, onBack = { node = N_TUNE })
        N_CAST -> CastScreen(book = null, app = app, onBack = { node = N_TUNE })
        N_VAULT -> StoreScreen(
            kind = vault,
            s = s,
            app = app,
            list = vaultList,
            onBack = { node = N_TUNE },
            onChanged = {},
        )
        else -> TuneScreen(
            s = s,
            app = app,
            list = list,
            toBg = { p -> bgPage = p; node = N_BG },
            toVault = { k -> vault = k; node = N_VAULT },
            toCast = { node = N_CAST },
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 定音
// ─────────────────────────────────────────────────────────────

@Composable
private fun TuneScreen(
    s: Settings,
    app: App,
    list: androidx.compose.foundation.lazy.LazyListState,
    toBg: (String) -> Unit,
    toVault: (StoreKind) -> Unit,
    toCast: () -> Unit,
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val engines = remember(s.enginesJson) { s.engines }
    val chats = remember(s.chatJson) { s.chats }

    var pickFor by remember { mutableStateOf<String?>(null) }
    var homeAsk by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    var audioBytes by remember { mutableLongStateOf(0L) }
    var audioCount by remember { mutableIntStateOf(0) }
    var bookBytes by remember { mutableLongStateOf(0L) }
    var bookCount by remember { mutableIntStateOf(0) }
    var voiceBytes by remember { mutableLongStateOf(0L) }
    var voiceCount by remember { mutableIntStateOf(0) }
    var bgBytes by remember { mutableLongStateOf(0L) }
    var bgCount by remember { mutableIntStateOf(0) }
    var coverBytes by remember { mutableLongStateOf(0L) }
    var coverCount by remember { mutableIntStateOf(0) }
    var trialCount by remember { mutableIntStateOf(0) }

    fun relist() {
        val ledger = app.synth.ledger()
        /*
         * 段落那两行账，按**账本**算，不按目录算。
         *
         * 数字取自账本，不扫目录：扫目录会把没登记的文件也算进去，
         * 那几条在库里既列不出来也删不掉，两边数就对不上。
         */
        val read = ledger.filterNot { it.trial }
        audioBytes = read.sumOf { app.synth.sizeOf(it.key, it.ext) }
        audioCount = read.size
        trialCount = ledger.count { it.trial }
        val covers = app.shelf.coverDir(app).listFiles { f -> f.isFile }
        coverBytes = covers?.sumOf { it.length() } ?: 0L
        coverCount = app.shelf.all().count { it.cover.isNotBlank() }
        val books = app.shelf.dir().listFiles { f -> f.isFile } ?: emptyArray()
        bookBytes = books.sumOf { it.length() }; bookCount = books.size
        val vf = File(app.filesDir, "voices.json")
        voiceBytes = if (vf.exists()) vf.length() else 0L
        voiceCount = app.box.custom().size
        val (bb, bn) = Bg.sizeOf(app)
        bgBytes = bb; bgCount = bn
    }

    LaunchedEffect(s.enginesJson) {
        /*
         * 数账这一趟出什么事都不能把这一页带走。
         *
         * 定音是唯一能配嗓子、能填钥匙的地方，它一闪退，整个应用就等于废了。
         * 数不出来就少几行数字，别让人连门都进不去。
         */
        val bad = withContext(Dispatchers.IO) { runCatching { relist() }.exceptionOrNull() }
        if (bad != null) {
            msg = "库房那几行没数出来：" + (bad.message ?: bad.javaClass.simpleName)
        }
    }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val page = pickFor
        pickFor = null
        if (uri == null || page == null) return@rememberLauncherForActivityResult
        scope.launch {
            val v = Bg.take(app, page, uri)
            if (v != null) {
                app.field.edit { it.withBg(page, v) }
                relist()
                toBg(page)
            } else {
                msg = "这张读不出来，换一张"
            }
        }
    }

    /*
     * 崩过才有的记录。读在列表外面 —— 列表 DSL 里不是可组合作用域，拿不到 remember。
     */
    val crashTxt = remember(app) {
        runCatching {
            listOf("last-crash.txt", "last-crash-player.txt")
                .map { File(app.filesDir, it) }
                .filter { it.exists() && it.length() > 0 }
                .joinToString("\n\n———\n\n") { it.readText().takeLast(1600) }
        }.getOrDefault("")
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {

            item { Head() }

            // ── 装帧 ──
            item { Chapter("装帧") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Sz.gutter),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ThemeId.entries.toList(), key = { it.key }) { t ->
                        SkinCard(t, s.theme == t.key, s.dark) {
                            app.field.edit { it.copy(theme = t.key) }
                        }
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter, vertical = 9.dp)) {
                    Text(ThemeId.of(s.theme).tagline, fontSize = 11.sp, color = skin.inkFaint)
                }
            }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        RowLine("深色") {
                            Pill(s.dark) { app.field.edit { c -> c.copy(dark = it) } }
                        }
                    }
                }
            }

            item { SubChapter("底子", "每一页各挑各的") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Sz.gutter),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(Pages.ALL, key = { it.first }) { (key, name) ->
                        BgCell(key, name, s.bg(key), skin) { pickFor = it }
                    }
                }
            }
            // ── 嗓源 ──
            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            item { Chapter("声源") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        engines.forEachIndexed { i, e ->
                            if (i > 0) Hair()
                            VoiceBlock(
                                e = e,
                                note = noteOf(e),
                                app = app,
                                onToggle = { on ->
                                    app.field.edit { st ->
                                        st.withEngines(engines.map { if (it.id == e.id) it.copy(on = on) else it })
                                    }
                                },
                                onEdit = { fixed ->
                                    app.field.edit { st ->
                                        st.withEngines(engines.map { if (it.id == fixed.id) fixed else it })
                                    }
                                },
                                onDrop = if (Engines.DEFAULT.any { it.id == e.id }) null else ({
                                    app.field.edit { st ->
                                        st.withEngines(engines.filterNot { it.id == e.id })
                                    }
                                    msg = "已经拿掉了"
                                }),
                            )
                        }
                        Hair()
                    }
                }
            }

            // ── 接口 ──
            //
            // 这一节跟上面那七家不是一回事：上面是「把字念成声音」，这里是「替人拿主意」。
            // 目前只给派角用一次 —— 让它把名单分成组、每组派一副嗓子。
            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            item { Chapter("接口") }
            /*
             * 这行字回答「这一节跟上面那七家有什么区别」——上面是把字念成声音，
             * 这里是「替人拿主意」，而且只服务派角那一件事。不写清楚，用户会以为
             * 填了这里念书就换嗓子了。
             */
            item {
                Box(Modifier.padding(horizontal = Sz.gutter).padding(bottom = 10.dp)) {
                    Text(
                        "此接口用于派角中的「让 AI 排」所使用的 AI 提供商。",
                        fontSize = 11.sp, color = skin.inkFaint, lineHeight = 17.sp,
                    )
                }
            }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        chats.forEachIndexed { i, c ->
                            if (i > 0) Hair()
                            ChatBlock(
                                e = c,
                                app = app,
                                onToggle = { on ->
                                    app.field.edit { st ->
                                        st.withChats(chats.map { if (it.id == c.id) it.copy(on = on) else it })
                                    }
                                },
                                onEdit = { fixed ->
                                    app.field.edit { st ->
                                        st.withChats(chats.map { if (it.id == fixed.id) fixed else it })
                                    }
                                },
                            )
                        }
                        Hair()
                    }
                }
            }

            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            // ── 派角 ──
            //
            // 这一节是**起手那一档**：每本书点「让 AI 排」时先照这里，书里还能临时改。
            // 没有这一节，用户每换一本书都得重新点一遍「准它造」「准用哪几副」。
            item { Chapter("派角") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            RowLine("准它自己造嗓音") {
                                Pill(s.aiDesign) { on ->
                                    if (on && !s.canDesign) {
                                        msg = "造嗓音要小米那一路开着。先去「声源」里把它拨开"
                                    } else {
                                        app.field.edit { it.copy(aiDesign = on) }
                                    }
                                }
                            }
                            Hair()
                            Spacer(Modifier.height(8.dp))
                            AiVoicePick(s, app) { msg = it }
                            Hair()
                            Spacer(Modifier.height(8.dp))
                            AiAskField(s, app) { msg = it }
                        }
                    }
                }
            }

            // ── 新书照这个来 ──
            //
            // 这一节只管**新收进来的书**。已经进库的书各有各的版式，
            // 在这里拨动它们不会跟着变 —— 要改那一本，进那本书的「这一本怎么读」。
            item { Chapter("新书照这个来") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Column {
                        SegBar(
                            listOf("左右翻", "上下滚"),
                            if (s.turn == TurnMode.FLIP) 0 else 1,
                            {
                                app.field.edit { c ->
                                    c.copy(turnMode = if (it == 0) TurnMode.FLIP.name else TurnMode.SCROLL.name)
                                }
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        Sample(s)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "书架上已有的书不受影响。改那一本，进书里点「怎么读」。",
                            fontSize = 11.sp, color = skin.inkFaint, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Ruler(
                                "字号", s.fontSize.toFloat(), 13f, 30f, 1f,
                                { app.field.edit { c -> c.copy(fontSize = it.roundToInt()) } },
                                unit = "sp", low = "13", high = "30",
                                hint = "字多大。默认 19。"
                            )
                            Ruler(
                                "行距", s.lineGap.toFloat(), 110f, 220f, 5f,
                                { app.field.edit { c -> c.copy(lineGap = it.roundToInt()) } },
                                unit = "%", low = "110", high = "220",
                                hint = "行距按字号的百分比算，150 就是 1.5 倍。"
                            )
                            Ruler(
                                "页边距", s.padH.toFloat(), 8f, 48f, 2f,
                                { app.field.edit { c -> c.copy(padH = it.roundToInt()) } },
                                unit = "dp", low = "8", high = "48",
                                hint = "左右留多少白。留得多，一行字就少。"
                            )
                            Ruler(
                                "段间距", s.paraGap.toFloat(), 0f, 40f, 2f,
                                { app.field.edit { c -> c.copy(paraGap = it.roundToInt()) } },
                                unit = "sp", low = "0", high = "40",
                                hint = "两段之间再空一点。0 就是贴住，只换一行。"
                            )
                            Spacer(Modifier.height(6.dp))
                            Btn(
                                "照默认这套",
                                {
                                    app.field.edit { it.copy(fontSize = 19, lineGap = 150, padH = 22, paraGap = 0) }
                                    msg = "眼下 19 号字，1.5 倍行距，左右留 22。"
                                },
                                BtnKind.GHOST,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ── 驿传 ──
            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            item { Chapter("驿传") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Bare("衔接（前后话）")
                            Ruler(
                                "往前带几句", s.context.toFloat(), 0f, 20f, 1f,
                                { app.field.edit { c -> c.copy(context = it.roundToInt()) } },
                                unit = "句", low = "0", high = "20",
                                hint = "合成这一句时，连前几句一起发过去，引擎据此接住上一句的口气。只是多花几个字，不会念出来。"
                            )
                            Ruler(
                                "往后带几句", s.next.toFloat(), 0f, 20f, 1f,
                                { app.field.edit { c -> c.copy(next = it.roundToInt()) } },
                                unit = "句", low = "0", high = "20",
                                hint = "连后几句一起发。下一句马上有人接话，引擎就会把这一句收着尾，不留长气口。"
                            )
                            RowLine("按段落断语气") {
                                Pill(s.cue) { app.field.edit { c -> c.copy(cue = it) } }
                            }
                            Text(
                                "先判这一句是旁白、对白、喊还是耳语，再按各自定下的快慢高低去合成。",
                                fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Hair()
                            Bare("驿马（先备多少）")
                            Ruler(
                                "先备几段", s.prefetch.toFloat(), 1f, 12f, 1f,
                                { app.field.edit { c -> c.copy(prefetch = it.roundToInt()) } },
                                unit = "段", low = "1", high = "12",
                                hint = "念着这一段的时候，后面这几段已经在合成。留得多，网络抖一下不断；留太多，引擎被占着，开头慢半拍。"
                            )
                            Ruler(
                                "同时开几路", s.concurrency.toFloat(), 1f, 4f, 1f,
                                { app.field.edit { c -> c.copy(concurrency = it.roundToInt()) } },
                                unit = "路", low = "1", high = "4",
                                hint = "对同一家服务同时发几条请求。两路是这个尺寸的手机上的平衡点：再往上，首句不会更快，只会更容易被对面按 429 挡回来。"
                            )
                            Ruler(
                                "隔多久再开口", (s.reqIntervalMs / 100f).coerceIn(0f, 30f), 0f, 30f, 1f,
                                { app.field.edit { c -> c.copy(reqIntervalMs = (it * 100).roundToInt()) } },
                                unit = "秒", low = "0", high = "3.0",
                                format = { "%.1f".format(it / 10f) },
                                hint = "对同一家服务，两次请求之间至少隔这么久。这是本地的一道闸门，不是引擎的快慢：调小，下一段来得快，但更容易被对面挡回来；调大，稳，代价是接得慢。看到「取得太急了」，就把这格往上推。"
                            )
                            Ruler(
                                "不成再试几回", s.retry.toFloat(), 0f, 5f, 1f,
                                { app.field.edit { c -> c.copy(retry = it.roundToInt()) } },
                                unit = "回", low = "0", high = "5",
                                hint = "超时、429、空音频，都算没取到，就再跑一趟。0 = 取不到就跳过这一段，接着念下一段。"
                            )
                            Ruler(
                                "每回之间等多久", (s.retryDelayMs / 100f).coerceIn(2f, 50f), 2f, 50f, 2f,
                                { app.field.edit { c -> c.copy(retryDelayMs = (it * 100).roundToInt()) } },
                                unit = "毫秒", low = "0.2 秒", high = "5.0 秒",
                                format = { "%.1f".format(it / 10f) },
                                hint = "两次重试之间的间隔。对面抽风时，隔得越短，越容易连着撞同一堵墙。"
                            )
                            Ruler(
                                "往后先备几章", s.cacheChapters.toFloat(), 0f, 20f, 1f,
                                { app.field.edit { c -> c.copy(cacheChapters = it.roundToInt()) } },
                                unit = "回", low = "0", high = "20",
                                hint = "从这一章往后，先把这几章的音频合好存在本机。断网、进地铁也还能听一段；占的空间也按这个数涨。"
                            )
                            Spacer(Modifier.height(6.dp))
                            Btn(
                                "照默认这套",
                                {
                                    app.field.edit {
                                        it.copy(
                                            prefetch = 6, reqIntervalMs = 600, concurrency = 2,
                                            retry = 3, retryDelayMs = 1200, cacheChapters = 5,
                                            context = 1, next = 1, cue = true,
                                        )
                                    }
                                    msg = "眼下预取 6 段，两路一起取，中间隔 0.6 秒。"                                },
                                BtnKind.GHOST,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ── 库房 ──
            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            item { Chapter("库房") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        VaultRow(
                            "念过的段落",
                            "$audioCount 段 · ${size(audioBytes)}",
                            "每句都在里头，随时重放。",
                            onClick = { toVault(StoreKind.AUDIO) }
                        )
                        Hair()
                        VaultRow(
                            "库里的书",
                            "$bookCount 本 · ${size(bookBytes)}",
                            "抄进库的正文，原文件删了也照读。",
                            onClick = { toVault(StoreKind.BOOK) }
                        )
                        Hair()
                        VaultRow(
                            "自配的嗓音",
                            "$voiceCount 个 · ${size(voiceBytes)}",
                            "自己写下的那些。",
                            onClick = { toVault(StoreKind.VOICE) }
                        )
                        if (trialCount > 0) {
                            Hair()
                            VaultRow(
                                "试听留下的",
                                "$trialCount 段",
                                "点一下听个响造的，不是书里的字。",
                                onClick = { toVault(StoreKind.TRIAL) }
                            )
                        }
                        Hair()
                        VaultRow(
                            "换过的封面",
                            "$coverCount 张 · ${size(coverBytes)}",
                            "自己挑的那张，删掉就回布面。",
                            onClick = { toVault(StoreKind.COVER) }
                        )
                        Hair()
                        VaultRow(
                            "换过的底子",
                            "$bgCount 张 · ${size(bgBytes)}",
                            "挑来的图，缩过再存。",
                            onClick = { toVault(StoreKind.BG) }
                        )
                    }
                }
            }
            // ── 开卷 ──
            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            item { Chapter("开卷") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        NavLine(
                            "落在哪一页",
                            Pages.name(
                                when (s.homeTab) {
                                    0 -> "shelf"
                                    1 -> "voice"
                                    2 -> "preview"
                                    4 -> "about"
                                    else -> "tune"
                                }
                            )
                        ) { homeAsk = true }
                    }
                }
            }

            /*
             * 只有真崩过才出现的一节。
             *
             * 崩了以后栈写在 files/last-crash.txt（合成线程的是 last-crash-player.txt），
             * 内部目录要 root 才看得到。这里直接摊在界面上，一条「复制」，
             * 粘给谁都能定位 —— 不用再靠「你再复现一次我猜猜」。
             */
            if (crashTxt.isNotBlank()) {
                item { Chapter("上一回崩在这儿") }
                item {
                    Box(Modifier.padding(horizontal = Sz.gutter)) {
                        Quiet {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    crashTxt,
                                    fontSize = 10.sp, color = skin.inkDim, lineHeight = 15.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Btn(
                                        "复制",
                                        {
                                            runCatching {
                                                val cm = app.getSystemService(android.content.ClipboardManager::class.java)
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashTxt))
                                            }
                                            msg = "复制好了"
                                        },
                                        BtnKind.GHOST, modifier = Modifier.weight(1f)
                                    )
                                    Btn(
                                        "清掉",
                                        {
                                            listOf("last-crash.txt", "last-crash-player.txt")
                                                .forEach { File(app.filesDir, it).delete() }
                                            msg = "清掉了"
                                        },
                                        BtnKind.GHOST, modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }

        pickFor?.let { page ->
            BgSheet(
                page = page,
                current = s.bg(page),
                skin = skin,
                onClose = { pickFor = null },
                onPick = { imgPicker.launch(arrayOf("image/*")) },
                onAdjust = { pickFor = null; toBg(page) },
                onTheme = {
                    Bg.drop(app, page)
                    app.field.edit { it.withBg(page, "auto") }
                    relist(); pickFor = null
                }
            )
        }

        if (homeAsk) {
            Choices("进应用先看哪一页", { homeAsk = false }, listOf(
                "书房" to { app.field.edit { c -> c.copy(homeTab = 0, choseHome = true) }; homeAsk = false },
                "音色" to { app.field.edit { c -> c.copy(homeTab = 1, choseHome = true) }; homeAsk = false },
                "试听" to { app.field.edit { c -> c.copy(homeTab = 2, choseHome = true) }; homeAsk = false },
                "定音" to { app.field.edit { c -> c.copy(homeTab = 3, choseHome = true) }; homeAsk = false },
                "关于" to { app.field.edit { c -> c.copy(homeTab = 4, choseHome = true) }; homeAsk = false }
            ))
        }

        msg?.let { Toast(it) { msg = null } }
    }
}

private fun noteOf(e: EngineCfg): String {
    if (!e.on) return "关"
    // 缺哪一格就说哪一格。四格都齐了才报「几个音色」。
    Engines.blocked(e)?.let { return it }
    // 名字一眼就不是语音模型（小米那套口收到不认识的 model 不报错，会静默降级），
    // 在卡片上先说一句，省得他点开播放才发现。
    if (Why.notSpeech(e.modelOr(), e.protocol)) return "模型不能念"
    return when {
        e.kind == "MIMO" -> "8 个"
        e.voiceList().isNotEmpty() -> "${e.voiceList().size} 个"
        // 没有预设音色、但能复刻：把话说成他下一步能做的事。
        com.pagefold.reader.field.Presets.cloneFor(e.base ?: "", e.protocol).isNotEmpty() ->
            "克隆一副才能念"
        else -> "这家没给音色"
    }
}

// ─────────────────────────────────────────────────────────────
// 页头与章节
// ─────────────────────────────────────────────────────────────

@Composable
private fun Head() {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        // 右上那枚印是这一页的落款。纸墨上是实心朱印，其余材质上是描边的。
        Masthead(
            title = "定音",
            word = "把一切，调成你习惯的样子。",
            trailing = { SealMark("定音", 46.dp) },
        )
        Ornament(Modifier.padding(horizontal = Sz.gutter).padding(top = 2.dp))
    }
}

@Composable
internal fun Chapter(name: String) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(start = Sz.gutter, end = Sz.gutter, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 名前一粒小菱格：朱色，转四十五度。章回里「回目」前头那个记号。
        Box(
            Modifier.size(5.dp)
                .graphicsLayer { rotationZ = 45f }
                .background(s.flame.copy(alpha = 0.6f))
        )
        Spacer(Modifier.width(9.dp))
        Text(name, fontSize = 15.sp, color = s.ink, fontWeight = FontWeight.SemiBold, fontFamily = s.titleFont)
        Spacer(Modifier.width(10.dp))
        // 后头那道线不再齐头齐尾，往右化开 —— 齐平的线在长页里太像表格。
        Box(
            Modifier.height(1.dp).weight(1f)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(s.hair, Color.Transparent)
                    )
                )
        )
    }
}

@Composable
private fun SubChapter(name: String, note: String?) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(start = Sz.gutter, end = Sz.gutter, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 12.sp, color = s.inkDim, fontWeight = FontWeight.Medium)
        if (note != null) {
            Spacer(Modifier.weight(1f))
            Text(note, fontSize = 10.sp, color = s.inkFaint)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 装帧
// ─────────────────────────────────────────────────────────────

@Composable
private fun SkinCard(t: ThemeId, on: Boolean, dark: Boolean, onClick: () -> Unit) {
    val s = LocalSkin.current
    val p = t.skin(dark)
    val border by animateColorAsState(if (on) s.flame else s.hairSoft, tween(180), label = "sb")
    Column(Modifier.width(78.dp)) {
        Box(
            Modifier.size(78.dp, 92.dp)
                .clip(RoundedCornerShape(p.rCard))
                .background(p.ground)
                .border(if (on) 2.dp else 1.dp, border, RoundedCornerShape(p.rCard))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
        ) {
            Column(Modifier.padding(10.dp)) {
                Box(Modifier.width(20.dp).height(3.dp).background(p.flame, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.88f).height(3.dp).background(p.ink.copy(alpha = 0.46f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.62f).height(3.dp).background(p.ink.copy(alpha = 0.22f), RoundedCornerShape(2.dp)))
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(p.rChip))
                    .background(p.panelLift)
                    .border(1.dp, p.hair, RoundedCornerShape(p.rChip))
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (on) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(s.flame))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                t.label, fontSize = 12.sp,
                color = if (on) s.flame else s.inkDim,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 底子
// ─────────────────────────────────────────────────────────────

@Composable
private fun BgCell(page: String, name: String, value: String, skin: Skin, onPick: (String) -> Unit) {
    val spec = BgSpec.decode(value)
    Column(
        Modifier.width(60.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(page) }
    ) {
        Box(
            Modifier.size(60.dp, 82.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(skin.ground)
                .border(
                    if (spec != null) 1.5.dp else 1.dp,
                    if (spec != null) skin.flame else skin.hairSoft,
                    RoundedCornerShape(9.dp)
                )
        ) {
            if (spec != null) {
                BgImage(spec, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(skin.ground.copy(alpha = spec.dim * 0.7f)))
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp)
                        .size(5.dp).clip(CircleShape).background(skin.flame)
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(skin.spotA.copy(alpha = 0.75f), skin.ground),
                            center = androidx.compose.ui.geometry.Offset(18f, 14f),
                            radius = 130f
                        )
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, fontSize = 11.sp, color = if (spec != null) skin.ink else skin.inkDim, maxLines = 1)
    }
}

@Composable
private fun BgSheet(
    page: String,
    current: String,
    skin: Skin,
    onClose: () -> Unit,
    onPick: () -> Unit,
    onAdjust: () -> Unit,
    onTheme: () -> Unit,
) {
    val spec = BgSpec.decode(current)
    /*
     * 预览格的高度跟着屏幕走。
     *
     * 写死 190 的时候，屏幕矮一档（或者系统字体调大）这张纸的固定高度就超过了
     * 能用的那截，红按钮被裁在屏幕外，往下又滑不动。
     */
    val previewH = (LocalConfiguration.current.screenHeightDp * 0.26f).coerceIn(110f, 190f).dp
    Overlay(onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Pages.name(page)}的底子",
                    fontSize = 16.sp, color = skin.ink,
                    fontWeight = FontWeight.SemiBold, fontFamily = skin.titleFont
                )
                Spacer(Modifier.weight(1f))
                IconBtn(Icons.Default.Close, "收起", onClose, size = 32.dp)
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(previewH)
                    .clip(RoundedCornerShape(14.dp))
                    .background(skin.ground)
                    .border(1.dp, skin.hairSoft, RoundedCornerShape(14.dp))
            ) {
                if (spec != null) {
                    BgImage(spec, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(skin.ground.copy(alpha = spec.dim)))
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(skin.spotA.copy(alpha = 0.8f), skin.ground),
                                center = androidx.compose.ui.geometry.Offset(50f, 40f),
                                radius = 340f
                            )
                        )
                    )
                    Text(
                        "跟主题走",
                        fontSize = 12.sp, color = skin.inkFaint,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(
                    if (spec == null) "挑一张图" else "换一张",
                    onPick, BtnKind.PRIMARY,
                    modifier = Modifier.weight(1f)
                )
                if (spec != null) {
                    Btn("挪位置", onAdjust, BtnKind.GHOST, modifier = Modifier.weight(1f))
                }
            }
            if (spec != null) {
                Spacer(Modifier.height(10.dp))
                Btn("还是用主题的", onTheme, BtnKind.GHOST, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BgEditorScreen(page: String, s: Settings, app: App, onBack: () -> Unit) {
    val skin = LocalSkin.current
    val spec = BgSpec.decode(s.bg(page))
    if (spec == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var zoom by remember { mutableFloatStateOf(spec.zoom) }
    var ox by remember { mutableFloatStateOf(spec.ox) }
    var oy by remember { mutableFloatStateOf(spec.oy) }
    var dim by remember { mutableFloatStateOf(spec.dim) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BgImage(
            spec.copy(zoom = zoom, ox = ox, oy = oy),
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 3f)
                    ox = (ox + pan.x / size.width).coerceIn(-0.5f, 0.5f)
                    oy = (oy + pan.y / size.height).coerceIn(-0.5f, 0.5f)
                }
            }
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text("不要了", fontSize = 13.sp, color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text("${Pages.name(page)}的底子", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(skin.surface)
                .padding(18.dp)
        ) {
            Text("两根指头缩放，一根指头挪。", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(6.dp))
            Ruler(
                "明暗", dim * 100f, 0f, 85f, 1f,
                { dim = it / 100f },
                unit = "%", low = "原色", high = "压到底",
                hint = "图太亮，字就浮在面上。压到看着舒服为止。"
            )
            Ruler(
                "缩放", zoom, 1f, 3f, 0.05f,
                { zoom = it },
                unit = "倍", low = "满屏", high = "近看",
                format = { "%.2f".format(it) }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(
                    "就这样",
                    {
                        app.field.edit {
                            it.withBg(page, spec.copy(dim = dim, ox = ox, oy = oy, zoom = zoom).encode())
                        }
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                )
                Btn("摆正", { zoom = 1f; ox = 0f; oy = 0f }, BtnKind.GHOST)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 读法样张
// ─────────────────────────────────────────────────────────────

@Composable
private fun Sample(s: Settings) {
    val skin = LocalSkin.current
    val body = TextStyle(
        fontSize = s.fontSize.sp,
        lineHeight = (s.fontSize * s.lineGap / 100f).sp,
        color = skin.ink,
        fontFamily = skin.titleFont
    )
    val paras = listOf(
        "那年冬天来得早，城外的河还没封，风已经能割脸。",
        "「你不该来。」",
        "他说这话时没抬头，手里的炭笔也没停。",
        "桌上摊着半张舆图，朱线断在雁门关外。",
    )
    /*
     * 样张里的段间距必须跟正文那套算法一字不差：正文那条空行的高度**就是**
     * paraGap（sp），0 就贴住。差着算，样张就成了骗人的。
     */
    val gap = s.paraGap.coerceIn(0, 40)
    val gapDp = with(LocalDensity.current) { gap.sp.toDp() }
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(skin.rChip))
            .background(skin.ground)
            .border(1.dp, skin.hairSoft, RoundedCornerShape(skin.rChip))
            .padding(horizontal = s.padH.dp, vertical = 16.dp)
    ) {
        Column {
            paras.forEachIndexed { i, p ->
                if (i > 0 && gap > 0) Spacer(Modifier.height(gapDp))
                Text("　　$p", style = body)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 嗓子
// ─────────────────────────────────────────────────────────────

@Composable
private fun EngineMark(name: String, on: Boolean) {
    val s = LocalSkin.current
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) s.flame.copy(alpha = 0.10f) else s.panelSink),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(), fontSize = 13.sp,
            color = if (on) s.flame else s.inkFaint,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun VoiceBlock(
    e: EngineCfg,
    note: String,
    app: App,
    onToggle: (Boolean) -> Unit,
    onEdit: (EngineCfg) -> Unit,
    onDrop: (() -> Unit)? = null,
) {
    val s = LocalSkin.current
    var name by remember(e.id) { mutableStateOf(e.name) }
    // 老存档里这几个字段可能是 Gson 塞进来的 null，先兜平，别让输入框拿着 null 用。
    var base by remember(e.id) { mutableStateOf(e.base ?: "") }
    var key by remember(e.id) { mutableStateOf(e.key ?: "") }
    var model by remember(e.id) { mutableStateOf(e.model ?: "") }
    /*
     * 造嗓音、复刻嗓音各用哪个模型。
     *
     * 只有小米这一家分得开：它三个口子要三个不同模型，而且填串了不报错，只静默降级。
     * 别的家这两格留着不显示，值原样带回去，不会因为改了名字就被抹掉。
     */
    var design by remember(e.id) { mutableStateOf(e.modelDesign ?: "") }
    var clone by remember(e.id) { mutableStateOf(e.modelClone ?: "") }
    var voices by remember(e.id) { mutableStateOf(e.voices ?: "") }
    // 这家清单里有什么，就照原样列什么。不筛、不分组。
    var fetched by remember(e.id) { mutableStateOf<List<String>>(emptyList()) }

    /*
     * 改完就验，结果写在这一行的副标题上。
     *
     * 验的办法是真发两个字过去（[Speak.probe] / [Speak.probeMimo]）：这几家的模型清单
     * 里没有「这是朗读的还是听写的」这一项，按名字猜一定猜错。
     *
     * 四样东西（协议、地址、钥匙、模型）算一个签名，验过的结论落盘。一样没变就直接
     * 摆上一次的结果，不发请求 —— 定音页是常来的地方，不该每次进来都等一会儿。
     *
     * 手指停下 0.7 秒才发，边打字边发是白烧请求；验的过程不改他填的任何一格。
     */
    var verdict by remember(e.id) { mutableStateOf<String?>(null) }
    var trying by remember(e.id) { mutableStateOf(false) }
    val vScope = rememberCoroutineScope()

    /*
     * 进来**不再自己发请求**。
     *
     * 定音一页七家，每家一次验、一次要清单，进一次就是十几个请求同时出去 ——
     * 页面在这一串里发木，用户的话是「一进入就要重新获取」。
     * 现在只摆**已经验过**的结论（结论落盘，四样没变就一直在），
     * 想重验按那一行右边的「验」。改完格子不动手，也不会偷偷花他的钱。
     */
    LaunchedEffect(e.id, e.model, e.key, e.base, e.on, e.protocol) {
        verdict = null
        if (!e.on) return@LaunchedEffect
        if (e.protocol == "system") return@LaunchedEffect
        val m = (e.model ?: "").trim()
        val k = (e.key ?: "").trim()
        if (k.isBlank()) return@LaunchedEffect
        // 本地能判的三样，先判掉，一个请求都不发。
        if (m.isBlank()) { verdict = "模型空着，念不了"; return@LaunchedEffect }
        if (Why.notSpeech(m, e.protocol)) { verdict = "识别模型，念不了"; return@LaunchedEffect }
        verdict = Speak.probeRemembered(e.protocol, e.base ?: "", k, m)
    }

    /** 按一下才发一次。结果落盘，下次进来直接摆。 */
    fun tryProbe() {
        val m = (e.model ?: "").trim()
        val k = (e.key ?: "").trim()
        if (trying) return
        if (k.isBlank() || m.isBlank()) return
        vScope.launch {
            trying = true
            verdict = "正在测试中"
            val why = when {
                e.protocol == "mimo" -> Speak.probeMimo(e.base ?: "", k, m)
                else -> Speak.probe(
                    e.base ?: "", k, m,
                    com.pagefold.reader.field.Presets.voicesFor(e.base ?: "", e.protocol).firstOrNull().orEmpty()
                )
            }
            verdict = why ?: Speak.OK
            trying = false
        }
    }

    /*
     * 一进这一页、或者钥匙一变，就去要这家的模型清单。
     *
     * 拉回来**原样全列**。这几家的清单里没有「这是朗读模型」这一项，
     * 本地怎么猜都会猜错：`mimo-v2.5-tts-voiceclone` 也带 tts，`-asr` 也带 voice。
     * 藏错一条比列错一条更糟 —— 用户会觉得这家根本没好模型。
     *
     * 拉不到就什么都不显示，让格子空着由人自己填。
     * 火山没有清单端点，也走这条路。
     */
    LaunchedEffect(e.id, e.key, e.base, e.proto, e.on) {
        val k = e.key ?: ""
        if (k.isBlank() || !e.on) { fetched = emptyList(); return@LaunchedEffect }
        // 盘上有就先用盘上的，界面立刻有东西；没有才去要一次，要回来也落盘。
        val cached = Speak.modelsCached(e.base ?: "", k, e.protocol)
        if (cached.isNotEmpty()) fetched = cached
        fetched = Speak.models(e.base ?: "", k, e.protocol)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EngineMark(e.name, e.on)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.name, fontSize = 14.sp,
                    color = if (e.on) s.ink else s.inkDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    verdict ?: note, fontSize = 10.sp,
                    /*
                     * 三种颜色，三种意思：平时是灰的，测试中是淡的，念不出来才是红的。
                     * 只有「念不出来」才刷红。「正在测试中」是中间态，刷红像出了事。
                     */
                    color = when {
                        verdict == null -> s.inkFaint
                        verdict == "正在测试中" -> s.inkDim
                        verdict == "念得出来" -> s.flame
                        else -> s.bad
                    },
                    maxLines = 1,
                )
            }
            /*
             * 「验」。按一下才发一次。
             *
             * 进来就自己发的话，七家一起出去，页面在这一串里发木；
             * 而且有的家按字算钱，不打招呼就花掉是不对的。
             * 只在「开着、有钥匙、不是离线那两家」时才出现 —— 别的按了也没意义。
             */
            if (e.on && !e.key.isNullOrBlank() && e.protocol != "system") {
                Text(
                    if (trying) "…" else "验",
                    fontSize = 11.sp,
                    color = if (trying) s.inkFaint else s.inkDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { tryProbe() }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            if (onDrop != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDrop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", fontSize = 17.sp, color = s.inkFaint)
                }
            }
            Spacer(Modifier.width(4.dp))
            Pill(e.on, onToggle)
        }

        if (!e.on) return@Column
        if (e.protocol == "system") return@Column

        Spacer(Modifier.height(4.dp))

        /*
         * 改一格，整副配置重新交上去。
         *
         * 整副配置只从这一个出口交上去。改成三格模型之后，各处自己拼 e.copy(…)
         * 漏一格就把那一格抹掉。
         */
        fun push() = onEdit(
            e.copy(
                name = name.ifBlank { e.name },
                base = base,
                key = key,
                model = model,
                voices = voices,
                modelDesign = design,
                modelClone = clone,
            )
        )

        InlineField("名称", name, { name = it; push() }, "随便起")
        InlineField("接口地址", base, { base = it; push() }, "https://…")
        InlineField("钥匙", key, { key = it; push() }, "sk-…", secret = true, sanitize = Auth::clean)

        /*
         * 模型。清单全部照列，一条不筛。
         *
         * 填了钥匙就当场去要：要到了，这家的模型一个不落摆出来；要不到（没这个端点、
         * 或者钥匙不对）就只留一个空框，自己填。
         *
         * 小米这一家分三格 —— 朗读、造嗓音、复刻嗓音各要一个模型，
         * 而且填串了它不报错，只拿默认声音念，所以只能在界面上分开摆。
         * 别的家只有朗读这一路，仍是一格。
         */
        if (e.protocol == "mimo") {
            InlineField("朗读模型", model, { model = it; push() }, "")
            Chips(fetched.filter { it.contains("tts") && !it.contains("design") && !it.contains("clone") }, model) {
                model = it; push()
            }

            InlineField("设计模型", design, { design = it; push() }, "")
            Chips(fetched.filter { it.contains("design") }, design) { design = it; push() }

            InlineField("克隆模型", clone, { clone = it; push() }, "")
            Chips(fetched.filter { it.contains("clone") }, clone) { clone = it; push() }
        } else {
            /*
             * 能复刻的那几家（硅基、MiniMax）也分两格。
             *
             * 它们是一把钥匙，但两个口：念书走 audio/speech，复刻走另一条上传口，
             * 而且上传那边只认某一个模型。填串了不会报错，只会复刻出来不像 ——
             * 和朗读模型摆在同一格，用户永远不知道自己改的是哪一口。
             */
            /*
             * 朗读模型：清单里那些「一定不念书」的不摆出来。
             *
             * `?type=audio` 拉回来的是这一整类，识别模型（ASR / SenseVoice / Whisper /
             * 说话人分离）和合成模型混在一排里，长得一样。用户按名字猜不出来，
             * 点中一个识别模型，卡片从此写「还没填音色」，播放一个字都没有。
             *
             * 只挡「一定不是」的，拿不准的照旧摆着 —— 由对面的响应去说。
             * 当前已经填着的那个永远留着，否则选错了连改都不知道改哪一个。
             */
            InlineField("朗读模型", model, { model = it; push() }, "")
            Chips(fetched.filter { !Why.notSpeech(it) || it == model }, model) { model = it; push() }

            if (com.pagefold.reader.field.Presets.cloneFor(e.base ?: "", e.protocol).isNotEmpty()) {
                /*
                 * 克隆模型：**这一格不是从清单里挑，是从这家认的那一个里挑。**
                 *
                 * 上传样本的那个口只收某一个模型（硅基只认 CosyVoice2，MiniMax 只认
                 * speech-02-hd）。清单里根本没有名字带 clone 的，拿清单去过滤，
                 * 结果就是一个都没有 —— 用户看到的是「这一格空的，没法选」。
                 *
                 * 所以先把这家真正认的摆出来，再补上清单里像样的同类。
                 */
                val can = com.pagefold.reader.field.Presets.cloneFor(e.base ?: "", e.protocol)
                InlineField("克隆模型", clone, { clone = it; push() }, "")
                Chips(
                    (can + fetched.filter {
                        it.contains("cosyvoice", true) || it.contains("clone", true) ||
                            it.startsWith("speech-") || it.contains("voice", true)
                    }).distinct(),
                    clone,
                ) { clone = it; push() }
            }
        }

        /*
         * 音色**不在这儿挑** —— 它在音色页。这里只把值原样往下传，免得冲掉已有配置。
         */
    }
}

/**
 * 「这家有哪些模型」—— 进程级的一份备忘。
 *
 * 为什么要挪出这一行：定音是 LazyColumn，滚出屏幕的那段会被丢掉，滚回来是重新组合，
 * `remember` 里的东西一律从零开始。清单放在行内，等于每滑一次重新要一次，
 * 还得重新等 0.8 秒。用户说的「不要每次一滑动，它就重新拉取一次」就是它。
 *
 * 键是「地址 + 钥匙」。换一把钥匙当然要重新问；同一把钥匙滚进滚出、退出定音再进来，
 * 都是现成的。跟声源那边「验过的结论记着」是同一个路子。
 */
private object ChatMemo {
    private val mem = HashMap<String, List<String>>()
    private var file: java.io.File? = null
    private var loaded = false

    /**
     * 落一份在 files/chat-models.json —— 跟声源那份 models.json 是同一个路子。
     *
     * 不落盘的话，进程一重启就全忘：再进定音又去要一遍，用户看到的就是
     * 「每次进来都重新拉」。空了也要记（空表就是「问过了，这家没给」），
     * 不然那家连着失败，每进一次页面就撞一次墙。
     */
    fun attach(app: android.content.Context) {
        if (file == null) file = java.io.File(app.filesDir, "chat-models.json")
        if (loaded) return
        loaded = true
        runCatching {
            val f = file ?: return@runCatching
            if (!f.exists()) return@runCatching
            val t = object : com.google.gson.reflect.TypeToken<HashMap<String, List<String>>>() {}.type
            com.google.gson.Gson().fromJson<HashMap<String, List<String>>>(f.readText(), t)
                ?.let { mem.putAll(it) }
        }
    }

    private fun save() {
        runCatching {
            val f = file ?: return@runCatching
            f.writeText(com.google.gson.Gson().toJson(mem))
        }
    }

    fun sig(e: com.pagefold.reader.voice.ChatCfg): String =
        (e.base ?: "").trim() + "|" + (e.key ?: "").trim()

    fun get(k: String): List<String>? = mem[k]

    /** 这一对「地址 + 钥匙」问过没有。问过就不再自己去问第二遍。 */
    fun knows(k: String): Boolean = mem.containsKey(k)

    fun put(k: String, v: List<String>) {
        mem[k] = v
        save()
    }
}

/**
 * 接口那一行。比嗓源少两件事：不用选音色，也没有协议 —— 都是 OpenAI 兼容口。
 *
 * 「拉清单」和「测一下」两个动作分开：
 * 拉清单是把「这家有哪些模型」拿回来，测一下是「这个模型答不答得上话」。
 * 合成后一次请求按字计费，所以不自动发，按一下才发。
 */
@Composable
private fun ChatBlock(
    e: com.pagefold.reader.voice.ChatCfg,
    app: App,
    onToggle: (Boolean) -> Unit,
    onEdit: (com.pagefold.reader.voice.ChatCfg) -> Unit,
) {
    val s = LocalSkin.current
    var name by remember(e.id) { mutableStateOf(e.name) }
    var base by remember(e.id) { mutableStateOf(e.base ?: "") }
    var key by remember(e.id) { mutableStateOf(e.key ?: "") }
    var model by remember(e.id) { mutableStateOf(e.model ?: "") }
    /*
     * 这一格是「地址 + 钥匙」问来的清单。
     *
     * 进来先摆盘上那份（`chat-models.json`），一个请求都不发 —— 跟声源那边一样。
     * 只有**从来没问过**这一对，才自己去要一次；要过之后就一直摆着，包括问空了。
     * 想重问按「刷新清单」，那颗按钮一直都在。
     */
    ChatMemo.attach(app)
    val sigKey = ChatMemo.sig(e)
    var fetched by remember(e.id) { mutableStateOf(ChatMemo.get(sigKey).orEmpty()) }
    var verdict by remember(e.id) { mutableStateOf<com.pagefold.reader.voice.Chat.Hit?>(null) }
    var busy by remember(e.id) { mutableStateOf(false) }
    /** 正在拉清单。跟「测」分开：测的是这个模型答不答得上话。 */
    var pulling by remember(e.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun push(next: com.pagefold.reader.voice.ChatCfg = e.copy(
        name = name.ifBlank { e.name },
        base = base,
        key = key,
        model = model,
    )) {
        // 地址、钥匙、模型动过，上一次验收的结论就不算数了 —— 留着会骗人。
        verdict = null
        onEdit(next)
    }

    /** 清单里挑一个填进去。已经填过模型的一个字都不碰。 */
    fun autoPick(got: List<String>) {
        if (got.isEmpty() || (e.model ?: "").isNotBlank()) return
        val pick = com.pagefold.reader.voice.Chats.pick(
            com.pagefold.reader.voice.ChatPresets.of(e.base ?: ""), got
        )
        if (pick.isNotBlank()) {
            model = pick
            push(e.copy(name = name.ifBlank { e.name }, base = base, key = key, model = pick))
        }
    }

    /*
     * 进来只做一件事：把盘上那份摆出来。
     *
     * 只有「这一对地址 + 钥匙从来没问过」才真去要一次 —— 要回来就落盘，之后
     * 无论进来多少次、滚出去滚回来，都不再发请求。跟声源那边一模一样。
     * 想重新问，按模型底下那颗「刷新清单」。
     */
    LaunchedEffect(sigKey, e.on) {
        fetched = ChatMemo.get(sigKey).orEmpty()
        if (!e.on) return@LaunchedEffect
        val k = key.trim()
        if (k.isBlank() || Auth.why(k) != null) return@LaunchedEffect
        if (ChatMemo.knows(sigKey)) { autoPick(fetched); return@LaunchedEffect }
        /*
         * 手指停下 0.8 秒才去要。
         *
         * 每敲一个字都重来一次的话，粘一把钥匙就是一串请求 —— 有的家按次计费，
         * 而且中途那几个残缺的钥匙会连着回 401，界面上看着像「这把钥匙不对」。
         */
        kotlinx.coroutines.delay(800)
        pulling = true
        val got = com.pagefold.reader.voice.Chat.models(
            e.copy(base = base, key = k, model = model)
        )
        ChatMemo.put(sigKey, got)
        fetched = got
        autoPick(got)
        pulling = false
    }

    /** 按一下才重问。问过的都落盘，下一次进来直接摆。 */
    fun repull() {
        val k = key.trim()
        if (pulling || k.isBlank() || Auth.why(k) != null) return
        scope.launch {
            pulling = true
            val got = com.pagefold.reader.voice.Chat.models(
                e.copy(base = base, key = k, model = model)
            )
            ChatMemo.put(sigKey, got)
            fetched = got
            autoPick(got)
            pulling = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EngineMark(e.name, e.on)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.name, fontSize = 14.sp,
                    color = if (e.on) s.ink else s.inkDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val v = verdict
                Text(
                    v?.said ?: when {
                        !e.on -> "关"
                        busy -> "…"
                        (e.key ?: "").isBlank() -> "还差一把钥匙"
                        (e.model ?: "").isBlank() -> "没挑模型"
                        else -> (e.model ?: "").take(30)
                    },
                    fontSize = 10.sp,
                    color = if (v == null) s.inkFaint else if (v.ok) s.flame else s.bad,
                    maxLines = if (v == null) 1 else 2,
                )
            }
            if (e.on && (e.key ?: "").isNotBlank()) {
                Text(
                    if (busy) "…" else "测",
                    fontSize = 11.sp,
                    color = if (busy) s.inkFaint else s.inkDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            if (busy) return@clickable
                            busy = true
                            verdict = null
                            scope.launch {
                                verdict = com.pagefold.reader.voice.Chat.hello(e.copy(model = model))
                                busy = false
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Pill(e.on, onToggle)
        }

        if (!e.on) return@Column
        Spacer(Modifier.height(4.dp))

        InlineField("接口地址", base, { base = it; push() }, "https://…")
        InlineField("钥匙", key, { key = it; push() }, "sk-…", secret = true, sanitize = Auth::clean)
        InlineField("模型", model, { model = it; push() }, "填完钥匙自动挑")
        if (fetched.isNotEmpty()) {
            Chips(fetched.take(40), model) { model = it; push() }
        }
        /*
         * 「刷新清单」一直都在。
         *
         * 清单是问过就摆着、不再自己重问的（跟声源一样）。那家的模型换了、或者
         * 上一次恰好没连上，总得有个地方能按一下 —— 不然又回到「只能杀进程」。
         */
        if (key.isNotBlank() && Auth.why(key) == null) {
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        pulling -> "正在取清单"
                        fetched.isEmpty() -> "取一份清单"
                        else -> "刷新清单"
                    },
                    fontSize = 11.sp,
                    color = if (pulling) s.inkFaint else s.inkDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, s.hairSoft, RoundedCornerShape(8.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { repull() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                if (fetched.isEmpty() && !pulling) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "没取到就自己填，一样能用",
                        fontSize = 10.sp, color = s.inkFaint,
                    )
                }
            }
        }
    }
}

/**
 * 一排可选的字。点一下就换成那个。
 *
 * @param multi  true = 多选（音色那一栏用），点一下加、再点一下去掉
 * @param picked 多选时当前选中的
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    options: List<String>,
    current: String,
    multi: Boolean = false,
    picked: List<String> = emptyList(),
    onPick: (String) -> Unit,
) {
    val s = LocalSkin.current
    if (options.isEmpty()) return
    /*
     * 换行排，不横滚。
     *
     * 用自动换行，不用横滑：横滑一下就看不出后面还有几条，像只有前面几个可选。
     * 用户要的是「把主要的模型列出来」，那就全摆出来，一行放不下就折行。
     */
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { o ->
            val on = if (multi) picked.contains(o) else o == current
            Text(
                o,
                fontSize = 11.sp,
                color = if (on) s.flame else s.inkDim,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) s.flameGhost else Color.Transparent)
                    .border(1.dp, if (on) s.flame.copy(alpha = 0.4f) else s.hairSoft, RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onPick(o) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 一行可编辑的字。
 *
 * @param secret true = 默认盖住（钥匙那一格用）。右边一只眼睛，点一下才看得见。
 *   盖住时真的转成圆点 —— 不是靠颜色糊过去：截屏、录屏、旁边有人看，
 *   画面里就只有一串点。
 */
@Composable
private fun InlineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
    /* 入框前先过一道。钥匙那一格用它：一个汉字混进去，拼请求头时会抛
     * IllegalArgumentException（拿不住的那种），整个应用当场退出。 */
    sanitize: ((String) -> String)? = null,
) {
    val s = LocalSkin.current
    /*
     * 钥匙默认是盖住的。
     *
     * 一进这一页就能看见整串 key，等于谁凑过来都能抄走 —— 截屏、录屏、
     * 旁边站着人，全露着。所以默认只显示一串圆点，右边一只眼睛，
     * 自己想看才点开，看完再点回去。
     *
     * 但**不用 Password 键盘**（KeyboardType.Password）：那会把手机的
     * 安全输入法叫起来，部分 ROM 还会顺带禁掉截屏。这里只是把显示的字符
     * 换成圆点，输入本身还是普通键盘。
     */
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = s.inkFaint, modifier = Modifier.width(58.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(hint, fontSize = 12.sp, color = s.inkFaint.copy(alpha = 0.6f), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = if (sanitize == null) onChange else { v -> onChange(sanitize(v)) },
                singleLine = true,
                textStyle = TextStyle(color = s.ink, fontSize = 12.sp),
                visualTransformation = if (secret && !open) {
                    PasswordVisualTransformation('\u25CF')
                } else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(end = if (secret) 30.dp else 0.dp)
            )
        }
        if (secret) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { open = !open },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (open) Glyph.VisibilityOff else Glyph.Visibility,
                    contentDescription = if (open) "盖住" else "看一眼",
                    tint = if (open) s.flame else s.inkFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.hairSoft))
}

// ─────────────────────────────────────────────────────────────
// 家底
// ─────────────────────────────────────────────────────────────

@Composable
private fun VaultRow(name: String, amount: String, note: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 14.sp, color = s.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(amount, fontSize = 11.sp, color = s.inkDim)
            }
            Spacer(Modifier.height(5.dp))
            Text(note, fontSize = 11.sp, color = s.inkFaint, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.KeyboardArrowRight,
            null,
            tint = s.inkFaint,
            modifier = Modifier.size(19.dp)
        )
    }
}

private fun size(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024L -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

// ─────────────────────────────────────────────────────────────
// 公用小件
// ─────────────────────────────────────────────────────────────

@Composable
internal fun Quiet(content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(s.rCard))
            .background(s.surface)
            .border(1.dp, s.hairSoft, RoundedCornerShape(s.rCard))
            .padding(horizontal = 15.dp, vertical = 4.dp),
        content = content
    )
}

@Composable
internal fun Hair() {
    val s = LocalSkin.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.hairSoft))
}

@Composable
private fun RowLine(label: String, trailing: @Composable () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = s.ink)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun NavLine(title: String, sub: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = s.ink)
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 10.sp, color = s.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", fontSize = 16.sp, color = s.inkFaint)
    }
}

/**
 * 准它用哪几副嗓子。
 *
 * 「一副不给」必须存得下来 —— 空白既表示「全给」，把最后一副点掉就会自己全亮回来。
 * 记号在 [com.pagefold.reader.field.AI_NO_VOICE]。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiVoicePick(s: Settings, app: App, say: (String) -> Unit) {
    val skin = LocalSkin.current
    val all = remember(s.enginesJson, app.box.custom().size) {
        runCatching { Catalog.ours(s.engines, app.box.custom()).map { it.name }.distinct() }
            .getOrDefault(emptyList())
    }
    val picked = s.aiVoiceSet ?: all.toSet()

    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("准它用的嗓子", fontSize = 15.sp, color = skin.ink, modifier = Modifier.weight(1f))
            Text("${picked.size} / ${all.size}", fontSize = 11.sp, color = skin.inkFaint)
        }
        if (all.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("音色库里一副都没有。先去「声源」里开一路。", fontSize = 11.sp, color = skin.inkFaint)
        } else {
            Chips(all, "", multi = true, picked = picked.toList()) { n ->
                val next = if (n in picked) picked - n else picked + n
                app.field.edit {
                    it.copy(
                        aiVoices = when {
                            next.isEmpty() -> com.pagefold.reader.field.AI_NO_VOICE
                            next.size == all.size -> ""
                            else -> all.filter { x -> x in next }.joinToString(",")
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("全给", { app.field.edit { it.copy(aiVoices = "") } }, BtnKind.GHOST)
                Btn(
                    "一副不给",
                    { app.field.edit { it.copy(aiVoices = com.pagefold.reader.field.AI_NO_VOICE) } },
                    BtnKind.GHOST,
                )
            }
        }
    }
}

/**
 * 发给它的那段话。
 *
 * 名单和返回格式不在这儿 —— 那两段是接口约定，改了整份结果就解析不出来。
 * 这儿只放「怎么分」的口径：先挑现成的、主角要各有各的、配角可以合。
 */
@Composable
private fun AiAskField(s: Settings, app: App, say: (String) -> Unit) {
    val skin = LocalSkin.current
    /*
     * 初值只取一次。
     *
     * 写成 `remember(s.aiPrompt)` 的话，每敲一个字都改一次设置 → key 变 → 状态重建，
     * 中文输入法正在拼的那一截会被打断（「打字打一半字没了」）。
     * 这一格是唯一的数据源，别处要改它就重进这一页。
     */
    var text by remember { mutableStateOf(s.aiPrompt) }

    Column(Modifier.padding(vertical = 6.dp)) {
        Text("发给它的话", fontSize = 15.sp, color = skin.ink)
        Spacer(Modifier.height(8.dp))
        /*
         * 一段真能改的字。
         *
         * 上一版把出厂那段压在输入框上面当占位：两段字叠在一起，既看不清也点不准，
         * 用户说的「用户也修改不了」就是它。占位只在空的时候写一行小字，落在最上面；
         * 真开始写，那行就没了。
         *
         * 「看全文」「恢复出厂」拿掉：框里本来就是全文，也没有第二个版本可以「恢复」——
         * 清空这一格就是回到出厂那段。
         */
        Box(
            Modifier.fillMaxWidth().heightIn(min = 190.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(skin.panelSink)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { t ->
                    text = t.take(1600)
                    app.field.edit { it.copy(aiPrompt = text) }
                },
                textStyle = TextStyle(fontSize = 12.sp, color = skin.ink, lineHeight = 19.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(skin.flame),
                modifier = Modifier.fillMaxWidth().fillMaxSize(),
            )
            if (text.isBlank()) {
                Text(
                    Assign.ASK, fontSize = 12.sp, color = skin.inkFaint, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun Pill(on: Boolean, onChange: (Boolean) -> Unit) {
    val s = LocalSkin.current
    val bg by animateColorAsState(if (on) s.flame else s.panelSink, tween(170), label = "pb")
    Box(
        Modifier.size(46.dp, 27.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (on) Color.Transparent else s.hair, CircleShape)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!on) },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier.padding(horizontal = 3.dp).size(21.dp).clip(CircleShape)
                .background(if (on) s.onFlame else s.inkFaint)
        )
    }
}

/**
 * 底部弹层。
 *
 * 用真 Dialog，不用页内 Box —— 页内 Box 有两个治不好的病：
 * 点空白处焦点不散、键盘收起后 imePadding 还按旧值留一大片白。
 * 独立窗口一关就干净。
 */
@Composable
internal fun Overlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    val s = LocalSkin.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val shut = {
        runCatching { focus.clearFocus() }
        onClose()
    }

    /*
     * 弹出来要有过程。
     *
     * Dialog 自己不带进场动画：窗口一开，整张纸和那层遮罩就是最终样子同时出现，
     * 观感就是「闪一下」。这里自己把两样东西从头演一遍 ——
     * 遮罩由淡到实，纸从下面升上来，两者同一条曲线同一个时长，才是一块东西在动。
     */
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }

    val geom = sheetGeom()
    val lift = geom[0]
    val d = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(d)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = shut,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(s.scrim.copy(alpha = s.scrim.alpha * rise.value))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { shut() }
        ) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer {
                        // 窗口越出去多少就抬回来多少。lift 为 0 的机器上，这一项是零。
                        translationY = (1f - rise.value) * 44.dp.toPx() - lift
                        alpha = rise.value
                    }
                    .then(
                        /*
                         * 窗口给得对的机器（lift = 0）：照旧让 Compose 的 insets 说话 ——
                         * 键盘、导航条、状态栏三样都让。
                         *
                         * 窗口越界的机器（lift > 0）：那一套在这台机器上量出来全是 0，
                         * 让了等于没让，改用 sheetGeom 量到的实数。
                         */
                        if (lift == 0) Modifier.windowInsetsPadding(
                            WindowInsets.ime
                                .union(WindowInsets.navigationBars)
                                .union(WindowInsets.statusBars)
                        ) else Modifier.padding(
                            top = with(d) { geom[1].toDp() },
                            bottom = with(d) { maxOf(geom[2], imePx).toDp() },
                        )
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(s.surface)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            ) { content() }
        }
    }
}

/**
 * 这张弹层纸在屏幕里到底有多少地方可以站。
 *
 * 同一份 `DialogProperties`，各家系统摆得不一样。小米 8 SE（Android 10）上，窗口就是
 * 「状态栏下面一横，到导航条上面一横」，纸贴在窗口底，正好落在底下那排字的上方；
 * K90 Pro Max（Android 17）上，窗口顶也从状态栏下面起算，高度却仍旧按整屏给 ——
 * 窗口底于是比屏幕底低一整个状态栏，纸跟着掉出去一截，底下那颗红按钮只剩一条边。
 *
 * 所以不猜。量一次窗口在屏幕上的实际矩形，超出去多少记下来；系统栏的真实厚度也一并量，
 * 弹层里读不到就退回资源里的标准值。
 *
 * 这整块包在 runCatching 里：它跑在 layout 回调上，抛一次就是整页崩。
 * 量不出来就用 0，弹层退回原来那条路，最坏是位置不对，不会挂。
 *
 * @return [越过屏幕底的像素, 顶部系统栏, 底部系统栏]
 */
@Composable
private fun sheetGeom(): IntArray {
    val view = androidx.compose.ui.platform.LocalView.current
    var geom by remember { mutableStateOf(intArrayOf(0, 0, 0)) }
    DisposableEffect(view) {
        val loc = IntArray(2)
        val read = {
            runCatching {
                view.getLocationOnScreen(loc)
                val over = loc[1] + view.height - view.resources.displayMetrics.heightPixels
                val bars = insetBarsPx(view.rootWindowInsets, view.resources)
                val next = intArrayOf(if (over > 0) over else 0, bars[0], bars[1])
                if (!next.contentEquals(geom)) geom = next
            }
        }
        val watch = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> read() }
        view.addOnLayoutChangeListener(watch)
        view.post { read() }
        onDispose { view.removeOnLayoutChangeListener(watch) }
    }
    return geom
}

/**
 * 系统栏上下沿的厚度，单位像素。
 *
 * `WindowInsets.Type.systemBars()` 是 API 30 才有的；minSdk 是 26，
 * 在 29 上直接调到就是个 `NoSuchMethodError`（Android 10 的机器一点就崩）。
 * 老机器走 `systemWindowInsetTop/Bottom`，那两个字段 API 20 就在。
 */
@Suppress("DEPRECATION")
private fun insetBarsPx(wi: android.view.WindowInsets?, res: android.content.res.Resources): IntArray {
    var top = 0
    var bottom = 0
    if (wi != null) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = insetBarsPxR(wi)
            top = b[0]; bottom = b[1]
        } else {
            top = wi.systemWindowInsetTop
            bottom = wi.systemWindowInsetBottom
        }
    }
    // 弹层窗口里这两项常常是 0（窗口自己不让），退回系统资源里写着的那两个值。
    if (top <= 0) top = resBarPx(res, "status_bar_height")
    if (bottom <= 0) bottom = resBarPx(res, "navigation_bar_height")
    return intArrayOf(top, bottom)
}

/**
 * API 30 起才有 `getInsets(Type)`。
 *
 * 这里走反射，不写死 `WindowInsets.Type.systemBars()` —— 那样类引用会留在字节码里，
 * 老机器上无论有没有 SDK 守卫，只要有一种预解析的验证器碰上就炸（就是这么崩的）。
 * 反射缺类时抛 ClassNotFoundException，被上面的 runCatching 吃掉，退回资源值。
 */
private fun insetBarsPxR(wi: android.view.WindowInsets): IntArray {
    val api = android.os.Build.VERSION.SDK_INT
    if (api < 30) return intArrayOf(0, 0)
    val type = Class.forName("android.view.WindowInsets\$Type")
    val bars = type.getMethod("systemBars").invoke(null) as Int
    val got = wi.javaClass.getMethod("getInsets", Int::class.javaPrimitiveType).invoke(wi, bars)
    val f = got.javaClass
    val top = f.getField("top").getInt(got)
    val bottom = f.getField("bottom").getInt(got)
    return intArrayOf(top, bottom)
}

private fun resBarPx(res: android.content.res.Resources, name: String): Int {
    val id = res.getIdentifier(name, "dimen", "android")
    return if (id > 0) res.getDimensionPixelSize(id) else 0
}

/** 卡片里的小节标记。 */
@Composable
internal fun Bare(text: String) {
    val s = LocalSkin.current
    Text(
        text, fontSize = 10.sp, color = s.inkFaint,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
internal fun Choices(title: String, onClose: () -> Unit, options: List<Pair<String, () -> Unit>>) {
    val s = LocalSkin.current
    Overlay(onClose) {
        /*
         * 选项多的时候要能往下滑：限高 + 可滚。不限的话，屏幕矮一点就只看得见前四五个，
         * 底下的悄无声息被裁掉，看着像没列全。
         */
        Column(
            Modifier.padding(vertical = 18.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, color = s.ink, fontWeight = FontWeight.SemiBold, fontFamily = s.titleFont)
                Spacer(Modifier.weight(1f))
                IconBtn(Icons.Default.Close, "收起", onClose, size = 32.dp)
            }
            Spacer(Modifier.height(6.dp))
            options.forEach { (label, act) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { act() }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, fontSize = 15.sp, color = s.ink)
                }
            }
        }
    }
}

@Composable
internal fun Toast(msg: String, onDone: () -> Unit) {
    val s = LocalSkin.current
    LaunchedEffect(msg) {
        kotlinx.coroutines.delay(1900)
        onDone()
    }
    Box(Modifier.fillMaxSize().padding(bottom = 94.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(s.surfaceHi)
                .border(1.dp, s.hair, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Text(msg, fontSize = 12.sp, color = s.ink)
        }
    }
}
