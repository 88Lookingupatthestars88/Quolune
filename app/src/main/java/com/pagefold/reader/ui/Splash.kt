package com.pagefold.reader.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.R
import com.pagefold.reader.kit.Mat
import com.pagefold.reader.kit.Skin
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 启动那一屏。底色、光、纹理都从当前皮肤算出来，四套材质各走一条；
 * 标与文字在所有皮肤上同一位置、同一字号。
 */
@Composable
fun Splash(skin: Skin, onDone: () -> Unit) {
    var alive by remember { mutableStateOf(true) }
    val fade by animateFloatAsState(
        targetValue = if (alive) 1f else 0f,
        animationSpec = tween(430),
        label = "splash",
    )
    LaunchedEffect(Unit) {
        delay(900)
        alive = false
        delay(440)
        onDone()
    }
    if (fade <= 0.002f) return

    Box(Modifier.fillMaxSize().alpha(fade)) {
        Sky(skin)
        Column(
            Modifier.align(Alignment.Center).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(
                    if (skin.dark) R.drawable.mark_cream else R.drawable.mark_ink
                ),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(152.dp),
            )
            Spacer(Modifier.height(26.dp))
            Text(
                "Quolune",
                fontSize = 31.sp,
                fontFamily = skin.titleFont,
                color = skin.ink,
                letterSpacing = 2.6.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "一款中国古风的听书应用",
                fontSize = 12.5.sp,
                color = skin.inkDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Rule(skin)
        }
    }
}

/**
 * 背景：地色 → 标后面一团同色系的光 → 四周压暗 → 材质自己的纹理。
 * 光用肤色偏暖的那一支，跟标的朱红接得上。
 */
@Composable
private fun Sky(skin: Skin) {
    val glow = if (skin.dark) skin.spotA else skin.spotA
    Box(
        Modifier.fillMaxSize().background(skin.ground).drawBehind {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.42f
            val r = min(w, h)

            // 上浅下沉：纸有厚薄，不铺死一块色
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        if (skin.dark) Color.White.copy(alpha = 0.045f) else Color.White.copy(alpha = 0.34f),
                        Color.Transparent,
                    ),
                    endY = h * 0.55f,
                )
            )
            // 标后面一圈大背光
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        skin.spotA.copy(alpha = if (skin.dark) 0.55f else 0.62f),
                        skin.spotA.copy(alpha = if (skin.dark) 0.16f else 0.20f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = r * 0.62f,
                )
            )
            // 背光里再收一圈：像一枚印，把标托在中间
            drawCircle(
                color = skin.flame.copy(alpha = if (skin.dark) 0.13f else 0.10f),
                radius = r * 0.285f,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.0032f),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        skin.flame.copy(alpha = if (skin.dark) 0.20f else 0.13f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy + h * 0.015f),
                    radius = r * 0.26f,
                )
            )
            // 四周压暗，视线收到中间
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        skin.ground.copy(alpha = if (skin.dark) 0.88f else 0.78f),
                    ),
                    center = Offset(cx, cy),
                    radius = r * 0.92f,
                )
            )
            when (skin.mat) {
                Mat.HUD -> {
                    val ink = skin.flame
                    val arm = 26.dp.toPx()
                    val pad = 30.dp.toPx()
                    val lw = 1.6.dp.toPx()
                    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(ink.copy(alpha = 0.55f), Offset(x, y), Offset(x + dx * arm, y), lw)
                        drawLine(ink.copy(alpha = 0.55f), Offset(x, y), Offset(x, y + dy * arm), lw)
                    }
                    corner(pad, pad, 1f, 1f)
                    corner(w - pad, pad, -1f, 1f)
                    corner(pad, h - pad, 1f, -1f)
                    corner(w - pad, h - pad, -1f, -1f)
                }

                Mat.PAPER -> {
                    // 纸的横纹：一密一疏两道，间距不整，免得看出规律
                    val step = 3.dp.toPx()
                    var y = 0f
                    var i = 0
                    while (y < h) {
                        val a = if (i % 3 == 0) 0.028f else 0.014f
                        drawRect(skin.ink.copy(alpha = a), Offset(0f, y), Size(w, 1f))
                        y += step
                        i++
                    }
                }

                Mat.FROST -> {
                    val step = 5.dp.toPx()
                    var y = 0f
                    while (y < h) {
                        drawRect(skin.ink.copy(alpha = 0.016f), Offset(0f, y), Size(w, 1f))
                        y += step
                    }
                }

                Mat.GLASS -> {
                    // 斜向的一道高光，从左上扫到右下
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (skin.dark) 0.09f else 0.30f),
                                Color.Transparent,
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(w * 0.85f, h * 0.62f),
                        )
                    )
                }
            }
        }
    )
}

/** 一条细线，中间断开，缺口里一颗菱。 */
@Composable
private fun Rule(skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp).height(1.dp).background(skin.flame.copy(alpha = 0.40f)))
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier.width(5.dp).height(5.dp).drawBehind {
                val k = Path()
                k.moveTo(size.width / 2, 0f)
                k.lineTo(size.width, size.height / 2)
                k.lineTo(size.width / 2, size.height)
                k.lineTo(0f, size.height / 2)
                k.close()
                drawPath(k, skin.flame)
            }
        )
        Spacer(Modifier.width(7.dp))
        Box(Modifier.width(44.dp).height(1.dp).background(skin.flame.copy(alpha = 0.40f)))
    }
}
