package com.pagefold.reader.field

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 翻页方式。 */
enum class TurnMode { FLIP, SCROLL, CHAPTER }

/** Settings.aiVoices 里表示「一副都不给」的那一格。 */
const val AI_NO_VOICE = "-"

/** 朗读引擎。 */
enum class EngineKind { MIMO, SYSTEM }

/** 全部可调项。整体序列化成一个 JSON 存在 SharedPreferences，加字段不用迁移，旧版本读到没有的字段自动用默认值。 */
data class Settings(
    // 外观
    val theme: String = "paper",
    val dark: Boolean = false,
    val shelfBg: String = "auto",       // 旧字段，只用来迁移
    val shelfDim: Int = 55,             // 旧字段，界面上已经不给调了
    val bgJson: String = "",            // {"shelf":"image:/…|0.6|0|0|1.0", …}
    val enginesJson: String = "",       // 嗓子列表
    /** 会想事的那几路接口（派角时用）。 */
    val chatJson: String = "",

    // 头一屏是哪一页。3 = 定音：声源、钥匙、念法都在那儿定。
    val homeTab: Int = 3,
    val choseHome: Boolean = false,
    val uiRev: Int = 0,
    /** 已同意的条款版本。0 = 没同意过。政策大改时把 Consent.CONSENT_REV 换成一个更大的数。 */
    val consentRev: Int = 0,
    /** 是否已同意《声音样本处理说明》。false = 第一次点「复刻嗓音」时先问。 */
    val voiceConsent: Boolean = false,

    // 阅读
    val fontSize: Int = 19,
    val lineGap: Int = 150,             // 行距百分比
    val turnMode: String = "FLIP",
    val padH: Int = 22,
    val paraGap: Int = 0,

    // 引擎
    val engine: String = "MIMO",
    val mimoKey: String = "",
    val mimoBase: String = "https://api.xiaomimimo.com",
    val sysEngine: String = "",
    val sysVoice: String = "",
    val style: String = "平静",          // 情绪档（单嗓子时用）
    val narratorStyle: String = "",     // 旁白自己的风格。空 = 跟 style
    val dialogStyle: String = "",       // 对白自己的风格。空 = 跟 style
    val speed: Int = 100,               // 语速 50-200
    val pitch: Int = 100,               // 音高 50-200

    // 多角色朗读
    val castOn: Boolean = true,
    // 旁白与对白各一副。名字对应的音色不存在时，由 AutoCast 按开着的嗓源换过去。
    val narratorVoice: String = "白桦",
    val defaultVoice: String = "茉莉",
    val rulesJson: String = "",
    /** 全局角色表（CastBook 的 JSON）。单本自己的在 BookMeta.castJson，优先。 */
    val castJson: String = "",

    // 朗读节奏
    val chunkChars: Int = 400,          // 单行上限。超过就在句末切一刀
    val prefetchChars: Int = 3600,      // 往后先合成多少字
    val cacheChapters: Int = 5,         // 新书默认备几章
    val gapMs: Int = 500,               // 段间间隔
    val reqIntervalMs: Int = 600,       // 两次请求之间歇多久
    val concurrency: Int = 2,           // 同时发几条
    val retry: Int = 3,
    val retryDelayMs: Int = 1200,

    /** 按段落判角色（旁白/对白/心里话…），判完再定语速音高。 */
    val cue: Boolean = true,

    // ── 收听 ──
    /** 连听几章后停在章末。0 = 一直听。数含开念的那一章：1 就是念完本章停。 */
    val stopChapters: Int = 0,
    /** 播放倍速 ×100。100=原速，只在已合成的音频上变速，不改合成语速。 */
    val rate: Int = 100,
    /** 接着上次听时，往前退几段。段落边界常常正好切在半句上。 */
    val rewind: Int = 1,

    // 缓存上限，0 = 不限。只用来显示。
    val cacheMaxMb: Int = 0,

    // ── 怎么分配（让 AI 分组派嗓子） ──
    /**
* 「几句话以上才发」。用户自己写，默认 50，存进存档。
*/
    val aiGate: Int = 50,
    /** 发给 AI 的那段「怎么分」的说明。 */
    val aiPrompt: String = "",
    /** 准 AI 自己造嗓音。只有能造的那一路开着（目前是小米）才作数；没有这一格时派角永远是「从现成的里挑」。 */
    val aiDesign: Boolean = false,
    /** 准它用的那几副嗓子，逗号分隔。 */
    val aiVoices: String = "",
    /** 派角里那一串嗓子的展开状态。默认展开；收起来之后下次进来还是收着。 */
    val aiVoiceOpen: Boolean = true,
) {
    val turn: TurnMode get() = runCatching { TurnMode.valueOf(turnMode) }.getOrDefault(TurnMode.FLIP)
    val engineKind: EngineKind get() = runCatching { EngineKind.valueOf(engine) }.getOrDefault(EngineKind.MIMO)

    /** 准它用的那几副嗓子。null = 还没定过（那就是库里有的全给）。 */
    val aiVoiceSet: Set<String>?
        get() = when {
            aiVoices.isBlank() -> null
            aiVoices.trim() == AI_NO_VOICE -> emptySet()
            else -> aiVoices.split(',', '，', '\n')
                .map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

    /** 能造嗓音的那一路（小米）开着没有。 */
    val canDesign: Boolean
        get() = engines.any { it.on && it.src == VoiceSource.MIMO }

    /** 声源表。这一句会被界面每帧调到，所以绝不许抛。 */
    val engines: List<EngineCfg>
        get() {
            /* 缓存一层。这个 getter 一帧里会被叫好几次（阅读页每个 remember 的 key、播放器每一句的取音色、音色页的分组）。 */
            val key = enginesJson
            EnginesCache.hit(key)?.let { return it }
            val v = runCatching { Engines.decode(key) }.getOrDefault(Engines.DEFAULT)
            EnginesCache.put(key, v)
            return v
        }

    /** 接口表。跟 [engines] 一样要缓存 —— 这个 getter 也会被界面每帧叫到。 */
    val chats: List<com.pagefold.reader.voice.ChatCfg>
        get() {
            val key = chatJson
            ChatCache.hit(key)?.let { return it }
            val v = runCatching { com.pagefold.reader.voice.Chats.decode(key) }
                .getOrDefault(com.pagefold.reader.voice.Chats.DEFAULT)
            ChatCache.put(key, v)
            return v
        }

    /** 某一页的底子。空 = 跟着主题走。 */
    fun bg(page: String): String {
        val raw = bgJson
        if (raw.isNullOrBlank()) {
            if (page == "shelf") {
                val one = shelfBg
                if (!one.isNullOrBlank() && one != "auto") return one
            }
            return "auto"
        }
        BgCache.of(raw)[page]?.let { return it }
        if (page == "shelf") {
            val one = shelfBg
            if (!one.isNullOrBlank() && one != "auto") return one
        }
        return "auto"
    }

    fun withBg(page: String, value: String): Settings {
        val t = object : TypeToken<Map<String, String>>() {}.type
        val g = Gson()
        val m = runCatching { g.fromJson<Map<String, String>>(bgJson, t) }.getOrNull()
            ?.toMutableMap() ?: mutableMapOf()
        m[page] = value
        return copy(bgJson = g.toJson(m))
    }

    fun withEngines(list: List<EngineCfg>): Settings = copy(enginesJson = Engines.encode(list))

    fun withChats(list: List<com.pagefold.reader.voice.ChatCfg>): Settings =
        copy(chatJson = com.pagefold.reader.voice.Chats.encode(list))
}

/** bgJson 的单槽解析缓存。 */
private object BgCache {
    private var raw: String? = null
    private var map: Map<String, String> = emptyMap()

    fun of(json: String): Map<String, String> {
        if (json == raw) return map
        val t = object : TypeToken<Map<String, String>>() {}.type
        map = runCatching { Gson().fromJson<Map<String, String>>(json, t) }
            .getOrNull().orEmpty()
        raw = json
        return map
    }
}

/** 页的名字，跟底子一一对应。 */
object Pages {
    val ALL: List<Pair<String, String>> = listOf(
        "shelf" to "书房",
        "voice" to "音色",
        "preview" to "试听",
        "reader" to "阅读",
        "tune" to "定音",
        "about" to "关于",
    )

    fun name(key: String): String = ALL.firstOrNull { it.first == key }?.second ?: key
}

/** 接口表的解析结果，按存档原文记一份。跟 [EnginesCache] 一个道理。 */
private object ChatCache {
    private var key: String? = null
    private var val_: List<com.pagefold.reader.voice.ChatCfg>? = null

    fun hit(k: String): List<com.pagefold.reader.voice.ChatCfg>? = if (k == key) val_ else null

    fun put(k: String, v: List<com.pagefold.reader.voice.ChatCfg>) {
        key = k
        val_ = v
    }
}

/** 声源表的解析结果，按存档原文记一份。 */
private object EnginesCache {
    private var key: String? = null
    private var val_: List<com.pagefold.reader.field.EngineCfg>? = null

    fun hit(k: String): List<com.pagefold.reader.field.EngineCfg>? = if (k == key) val_ else null

    fun put(k: String, v: List<com.pagefold.reader.field.EngineCfg>) {
        key = k
        val_ = v
    }
}

/** 存档版本。抬一格就代表下面 read() 里要多一段迁移。 */
const val REV = 5

class Field(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("field", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    val now: Settings get() = _state.value

    /** 读存档。Gson 反序列化不走 Kotlin 构造器。 */
    private fun read(): Settings = runCatching {
        val raw = sp.getString("json", null) ?: return@runCatching Settings(uiRev = REV)
        val stored = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
            ?: return@runCatching Settings(uiRev = REV)

        val base = gson.toJsonTree(Settings(uiRev = REV)).asJsonObject
        stored.entrySet().forEach { (k, v) ->
            if (v != null && !v.isJsonNull && base.has(k)) base.add(k, v)
        }
        var out = gson.fromJson(base, Settings::class.java) ?: Settings(uiRev = REV)

        // 存档里的版本号
        val had = runCatching { stored.get("uiRev")?.asInt ?: 0 }.getOrDefault(0)

        if (had < 1) out = out.copy(dark = false, choseHome = false)
        // 二版起：嗓子一律先关着，由用户自己拨。钥匙地址都留着。
        if (had < 2) out = out.copy(enginesJson = Engines.encode(out.engines.map { it.copy(on = false) }))
        // 请求间隔落在 100..2000，存档里更小的值抬到 100。
        if (had < 3 && out.reqIntervalMs >= 2000) out = out.copy(reqIntervalMs = 600)
        out.copy(uiRev = REV)
    }.getOrDefault(Settings(uiRev = REV))

    /** 写。内存立刻生效，落盘压到 300 毫秒后。 */
    fun put(s: Settings) {
        _state.value = s
        pending?.cancel()
        pending = scope.launch {
            delay(300)
            sp.edit().putString("json", gson.toJson(_state.value)).apply()
        }
    }

    /** 立刻落盘。退到后台、退出前调一次，免得最后那点改动丢。 */
    fun flush() {
        pending?.cancel()
        pending = null
        runCatching { sp.edit().putString("json", gson.toJson(_state.value)).apply() }
    }

    fun edit(block: (Settings) -> Settings) = put(block(_state.value))

    fun reset() = put(Settings())
}
