package com.pagefold.reader.voice

import android.content.Context
import android.util.Base64
import java.io.File

/** 复刻嗓音用的那段录音。小米的 voiceclone 要的不是文件名，是一整串 data:audio/…;base64,…。 */
object CloneStore {

    /** 一段样本的上限。与选择文件那一关 [ui.CloneVoice] 的 10 MB 一致。 */
    const val LIMIT = 10L * 1024 * 1024

    private fun dir(ctx: Context): File = File(ctx.filesDir, "clones").apply { mkdirs() }

    /** 存一段样本。返回 voices.json 里该写的那个 param；失败返回 null。 */
    fun save(ctx: Context, id: String, bytes: ByteArray, mime: String): String? {
        if (bytes.isEmpty() || bytes.size > LIMIT) return null
        val f = File(dir(ctx), "$id.b64")
        return runCatching {
            val head = "data:" + (mime.ifBlank { "audio/wav" }) + ";base64,"
            f.writeText(head + Base64.encodeToString(bytes, Base64.NO_WRAP))
            "clone:$id"
        }.getOrNull()
    }

    /** 把 clone:<id> 还原成能直接发给对面的那串 data URI。 */
    fun uri(ctx: Context, param: String): String? {
        val id = param.trim().removePrefix("clone:").trim()
        if (id.isEmpty()) return null
        val f = File(dir(ctx), "$id.b64")
        if (!f.exists() || f.length() < 64) return null
        return runCatching { f.readText().trim().ifBlank { null } }.getOrNull()
    }

    /** 这副音色被删掉时，样本一起删。留着只是白占地方。 */
    fun drop(ctx: Context, param: String) {
        val id = param.trim().removePrefix("clone:").trim()
        if (id.isEmpty()) return
        runCatching { File(dir(ctx), "$id.b64").delete() }
    }

    /** 存在磁盘上的样本一共占多少。装订那一页要报数字。 */
    fun sizeOf(ctx: Context): Long =
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
}

/** 复刻嗓音只走这三种格式。选择器的 MIME 过滤、上传时的扩展名、存样本的 data URI 共用这一份。 */
object AudioFormats {

    /** 交给系统文件选择器的过滤条件。有的文件管理器给 m4a 报 audio/mp4，两个都列。 */
    val MIME = arrayOf(
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
        "audio/mpeg", "audio/mp3",
        "audio/mp4", "audio/x-m4a", "audio/m4a",
    )

    private val EXT = setOf("wav", "mp3", "m4a")

    /** 判这段音频属于哪一类。MIME 报成 application/octet-stream 时靠文件名兜底，都对不上返回 null。 */
    fun extOf(mime: String, name: String?): String? {
        val m = mime.lowercase()
        if (m.contains("wav")) return "wav"
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3"
        if (m.contains("m4a") || m.contains("mp4")) return "m4a"
        val e = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (e in EXT) e else null
    }
}
