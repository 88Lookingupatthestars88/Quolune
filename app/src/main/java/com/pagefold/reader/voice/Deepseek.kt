package com.pagefold.reader.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** DeepSeek 语音合成：POST {base}/v1/tools/tts，回来的直接是 wav。 */
object Deepseek {

    private val json = "application/json".toMediaType()

    /* 合成是边念边回，一条三十秒的音频要等一会儿才收完。 */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 单次上限一万个计费字符，汉字算两个。按一千八切，一段念完再要下一段。 */
    private const val MAX_CHARS = 1800

    suspend fun wav(base: String, key: String, text: String, voice: String): ByteArray =
        withContext(Dispatchers.IO) {
            if (base.isBlank()) throw IOException("还没填接口地址")
            if (key.isBlank()) throw IOException("还没填钥匙")
            if (text.isBlank()) throw IOException("这一段是空的")

            val pieces = Wav.split(text, MAX_CHARS)
            if (pieces.size <= 1) return@withContext one(base, key, text, voice)
            val parts = ArrayList<ByteArray>(pieces.size)
            // 一段一段来：这家按在途请求数限流，同时开几路只会等更久。
            pieces.forEach { parts.add(one(base, key, it, voice)) }
            Wav.join(parts)
        }

    private fun one(base: String, key: String, text: String, voice: String): ByteArray {
        val t = if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text
        val payload = buildString {
            append('{')
            append("\"text\":").append(quote(t))
            append(",\"voice_id\":").append(quote(voice.ifBlank { "stella" }))
            append(",\"format\":\"wav\"")
            append('}')
        }

        val req = Request.Builder()
            .url(endpoint(base))
            .header("Authorization", Auth.bearer(key))
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(json))
            .build()

        return http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val say = String(bytes, Charsets.UTF_8).take(200)
                val msg = "DeepSeek ${resp.code}: $say"
                // 429 是同时开着的请求太多，503 / 504 是它在忙 —— 这三种过一会儿再来。
                if (resp.code == 429 || resp.code in 500..599) throw IOException(msg)
                throw IllegalStateException(msg)
            }
            /* 念到一半断掉也是 200。字节太少就当没念出来，别当成品存下。 */
            if (bytes.size < 512) {
                throw IOException(
                    "回了空声音（${bytes.size} 字节）：${String(bytes, Charsets.UTF_8).take(160)}"
                )
            }
            bytes
        }
    }

    fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/v1/tools/tts") -> b
            b.endsWith("/v1") -> "$b/tools/tts"
            else -> "$b/v1/tools/tts"
        }
    }

    /** 填完钥匙验一次：这个音色念不念得出来。 */
    suspend fun probe(base: String, key: String, voice: String): String? {
        val why = withContext(Dispatchers.IO) {
            try {
                val b = wav(base, key, "嗯。", voice)
                if (b.size < 2048) "回来了，但没有声音" else null
            } catch (e: Throwable) {
                Speak.short(e.message)
            }
        }
        Speak.probeKeep("deepseek", base, key, "", why ?: Speak.OK)
        return why
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
