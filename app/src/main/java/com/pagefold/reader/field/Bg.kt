package com.pagefold.reader.field

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 一张自己挑的底子，连同它的摆法。 */
data class BgSpec(
    val path: String,
    val dim: Float = 0.35f,
    val ox: Float = 0f,      // −0.5…0.5，相对于屏宽
    val oy: Float = 0f,
    val zoom: Float = 1f,    // 1…3
) {
    fun encode(): String = "image:$path|$dim|$ox|$oy|$zoom"

    companion object {
        fun decode(v: String): BgSpec? {
            if (!v.startsWith("image:")) return null
            val parts = v.removePrefix("image:").split('|')
            val path = parts.getOrNull(0).orEmpty()
            if (path.isBlank()) return null
            return BgSpec(
                path = path,
                dim = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 0.9f) ?: 0.35f,
                ox = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(-0.5f, 0.5f) ?: 0f,
                oy = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(-0.5f, 0.5f) ?: 0f,
                zoom = parts.getOrNull(4)?.toFloatOrNull()?.coerceIn(1f, 3f) ?: 1f,
            )
        }
    }
}

/** 收一张图，并且记住解出来的那张。 */
object Bg {

    private const val MAX_SIDE = 1600

    /* 换过一张图就加一。界面读它决定要不要重新取图 —— 路径不变，文件时间也会撞。 */
    private val tick = mutableIntStateOf(0)

    /** 这一页的图存在哪儿。文件名固定，换图就覆盖同一个文件。 */
    fun file(ctx: Context, page: String): File = File(ctx.filesDir, "bg_$page.jpg")

    fun stamp(): Int = tick.intValue

    private fun bump() {
        tick.intValue = tick.intValue + 1
    }

    /** 内存里现成的那张，主线程可调。图缓存在 [com.pagefold.reader.kit.Pics] 里，只存一份。 */
    fun peek(path: String): Bitmap? = com.pagefold.reader.kit.Pics.peek(path)

    /** 读盘解码，只能在 IO 线程调。 */
    fun shot(path: String): Bitmap? = com.pagefold.reader.kit.Pics.shot(path)

    private fun forget(path: String) {
        com.pagefold.reader.kit.Pics.forget(path)
    }

    suspend fun take(ctx: Context, page: String, uri: Uri): String? {
        val dst = file(ctx, page)
        val out = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = decodeScaled(ctx, uri) ?: return@runCatching null
                dst.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 86, o) }
                bmp.recycle()
                /* 写坏了不指过去：半截文件当底子，这一页会一直空白。 */
                if (dst.length() < 1024L) {
                    runCatching { dst.delete() }
                    return@runCatching null
                }
                BgSpec(dst.absolutePath).encode()
            }.getOrNull()
        }
        if (out != null) {
            forget(dst.absolutePath)
            bump()
        }
        return out
    }

    private fun decodeScaled(ctx: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (w / sample > MAX_SIDE * 2 || h / sample > MAX_SIDE * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val longest = maxOf(raw.width, raw.height)
        if (longest <= MAX_SIDE) return raw
        val scale = MAX_SIDE.toFloat() / longest
        val out = Bitmap.createScaledBitmap(
            raw, (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1), true
        )
        if (out !== raw) raw.recycle()
        return out
    }

    fun drop(ctx: Context, page: String) {
        val f = file(ctx, page)
        runCatching { f.delete() }
        forget(f.absolutePath)
        bump()
    }

    fun sizeOf(ctx: Context): Pair<Long, Int> {
        val files = files(ctx) ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
    }

    fun clearAll(ctx: Context): Int {
        val files = files(ctx) ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        bump()
        return n
    }

    private fun files(ctx: Context): Array<File>? =
        ctx.filesDir.listFiles { f -> f.isFile && (f.name.startsWith("bg_") || f.name == "shelf_bg.jpg") }
}
