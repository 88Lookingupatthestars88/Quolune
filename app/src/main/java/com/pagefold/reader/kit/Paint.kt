package com.pagefold.reader.kit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import java.io.File
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.random.Random

/** 噪点纹理：一张 96×96 的 ARGB 位图，用 BitmapShader 平铺。 */
private val grainCache = HashMap<Int, ShaderBrush>()

private fun grainBrush(strength: Float, seed: Int): ShaderBrush = grainCache.getOrPut(seed) {
    val n = 96
    val px = IntArray(n * n)
    val rnd = Random(seed)
    val peak = (strength * 255f).toInt().coerceIn(1, 255)
    for (i in px.indices) {
        val a = rnd.nextInt(peak + 1)
        px[i] = (a shl 24) or 0x00FFFFFF
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader = shader
    }
}

/** 背景：底色 + 3 个径向光斑 + 噪点 +（HUD 才有）扫描线。 */
@Composable
fun Ground(modifier: Modifier = Modifier, skin: Skin = LocalSkin.current) {
    Box(modifier.fillMaxSize().drawWithCache {
        val w = size.width
        val h = size.height
        val m = min(w, h)
        val brushA = Brush.radialGradient(
            colors = listOf(skin.spotA.copy(alpha = if (skin.dark) 0.55f else 0.85f), Color.Transparent),
            center = Offset(w * 0.16f, h * 0.10f), radius = m * 1.05f
        )
        val brushB = Brush.radialGradient(
            colors = listOf(skin.spotB.copy(alpha = if (skin.dark) 0.42f else 0.70f), Color.Transparent),
            center = Offset(w * 0.94f, h * 0.34f), radius = m * 0.95f
        )
        val brushC = Brush.radialGradient(
            colors = listOf(skin.spotC.copy(alpha = if (skin.dark) 0.34f else 0.55f), Color.Transparent),
            center = Offset(w * 0.66f, h * 0.92f), radius = m * 1.15f
        )
        val grain = if (skin.grain > 0f) grainBrush(skin.grain, 0x5EED) else null
        onDrawBehind {
            drawRect(skin.ground)
            drawRect(brushA)
            drawRect(brushB)
            drawRect(brushC)
            if (skin.scan) {
                val step = 4.dp.toPx()
                var y = 0f
                val c = if (skin.dark) Color.White.copy(alpha = 0.018f) else Color.Black.copy(alpha = 0.016f)
                while (y < h) {
                    drawRect(c, topLeft = Offset(0f, y), size = Size(w, 1f))
                    y += step
                }
            }
            if (grain != null) drawRect(grain)
        }
    })
}

/** 用户自选背景图之上的统一压暗层，保证文字可读。 */
@Composable
fun Scrim(modifier: Modifier = Modifier, skin: Skin = LocalSkin.current, strength: Float = 0.55f) {
    Box(
        modifier.fillMaxSize().drawWithCache {
            val b = Brush.verticalGradient(
                listOf(
                    skin.ground.copy(alpha = strength),
                    skin.ground.copy(alpha = (strength - 0.12f).coerceAtLeast(0f)),
                    skin.ground.copy(alpha = strength)
                )
            )
            onDrawBehind { drawRect(b) }
        }
    )
}

/** 一条 1dp 的渐变分隔线：两端淡出。 */
fun DrawScope.hairline(y: Float, skin: Skin, alpha: Float = 1f) {
    val b = Brush.horizontalGradient(
        listOf(Color.Transparent, skin.hair.copy(alpha = skin.hair.alpha * alpha), Color.Transparent)
    )
    drawRect(b, topLeft = Offset(0f, y), size = Size(size.width, 1f))
}

/** 面板底：按材质分派。玻璃 = 纯色半透明；磨砂 = 实色；HUD = 实色 + 描边；纸 = 实色。 */
fun DrawScope.panelBase(size: Size, skin: Skin, lift: Boolean = false, radiusPx: Float) {
    val fill = if (lift) skin.panelLift else skin.panel
    when (skin.mat) {
        Mat.GLASS -> {
            drawRoundRect(fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx))
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (skin.dark) 0.22f else 0.85f),
                        Color.White.copy(alpha = if (skin.dark) 0.04f else 0.30f)
                    )
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx),
                style = Stroke(width = 1f)
            )
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (skin.dark) 0.10f else 0.55f), Color.Transparent),
                    startY = 0f, endY = 34f
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx),
                style = Stroke(width = 2f)
            )
        }
        Mat.FROST -> {
            drawRoundRect(fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx))
            drawRoundRect(
                if (skin.dark) Color.Black.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.07f),
                topLeft = Offset(0f, 1f),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx),
                style = Stroke(width = 1f)
            )
        }
        Mat.HUD -> {
            drawRect(fill)
            drawRect(skin.hair, style = Stroke(width = 1f))
            // 左上 / 右下的角标
            val len = 14f
            drawLine(skin.flame, Offset(0f, 0f), Offset(len, 0f), 2f)
            drawLine(skin.flame, Offset(0f, 0f), Offset(0f, len), 2f)
            drawLine(skin.flame, Offset(size.width, size.height), Offset(size.width - len, size.height), 2f)
            drawLine(skin.flame, Offset(size.width, size.height), Offset(size.width, size.height - len), 2f)
        }
        Mat.PAPER -> {
            drawRoundRect(fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx))
            drawRoundRect(
                skin.hairSoft,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx),
                style = Stroke(width = 1f)
            )
        }
    }
}

/** HUD 主题的切角形状：右上 + 左下各切一刀。 */
fun DrawScope.cutPanel(size: Size, skin: Skin, fill: Color, cut: Float = 14f) {
    withTransform({}) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width - cut, 0f)
            lineTo(size.width, cut)
            lineTo(size.width, size.height)
            lineTo(cut, size.height)
            lineTo(0f, size.height - cut)
            close()
        }
        drawPath(path, fill)
        drawPath(path, skin.hair, style = Stroke(width = 1f))
    }
}

/** 音波条：一组竖条，高度随数值变化。用于音色卡与正在播放的指示。 */
@Composable
fun WaveBar(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Float = 3f,
    gap: Float = 3f,
) {
    Canvas(modifier) {
        val n = values.size
        if (n == 0) return@Canvas
        val totalW = n * barWidth + (n - 1) * gap
        var x = (size.width - totalW) / 2f
        val midY = size.height / 2f
        values.forEach { v ->
            val hh = (v.coerceIn(0.06f, 1f) * size.height / 2f)
            drawRoundRect(
                color,
                topLeft = Offset(x, midY - hh),
                size = Size(barWidth, hh * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
            x += barWidth + gap
        }
    }
}

/** 静态的波峰值数组，按 key 决定形状，避免每帧重算。 */
fun waveShape(seed: Int, n: Int = 11): List<Float> {
    val rnd = Random(seed)
    return List(n) { i ->
        val center = 1f - kotlin.math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f)
        (0.18f + center * 0.55f + rnd.nextFloat() * 0.27f).coerceIn(0.12f, 1f)
    }
}

/** 记住一个 ImageBitmap 版本的位图，供背景图使用。 */
@Composable
fun rememberBitmap(key: Any?, block: () -> Bitmap?): androidx.compose.ui.graphics.ImageBitmap? =
    remember(key) { block()?.asImageBitmap() }

/** 磁盘上的图片，按路径缓存 + 后台解码。 */
object Pics {

    private class Shot(val at: Long, val len: Long, val bmp: Bitmap)

    private val cache = object : LinkedHashMap<String, Shot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Shot>?): Boolean =
            size > 6
    }

    /** 内存里现成的那张。主线程可调，不碰磁盘。 */
    fun peek(path: String): Bitmap? = synchronized(cache) { cache[path]?.bmp }

    /** 读盘解码。**只能在 IO 线程调**。 */
    fun shot(path: String): Bitmap? {
        val f = File(path)
        if (!f.exists() || f.length() <= 0L) return null
        val at = f.lastModified()
        val len = f.length()
        synchronized(cache) {
            cache[path]?.let { if (it.at == at && it.len == len) return it.bmp }
        }
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        synchronized(cache) { cache[path] = Shot(at, len, bmp) }
        return bmp
    }

    fun forget(path: String) {
        synchronized(cache) { cache.remove(path) }
    }
}

/** 按路径取一张图。第一帧用内存里那张，没有再去后台解。 */
@Composable
fun picture(path: String, key: Any? = null): androidx.compose.ui.graphics.ImageBitmap? {
    val got = produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = Pics.peek(path)?.asImageBitmap(),
        path, key,
    ) {
        value = withContext(Dispatchers.IO) { Pics.shot(path)?.asImageBitmap() }
    }
    return got.value
}

// ───────────────────────── 底子 ─────────────────────────

/** 一张底图连着它的摆法：缩放、偏移、压暗。设置页与正文共用这一份。 */
@Composable
fun BgImage(spec: com.pagefold.reader.field.BgSpec, modifier: Modifier = Modifier) {
    /* 按路径取图，后台解码；第一帧用内存里那张，没有才去读盘。 */
    val gen = com.pagefold.reader.field.Bg.stamp()
    val got = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = com.pagefold.reader.field.Bg.peek(spec.path)?.asImageBitmap(),
        spec.path, gen,
    ) {
        value = withContext(Dispatchers.IO) {
            com.pagefold.reader.field.Bg.shot(spec.path)?.asImageBitmap()
        }
    }
    val bmp = got.value ?: return
    androidx.compose.foundation.Image(
        bmp, null,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = spec.zoom
                scaleY = spec.zoom
                translationX = spec.ox * size.width
                translationY = spec.oy * size.height
            },
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}

/** 一页的底。没挑图就跟主题走。 */
@Composable
fun BgLayer(page: String, settings: com.pagefold.reader.field.Settings, modifier: Modifier = Modifier) {
    val s = LocalSkin.current
    // 这一层每帧都会被问一遍，解析结果留住
    val v = remember(settings.bgJson, settings.shelfBg, page) { settings.bg(page) }
    Box(modifier.fillMaxSize()) {
        Ground(skin = s)
        val spec = remember(v) { com.pagefold.reader.field.BgSpec.decode(v) }
        if (spec != null) {
            BgImage(spec, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(s.ground.copy(alpha = spec.dim)))
        }
    }
}
