package com.pagefold.reader.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── 文字 ─────────────────────────

@Composable
fun T(
    text: String,
    size: Int = Sz.body,
    color: Color = LocalSkin.current.ink,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    family: androidx.compose.ui.text.font.FontFamily? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalSkin.current
    Text(
        text = text,
        modifier = modifier,
        fontSize = size.sp,
        color = color,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        fontFamily = family ?: s.titleFont,
        lineHeight = (size * 1.45f).sp,
    )
}

@Composable
fun MicroLabel(text: String, color: Color = LocalSkin.current.inkFaint) {
    Text(
        text = text,
        fontSize = Sz.micro.sp,
        color = color,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ───────────────────────── 面板 ─────────────────────────

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    lift: Boolean = false,
    pad: Dp = Sz.cardPad,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalSkin.current
    val r = if (lift) s.rBig else s.rCard
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    val scale by animateDpAsState(if (pressed) 1.dp else 0.dp, tween(120), label = "ps")
    var m = modifier.drawBehind {
        panelBase(size, s, lift, r.toPx())
    }
    if (onClick != null) m = m.clickable(interactionSource = inter, indication = null, onClick = onClick)
    Column(
        m.padding(pad).scale(1f - scale.value * 0.004f),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content
    )
}

/** 无内边距的面板，自己排内容。 */
@Composable
fun BarePanel(
    modifier: Modifier = Modifier,
    lift: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val s = LocalSkin.current
    val r = if (lift) s.rBig else s.rCard
    Box(modifier.drawBehind { panelBase(size, s, lift, r.toPx()) }, content = content)
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    val s = LocalSkin.current
    Box(
        modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Transparent, s.hairSoft, Color.Transparent)
                )
            )
        }
    )
}

// ───────────────────────── 页头 ─────────────────────────

/**
 * 页头。刻意不用「英文小标签 + 超大标题」那种模板：
 * 这里是左对齐中文标题 + 右侧动作，标题下压一条短线。
 */
@Composable
fun TopBar(
    title: String,
    sub: String? = null,
    verse: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val s = LocalSkin.current
    Column(Modifier.fillMaxWidth().padding(start = Sz.gutter, end = Sz.gutter, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = Sz.h1.sp,
                    color = s.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = s.titleFont,
                    letterSpacing = (-0.8).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (verse != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        verse,
                        fontSize = 12.sp, color = s.inkDim, lineHeight = 19.sp,
                        fontFamily = s.titleFont,
                    )
                }
                if (sub != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(sub, fontSize = Sz.label.sp, color = s.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(34.dp).height(2.dp).background(s.flame, RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 每一页最上面那两行：一个主标题，一个副标题。没有英文小标。
 *
 * 主次靠字号差分，不靠颜色、线、色块。三行小字叠在一起，眼睛不知道先看哪儿。
 *
 * 排布：
 *   一、上边留 22dp。贴太高压到状态栏，就没有一页纸的感觉了。
 *   二、标题 31sp，行高 40sp，字距收紧 0.7。中文大字号不加紧，字与字之间会散。
 *   三、副标题 13.5sp，行高 21sp，比标题暗一档。两者左边缘对齐同一条竖线。
 */
@Composable
fun Masthead(
    title: String,
    word: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxWidth().padding(start = Sz.gutter, end = Sz.gutter, top = 22.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 31.sp,
                lineHeight = 40.sp,
                color = s.ink,
                fontWeight = FontWeight.Bold,
                fontFamily = s.titleFont,
                letterSpacing = (-0.7).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (!word.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                word,
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = s.inkDim,
                fontFamily = s.titleFont,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分组小标题：左侧一根竖条 + 文字，不用全大写英文。 */
@Composable
fun SectionLabel(text: String, note: String? = null) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(start = Sz.gutter, end = Sz.gutter, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(s.flame, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = Sz.label.sp, color = s.ink, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        if (note != null) {
            Spacer(Modifier.weight(1f))
            Text(note, fontSize = Sz.micro.sp, color = s.inkFaint)
        }
    }
}

// ───────────────────────── 行 ─────────────────────────

@Composable
fun KitRow(
    label: String,
    desc: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val s = LocalSkin.current
    var m = Modifier.fillMaxWidth().heightIn(min = Sz.rowMin).padding(vertical = 10.dp)
    if (onClick != null) m = m.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = Sz.body.sp, color = s.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (desc != null) {
                Spacer(Modifier.height(2.dp))
                Text(desc, fontSize = Sz.micro.sp, color = s.inkFaint, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
fun KitSwitchRow(label: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val s = LocalSkin.current
    KitRow(label, desc) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = s.onFlame,
                checkedTrackColor = s.flame,
                uncheckedThumbColor = s.inkFaint,
                uncheckedTrackColor = s.panelSink,
                uncheckedBorderColor = s.hair,
            ),
            modifier = Modifier.scale(0.92f)
        )
    }
}

// ───────────────────────── 基础按钮 ─────────────────────────

@Composable
fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    active: Boolean = false,
    size: Dp = 42.dp,
) {
    val s = LocalSkin.current
    val bg by animateColorAsState(if (active) s.flameGhost else s.panel, tween(200), label = "ibg")
    Box(
        Modifier.size(size).clip(CircleShape).background(bg)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (active) s.flame else s.inkDim, modifier = Modifier.size(size * 0.46f))
    }
}

enum class BtnKind { PRIMARY, GHOST, DANGER }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    kind: BtnKind = BtnKind.PRIMARY,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val s = LocalSkin.current
    val bg = when (kind) {
        BtnKind.PRIMARY -> s.flame
        BtnKind.GHOST -> Color.Transparent
        BtnKind.DANGER -> s.bad.copy(alpha = 0.14f)
    }
    val fg = when (kind) {
        BtnKind.PRIMARY -> s.onFlame
        BtnKind.GHOST -> s.inkDim
        BtnKind.DANGER -> s.bad
    }
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    var m = modifier.heightIn(min = 46.dp).drawBehind {
        val r = s.rChip.toPx() * 2f
        if (kind == BtnKind.GHOST) {
            drawRoundRect(Color.Transparent)
            drawRoundRect(s.hair, cornerRadius = CornerRadius(r), style = Stroke(1f))
        } else {
            drawRoundRect(bg, cornerRadius = CornerRadius(r))
        }
    }.clickable(interactionSource = inter, indication = null, enabled = enabled) { onClick() }
    Row(
        m.padding(horizontal = 18.dp, vertical = 12.dp).scale(if (pressed) 0.97f else 1f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else fg.copy(alpha = 0.4f), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            fontSize = Sz.body.sp,
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ───────────────────────── 选项片 ─────────────────────────

@Composable
fun PickChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val s = LocalSkin.current
    val bg by animateColorAsState(if (selected) s.flame else s.panel, tween(180), label = "cb")
    val fg by animateColorAsState(if (selected) s.onFlame else s.inkDim, tween(180), label = "cf")
    Row(
        modifier.heightIn(min = 36.dp)
            .clip(RoundedCornerShape(s.rChip))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else s.hair, RoundedCornerShape(s.rChip))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = Sz.label.sp, color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** 分段选择器：一条底槽 + 一个滑动的实心块。 */
@Composable
fun SegBar(
    options: List<String>,
    index: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalSkin.current
    Row(
        modifier.fillMaxWidth().height(42.dp)
            .clip(RoundedCornerShape(s.rChip))
            .background(s.panelSink)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            val on = i == index
            Box(
                Modifier.weight(1f).height(36.dp)
                    .clip(RoundedCornerShape(s.rChip - 2.dp))
                    .background(if (on) s.flame else Color.Transparent)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = Sz.label.sp,
                    color = if (on) s.onFlame else s.inkDim,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ───────────────────────── 数字输入 ─────────────────────────

/** 只有数字的小方框，点开弹系统键盘。 */
@Composable
fun NumBox(
    value: Int,
    onChange: (Int) -> Unit,
    suffix: String? = null,
    width: Dp = 62.dp,
) {
    val s = LocalSkin.current
    var txt by remember(value) { mutableStateOf(value.toString()) }
    Box(
        Modifier.width(width).height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(s.panelSink)
            .border(1.dp, s.hairSoft, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = txt,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(6)
                    txt = digits
                    digits.toIntOrNull()?.let(onChange)
                },
                singleLine = true,
                textStyle = TextStyle(color = s.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = s.numFont),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(width - 14.dp)
            )
            if (suffix != null) Text(suffix, fontSize = 10.sp, color = s.inkFaint)
        }
    }
}

/** 步进器：− [数字] + */
@Composable
fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int = 0,
    max: Int = 9999,
    step: Int = 1,
    suffix: String? = null,
) {
    val s = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepKey(Glyph.Remove) { if (value - step >= min) onChange(value - step) }
        Spacer(Modifier.width(8.dp))
        NumBox(value, { onChange(it.coerceIn(min, max)) }, suffix)
        Spacer(Modifier.width(8.dp))
        StepKey(Icons.Default.Add) { if (value + step <= max) onChange(value + step) }
        Spacer(Modifier.width(2.dp))
        if (suffix == null) {
            // 无单位时右侧补一点呼吸位
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun StepKey(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val s = LocalSkin.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(s.panelSink)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = s.inkDim, modifier = Modifier.size(16.dp))
    }
}

/**
 * 旋钮：滑块 + 数字框二合一。
 * 上方一行「名称 ………… − 数字 +」，下方细轨。拖动和键入实时同步。
 */
@Composable
fun Dial(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    min: Float,
    max: Float,
    step: Float,
    unit: String? = null,
    desc: String? = null,
    decimals: Int = 0,
) {
    val s = LocalSkin.current
    val asInt = { v: Float -> v.roundToInt() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = Sz.body.sp, color = s.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (desc != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, fontSize = Sz.micro.sp, color = s.inkFaint, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepKey(Glyph.Remove) {
                    val nv = (value - step).coerceAtLeast(min)
                    onChange(nv)
                }
                Spacer(Modifier.width(6.dp))
                NumBox(asInt(value), { nv -> onChange(nv.toFloat().coerceIn(min, max)) }, unit, 54.dp)
                Spacer(Modifier.width(6.dp))
                StepKey(Icons.Default.Add) {
                    val nv = (value + step).coerceAtMost(max)
                    onChange(nv)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { onChange(it) },
            valueRange = min..max,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = s.flame,
                activeTrackColor = s.flame,
                inactiveTrackColor = s.panelSink,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().height(26.dp)
        )
    }
}

// ───────────────────────── 输入框 ─────────────────────────

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    /** 单行时也允许折行显示：书名叫「大秦：开局扶苏被贬，忽悠他造反 作者：……」这种，  */
    /** 单行框会被裁掉后半截，光标走到末尾时前面又看不见。要给书名这种长文本留几行。 */
    maxLines: Int = 1,
    minHeight: Dp = 46.dp,
) {
    val s = LocalSkin.current
    Box(
        modifier.fillMaxWidth().heightIn(min = minHeight)
            .clip(RoundedCornerShape(s.rChip))
            .background(s.panelSink)
            .border(1.dp, s.hairSoft, RoundedCornerShape(s.rChip))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder, fontSize = Sz.body.sp, color = s.inkFaint,
                maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            maxLines = maxLines,
            // 折行的时候要让光标停在可见的地方；不然打到末尾，前面那截就永远看不见了。
            textStyle = TextStyle(color = s.ink, fontSize = Sz.body.sp, lineHeight = 22.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ───────────────────────── 提示条 ─────────────────────────

enum class NoteKind { INFO, WARN, ERROR, OK }

@Composable
fun Note(text: String, kind: NoteKind = NoteKind.INFO, title: String? = null) {
    val s = LocalSkin.current
    val c = when (kind) {
        NoteKind.INFO -> s.flame
        NoteKind.WARN -> s.warn
        NoteKind.ERROR -> s.bad
        NoteKind.OK -> s.ok
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(s.rChip))
            .background(c.copy(alpha = 0.10f))
            .border(1.dp, c.copy(alpha = 0.28f), RoundedCornerShape(s.rChip))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 16.dp).background(c, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column {
            if (title != null) {
                Text(title, fontSize = Sz.label.sp, color = c, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
            }
            Text(text, fontSize = Sz.micro.sp, color = s.inkDim, lineHeight = 18.sp)
        }
    }
}

@Composable
fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, action: (@Composable () -> Unit)? = null) {
    val s = LocalSkin.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 46.dp, horizontal = Sz.gutter),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(s.flameGhost),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = s.flame, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = Sz.bodyL.sp, color = s.ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(desc, fontSize = Sz.label.sp, color = s.inkFaint, lineHeight = 20.sp)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** 一行满宽的点按区域，带右侧箭头。 */
@Composable
fun NavRow(title: String, sub: String? = null, onClick: () -> Unit) {
    val s = LocalSkin.current
    KitRow(title, sub, onClick) {
        Icon(
            androidx.compose.material.icons.Icons.Default.KeyboardArrowRight,
            null,
            tint = s.inkFaint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 完成标记，用于规则测试结果。 */
@Composable
fun Tally(ok: Boolean, text: String) {
    val s = LocalSkin.current
    val c = if (ok) s.ok else s.bad
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (ok) Icons.Default.Check else Glyph.Remove, null, tint = c, modifier = Modifier.size(11.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = Sz.micro.sp, color = c)
    }
}

/** 顶部安全区占位（状态栏）。用 windowInsets 会更准，但这里固定一个值更稳。 */
@Composable
fun StatusGap(extra: Dp = 0.dp) {
    Spacer(Modifier.height(28.dp + extra))
}

// ─────────────────────────────────────────────────────────────
// 尺：减号 · 轨道 · 加号
// ─────────────────────────────────────────────────────────────

/**
 * 一项数值。
 *
 * 手势交给 Material3 的 Slider：按下、拖动、方向锁、无障碍都是它管，
 * 竖向滑动不会被吞掉。手写 pointerInput 的话，闭包抓到的是首帧的 value，
 * 而且一按下就 consume，列表跟着卡住。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Ruler(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onChange: (Float) -> Unit,
    unit: String = "",
    hint: String? = null,
    low: String? = null,
    high: String? = null,
    format: (Float) -> String = { it.roundToInt().toString() },
) {
    val s = LocalSkin.current
    val span = (max - min).coerceAtLeast(0.0001f)
    val held = value.coerceIn(min, max)

    fun snap(v: Float): Float = (Math.round(v / step) * step).coerceIn(min, max)

    // 拖动时不写设置，只在手里记一个数；抬手才交出去一次。
    // 每帧写一次设置 = 每帧整页重组 + 序列化，那才是卡的来源。
    var live by remember { mutableFloatStateOf(held) }
    var grip by remember { mutableStateOf(false) }
    LaunchedEffect(held) { if (!grip) live = held }
    val shown = if (grip) live else held

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 14.sp, color = s.ink)
            Spacer(Modifier.weight(1f))
            Text(
                format(shown), fontSize = 20.sp, color = s.flame,
                fontWeight = FontWeight.Medium, fontFamily = s.numFont
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(unit, fontSize = 10.sp, color = s.inkFaint, modifier = Modifier.padding(bottom = 3.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Key("−") { onChange(snap(held - step)) }

            Box(Modifier.weight(1f).height(42.dp)) {
                val gripDp = 26.dp

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val travel = (maxWidth - gripDp).coerceAtLeast(1.dp)
                    val frac = ((shown - min) / span).coerceIn(0f, 1f)

                    Canvas(Modifier.fillMaxSize()) {
                        val cy = size.height / 2
                        val half = gripDp.toPx() / 2
                        val l = half
                        val r = size.width - half
                        drawLine(s.hair, Offset(l, cy), Offset(r, cy), 3.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(
                            s.flame, Offset(l, cy), Offset(l + (r - l) * frac, cy),
                            3.dp.toPx(), cap = StrokeCap.Round
                        )
                    }

                    // 整条都能按：横着推一下就接手，一路跟到抬手；竖着推就还给列表。
                    // 光按一下不挪 = 跳到那一格。这样既不误触，也不用非得揪住小圆点。
                    Box(
                        Modifier.fillMaxSize().pointerInput(min, max, step, span) {
                            val gripPx = gripDp.toPx()
                            val slop = viewConfiguration.touchSlop

                            fun valueAt(x: Float): Float {
                                val half = gripPx / 2
                                val travelPx = (size.width - gripPx).coerceAtLeast(1f)
                                val f = ((x - half) / travelPx).coerceIn(0f, 1f)
                                return snap(min + f * span)
                            }

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var mode = 0   // 0 还没定，1 横着拖，2 让路
                                var endX = down.position.x

                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    endX = ch.position.x
                                    val dx = ch.position.x - down.position.x
                                    val dy = ch.position.y - down.position.y

                                    if (mode == 0) {
                                        if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                            mode = 1
                                            grip = true
                                            live = valueAt(ch.position.x)
                                            ch.consume()
                                        } else if (abs(dy) > slop) {
                                            mode = 2
                                            break
                                        }
                                    } else {
                                        live = valueAt(ch.position.x)
                                        ch.consume()
                                    }
                                    if (!ch.pressed) break
                                }

                                if (mode == 2) {
                                    live = held
                                } else {
                                    if (mode == 0) {
                                        // 点数：按哪儿跳哪儿
                                        grip = true
                                        live = valueAt(endX)
                                    }
                                    grip = false
                                    onChange(live)
                                }
                            }
                        }
                    )

                    // 手柄只是画上去的，不接手势 —— 手势在上面的整条 Box 里。
                    Box(
                        Modifier
                            .offset(x = travel * frac - (gripDp - 24.dp) / 2)
                            .size(gripDp)
                            .align(Alignment.CenterStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(gripDp)) {
                            val rad = size.minDimension / 2
                            val c = Offset(size.width / 2, size.height / 2)
                            drawCircle(s.flame.copy(alpha = if (grip) 0.26f else 0.14f), rad, c)
                            drawCircle(s.surface, rad - 2.dp.toPx(), c)
                            drawLine(
                                s.flame,
                                Offset(c.x, size.height * 0.27f),
                                Offset(c.x, size.height * 0.73f),
                                (if (grip) 4.5f else 3.5f).dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            Key("＋") { onChange(snap(held + step)) }
        }

        if (low != null || high != null) {
            Row(Modifier.fillMaxWidth().padding(top = 1.dp, start = 40.dp, end = 40.dp)) {
                Text(low ?: "", fontSize = 9.sp, color = s.inkFaint)
                Spacer(Modifier.weight(1f))
                Text(high ?: "", fontSize = 9.sp, color = s.inkFaint)
            }
        }
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 11.sp, color = s.inkFaint, lineHeight = 16.sp)
        }
    }
}

/** 加号减号。按一下走一格。 */
@Composable
private fun Key(text: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Box(
        Modifier.size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(s.panelSink)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 16.sp, color = s.ink, fontFamily = s.numFont)
    }
}

/** 一句小字，紧贴在某个控件下面。 */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    val s = LocalSkin.current
    Text(
        text, modifier = modifier.fillMaxWidth(),
        fontSize = 11.sp, color = s.inkFaint, lineHeight = 17.sp
    )
}

// ───────────────────────── 纹样 ─────────────────────────

/**
 * 一道分隔。
 *
 * 四套材质各画各的：纸墨是两道线（书口压出来的粗细线），玻璃是一道两头淡掉的横线，
 * 磨砂是细线加一个方点，科幻是断续线加两端的小折角。
 *
 * 它唯一的用处是：长页里两节之间有一处「换气了」的标记。
 * 不画边框、不画圆角、不留阴影 —— 那些只是把内容再框一遍。
 */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    val s = LocalSkin.current
    Canvas(modifier.fillMaxWidth().height(7.dp)) {
        val w = size.width
        val mid = size.height / 2f
        when (s.mat) {
            Mat.PAPER -> {
                drawLine(s.hairSoft, Offset(0f, mid - 1.2f), Offset(w, mid - 1.2f), 1f)
                drawLine(s.hair.copy(alpha = 0.5f), Offset(0f, mid + 1.6f), Offset(w, mid + 1.6f), 1.6f)
            }
            Mat.GLASS -> {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Transparent, s.hair, Color.Transparent)
                    ),
                    topLeft = Offset(0f, mid), size = Size(w, 1f)
                )
            }
            Mat.FROST -> {
                drawLine(s.hairSoft, Offset(0f, mid), Offset(w, mid), 1f)
                drawRect(s.hair, topLeft = Offset(0f, mid - 2.5f), size = Size(7f, 5f))
            }
            Mat.HUD -> {
                var x = 0f
                while (x < w) {
                    drawLine(s.hair, Offset(x, mid), Offset((x + 6f).coerceAtMost(w), mid), 1f)
                    x += 12f
                }
                drawLine(s.flame, Offset(0f, mid - 4f), Offset(0f, mid + 4f), 1.6f)
                drawLine(s.flame, Offset(w, mid - 4f), Offset(w, mid + 4f), 1.6f)
            }
        }
    }
}

/**
 * 一串小菱格，两头淡、中间实。
 *
 * 放在页头下面、区块之间当收尾的纹样。不是分割线（分割线是 [Rule]），
 * 是一段「纸边压过的花纹」，所以它两端要化开，不能齐头齐尾。
 */
@Composable
fun Ornament(modifier: Modifier = Modifier, count: Int = 23) {
    val s = LocalSkin.current
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        if (count <= 0) return@Canvas
        val gap = size.width / count
        val r = gap * 0.19f
        val mid = size.height / 2f
        repeat(count) { i ->
            val c = (i + 0.5f) * gap
            // 两头各留三格淡下去，中间满色
            val t = when (i) {
                0, count - 1 -> 0.12f
                1, count - 2 -> 0.30f
                2, count - 3 -> 0.55f
                else -> 1f
            }
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c, mid - r)
                lineTo(c + r, mid)
                lineTo(c, mid + r)
                lineTo(c - r, mid)
                close()
            }
            drawPath(path, s.flame.copy(alpha = 0.36f * t))
        }
    }
}

/**
 * 一枚印。
 *
 * 一枚方印压在这一页的角上：朱底、字是纸色、边栏一道细线、四角略略不齐
 * （真印不会四边笔直，齐得像按钮）。整体歪两度，看着像刚按下去。
 *
 * 印上两字竖排，或者一字居中。朱色取当前材质的主色，字取主色上的前景色 ——
 * 深色主题里是暗字压亮朱，浅色里是亮字压暗朱，两种都成立。
 *
 * @param word 印上那一两个字。多的截掉：印面本来就窄。
 */
@Composable
fun SealMark(word: String, side: Dp = 46.dp, modifier: Modifier = Modifier) {
    val s = LocalSkin.current
    val chars = word.trim().take(2).map { it.toString() }.filter { it.isNotBlank() }
    if (chars.isEmpty()) return
    val fg = s.onFlame
    val one = chars.size == 1
    Box(
        modifier.size(side).graphicsLayer { rotationZ = -1.8f },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val j = w * 0.018f
            // 四角各挪一点点：齐头齐脑的方块看着像按钮，不像石头刻的。
            val body = androidx.compose.ui.graphics.Path().apply {
                moveTo(j, j * 0.6f)
                lineTo(w - j * 0.5f, j)
                lineTo(w, h - j * 0.7f)
                lineTo(w - j * 0.8f, h)
                lineTo(j * 0.7f, h - j * 0.4f)
                close()
            }
            drawPath(body, s.flame)
            // 边栏：印面四周留一圈，中间是字的天地。细，不抢。
            val inset = w * 0.098f
            drawRect(
                color = fg.copy(alpha = 0.55f),
                topLeft = Offset(inset, inset),
                size = Size(w - inset * 2f, h - inset * 2f),
                style = Stroke(width = 1f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            chars.forEach { c ->
                Text(
                    c,
                    fontSize = (side.value * if (one) 0.40f else 0.295f).sp,
                    lineHeight = (side.value * if (one) 0.44f else 0.325f).sp,
                    color = fg,
                    fontFamily = s.titleFont,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
