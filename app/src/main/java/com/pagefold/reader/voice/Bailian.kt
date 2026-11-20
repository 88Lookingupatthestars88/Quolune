package com.pagefold.reader.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 阿里百炼 · 千问语音合成（qwen3-tts）。 */
object Bailian {

    private val json = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val MAX_CHARS = 580

    suspend fun mp3(
        base: String,
        key: String,
        model: String,
        text: String,
        voice: String,
        speed: Float = 1f,
        lang: String = "",
    ): ByteArray = withContext(Dispatchers.IO) {
        if (base.isBlank()) throw IOException("还没填接口地址")
        if (key.isBlank()) throw IOException("还没填钥匙")
        if (text.isBlank()) throw IOException("这一段是空的")

        /* 这家单次上限 600 字符，是硬上限，超了直接 400。 */
        val pieces = Wav.split(text, MAX_CHARS)
        if (pieces.size <= 1) return@withContext one(base, key, model, text, voice, lang)

        val parts = ArrayList<ByteArray>(pieces.size)
        pieces.forEachIndexed { i, piece ->
            // 一路一段，不去并发：这家的免费额度按字符算，同时开几路容易被限流，
            // 而且这一段本来就是要拼起来的，早一秒晚一秒没有区别。
            parts.add(one(base, key, model, piece, voice, lang))
        }
        return@withContext Wav.join(parts)
    }

    /** 一段：真的发一次请求。 */
    private fun one(
        base: String,
        key: String,
        model: String,
        text: String,
        voice: String,
        lang: String,
    ): ByteArray {
        val t = if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text
        val payload = buildString {
            append("{\"model\":").append(quote(model.ifBlank { "qwen3-tts-flash" })).append(",")
            append("\"input\":{")
            append("\"text\":").append(quote(t)).append(",")
            append("\"voice\":").append(quote(voice.ifBlank { "Cherry" })).append(",")
            append("\"language_type\":").append(quote(lang.ifBlank { "Chinese" }))
            // 官方 input 只认 text / voice / language_type。多塞一个语速字段，
            // 有的部署直接回 400 —— 所以念速不在这一层传，交给播放器。
            append("}}")
        }

        val req = Request.Builder()
            .url(endpoint(base))
            .header("Authorization", Auth.bearer(key))
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(json))
            .build()

        return http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = "百炼 $resp.code: ${body.take(200)}"
                if (resp.code == 429 || resp.code in 500..599) throw IOException(msg)
                throw IllegalStateException(msg)
            }
            val o = runCatching {
                com.google.gson.JsonParser.parseString(body).asJsonObject
            }.getOrNull() ?: throw IOException("百炼回的不是 JSON：${body.take(160)}")

            // 业务错误：{"code":"InvalidApiKey","message":"..."}
            runCatching { o.get("code").asString }.getOrNull()?.let { c ->
                if (c.isNotBlank() && c != "0") {
                    val m = runCatching { o.get("message").asString }.getOrNull().orEmpty()
                    throw IOException("百炼 $c: $m")
                }
            }

            val audio = runCatching { o.getAsJsonObject("output").getAsJsonObject("audio") }.getOrNull()
                ?: throw IOException("百炼没给音频：${body.take(200)}")

            val b64 = runCatching { audio.get("data").asString }.getOrNull().orEmpty()
            if (b64.length > 400) {
                return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
            val url = runCatching { audio.get("url").asString }.getOrNull().orEmpty()
            if (url.isBlank()) throw IOException("百炼没给音频地址：${body.take(200)}")
            download(url)
        }
    }

    /** 音频在另一个地址上，再取一次。这个地址是带签名的，不需要带钥匙。 */
    private fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful || bytes.size < 512) {
                throw IOException("百炼音频下载失败（${r.code}，${bytes.size} 字节）")
            }
            return bytes
        }
    }

    fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.contains("/multimodal-generation/generation") -> b
            b.endsWith("/api/v1") -> "$b/services/aigc/multimodal-generation/generation"
            else -> "$b/api/v1/services/aigc/multimodal-generation/generation"
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        s.forEach {
            when (it) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (it < ' ') sb.append(String.format("\\u%04x", it.code)) else sb.append(it)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
