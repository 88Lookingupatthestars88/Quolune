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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalView
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
import com.pagefold.reader.field.Vault
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
import com.pagefold.reader.kit.tap
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

    /* 「加一台本机引擎」：点了才去扫，扫回来按引擎分组列成一张纸。 */
    var localAsk by remember { mutableStateOf(false) }
    var localScan by remember { mutableStateOf(false) }
    var localFound by remember {
        mutableStateOf<List<com.pagefold.reader.voice.LocalEngine>>(emptyList())
    }
    /* 扫到了、但一副也念不出来的台数。纸上不指名。 */
    var localMuted by remember { mutableIntStateOf(0) }

    /* 初值取上一回的账，切页回来第一帧就是真数，不会先画一排 0。 */
    var audioBytes by remember { mutableLongStateOf(Vault.audioBytes) }
    var audioCount by remember { mutableIntStateOf(Vault.audioCount) }
    var bookBytes by remember { mutableLongStateOf(Vault.bookBytes) }
    var bookCount by remember { mutableIntStateOf(Vault.bookCount) }
    var voiceBytes by remember { mutableLongStateOf(Vault.voiceBytes) }
    var voiceCount by remember { mutableIntStateOf(Vault.voiceCount) }
    var bgBytes by remember { mutableLongStateOf(Vault.bgBytes) }
    var bgCount by remember { mutableIntStateOf(Vault.bgCount) }
    var coverBytes by remember { mutableLongStateOf(Vault.coverBytes) }
    var coverCount by remember { mutableIntStateOf(Vault.coverCount) }
    var trialCount by remember { mutableIntStateOf(Vault.trialCount) }

    fun relist() {
        val ledger = app.synth.ledger()
        /* 段落那两行账，按账本算，不按目录算。 */
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

        Vault.audioBytes = audioBytes
        Vault.audioCount = audioCount
        Vault.bookBytes = bookBytes
        Vault.bookCount = bookCount
        Vault.voiceBytes = voiceBytes
        Vault.voiceCount = voiceCount
        Vault.bgBytes = bgBytes
        Vault.bgCount = bgCount
        Vault.coverBytes = coverBytes
        Vault.coverCount = coverCount
        Vault.trialCount = trialCount
    }

    /* 数账。enginesJson 每敲一个字就变一次，直接跟着它跑就是每敲一下全盘扫一遍：账本里的每一段都要 File.length()。 */
    var scanned by remember { mutableStateOf(false) }
    LaunchedEffect(s.enginesJson) {
        if (scanned) delay(400)
        scanned = true
        val bad = withContext(Dispatchers.IO) { runCatching { relist() }.exceptionOrNull() }
        if (bad != null) {
            msg = "库房那几行没数出来：" + (bad.message ?: bad.javaClass.simpleName)
        }
    }

    /* 存图、缩图在 IO 上，这段空档按钮写「正在放…」。 */
    var piecing by remember { mutableStateOf(false) }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val page = pickFor
        if (uri == null || page == null) return@rememberLauncherForActivityResult
        piecing = true
        scope.launch {
            val v = Bg.take(app, page, uri)
            piecing = false
            if (v != null) {
                app.field.edit { it.withBg(page, v) }
                relist()
            } else {
                msg = "这张读不出来，换一张"
            }
        }
    }

    /* 崩过才有的记录。读在列表外面 —— 列表 DSL 里不是可组合作用域，拿不到 remember。 */
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
                        /* 本机那几台排在云端前面。 */
                        val local = engines.filter { it.protocol == "system" }
                        val cloud = engines.filter { it.protocol != "system" }

                        local.forEachIndexed { i, e ->
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
                                onDrop = {
                                    app.field.edit { st ->
                                        st.withEngines(engines.filterNot { it.id == e.id })
                                    }
                                    // 同一台引擎还有别的条在用它的话音表，那就留着。
                                    if (engines.none { it.id != e.id && it.base == e.base }) {
                                        com.pagefold.reader.voice.SysCache.forget(e.base)
                                    }
                                    msg = "已经拿掉了"
                                },
                            )
                        }
                        if (local.isNotEmpty()) Hair()
                        AddRow("加一台本机引擎") {
                            localAsk = true
                            /* 这一趟已经扫过了，直接用那张表：再扫一遍要等几秒，挑的时候也白丢已勾的。 */
                            if (localFound.isNotEmpty()) return@AddRow
                            localScan = true
                            scope.launch {
                                val machines = withContext(Dispatchers.IO) {
                                    runCatching { app.sys.engines() }.getOrDefault(emptyList())
                                }
                                val swept = runCatching { app.sys.sweep(machines) }.getOrNull()
                                // 读回来的话音表收下：声源里那几条要靠它认语种。
                                swept?.engines?.forEach {
                                    com.pagefold.reader.voice.SysCache.memorize(it.pkg, it.voices)
                                }
                                localFound = swept?.engines.orEmpty()
                                localMuted = swept?.muted ?: 0
                                localScan = false
                            }
                        }
                        Hair()

                        cloud.forEachIndexed { i, e ->
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
            // 这些不是声源：上面是把字念成声音，这里是替人拿主意。目前只给派角用。
            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            item { Chapter("接口") }
            /* 这行字回答「这一节跟上面那七家有什么区别」——上面是把字念成声音，这里是「替人拿主意」，而且只服务派角那一件事。 */
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
                                onDrop = if (com.pagefold.reader.voice.ChatPresets.byId(c.id) != null) null else ({
                                    app.field.edit { st ->
                                        st.withChats(chats.filterNot { it.id == c.id })
                                    }
                                    msg = "已经拿掉了"
                                }),
                            )
                        }
                        Hair()
                        AddRow("加一家自己的接口") {
                            app.field.edit { st ->
                                st.withChats(
                                    st.chats + com.pagefold.reader.voice.ChatCfg(
                                        id = "u" + System.currentTimeMillis().toString(36),
                                        name = "自己的接口",
                                        base = "",
                                        key = "",
                                        model = "",
                                        on = true,
                                    )
                                )
                            }
                            msg = "填上地址和钥匙就能用"
                        }
                        Hair()
                    }
                }
            }

            item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 22.dp)) }
            // ── 派角 ──
            // 起手那一档：每本书点「让 AI 排」时先照这里，书里还能临时改。
            item { Chapter("派角") }
            item {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Quiet {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            RowLine("让 AI 新造嗓音") {
                                Pill(s.aiDesign) { on ->
                                    if (on && !s.canDesign) {
                                        msg = "造嗓音要小米那一路开着。先去「声源」里把它拨开"
                                    } else {
                                        app.field.edit { it.copy(aiDesign = on) }
                                    }
                                }
                            }
                            /* 这一行回答「为什么打开了它还是只从现成的里挑」——现造嗓音只有小米能做。 */
                            Text(
                                "只限 MiMo 引擎。",
                                fontSize = 11.sp, color = skin.inkFaint,
                                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                            )
                            Hair()
                            Spacer(Modifier.height(8.dp))
                            AiVoicePick(s, app) { msg = it }
                            Hair()
                            Spacer(Modifier.height(8.dp))
                            AiAskField(s)
                        }
                    }
                }
            }

            // ── 新书照这个来 ──
            // 只管新收进来的书。已进库的书各按各自的「这一本怎么读」。
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
                            "改动自下一次导入起生效，已导入的书保持原样。单本调整在该书的「怎么读」中进行。",
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
                                hint = "正文字号，单位 sp。默认 19。"
                            )
                            Ruler(
                                "行距", s.lineGap.toFloat(), 110f, 220f, 5f,
                                { app.field.edit { c -> c.copy(lineGap = it.roundToInt()) } },
                                unit = "%", low = "110", high = "220",
                                hint = "行高与字号的百分比，150 即 1.5 倍行高。"
                            )
                            Ruler(
                                "页边距", s.padH.toFloat(), 8f, 48f, 2f,
                                { app.field.edit { c -> c.copy(padH = it.roundToInt()) } },
                                unit = "dp", low = "8", high = "48",
                                hint = "正文左右边距，单位 dp。数值增大，单行字数减少。"
                            )
                            Ruler(
                                "段间距", s.paraGap.toFloat(), 0f, 40f, 2f,
                                { app.field.edit { c -> c.copy(paraGap = it.roundToInt()) } },
                                unit = "sp", low = "0", high = "40",
                                hint = "段落间的额外空白，单位 sp。0 表示不加空白，仅换行。"
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
                            RowLine("按段落断语气") {
                                Pill(s.cue) { app.field.edit { c -> c.copy(cue = it) } }
                            }
                            Hair()
                            Bare("驿马（先备多少）")
                            Ruler(
                                "先备多少字", s.prefetchChars.toFloat(), 600f, 12000f, 300f,
                                { app.field.edit { c -> c.copy(prefetchChars = it.roundToInt()) } },
                                unit = "字", low = "600", high = "12000",
                                hint = "往后提前合成的字数。开播时一次发满，播到剩一半再补齐。"
                            )
                            Ruler(
                                "同时开几路", s.concurrency.toFloat(), 1f, 10f, 1f,
                                { app.field.edit { c -> c.copy(concurrency = it.roundToInt()) } },
                                unit = "路", low = "1", high = "10",
                                hint = "同时向服务商发出的请求数。开播时几路一起发；块已经很大时，一两路就够。"
                            )
                            Ruler(
                                "传输间隔", (s.reqIntervalMs / 100f).coerceIn(0f, 30f), 0f, 30f, 1f,
                                { app.field.edit { c -> c.copy(reqIntervalMs = (it * 100).roundToInt()) } },
                                unit = "秒", low = "0", high = "3.0",
                                format = { "%.1f".format(it / 10f) },
                                hint = "向同一服务商发起两次请求的最小间隔，本机限流，与合成速度无关。数值减小，后续段落到达更快，触发限流的概率上升；数值增大，请求更稳，出音延迟增加。出现「服务商已限流」时上调此项。"
                            )
                            Ruler(
                                "遇到失误，重试", s.retry.toFloat(), 0f, 5f, 1f,
                                { app.field.edit { c -> c.copy(retry = it.roundToInt()) } },
                                unit = "次", low = "0", high = "5",
                                hint = "单段合成失败后的重试次数。超时、429、返回空音频均计为失败。0 表示不重试，该段跳过，继续合成下一段。"
                            )
                            Ruler(
                                "失误后等待时长", (s.retryDelayMs / 100f).coerceIn(2f, 50f), 2f, 50f, 2f,
                                { app.field.edit { c -> c.copy(retryDelayMs = (it * 100).roundToInt()) } },
                                unit = "秒", low = "0.2 秒", high = "5.0 秒",
                                format = { "%.1f".format(it / 10f) },
                                hint = "两次重试之间的固定间隔，单位秒。取值过小时，同一故障在短时间内被连续命中，重试成功率下降。"
                            )
                            Ruler(
                                "往后先备几章", s.cacheChapters.toFloat(), 1f, 20f, 1f,
                                { app.field.edit { c -> c.copy(cacheChapters = it.roundToInt()) } },
                                unit = "章", low = "1", high = "20",
                                hint = "从「从第几章起」那一格往后备几章，存进本机。"
                            )
                            Spacer(Modifier.height(6.dp))
                            Btn(
                                "照默认这套",
                                {
                                    app.field.edit {
                                        it.copy(
                                            prefetchChars = 3600, reqIntervalMs = 600, concurrency = 2,
                                            retry = 3, retryDelayMs = 1200, cacheChapters = 5,
                                            cue = true,
                                        )
                                    }
                                    msg = "眼下先备 3600 字，两路一起取，中间隔 0.6 秒。"                                },
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
                            onClick = { toVault(StoreKind.AUDIO) }
                        )
                        Hair()
                        VaultRow(
                            "库里的书",
                            "$bookCount 本 · ${size(bookBytes)}",
                            onClick = { toVault(StoreKind.BOOK) }
                        )
                        Hair()
                        VaultRow(
                            "自配的嗓音",
                            "$voiceCount 个 · ${size(voiceBytes)}",
                            onClick = { toVault(StoreKind.VOICE) }
                        )
                        Hair()
                        VaultRow(
                            "试听留下的",
                            "$trialCount 段",
                            onClick = { toVault(StoreKind.TRIAL) }
                        )
                        Hair()
                        VaultRow(
                            "换过的封面",
                            "$coverCount 张 · ${size(coverBytes)}",
                            onClick = { toVault(StoreKind.COVER) }
                        )
                        Hair()
                        VaultRow(
                            "换过的底子",
                            "$bgCount 张 · ${size(bgBytes)}",
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

            /* 真崩过才有的一节。栈在 files/last-crash.txt，合成线程写的是 last-crash-player.txt。 */
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
                busy = piecing,
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

if (localAsk) {
    /* 已经在声源里的那几副不再列。 */
    val taken = engines.filter { it.protocol == "system" }
        .mapNotNull { it.voices?.trim()?.ifBlank { null } }.toSet()
    LocalPick(
        found = localFound,
        muted = localMuted,
        scanning = localScan,
        taken = taken,
        onClose = { localAsk = false },
        onImport = { picks ->
            localAsk = false
            app.field.edit { st ->
                st.withEngines(
                    st.engines + picks.map { (eng, v) ->
                        EngineCfg(
                            id = Engines.sysId(eng.pkg, rawOf(v.param)),
                            name = eng.label + " · " + v.name,
                            kind = "SYSTEM",
                            base = eng.pkg,
                            proto = "system",
                            voices = v.param,
                            on = true,
                        )
                    }
                )
            }
            msg = "加进来 ${picks.size} 副"
        },
    )
}

msg?.let { Toast(it) { msg = null } }
}
}

private fun noteOf(e: EngineCfg): String {
    if (!e.on) return "关"
    /* 读到几副报几副；还在读就报「正在读」。 */
    if (e.protocol == "system") {
        val n = com.pagefold.reader.voice.SysCache.count(e.base)
        if (n < 0) return "正在读它的话音"
        if (n == 0) return "这台没装话音"
        /* 一副话音一条。这一条配的那副话不在了，多半是引擎那边删掉了。 */
        val v = com.pagefold.reader.voice.SysCache.of(e.base)
            .firstOrNull { it.param == (e.voices ?: "").trim() }
        return v?.desc?.ifBlank { "本机" } ?: "这副话音不在了"
    }
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
// 那道线往右化开。
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
.tap { onClick() }
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
/* 空态那圈光。定音一滚就重组，别每次新建一个 Brush。 */
    val emptyBrush = remember(skin.ground, skin.spotA) {
        androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(skin.spotA.copy(alpha = 0.75f), skin.ground),
            center = androidx.compose.ui.geometry.Offset(18f, 14f),
            radius = 130f
        )
    }
    Column(
        Modifier.width(60.dp)
            .tap { onPick(page) }
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
                Box(Modifier.fillMaxSize().background(emptyBrush))
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
    busy: Boolean,
    skin: Skin,
    onClose: () -> Unit,
    onPick: () -> Unit,
    onAdjust: () -> Unit,
    onTheme: () -> Unit,
) {
    val spec = BgSpec.decode(current)
    /* 预览格的高度跟着屏幕走。写死 190 的时候，屏幕矮一档（或者系统字体调大）这张纸的固定高度就超过了能用的那截，红按钮被裁在屏幕外。 */
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
                    when {
                        busy -> "正在放…"
                        spec == null -> "挑一张图"
                        else -> "换一张"
                    },
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
                    .tap { onBack() }
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
    /* 样张里的段间距必须跟正文那套算法一字不差：正文那条空行的高度就是paraGap（sp），0 就贴住。 */
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

/** 一张表底下那一行「再加一条」。 */
@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().tap { onClick() }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("＋", fontSize = 14.sp, color = s.flame)
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 13.sp, color = s.flame)
    }
}

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
    // 这三个字段可能是 Gson 塞进来的 null，输入框不收 null。
    var base by remember(e.id) { mutableStateOf(e.base ?: "") }
    var key by remember(e.id) { mutableStateOf(e.key ?: "") }
    var model by remember(e.id) { mutableStateOf(e.model ?: "") }
    /* 造嗓音、复刻嗓音各用哪个模型。 */
    var design by remember(e.id) { mutableStateOf(e.modelDesign ?: "") }
    var clone by remember(e.id) { mutableStateOf(e.modelClone ?: "") }
    var voices by remember(e.id) { mutableStateOf(e.voices ?: "") }
    // 这家清单里有什么，就照原样列什么。不筛、不分组。
    var fetched by remember(e.id) { mutableStateOf<List<String>>(emptyList()) }

    /* 改完就验，结果写在这一行的副标题上。一样没变就直接摆上一次的结果，不发请求 —— 定音页是常来的地方，不该每次进来都等一会儿。 */
    var verdict by remember(e.id) { mutableStateOf<String?>(null) }
    var trying by remember(e.id) { mutableStateOf(false) }
    val vScope = rememberCoroutineScope()

    /* 进来不发请求。定音一页七家，进一次就是十几个请求同时出去。 */
    LaunchedEffect(e.id, e.model, e.key, e.base, e.on, e.protocol) {
        verdict = null
        if (!e.on) return@LaunchedEffect
        if (e.protocol == "system") return@LaunchedEffect
        val m = (e.model ?: "").trim()
        val k = (e.key ?: "").trim()
        if (k.isBlank()) return@LaunchedEffect
        // 本地能判的三样，先判掉，一个请求都不发。DeepSeek 没有模型这一格。
        if (e.protocol != "deepseek") {
            if (m.isBlank()) { verdict = "模型空着，念不了"; return@LaunchedEffect }
            if (Why.notSpeech(m, e.protocol)) { verdict = "识别模型，念不了"; return@LaunchedEffect }
        }
        verdict = Speak.probeRemembered(e.protocol, e.base ?: "", k, m)
    }

    /** 按一下才发一次。结果落盘，下次进来直接摆。 */
    fun tryProbe() {
        val m = (e.model ?: "").trim()
        val k = (e.key ?: "").trim()
        if (trying) return
        if (k.isBlank()) return
        if (e.protocol != "deepseek" && m.isBlank()) return
        vScope.launch {
            trying = true
            verdict = "正在测试中"
            val why = when (e.protocol) {
                "mimo" -> Speak.probeMimo(e.base ?: "", k, m)
                "deepseek" -> com.pagefold.reader.voice.Deepseek.probe(
                    e.base ?: "", k,
                    e.voiceList().firstOrNull().orEmpty()
                )
                else -> Speak.probe(
                    e.base ?: "", k, m,
                    com.pagefold.reader.field.Presets.voicesFor(e.base ?: "", e.protocol).firstOrNull().orEmpty()
                )
            }
            verdict = why ?: Speak.OK
            trying = false
        }
    }

    /* 一进这一页、或者钥匙一变，就去要这家的模型清单。 */
    LaunchedEffect(e.id, e.key, e.base, e.proto, e.on) {
        val k = e.key ?: ""
        if (k.isBlank() || !e.on) { fetched = emptyList(); return@LaunchedEffect }
        /* 这家的清单里没有能念的东西，不问。 */
        if (e.protocol == "deepseek") { fetched = emptyList(); return@LaunchedEffect }
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
                    /* 三种颜色，三种意思：平时是灰的，测试中是淡的，念不出来才是红的。 */
                    color = when {
                        verdict == null -> s.inkFaint
                        verdict == "正在测试中" -> s.inkDim
                        verdict == "念得出来" -> s.flame
                        else -> s.bad
                    },
                    maxLines = 1,
                )
            }
            /* 「验」。按一下才发一次。进来就自己发的话，七家一起出去，页面在这一串里发木；而且有的家按字算钱，不打招呼就花掉是不对的。 */
            if (e.on && !e.key.isNullOrBlank() && e.protocol != "system") {
                Text(
                    if (trying) "…" else "验",
                    fontSize = 11.sp,
                    color = if (trying) s.inkFaint else s.inkDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .tap { tryProbe() }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            if (onDrop != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .tap { onDrop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", fontSize = 17.sp, color = s.inkFaint)
                }
            }
            Spacer(Modifier.width(4.dp))
            Pill(e.on, onToggle)
        }

        if (!e.on) return@Column
        /* 本机那一条只有开关和名字：话音在加的时候挑过了。 */
        if (e.protocol == "system") return@Column

        Spacer(Modifier.height(4.dp))

        /* 改一格，整副配置重新交上去。 */
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

        /* 模型。清单全部照列，一条不筛。 */
        if (e.protocol == "mimo") {
            InlineField("朗读模型", model, { model = it; push() }, "")
            Chips(fetched.filter { it.contains("tts") && !it.contains("design") && !it.contains("clone") }, model) {
                model = it; push()
            }

            InlineField("设计模型", design, { design = it; push() }, "")
            Chips(fetched.filter { it.contains("design") }, design) { design = it; push() }

            InlineField("克隆模型", clone, { clone = it; push() }, "")
            Chips(fetched.filter { it.contains("clone") }, clone) { clone = it; push() }
        } else if (e.protocol == "deepseek") {
            /* 这家没有模型这一格：地址后面直接挂音色名，音色在音色页挑。 */
        } else {
            /* 能复刻的那几家（硅基、MiniMax）也分两格。 */
            /* 朗读模型：清单里那些「一定不念书」的不摆出来。 */
            InlineField("朗读模型", model, { model = it; push() }, "")
            Chips(fetched.filter { !Why.notSpeech(it) || it == model }, model) { model = it; push() }

            if (com.pagefold.reader.field.Presets.cloneFor(e.base ?: "", e.protocol).isNotEmpty()) {
                /* 克隆模型：这一格不是从清单里挑，是从这家认的那一个里挑。 */
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

        /* 音色不在这儿挑 —— 它在音色页。 */
    }
}

/**
* 「这家有哪些模型」的进程级备忘，键是地址 + 钥匙，滚动不重拉。
*/
private object ChatMemo {
    private val mem = HashMap<String, List<String>>()
    private var file: java.io.File? = null
    private var loaded = false

    /** 落一份在 files/chat-models.json —— 跟声源那份 models.json 是同一个路子。不落盘的话，进程一重启就全忘：再进定音又去要一遍，用户看到的就是「每次进来都重新拉」。 */
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

    /** 这一对「地址 + 钥匙」问过没有。 */
    fun knows(k: String): Boolean = mem.containsKey(k)

    fun put(k: String, v: List<String>) {
        mem[k] = v
        save()
    }
}

/** 接口那一行。比嗓源少两件事：不用选音色，也没有协议 —— 都是 OpenAI 兼容口。 */
@Composable
private fun ChatBlock(
    e: com.pagefold.reader.voice.ChatCfg,
    app: App,
    onToggle: (Boolean) -> Unit,
    onEdit: (com.pagefold.reader.voice.ChatCfg) -> Unit,
    onDrop: (() -> Unit)? = null,
) {
    val s = LocalSkin.current
    var name by remember(e.id) { mutableStateOf(e.name) }
    var base by remember(e.id) { mutableStateOf(e.base ?: "") }
    var key by remember(e.id) { mutableStateOf(e.key ?: "") }
    var model by remember(e.id) { mutableStateOf(e.model ?: "") }
    /* 这一格是「地址 + 钥匙」问来的清单。 */
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

    /* 进来只做一件事：把盘上那份摆出来。 */
    LaunchedEffect(sigKey, e.on) {
        fetched = ChatMemo.get(sigKey).orEmpty()
        if (!e.on) return@LaunchedEffect
        val k = key.trim()
        if (k.isBlank() || Auth.why(k) != null) return@LaunchedEffect
        if (ChatMemo.knows(sigKey)) { autoPick(fetched); return@LaunchedEffect }
        /* 手指停下 0.8 秒才去要。 */
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
                        .tap {
                            if (!busy) {
                                busy = true
                                verdict = null
                                scope.launch {
                                    verdict = com.pagefold.reader.voice.Chat.hello(e.copy(model = model))
                                    busy = false
                                }
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            if (onDrop != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .tap { onDrop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", fontSize = 17.sp, color = s.inkFaint)
                }
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
            /* 全交给 Chips：前面 12 个摆出来，剩下的收进「还有 N 个」。 */
            Chips(fetched, model) { model = it; push() }
        }
        /* 「刷新清单」一直都在：清单问过就摆着，不会自己重问。 */
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
                        .tap { repull() }
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
* 一排可选的字。
*
* @param multi  true = 多选（音色那一栏用），点一下加、再点一下去掉
* @param picked 多选时当前选中的
* @param open   true = 全铺开，false = 只铺前 [CAP_CHIPS] 个、末尾留一个「还有 N 个」
* @param onOpen 末尾那个「还有 N 个」被点到了
*/
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    options: List<String>,
    current: String,
    multi: Boolean = false,
    picked: List<String> = emptyList(),
    open: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    val s = LocalSkin.current
    if (options.isEmpty()) return
    /* 没接外部状态的那几处（声源、模型清单）自己记展开；派角那一处走 Settings，退出应用也留着。 */
    var inner by remember(options.size) { mutableStateOf(false) }
    val isOpen = if (onOpen == null) inner else open
    /* 换行排，不横滚：横滑看不出后面还有几条。 */
    val head = options
    val shown = if (isOpen || head.size <= CAP_CHIPS) head else head.take(CAP_CHIPS)
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        shown.forEach { o ->
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
                    .tap { onPick(o) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        if (!isOpen && head.size > CAP_CHIPS) {
            /* 这一格得像个能按的东西：外面一道边、朱色字。不然看着只是一行灰字，没人会去点。 */
            Text(
                "展开 ${head.size - CAP_CHIPS} 个",
                fontSize = 11.sp,
                color = s.flame,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, s.flame.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .tap { if (onOpen != null) onOpen() else inner = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** 一行里先摆几个。再多就折起来 —— 见 [Chips]。 */
private const val CAP_CHIPS = 12

/**
* 一行可编辑的字。
*
* @param secret true = 默认盖住（钥匙那一格用）。右边一只眼睛，点一下才看得见。
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
    /* 钥匙默认是盖住的。一进这一页就能看见整串 key，等于谁凑过来都能抄走 —— 截屏、录屏、旁边站着人，全露着。 */
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
                    .tap { open = !open },
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
private fun VaultRow(name: String, amount: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .tap { onClick() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 14.sp, color = s.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(amount, fontSize = 11.sp, color = s.inkDim)
            }
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
internal fun RowLine(label: String, trailing: @Composable () -> Unit) {
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
            .tap { onClick() }
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

/** 让 AI 用哪几副嗓子。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiVoicePick(s: Settings, app: App, say: (String) -> Unit) {
    val skin = LocalSkin.current
    val all = remember(s.enginesJson, app.box.custom().size, com.pagefold.reader.voice.SysCache.rev) {
        runCatching { Catalog.ours(s.engines, app.box.custom()).map { it.name }.distinct() }
            .getOrDefault(emptyList())
    }
    val picked = s.aiVoiceSet ?: all.toSet()

    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("让 AI 使用的嗓音", fontSize = 15.sp, color = skin.ink, modifier = Modifier.weight(1f))
            Text("${picked.size} / ${all.size}", fontSize = 11.sp, color = skin.inkFaint)
        }
        /* 这一行回答「我在这儿挑了嗓子，为什么书里还是派给别人」——它管的是整本书的派角。 */
        Text(
            "整本书「让 AI 排」的时候，只从这几副里派。",
            fontSize = 11.sp, color = skin.inkFaint, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (all.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("音色库里一副都没有。先去「声源」里开一路。", fontSize = 11.sp, color = skin.inkFaint)
        } else {
            Chips(
                all, "", multi = true, picked = picked.toList(),
                open = s.aiVoiceOpen,
                onOpen = { app.field.edit { it.copy(aiVoiceOpen = true) } },
            ) { n ->
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
                if (s.aiVoiceOpen) {
                    Btn("收起", { app.field.edit { it.copy(aiVoiceOpen = false) } }, BtnKind.GHOST)
                }
            }
        }
    }
}

/** 发给它的那段话。名单和返回格式不在这儿 —— 那两段是接口约定，改了整份结果就解析不出来。 */
@Composable
private fun AiAskField(s: Settings) {
    val skin = LocalSkin.current
    /* 这段是发出去的原样，只给人看，不给改 —— 改一个字整份结果就可能解析不出来。 */
    val text = s.aiPrompt.ifBlank { Assign.ASK }
    var open by remember { mutableStateOf(false) }

    Column(Modifier.padding(vertical = 6.dp)) {
        Text("发给它的话", fontSize = 15.sp, color = skin.ink)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(skin.panelSink)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text,
                fontSize = 12.sp, color = skin.inkDim, lineHeight = 19.sp,
                /* 收起时露七行，够看出这一段在讲什么。 */
                maxLines = if (open) Int.MAX_VALUE else 7,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Btn(
            if (open) "收起" else "展开看全文",
            { open = !open },
            BtnKind.GHOST,
        )
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
            .tap { onChange(!on) },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier.padding(horizontal = 3.dp).size(21.dp).clip(CircleShape)
                .background(if (on) s.onFlame else s.inkFaint)
        )
    }
}

/** 底部弹层。用真 Dialog：页内 Box 点空白不散焦点，也盖不住底栏。 */
@Composable
internal fun Overlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    val s = LocalSkin.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val shut = {
        runCatching { focus.clearFocus() }
        onClose()
    }

    /* 弹出来要有过程。Dialog 自己不带进场动画：窗口一开，整张纸和那层遮罩就是最终样子同时出现，观感就是「闪一下」。 */
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = shut,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        /* 量一次视图在屏幕上的位置和高度，越界多少挪回多少。
屏幕高用 maximumWindowMetrics。 */
        val host = LocalView.current
        var shiftPx by remember { mutableStateOf(0f) }
        var screenPx by remember { mutableStateOf(0) }
        DisposableEffect(host) {
            val loc = IntArray(2)
            val update = {
                val screen = screenHeightPx(host.context)
                if (screen > 0 && host.height > 0) {
                    host.getLocationOnScreen(loc)
                    val down = loc[1] + host.height - screen
                    val up = -loc[1]
                    /* 超过三分之一屏的数一定是量错了，宁可不动。 */
                    val lim = screen / 3
                    shiftPx = when {
                        down in 1 until lim -> -down.toFloat()
                        up in 1 until lim -> up.toFloat()
                        else -> 0f
                    }
                    screenPx = screen
                }
            }
            val vto = host.viewTreeObserver
            vto.addOnGlobalLayoutListener(update)
            host.post(update)
            onDispose { vto.removeOnGlobalLayoutListener(update) }
        }

        BoxWithConstraints(
            Modifier.fillMaxSize().graphicsLayer { translationY = shiftPx }
        ) {
            val d = LocalDensity.current
            val statusDp = with(d) { WindowInsets.statusBars.getTop(this).toDp() }
            val navDp = with(d) { WindowInsets.navigationBars.getBottom(this).toDp() }
            /* 遮罩让开状态栏。 */
            val liftedDp = with(d) { if (shiftPx < 0f) (-shiftPx).toDp() else 0.dp }
            val scrimTop = maxOf(statusDp, liftedDp)
            /* 键盘只在真立起来的时候让位：真实键盘至少占屏幕三成，报得比这小的都是假数。 */
            val imePx = WindowInsets.ime.getBottom(d)
            val imeDp = with(d) {
                if (imePx > constraints.maxHeight * 3 / 10) imePx.toDp() else 0.dp
            }
            /* 纸最多长到屏幕可用高度。窗口比屏幕大时，以屏幕为准。 */
            val room = if (screenPx > 0) with(d) { screenPx.toDp() } - statusDp - navDp else maxHeight
            val cap = (minOf(maxHeight - scrimTop, room) - imeDp).coerceAtLeast(120.dp)

            /* 遮罩让开状态栏那一条：时间不跟着变灰。 */
            Box(
                Modifier.fillMaxSize().padding(top = scrimTop)
                    .background(s.scrim.copy(alpha = s.scrim.alpha * rise.value))
                    .tap { shut() }
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer {
                        /* 只剩进场那一下的上浮。整棵的位置由 shiftPx 管。 */
                        translationY = (1f - rise.value) * 44.dp.toPx()
                        alpha = rise.value
                    }
                    /* 键盘让在纸外面：纸整张浮在键盘之上，底边仍是圆角。 */
                    .padding(bottom = imeDp)
                    .heightIn(max = cap)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(s.surface)
                    .tap {}
                    /* 导航条让在纸里面 —— 纸的底色要一直铺到屏幕底。 */
                    .padding(bottom = if (navDp > 0.dp && navDp < 60.dp) navDp else 0.dp)
            ) { content() }
        }
    }
}

/** 屏幕真实高度（含状态栏与导航条）。 */
private fun screenHeightPx(ctx: android.content.Context): Int {
    val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
        ?: return 0
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        return runCatching { wm.maximumWindowMetrics.bounds.height() }.getOrDefault(0)
    }
    val p = android.graphics.Point()
    @Suppress("DEPRECATION")
    return runCatching { wm.defaultDisplay.getRealSize(p); p.y }.getOrDefault(0)
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

/** 本机话音的 param 形如 `包名|话音名|语种`，中间那一段就是引擎自己认的名字。 */
private fun rawOf(param: String): String = param.split('|').getOrElse(1) { param }

/**
 * 「机器上有什么话音」那张纸：按引擎分组，一副话音一行，勾几副加几副。
 *
 * @param taken 已经在声源里的那几副，不再列
 */
@Composable
private fun LocalPick(
    found: List<com.pagefold.reader.voice.LocalEngine>,
    muted: Int,
    scanning: Boolean,
    taken: Set<String>,
    onClose: () -> Unit,
    onImport: (List<Pair<com.pagefold.reader.voice.LocalEngine, com.pagefold.reader.voice.Voice>>) -> Unit,
) {
    val s = LocalSkin.current
    var picked by remember(found) { mutableStateOf<Set<String>>(emptySet()) }
    val groups = remember(found, taken) {
        found.map { e -> e to e.voices.filter { it.param !in taken } }
            .filter { it.second.isNotEmpty() }
    }
    val title = when {
        scanning -> "正在读机器上的朗读引擎"
        found.isNotEmpty() && groups.isNotEmpty() -> "机器上的话音"
        found.isNotEmpty() -> "机器上的都加过了"
        muted > 0 -> "没找到能出声的引擎"
        else -> "没找到朗读引擎"
    }
    val hint = when {
        scanning -> "一台一台试念一句，慢几秒"
        found.isNotEmpty() && groups.isNotEmpty() -> null
        found.isNotEmpty() -> "装了新的朗读引擎再回来，这里会重新找"
        muted > 0 -> "装的那几台念不出声，去它们自己的设置里把语音包下下来"
        else -> "先在手机里装一个朗读引擎"
    }

    Overlay(onClose) {
        Column(
            Modifier.padding(vertical = 18.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title, fontSize = 16.sp, color = s.ink,
                    fontWeight = FontWeight.SemiBold, fontFamily = s.titleFont
                )
                Spacer(Modifier.weight(1f))
                IconBtn(Icons.Default.Close, "收起", onClose, size = 32.dp)
            }
            Spacer(Modifier.height(6.dp))
            if (hint != null) {
                Text(
                    hint, fontSize = 12.sp, color = s.inkDim, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            } else {
                groups.forEach { (eng, list) ->
                    Text(
                        eng.label, fontSize = 10.sp, color = s.inkFaint,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp)
                    )
                    list.forEach { v ->
                        val on = v.param in picked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .tap {
                                    picked = if (on) picked - v.param else picked + v.param
                                }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (on) s.flame.copy(alpha = 0.14f) else s.panelSink)
                                    .border(1.dp, if (on) s.flame else s.hair, RoundedCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (on) Text("✓", fontSize = 11.sp, color = s.flame)
                            }
                            Spacer(Modifier.width(11.dp))
                            Text(
                                v.name, fontSize = 14.sp,
                                color = if (on) s.ink else s.inkDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (v.desc.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    v.desc, fontSize = 10.sp, color = s.inkFaint,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Btn(
                        if (picked.isEmpty()) "挑一副" else "导入选中的 ${picked.size} 副",
                        {
                            onImport(
                                groups.flatMap { (eng, list) ->
                                    list.filter { it.param in picked }.map { eng to it }
                                }
                            )
                        },
                        BtnKind.PRIMARY,
                        Modifier.weight(1f),
                        enabled = picked.isNotEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun Choices(title: String, onClose: () -> Unit, options: List<Pair<String, () -> Unit>>) {
    val s = LocalSkin.current
    Overlay(onClose) {
        /* 选项多的时候要能往下滑：限高 + 可滚。 */
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
                        .tap { act() }
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
