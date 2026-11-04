package com.pagefold.reader.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底栏五格。顺序：书在最左，关于在最右。
 *
 * 这一条是自己画的，不用任何 Material 图标。
 *
 * 图标库里的那几枚（MenuBook / PlayCircle / Tune）是通用件 —— 谁都在用，
 * 摆在一起就是「另一个应用」。这里五个字形全按「书」这一件事来：
 * 一册摊开的书、一排说话的杆、一只耳朵的圈、几道律管的横线、一个句点。
 * 笔画一律 1.5dp 圆头，和正文的字重是一套。
 *
 * 形状上也不做「浮起的胶囊」：整条贴着屏幕底边，顶上一道极细的分隔线，
 * 靠一道书签似的短线标出选中的那一格。栏是页的一部分，不是浮在页上的东西。
 */
val DOCK_ITEMS: List<String> = listOf("书房", "音色", "试听", "定音", "关于")

val DockHeight = 52.dp

@Composable
fun Dock(tab: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalSkin.current
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(s.surface)
    ) {
        // 顶边一道发丝线。用它分界，不用色块、不用阴影 —— 阴影在这套皮肤里很脏。
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.hairSoft))

        Row(
            Modifier.fillMaxWidth().height(DockHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DOCK_ITEMS.forEachIndexed { i, label ->
                val on = i == tab
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onPick(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 书签那道短线：只有选中那一格才长出来。
                    Box(
                        Modifier.width(14.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (on) s.flame else Color.Transparent)
                    )
                    Spacer(Modifier.height(6.dp))
                    Canvas(Modifier.size(18.dp)) {
                        glyph(i, if (on) s.flame else s.inkFaint)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        color = if (on) s.flame else s.inkFaint,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 五个字形，全手画。改一处粗细，五格一起跟着变。 */
private fun DrawScope.glyph(kind: Int, c: Color) {
    val w = size.width
    val h = size.height
    val sw = 1.5.dp.toPx()
    val round = Stroke(width = sw, cap = StrokeCap.Round)

    when (kind) {
        // 书房：一册摊开的书。中间那道是书脊，两边是摊开的页，底边收成一个 V。
        0 -> {
            drawLine(c, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.50f, h * 0.86f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.12f, h * 0.22f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.12f, h * 0.22f), Offset(w * 0.12f, h * 0.80f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.88f, h * 0.22f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.88f, h * 0.22f), Offset(w * 0.88f, h * 0.80f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.12f, h * 0.80f), Offset(w * 0.50f, h * 0.92f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.88f, h * 0.80f), Offset(w * 0.50f, h * 0.92f), sw, StrokeCap.Round)
        }

        // 音色：五根长短不一的杆。高低是音节，不是随机的。
        1 -> {
            val bars = floatArrayOf(0.34f, 0.72f, 1f, 0.54f, 0.28f)
            bars.forEachIndexed { i, v ->
                val x = w * (0.12f + i * 0.19f)
                val half = h * v * 0.40f
                drawLine(c, Offset(x, h * 0.5f - half), Offset(x, h * 0.5f + half), sw, StrokeCap.Round)
            }
        }

        // 试听：一只圈里的箭头。圈是开口的，不画满一圈 —— 满圈像播放器按钮。
        2 -> {
            drawCircle(
                c, radius = h * 0.42f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = round
            )
            val p = Path().apply {
                moveTo(w * 0.42f, h * 0.33f)
                lineTo(w * 0.67f, h * 0.5f)
                lineTo(w * 0.42f, h * 0.67f)
                close()
            }
            drawPath(p, c)
        }

        // 定音：三道律管。点子错开，看着是在调，不是一排开关。
        3 -> {
            val knobs = floatArrayOf(0.34f, 0.68f, 0.46f)
            knobs.forEachIndexed { i, v ->
                val y = h * (0.24f + i * 0.26f)
                drawLine(c, Offset(w * 0.10f, y), Offset(w * 0.90f, y), sw * 0.85f, StrokeCap.Round)
                drawCircle(c, radius = sw * 1.6f, center = Offset(w * v, y))
            }
        }

        // 关于：一个句点，下面一竖。就是「这里有一句话要交代」。
        else -> {
            drawCircle(
                c, radius = h * 0.40f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = round
            )
            drawCircle(c, radius = sw * 1.2f, center = Offset(w * 0.5f, h * 0.40f))
            drawLine(c, Offset(w * 0.5f, h * 0.54f), Offset(w * 0.5f, h * 0.70f), sw, StrokeCap.Round)
        }
    }
}
