package com.pagefold.reader.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.Hint
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Masthead
import com.pagefold.reader.kit.Ornament
import com.pagefold.reader.kit.SealMark
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.voice.Audition
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Voice
import com.pagefold.reader.voice.Why
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 试听。
 *
 * 只有单句：挑一副嗓子、写一句话、点试听。
 * 整段预演和生成音色搬去别处了 —— 那两件跟「听一句」不是一个事。
 */
@Composable
fun PreviewScreen(
    s: Settings,
    app: App,
    list: androidx.compose.foundation.lazy.LazyListState,
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()

    var custom by remember { mutableStateOf(app.box.custom()) }

    /*
     * 池子里是「现在开着的嗓源底下，所有真能念的嗓子」：
     * 内置的 Edge 十二副、MiMo 八副；硅基流动、火山、MiniMax、百炼、OpenAI
     * 这几家写在嗓源上的音色名；再加自己造的那几副。
     * 关着的嗓源一副都不列 —— 列出来点了也念不响。
     */
    val pool = remember(custom, s.enginesJson) {
        Catalog.ours(s.engines, custom, emptyList())
    }

    /*
     * 一进这一页，手上就得先有一副嗓子。
     *
     * 以前是 pick 从空开始，靠 LaunchedEffect 在下一帧补上。那一帧里音色一排
     * 一副都没选中、下面「这一副怎么念」那块不存在、试听按钮是灰的；下一帧三样
     * 一起出现，音色那一排还要把选中的那副挪到最前 —— 整页重排一次，
     * 看上去就是「进来闪了一下，像重新加载」。
     *
     * 现在在组合里就把它定下来，第一帧画出来已经是选好的样子。
     */
    val want = remember(pool, s.defaultVoice) {
        pool.firstOrNull { it.name == s.defaultVoice } ?: pool.firstOrNull()
    }
    var pick by remember { mutableStateOf(want) }
    var text by remember {
        mutableStateOf("他说，这世上的事，从来不是讲道理就能讲通的。")
    }
    var style by remember { mutableStateOf(s.style) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(Audition.playingId != null) }

    /*
     * 这一页也能改这副嗓子怎么念。
     *
     * 挑嗓子和调嗓子从来是一件事：听一句不对，第一反应是「快一点」「压低些」，
     * 不是「换一副」。以前这里只能换人不能改，改要去音色页，改完还得回来再听。
     * 情绪那一排已经就是调门，所以这块只摆其余几根。
     */
    var rev by remember { mutableStateOf(0) }
    val probe = pick
    val proto = probe?.let { protoOf(it, s.engines) }.orEmpty()
    val model = probe?.let { modelOf(it, s.engines) }.orEmpty()
    val knobs = if (probe == null) emptyList() else com.pagefold.reader.field.Caps
        .of(proto, model, probe.kind)
        .filter { it != com.pagefold.reader.field.Knob.MOOD }
    val savedTune = remember(probe?.id, s.speed, s.pitch, rev) {
        if (probe != null && app.vtune.has(probe.id)) app.vtune.of(probe.id)
        else com.pagefold.reader.field.Vt(
            speed = s.speed.coerceIn(50, 200),
            pitch = s.pitch.coerceIn(50, 200),
            volume = 100,
            mood = "",
        )
    }
    var draft by remember(probe?.id, rev) { mutableStateOf(savedTune) }
    val tuneDirty = draft != savedTune
    // 在这一页开关嗓源，手上这副可能就没了。只补这一种情况，第一帧不走这里。
    LaunchedEffect(pool) {
        val cur = pick
        if (cur == null || pool.none { it.id == cur.id }) pick = want
    }

    fun stop() {
        Audition.stop()
        playing = false
    }

    // 试听不随页面销毁 —— 切到别的页照样念完

    fun say() {
        val v = pick ?: return
        if (text.isBlank()) return
        scope.launch {
            stop()
            busy = true
            err = null
            val clip = withContext(Dispatchers.IO) {
                runCatching {
                    app.synth.make(
                        s.copy(style = style.trim().ifBlank { s.style }), v, text,
                        com.pagefold.reader.book.Cue.of(text, null, null, style, !s.cue),
                        one = if (knobs.isEmpty()) null else draft,
                    )
                }
                    .getOrElse { err = Why.say(it, ""); null }
            }
            busy = false
            if (clip == null) return@launch
            val ok = Audition.play(v.id, clip.file.absolutePath) { playing = false }
            playing = ok
            if (!ok) err = "这个引擎不能使用"
        }
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 26.dp)) {

        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Masthead(title = "选择你喜欢的声音", word = "听着舒服，就好。") {
                    SealMark("试听", 42.dp)
                }
                Ornament(Modifier.padding(horizontal = Sz.gutter).padding(top = 2.dp))
            }
        }

        // 音色
        item { BlockLabel("音色") }
        item {
            if (pool.isEmpty()) {
                Box(Modifier.padding(horizontal = Sz.gutter)) {
                    Hint("还没有可用的音色。到「定音」里打开一副嗓音，这里才有可选的。")
                }
            } else {
                // 点了哪一副，哪一副就排到最前 —— 不必再横着找回来。
                // 这个顺序得在 LazyRow 外面算：它的 content 是 LazyListScope，
                // 里面不能调 remember。
                val ordered = run {
                    val head = pool.filter { it.id == pick?.id }
                    head + pool.filterNot { it.id == pick?.id }
                }
                // 顺序变了还不够：这一排自己的滚动位置留在原处，
                // 挑中的那副可能就停在左边看不见的地方。把这一排拉回开头。
                val row = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(pick?.id) { row.animateScrollToItem(0) }

                LazyRow(
                    state = row,
                    contentPadding = PaddingValues(horizontal = Sz.gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ordered, key = { it.id }) { v ->
                        val on = v.id == pick?.id
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) skin.flame.copy(alpha = 0.10f) else skin.surface)
                                .border(
                                    1.dp,
                                    if (on) skin.flame else skin.hairSoft,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { pick = v }
                                .padding(horizontal = 13.dp, vertical = 9.dp)
                        ) {
                            Text(
                                v.name, fontSize = 13.sp,
                                color = if (on) skin.flame else skin.ink, maxLines = 1
                            )
                            if (v.desc.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    v.desc, fontSize = 9.sp,
                                    color = if (on) skin.flame.copy(alpha = 0.7f) else skin.inkFaint,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 文本
        item { BlockLabel("文本") }
        item {
            Box(
                Modifier.padding(horizontal = Sz.gutter).fillMaxWidth()
                    .clip(RoundedCornerShape(skin.rCard))
                    .background(skin.surface)
                    .border(1.dp, skin.hairSoft, RoundedCornerShape(skin.rCard))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        color = skin.ink, fontSize = 15.sp,
                        lineHeight = 24.sp, fontFamily = skin.titleFont
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp)
                )
            }
        }

        // 情绪
        run {
            item { BlockLabel("情绪") }
            item {
                Column(Modifier.padding(horizontal = Sz.gutter)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(STYLES) { st ->
                            val on = st == style
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (on) skin.flame else skin.surface)
                                    .border(
                                        1.dp,
                                        if (on) Color.Transparent else skin.hairSoft,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { style = st }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    st, fontSize = 12.sp,
                                    color = if (on) skin.onFlame else skin.inkDim
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(skin.surface)
                            .border(1.dp, skin.hairSoft, RoundedCornerShape(9.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        if (style.isBlank()) {
                            Text("自己写一句", fontSize = 12.sp, color = skin.inkFaint)
                        }
                        BasicTextField(
                            value = style,
                            onValueChange = { style = it },
                            singleLine = true,
                            textStyle = TextStyle(color = skin.ink, fontSize = 12.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (probe != null && knobs.isNotEmpty()) {
            item { BlockLabel("这一副怎么念") }
            item {
                Column(Modifier.padding(horizontal = Sz.gutter)) {
                    TuneRows(knobs, proto, model, draft) { draft = it }
                    if (tuneDirty) {
                        Spacer(Modifier.height(10.dp))
                        Btn(
                            "存下",
                            {
                                app.vtune.put(probe.id, draft)
                                rev++
                            },
                            BtnKind.GHOST,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = Sz.gutter, vertical = 18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn(
                        if (busy) "合成中" else "试听",
                        { if (!busy) say() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && pick != null
                    )
                    if (playing || busy) {
                        Btn("停止", { stop(); busy = false }, BtnKind.GHOST)
                    }
                }
                err?.let {
                    Spacer(Modifier.height(10.dp))
                    Hint(it)
                }
            }
        }
    }
}

@Composable
private fun BlockLabel(name: String) {
    val s = LocalSkin.current
    Text(
        name, fontSize = 11.sp, color = s.inkFaint,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = Sz.gutter, end = Sz.gutter, top = 18.dp, bottom = 8.dp)
    )
}
