package com.pagefold.reader.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.kit.Glyph
import com.pagefold.reader.App
import com.pagefold.reader.R
import com.pagefold.reader.book.BookMeta
import com.pagefold.reader.book.Parse
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.Field
import com.pagefold.reader.kit.IconBtn
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.MicroLabel
import com.pagefold.reader.kit.Panel
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.kit.T
import com.pagefold.reader.kit.Masthead
import com.pagefold.reader.kit.Ornament
import com.pagefold.reader.kit.SealMark
import com.pagefold.reader.kit.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@Composable
fun LibraryScreen(
    s: Settings,
    app: App,
    open: (BookMeta) -> Unit,
    grid: LazyGridState,
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(app.shelf.all()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<BookMeta?>(null) }
    var renaming by remember { mutableStateOf<BookMeta?>(null) }
    var reassign by remember { mutableStateOf<BookMeta?>(null) }
    var draft by remember { mutableStateOf("") }
    var newGroup by remember { mutableStateOf("") }
    var shelfSel by remember { mutableStateOf("") }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "正在导入…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { importBook(app, uri) }
            busy = null
            if (r.first) {
                books = app.shelf.all()
                err = null
            } else {
                err = r.second ?: "导入失败"
            }
        }
    }

    // 换封面：选一张图，拷进 files/covers，原图可以删
    val coverPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = renamingCover
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val p = withContext(Dispatchers.IO) { saveCover(app, target.id, uri) }
            if (p != null) books = app.shelf.setCover(target.id, p)
            else err = "这张图读不出来"
            renamingCover = null
        }
    }

    val groups = remember(books) { books.map { it.group }.filter { it.isNotBlank() }.distinct() }
    val shown = remember(books, shelfSel) {
        if (shelfSel.isBlank()) books.sortedByDescending { it.addedAt }
        else books.filter { it.group == shelfSel }.sortedByDescending { it.addedAt }
    }
    val keep = books.maxByOrNull { it.openedAt }

    val launchImport = { importer.launch(arrayOf("text/plain", "application/epub+zip", "*/*")) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Masthead(
                title = "专属书库",
                word = "希望你能一直热爱阅读、热爱生活。",
            ) {
                IconBtn(Icons.Default.Add, "导入", launchImport)
                Spacer(Modifier.width(12.dp))
                SealMark("书房", 42.dp)
            }
            Ornament(Modifier.padding(horizontal = Sz.gutter).padding(bottom = 2.dp))

            if (books.isEmpty()) {
                EmptyShelf(launchImport)
            } else {
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Sz.gutter, end = Sz.gutter, top = 4.dp, bottom = 34.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (keep != null && keep.openedAt > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueBar(keep, { open(keep) })
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfRow(
                            groups = groups,
                            sel = shelfSel,
                            total = books.size,
                            onPick = { shelfSel = it },
                        )
                    }
                    if (shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                T("这一架还空着。长按一本，挑「归到哪一架」。", size = 12, color = skin.inkFaint)
                            }
                        }
                    }
                    items(shown, key = { it.id }) { b ->
                        BookTile(
                            b = b,
                            reading = keep?.id == b.id && b.openedAt > 0,
                            onOpen = { open(b) },
                            onHold = { menu = b },
                        )
                    }
                }
            }
        }

        if (busy != null) {
            Box(Modifier.fillMaxSize().background(skin.scrim), contentAlignment = Alignment.Center) {
                Panel(lift = true) { Busy(busy ?: "") }
            }
        }

        // 长按：改名 / 换封面 / 归架 / 移出
        menu?.let { b ->
            Sheet(skin.scrim) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                    T(b.title, size = 14, weight = FontWeight.Medium, maxLines = 2)
                    Spacer(Modifier.height(12.dp))
                    SheetRow("改个名字") { draft = b.title; renaming = b; menu = null }
                    SheetRow("换一张封面") { renamingCover = b; menu = null; coverPick.launch(arrayOf("image/*")) }
                    SheetRow("归到哪一架") { reassign = b; menu = null }
                    SheetRow("删除", danger = true) { confirmDel = b.id; menu = null }
                    Spacer(Modifier.height(10.dp))
                    // 点空白不再关它，就得留一处明说的出口。
                    Btn("收起", { menu = null }, BtnKind.GHOST, Modifier.fillMaxWidth())
                }
            }
        }

        // 改名
        renaming?.let { b ->
            val target = b
            Dialog(skin.scrim) {
                T("书名", size = 13, weight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                // 书名常常很长（有的把「作者：某某」也带在里头）。
                // 单行框只会裁掉后半截，手划也划不到底 —— 这里给它两到四行，写完能整段看见。
                Field(
                    draft, { draft = it }, "书名",
                    singleLine = false,
                    maxLines = 4,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("算了", { renaming = null }, BtnKind.GHOST, Modifier.weight(1f))
                    Btn("存下", {
                        books = app.shelf.rename(target.id, draft)
                        renaming = null
                    }, BtnKind.PRIMARY, Modifier.weight(1f))
                }
            }
        }

        // 归架
        reassign?.let { b ->
            val target = b
            Sheet(skin.scrim) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                    T("归到哪一架", size = 13, weight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    T("现在：${target.group.ifBlank { "未归类" }}", size = 11, color = skin.inkFaint)
                    Spacer(Modifier.height(12.dp))
                    ShelfOption("不归类", target.group.isBlank()) {
                        books = app.shelf.setGroup(target.id, ""); reassign = null
                    }
                    groups.forEach { g ->
                        ShelfOption(g, target.group == g) {
                            books = app.shelf.setGroup(target.id, g); reassign = null
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Field(newGroup, { newGroup = it }, "新起一架，写个名") }
                        Spacer(Modifier.width(8.dp))
                        Btn("新建", {
                            val g = newGroup.trim()
                            if (g.isNotEmpty()) {
                                books = app.shelf.setGroup(target.id, g)
                                newGroup = ""
                                reassign = null
                            }
                        }, BtnKind.GHOST)
                    }
                    Spacer(Modifier.height(12.dp))
                    Btn("算了", { reassign = null }, BtnKind.GHOST, Modifier.fillMaxWidth())
                }
            }
        }

        confirmDel?.let { id ->
            val b = books.firstOrNull { it.id == id }
            if (b != null) {
                Dialog(skin.scrim) {
                    T("删掉《${b.title}》？", size = 16, weight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    T("正文文件一起删掉，听到哪儿也一起没。", size = 12, color = skin.inkDim)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("算了", { confirmDel = null }, BtnKind.GHOST, Modifier.weight(1f))
                        Btn("删除", {
                            books = app.shelf.remove(id)
                            confirmDel = null
                        }, BtnKind.DANGER, Modifier.weight(1f))
                    }
                }
            }
        }

        err?.let {
            Box(Modifier.padding(Sz.gutter).align(Alignment.BottomCenter)) {
                Panel { T(it, size = 12, color = skin.bad) }
            }
        }
    }
}

/** 正在换封面的那本。放外面是因为选图是个异步回调，拿不到当时的局部状态。 */
private var renamingCover: BookMeta? = null

@Composable
private fun Busy(t: String) {
    val s = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp, color = s.flame
        )
        Spacer(Modifier.width(10.dp))
        T(t, size = 13, color = s.inkDim)
    }
}

// ───────────────────────── 空书架 ─────────────────────────

@Composable
private fun EmptyShelf(onImport: () -> Unit) {
    val s = LocalSkin.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.height(74.dp)
            ) {
                listOf(46f, 66f, 52f, 72f, 40f).forEachIndexed { i, h ->
                    Box(
                        Modifier.width(9.dp).height(h.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(s.ink.copy(alpha = if (i % 2 == 0) 0.09f else 0.05f))
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            T("书架还空着", size = 22, weight = FontWeight.SemiBold, family = s.titleFont)
            Spacer(Modifier.height(30.dp))
            Btn("导入小说", onImport, BtnKind.GHOST, icon = Glyph.FileOpen)
        }
    }
}

// ───────────────────────── 接着听 ─────────────────────────

@Composable
private fun ContinueBar(b: BookMeta, onOpen: () -> Unit) {
    val s = LocalSkin.current
    Panel(lift = true, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(b, Modifier.width(52.dp).height(70.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicroLabel("在读", s.flame)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(3.dp).height(3.dp).clip(CircleShape).background(s.inkFaint))
                    Spacer(Modifier.width(6.dp))
                    MicroLabel("第 ${b.chapterIndex + 1} 章 · ${(b.progress * 100).toInt()}%")
                }
                Spacer(Modifier.height(6.dp))
                T(b.title, size = 15, weight = FontWeight.SemiBold, maxLines = 2, family = s.titleFont)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(s.flame),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, "接着读", tint = s.onFlame, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ───────────────────────── 书架分架 ─────────────────────────

@Composable
private fun ShelfRow(groups: List<String>, sel: String, total: Int, onPick: (String) -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ShelfTab("全部", sel.isBlank(), total) { onPick("") }
        groups.forEach { g ->
            ShelfTab(g, sel == g, 0) { onPick(g) }
        }
    }
}

@Composable
private fun ShelfTab(text: String, on: Boolean, count: Int, onClick: () -> Unit) {
    val s = LocalSkin.current
    Column(
        Modifier.tap { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, fontSize = 13.sp,
                color = if (on) s.ink else s.inkFaint,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = 0.6.sp,
                fontFamily = s.titleFont,
            )
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text("$count", fontSize = 9.sp, color = s.inkFaint)
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.width(if (on) 16.dp else 0.dp).height(2.dp)
                .background(if (on) s.flame else Color.Transparent)
        )
    }
}

// ───────────────────────── 一格书 ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(b: BookMeta, reading: Boolean, onOpen: () -> Unit, onHold: () -> Unit) {
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onOpen,
            onLongClick = onHold,
        )
    ) {
        Box {
            Cover(b, Modifier.fillMaxWidth().aspectRatio(0.72f))
            if (reading) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(s.ground.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text("在读", fontSize = 9.sp, color = s.flame, letterSpacing = 0.5.sp)
                }
            }
            // 长按能出的那张菜单，从这里也能出。不是所有人都想得到长按。
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(s.ground.copy(alpha = 0.62f))
                    .tap { onHold() },
                contentAlignment = Alignment.Center
            ) {
                Text("⋯", fontSize = 13.sp, color = s.inkDim, lineHeight = 13.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            b.title, fontSize = 12.5.sp, lineHeight = 17.sp,
            color = s.ink, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontFamily = s.titleFont,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (b.openedAt > 0) "第 ${b.chapterIndex + 1} 章 · ${(b.progress * 100).toInt()}%"
            else "${b.chapters} 章",
            fontSize = 10.sp, color = s.inkFaint, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 封面。用户换过就用他那张；没换过就现画一块纸。 */
@Composable
private fun Cover(b: BookMeta, modifier: Modifier) {
    val s = LocalSkin.current

    if (b.cover.isNotBlank()) {
        /* 后台解、按路径缓存。书房滚动时不再在主线程解图。 */
        val bmp = com.pagefold.reader.kit.picture(b.cover)
        if (bmp != null) {
            Box(modifier.clip(RoundedCornerShape(3.dp)).background(s.panelSink)) {
                androidx.compose.foundation.Image(
                    bitmap = bmp, contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                ProgressLine(b)
            }
            return
        }
    }

    /* 封面是一张纸，不随四套主题变深变浅 —— 只分明暗两档。 */
    val paper = if (s.dark) Color(0xFF211E1A) else Color(0xFFF4EEE2)
    val ink = if (s.dark) Color(0xFFE6DECD) else Color(0xFF2A231B)
    val grey = if (s.dark) Color(0xFF8D8578) else Color(0xFF8B7D68)
    val seal = if (s.dark) Color(0xFFA8452F) else Color(0xFF9C3B2B)

    BoxWithConstraints(modifier.clip(RoundedCornerShape(3.dp)).background(paper)) {
        val w = maxWidth.value
        val h = maxHeight.value
        /* 92 是「接着听」那条的小格。小于它就不摆朱点和作者。 */
        val mini = w < 92f

        Box(
            Modifier.fillMaxSize().padding(if (mini) 3.dp else (w * 0.06f).dp)
                .border(0.7.dp, ink.copy(alpha = 0.13f))
        )
        if (!mini && w > 0f && h > 0f) {
            Box(
                Modifier.padding(start = (w * 0.145f).dp, top = (h * 0.075f).dp)
                    .size((w * 0.036f).coerceIn(3.5f, 9f).dp)
                    .background(seal)
            )
        }

        if (w > 0f && h > 0f) {
            val raw = b.title.trim()
            val cut = raw.indexOf("作者")
            val head = if (cut > 2) raw.substring(0, cut).trim().trimEnd('，', ',', ' ', '\u3000') else raw
            val who = if (cut > 2) raw.substring(cut + 2).trimStart('：', ':', ' ', '\u3000') else ""

            /* 「大秦：开局扶苏被贬，忽悠他造反」这种两段式书名：冒号前那一截是系列名，排成一行小字当眉标；后面那截才是这本的书名。 */
            val series = if (mini) null else seriesOf(head)
            val main = if (series != null) head.substring(series.length + 1) else head

            val boxW = w * 0.72f
            val cap = h * (if (mini) 0.70f else 0.46f)
            val maxRows = if (mini) 4 else 3
            val maxFs = if (mini) 13f else 22f
            val minFs = maxFs * 0.55f

            /* 几行怎么定：先试一行。这一行只要不算小（≥ 上限的七成），就用一行 ——「极品万岁爷」排成「极品万岁 / 爷」比排成一整行难看十倍。 */
            var fs = minOf(boxW / main.length.coerceAtLeast(1), maxFs)
            var lines: List<String> = listOf(main)
            if (fs < maxFs * 0.7f) {
                var got = false
                for (rows in 2..maxRows) {
                    val per = (main.length + rows - 1) / rows
                    val f = minOf(boxW / per, cap / (rows * 1.28f), maxFs)
                    if (f >= minFs) {
                        fs = f
                        lines = fitTitle(main, per)
                        got = true
                        break
                    }
                }
                if (!got) {
                    fs = minFs
                    lines = hardWrap(main, (boxW / minFs).toInt().coerceAtLeast(1))
                }
            }

            /* 眉标只是标记这是哪一套，比书名小一半，颜色退一档。 */
            val sFs = if (series != null) minOf(fs * 0.46f, boxW / series.length) else 0f

            /* 竖着排完的总高，超了就把字号按比例收回。 */
            val need = (if (sFs > 0f) sFs * 1.34f else 0f) + lines.size * fs * 1.28f
            if (need > cap) {
                val k = cap / need
                fs *= k
            }

            Column(
                Modifier.fillMaxSize().padding(
                    start = (w * 0.11f).dp, end = (w * 0.11f).dp,
                    top = (h * 0.05f).dp, bottom = (h * 0.05f).dp,
                ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (sFs > 0f) {
                    Text(
                        series.orEmpty(),
                        fontSize = sFs.sp,
                        lineHeight = (sFs * 1.18f).sp,
                        color = grey,
                        fontFamily = FontFamily.Serif,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height((sFs * 0.34f).dp))
                }
                lines.forEach { ln ->
                    Text(
                        ln,
                        fontSize = fs.sp,
                        lineHeight = (fs * 1.28f).sp,
                        color = ink,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                if (!mini && who.isNotBlank()) {
                    Spacer(Modifier.height((fs * 1.05f).dp))
                    Text(
                        who.take(12),
                        fontSize = (fs * 0.42f).coerceAtLeast(6f).sp,
                        color = grey,
                        fontFamily = FontFamily.Serif,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ProgressLine(b)
    }
}

/** 书名里冒号前面那一小截是系列名（「大秦：」「斗罗大陆：」），四字以内才算。 */
private fun seriesOf(t: String): String? {
    val i = t.indexOf('：')
    if (i in 1..4) return t.substring(0, i)
    val j = t.indexOf(':')
    if (j in 1..4) return t.substring(0, j)
    return null
}

/** 按宽度断行。优先在标点后断开，行首不留标点。 */
/** 断行，但保证每一行都不超过 [per] 个字。 */
private fun fitTitle(t: String, per: Int): List<String> {
    val ls = wrapTitle(t, per)
    return if (ls.any { it.length > per }) hardWrap(t, per) else ls
}

/** 按字数硬切，不看标点。 */
private fun hardWrap(t: String, per: Int): List<String> {
    val out = ArrayList<String>(4)
    var i = 0
    while (i < t.length) {
        val e = minOf(i + per, t.length)
        val piece = t.substring(i, e)
            .trimStart('，', '。', '、', '；', '：', ' ')
            .trimEnd('，', '、', '；', ' ')
        if (piece.isNotEmpty()) out.add(piece)
        i = e
    }
    return if (out.isEmpty()) listOf(t) else out
}

private fun wrapTitle(t: String, per: Int): List<String> {
    val out = ArrayList<String>(4)
    var i = 0
    val n = t.length
    while (i < n) {
        /* 往后多看两个字：标点常常刚好落在这一行的下一个字上。 */
        val hi = minOf(i + per + 2, n)
        var e = -1
        var k = hi
        while (k > i + 1) {
            if (t[k - 1] in "，。！？、：；·") { e = k; break }
            k--
        }
        if (e < 0) e = minOf(i + per, n)
        var piece = t.substring(i, e)
        piece = piece.trimStart('，', '。', '、', '；', '：', ' ')
            .trimEnd('，', '、', '；', ' ')
        if (piece.isNotEmpty()) out.add(piece)
        i = e
    }
    return if (out.isEmpty()) listOf(t) else out
}

@Composable
private fun ProgressLine(b: BookMeta) {
    val s = LocalSkin.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                .background(s.ink.copy(alpha = 0.08f))
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(b.progress.coerceIn(0f, 1f)).height(2.dp)
                .background(s.flame.copy(alpha = 0.75f))
        )
    }
}

// ───────────────────────── 弹层 ─────────────────────────

@Composable
private fun sheetSurface(content: @Composable () -> Unit) {
    val s = LocalSkin.current
    Box(
        Modifier.fillMaxWidth()
            .background(s.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .tap {}
            .padding(bottom = 16.dp)
    ) { content() }
}

@Composable
private fun Sheet(scrim: Color, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().imePadding().background(scrim)) {
        Box(Modifier.align(Alignment.BottomCenter)) { sheetSurface(content) }
    }
}

@Composable
private fun Dialog(scrim: Color, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        // 键盘弹出来的时候整块往上让 —— 不然输入框被手指和键盘一起埋掉。
        Modifier.fillMaxSize().imePadding().background(scrim),
        contentAlignment = Alignment.Center
    ) {
        // 遮罩不吃点击。点空白就关的话，打字打到一半手指一滑，改了半截的书名就没了。
        // 只能从「算了」或者「存下」出去。
        Box(Modifier.padding(26.dp)) { Panel(lift = true) { content() } }
    }
}

@Composable
private fun SheetRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .tap { onClick() }
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 14.sp, color = if (danger) s.bad else s.ink)
    }
}

@Composable
private fun ShelfOption(text: String, on: Boolean, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .tap { onClick() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 14.sp, color = if (on) s.flame else s.ink, modifier = Modifier.weight(1f))
        if (on) Icon(Icons.Default.Check, null, tint = s.flame, modifier = Modifier.size(16.dp))
    }
}

private fun fmtWan(chars: Long): String =
    if (chars >= 10000) "%.1f 万字".format(chars / 10000f) else "$chars 字"

// ───────────────────────── 导入 / 封面 ─────────────────────────

private fun displayName(app: App, uri: Uri): String {
    var name = "未命名"
    runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
    }
    return name
}

/** 存一张封面。不转码，原样存，解码交给显示那一层带降采样。 */
private fun saveCover(app: App, id: String, uri: Uri): String? = runCatching {
    val dir = app.shelf.coverDir(app)
    val name = "$id-${System.currentTimeMillis().toString(36)}.img"
    val dst = File(dir, name)
    app.contentResolver.openInputStream(uri)?.use { input ->
        dst.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
    } ?: return null
    if (dst.length() < 128) { dst.delete(); return null }
    // 先试着解一次，解不开就别留在库里，免得以后当封面时是一块空白
    val ok = BitmapFactory.decodeFile(dst.absolutePath) != null
    if (!ok) { dst.delete(); return null }
    // 这一本的旧封面（含老版本那种 `<书号>.img`）一并清掉，不留尸。
    runCatching {
        dir.listFiles { f ->
            f.isFile && (f.name.startsWith("$id-") || f.name == "$id.img")
        }?.forEach { if (it.name != name) it.delete() }
    }
    dst.absolutePath
}.getOrNull()

/** 返回 (成功?, 错误信息)。 */
private fun importBook(app: App, uri: Uri): Pair<Boolean, String?> {
    return try {
        val name = displayName(app, uri)
        val ext = name.substringAfterLast('.', "txt").lowercase()
        if (ext !in listOf("txt", "epub")) return false to "只支持 txt / epub，这个文件是 .$ext"
        val id = "b" + System.currentTimeMillis().toString(36)
        val dst = File(app.shelf.dir(), "$id.$ext")
        app.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        } ?: return false to "读不到文件"
        if (dst.length() < 64) {
            dst.delete()
            return false to "文件是空的"
        }
        val parsed = Parse.load(dst)
        if (parsed.text.length < 100) {
            dst.delete()
            return false to "里头没有正文，可能是加密的，或者整本都是图"
        }
        val title = name.substringBeforeLast('.').take(60)
        val meta = BookMeta(
            id = id,
            title = title.ifBlank { "未命名" },
            path = dst.absolutePath,
            bytes = dst.length(),
            chars = Parse.countChars(parsed.text),
            chapters = parsed.chapters.size,
            addedAt = System.currentTimeMillis(),
            openedAt = 0L,
            tintSeed = abs(title.hashCode()),
            // 收下来的这一刻，把定音里那套版式抄一份给这本书。
            // 之后定音再改，这本书不跟着动 —— 它有自己的那五格了。
            tuneJson = com.pagefold.reader.field.Tune.snapshot(app.field.now),
        )
        app.shelf.add(meta)
        // 收进来就派下去数人，不用等用户点。数完是它自己写回这本书的配置。
        com.pagefold.reader.book.Counts.ensure(app, meta)
        true to null
    } catch (e: Exception) {
        false to (e.message ?: e.javaClass.simpleName)
    }
}
