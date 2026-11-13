package com.pagefold.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.book.BookMeta
import com.pagefold.reader.book.CastBook
import com.pagefold.reader.book.CastName
import com.pagefold.reader.book.CastRole
import com.pagefold.reader.book.CastRule
import com.pagefold.reader.book.Counts
import com.pagefold.reader.book.Look
import com.pagefold.reader.book.Match
import com.pagefold.reader.book.RuleCodec
import com.pagefold.reader.book.Words
import androidx.compose.ui.platform.LocalContext
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.IconBtn
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Paper
import com.pagefold.reader.kit.PickChip
import com.pagefold.reader.kit.T
import com.pagefold.reader.kit.tap
import com.pagefold.reader.field.VoiceSource
import com.pagefold.reader.voice.AiJob
import com.pagefold.reader.voice.Assign
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Chat
import com.pagefold.reader.voice.Chats
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 派角。一本书一张表。三段：旁白和对白 → 角色 → 规则。 */
@Composable
fun CastScreen(
    book: BookMeta?,
    app: App,
    onChanged: () -> Unit = {},
    /** 点某一句下面的「跳到书里」：跳回书里那一处。 */
    onJump: ((ch: Int, at: Int) -> Unit)? = null,
    /** 章节目录。有话要按章分组，没它只能写「第 12 章」。 */
    chapters: List<com.pagefold.reader.book.Chapter> = emptyList(),
    /** 是当一张纸抽上来的（压在阅读页上），还是独占一页（从定音进来）。 */
    sheet: Boolean = false,
    onBack: () -> Unit,
) {
    val s = LocalSkin.current
    val scope = rememberCoroutineScope()
    val groups = remember(book?.id, app.box.custom().size) {
        Catalog.groups(app.field.now.engines, app.box.custom())
    }

    var cast by remember(book?.id) { mutableStateOf(loadCast(app, book)) }
    var msg by remember { mutableStateOf<String?>(null) }

    /** 0 旁白、1 对白。null = 那张纸没开。 */
    var personEdit by remember { mutableStateOf<Int?>(null) }
    /** 正在看谁的话。单人的纸是「他的话 + 嗓子」，组的纸是「嗓子 + 成员」。 */
    var voiceEdit by remember { mutableStateOf<String?>(null) }
    /** 台账（这本书里谁在说话）开没开。 */
    var ledger by remember { mutableStateOf(false) }
    /** 正在改第几条规则。-1 = 新加一条。 */
    var ruleEdit by remember { mutableStateOf<Int?>(null) }
    /** 加人那张纸：0 没开、1 选一个还是多个、2 加一个、3 加一批。 */
    var adding by remember { mutableStateOf(0) }
    /** 并成一组那张纸。 */
    var grouping by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupName by remember { mutableStateOf("") }
    /** 题材词表那张纸。 */
    var wordsOpen by remember { mutableStateOf(false) }
    /** 完整名单那张纸。 */
    var allOpen by remember { mutableStateOf(false) }
    /** 嗓子那张纸是从名单点进来的。关掉它要还回名单，不是退回派角。 */
    var fromAll by remember { mutableStateOf(false) }
    /** 名单里的搜索。 */
    var find by remember { mutableStateOf("") }
    /** "import" / "export" / "paste"，空 = 都没开。 */
    var panel by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    /** 加人那张纸里手写的那一格。 */
    var hand by remember { mutableStateOf("") }
    /** 准备写出去的那一坨。 */
    var outBody by remember { mutableStateOf("") }
    /** 这份表动过没有。退出时用它决定要不要让外层重读。 */
    var dirty by remember { mutableStateOf(false) }

    /* AI 那一趟的状态不在这一页里，在 [AiJob]。 */
    val aiStamp = AiJob.stamp
    LaunchedEffect(aiStamp) {
        if (aiStamp > 0) {
            cast = loadCast(app, book)
            dirty = true
        }
    }

    fun save(next: CastBook, note: String? = null) {
        cast = next
        val json = RuleCodec.toJson(next)
        if (book == null) app.field.edit { it.copy(castJson = json) }
        else app.shelf.setCast(book.id, json)
        /* 外层那份快照（阅读页拿着的 book）先不刷新。 */
        dirty = true
        if (note != null) msg = note
    }

    fun say(t: String) { msg = t }

    /** 关掉嗓子那张纸。从完整名单点进来的，关掉之后还回名单 —— 在那儿就是挨个往下调，每调一个人都退回派角、再重新点「全看」进来找位置。 */
    fun closeVoice() {
        voiceEdit = null
        if (fromAll) {
            fromAll = false
            allOpen = true
        }
    }

    val state = book?.let { Counts.of(it.id) } ?: Counts.IDLE

    /* 书一进来就数，不等用户按。数完的那一刻把表重读一遍 —— 只在「正在数 → 数完」这一下读。 */
    var seen by remember(book?.id) { mutableStateOf(Counts.of(book?.id.orEmpty())) }
    LaunchedEffect(book?.id) {
        if (book != null) Counts.ensure(app, book)
    }
    val ver = Counts.version.intValue
    LaunchedEffect(ver, book?.id) {
        val b = book ?: return@LaunchedEffect
        val now = Counts.of(b.id)
        if (now == Counts.DONE && seen != Counts.DONE) {
            cast = loadCast(app, app.shelf.find(b.id) ?: b)
            /* 名单整份换过了，手上翻开的旧名单/旧组都不是这一份，收掉。 */
            fromAll = false
            voiceEdit = null
            // 外层那本快照也过时了，让它重读一遍，念出来的才是新名单。
            onChanged()
        }
        seen = now
    }

    /** 用户自己按的那一下。 */
    fun count() {
        val b = book ?: return
        Counts.redo(app, b)
    }

    // ── 读写文件 ──────────────────────────────────────────

    val writeFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val body = outBody
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                    true
                }.getOrDefault(false)
            }
            say(if (ok) "写出去了" else "写不进去")
        }
    }

    /** 读进来一坨。三种都认：这里导出的 .cast（整份 JSON）、一行一组的 （人 人）嗓子、和更早那种「谁｜嗓子｜正则」。 */
    fun take(raw: String) {
        if (raw.isBlank()) { say("这儿没有东西"); return }
        val head = raw.trimStart()
        val loaded = runCatching {
            if (head.startsWith("{")) RuleCodec.fromJson(raw)
            else {
                val byGroup = RuleCodec.fromGroups(raw)
                if (byGroup.roles.isEmpty()) {
                    RuleCodec.toBook(RuleCodec.fromText(raw), cast.narrator, cast.dialog)
                } else byGroup.copy(rules = emptyList())
            }
        }.getOrNull()
        if (loaded == null || (loaded.rules.isEmpty() && loaded.roles.isEmpty())) {
            say("里头没读出东西")
            return
        }
        // 嗓子是本机攒的：表里没写的，留旧的。
        val mine = cast.roles.filter { it.voice.isNotBlank() }.associate { it.name to it.voice }
        /* 句数按名字从旧名单带过来。一行一组那种文件里没有句数，照它建的名单全是 0。 */
        val known = cast.pool.associate { it.name to it.lines }
        val filled = loaded.pool.map { p -> p.copy(lines = known[p.name] ?: 0) }
        save(
            loaded.copy(
                roles = loaded.roles.map { it.copy(voice = it.voice.ifBlank { mine[it.name].orEmpty() }) },
                pool = filled,
                narrator = loaded.narrator.ifBlank { cast.narrator },
                dialog = loaded.dialog.ifBlank { cast.dialog },
                // 题材包是认人的，导入一份派角不该把它清空。
                packs = loaded.packs.ifEmpty { cast.packs },
            ),
            "进来了 ${loaded.roles.size} 个角色 · ${loaded.rules.size} 条规则",
        )
        panel = ""
    }

    /**
     * 把「让 AI 排」这张纸摊开。
     * 这里只做入口：能不能排、名单够不够、嗓子有没有，[AiJob.begin] 一句答完。
     */
    fun runAi() {
        val why = AiJob.begin(app, book, cast)
        if (why != null) say(why)
    }

    val readFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.reader().readText() } }
                    .getOrNull()
            }
            if (text.isNullOrBlank()) { say("这个文件读不出来"); return@launch }
            take(text)
        }
    }

    fun copyOut(body: String) {
        app.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("派角", body))
        say("复制好了")
    }

    fun shareOut(body: String, name: String) {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "cast")
                if (!dir.exists()) dir.mkdirs()
                runCatching { File(dir, name).also { it.writeText(body) } }.getOrNull()
            }
            if (f == null) { copyOut(body); return@launch }
            runCatching {
                app.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
                        }, "发到"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    val title = (book?.title ?: "派角").take(16).ifBlank { "cast" }
    val all = rowsOf(cast)
    val shown = all.take(20)
    /* 人数按人头算：组名本身是个角色时，他也是这一组里的人。 */
    val heads = all.sumOf { it.heads }
    val grpN = all.count { it.alt.isNotEmpty() }

    /* 手机返回键：一层一层退，退到根才离开派角。 */
    BackHandler(enabled = !AiJob.open) {
        when {
            panel.isNotEmpty() -> panel = ""
            ruleEdit != null -> ruleEdit = null
            allOpen -> allOpen = false
            wordsOpen -> wordsOpen = false
            ledger -> ledger = false
            voiceEdit != null -> closeVoice()
            grouping -> grouping = false
            adding > 0 -> { adding = 0; hand = "" }
            personEdit != null -> personEdit = null
            else -> {
                if (dirty) onChanged()
                onBack()
            }
        }
    }

    /* 空嗓子的行实际用对白那副，能不能吃调门看的就是对白那副。 */
    val dialogCan = groups.flatMap { it.second }.firstOrNull { it.name == cast.dialog }?.let { tasty(it) } ?: false
    fun canStyleOf(name: String): Boolean {
        val v = voiceOf(cast, name).ifBlank { cast.dialog }
        val one = groups.flatMap { it.second }.firstOrNull { it.name == v }
        return if (one == null) dialogCan else tasty(one)
    }

    Box(
        Modifier.fillMaxSize().background(if (sheet) Color.Transparent else s.ground)
    ) {
        Column(
            Modifier.fillMaxSize()
                .then(if (sheet) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", {
                    if (dirty) onChanged()
                    onBack()
                }, size = 34.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T("派角", size = 17, weight = FontWeight.Medium)
                    T(book?.title ?: "所有书共用这一张", size = 10, color = s.inkFaint, maxLines = 1)
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp)
            ) {
                // ── 一、旁白和对白 ────────────────────────
                item { Head("旁白和对白") }
                item { PersonRow("旁白", cast.narrator) { personEdit = 0 } }
                item { PersonRow("对白", cast.dialog) { personEdit = 1 } }

                // ── 二、角色 ──────────────────────────────
                item {
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(
                            if (heads == 0) "角色" else
                                "角色现有 $heads 人" + if (grpN > 0) "，一共有 $grpN 组。" else "。",
                            size = 12, color = s.inkDim,
                            modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        val tag = when (state) {
                            Counts.WORK -> String.format(
                                "第 %d%% · ",
                                Counts.pct.intValue
                            ) + (Counts.note.value.ifBlank { "在数…" })
                            Counts.FAIL -> "重数 ›"
                            Counts.DONE -> "重数"
                            else -> "还没数"
                        }
                        T(
                            tag, size = 10,
                            color = if (state == Counts.WORK) s.inkFaint else s.flame,
                            modifier = Modifier.tap { if (book != null && state != Counts.WORK) count() },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        shown.forEach { r ->
                            RoleRow(
                                name = r.name,
                                alt = r.alt,
                                lines = r.lines,
                                voice = r.voice,
                                tag = r.tag,
                                who = r.who,
                                onClick = { voiceEdit = r.name },
                            )
                        }
                    }
                }
                if (all.size > shown.size) {
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .tap { find = ""; allOpen = true }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            T("还有 ${all.size - shown.size} 个", size = 10, color = s.inkFaint, modifier = Modifier.weight(1f))
                            T("全看 ›", size = 10, color = s.flame)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("添加角色", { adding = 1 }, BtnKind.PRIMARY, Modifier.weight(1f))
                        Btn(
                            "几个人并成一组",
                            { sel = emptySet(); groupName = ""; grouping = true },
                            BtnKind.GHOST,
                            Modifier.weight(1f),
                            enabled = all.isNotEmpty(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    /* 这是这一页的主动作，所以单独占一整行。 */
                    val working = AiJob.step == AiJob.Step.RUN && AiJob.err == null && !AiJob.paused
                    /* 按钮叫「让 AI 排」，「怎么分配」是那张纸里面的标题。 */
                    Btn(
                        when {
                            working -> "正在排 · ${AiJob.done}/${AiJob.total} 批"
                            AiJob.fresh -> "看排好的"
                            else -> "让 AI 排"
                        },
                        { if (AiJob.alive) AiJob.resume() else runAi() },
                        BtnKind.GHOST,
                        Modifier.fillMaxWidth(),
                        enabled = all.isNotEmpty() || AiJob.alive,
                    )
                }

                /*
                 * 书里谁在说话，一章一屏。这是「按顺序看」的那一面 —— 角色列表是「按人看」的那一面。
                 * 两个看法吃同一份数据（句库 + 改派），不会一边改了另一边不动。
                 */
                item {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .tap { if (book != null) ledger = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T("这本书里谁在说话", size = 12, color = if (book == null) s.inkFaint else s.inkDim,
                            modifier = Modifier.weight(1f))
                        T(if (book == null) "要进书里才看得到" else "逐章看 ›", size = 10,
                            color = if (book == null) s.inkFaint else s.flame)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T("读的时候标出谁在说话", size = 12, color = s.inkDim)
                            Spacer(Modifier.height(2.dp))
                            T(
                                "每段对白前面挂一个名字签，点它就能改这一句归谁。",
                                size = 10, color = s.inkFaint,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Pill(cast.mark, { on -> save(cast.copy(mark = on), null) })
                    }
                }

                // ── 三、规则 ──────────────────────────────
                item { Head("规则") }
                item { MiniLabel("题材") }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Words.packs.chunked(3).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { p ->
                                    val hit = p.id in cast.packs
                                    Box(Modifier.weight(1f)) {
                                        PickChip(
                                            p.cn, hit,
                                            {
                                                val np = if (hit) cast.packs - p.id else cast.packs + p.id
                                                save(cast.copy(packs = np), null)
                                            },
                                            Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        val picked = cast.packs.mapNotNull { Words.packOf(it) }
                        val baseN = Words.verbs.size + Words.titles.size + Words.advs.size
                        val packN = picked.sumOf { it.verbs.size + it.titles.size + it.advs.size }
                        Row(
                            Modifier.fillMaxWidth()
                                .tap { wordsOpen = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            T(
                                "基础 $baseN 条" + if (packN > 0) " · 题材 $packN 条" else "",
                                size = 10, color = s.inkFaint, modifier = Modifier.weight(1f)
                            )
                            T("看词表 ›", size = 10, color = s.flame)
                        }
                    }
                }

                item { Spacer(Modifier.height(14.dp)); MiniLabel("自己加") }
                items(cast.rules.size) { i ->
                    val r = cast.rules[i]
                    RuleRow(
                        rule = r,
                        bad = r.bad(),
                        same = i > 0 && cast.rules[i - 1].pattern == r.pattern,
                        voice = voiceOf(cast, r.character),
                        onClick = { ruleEdit = i },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Btn("加一条", { ruleEdit = -1 }, BtnKind.GHOST, Modifier.fillMaxWidth())
                }

                // ── 四、导入导出 ──────────────────────────
                item {
                    Head("导入和导出")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("导入", { panel = "import" }, BtnKind.PRIMARY, Modifier.weight(1f))
                        Btn("导出", { panel = "export" }, BtnKind.GHOST, Modifier.weight(1f))
                    }
                }
            }
        }

        // ── 纸 ────────────────────────────────────────────

        if (AiJob.open) AiSheet()

        personEdit?.let { i ->
            val v = if (i == 0) cast.narrator else cast.dialog
            val st = if (i == 0) cast.narratorStyle else cast.dialogStyle
            val can = if (v.isBlank()) dialogCan
            else groups.flatMap { it.second }.firstOrNull { it.name == v }?.let { tasty(it) } ?: false
            PersonSheet(
                what = if (i == 0) "旁白" else "对白",
                voice = v,
                style = st,
                groups = groups,
                canStyle = can,
                onVoice = {
                    val nxt = if (i == 0) cast.copy(narrator = it) else cast.copy(dialog = it)
                    save(nxt, null)
                },
                onStyle = {
                    val nxt = if (i == 0) cast.copy(narratorStyle = it) else cast.copy(dialogStyle = it)
                    save(nxt, null)
                },
                onClose = { personEdit = null },
            )
        }

        if (adding > 0) {
            AddSheet(
                step = adding,
                hand = hand,
                onHand = { hand = it },
                onStep = { adding = it },
                onClose = { adding = 0; hand = "" },
                onOne = { raw ->
                    val ways = splitWays(raw)
                    if (ways.isEmpty()) {
                        say("先写个名字")
                    } else {
                        val name = ways.first()
                        val alt = ways.drop(1)
                        val pool = if (cast.pool.any { it.name == name }) {
                            cast.pool.map { if (it.name == name) it.copy(on = true, alt = alt) else it }
                        } else cast.pool + CastName(name, 0, on = true, alt = alt)
                        val roles = if (cast.roles.any { it.name == name }) {
                            cast.roles.map { if (it.name == name) it.copy(alt = alt) else it }
                        } else cast.roles + CastRole(name, alt = alt)
                        save(cast.copy(pool = pool, roles = roles), "加上了「$name」")
                        hand = ""
                        adding = 0
                        /* 手写进来的人，句数是 0 —— 那不叫「他没说过话」，那叫「还没数过」。 */
                        val bid = book?.id.orEmpty()
                        if (bid.isNotBlank()) app.countsRead(bid, name) { n ->
                            if (n > 0) {
                                cast = loadCast(app, app.shelf.find(bid))
                                dirty = true
                            }
                        }
                    }
                },
                onMany = { raw ->
                    val names = splitMany(raw)
                    if (names.isEmpty()) {
                        say("先写几个名字")
                    } else {
                        var pool = cast.pool
                        var roles = cast.roles
                        names.forEach { n ->
                            if (pool.none { it.name == n }) pool = pool + CastName(n, 0, on = true)
                            else pool = pool.map { if (it.name == n) it.copy(on = true) else it }
                            if (roles.none { it.name == n }) roles = roles + CastRole(n)
                        }
                        save(cast.copy(pool = pool, roles = roles), "加上了 ${names.size} 个")
                        hand = ""
                        adding = 0
                    }
                },
            )
        }

        // 并成一组
        if (grouping) {
            GroupSheet(
                rows = all,
                sel = sel,
                groupName = groupName,
                hint = firstOf(all, sel),
                onName = { groupName = it },
                onToggle = { n -> sel = if (n in sel) sel - n else sel + n },
                onClose = { grouping = false },
                onOk = {
                    /* 挑中的要是一个已经并好的组，带过来的是它里面的人，不是组名 ——把组名当成一个人并进新组，原来那组的人就没地方去了。 */
                    val members = all.filter { it.name in sel }
                        .flatMap { r -> if (r.alt.isEmpty()) listOf(r.name) else r.alt }
                        .distinct()
                    if (members.isEmpty()) {
                        say("先挑两个人")
                    } else if (members.size == 1) {
                        say("一个人成不了组")
                    } else {
                        /* 组名照旧取第一个人 —— 用户认的是那个名字。 */
                        val name = groupName.trim().ifBlank { members.first() }
                        val first = cast.roles.firstOrNull { it.name in members }
                        val keep = cast.roles
                            .filter { it.name !in members && it.name != name }
                            .map { r -> r.copy(alt = r.alt.filter { it !in members }) }
                        val group = CastRole(
                            name = name,
                            voice = first?.voice.orEmpty(),
                            style = first?.style.orEmpty(),
                            // 组名就是 members 里第一个的时候，别把他也写进成员表 —— 会算两遍。
                            alt = members.filter { it != name },
                        )
                        val pool = cast.pool.map { if (it.name in members) it.copy(on = false) else it }
                        save(cast.copy(roles = keep + group, pool = pool), "并成了「$name」")
                        groupName = ""
                        grouping = false
                    }
                },
            )
        }

        /*
         * 「改哪个人」这张纸换成「看他的话」那一张。
         * 原来点一行只能换嗓子；换完嗓子想问「他到底说了些什么」就没地方去了。
         */
        voiceEdit?.let { name ->
            val cur = voiceOf(cast, name)
            val members = altOf(cast, name).distinct()
            val isGroup = members.isNotEmpty()
            if (!isGroup) PersonSheet2(
                name = name,
                voice = cur,
                style = cast.styleOf(name),
                members = members,
                groups = groups,
                canStyle = canStyleOf(name),
                app = app,
                bookId = book?.id.orEmpty(),
                pins = cast.pins,
                roles = all.map { it.name },
                chapters = chapters,
                onJump = onJump,
                onVoice = { v -> save(cast.copy(roles = cast.roles.map { if (it.name == name) it.copy(voice = v) else it }), null) },
                onStyle = { st -> save(cast.copy(roles = cast.roles.map { if (it.name == name) it.copy(style = st) else it }), null) },
                onPin = { qs, who ->
                    val add = qs.map { q ->
                        com.pagefold.reader.book.Pin(q.ch, q.at, who, q.text)
                    }
                    val keep = cast.pins.filterNot { p ->
                        qs.any { it.ch == p.ch && it.at == p.at }
                    }
                    save(cast.copy(pins = keep + add), "转过去 ${qs.size} 句")
                },
                onDelete = {
                    /* 删一个人：角色表、名单里那一格、指着他改的句子，一起收掉。 */
                    val keep = cast.roles.filter { it.name != name }
                    val pool = cast.pool.filter { it.name != name }
                        .map { it.copy(alt = it.alt.filter { a -> a != name }) }
                    val pins = cast.pins.filter { it.who != name }
                    save(cast.copy(roles = keep, pool = pool, pins = pins), "删掉了「$name」")
                    voiceEdit = null
                },                onClose = { voiceEdit = null },
            ) else VoiceSheet(
                name = name,
                voice = cur,
                style = cast.styleOf(name),
                members = members,
                groups = groups,
                canStyle = canStyleOf(name),
                /* 改组名。组名只是个标签，改它不该动组里的人 —— 但新名字要是正好撞上组里某个人的名字，那个人就从「成员」变成「标签」。 */
                onRename = { nn ->
                    val v = nn.trim().take(12)
                    if (v.isNotEmpty() && v != name) {
                        /* 组名只是个标签，改名不该动成员表 —— 人还是那些人。 */
                        val other = cast.roles.firstOrNull { it.name == v }
                        val roles = if (other != null) {
                            cast.roles.filter { it.name != name }.map {
                                if (it.name == v) it.copy(alt = (it.alt + members).distinct().filter { m -> m != v }) else it
                            }
                        } else {
                            cast.roles.map {
                                if (it.name == name) it.copy(name = v, alt = members.filter { m -> m != v }) else it
                            }
                        }
                        val pool = cast.pool.map { if (it.name == v) it.copy(on = false) else it }
                        save(cast.copy(roles = roles, pool = pool), "改成了「$v」")
                        voiceEdit = v
                    }
                },
                pool = all.filter { r -> r.alt.isEmpty() && r.name != name && r.name !in members },
                onAdd = { m ->
                    val alt = (members + m).distinct()
                    val roles = cast.roles.map { if (it.name == name) it.copy(alt = alt) else it }
                    val pool = cast.pool.map { if (it.name == m) it.copy(on = false) else it }
                    save(cast.copy(roles = roles, pool = pool), "把「$m」并了进来")
                },
                onVoice = { v ->
                    save(cast.with(CastRole(name, v, cast.styleOf(name), members)), null)
                },
                onStyle = { st ->
                    save(cast.with(CastRole(name, voiceOf(cast, name), st, members)), null)
                },
                onRemove = { m ->
                    /*
                     * 从组里拿掉一个人。人还留着：回角色列表单独占一行，嗓子用对白那副。
                     * 删组才连人一起拿掉 —— 那是两件不同的事，不能混成一个按钮。
                     */
                    val rest = members.filter { it != m }
                    val pool = cast.pool.map { if (it.name == m) it.copy(on = true) else it }
                    val roles = if (rest.isEmpty()) {
                        /* 组里就剩他一个了。组名本身是个角色（「嬴政」那种）→ 组散成他一个人，他留在原位；组名只是个标签（「众臣」那种）→ 标签撤掉。 */
                        if (isPerson(cast, name)) {
                            cast.roles.map { if (it.name == name) it.copy(alt = emptyList()) else it }
                        } else {
                            cast.roles.filter { it.name != name } + CastRole(m)
                        }
                    } else {
                        cast.roles.map { if (it.name == name) it.copy(alt = rest) else it } + CastRole(m)
                    }
                    save(cast.copy(roles = roles, pool = pool), "拿出了「$m」")
                    if (rest.isEmpty()) closeVoice()
                },
                onDissolve = {
                    /* 全散开，一个不留。组名本身是个角色的话，他也要跟着散出来 —— 只散成员的话，组名那个人随着组一起消失，看上去就是「怎么按都散不掉」。 */
                    val back = (if (isPerson(cast, name) && name !in members) listOf(name) else emptyList()) +
                        members.distinct()
                    val keep = cast.roles.filter { it.name != name } + back.map { CastRole(it) }
                    val pool = cast.pool.map { if (it.name in back) it.copy(on = true) else it }
                    save(cast.copy(roles = keep, pool = pool), "散开了 ${back.size} 个")
                    closeVoice()
                },
                onDelete = {
                    val pool = cast.pool.filter { it.name != name && it.name !in members }
                    save(cast.copy(roles = cast.roles.filter { it.name != name }, pool = pool), "删掉了")
                    closeVoice()
                },
                onClose = { closeVoice() },
                self = members.isNotEmpty() && isPerson(cast, name),
            )
        }

        if (ledger && book != null) {
            LedgerSheet(
                app = app,
                bookId = book.id,
                chapters = chapters,
                /* 一开就停在用户正在读的那一章。 */
                startCh = app.shelf.find(book.id)?.chapterIndex ?: 0,
                body = { i -> app.shelf.readChapter(book.id, i) },
                pins = cast.pins,
                roles = rowsOf(cast).map { it.name },
                onPin = { ch, at, who ->
                    val text = runCatching {
                        app.shelf.read(book.id).orEmpty()
                    }.getOrNull().orEmpty()
                    val chs = chapters
                    val c = chs.getOrNull(ch)
                    val body = if (c != null && text.isNotBlank()) {
                        text.substring(c.start.coerceIn(0, text.length), c.end.coerceIn(0, text.length))
                    } else ""
                    val e = if (at >= 0 && at < body.length) {
                        var k = at
                        while (k < body.length && body[k] != QUOT_END_1 && body[k] != QUOT_END_2 && body[k] != NL) k++
                        body.substring(at.coerceIn(0, body.length), k).take(120)
                    } else ""
                    val keep = cast.pins.filterNot { it.ch == ch && it.at == at }
                    save(cast.copy(pins = keep + com.pagefold.reader.book.Pin(ch, at, who, e)), null)
                },
                onClose = { ledger = false },
            )
        }

        if (allOpen) {
            AllSheet(
                rows = all,
                find = find,
                onFind = { find = it },
                onClick = { n -> allOpen = false; fromAll = true; voiceEdit = n },
                onClose = { allOpen = false },
            )
        }

        ruleEdit?.let { i ->
            val isNew = i < 0
            val r = if (isNew) null else cast.rules.getOrNull(i)
            RuleSheet(
                rule = r,
                ways = { name -> (listOf(name) + altOf(cast, name)).filter { it.isNotBlank() } },
                // 派给谁：整张表都列，不要求先配过嗓子。数是数出来的人，配嗓子是另一件事。
                names = rowsOf(cast).map { it.name },
                onClose = { ruleEdit = null },
                onDelete = if (isNew) null else ({
                    save(cast.copy(rules = cast.rules.filterIndexed { j, _ -> j != i }), "删掉一条")
                    ruleEdit = null
                }),
                onOk = { who, pat ->
                    val list = cast.rules.toMutableList()
                    val fresh = CastRule(who, pat.trim(), afterDialog = false, enabled = true)
                    if (isNew) list.add(fresh) else list[i] = fresh
                    var roles = cast.roles
                    if (roles.none { it.name == who }) roles = roles + CastRole(who)
                    save(cast.copy(rules = list, roles = roles), if (isNew) "加了一条" else "改好了")
                    ruleEdit = null
                },
            )
        }

        if (wordsOpen) {
            WordsSheet(
                packs = cast.packs,
                off = cast.off,
                onToggle = { w ->
                    val no = cast.off
                    save(cast.copy(off = if (w in no) no - w else no + w), null)
                },
                onClose = { wordsOpen = false },
            )
        }

        if (panel == "import") {
            Sheet("导入", { panel = "" }) {
                Text(
                    "一行一组：括号里写这一组的人，括号后面写这一组用的嗓子。\n" +
                        "　　旁白 白桦\n" +
                        "　　对白 苏打\n" +
                        "　　（赵惊鸿）苏打\n" +
                        "　　（嬴政 扶苏 胡亥）秦·帝王\n" +
                        "　　也可以直接粘这里导出的 .cast。规则不从文件里读。",
                    fontSize = 10.sp, lineHeight = 17.sp, color = s.inkFaint,
                )
                Spacer(Modifier.height(12.dp))
                Btn("挑一个文件", { readFile.launch(arrayOf("*/*")) }, BtnKind.PRIMARY, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Btn("从剪贴板读", {
                    val cm = app.getSystemService(ClipboardManager::class.java)
                    val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
                    take(text)
                }, BtnKind.GHOST, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Btn("粘一段文本", { paste = ""; panel = "paste" }, BtnKind.GHOST, Modifier.fillMaxWidth())
            }
        }

        if (panel == "export") {
            Sheet("导出", { panel = "" }) {
                T("整套带走的只有 .cast：旁白、角色、叫法、题材、规则、名单都在里面。", size = 10, color = s.inkFaint)
                Spacer(Modifier.height(12.dp))
                Btn("存成 .cast", {
                    outBody = RuleCodec.toJson(cast)
                    writeFile.launch("$title.cast")
                }, BtnKind.PRIMARY, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Btn("存成文本 .txt", {
                    outBody = RuleCodec.toGroups(cast, book?.title.orEmpty())
                    writeFile.launch("$title.txt")
                }, BtnKind.GHOST, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Btn("复制到剪贴板", { copyOut(RuleCodec.toJson(cast)) }, BtnKind.GHOST, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Btn("发到别处", { shareOut(RuleCodec.toJson(cast), "$title.cast") }, BtnKind.GHOST, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    "文本是一行一组，手改方便，也能直接导回来：\n" +
                        "　　（嬴政 扶苏 胡亥）秦·帝王\n" +
                        "规则不进文本 —— 规则是这本书里的兜底，换个地方就用不上了。",
                    fontSize = 10.sp, lineHeight = 17.sp, color = s.inkFaint,
                )
            }
        }

        if (panel == "paste") {
            Sheet("粘一段", { panel = "import" }) {
                T("一行一组：（人 人）嗓子。以 # 开头的行不算。", size = 10, color = s.inkFaint)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(240.dp)
                        .clip(RoundedCornerShape(9.dp)).background(s.panelSink)
                        .padding(12.dp)
                ) {
                    if (paste.isEmpty()) {
                        Text(
                            "旁白 茉莉\n对白 苏打\n（邮差小陈 小陈）苏打\n（老太太 冯婶 小满）冰糖",
                            fontSize = 11.sp, lineHeight = 18.sp, color = s.inkFaint,
                        )
                    }
                    BasicTextField(
                        value = paste,
                        onValueChange = { paste = it },
                        textStyle = TextStyle(fontSize = 11.sp, color = s.ink, fontFamily = s.numFont),
                        cursorBrush = SolidColor(s.flame),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Btn("读这一段", { take(paste) }, BtnKind.PRIMARY, Modifier.fillMaxWidth())
            }
        }

        msg?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(2600); msg = null }
            Box(Modifier.padding(bottom = 34.dp).align(Alignment.BottomCenter)) {
                Box(
                    Modifier.background(s.ink.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) { T(it, size = 11, color = s.ground) }
            }
        }
    }
}

// ── 取 ─────────────────────────────────────────────

/** 角色列表上的一行。名字可能是专名，也可能是一组的组名（此时 alt 是组里的人）。 */
internal data class CastRow(
    val name: String,
    val alt: List<String>,
    val lines: Int,
    val voice: String,
    /** 类别名（「秦·谋士」这种）。只用来标注。 */
    val tag: String = "",
    /** 这一行一共几个人。单人就是 1；组是「组员 + 组名（如果组名本身是个角色）」。 */
    val heads: Int = 1,
    /** 这一组都是谁，用来摆副标题。单人为空。 */
    val who: List<String> = emptyList(),
)

/**
 * 列表从哪儿来。不能只看名单（pool）：别处生成的配置只有角色表、没有名单，照名单画的话导进来一个都看不见。
 * 也不能只看角色表：手动勾上、还没配嗓子的人只在名单里。
 */
internal fun rowsOf(cast: CastBook): List<CastRow> {
    /* 谁是「别人的组员」。只认别的组 —— 组名和组里的一个人同名是常事（一组就是拿第一个人的名字起的），那种情况下组自己不能被藏起来。 */
    val owned = HashMap<String, String>()
    cast.roles.forEach { r -> r.alt.forEach { if (it != r.name) owned[it] = r.name } }
    /* 单句改派也要算进句数。名单里那个数（pool.lines）是「数人」那一遍扫出来的 —— 它数的是引擎在正文里认出他多少次。 */
    val pinN = HashMap<String, Int>(16)
    cast.pins.forEach { p -> if (p.who.isNotBlank()) pinN[p.who] = (pinN[p.who] ?: 0) + 1 }
    val lines = cast.pool.associate { it.name to it.lines }
    val total = { n: String -> (lines[n] ?: 0) + (pinN[n] ?: 0) }
    /* 组名本人算不算一个人，只看名单里有没有这个名字。 */
    val inPool = HashSet<String>(cast.pool.size * 2)
    cast.pool.forEach { if (it.lines > 0 || it.on) inPool.add(it.name) }
    val out = ArrayList<CastRow>(cast.roles.size + 8)
    cast.roles.forEach { r ->
        val by = owned[r.name]
        if (by != null && by != r.name) return@forEach
        val sum = (listOf(r.name) + r.alt).sumOf { total(it) }
        val who = if (r.alt.isEmpty()) emptyList()
        else (if (r.name in inPool) listOf(r.name) else emptyList()) + r.alt
        out.add(CastRow(r.name, r.alt, sum, r.voice, r.tag, if (who.isEmpty()) 1 else who.size, who))
    }
    cast.pool.forEach { p ->
        if (!p.on || p.name in owned) return@forEach
        if (cast.roles.any { it.name == p.name }) return@forEach
        /* 名单里的一律当单人。alt 只属于角色表 —— 从名单里再抄一份，组散掉之后它还挂在那儿，界面上就永远是个散不开的组。 */
        out.add(CastRow(p.name, emptyList(), total(p.name), ""))
    }
    /* 只被单句改派提到、名单和角色表里都没有的名字，也要占一行。 */
    pinN.forEach { (n, _) ->
        if (out.any { it.name == n || n in it.alt }) return@forEach
        out.add(CastRow(n, emptyList(), total(n), ""))
    }
    /* 组排在最前。一组是一群人共用一副嗓子，它比任何单个角色都更「该先看见」——而且并完之后要找它配嗓子，还得翻到中间去找，等于把它藏起来了。 */
    return out.sortedWith(
        compareByDescending<CastRow> { it.alt.isNotEmpty() }.thenByDescending { it.lines }
    )
}

/**
 * 这个名字是不是「一个人」，还是一条组的标签。
 * 这条判据决定三件事：人数怎么算、解散时要不要把他散出来、从组里拿出最后一个人时组怎么收场。
 */
internal fun isPerson(cast: CastBook, name: String): Boolean {
    val p = cast.pool.firstOrNull { it.name == name } ?: return false
    return p.lines > 0 || p.on
}

private fun voiceOf(cast: CastBook, name: String): String =
    cast.roles.firstOrNull { it.name == name }?.voice.orEmpty()

private fun altOf(cast: CastBook, name: String): List<String> =
    cast.roles.firstOrNull { it.name == name }?.alt.orEmpty()

/** 把旧存档里的组理顺。老版本并组时把组名当成组员写进了 alt。 */
private fun heal(cast: CastBook): CastBook {
    val hit = cast.roles.any { r -> r.alt.any { it == r.name } }
    if (!hit) return cast
    return cast.copy(roles = cast.roles.map { r -> r.copy(alt = r.alt.filter { it != r.name }) })
}

/** 不打名字时用哪一个：勾上的第一个人的名字。 */
private fun firstOf(rows: List<CastRow>, sel: Set<String>): String =
    rows.firstOrNull { it.name in sel }?.name.orEmpty()

/** 这副嗓子能不能接调门。自建、克隆、系统那几副接不了，接不了就不摆这一块。 */
private fun tasty(v: Voice): Boolean = v.engine == "mimo" && v.kind == "preset"

/** 「邮差小陈 / 小陈」这种写法拆成几个叫法。 */
private fun splitWays(raw: String): List<String> =
    raw.split(' ', '\u3000', '、', '/', '／', ',', '，', '|', '｜')
        .map { it.trim() }.filter { it.isNotBlank() }.distinct()

/** 一次加一批：换行、顿号、逗号、空格、斜杠都算分隔，括号当装饰去掉。 */
private fun splitMany(raw: String): List<String> {
    val body = raw.map { c ->
        if (c in "（）()【】[]「」『』<>《》") ' ' else c
    }.joinToString("")
    return body.split('\n', '\r', ' ', '\u3000', '、', ',', '，', '/', '／', '|', '｜', ';', '；')
        .map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

@Composable
internal fun Head(title: String) {
    val s = LocalSkin.current
    Spacer(Modifier.height(22.dp))
    T(title, size = 12, color = s.inkDim)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun MiniLabel(text: String) {
    val s = LocalSkin.current
    T(text, size = 11, color = s.inkDim)
    Spacer(Modifier.height(7.dp))
}

/** 一个已勾上的角色：名字、别的叫法、台词数、嗓子。 */
@Composable
private fun RoleRow(
    name: String,
    alt: List<String>,
    lines: Int,
    voice: String,
    tag: String,
    /** 这一组都是谁（组名本身是角色时也在里面）。单人为空。 */
    who: List<String>,
    onClick: () -> Unit,
) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .tap { onClick() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(name, size = 14, maxLines = 1)
                if (lines > 0) {
                    Spacer(Modifier.width(7.dp))
                    T("$lines 句", size = 9, color = s.inkFaint)
                }
                if (tag.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    T(tag, size = 9, color = s.inkFaint.copy(alpha = 0.8f), maxLines = 1)
                }
            }
            if (alt.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                T(
                    "这一组 ${(if (who.isEmpty()) alt else who).size} 个人 · " +
                        (if (who.isEmpty()) alt else who).joinToString("、"),
                    size = 9, color = s.inkFaint, maxLines = 1,
                )
            }
        }
        T(voice.ifBlank { "对白那副" }, size = 11, color = if (voice.isBlank()) s.inkFaint else s.flame)
        Spacer(Modifier.width(5.dp))
        T("›", size = 13, color = s.inkFaint)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
}

/** 一条规则：派给谁、认什么、对上用哪副。 */
@Composable
private fun RuleRow(
    rule: CastRule,
    bad: Boolean,
    same: Boolean,
    voice: String,
    onClick: () -> Unit,
) {
    val s = LocalSkin.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .tap { onClick() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(2.dp).height(30.dp)
                    .background(if (bad) s.bad else s.flame.copy(alpha = 0.55f))
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(rule.character.ifBlank { "没派给谁" }, size = 14, color = if (bad) s.bad else s.ink)
                    if (bad) {
                        Spacer(Modifier.width(6.dp))
                        T("写坏了", size = 9, color = s.bad)
                    }
                }
                Spacer(Modifier.height(3.dp))
                T(
                    "对上就用「" + voice.ifBlank { "对白那副" } + "」",
                    size = 10, color = s.inkFaint, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (bad) rule.pattern.ifBlank { "空" } else summarize(rule.pattern),
                    fontSize = 9.sp, lineHeight = 14.sp,
                    color = if (bad) s.bad else s.inkFaint.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (same) {
                    Spacer(Modifier.height(2.dp))
                    T("跟上面那条一样，永远轮不到它", size = 9, color = s.warn)
                }
            }
            Spacer(Modifier.width(8.dp))
            T("改", size = 11, color = s.inkFaint)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
    }
}

/** 一条正则翻成人话。太长的不摊开，只说它在干什么。 */
private fun summarize(pat: String): String {
    val p = pat.trim()
    if (p.isEmpty()) return "空"
    val names = Regex("[\\u4e00-\\u9fa5]{2,3}").findAll(p).map { it.value }.distinct().count()
    return when {
        names >= 6 -> "按名字表认人，表里 $names 个名字"
        p.length <= 26 -> p
        else -> p.take(24) + "…"
    }
}

// ── 纸 ────────────────────────────────────────────

@Composable
internal fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    /* 全项目就这一个「纸」的入口，底下是 [Paper]。 */
    Paper(title = title, onClose = onClose, maxFraction = 0.86f) { content() }
}

/** 旁白 / 对白的一行：名字 + 现在用的嗓子。 */
@Composable
private fun PersonRow(label: String, voice: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .tap { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(label, size = 14, modifier = Modifier.weight(1f))
        T(voice.ifBlank { "没挑" }, size = 12, color = if (voice.isBlank()) s.inkFaint else s.flame)
        Spacer(Modifier.width(6.dp))
        T("›", size = 14, color = s.inkFaint)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
}

/** 一个「勾」。勾上是什么、没勾是什么，都写在字里。 */
@Composable
private fun Tick(on: Boolean, yes: String, no: String) {
    val s = LocalSkin.current
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (on) s.flameGhost else Color.Transparent)
            .border(
                1.dp,
                if (on) s.flame.copy(alpha = 0.45f) else s.hair,
                RoundedCornerShape(7.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /* 勾格里要看得见那个勾，实心方块读起来像色块。 */
        Box(
            Modifier.width(13.dp).height(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (on) s.flame else Color.Transparent)
                .border(
                    1.dp,
                    if (on) Color.Transparent else s.inkFaint.copy(alpha = 0.7f),
                    RoundedCornerShape(3.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Text("✓", fontSize = 9.sp, color = s.onFlame, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(5.dp))
        T(if (on) yes else no, size = 10, color = if (on) s.flame else s.inkFaint, maxLines = 1)
    }
}

/** 旁白 / 对白那张纸。点一下就存，纸不关 —— 挑嗓子本来就是「挨个听一遍再定」，每挑一次退出去再点回来，等于把这件事做成了十次往返。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonSheet(
    what: String,
    voice: String,
    style: String,
    groups: List<Pair<String, List<Voice>>>,
    canStyle: Boolean,
    onVoice: (String) -> Unit,
    onStyle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    var v by remember { mutableStateOf(voice) }
    var st by remember { mutableStateOf(style) }

    Sheet("改「$what」", onClose) {
        Lab("嗓音")
        if (groups.isEmpty()) {
            T("一副都没开。去定音里开一路声源，这里才有得挑。", size = 11, color = s.inkFaint)
            Spacer(Modifier.height(8.dp))
        } else {
            /* 换行排，不横滚：横滚看不出后面还有几条。 */
            groups.forEach { (g, list) ->
                if (list.isEmpty()) return@forEach
                if (g.isNotBlank() && groups.size > 1 && groups.any { it.second.isNotEmpty() }) {
                    T(g, size = 10, color = s.inkFaint, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    list.forEach { vo ->
                        PickChip(vo.name, v == vo.name, { v = vo.name; onVoice(vo.name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (canStyle) {
            Spacer(Modifier.height(4.dp))
            Lab("调门")
            var write by remember { mutableStateOf(st.isNotBlank() && st !in STYLE_WORDS) }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                STYLE_WORDS.forEach { w ->
                    PickChip(w, st == w, { st = if (st == w) "" else w; write = false; onStyle(st) })
                }
                PickChip("自己写", write, {
                    write = true
                    if (st in STYLE_WORDS) st = ""
                    onStyle(st)
                })
            }
            if (write) {
                Spacer(Modifier.height(8.dp))
                Field(st, "比如 低沉、急促、带笑", 0) { st = it.take(40); onStyle(st) }
            }
        }
        Spacer(Modifier.height(4.dp))
        T(
            if (canStyle) "点一下当场生效，纸不用关。"
            else "这副嗓子接不了调门，换一副带「预设」的才有得调。",
            size = 10, color = s.inkFaint,
        )
    }
}

/** 调门的常用写法。填什么都行，这只是省得每次手打。 */
/* 引号的右半边和换行。直接写字符在 Kotlin 里容易被当成引号本身，收成常量。 */
internal const val QUOT_END_1 = '\u300D'
internal const val QUOT_END_2 = '\u201D'
internal const val NL = '\n'

internal val STYLE_WORDS = listOf(
    "平静", "温和", "严肃", "激昂", "低沉", "讲述",
    "慵懒", "轻快", "清冷", "急促", "耳语", "带笑",
)

/** 添加角色。两级：先问加一个还是一批。 */
@Composable
private fun AddSheet(
    step: Int,
    hand: String,
    onHand: (String) -> Unit,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    onOne: (String) -> Unit,
    onMany: (String) -> Unit,
) {
    val s = LocalSkin.current
    when (step) {
        1 -> Sheet("添加角色", onClose) {
            Btn("加一个人", { onStep(2) }, BtnKind.PRIMARY, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Btn("加一批人", { onStep(3) }, BtnKind.GHOST, Modifier.fillMaxWidth())
        }
        2 -> Sheet("加一个人", onClose) {
            Field(hand, "名字", 0) { onHand(it) }
            Spacer(Modifier.height(12.dp))
            Btn("加进来", { onOne(hand) }, BtnKind.PRIMARY, Modifier.fillMaxWidth(), enabled = hand.isNotBlank())
        }
        else -> Sheet("加一批人", onClose) {
            Field(hand, "小张、小红、小粉、小蓝、小绿", 150) { onHand(it) }
            Spacer(Modifier.height(12.dp))
            Btn("都加进来", { onMany(hand) }, BtnKind.PRIMARY, Modifier.fillMaxWidth(), enabled = hand.isNotBlank())
        }
    }
}

/** 把几个人并成一组。一大群人共用一副嗓子（「众臣」「士兵们」「围观的人」）是很常见的一件事。 */
@Composable
private fun GroupSheet(
    rows: List<CastRow>,
    sel: Set<String>,
    groupName: String,
    hint: String,
    onName: (String) -> Unit,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
    onOk: () -> Unit,
) {
    val s = LocalSkin.current
    Sheet("并成一组", onClose) {
        Lab("挑人")
        Column(Modifier.fillMaxWidth()) {
            rows.forEach { r ->
                val hit = r.name in sel
                Row(
                    Modifier.fillMaxWidth()
                        .tap { onToggle(r.name) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (hit) s.flame else Color.Transparent)
                            .border(1.dp, if (hit) s.flame else s.hairSoft, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hit) Text("✓", fontSize = 10.sp, color = s.onFlame)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T(r.name, size = 13, color = if (hit) s.ink else s.inkDim, maxLines = 1)
                        if (r.alt.isNotEmpty()) {
                            T(
                                "已经是「${r.name}」这一组的人：" + r.alt.joinToString("、"),
                                size = 9, color = s.inkFaint, maxLines = 1,
                            )
                        }
                    }
                    if (r.lines > 0) T("${r.lines} 句", size = 9, color = s.inkFaint)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
            }
        }

        Spacer(Modifier.height(12.dp))
        Lab("这一组叫什么")
        Field(groupName, hint, 0) { onName(it) }
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            T("空着就叫「$hint」。", size = 9, color = s.inkFaint)
        }

        Spacer(Modifier.height(8.dp))
        T("已挑 ${sel.size} 个", size = 10, color = if (sel.isEmpty()) s.inkFaint else s.flame)

        Spacer(Modifier.height(16.dp))
        Btn("并成一组", onOk, BtnKind.PRIMARY, Modifier.fillMaxWidth(), enabled = sel.isNotEmpty())
    }
}

/** 一个角色的嗓子 / 调门。组的话，下面多一份成员清单。 */
@Composable
private fun VoiceSheet(
    name: String,
    voice: String,
    style: String,
    members: List<String>,
    groups: List<Pair<String, List<Voice>>>,
    canStyle: Boolean,
    onRename: (String) -> Unit,
    onVoice: (String) -> Unit,
    onStyle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDissolve: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    pool: List<CastRow> = emptyList(),
    onAdd: (String) -> Unit = {},
    /** 组名本身也是个角色（「嬴政」那种）。他不在 members 里，但要让人看见他在这一组。 */
    self: Boolean = false,
) {
    val s = LocalSkin.current
    var v by remember(name) { mutableStateOf(voice) }
    var st by remember(name) { mutableStateOf(style) }
    var nn by remember(name) { mutableStateOf(name) }

    Sheet(if (members.isEmpty()) "「$name」" else "「$name」这一组", onClose) {
        if (members.isNotEmpty()) {
            Lab("这一组叫什么")
            Field(nn, name, 0) { nn = it.take(12) }
            Spacer(Modifier.height(8.dp))
            if (nn.trim().isNotEmpty() && nn.trim() != name) {
                Btn("改成「${nn.trim()}」", { onRename(nn) }, BtnKind.GHOST, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PickChip("跟对白那一副", v.isBlank(), { v = ""; onVoice("") }, Modifier.padding(end = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        if (groups.isEmpty()) {
            T("一副都没开。去定音里开一路声源，这里才有得挑。", size = 11, color = s.inkFaint)
        } else {
            groups.forEach { (g, list) ->
                T(g, size = 10, color = s.inkFaint)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    list.forEach { vo ->
                        PickChip(vo.name, v == vo.name, { v = vo.name; onVoice(vo.name) }, Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (canStyle) {
            Lab("调门")
            var write by remember(name) { mutableStateOf(st.isNotBlank() && st !in STYLE_WORDS) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                STYLE_WORDS.forEach { w ->
                    PickChip(w, st == w, { st = if (st == w) "" else w; write = false; onStyle(st) }, Modifier.padding(end = 6.dp))
                }
                PickChip("自己写", write, {
                    write = true
                    if (st in STYLE_WORDS) st = ""
                    onStyle(st)
                }, Modifier.padding(end = 6.dp))
            }
            if (write) {
                Spacer(Modifier.height(8.dp))
                Field(st, "比如 低沉、急促、带笑", 0) { st = it.take(40); onStyle(st) }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (members.isNotEmpty()) {
            Lab("这一组 ${members.size} 个人")
            Column(Modifier.fillMaxWidth()) {
                /* 组名本人排在最前，没有 ×。 */
                if (self) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T(name, size = 13, modifier = Modifier.weight(1f), maxLines = 1)
                        T("组名", size = 9, color = s.inkFaint)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
                }
                members.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T(m, size = 13, modifier = Modifier.weight(1f), maxLines = 1)
                        Box(
                            Modifier
                                .tap { onRemove(m) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            T("×", size = 15, color = s.inkFaint)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
                }
            }
            if (pool.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Lab("再并谁进来")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    pool.forEach { r ->
                        PickChip(r.name, false, { onAdd(r.name) }, Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (members.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Btn("解散这一组", onDissolve, BtnKind.GHOST, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
        }
        Btn(
            if (members.isEmpty()) "删掉「$name」" else "删掉这一组",
            onDelete,
            BtnKind.DANGER, Modifier.fillMaxWidth(),
        )
    }
}

/** 完整名单。长书数出来一百多人，一次摊在主页上，规则那一块就滑不到了。 */
@Composable
private fun AllSheet(
    rows: List<CastRow>,
    find: String,
    onFind: (String) -> Unit,
    onClick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    val hit = if (find.isBlank()) rows
    else rows.filter { r ->
        val f = find.trim()
        r.name.contains(f) || r.who.any { it.contains(f) } || r.alt.any { it.contains(f) }
    }
    Sheet("角色 · ${rows.size}", onClose) {
        Field(find, "找谁", 0) { onFind(it) }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth()) {
            hit.forEach { r ->
                RoleRow(
                    name = r.name,
                    alt = r.alt,
                    lines = r.lines,
                    voice = r.voice,
                    tag = r.tag,
                    who = r.who,
                    onClick = { onClick(r.name) },
                )
            }
        }
    }
}

/** 规则那张纸。用户只回答两件事：派给谁、那句话长什么样。 */
@Composable
private fun RuleSheet(
    rule: CastRule?,
    ways: (String) -> List<String>,
    names: List<String>,
    onClose: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onOk: (String, String) -> Unit,
) {
    val s = LocalSkin.current
    var who by remember { mutableStateOf(rule?.character.orEmpty()) }
    var sample by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf(rule?.pattern.orEmpty()) }
    var touched by remember { mutableStateOf(false) }

    /** 原句一变就重拼一次；自己动过手就不覆盖了。 */
    LaunchedEffect(sample, who) {
        if (sample.isBlank() || who.isBlank() || touched) return@LaunchedEffect
        val made = Match.fromSample(sample, ways(who))
        if (made.isNotBlank()) pat = made
    }

    val err = if (pat.isBlank()) null
    else runCatching { Regex(pat) }.exceptionOrNull()?.let { "这条不通：" + it.message?.take(40) }
    val hit = if (sample.isBlank() || pat.isBlank() || err != null) null
    else runCatching { Regex(pat).containsMatchIn(sample) }.getOrDefault(false)

    Sheet(if (rule == null) "加一条规则" else "改这条规则", onClose) {
        Lab("派给谁")
        if (names.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                // 全部列出来。名字按句数排，常派的那几个本来就在最前；
                // 截成前三十个的话，第一百个人就永远派不到。
                names.forEach { n ->
                    PickChip(n, who == n, { who = n }, Modifier.padding(end = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Field(who, "角色名", 0) { who = it }

        Spacer(Modifier.height(14.dp))
        Lab("他说的是哪一句")
        Field(sample, "「别关门。」邮差小陈说。", 64) { sample = it }

        Spacer(Modifier.height(8.dp))
        when {
            sample.isBlank() -> {}
            who.isNotBlank() && !Match.found(sample, ways(who)) ->
                T("这句里没有「$who」，换个带他名字的句子。", size = 10, color = s.warn)
            pat.isBlank() ->
                T("这句里看不出他说话的样子。也许是旁白，也许动词太生 —— 下面自己写一条。", size = 10, color = s.warn)
            hit == true -> T("认出来了", size = 10, color = s.ok)
            hit == false -> T("没认出来，改改下面那条", size = 10, color = s.warn)
        }

        Spacer(Modifier.height(14.dp))
        Lab("规则")
        Field(pat, "(邮差小陈|小陈)(?:说道|道|说)", 80) { pat = it; touched = true }
        if (err != null) {
            Spacer(Modifier.height(4.dp))
            T(err, size = 10, color = s.bad)
        }

        Spacer(Modifier.height(18.dp))
        Btn(
            "存下",
            { onOk(who, pat) },
            BtnKind.PRIMARY, Modifier.fillMaxWidth(),
            enabled = who.isNotBlank() && pat.isNotBlank() && err == null,
        )
        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            Btn("删掉这条", onDelete, BtnKind.DANGER, Modifier.fillMaxWidth())
        }
    }
}

/** 题材里到底有些什么词。点一下那个词，它就不参与认人了。 */
@Composable
private fun WordsSheet(
    packs: List<String>,
    off: List<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    val picked = packs.mapNotNull { Words.packOf(it) }
    Sheet("认人用的词", onClose) {
        T("点一下划掉，它就不参与认人了。", size = 10, color = s.inkFaint)
        Spacer(Modifier.height(14.dp))

        // 基础表：一直都在，跟勾不勾题材无关。看得见才敢信它在干活。
        T("基础", size = 12, color = s.inkDim)
        Spacer(Modifier.height(2.dp))
        T(
            "动词 ${Words.verbs.size} · 称谓 ${Words.titles.size} · 状语 ${Words.advs.size}",
            size = 9, color = s.inkFaint,
        )
        Spacer(Modifier.height(8.dp))
        listOf("动词" to Words.verbs, "称谓" to Words.titles, "状语" to Words.advs).forEach { (label, list) ->
            T(label, size = 9, color = s.inkFaint)
            Spacer(Modifier.height(4.dp))
            list.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { w ->
                        Box(Modifier.weight(1f)) {
                            PickChip(w, w !in off, { onToggle(w) }, Modifier.fillMaxWidth())
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
        }

        if (picked.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
            Spacer(Modifier.height(14.dp))
            T("题材", size = 12, color = s.inkDim)
            Spacer(Modifier.height(8.dp))
        }
        picked.forEach { p ->
            T(p.cn, size = 11, color = s.inkDim)
            Spacer(Modifier.height(6.dp))
            listOf("动词" to p.verbs, "称谓" to p.titles, "状语" to p.advs).forEach { (label, list) ->
                if (list.isEmpty()) return@forEach
                T(label, size = 9, color = s.inkFaint)
                Spacer(Modifier.height(4.dp))
                list.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { w ->
                            Box(Modifier.weight(1f)) {
                                PickChip(w, w !in off, { onToggle(w) }, Modifier.fillMaxWidth())
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
internal fun Lab(title: String) {
    val s = LocalSkin.current
    T(title, size = 11, color = s.inkDim)
    Spacer(Modifier.height(7.dp))
}

@Composable
internal fun Field(value: String, hint: String, height: Int = 0, onChange: (String) -> Unit) {
    val s = LocalSkin.current
    val m = if (height > 0) Modifier.fillMaxWidth().height(height.dp) else Modifier.fillMaxWidth()
    Box(
        m.clip(RoundedCornerShape(9.dp)).background(s.panelSink).padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) T(hint, size = 12, color = s.inkFaint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 13.sp, color = s.ink),
            cursorBrush = SolidColor(s.flame),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── 存取 ──────────────────────────────────────────

private fun loadCast(app: App, book: BookMeta?): CastBook {
    /* 先从书架上取这一份，再退回调用方手里那份。 */
    val fresh = book?.let { app.shelf.find(it.id) }
    val own = fresh?.castJson.orEmpty().ifBlank { book?.castJson.orEmpty() }
    if (own.isNotBlank()) return heal(RuleCodec.fromJson(own))
    val g = app.field.now.castJson
    if (g.isNotBlank()) return heal(RuleCodec.fromJson(g))
    return CastBook(narrator = app.field.now.narratorVoice, dialog = app.field.now.defaultVoice)
}

/** 「怎么分配」那张纸。五步，一步一屏。 */
@Composable
private fun AiSheet() {
    val s = LocalSkin.current
    val step = AiJob.step
    val name = when (step) {
        AiJob.Step.PICK -> "用哪一路"
        AiJob.Step.DETAIL -> "怎么分配"
        AiJob.Step.NAMES -> "发哪些人"
        AiJob.Step.RUN -> "在排"
        AiJob.Step.PLAN -> "排好了"
    }
    /** 「给 AI 的话」开没开。 */
    var ask by remember { mutableStateOf(false) }
    /** 正在给谁挑那句台词。 */
    var pick by remember { mutableStateOf<String?>(null) }
    /** 结果纸上那两颗按钮各自的确认。 */
    var askApply by remember { mutableStateOf(false) }
    var askQuit by remember { mutableStateOf(false) }
    /** 正在改哪一组现造的音色。 */
    var editVoice by remember { mutableStateOf<String?>(null) }

    /* 返回键由 [Paper] 那层管：子纸先收，再收这张纸（onClose = AiJob.close）。 */

    Paper(
        title = name,
        onClose = { AiJob.close() },
        maxFraction = if (step == AiJob.Step.NAMES || step == AiJob.Step.PLAN) 0.92f else 0.86f,
        heightFraction = if (step == AiJob.Step.NAMES || step == AiJob.Step.PLAN) 0.92f else null,
        scroll = step != AiJob.Step.NAMES && step != AiJob.Step.PLAN,
    ) {
        when (step) {
            AiJob.Step.PICK -> StepPick(s)
            AiJob.Step.DETAIL -> StepDetail(s)
            AiJob.Step.NAMES -> StepNames(s, onAsk = { ask = true }, onPick = { pick = it })
            AiJob.Step.RUN -> StepRun(s)
            AiJob.Step.PLAN -> StepPlan(
                s,
                onApply = { askApply = true },
                onQuit = { askQuit = true },
                onEdit = { editVoice = it },
            )
        }
    }

    if (ask) AskSheet { ask = false }
    pick?.let { who -> PickLineSheet(who) { pick = null } }

    /* 两张确认纸。「就用这些」会盖掉这本书现在的角色和音色，退出去则是把这一份整份丢掉 ——两件事都不可撤回，都得先问一句。 */
    if (askApply) {
        val n = AiJob.plan?.groups?.count { it.name in AiJob.use } ?: 0
        ConfirmSheet(
            title = "用这一份？",
            body = "将按这 $n 组重写这本书的角色表：各组的人换成这一组的音色，没选中的组并回「旁白」。" +
                "现在的对应关系会被盖掉。",
            ok = "就用这些",
            onOk = { askApply = false; AiJob.apply() },
            onClose = { askApply = false },
        )
    }
    if (askQuit) {
        ConfirmSheet(
            title = "不看了？",
            body = "退出会把这一份结果丢掉，角色表一个字都不改。想再排一次，回派角点「让 AI 排」，从头再发一趟。",
            ok = "退出",
            kind = BtnKind.DANGER,
            onOk = { askQuit = false; AiJob.discard() },
            onClose = { askQuit = false },
        )
    }
    editVoice?.let { who ->
        GroupVoiceSheet(who) { editVoice = null }
    }
}

/** 一句话的确认纸。不写「确定 / 取消」：两颗按钮上都该写清这一下会发生什么。 */
@Composable
private fun ConfirmSheet(
    title: String,
    body: String,
    ok: String,
    kind: BtnKind = BtnKind.PRIMARY,
    onOk: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    Paper(title = title, onClose = onClose, maxFraction = 0.56f) {
        T(body, size = 12, color = s.inkDim)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("算了", onClose, BtnKind.GHOST, Modifier.weight(1f))
            Btn(ok, onOk, kind, Modifier.weight(1f))
        }
    }
}

/** 改一组现造的音色。现造的那一副此刻还只是一个描述 —— 名字要等真造出来才有。 */
@Composable
private fun GroupVoiceSheet(name: String, onClose: () -> Unit) {
    val s = LocalSkin.current
    val g = AiJob.plan?.groups?.firstOrNull { it.name == name }
    if (g == null) { onClose(); return }

    var desc by remember(name, g.desc) { mutableStateOf(g.desc) }
    val voices = remember { AiJob.voiceAll.toList() }

    Paper(
        title = "「$name」用什么音色",
        onClose = onClose,
        maxFraction = 0.86f,
        heightFraction = 0.80f,
        scroll = false,
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item(key = "desc") {
                T("照这句话现造一副", size = 11, color = s.inkDim)
                Spacer(Modifier.height(6.dp))
                Field(desc, "比如 中年男声，低沉偏沙，语速慢", 0) { t ->
                    desc = t.take(60)
                    AiJob.setGroupVoice(name, "", desc.trim())
                }
                Spacer(Modifier.height(14.dp))
                T("或者用现成的", size = 11, color = s.inkDim)
                Spacer(Modifier.height(6.dp))
            }
            items(voices, key = { it }) { v ->
                Row(
                    Modifier.fillMaxWidth()
                        .tap {
                            AiJob.setGroupVoice(name, v, "")
                            onClose()
                        }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(v, size = 12, modifier = Modifier.weight(1f), maxLines = 1)
                    if (v == g.voice) T("正在用", size = 9, color = s.flame)
                    Spacer(Modifier.width(6.dp))
                    T("›", size = 13, color = s.inkFaint)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
            }
        }
    }
}

/** 第一步：开着的接口不止一路，先问走哪一路。只有一路时这一步根本不出现。 */
@Composable
private fun StepPick(s: com.pagefold.reader.kit.Skin) {
    AiJob.picks.forEach { c ->
        val hit = c.id == AiJob.chatId
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .tap { AiJob.chatId = c.id }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                T(c.name, size = 13, color = if (hit) s.ink else s.inkDim, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                T((c.model ?: "").ifBlank { "没挑模型" }, size = 10, color = s.inkFaint, maxLines = 1)
            }
            T(if (hit) "用这个" else "", size = 10, color = s.flame)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
    }
    Spacer(Modifier.height(16.dp))
    Btn("下一步", { AiJob.step = AiJob.Step.DETAIL }, BtnKind.PRIMARY, Modifier.fillMaxWidth())
}

/** 第二步：详细还是简单。两档各占一行，标题下面写的就是它到底发什么 ——「详细」不是形容词，是「多带一句」。 */
@Composable
private fun StepDetail(s: com.pagefold.reader.kit.Skin) {
    ModeRow("详细", "把这个人还有一句话发给 AI", AiJob.deep) { AiJob.deep = true }
    Spacer(Modifier.height(8.dp))
    ModeRow("简单", "光发姓名和句数", !AiJob.deep) { AiJob.deep = false }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("返回", { AiJob.back() }, BtnKind.GHOST, Modifier.weight(1f))
        Btn("下一步", { AiJob.step = AiJob.Step.NAMES }, BtnKind.PRIMARY, Modifier.weight(1f))
    }
}

/** 一档。整行都能点，选中的那一档描一道朱边。 */
@Composable
private fun ModeRow(title: String, note: String, on: Boolean, onClick: () -> Unit) {
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (on) s.flameGhost else Color.Transparent)
            .border(
                1.dp,
                if (on) s.flame.copy(alpha = 0.55f) else s.hair,
                RoundedCornerShape(11.dp),
            )
            .tap { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, size = 13, color = if (on) s.flame else s.ink, modifier = Modifier.weight(1f))
            if (on) T("用这个", size = 10, color = s.flame)
        }
        Spacer(Modifier.height(3.dp))
        T(note, size = 11, color = s.inkDim)
    }
}

/** 第三步：这一趟发哪些人。门槛只是起手那一下的筛子。 */
@Composable
private fun ColumnScope.StepNames(
    s: com.pagefold.reader.kit.Skin,
    onAsk: () -> Unit,
    onPick: (String) -> Unit,
) {
    val all = AiJob.pool
    val on = all.count { it.name in AiJob.sent }
    T("发 $on 个 · 一共 ${all.size} 个", size = 13)
    Spacer(Modifier.height(10.dp))

    /* 门槛：一个能写数的格子，默认 50，存进设置，退出再进不变。 */
    var gateText by remember { mutableStateOf(AiJob.gate.toString()) }
    LaunchedEffect(AiJob.gate) {
        if (gateText.toIntOrNull() != AiJob.gate) gateText = AiJob.gate.toString()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("几句话以上才发", size = 12, color = s.inkDim, modifier = Modifier.weight(1f))
        Box(Modifier.width(104.dp)) {
            Field(value = gateText, hint = "50", height = 42) { t ->
                val d = t.filter { it.isDigit() }.take(4)
                gateText = d
                d.toIntOrNull()?.let { AiJob.gateTo(it) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    T(
        if (AiJob.deep) "这个数只是起手筛一遍。这一档只换句子 —— 想改人，回上一档选简单。"
        else "这个数只是起手筛一遍。下面是「发」和「不发」：勾着的这一次才发，点一行就换。",
        size = 10, color = s.inkFaint,
    )
    Spacer(Modifier.height(10.dp))

    /* 名单自己是滚的，表头和底下那两颗按钮钉住。 */
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(all, key = { it.name }) { p ->
        val hit = p.name in AiJob.sent
        Column(
            Modifier.fillMaxWidth()
                /* 分档，是用户定的：· 简单 —— 每行点一下「发 / 留」，人是这儿定的；· 详细 —— 只换那一句台词，人不动。 */
                .then(
                    if (AiJob.deep) Modifier else Modifier.tap { AiJob.flip(p.name) }
                )
                .padding(vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(
                    p.name, size = 12,
                    color = if (hit) s.ink else s.inkFaint,
                    modifier = Modifier.weight(1f), maxLines = 1,
                )
                T("${p.lines} 句", size = 9, color = s.inkFaint)
                if (!AiJob.deep) {
                    Spacer(Modifier.width(10.dp))
                    /* 两个字都要能读出「现在是什么状态」。 */
                    Tick(hit, "发", "不发")
                }
            }
            if (AiJob.deep) {
                Spacer(Modifier.height(2.dp))
                val one = AiJob.sampleOf(p)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(
                        if (one.isBlank()) "（他这一句还没挑）" else "「" + one + "」",
                        size = 10, color = s.inkFaint,
                        modifier = Modifier.weight(1f), maxLines = 2,
                    )
                    Text(
                        "换",
                        fontSize = 10.sp,
                        color = s.flame,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .tap { onPick(p.name) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
        }
    }

    Spacer(Modifier.height(12.dp))
    Btn("给 AI 的话", onAsk, BtnKind.GHOST, Modifier.fillMaxWidth())

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("返回", { AiJob.back() }, BtnKind.GHOST, Modifier.weight(1f))
        Btn("开排", {
            AiJob.done = 0
            AiJob.startedAt = 0L
            AiJob.run()
        }, BtnKind.PRIMARY, Modifier.weight(1f), enabled = AiJob.sent.isNotEmpty())
    }
}

/** 「给 AI 的话」。两件事：准不准它自己造、准它用哪几副；底下一段是这一趟真会发出去的原文。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskSheet(onClose: () -> Unit) {
    val s = LocalSkin.current
    Sheet("给 AI 的话", onClose) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("允许它自己造嗓音", size = 13, modifier = Modifier.weight(1f))
            Pill(AiJob.design, { AiJob.design = it })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.hairSoft))

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("准它用的嗓子", size = 13, modifier = Modifier.weight(1f))
            T(
                "${AiJob.voiceUse.size} / ${AiJob.voiceAll.size}",
                size = 10, color = s.inkFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (AiJob.voiceAll.isEmpty()) {
            T("音色库里一副都没有。先去定音里开一路声源。", size = 11, color = s.inkFaint)
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                AiJob.voiceAll.forEach { n ->
                    PickChip(
                        n,
                        n in AiJob.voiceUse,
                        {
                            AiJob.voiceUse =
                                if (n in AiJob.voiceUse) AiJob.voiceUse - n else AiJob.voiceUse + n
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("全给", { AiJob.voiceUse = AiJob.voiceAll.toSet() }, BtnKind.GHOST)
                Btn("一副不给", { AiJob.voiceUse = emptySet() }, BtnKind.GHOST)
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.hairSoft))
        Spacer(Modifier.height(12.dp))
        T("发出去的整段", size = 13)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(s.panelSink)
                .padding(10.dp)
        ) {
            T(AiJob.preview(), size = 9, color = s.inkDim, maxLines = 400)
        }
    }
}

/** 给一个人挑那句台词。从他全部的话里取，不按章分组。 */
@Composable
private fun PickLineSheet(who: String, onClose: () -> Unit) {
    val s = LocalSkin.current
    val bookId = AiJob.bookId
    val ctx = LocalContext.current
    val cand = remember(who, ctx) {
        if (bookId.isBlank()) emptyList()
        else com.pagefold.reader.book.Lines.head(ctx, bookId, who, 24)
    }
    var draft by remember(who) { mutableStateOf("") }
    Sheet("给「$who」挑一句", onClose) {
        if (cand.isEmpty()) {
            T("句库里没有他的话。手写一句也行。", size = 10, color = s.inkFaint)
        } else {
            cand.forEach { q ->
                Row(
                    Modifier.fillMaxWidth()
                        .tap {
                            AiJob.setPick1(who, q.text)
                            onClose()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("「" + q.text + "」", size = 11, color = s.inkDim,
                        modifier = Modifier.weight(1f), maxLines = 2)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.04f)))
            }
        }
        Spacer(Modifier.height(10.dp))
        Field(draft, "也可以自己写一句", 0) { draft = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("用这一句", {
                AiJob.setPick1(who, draft)
                onClose()
            }, BtnKind.PRIMARY, Modifier.weight(1f), enabled = draft.isNotBlank())
            Btn("不要这一句", {
                AiJob.setPick1(who, "")
                onClose()
            }, BtnKind.GHOST, Modifier.weight(1f))
        }
    }
}

/** 第四步：在排。进度是实数 —— 第几批、多少人、过了多久。 */
@Composable
private fun StepRun(s: com.pagefold.reader.kit.Skin) {
    var tick by remember { mutableStateOf(0) }
    val live = AiJob.err == null && !AiJob.paused
    LaunchedEffect(live) {
        while (live) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val secs = AiJob.seconds() + tick * 0L
    val all = AiJob.pool.count { it.name in AiJob.sent }

    T(AiJob.stage.ifBlank { "第 ${AiJob.done + 1} 批，正在发" }, size = 13)
    Spacer(Modifier.height(4.dp))
    T(
        if (AiJob.brief.isNotBlank()) AiJob.brief else "一共 ${AiJob.total} 批 · $all 人",
        size = 11, color = s.inkFaint,
    )
    Spacer(Modifier.height(14.dp))

    // 一根进度条。没到 100% 之前它就是「还没完」，别用一句话假装说完。
    val frac = if (AiJob.total <= 0) 0f else AiJob.done.toFloat() / AiJob.total
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(s.ink.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxWidth(frac).height(3.dp).background(s.flame))
    }
    Spacer(Modifier.height(6.dp))
    T("${AiJob.done} / ${AiJob.total} 批" + if (secs > 0) " · 已过 ${secs} 秒" else "", size = 10, color = s.inkFaint)

    AiJob.err?.let { e ->
        Spacer(Modifier.height(12.dp))
        T(e, size = 11, color = s.bad)
    }

    Spacer(Modifier.height(18.dp))
    if (AiJob.paused || AiJob.err != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("接着排", { AiJob.run() }, BtnKind.PRIMARY, Modifier.weight(1f))
            Btn("收起", { AiJob.close() }, BtnKind.GHOST, Modifier.weight(1f))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("暂停", { AiJob.pause() }, BtnKind.PRIMARY, Modifier.weight(1f))
            Btn("收起，回来还在", { AiJob.close() }, BtnKind.GHOST, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        T("收起来它照样在排。排完顶上会出来一条，点它回到这儿。", size = 10, color = s.inkFaint)
    }
}

/** 第五步：结果。摆法跟派角那一页一样：左边人，右边音色。 */
@Composable
private fun ColumnScope.StepPlan(
    s: com.pagefold.reader.kit.Skin,
    onApply: () -> Unit,
    onQuit: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val p = AiJob.plan ?: return
    val on = p.groups.filter { it.name in AiJob.use }
    val newN = on.count { it.desc.isNotBlank() }
    val heads = on.sumOf { it.members.size }

    T(
        "新建 $newN 副音色 · $heads 个角色 · ${on.size} 组",
        size = 14,
    )
    Spacer(Modifier.height(10.dp))

    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(p.groups, key = { it.name }) { g ->
            val hit = g.name in AiJob.use
            Row(
                Modifier.fillMaxWidth()
                    .tap { AiJob.use = if (hit) AiJob.use - g.name else AiJob.use + g.name }
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /* 左：人。多人的组把人数写在名字后面，不再单占一行。 */
                Column(Modifier.weight(1f)) {
                    T(
                        g.name + if (g.members.size > 1) " · ${g.members.size} 人" else "",
                        size = 13, color = if (hit) s.ink else s.inkFaint, maxLines = 1,
                    )
                    val more = g.members.filter { it != g.name }
                    if (more.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        T(more.joinToString("、"), size = 10, color = s.inkFaint, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(10.dp))
                /* 右：音色。现造的可以点进去改 —— 它只是一个描述，还没造出来；现成的那几副是音色库里的东西，这一页不该改它（要换就在上面那一列点别组）。 */
                if (g.desc.isNotBlank()) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, s.flame.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .tap { onEdit(g.name) }
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T("现造", size = 10, color = s.flame)
                        Spacer(Modifier.width(4.dp))
                        T("改", size = 10, color = s.flame)
                    }
                } else {
                    T(
                        g.voice.ifBlank { "对白那副" },
                        size = 11, color = if (hit) s.inkDim else s.inkFaint, maxLines = 1,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Tick(hit, "用", "不用")
            }
            if (g.desc.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                T(g.desc, size = 10, color = s.inkFaint, maxLines = 1)
                Spacer(Modifier.height(6.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
        }

        if (p.drop.isNotEmpty()) {
            item(key = "dropHead") {
                Spacer(Modifier.height(14.dp))
                T("它判成不是人名的 ${p.drop.size} 个", size = 11, color = s.inkDim)
                Spacer(Modifier.height(2.dp))
                T(
                    "勾上「留下」：还是发出去，跟着对白那副嗓子念。不勾就是这次不用它们，表里不会多出这个人。",
                    size = 10, color = s.inkFaint,
                )
                Spacer(Modifier.height(4.dp))
            }
            items(p.drop, key = { "d" + it.name }) { d ->
                val held = d.name in AiJob.keep
                Row(
                    Modifier.fillMaxWidth()
                        .tap { AiJob.keep = if (held) AiJob.keep - d.name else AiJob.keep + d.name }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    T(d.name, size = 12, color = if (held) s.ink else s.inkDim, modifier = Modifier.weight(1f))
                    T(d.why, size = 10, color = s.inkFaint, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Tick(held, "留下", "不要")
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("就用这些", onApply, BtnKind.PRIMARY, Modifier.weight(1f), enabled = on.isNotEmpty())
        Btn("退出", onQuit, BtnKind.GHOST, Modifier.weight(1f))
    }
}
