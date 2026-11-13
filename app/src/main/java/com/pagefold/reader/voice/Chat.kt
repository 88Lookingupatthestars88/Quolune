package com.pagefold.reader.voice

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 一路「会想事」的接口。
 * @param auth 认证头怎么放。`bearer` = Authorization: Bearer xxx；
 */
data class ChatCfg(
    val id: String,
    val name: String,
    val base: String,
    val key: String = "",
    val model: String = "",
    val on: Boolean = false,
    val auth: String? = null,
) {
    fun authKind(): String = (auth ?: "").ifBlank {
        if ((base ?: "").contains("xiaomimimo")) "api-key" else "bearer"
    }
}

/**
 * 一家聊天服务的固定参数。
 * @param base   接口地址。填到这里，用户只差一把钥匙。
 * @param prefer 拉回模型清单之后，按这个顺序自动挑一个。挑不到就挑清单第一个。
 * @param note   这家和别家不一样的地方，一句话，摆在卡片副标题上。
 */
data class ChatPreset(
    val id: String,
    val name: String,
    val base: String,
    val prefer: List<String> = emptyList(),
    val auth: String = "bearer",
    val note: String = "",
)

object ChatPresets {

    val ALL: List<ChatPreset> = listOf(
        ChatPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com",
            prefer = listOf("deepseek-chat", "deepseek-reasoner"),
            note = "国内直连，中文小说读得细",
        ),
        ChatPreset(
            "silicon", "硅基流动", "https://api.siliconflow.cn/v1",
            prefer = listOf("DeepSeek-V3", "Qwen3", "GLM-4.5", "Qwen2.5-72B"),
            note = "一个钥匙开一堆模型，挑得动",
        ),
        ChatPreset(
            "qwen", "阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            prefer = listOf("qwen-plus", "qwen-max", "qwen3"),
            note = "开通后有免费额度",
        ),
        ChatPreset(
            "zhipu", "智谱", "https://open.bigmodel.cn/api/paas/v4",
            prefer = listOf("glm-4-flash", "glm-4-air", "glm-4-plus"),
            note = "flash 那档不要钱",
        ),
        ChatPreset(
            "kimi", "Kimi", "https://api.moonshot.cn/v1",
            prefer = listOf("kimi-k2", "moonshot-v1-32k", "moonshot-v1-8k"),
            note = "长名单不怕，上下文大",
        ),
        ChatPreset(
            "mimo", "小米 MiMo", "https://api.xiaomimimo.com/v1",
            prefer = listOf("mimo-v2", "mimo"),
            auth = "api-key",
            note = "认证头是 api-key，不是 Bearer",
        ),
        ChatPreset(
            "openai", "OpenAI", "https://api.openai.com/v1",
            prefer = listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o"),
            note = "国内多半连不上",
        ),
        ChatPreset(
            "ark", "火山方舟", "https://ark.cn-beijing.volces.com/api/v3",
            prefer = emptyList(),
            note = "模型那格要填推理接入点（ep- 开头），拉不到清单",
        ),
    )

    fun byId(id: String): ChatPreset? = ALL.firstOrNull { it.id == id }

    /** 按地址认是谁家。同一家常有新旧两个域名，所以按关键字认，不按整串比。 */
    fun of(base: String): ChatPreset? {
        val b = (base ?: "").trim().lowercase()
        if (b.isEmpty()) return null
        return when {
            b.contains("deepseek") -> byId("deepseek")
            b.contains("siliconflow") -> byId("silicon")
            b.contains("dashscope") || b.contains("aliyuncs") -> byId("qwen")
            b.contains("bigmodel") || b.contains("zhipu") -> byId("zhipu")
            b.contains("moonshot") -> byId("kimi")
            b.contains("xiaomimimo") -> byId("mimo")
            b.contains("openai.com") -> byId("openai")
            b.contains("volces") || b.contains("ark.cn") -> byId("ark")
            else -> null
        }
    }

    /** 这一列是「还没配的那家」的出厂值：地址填好，钥匙空着，开关关着。 */
    fun fresh(): List<ChatCfg> = ALL.map {
        ChatCfg(id = it.id, name = it.name, base = it.base, auth = it.auth)
    }
}

object Chats {

    private val gson = Gson()

    /** 出厂那一排。地址已经填好，缺的只有钥匙。 */
    val DEFAULT: List<ChatCfg> = ChatPresets.fresh()

    /** 读存档里的那串。老存档里没有这一格（空串），直接给出厂表 —— 不必写迁移。 */
    fun decode(raw: String?): List<ChatCfg> {
        if (raw.isNullOrBlank()) return DEFAULT
        val t = object : TypeToken<List<ChatCfg>>() {}.type
        val list = runCatching { gson.fromJson<List<ChatCfg>>(raw, t) }.getOrNull()
        if (list.isNullOrEmpty()) return DEFAULT
        val out = list.filter { it.id.isNotBlank() }.map { e ->
            runCatching {
                e.copy(
                    name = (e.name ?: "").ifBlank { ChatPresets.byId(e.id)?.name ?: "接口" },
                    base = (e.base ?: "").ifBlank { ChatPresets.byId(e.id)?.base ?: "" },
                    key = e.key ?: "",
                    model = e.model ?: "",
                    auth = (e.auth ?: "").ifBlank { ChatPresets.byId(e.id)?.auth ?: "bearer" },
                )
            }.getOrNull()
        }.filterNotNull().toMutableList()
        DEFAULT.forEach { d -> if (out.none { it.id == d.id }) out.add(d) }
        return out
    }

    fun encode(list: List<ChatCfg>): String = gson.toJson(list)

    /** 现在真能用的那一路：开着、有地址、有钥匙。没有就是 null。 */
    fun ready(list: List<ChatCfg>): ChatCfg? =
        list.firstOrNull { it.on && why(it) == null }

    /** 缺什么。null = 能用。 */
    fun why(e: ChatCfg): String? {
        if ((e.base ?: "").isBlank()) return "还没填接口地址"
        if ((e.key ?: "").isBlank()) return "还没填钥匙"
        Auth.why(e.key)?.let { return it }
        return null
    }

    /** 拉清单之后自动挑一个：先按这家认的那几个挑，挑不到取第一个。 */
    fun pick(preset: ChatPreset?, list: List<String>): String {
        if (list.isEmpty()) return ""
        preset?.prefer?.forEach { want ->
            list.firstOrNull { it.equals(want, true) || it.contains(want, true) }?.let { return it }
        }
        return list.firstOrNull { it.contains("chat") || it.contains("instruct") || it.contains("flash") }
            ?: list.first()
    }
}

/**
 * 聊天口的调用。OpenAI 兼容的那一套：POST {base}/chat/completions。
 * 不流式、不存历史 —— 这个应用里它每次都是独立一问，没有「接着上一句聊」这回事。
 */
object Chat {

    private val json = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 一个大模型的回答要等：几十秒是常态，两分钟是这条路的底线。
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** `{base}/chat/completions`。地址末尾带不带 /v1、/v4、/v3 都认。 */
    fun endpoint(base: String): String {
        val b = (base ?: "").trim().trimEnd('/')
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") || b.endsWith("/v4") || b.endsWith("/v3") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    fun modelsEndpoint(base: String): String {
        val b = (base ?: "").trim().trimEnd('/')
        return when {
            b.endsWith("/models") -> b
            b.endsWith("/v1") || b.endsWith("/v4") || b.endsWith("/v3") -> "$b/models"
            else -> "$b/v1/models"
        }
    }

    private fun builder(base: String, key: String, auth: String): Request.Builder {
        val b = Request.Builder()
            .header("Content-Type", "application/json")
        if (auth == "api-key") b.header("api-key", Auth.head(key))
        else b.header("Authorization", Auth.bearer(key))
        return b
    }

    /**
     * 问一句，拿回一段字。
     * @param system 规矩。这段是「你是谁、按什么口径办」，每次都一样。
     * @param user   这一回的具体内容。
     */
    suspend fun ask(cfg: ChatCfg, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val base = (cfg.base ?: "").trim()
            if (base.isBlank()) throw IOException("还没填接口地址")
            Auth.why(cfg.key)?.let { throw IOException(it) }
            val model = (cfg.model ?: "").trim()
            if (model.isBlank()) throw IOException("还没挑模型")

            val payload = JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.3)
                addProperty("stream", false)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", system)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", user)
                    })
                })
            }.toString()

            val req = builder(base, cfg.key, cfg.authKind())
                .url(endpoint(base))
                .post(payload.toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException(say(resp.code, body))
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: throw IOException("对面回的不是 JSON：${body.take(120)}")
                val msg = root.getAsJsonArray("choices")?.firstOrNull()
                    ?.asJsonObject?.getAsJsonObject("message")
                val text = msg?.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (text.isBlank()) {
                    /* 推理模型偶尔只回 reasoning_content。那一段是「怎么想的」，不是答案，但总比空手强。 */
                    val think = msg?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    if (think.isBlank()) throw IOException("对面没给内容：${body.take(120)}")
                    return@use think
                }
                text
            }
        }

    /** 拉清单。拉不到给空表，界面让用户自己填 —— 不猜。 */
    suspend fun models(cfg: ChatCfg): List<String> = withContext(Dispatchers.IO) {
        val base = (cfg.base ?: "").trim()
        if (base.isBlank()) return@withContext emptyList()
        if (Auth.why(cfg.key) != null) return@withContext emptyList()
        val cached = listOf(base, cfg.key, cfg.model).joinToString("|")
        modelMem[cached]?.let { return@withContext it }
        runCatching {
            val req = builder(base, cfg.key, cfg.authKind())
                .url(modelsEndpoint(base))
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use emptyList<String>()
                val arr = runCatching {
                    JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
                }.getOrNull() ?: return@use emptyList<String>()
                arr.mapNotNull { el ->
                    runCatching { el.asJsonObject.get("id").asString }.getOrNull()
                }.filter { it.isNotBlank() }.distinct()
            }
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) modelMem[cached] = it }
    }

    private val modelMem = HashMap<String, List<String>>()

    /** 一次验收回来的一坨：通没通，以及对面原话。 */
    data class Hit(val ok: Boolean, val said: String)

    /** 验收：真发一句「你好」过去，把对面的回话原样端回来。 */
    suspend fun hello(cfg: ChatCfg): Hit = withContext(Dispatchers.IO) {
        Chats.why(cfg)?.let { return@withContext Hit(false, it) }
        var c = cfg
        if ((c.model ?: "").isBlank()) {
            val got = models(c)
            if (got.isEmpty()) return@withContext Hit(false, "还没挑模型")
            c = c.copy(model = Chats.pick(ChatPresets.of(c.base ?: ""), got))
        }
        try {
            val t = ask(c, "你在试接口通不通。只回一句问候，十个字以内。", "你好")
            Hit(true, t.trim().ifBlank { "（回了空话）" }.replace("\n", " ").take(60))
        } catch (e: Throwable) {
            Hit(false, (e.message ?: "没通").trim().replace("\n", " ").take(80))
        }
    }

    /** 老名字留着，别的地方还当「通不通」用。 */
    suspend fun probe(cfg: ChatCfg): String? = hello(cfg).let { if (it.ok) null else short(it.said) }

    const val OK = "通"

    /** 把一段回话里的 JSON 抠出来。 */
    fun tidy(raw: String): String {
        var t = (raw ?: "").trim()
        if (t.startsWith("```")) {
            val i = t.indexOf('\n')
            if (i > 0) t = t.substring(i + 1)
            val j = t.lastIndexOf("```")
            if (j > 0) t = t.substring(0, j)
        }
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        return if (a >= 0 && b > a) t.substring(a, b + 1) else t.trim()
    }

    /** 把长错误压成一句人话。跟念书那边同一套口径，用户看到的是同一批词。 */
    fun short(msg: String?): String {
        val m = (msg ?: "").trim()
        if (m.isEmpty()) return "没通"
        if (m.startsWith("还没填") || m.startsWith("钥匙")) return m.take(30)
        val code = Regex("接口\\s*(\\d{3})").find(m)?.groupValues?.get(1)
        if (code != null) return when {
            code == "401" || code == "403" -> "钥匙不对"
            code == "404" -> "地址或模型不对"
            code == "400" -> "请求被拒了，多半是模型名不对"
            code == "429" -> "被限流了"
            code.startsWith("5") -> "对面自己出错了"
            else -> "对面回了 $code"
        }
        return when {
            m.contains("Unable to resolve host") || m.contains("Failed to connect") ||
                m.contains("timeout", true) -> "地址连不上"
            else -> m.take(18)
        }
    }

    private fun say(code: Int, body: String): String =
        "接口 $code：" + body.take(180)
}
