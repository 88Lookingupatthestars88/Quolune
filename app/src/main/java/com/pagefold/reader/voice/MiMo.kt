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

/**
 * 小米 MiMo 语音合成。返回 WAV 字节。
 *
 * 请求体结构由服务端规定：
 *   messages[0] = 情绪/音色指令（预设音色才吃这一项）
 *   messages[1] = 实际要读的文本
 *   audio.voice  = 预设音色名；设计/克隆模型不带这个字段
 */
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

        /**
         * 把「快一点、高一点、什么语气」拼成一整句请求。
         *
         * 这一家的 audio 对象里只有 format / optimize_text_preview / voice 三个字段，
         * 没有 speed、没有 pitch。官方示例就是这么写的：拿一条 user 消息用自然语言
         * 说清楚要什么（"Fast pace, rising pitch at the end."）。
         *
         * 写成一句完整的话，不写成几个词。理由：一个孤零零的「欢快」夹在正文前面，
         * 模型容易把它当成要念的第一句 —— 那就是「开头多出一句话」的老病根。
         * 有主语的句子，模型认得出这是在提要求，不是在念台词。
         *
         * 三样都没动就返回空串，一个字都不发。
         */
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

        /**
         * 这一次真正发给对面的模型名。
         *
         * 三格各管一路，谁也替不了谁：
         *
         *   朗读音色 → 嗓源上「朗读模型」那一格
         *   设计音色 → 「设计模型」那一格（它的 voice 是一段描述文字）
         *   克隆音色 → 「克隆模型」那一格（它的 voice 必须是一整串 data:audio/…;base64）
         *
         * 后两种不是偏好，是工作方式：模型和 voice 的格式必须成对。配错了对面不会报错，
         * 只会拿默认声音念 —— 所以这里只认各格自己填的，不互相顶替。
         *
         * 返回值同时是「拦截判据」和「请求里的 model」，两处必须用同一个，
         * 否则会出现「按 A 拦下来、实际却要发 B」的自相矛盾。
         */
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

        /*
         * 用哪个模型：三格各管一路，见 modelFor。
         * 用 modelId（写死的默认模型）会让「朗读模型」那一格变成死格子：
         * 几个模型列在那儿，点哪个念出来都一样。
         */
        val chosen = modelFor(voice, cfg)

        /*
         * 三个模型要的东西不一样，而且差别就在 audio.voice 这一格上：
         *
         *   voicedesign  不要 voice，音色描述写在 user 消息里
         *   voiceclone   要 voice，而且必须是 data:audio/xxx;base64,… 整串
         *   其余的 tts   要 voice，填预置音色名
         *
         * 判据是**模型名**，不是音色的 kind —— 用户改了模型，发送方式就得跟着改。
         */
        val isDesign = chosen.contains("voicedesign", true)
        val isClone = chosen.contains("clone", true) && !isDesign

        /*
         * 克隆音色的 voice 必须是整串 data URI，不能是名字。
         *
         * 我们自己存的样本写成 `clone:<id>`，真正的 data URI 在发请求这一刻才拼出来 ——
         * 几十兆的 base64 不进 voices.json，读嗓音表就不会因此卡。
         */
        val sample: String? = when {
            !isClone -> null
            voice.param.trim().startsWith("data:") -> voice.param.trim()
            else -> {
                val ctx = appCtx ?: throw IOException("克隆模型要一段音频样本才能张嘴")
                CloneStore.uri(ctx, voice.param)
                    ?: throw IOException("克隆模型要一段音频样本才能张嘴")
            }
        }

        /*
         * messages 只发一条 assistant，内容是正文。
         *
         * 官方文档（docs/zh-CN/api/audio/tts）：user 消息只有 voicedesign 必填，
         * 其余模型不必传；assistant 永远必填，它才是「要念的那段字」。
         * user 消息一旦发了，模型会把它也当成要念的内容 —— 这一段音频的最前面
         * 就会多出一句文章里没有的话。
         *
         * 所以预设音色和克隆音色都不发 user，只有 voicedesign 发（它必须靠描述定音色）。
         *
         * 例外是「调过速度、音调或调门」的那几副：这一家没有数值字段，
         * 官方示例就是用一句自然语言写在 user 里（"Fast pace, rising pitch at the end."）。
         * 代价是那条老毛病 —— 一个孤零零的词容易被当台词念出来。所以这里发的是
         * 一整句带主语的请求，不是「欢快」两个字，并且落盘留证（见 [note4check]）。
         */
        val tune = askPhrase(speed, pitch, mood)
        val said = when {
            /*
             * 造出来的音色：描述必须发（声音就是它生成的），快慢和音调也拼进同一条。
             * 拼法跟下面 [askPhrase] 一样，是一整句带主语的话，不是几个词。
             */
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
            .header("api-key", key)
            .header("Content-Type", "application/json")
        extra.forEach { (k, v) ->
            val low = k.lowercase()
            // 这三个由程序自己管，用户填了也不覆盖
            if (low != "api-key" && low != "content-type" && low != "authorization") b.header(k, v)
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

    /**
     * 把这一次真正发出去的东西留在 files/last-tts.txt。
     *
     * 只留最后一次。用户说「念出了文章里没有的话」，拿这个文件一对就知道
     * 是我们多发了一句、还是模型自己加的词 —— 不用再靠猜。
     */
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
