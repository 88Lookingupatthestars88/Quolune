package com.pagefold.reader.field

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一张自己挑的底子，连同它的摆法。
 *
 * 摆法是用户自己挪出来的：缩放、左右上下偏移、明暗。
 * 所以这里存全，不靠猜。
 */
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

/**
 * 收一张图。
 *
 * 只做一件用户看不见的事：缩到最长边 1600 再存。
 * 4GB 的机器把原图直接读进内存会闪退，缩完就稳了。
 */
object Bg {

    private const val MAX_SIDE = 1600

    fun file(ctx: Context, page: String): File = File(ctx.filesDir, "bg_$page.jpg")

    suspend fun take(ctx: Context, page: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = decodeScaled(ctx, uri) ?: return@runCatching null
            val dst = file(ctx, page)
            dst.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 86, o) }
            bmp.recycle()
            BgSpec(dst.absolutePath).encode()
        }.getOrNull()
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
        runCatching { file(ctx, page).delete() }
    }

    fun sizeOf(ctx: Context): Pair<Long, Int> {
        val files = files(ctx) ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
    }

    fun clearAll(ctx: Context): Int {
        val files = files(ctx) ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }

    private fun files(ctx: Context): Array<File>? =
        ctx.filesDir.listFiles { f -> f.isFile && (f.name.startsWith("bg_") || f.name == "shelf_bg.jpg") }
}
