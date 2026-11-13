package com.pagefold.reader.voice

import com.pagefold.reader.field.EngineCfg
import android.util.Base64
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit


/** 小米 MiMo 语音合成。返回 WAV 字节。 */
class MiMo(
    @Volatile var base: String = "https://api.xiaomimimo.com",
    @Volatile var key: String = "",
) {
    /** 落盘排查用的上下文，由 Synth 注入。 */
    @Volatile var appCtx: android.content.Context? = null

    /** 用户自定义的头。空就跟默认一样。 */
    @Volatile var extra: List<Pair<String, String>> = emptyList()

    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        const val MODEL_PRESET = "mimo-v2.5-tts"
        const val MODEL_DESIGN = "mimo-v2.5-tts-voicedesign"
        const val MODEL_CLONE = "mimo-v2.5-tts-voiceclone"

        /** 从旁白文字里嗅情绪，只在调用方没给 style 时用。 */
        private val CUE = listOf(
            "生气" to Regex("怒道|怒吼|吼道|咆哮|暴喝|厉声|愤怒|恼怒|气急"),
            "恐惧" to Regex("惊恐|恐惧|害怕|畏惧|颤声|颤抖|发抖"),
            "悲伤" to Regex("哭道|哭喊|抽泣|哽咽|悲伤|悲痛|伤心|泪流|啜泣"),
            "开心" to Regex("笑道|笑着|大笑|轻笑|开心|高兴|欣喜|兴奋|欢呼|喜悦"),
            "惊讶" to Regex("惊讶|震惊|惊呼|失声|难以置信|怎么可能"),
            "悄悄话" to Regex("低声|轻声|小声|耳语|喃喃|呢喃|悄声|压低声音"),
            "平静" to Regex("平静|淡淡地|淡然|从容|冷静|不紧不慢"),
        )

        fun sniffCue(text: String): String? = CUE.firstOrNull { it.second.containsMatchIn(text) }?.first

        /** 把「快一点、高一点、什么语气」拼成一整句请求。 */
        fun askPhrase(speed: Int, pitch: Int, mood: String): String {
            val parts = ArrayList<String>(3)
            when {
                speed <= 85 -> parts.add("比平时慢一半的语速")
                speed <= 95 -> parts.add("比平时慢一点的语速")
                speed >= 130 -> parts.add("比平时快得多的语速")
                speed >= 106 -> parts.add("比平时快一点的语速")
            }
            when {
                pitch <= 85 -> parts.add("明显更低的音调")
                pitch <= 95 -> parts.add("略低的音调")
                pitch >= 130 -> parts.add("明显更高的音调")
                pitch >= 106 -> parts.add("略高的音调")
            }
            val m = mood.trim()
            if (m.isNotEmpty() && m != "平静" && m != "无") parts.add("「$m」的语气")
            if (parts.isEmpty()) return ""
            return "请用" + parts.joinToString("、") + "来念下面这段文字，不要念出这句要求本身。"
        }

        /** 这一次真正发给对面的模型名。 */
        fun modelFor(voice: Voice, cfg: EngineCfg?): String = when (voice.kind) {
            "design" -> cfg?.designOr().orEmpty().ifBlank { MODEL_DESIGN }
            "clone" -> cfg?.cloneOr().orEmpty().ifBlank { MODEL_CLONE }
            else -> cfg?.modelOr().orEmpty().ifBlank { MODEL_PRESET }
        }
    }

    suspend fun wav(
        text: String,
        voice: Voice,
        instruction: String? = null,
        temperature: Float? = null,
        cfg: EngineCfg? = null,
        speed: Int = 100,
        pitch: Int = 100,
        mood: String = "",
    ): ByteArray = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IOException("还没填 API Key")

        /* 用哪个模型：三格各管一路，见 modelFor。 */
        val chosen = modelFor(voice, cfg)

        /* 三个模型要的东西不一样，而且差别就在 audio.voice 这一格上：voicedesign  不要 voice。 */
        val isDesign = chosen.contains("voicedesign", true)
        val isClone = chosen.contains("clone", true) && !isDesign

        /* 克隆音色的 voice 必须是整串 data URI，不能是名字。 */
        val sample: String? = when {
            !isClone -> null
            voice.param.trim().startsWith("data:") -> voice.param.trim()
            else -> {
                val ctx = appCtx ?: throw IOException("克隆模型要一段音频样本才能张嘴")
                CloneStore.uri(ctx, voice.param)
                    ?: throw IOException("克隆模型要一段音频样本才能张嘴")
            }
        }

        /* messages 只发一条 assistant，内容是正文。 */
        val tune = askPhrase(speed, pitch, mood)
        val said = when {
            /* 造出来的音色：描述必须发（声音就是它生成的），快慢和音调也拼进同一条。 */
            isDesign -> {
                val desc = voice.param.ifBlank { voice.desc }.trim().take(60)
                val extra = askPhrase(speed, pitch, mood)
                listOf(desc, extra).filter { it.isNotBlank() }.joinToString(" ")
            }
            // 克隆音色不发描述：它的音色就是那段样本，发描述只会多念一句。
            isClone -> ""
            else -> tune
        }
        val payload = buildString {
            append("{\"model\":").append(quote(chosen)).append(",\"messages\":[")
            if (said.isNotEmpty()) {
                append("{\"role\":\"user\",\"content\":").append(quote(said)).append("},")
            }
            append("{\"role\":\"assistant\",\"content\":").append(quote(text)).append("}],")
            append("\"audio\":{\"format\":\"wav\"")
            if (!isDesign) append(",\"voice\":").append(quote(sample ?: voice.param))
            append("}")
            if (temperature != null) append(",\"temperature\":").append(temperature)
            append("}")
        }
        note4check(chosen, voice, said, text, payload)

        val b = Request.Builder()
            .url(base.trimEnd('/') + "/v1/chat/completions")
            .header("api-key", Auth.head(key))
            .header("Content-Type", "application/json")
        extra.forEach { (k, v) ->
            val low = k.lowercase()
            // 这三个由程序自己管，用户填了也不覆盖
            if (low != "api-key" && low != "content-type" && low != "authorization") {
                runCatching { b.header(k, Auth.clean(v)) }
            }
        }
        val req = b.post(payload.toRequestBody(json)).build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = "MiMo ${resp.code}: ${body.take(180)}"
                if (resp.code == 429 || resp.code in 500..599) throw IOException(msg)
                throw IllegalStateException(msg)
            }
            val root = JsonParser.parseString(body).asJsonObject
            val b64 = root.getAsJsonArray("choices")?.firstOrNull()
                ?.asJsonObject?.getAsJsonObject("message")
                ?.getAsJsonObject("audio")?.get("data")?.asString
                ?: throw IOException("没有音频：$chosen")
            Base64.decode(b64, Base64.DEFAULT)
        }
    }

    /** 把这一次真正发出去的东西留在 files/last-tts.txt。 */
    private fun note4check(model: String, voice: Voice, said: String, text: String, payload: String) {
        runCatching {
            val ctx = appCtx ?: return
            val f = java.io.File(ctx.filesDir, "last-tts.txt")
            f.writeText(
                buildString {
                    append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date()))
                    append("\nmodel=").append(model)
                    append("\nvoice=").append(voice.param)
                    append("\nkind=").append(voice.kind)
                    append("\ninstruction=").append(said.ifEmpty { "(未发送)" })
                    append("\ntext=").append(text)
                    append("\npayload=").append(payload)
                }
            )
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
