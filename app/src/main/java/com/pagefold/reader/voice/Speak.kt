package com.pagefold.reader.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 标准口：POST {base}/v1/audio/speech，回来的就是音频字节。
 *
 *   { "model": "...", "input": "...", "voice": "...",
 *     "response_format": "mp3", "speed": 1.0 }
 *   Authorization: Bearer <key>
 *
 * OpenAI、硅基流动走这一套，两家共用一个实现。小米那套 chat/completions
 * 是另一种口（音频在 choices[0].message.audio 里，头是 api-key），在 [MiMo]。
 */
object Speak {

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
     * @param base   接口地址，末尾带不带 /v1 都行
     * @param voice  音色名（`alloy`、`FunAudioLLM/CosyVoice2-0.5B:alex` 这种）
     * @param speed  0.25–4.0，我们那边是百分数，调用方自己除
     */
    suspend fun mp3(
        base: String,
        key: String,
        model: String,
        text: String,
        voice: String,
        speed: Float = 1f,
        extra: List<Pair<String, String>> = emptyList(),
        gain: Float? = null,
        instructions: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (base.isBlank()) throw IOException("还没填接口地址")
        if (key.isBlank()) throw IOException("还没填钥匙")
        // 汉字会让 OkHttp 在 build() 抛 IllegalArgumentException，先拦下。
        Auth.why(key)?.let { throw IOException(it) }
        if (model.isBlank()) throw IOException("还没填模型")
        if (text.isBlank()) throw IOException("这一段是空的")

        /*
         * 模型名要跟地址同源。
         *
         * 百炼的模型是 qwen3-tts-flash，走的是 dashscope 的多模态口；
         * 上面这七家列出来的是**各自**的清单，如果用户把 MiMo 那条的模型名
         * 挑到百炼这里来，地址和模型就对不上 —— 对面会回一个语焉不详的 400。
         * 这里先拦下来，说清是哪儿错了，别让它跑一趟再报个看不懂的错。
         */
        if (!modelLooksRight(base, model)) {
            throw IOException("「$model」不是这家的模型，地址与它不匹配")
        }

        val url = endpoint(base)
        val payload = buildString {
            append("{\"model\":").append(quote(model))
            append(",\"input\":").append(quote(text))
            append(",\"voice\":").append(quote(voice.ifBlank { "alloy" }))
            append(",\"response_format\":\"mp3\"")
            append(",\"speed\":").append(fmt(speed.coerceIn(0.25f, 4f)))
            /*
             * gain 只有硅基认（−10…10 dB）。OpenAI 那边没有这个字段，
             * 多发一个它不认识的键，有的边关会直接回 400 —— 所以只在真要发的时候拼。
             */
            if (gain != null && gain != 0f) {
                append(",\"gain\":").append(fmt(gain.coerceIn(-10f, 10f)))
            }
            /*
             * instructions 只有 gpt-4o 那几个认，用一句话说语气。
             * 别家发这一格等于往请求里塞一个没人看的字段。
             */
            if (!instructions.isNullOrBlank()) {
                append(",\"instructions\":").append(quote(instructions))
            }
            append("}")
        }

        val b = Request.Builder()
            .url(url)
            .header("Authorization", Auth.bearer(key))
            .header("Content-Type", "application/json")
        extra.forEach { (k, v) ->
            val low = k.lowercase()
            if (low != "authorization" && low != "content-type") {
                runCatching { b.header(k, Auth.clean(v)) }
            }
        }
        val req = b.post(payload.toRequestBody(json)).build()

        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val say = String(bytes, Charsets.UTF_8).take(200)
                val msg = "语音口 ${resp.code}（$model）: $say"
                if (resp.code == 429 || resp.code in 500..599) throw IOException(msg)
                throw IllegalStateException(msg)
            }
            // 有的服务 200 却回一段 JSON 错误，长度短又不是音频。512 以下当没声音。
            if (bytes.size < 512) {
                val say = String(bytes, Charsets.UTF_8).take(200)
                throw IOException("回了空声音（${bytes.size} 字节）：$say")
            }
            bytes
        }
    }

    /**
     * 填完就验一次：这个模型名，这家到底念不念得出来。
     *
     * **不是「听一句」，是「问一句对面收不收」。** 发两个字，只看回来的东西像不像音频。
     * 判据是硬的，不接受 200：对面 200 回一段 JSON 错误、回一段 300 字节的空壳、
     * 或者根本不认识这个 model 却拿默认音色硬念（小米就干这个），都在这里被认出来。
     *
     * 返回 null = 能念。返回一句话 = 为什么不能，字数按卡片那行留的位子来算，
     * 不超过十来个字，超出就没人看了。
     */
    suspend fun probe(base: String, key: String, model: String, voice: String): String? {
        val why = withContext(Dispatchers.IO) {
            try {
                val b = mp3(base, key, model, "嗯。", voice.ifBlank { "alloy" })
                if (b.size < 2048) "回来了，但没有声音" else null
            } catch (e: Throwable) {
                short(e.message)
            }
        }
        probeKeep("speech", base, key, model, why ?: OK)
        return why
    }

    /** 验出来没问题时记下的那个值。跟「没验过」（null）不是一回事。 */
    const val OK = "念得出来"

    /**
     * 小米那一路的验法。
     *
     * 它不走 `audio/speech`，走的是 chat/completions，音频藏在 message.audio 里，
     * 认证头是 `api-key`。拿标准口那套去敲它，回什么都说明不了问题 ——
     * 所以这里走它自己的口，发同样两个字。
     *
     * 只读：不落盘、不改任何配置。
     */
    suspend fun probeMimo(base: String, key: String, model: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val v = Voice(
                    id = "probe", name = "冰糖", kind = "preset", engine = "mimo",
                    param = "冰糖", desc = "", builtin = true,
                )
                val cfg = com.pagefold.reader.field.EngineCfg(
                    id = "probe", name = "probe", kind = "MIMO",
                    base = base, key = key, model = model, on = true, proto = "mimo",
                )
                val b = MiMo(base.ifBlank { "https://api.xiaomimimo.com" }, key).wav("嗯。", v, cfg = cfg)
                if (b.size < 2048) "回来了，但没有声音" else null
            } catch (e: Throwable) {
                short(e.message)
            }.also { probeKeep("mimo", base, key, model, it ?: OK) }
        }

    /** 把长错误压成一句能塞进卡片副标题的话。 */
    private fun short(msg: String?): String {
        val m = (msg ?: "").trim()
        if (m.isEmpty()) return "没验成"
        // 我们这边自己拦下来的，原话就已经是一句人话。
        if (m.startsWith("「") || m.startsWith("不能合成") || m.startsWith("还没填") ||
            m.startsWith("钥匙")
        ) return m.take(30)
        val code = Regex("(?:语音口|MiMo)\\s*(\\d{3})").find(m)?.groupValues?.get(1)
        if (code != null) return when {
            code == "401" || code == "403" -> "钥匙不对"
            code == "404" -> "这家没有这个模型"
            // 400 分不清是模型不收还是音色名不认，别替对面下结论。
            code == "400" -> "模型或音色不对"
            code == "429" -> "被限流了"
            code.startsWith("5") -> "对面自己出错了"
            else -> "对面回了 $code"
        }
        return when {
            m.contains("Unable to resolve host") || m.contains("Failed to connect") ||
                m.contains("timeout", true) -> "地址连不上"
            m.contains("空声音") -> "回来了，但没有声音"
            else -> m.take(16)
        }
    }

    /**
     * 这家有哪些模型。`GET {base}/v1/models`，OpenAI 标准口都有这个端点。
     *
     * **拉回来全部照列，一条不筛。** 这几家的清单里没有「这是朗读模型」这个字段，
     * 本地猜一定会猜错（`mimo-v2.5-tts-voiceclone` 也带 tts），猜错就把能用的藏起来了。
     * 选错了怎么办：点「试一句」，对面会回绝，那时再照它的原话讲清楚。
     *
     * 能少拉的只做两件事，都是对面自己给的开关：
     * · 硅基流动认 `?type=audio`，把它两百条文字模型挡在外面；
     * · 火山没有清单端点（`/api/v3/tts/models` 是 404），不发这个请求。
     *
     * @param proto 走哪套协议，决定端点和要不要筛
     */
    /**
     * 上一次要回来的清单，记在内存里。
     *
     * 定音页每进一次、每改一格钥匙，都会来要一遍。七家各要一次就是七个请求，
     * 而这份清单一天也变不了一次 —— 记住它，来回翻页就是瞬间出来的。
     * 只记这一次开机；重启应用重拉。
     */
    private val modelMem = HashMap<String, List<String>>()

    /**
     * 模型清单也落一份在 files/models.json。
     *
     * 只放内存的话，进程一重启就得七家各发一次请求 —— 进一次定音等一轮，
     * 这是「一进来就要重新获取」的那一半。
     */
    @Volatile
    private var modelFile: File? = null
    private var modelDiskLoaded = false
    private val modelDisk = HashMap<String, List<String>>()

    private fun modelDiskLoad() {
        if (modelDiskLoaded) return
        modelDiskLoaded = true
        runCatching {
            val f = modelFile ?: return@runCatching
            if (!f.exists()) return@runCatching
            val t = object : com.google.gson.reflect.TypeToken<HashMap<String, List<String>>>() {}.type
            com.google.gson.Gson().fromJson<HashMap<String, List<String>>>(f.readText(), t)
                ?.let { m -> m.forEach { (k, v) -> if (v != null) modelDisk[k] = v } }
        }
    }

    private fun modelDiskSave() {
        runCatching {
            modelFile?.writeText(com.google.gson.Gson().toJson(modelDisk))
        }
    }

    /** 盘上有没有这家的清单。有就不必再去要，界面直接摆出来。 */
    fun modelsCached(base: String, key: String, proto: String = "speech"): List<String> {
        val sig = "$proto|$base|$key"
        modelMem[sig]?.let { return it }
        modelDiskLoad()
        return modelDisk[sig].orEmpty()
    }

    /* ───────────────── 验过就记住 ───────────────── */

    /**
     * 验过的结论，落一份在 files/probed.json。
     *
     * 每验一次都要真发一个请求，有的家按字计费。所以把「协议 + 地址 + 钥匙 + 模型」
     * 合成一个签名一起记：四样没变就直接拿上一次的结论，一个请求都不发；
     * 换了一样，签名变了，才重验。
     */
    @Volatile
    private var probeFile: File? = null

    private val probeMem = HashMap<String, String>()

    fun attach(ctx: android.content.Context) {
        if (probeFile == null) probeFile = File(ctx.filesDir, "probed.json")
        if (modelFile == null) modelFile = File(ctx.filesDir, "models.json")
    }

    private fun probeSig(proto: String, base: String, key: String, model: String): String =
        proto + "|" + base.trim() + "|" + key.trim() + "|" + model.trim()

    private fun probeLoad(): HashMap<String, String> {
        if (probeMem.isNotEmpty()) return probeMem
        runCatching {
            val f = probeFile ?: return@runCatching
            if (!f.exists()) return@runCatching
            val t = object : com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.type
            com.google.gson.Gson().fromJson<HashMap<String, String>>(f.readText(), t)
                ?.let { probeMem.putAll(it) }
        }
        return probeMem
    }

    private fun probeSave() {
        runCatching { probeFile?.writeText(com.google.gson.Gson().toJson(probeMem)) }
    }

    /** 上一次的结论；没验过返回 null（跟「验出来没问题」区分开）。 */
    fun probeRemembered(proto: String, base: String, key: String, model: String): String? =
        probeLoad()[probeSig(proto, base, key, model)]

    fun probeForget(proto: String, base: String, key: String, model: String) {
        if (probeLoad().remove(probeSig(proto, base, key, model)) != null) probeSave()
    }

    private fun probeKeep(proto: String, base: String, key: String, model: String, verdict: String) {
        probeMem[probeSig(proto, base, key, model)] = verdict
        probeSave()
    }

    suspend fun models(base: String, key: String, proto: String = "speech"): List<String> {
        val sig = "$proto|$base|$key"
        modelMem[sig]?.let { return it }
        modelsCached(base, key, proto).takeIf { it.isNotEmpty() }?.let {
            modelMem[sig] = it
            return it
        }
        val got = fetchModels(base, key, proto)
        if (got.isNotEmpty()) {
            modelMem[sig] = got
            modelDisk[sig] = got
            modelDiskSave()
        }
        return got
    }

    private suspend fun fetchModels(base: String, key: String, proto: String): List<String> =
        withContext(Dispatchers.IO) {
        if (base.isBlank() || key.isBlank()) return@withContext emptyList()
        // 钥匙不合规就不发，界面拿到空清单自己填模型。
        if (Auth.why(key) != null) return@withContext emptyList()
        if (proto == "volc" || proto == "system") return@withContext emptyList()
        val url = modelsEndpoint(base, proto).let {
            // 硅基的清单按大类分，audio 里只有语音相关的；文字模型全被挡在外面。
            if (proto == "speech" && base.contains("siliconflow") && !it.contains("?")) "$it?type=audio"
            else it
        }
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", Auth.bearer(key))
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use emptyList<String>()
                val arr = com.google.gson.JsonParser.parseString(body)
                    .asJsonObject.getAsJsonArray("data") ?: return@use emptyList<String>()
                arr.mapNotNull { el ->
                    runCatching { el.asJsonObject.get("id").asString }.getOrNull()
                }.filter { it.isNotBlank() }.distinct()
            }
        }.getOrDefault(emptyList())
    }

    fun modelsEndpoint(base: String, proto: String = "speech"): String {
        val b = base.trim().trimEnd('/')
        /*
         * 阿里百炼的兼容口不在 /v1 上，在 /compatible-mode/v1。
         * 拼成 /v1/models 是 404 —— 实测过。它自己认的地址是
         * https://dashscope.aliyuncs.com/compatible-mode/v1/models。
         */
        if (proto == "bailian" && !b.contains("/compatible-mode")) {
            return when {
                b.endsWith("/v1") -> "$b/models"
                else -> "$b/compatible-mode/v1/models"
            }
        }
        return when {
            b.endsWith("/models") -> b
            b.endsWith("/v1") -> "$b/models"
            else -> "$b/v1/models"
        }
    }

    /** 地址收尾：已经带 /v1 就只补 /audio/speech，否则补全。 */
    fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/audio/speech") -> b
            b.endsWith("/v1") -> "$b/audio/speech"
            else -> "$b/v1/audio/speech"
        }
    }

    /**
     * 这个模型名像不像这家的。
     *
     * 不判「是不是朗读模型」，那个猜不准。只判名字的出处对不对：
     * MiMo 是 `mimo-`、百炼是 `qwen`、硅基是 `厂/模型`。前缀全不搭的，是选串了行。
     */
    private fun modelLooksRight(base: String, model: String): Boolean {
        val b = base.lowercase()
        val m = model.lowercase()
        // 地址认不出来（多半是打错了或还没填全）：不拦，让对面的响应去说
        val known = b.contains("openai.com") || b.contains("siliconflow") ||
            b.contains("minimax") || b.contains("dashscope") || b.contains("aliyuncs") ||
            b.contains("volces") || b.contains("openspeech") || b.contains("xiaomimimo")
        if (!known) return true
        return when {
            b.contains("xiaomimimo") -> m.startsWith("mimo")
            b.contains("dashscope") || b.contains("aliyuncs") -> m.contains("qwen") || m.contains("cosyvoice")
            b.contains("minimax") -> m.contains("speech")
            b.contains("siliconflow") -> m.contains("/") || m.contains("cosyvoice") || m.contains("tts")
            b.contains("openai.com") -> m.startsWith("tts") || m.contains("gpt") || m.contains("audio")
            else -> true
        }
    }

    private fun fmt(f: Float): String {
        val i = (f * 100).toInt()
        return if (i % 100 == 0) "${i / 100}.0" else "%.2f".format(f)
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
