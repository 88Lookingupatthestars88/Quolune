package com.pagefold.reader.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 火山引擎 · 豆包语音合成。
 *
 * 它**不走** OpenAI 兼容口，所以必须单独一支。走的是 V3 单向合成：
 *
 *   POST https://openspeech.bytedance.com/api/v3/tts/unidirectional
 *   X-Api-Key: <控制台 API Key>
 *   X-Api-Resource-Id: seed-tts-2.0 | seed-icl-2.0
 *   X-Api-Request-Id: <每次一个新的 uuid>
 *
 *   { "user": {"uid": "..."},
 *     "req_params": { "text": "...", "speaker": "zh_female_vv_uranus_bigtts",
 *                     "audio_params": {"format":"mp3","sample_rate":24000,"speech_rate":0} } }
 *
 * 回来的不是音频，是**按行分开的 JSON**（NDJSON）：每行 `{code, data}`，
 * `code == 0` 的 `data` 是一段 base64 音频；`code == 20000000` 是收尾。
 * 所以不能 `response.body.bytes()` 一把读，那样拿到的是 JSON 文本。
 *
 * 关键一点：**正文之外的任何话都不发。** 豆包 2.0 支持用自然语言写情绪指令
 * （`additions.context_texts`），那正好是「念出文章外内容」的入口，这里一律不填。
 * 语速走 `speech_rate` 这个数字参数，不用自然语言描述。
 */
object Volc {

    private val json = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 念一段，回 MP3 字节。
     *
     * @param base    `https://openspeech.bytedance.com`
     * @param key     控制台 > API Key 管理 里那把
     * @param model   resource id：`seed-tts-2.0`（官方音色）或 `seed-icl-2.0`（复刻音色）
     * @param speaker 音色 id，如 `zh_female_vv_uranus_bigtts`
     * @param speed   0.5–2.0，1.0 是常速
     */
    suspend fun mp3(
        base: String,
        key: String,
        model: String,
        text: String,
        speaker: String,
        speed: Float = 1f,
        loudness: Float = 1f,
        pitch: Int = 0,
        mood: String = "",
        dialect: String = "",
        silenceMs: Int = 0,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (base.isBlank()) throw IOException("还没填接口地址")
        if (key.isBlank()) throw IOException("还没填钥匙")
        if (text.isBlank()) throw IOException("这一段是空的")

        val rid = model.ifBlank { "seed-tts-2.0" }
        val spk = speaker.ifBlank { "zh_female_vv_uranus_bigtts" }
        // speech_rate / loudness_rate 都是 -50…100 的整数百分比，不是倍率。
        val rate = ((speed.coerceIn(0.5f, 2f) - 1f) * 100).toInt().coerceIn(-50, 100)
        val loud = ((loudness.coerceIn(0.5f, 2f) - 1f) * 100).toInt().coerceIn(-50, 100)

        val payload = buildString {
            append("{\"user\":{\"uid\":\"").append("quolune").append("\"},")
            append("\"req_params\":{")
            append("\"text\":").append(quote(text)).append(",")
            append("\"speaker\":").append(quote(spk)).append(",")
            append("\"audio_params\":{\"format\":\"mp3\",\"sample_rate\":24000,")
            append("\"speech_rate\":").append(rate)
            if (loud != 0) append(",\"loudness_rate\":").append(loud)
            append("}")
            /*
             * post_process 是另一层：音调只有这儿有，-12…12。
             * 它跟 speech_rate 不在一个对象里，别塞进 audio_params。
             */
            if (pitch != 0) {
                append(",\"post_process\":{\"pitch\":").append(pitch.coerceIn(-12, 12)).append("}")
            }
            /*
             * 语音指令：这家唯一能说「用什么语气」的地方，而且不计费。
             * 只发一句完整的话 —— 几个词夹在正文前容易被当成正文念出来。
             */
            val m = mood.trim()
            if (m.isNotEmpty() && m != "平静" && m != "无") {
                append(",\"context_texts\":[\"请用「").append(escapeText(m)).append("」的语气念下面这段文字，不要念出这句要求本身。\"]")
            }
            /*
             * 方言：指定了才算数，而且音色本身得会说这个方言，否则没效果。
             * 值的写法是八个固定词（beijing / dongbei / …），不是中文。
             */
            if (dialect.isNotBlank()) {
                append(",\"explicit_dialect\":").append(quote(dialect.trim()))
            }
            // 段尾留白，0–30000 毫秒。0 就是不额外留。
            if (silenceMs > 0) {
                append(",\"silence_duration\":").append(silenceMs.coerceIn(0, 30000))
            }
            append("}}")
        }

        val req = Request.Builder()
            .url(endpoint(base))
            .header("X-Api-Key", key)
            .header("X-Api-Resource-Id", rid)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(json))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = "火山 $resp.code: ${body.take(200)}"
                if (resp.code == 429 || resp.code in 500..599) throw IOException(msg)
                throw IllegalStateException(msg)
            }
            // NDJSON：一行一段。有的部署会带 SSE 的 "data:" 前缀，一起切掉。
            val out = java.io.ByteArrayOutputStream()
            var err: String? = null
            body.lineSequence().forEach { raw ->
                val line = raw.trim().removePrefix("data:").trim()
                if (line.isEmpty() || line == "[DONE]") return@forEach
                val o = runCatching {
                    com.google.gson.JsonParser.parseString(line).asJsonObject
                }.getOrNull() ?: return@forEach
                val code = runCatching { o.get("code").asInt }.getOrDefault(-1)
                val data = runCatching { o.get("data")?.asString }.getOrNull()
                when {
                    code == 0 && !data.isNullOrEmpty() ->
                        runCatching { out.write(android.util.Base64.decode(data, android.util.Base64.DEFAULT)) }
                    code == 20000000 -> Unit          // 收尾
                    code > 0 -> err = "火山 $code: " + (runCatching { o.get("message").asString }.getOrNull() ?: "")
                }
            }
            val bytes = out.toByteArray()
            if (bytes.size < 512) {
                throw IOException(err ?: "回了空声音（${bytes.size} 字节）：${body.take(160)}")
            }
            bytes
        }
    }

    fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/unidirectional") -> b
            b.endsWith("/api/v3/tts") -> "$b/unidirectional"
            b.endsWith("/api/v3") -> "$b/tts/unidirectional"
            b.contains("openspeech.bytedance.com") -> "$b/api/v3/tts/unidirectional"
            else -> "$b/api/v3/tts/unidirectional"
        }
    }

    private fun escapeText(s: String): String {
        val sb = StringBuilder(s.length + 8)
        s.forEach {
            when (it) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> if (it < ' ') sb.append(String.format("\\u%04x", it.code)) else sb.append(it)
            }
        }
        return sb.toString()
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
