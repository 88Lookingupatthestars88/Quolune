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

/**
 * `Settings.aiVoices` 里表示「一副都不给」的那一格。
 *
 * 空串是「还没定过 = 库里有的全给」，所以「一副都不给」必须另有一个记号 ——
 * 不然用户把最后一副点掉，画面会自己又全亮回来，看着像点了没反应。
 */
const val AI_NO_VOICE = "-"

/** 朗读引擎。 */
enum class EngineKind { MIMO, SYSTEM }

/**
 * 全部可调项。整体序列化成一个 JSON 存在 SharedPreferences，
 * 加字段不用迁移，旧版本读到没有的字段自动用默认值。
 */
data class Settings(
    // 外观
    val theme: String = "glass",
    val dark: Boolean = false,
    val shelfBg: String = "auto",       // 旧字段，只用来迁移
    val shelfDim: Int = 55,             // 旧字段，界面上已经不给调了
    val bgJson: String = "",            // {"shelf":"image:/…|0.6|0|0|1.0", …}
    val enginesJson: String = "",       // 嗓子列表
    /**
     * 会想事的那几路接口（派角时用）。
     *
     * 跟嗓源分开存：念书和拿主意是两件事，模型名、计费、限流都不一样。
     * 老存档里没有这一格 → 空串 → 读出出厂那一排（地址已经填好，只差钥匙）。
     */
    val chatJson: String = "",

    // 头一屏是哪一页。3 = 定音。
    //
    // 第一次装的人得先在这里把嗓源拨开、把钥匙填上、把念法定下来；
    // 这些不定，进了书也听不见。所以他先落定音，之后再从底栏去别处。
    val homeTab: Int = 3,
    val choseHome: Boolean = false,
    val uiRev: Int = 0,

    // 阅读
    val fontSize: Int = 19,
    val lineGap: Int = 150,             // 行距百分比
    val turnMode: String = "FLIP",
    val padH: Int = 22,
    val paraGap: Int = 10,

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
    val chunkChars: Int = 400,          // 一次合成多长。400 字是「出声快」和「请求别太碎」之间的点
    val prefetch: Int = 6,              // 提前合成的段数
    val cacheChapters: Int = 5,         // 预缓存章数
    val gapMs: Int = 500,               // 段间间隔
    val reqIntervalMs: Int = 600,       // 两次请求之间歇多久
    val concurrency: Int = 2,           // 同时发几条
    val retry: Int = 3,
    val context: Int = 1,               // 合成时往上带几句上文
    val next: Int = 1,                  // 往下带几句下文
    val retryDelayMs: Int = 1200,

    /** 按段落判角色（旁白/对白/心里话…），判完再定语速音高。 */
    val cue: Boolean = true,

    // ── 收听 ──
    /** 一章读完自动接下一章。关掉就停在章末。 */
    val autoNext: Boolean = true,
    /** 每章读完就停。睡前用，优先于 autoNext。 */
    val stopAtChapter: Boolean = false,
    /** 播放倍速 ×100。100=原速，只在已合成的音频上变速，不改合成语速。 */
    val rate: Int = 100,
    /** 接着上次听时，往前退几段。段落边界常常正好切在半句上。 */
    val rewind: Int = 1,

    // 缓存（只用来显示，不再自动删）
    val cacheMaxMb: Int = 0,

    // ── 怎么分配（让 AI 分组派嗓子） ──
    /**
     * 「几句话以上才发」。用户自己写，默认 50。
     *
     * 以前写死 3，还只能加减；退出再进来又变回 3 —— 那是它根本没存。
     * 现在存这一格，进来读到什么就是什么。
     */
    val aiGate: Int = 50,
    /**
     * 发给 AI 的那段「怎么分」的说明。空 = 用出厂那份。
     *
     * 书里那本自己还存一份（在 castJson 的 prompt 字段），优先级更高。
     * 用户在这本书里改过，就不动全局；点「存成默认」才写回这一格。
     */
    val aiPrompt: String = "",
    /**
     * 准 AI 自己造嗓音。
     *
     * 只有能造的那一路开着（目前是小米）才作数；没有这一格时派角永远是「从现成的里挑」。
     * 书里那张纸还能临时改，但**起手的那一档在这里定** —— 用户不想每本书都去点一次。
     */
    val aiDesign: Boolean = false,
    /**
     * 准它用的那几副嗓子，逗号分隔。空 = 库里有的全给。
     *
     * 存名字不存 id：嗓子是用户自己看名字认的，id 换了人还得重选一遍。
     */
    val aiVoices: String = "",
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

    /**
     * 声源表。**这一句会被界面每帧调到**，所以绝不许抛。
     *
     * 老存档里有几格是 Gson 塞进来的 null（见 EngineCfg.proto 的注释），
     * 一抛就是整页白屏、应用消失。这里兜一次，最坏退回内置那两副。
     */
    val engines: List<EngineCfg>
        get() {
            /*
             * 缓存一层。
             *
             * 这个 getter 一帧里会被叫好几次（阅读页每个 remember 的 key、
             * 播放器每一句的取音色、音色页的分组），每次都是「Gson 解析一整串 JSON」。
             * 滚动、翻页、朗读时这是白烧 CPU —— 4GB 的机器上就是掉帧和发烫。
             * 键就是那串 JSON 本身：内容没变就直接还上一次的结果。
             */
            val key = enginesJson
            EnginesCache.hit(key)?.let { return it }
            val v = runCatching { Engines.decode(key) }.getOrDefault(Engines.DEFAULT)
            EnginesCache.put(key, v)
            return v
        }

    /**
     * 接口表。跟 [engines] 一样要缓存 —— 这个 getter 也会被界面每帧叫到。
     */
    val chats: List<com.pagefold.reader.voice.ChatCfg>
        get() {
            val key = chatJson
            ChatCache.hit(key)?.let { return it }
            val v = runCatching { com.pagefold.reader.voice.Chats.decode(key) }
                .getOrDefault(com.pagefold.reader.voice.Chats.DEFAULT)
            ChatCache.put(key, v)
            return v
        }

    /**
     * 某一页的底子。空 = 跟着主题走。
     *
     * BgLayer 每帧都会调一次，所以解析结果要留住。每帧 Gson 解析一遍 bgJson，
     * 滑滑块的时候就是几十次每秒。
     */
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

/**
 * bgJson 的单槽解析缓存。
 *
 * 放在 data class 外面 —— Settings 每改一个字段就走一次 copy()，新对象里存缓存的话
 * 每帧都会丢。而 BgLayer 每帧都要问「这一页的底子是什么」。
 */
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

/**
 * 设置仓。写入即落盘，同时推给 UI。
 */
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

    /**
     * 读存档。
     *
     * Gson 反序列化不走 Kotlin 构造器，data class 的默认值全部失效：
     * 存档里没有的键会变成 null / 0 / false，不是 Settings() 里写的那些值。
     * 新加的 cue / next 落进旧存档，读出来就是 null，用的时候直接炸。
     *
     * 所以先用 Settings() 打底，再把存档里有的键盖上去。以后加字段不用写迁移。
     */
    private fun read(): Settings = runCatching {
        val raw = sp.getString("json", null) ?: return@runCatching Settings(uiRev = REV)
        val stored = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
            ?: return@runCatching Settings(uiRev = REV)

        val base = gson.toJsonTree(Settings(uiRev = REV)).asJsonObject
        stored.entrySet().forEach { (k, v) ->
            if (v != null && !v.isJsonNull && base.has(k)) base.add(k, v)
        }
        var out = gson.fromJson(base, Settings::class.java) ?: Settings(uiRev = REV)

        // 存档里原来写的是第几版
        val had = runCatching { stored.get("uiRev")?.asInt ?: 0 }.getOrDefault(0)

        if (had < 1) out = out.copy(dark = false, choseHome = false)
        // 二版起：嗓子一律先关着，由用户自己拨。钥匙地址都留着。
        if (had < 2) out = out.copy(enginesJson = Engines.encode(out.engines.map { it.copy(on = false) }))
        // 三版起：请求间隔不再是 2 秒。那个数配默认的 6 段预取等于自己把自己卡住。
        if (had < 3 && out.reqIntervalMs >= 2000) out = out.copy(reqIntervalMs = 600)
        out.copy(uiRev = REV)
    }.getOrDefault(Settings(uiRev = REV))

    /**
     * 写。内存立刻生效，落盘压到 300 毫秒后。
     *
     * 拖滑块的时候 onValueChange 是每帧一次，原来每帧都 Gson 序列化 + 写盘，
     * 一秒几十次 —— 这就是「特别卡」。现在攒一次再写。
     */
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
