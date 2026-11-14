package com.pagefold.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.book.Cast
import com.pagefold.reader.book.CastProfile
import com.pagefold.reader.book.Chapter
import com.pagefold.reader.book.Line
import com.pagefold.reader.book.Lines
import com.pagefold.reader.book.Pin
import com.pagefold.reader.book.Quote
import com.pagefold.reader.book.RuleCodec
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Paper
import com.pagefold.reader.kit.PickChip
import com.pagefold.reader.kit.T
import com.pagefold.reader.kit.tap
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个人那张纸：上面是嗓子，下面是他这一辈子说过的话。
 * 分成两张纸，用户每次都得先想「这件事在哪个抽屉里」。
 */
@Composable
internal fun PersonSheet2(
    name: String,
    members: List<String>,
    voice: String,
    style: String,
    groups: List<Pair<String, List<Voice>>>,
    canStyle: Boolean,
    app: App,
    bookId: String,
    pins: List<Pin>,
    roles: List<String>,
    chapters: List<Chapter>,
    onJump: ((ch: Int, at: Int) -> Unit)?,
    onVoice: (String) -> Unit,
    onStyle: (String) -> Unit,
    onPin: (List<Quote>, String) -> Unit,
    /** 删掉这个人。组里的人先拿出来再用这个 —— 单人纸只删一个。 */
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current

    var tick by remember { mutableStateOf(0) }
    val all = remember(bookId, name, pins, tick) {
        if (bookId.isBlank()) emptyList() else Lines.view(app, bookId, name, pins)
    }

    var chPick by remember(name) { mutableStateOf(-1) }
    var sel by remember(name) { mutableStateOf<Set<Long>>(emptySet()) }
    /** 摊开的那一行。同一时刻只摊一行，摊第二行就收第一行。 */
    var open by remember(name) { mutableStateOf<Long?>(null) }
    var moving by remember { mutableStateOf(false) }
    var one by remember { mutableStateOf<Quote?>(null) }
    var read by remember { mutableStateOf(false) }
    var voiceOpen by remember(name) { mutableStateOf(false) }

    fun key(q: Quote): Long = q.ch.toLong() * 100_000_000L + q.at

    val byCh = remember(all) { all.groupBy { it.ch } }
    /* 章签按章节顺序排，不按句数。 */
    val chs = remember(byCh) { byCh.keys.sorted() }
    val shown = remember(all, chPick) { if (chPick < 0) all else all.filter { it.ch == chPick } }

    /* 用 LazyColumn，不用 Column。 */
    Paper(
        title = name + if (members.isEmpty()) "" else "（这一组 ${members.size + 1} 人）",
        onClose = onClose,
        maxFraction = 0.86f,
        heightFraction = 0.86f,
        scroll = false,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            /* ── 嗓子 ── */
            item(key = "voice") {
                /* 嗓子这一行要够大，进来第一眼先看见它。 */
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T("用的音色", size = 10, color = s.inkFaint)
                        Spacer(Modifier.height(3.dp))
                        T(
                            voice.ifBlank { "对白那副" },
                            size = 19,
                            color = if (voice.isBlank()) s.inkDim else s.flame,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Btn(
                        if (voiceOpen) "收起" else "换音色",
                        { voiceOpen = !voiceOpen },
                        if (voiceOpen) BtnKind.GHOST else BtnKind.PRIMARY,
                        Modifier.width(104.dp),
                    )
                }
                if (voiceOpen) {
                    if (groups.isEmpty()) {
                        T("一副都没开。去定音里开一路声源，这里才有得挑。", size = 11, color = s.inkFaint)
                    } else {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            PickChip("跟对白那一副", voice.isBlank(), { onVoice("") }, Modifier.padding(end = 6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        groups.forEach { (g, list) ->
                            T(g, size = 10, color = s.inkFaint)
                            Spacer(Modifier.height(5.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                list.forEach { v ->
                                    PickChip(
                                        v.name, voice == v.name, { onVoice(v.name) },
                                        Modifier.padding(end = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (canStyle) {
                            Lab("调门")
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                STYLE_WORDS.forEach { w ->
                                    PickChip(
                                        w, style == w,
                                        { onStyle(if (style == w) "" else w) },
                                        Modifier.padding(end = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                /* 删掉这个人。单人点进来也必须有这个出口。 */
                Spacer(Modifier.height(6.dp))
                Btn(
                    "删除这个角色", onDelete, BtnKind.DANGER,
                    Modifier.fillMaxWidth().height(52.dp),
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
                /* 下面那块是「他说过的话」：让它多留一点空，别贴着上面那条线。 */
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("他说过的话", size = 13, modifier = Modifier.weight(1f))
                    if (all.isNotEmpty()) T("共 ${all.size} 句", size = 10, color = s.inkFaint)
                }
                Spacer(Modifier.height(8.dp))
            }

            /* ── 空句库：补扫 ── */
            if (bookId.isBlank() || all.isEmpty()) {
                item(key = "empty") {
                    T(
                        if (bookId.isBlank()) "这本书还没建句库。" else "句库里没有他的话。补数一次就有了。",
                        size = 10, color = s.inkFaint,
                    )
                    Spacer(Modifier.height(8.dp))
                    Btn(
                        if (read) "正在数…" else "重新数他",
                        {
                            read = true
                            app.countsRead(bookId, name) {
                                read = false
                                tick++
                            }
                        },
                        BtnKind.GHOST, Modifier.fillMaxWidth(),
                        enabled = bookId.isNotBlank() && !read,
                    )
                }
            } else {
                /* ── 章签 ── */
                item(key = "chips") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        PickChip("全部 ${all.size}", chPick < 0, { chPick = -1 }, Modifier.padding(end = 6.dp))
                        chs.forEach { c ->
                            val n = byCh[c]?.size ?: 0
                            PickChip(
                                chName(chapters, c) + " $n",
                                chPick == c,
                                { chPick = if (chPick == c) -1 else c },
                                Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (shown.isEmpty()) {
                    item(key = "none") {
                        T("「" + chName(chapters, chPick) + "」这一章他没开口。", size = 11, color = s.inkFaint)
                    }
                }

                /* ── 一句一行 ── */
                itemsIndexed(shown, key = { _, q -> "q" + key(q) }) { i, q ->
                    val on = key(q) in sel
                    val ex = open == key(q)
                    val prev = shown.getOrNull(i - 1)
                    Column(Modifier.fillMaxWidth()) {
                        if (prev == null || prev.ch != q.ch) {
                            Spacer(Modifier.height(6.dp))
                            T(chName(chapters, q.ch), size = 10, color = s.flame)
                            Spacer(Modifier.height(2.dp))
                        }
                        /* 点这一行摊开两颗按钮，不直接跳走。 */
                        Row(
                            Modifier.fillMaxWidth()
                                .tap {
                                    if (sel.isNotEmpty()) {
                                        sel = if (on) sel - key(q) else sel + key(q)
                                    } else {
                                        open = if (ex) null else key(q)
                                    }
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(20.dp)
                                    .tap { sel = if (on) sel - key(q) else sel + key(q) }
                            ) {
                                T(if (on) "✓" else "·", size = 11, color = if (on) s.flame else s.inkFaint)
                            }
                            T(
                                "「" + q.text + "」",
                                size = 11,
                                color = if (on) s.ink else s.inkDim,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (ex) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (onJump != null) {
                                    Btn("跳到书里", { onJump(q.ch, q.at) }, BtnKind.GHOST, Modifier.weight(1f))
                                }
                                Btn("换个人说", { one = q }, BtnKind.GHOST, Modifier.weight(1f))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.04f)))
                    }
                }

                /* ── 底下那两排 ── */
                item(key = "bottom") {
                    Spacer(Modifier.height(12.dp))
                    if (sel.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn("整份并给…", { moving = true }, BtnKind.GHOST, Modifier.weight(1f))
                            Btn(
                                if (read) "正在数…" else "重新数他",
                                {
                                    read = true
                                    app.countsRead(bookId, name) {
                                        read = false
                                        tick++
                                    }
                                },
                                BtnKind.GHOST, Modifier.weight(1f), enabled = !read,
                            )
                        }
                    } else {
                        T("选了 ${sel.size} 句", size = 11, color = s.flame)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn("转给别人", { moving = true }, BtnKind.PRIMARY, Modifier.weight(1f))
                            Btn("取消选择", { sel = emptySet() }, BtnKind.GHOST, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (moving) {
        val qs = if (sel.isEmpty()) all else shown.filter { key(it) in sel }
        PickWho(
            title = "把 ${qs.size} 句转给",
            roles = roles.filter { it != name },
            onPick = { who ->
                onPin(qs, who)
                sel = emptySet()
                moving = false
                tick++
            },
            onClose = { moving = false },
        )
    }

    one?.let { q ->
        PickWho(
            title = "这一句换谁说",
            roles = roles.filter { it != name },
            onPick = { who ->
                onPin(listOf(q), who)
                one = null
                open = null
                tick++
            },
            onClose = { one = null },
        )
    }
}

/** 章名。目录还没读出来时退回「第 N 章」，不编一个名字。 */
internal fun chName(chapters: List<Chapter>, ch: Int): String {
    val t = chapters.getOrNull(ch)?.title.orEmpty().trim()
    return if (t.isNotBlank()) t.take(14) else "第 ${ch + 1} 章"
}

/**
 * 「挑一个人」。名单 = 这本书里数出来的所有人（[rowsOf]），不是「已经配过嗓子的角色表」。
 * 表里真没有的名字，当场写一个也认：单句改派是按位置记的，不要求那个人先在名单里。
 */
@Composable
internal fun PickWho(
    title: String,
    roles: List<String>,
    current: String? = null,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    var find by remember { mutableStateOf("") }
    var hand by remember { mutableStateOf("") }
    Paper(
        title = title,
        onClose = onClose,
        heightFraction = 0.74f,
        scroll = false,
    ) {
        T(
            if (current.isNullOrBlank()) "这一句还没认出来是谁。点一个人，或者写一个名字。"
            else "现在写的是「$current」。点一个人换掉他。",
            size = 11, color = s.inkDim,
        )
        Spacer(Modifier.height(10.dp))
        Field(find, "找谁", 0) { find = it }
        Spacer(Modifier.height(8.dp))

        val list = remember(roles, find) {
            if (find.isBlank()) roles
            else roles.filter { it.contains(find.trim()) }
        }
        if (list.isEmpty()) {
            T(
                if (roles.isEmpty()) "这本书还没数出人来。下面写一个名字也行。" else "没有这个叫法。下面写一个也行。",
                size = 11, color = s.inkFaint,
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(list, key = { it }) { n ->
                val hit = n == current
                Row(
                    Modifier.fillMaxWidth()
                        .tap { onPick(n) }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(
                        n, size = 13,
                        color = if (hit) s.flame else s.ink,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    if (hit) T("就是这句", size = 9, color = s.flame)
                    Spacer(Modifier.width(6.dp))
                    T("›", size = 13, color = s.inkFaint)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Field(hand, "不在表里？写个名字", 0) { hand = it }
            }
            Spacer(Modifier.width(8.dp))
            Btn(
                "就用",
                {
                    val n = hand.trim()
                    if (n.isNotEmpty()) onPick(n)
                },
                BtnKind.GHOST,
                enabled = hand.isNotBlank(),
            )
        }
    }
}

/**
 * 台账：这本书里，按章按顺序，每一句是谁说的。
 * 跟「他的话」那张纸是同一份数据的两个看法 —— 一个按人，一个按顺序。
 */
@Composable
internal fun LedgerSheet(
    app: App,
    bookId: String,
    /** 一打开停在哪一章。一般是用户正在读的那一章。 */
    startCh: Int = 0,
    chapters: List<Chapter>,
    body: suspend (Int) -> String,
    pins: List<Pin>,
    roles: List<String>,
    onPin: (ch: Int, at: Int, who: String) -> Unit,
    /** 点「跳到这一句」：回正文那一处。空 = 从定音进来，没有书可跳。 */
    onJump: ((ch: Int, at: Int) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    /* 从哪一章开始看。用户是从派角页点进来的，那时他正在读某一章 —— 台账一开就停在第 1 章、还得自己翻回去，这不叫「按顺序通看」。 */
    var ch by remember(bookId) { mutableStateOf(startCh.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))) }
    /* 整句留着，不只留人名。只写名字的时候连着十行「赵惊鸿」，谁也认不出是哪一句。 */
    var rows by remember(bookId) { mutableStateOf<List<com.pagefold.reader.book.Line>>(emptyList()) }
    var busy by remember(bookId) { mutableStateOf(false) }
    var fixing by remember { mutableStateOf<Int?>(null) }

    /* 章一换就去切这一章的对白归属。 */
    LaunchedEffect(bookId, ch) {
        if (bookId.isBlank()) return@LaunchedEffect
        busy = true
        rows = emptyList()
        val t = withContext(Dispatchers.IO) { body(ch) }
        if (t.isBlank()) { busy = false; return@LaunchedEffect }
        val got = runCatching {
            val bm = app.shelf.find(bookId)
            val cb = RuleCodec.fromJson(bm?.castJson.orEmpty().ifBlank { app.field.now.castJson })
            val pins = cb.pins.filter { it.ch == ch }.associate { it.at to it.who }
            Cast.plan(t, profOf(app, bookId), 0, pins).filter { it.isDialog }
        }.getOrDefault(emptyList())
        rows = got
        busy = false
    }

    /* 台账翻章只读那一章。目录还没建过就是空的 —— 说清楚，别让人以为这一章没对白。 */
    val hasIdx = remember(bookId) {
        bookId.isNotBlank() && runCatching { app.shelf.hasIndex(bookId) }.getOrDefault(false)
    }

    /* 台账也用 LazyColumn。 */
    Paper(
        title = "这本书里谁在说话",
        onClose = onClose,
        maxFraction = 0.86f,
        heightFraction = 0.86f,
        scroll = false,
    ) {
        if (bookId.isBlank()) {
            T("从书里进来才有这一页。", size = 11, color = s.inkFaint)
            return@Paper
        }
        /* 翻章：两条路都给。一是左右那两颗；二是左右划 —— 一段一段往下看的人，手指不会去够角上那两颗小按钮。 */
        Row(
            Modifier.fillMaxWidth()
                .pointerInput(chapters.size) {
                    detectHorizontalDragGestures { _, drag ->
                        if (kotlin.math.abs(drag) > 40f) {
                            if (drag < 0) { if (ch < chapters.size - 1) ch++ }
                            else { if (ch > 0) ch-- }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Btn("‹", { if (ch > 0) ch-- }, BtnKind.GHOST, Modifier.width(48.dp), enabled = ch > 0)
            T(
                chName(chapters, ch) + "   ${ch + 1} / ${chapters.size.coerceAtLeast(1)}",
                size = 12, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1,
            )
            Btn("›", { if (ch < chapters.size - 1) ch++ }, BtnKind.GHOST, Modifier.width(48.dp),
                enabled = ch < chapters.size - 1)
        }
        Spacer(Modifier.height(8.dp))
        if (!hasIdx) {
            T("目录还没建。先进这本书翻一页，回来这儿就有了。", size = 11, color = s.inkFaint)
        } else if (busy) {
            T("正在切这一章…", size = 11, color = s.inkFaint)
        } else if (rows.isEmpty()) {
            T("这一章没有对白。", size = 11, color = s.inkFaint)
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows, key = { _, r -> "r" + r.srcStart }) { _, ln ->
                val at = ln.srcStart
                val pin = pins.firstOrNull { it.ch == ch && it.at == at }
                val real = pin?.who ?: ln.speaker
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .tap { fixing = at }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            /* 先说这一句归谁，再说他说了什么。连着十行同一个人时，看的是下面那行字。 */
                            T(
                                real.ifBlank { "未识别出" } + if (pin != null) " · 改过" else " · 改",
                                size = 11,
                                color = if (real.isBlank()) s.inkFaint else if (pin != null) s.flame else s.inkDim,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(3.dp))
                            T(ln.text, size = 12, color = s.ink, maxLines = 2)
                        }
                        if (onJump != null) {
                            Spacer(Modifier.width(8.dp))
                            T(
                                "跳到 ›", size = 10, color = s.flame,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .tap { onJump(ch, at) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.05f)))
                }
            }
            item(key = "hint") {
                Spacer(Modifier.height(8.dp))
                T(
                    "左右划或点 ‹ › 换章。点一行改这一句归谁 —— 改了不动规则，只改这一句。",
                    size = 10, color = s.inkFaint,
                )
            }
        }
    }

    fixing?.let { at ->
        PickWho(
            title = "这一句归谁",
            roles = roles,
            current = rows.firstOrNull { it.srcStart == at }?.speaker
                ?.ifBlank { null }
                ?: pins.firstOrNull { it.ch == ch && it.at == at }?.who,
            onPick = { who ->
                onPin(ch, at, who)
                fixing = null
            },
            onClose = { fixing = null },
        )
    }
}

/** 按这本书自己的配置摊一份 profile。台账只用来切对白归属，不取嗓子。 */
private fun profOf(app: App, bookId: String): CastProfile {
    val bm = app.shelf.find(bookId)
    val st = app.field.now
    val cb = RuleCodec.fromJson(bm?.castJson.orEmpty().ifBlank { st.castJson })
    val known = LinkedHashSet<String>()
    val canon = LinkedHashMap<String, String>()
    cb.pool.filter { it.on }.forEach { n ->
        known += n.name
        n.alt.forEach { if (it.isNotBlank()) { known += it; canon[it] = n.name } }
    }
    cb.roles.forEach { r ->
        if (r.name.isNotBlank()) {
            known += r.name
            r.alt.forEach { if (it.isNotBlank()) { known += it; canon[it] = r.name } }
        }
    }
    return CastProfile(
        narratorVoice = "",
        defaultVoice = "",
        rules = cb.active(),
        voices = emptyMap(),
        styles = emptyMap(),
        ban = if (known.isEmpty()) null else com.pagefold.reader.book.Ban(
            names = known,
            gender = cb.sex,
            packs = cb.packs,
            extraVerb = cb.extraVerb,
            extraAdv = cb.extraAdv,
            extraTitle = cb.extraTitle,
            off = cb.off,
            canon = canon,
        ),
    )
}

/** 给一个人补扫他的句子。用户手写一个名字进来，句数是 0 —— 那不叫「这个人没说过话」，那叫「还没数过」。 */
internal fun App.countsRead(bookId: String, name: String, done: (Int) -> Unit) {
    if (bookId.isBlank()) { done(0); return }
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
        val n = withContext(Dispatchers.IO) {
            runCatching {
                val bm = shelf.find(bookId) ?: return@runCatching 0
                val raw = shelf.read(bookId).orEmpty()
                if (raw.isBlank()) return@runCatching 0
                val chs = com.pagefold.reader.book.Chapters.of(this@countsRead, bm, raw)
                val got = Lines.addOne(this@countsRead, bookId, raw, chs, name)
                if (got.isEmpty()) return@runCatching 0
                /* 句数和那句台词样例跟着改：列表上写 0 句，人会以为扫失败了。 */
                val old = RuleCodec.fromJson(bm.castJson.ifBlank { field.now.castJson })
                val best = got.maxByOrNull { it.text.length }?.text.orEmpty()
                val pool = if (old.pool.any { it.name == name }) {
                    old.pool.map {
                        if (it.name == name) it.copy(lines = got.size, sample = it.sample.ifBlank { best })
                        else it
                    }
                } else {
                    old.pool + com.pagefold.reader.book.CastName(
                        name = name, lines = got.size, on = true, sample = best,
                    )
                }
                shelf.setCast(bookId, RuleCodec.toJson(old.copy(pool = pool)))
                got.size
            }.getOrDefault(0)
        }
        done(n)
    }
}
