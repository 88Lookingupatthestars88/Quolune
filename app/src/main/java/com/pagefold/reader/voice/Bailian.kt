package com.pagefold.reader.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 阿里百炼 · 千问语音合成（qwen3-tts）。
 *
 *   POST {base}/api/v1/services/aigc/multimodal-generation/generation
 *   Authorization: Bearer <key>
 *   { "model": "qwen3-tts-flash", "input": { "text": "...", "voice": "Cherry", "language_type": "Auto" } }
 *
 * 回来的是 JSON，音频**不在响应体里**，在 `output.audio.url`（有效期 24 小时），
 * 得再下载一次。所以这一支是「两次请求」。
 * 也兼容直接给 `output.audio.data`（base64）的部署。
 *
 * 单次文本上限 600 字符。我们按句切段，天然不撞；超了就在这一层截。
 */
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

        /*
         * 这家单次上限 600 字符，是硬上限，超了直接 400。
         *
         * 原来是把多出来的字截掉 —— 一段八百字的正文，后两百字当场没了，
         * 用户听着像「念到一半跳过去了」，而我们连个错都不报。
         * 现在按句切开，一段一段要，回来再拼成一条。
         */
        val pieces = split(text, MAX_CHARS)
        if (pieces.size <= 1) return@withContext one(base, key, model, text, voice, lang)

        val parts = ArrayList<ByteArray>(pieces.size)
        pieces.forEachIndexed { i, piece ->
            // 一路一段，不去并发：这家的免费额度按字符算，同时开几路容易被限流，
            // 而且这一段本来就是要拼起来的，早一秒晚一秒没有区别。
            parts.add(one(base, key, model, piece, voice, lang))
        }
        return@withContext join(parts)
    }

    /**
     * 按句切成每段不超过 [max] 字。
     *
     * 切点从后往前找句末标点，找不到再退到逗号，再找不到才硬切。
     * 早于本块三分之一的位置不切 —— 那会切出「好。」这种三个字的碎块。
     */
    private fun split(text: String, max: Int): List<String> {
        val s = text.trim()
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>(4)
        var from = 0
        while (from < s.length) {
            val room = s.length - from
            if (room <= max) { out.add(s.substring(from)); break }
            val end = from + max
            val floor = from + max / 3
            var cut = -1
            for (i in end downTo floor) {
                if (s[i - 1] in "。！？；…!?;") { cut = i; break }
            }
            if (cut < 0) {
                for (i in end downTo floor) {
                    if (s[i - 1] in "，、,：: ") { cut = i; break }
                }
            }
            if (cut < 0) cut = end
            out.add(s.substring(from, cut))
            from = cut
        }
        return out
    }

    /**
     * 几段音频拼成一条。
     *
     * 这家回来的是 wav（地址后缀 .wav）。wav 不能像 mp3 那样直接把字节摞起来 ——
     * 每一段都带一个自己的头，摞起来播放器只认第一段，后面全当垃圾。
     * 所以取第一段的格式，把所有 data 块接上，再重写一个头。
     *
     * 万一哪一段不是 wav（边关改过返回格式），退回直接摞 —— mp3 的帧流摞起来是能播的。
     */
    private fun join(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        if (parts.any { it.size < 44 || it[0] != 'R'.code.toByte() }) {
            val out = java.io.ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }
        val fmt = fmtChunk(parts[0]) ?: run {
            val out = java.io.ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }
        val body = java.io.ByteArrayOutputStream()
        parts.forEach { p -> dataChunk(p)?.let { body.write(it) } }
        val data = body.toByteArray()
        if (data.isEmpty()) return parts[0]

        // 44 = RIFF 头 12 + fmt 块 8+16 + data 头 8
        val total = 36 + fmt.size + 8 + data.size
        val head = java.io.ByteArrayOutputStream(fmt.size + 44)
        fun le32(v: Int) {
            head.write(v and 0xFF); head.write((v ushr 8) and 0xFF)
            head.write((v ushr 16) and 0xFF); head.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) { head.write(v and 0xFF); head.write((v ushr 8) and 0xFF) }
        head.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(total)
        head.write("WAVE".toByteArray(Charsets.US_ASCII))
        head.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(fmt.size); head.write(fmt)
        head.write("data".toByteArray(Charsets.US_ASCII)); le32(data.size)
        val out = java.io.ByteArrayOutputStream(head.size() + data.size)
        out.write(head.toByteArray()); out.write(data)
        return out.toByteArray()
    }

    private fun chunk(b: ByteArray, want: String): ByteArray? {
        var i = 12
        var guard = 0
        while (i + 8 <= b.size && guard++ < 64) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (size < 0 || i + 8 + size > b.size) return null
            if (id == want) return b.copyOfRange(i + 8, i + 8 + size)
            i += 8 + size + (size and 1)
        }
        return null
    }

    private fun fmtChunk(b: ByteArray): ByteArray? = chunk(b, "fmt ")

    private fun dataChunk(b: ByteArray): ByteArray? = chunk(b, "data")

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
            .header("Authorization", "Bearer $key")
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
