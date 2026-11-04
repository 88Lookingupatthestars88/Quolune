package com.pagefold.reader.voice

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * 复刻嗓音用的那段录音。
 *
 * 小米的 `voiceclone` 要的不是文件名，是一整串 `data:audio/…;base64,…`，直接塞在
 * `audio.voice` 里。十分钟的录音 base64 之后几十兆，塞进 voices.json 就会让每次读
 * 嗓音表卡一下。
 *
 * 所以样本单独放一个文件，voices.json 里只留 `clone:<id>`。
 * 发请求那一刻才读出来拼成 data URI，用完就扔。
 */
object CloneStore {

    /**
     * 一段样本的上限。
     *
     * 复刻要的是音色特征，不是内容：十几秒就够。给到 8 MB 是为了容得下手机录音的
     * 未压缩 wav（一分钟 48k/16bit 单声道约 5.5 MB），再大对面也不收。
     * 超过就当场说清楚，别上传半天再报一个看不懂的错。
     */
    const val LIMIT = 8L * 1024 * 1024

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

    /**
     * 把 `clone:<id>` 还原成能直接发给对面的那串 data URI。
     *
     * 读不出来就返回 null —— 调用方要把它当成「没有样本」，而不是拿空串去发请求。
     */
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
