package com.pagefold.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.field.Bg
import com.pagefold.reader.field.Pages
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.Empty
import com.pagefold.reader.kit.IconBtn
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.kit.tap
import com.pagefold.reader.voice.Audition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 家底里的四本账。 */
enum class StoreKind(val title: String, val note: String) {
    AUDIO(
        "念过的段落",
        "点一句就重放。勾上删掉，那一段从此要重新去要。"
    ),
    /* 试听留下的，单列一本账。音色页点「听一下」、复刻页点「听听像不像」，念的都是同一句「这一段，念给你听。 */
    TRIAL(
        "试听留下的",
        "点一下听个响造出来的，不是书里的字。删干净也不影响正文。"
    ),
    COVER(
        "换过的封面",
        "自己挑的那张。删掉，书就回到按书名生成的那块布面。"
    ),
    BOOK(
        "库里的书",
        "删掉一本，正文一并从库里拿走，房里那一格也跟着空出来。"
    ),
    VOICE(
        "自配的嗓音",
        "删掉一个，音色页里也就不再有它。"
    ),
    BG(
        "换过的底子",
        "删掉一张，那一页就回到主题自带的底色。"
    ),
}

/** 清单上的一件。 */
private data class Piece(
    val id: String,
    val title: String,
    val sub: String,
    val bytes: Long = 0L,
    val play: String? = null,
)

@Composable
fun StoreScreen(
    kind: StoreKind,
    s: Settings,
    app: App,
    list: LazyListState,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val skin = LocalSkin.current
    var rows by remember { mutableStateOf<List<Piece>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var gen by remember { mutableStateOf(0) }
    // 谁在响只有 Audition 知道。这里跟着它走，不另存一份。
    val playing = Audition.playingId
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    LaunchedEffect(kind, gen) {
        rows = withContext(Dispatchers.IO) { load(kind, app) }
        picked = picked.filter { id -> rows.any { it.id == id } }.toSet()
    }

    val total = rows.sumOf { it.bytes }
    val allOn = rows.isNotEmpty() && picked.size == rows.size

    fun wipe(ids: Set<String>) {
        if (ids.isEmpty()) return
        if (playing != null && ids.contains(playing)) Audition.stop()
        val jobs = ids.mapNotNull { id -> rows.firstOrNull { it.id == id } }
        picked = picked - ids
        scope.launch {
            /* 一件一件来，每件单独兜，失败不影响其余。 */
            var bad = 0
            var why = ""
            withContext(Dispatchers.IO) {
                jobs.forEach { p ->
                    val ok = runCatching {
                        when (kind) {
                            StoreKind.AUDIO, StoreKind.TRIAL -> app.synth.drop(p.id)
                            StoreKind.BOOK -> app.shelf.remove(p.id)
                            StoreKind.VOICE -> app.box.remove(p.id)
                            StoreKind.COVER -> {
                                app.shelf.find(p.id)?.cover?.let { path ->
                                    if (path.isNotBlank()) runCatching { File(path).delete() }
                                }
                                app.shelf.setCover(p.id, "")
                            }
                            StoreKind.BG -> {
                                Bg.drop(app, p.id)
                                app.field.edit { it.withBg(p.id, "auto") }
                            }
                        }
                    }
                    if (ok.isFailure) {
                        bad++
                        if (why.isEmpty()) why = ok.exceptionOrNull()?.message.orEmpty()
                    }
                }
            }
            gen++
            msg = when {
                bad == 0 -> "已删 ${jobs.size} 件"
                bad == jobs.size -> "一件都没删掉" + why.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                else -> "删了 ${jobs.size - bad} 件，${bad} 件没删掉" +
                    why.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            }
            onChanged()
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = Sz.gutter, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.Default.ArrowBack, "返回", onBack)
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        kind.title, fontSize = 20.sp, color = skin.ink,
                        fontWeight = FontWeight.SemiBold, fontFamily = skin.titleFont
                    )
                    Text(
                        "${rows.size} 件 · ${size(total)}",
                        fontSize = 11.sp, color = skin.inkDim
                    )
                }
            }

            Text(
                kind.note,
                Modifier.padding(horizontal = Sz.gutter, vertical = 8.dp),
                fontSize = 12.sp, color = skin.inkFaint, lineHeight = 19.sp
            )

            if (rows.isEmpty()) {
                Empty(
                    Icons.Default.Delete,
                    "这里空着",
                    "还没有东西。"
                )
            } else {
                // 一横排：全选 / 反选，右边是删除。放顶上，不随列表滚走。
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Sz.gutter, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (allOn) "全不选" else "全选",
                        fontSize = 13.sp, color = skin.flame,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .tap {
                                picked = if (allOn) emptySet() else rows.map { it.id }.toSet()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (picked.isNotEmpty()) {
                        Btn(
                            "删掉 ${picked.size} 件",
                            { wipe(picked) },
                            BtnKind.DANGER
                        )
                    }
                }

                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Sz.gutter, end = Sz.gutter, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.id }) { p ->
                        PieceRow(
                            p = p,
                            on = picked.contains(p.id),
                            playing = playing == p.id,
                            onPick = {
                                picked = if (picked.contains(p.id)) picked - p.id else picked + p.id
                            },
                            onPlay = {
                                if (playing == p.id) {
                                    Audition.stop()
                                } else {
                                    p.play?.let { path ->
                                        if (!Audition.play(p.id, path)) msg = "这段解不开"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        msg?.let { Toast(it) { msg = null } }
    }
}

@Composable
private fun PieceRow(
    p: Piece,
    on: Boolean,
    playing: Boolean,
    onPick: () -> Unit,
    onPlay: () -> Unit,
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(skin.rCard))
            .background(skin.surface)
            .border(1.dp, if (on) skin.flame.copy(alpha = 0.5f) else skin.hairSoft, RoundedCornerShape(skin.rCard))
            .tap { onPick() }
            .padding(start = 12.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 方框，勾上是实心。圆点太像单选框，这里要多选。
        Box(
            Modifier.size(19.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (on) skin.flame else Color.Transparent)
                .border(1.4.dp, if (on) Color.Transparent else skin.hair, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (on) {
                Canvas(Modifier.size(12.dp)) {
                    drawLine(
                        Color.White, Offset(size.width * 0.1f, size.height * 0.55f),
                        Offset(size.width * 0.42f, size.height * 0.85f),
                        1.9.dp.toPx(), cap = StrokeCap.Round
                    )
                    drawLine(
                        Color.White, Offset(size.width * 0.42f, size.height * 0.85f),
                        Offset(size.width * 0.92f, size.height * 0.16f),
                        1.9.dp.toPx(), cap = StrokeCap.Round
                    )
                }
            }
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(p.title, fontSize = 13.sp, color = skin.ink, maxLines = 2, lineHeight = 18.sp)
            Spacer(Modifier.height(3.dp))
            Text(p.sub, fontSize = 10.sp, color = skin.inkFaint, maxLines = 1)
        }

        if (p.play != null) {
            Box(
                Modifier.size(36.dp)
                    .clip(CircleShape)
                    .background(if (playing) skin.flame.copy(alpha = 0.16f) else Color.Transparent)
                    .tap { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                if (playing) {
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(skin.flame))
                } else {
                    Icon(
                        Icons.Default.PlayArrow, null,
                        tint = skin.inkDim, modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

// ── 取数 ─────────────────────────────────────────────────────

private fun load(kind: StoreKind, app: App): List<Piece> = when (kind) {
    StoreKind.AUDIO, StoreKind.TRIAL -> {
        val want = kind == StoreKind.TRIAL
        app.synth.ledger().filter { it.trial == want }.map { e ->
            val bytes = app.synth.sizeOf(e.key, e.ext)
            val file = File(app.synth.cacheDir, e.key + "." + e.ext)
            Piece(
                id = e.key,
                title = e.text.ifBlank { "（空）" },
                sub = listOf(e.voice, stamp(e.at), size(bytes))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                bytes = bytes,
                play = if (file.exists()) file.absolutePath else null,
            )
        }
    }

    StoreKind.COVER -> app.shelf.all().filter { it.cover.isNotBlank() }.map { b ->
        val f = File(b.cover)
        Piece(
            id = b.id,
            title = b.title,
            sub = listOf(stamp(f.lastModified()), size(f.length()))
                .filter { it.isNotBlank() }.joinToString(" · "),
            bytes = if (f.exists()) f.length() else 0L,
        )
    }

    StoreKind.BOOK -> app.shelf.all().map { b ->
        Piece(
            id = b.id,
            title = b.title,
            sub = "${b.chapters} 章 · ${size(b.bytes)}",
            bytes = b.bytes,
        )
    }

    StoreKind.VOICE -> {
        // 自己配的嗓子全存在一个 voices.json 里，没有一条一个文件，
        // 所以「每副多大」本来是不存在的数。按各自那截 JSON 的长短把整个文件摊开，
        // 加起来正好等于文件大小 —— 比一律写 0 B 强。
        val list = app.box.custom()
        val f = File(app.filesDir, "voices.json")
        val total = if (f.exists()) f.length() else 0L
        val gson = com.google.gson.Gson()
        val lens = list.map { gson.toJson(it).toByteArray().size.toLong() }
        val sum = lens.sum().coerceAtLeast(1L)
        list.mapIndexed { i, v ->
            Piece(
                id = v.id,
                title = v.name,
                sub = listOf(
                    v.desc.ifBlank { v.param },
                    v.engine,
                    size(total * lens[i] / sum),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                bytes = total * lens[i] / sum,
            )
        }
    }

    StoreKind.BG -> Pages.ALL.mapNotNull { (key, label) ->
        val f = Bg.file(app, key)
        if (!f.exists()) return@mapNotNull null
        Piece(
            id = key,
            title = label,
            sub = "${stamp(f.lastModified())} · ${size(f.length())}",
            bytes = f.length(),
        )
    }
}

private fun stamp(at: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))

private fun size(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024L -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
