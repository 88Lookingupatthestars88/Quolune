package com.pagefold.reader.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** MiniMax 语音合成（同步口）。 */
object MiniMax {

    private val json = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 我们自己的调门词 → 这家的 emotion 枚举。认不出就不传（让它按文本自己判）。 */
    private fun emotionOf(style: String): String? = when (style.trim()) {
        "愤怒", "生气" -> "angry"
        "悲伤", "难过" -> "sad"
        "开心", "高兴" -> "happy"
        "害怕", "恐惧" -> "fearful"
        "惊讶" -> "surprised"
        "厌恶" -> "disgusted"
        "耳语", "悄悄话" -> "whisper"
        "平静" -> "calm"
        else -> null
    }

    suspend fun mp3(
        base: String,
        key: String,
        model: String,
        text: String,
        voiceId: String,
        speed: Float = 1f,
        style: String = "",
        pitch: Int = 0,
        vol: Float = 1f,
        bright: Int = 0,
        soft: Int = 0,
        clear: Int = 0,
        fx: String = "",
        boost: String = "",
        norm: String = "",
    ): ByteArray = withContext(Dispatchers.IO) {
        if (base.isBlank()) throw IOException("还没填接口地址")
        if (key.isBlank()) throw IOException("还没填钥匙")
        if (text.isBlank()) throw IOException("这一段是空的")

        val vId = voiceId.ifBlank { "audiobook_male_1" }
        val payload = buildString {
            append("{\"model\":").append(quote(model.ifBlank { "speech-2.8-hd" })).append(",")
            append("\"text\":").append(quote(text)).append(",")
            append("\"stream\":false,")
            append("\"voice_setting\":{")
            append("\"voice_id\":").append(quote(vId)).append(",")
            append("\"speed\":").append(speed.coerceIn(0.5f, 2f)).append(",")
            // vol 的取值范围是 (0,10]，1.0 是原音量。
            append("\"vol\":").append(vol.coerceIn(0.1f, 10f)).append(",")
            // pitch 是整数，-12…12，0 是原音色。界面上那一格是百分数，换算在调用方做。
            append("\"pitch\":").append(pitch.coerceIn(-12, 12))
            emotionOf(style)?.let { append(",\"emotion\":").append(quote(it)) }
            append("},")
            /* voice_modify 是另一层：改的不是高低快慢，是嗓子的质地。 */
            if (bright != 0 || soft != 0 || clear != 0 || fx.isNotBlank()) {
                append("\"voice_modify\":{")
                val parts = ArrayList<String>(4)
                if (bright != 0) parts.add("\"pitch\":" + bright.coerceIn(-100, 100))
                if (soft != 0) parts.add("\"intensity\":" + soft.coerceIn(-100, 100))
                if (clear != 0) parts.add("\"timbre\":" + clear.coerceIn(-100, 100))
                // 音效一次只能选一种，多选对面不认。
                if (fx.isNotBlank()) parts.add("\"sound_effects\":" + quote(fx))
                append(parts.joinToString(","))
                append("},")
            }
            append("\"audio_setting\":{\"sample_rate\":32000,\"bitrate\":128000,")
            append("\"format\":\"mp3\",\"channel\":1},")
            /* 语种增强：让它认得更准，小语种和方言才用得上。 */
            if (boost.isNotBlank()) append("\"language_boost\":").append(quote(boost)).append(",")
            if (norm.isNotBlank()) append("\"text_normalization_mode\":").append(quote(norm)).append(",")
            append("\"output_format\":\"hex\"}")
        }

        val req = Request.Builder()
            .url(endpoint(base))
            .header("Authorization", Auth.bearer(key))
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(json))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = "MiniMax $resp.code: ${body.take(200)}"
                if (resp.code == 429 || resp.code in 500..599) throw IOException(msg)
                throw IllegalStateException(msg)
            }
            val o = runCatching {
                com.google.gson.JsonParser.parseString(body).asJsonObject
            }.getOrNull() ?: throw IOException("MiniMax 回的不是 JSON：${body.take(160)}")

            // 200 也可能是业务错误
            val code = runCatching {
                o.getAsJsonObject("base_resp").get("status_code").asInt
            }.getOrDefault(0)
            if (code != 0) {
                val m = runCatching {
                    o.getAsJsonObject("base_resp").get("status_msg").asString
                }.getOrNull().orEmpty()
                throw IOException("MiniMax $code: $m")
            }

            val hex = runCatching {
                o.getAsJsonObject("data").get("audio").asString
            }.getOrNull().orEmpty()
            if (hex.length < 400) throw IOException("回了空声音（${hex.length} 个字符）：${body.take(160)}")
            unhex(hex)
        }
    }

    fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/t2a_v2") -> b
            // 用户可能把 GroupId 拼在地址后面，原样保留查询串
            b.contains('?') -> b.substringBefore('?') + "/v1/t2a_v2" + "?" + b.substringAfter('?')
            b.endsWith("/v1") -> "$b/t2a_v2"
            else -> "$b/v1/t2a_v2"
        }
    }

    private fun unhex(s: String): ByteArray {
        val n = s.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw IOException("MiniMax 返回的音频不是十六进制")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
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
