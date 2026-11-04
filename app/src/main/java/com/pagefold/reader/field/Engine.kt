package com.pagefold.reader.field

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 嗓子的来路。 */
enum class VoiceSource { MIMO, SYSTEM, OPENAI }

/**
 * 一副嗓子。
 *
 * 可以同时开好几副：音色池混在一起，分角色时 A 角色用这副、B 角色用那副。
 * 一段文字最后交给谁读，看的是**音色自己**属于哪副嗓子，不是全局开关。
 *
 * @param proto  走哪套协议。空 = 按 kind 推（见 [protocol]）。
 *               `mimo`   小米那套 chat/completions（音频藏在 message.audio 里）
 *               `speech` OpenAI 标准口：POST {base}/v1/audio/speech，直接回音频字节
 *               `system` 手机自带的 TTS
 * @param voices 这副嗓源底下有哪几个嗓音名，逗号分隔。
 *               空 = 没得挑（MiMo 用内置八副）。
 *               OpenAI / 硅基这类「音色就是一个词」的服务，填在这里，
 *               音色页就会照着列出来 —— 不用先手工造一遍音色。
 */
data class EngineCfg(
    val id: String,
    val name: String,
    val kind: String,
    val base: String = "",
    val key: String = "",
    val model: String = "",
    val on: Boolean = false,
    /**
     * 这两格**必须可以收 null**。
     *
     * Gson 反序列化不走 Kotlin 构造器，也不看可空性 —— 老存档里没有这两个键，
     * 它就照直往字段里塞 null。声明成 `String` 也挡不住，运行时照样是 null，
     * 于是一调 `proto.ifBlank{}` 就 NPE。
     * 「覆盖安装后第一次打开就闪退，删掉重装就好了」就是这么来的：
     * 新装的没有老存档，一个 null 都碰不上。
     */
    val proto: String? = null,
    val voices: String? = null,
    /**
     * 造嗓音用的那两个模型。只有小米这一家需要分开填。
     *
     * `voicedesign` 的 voice 是一段描述文字，`voiceclone` 的 voice 必须是一整串
     * data:audio/…;base64。三个用途各自锁各自的模型，混着填不会有任何报错 ——
     * 它只会静默降级，用默认声音念出来。所以在这里分成三格，各自能选、各自能拦。
     *
     * 同样要收 null：老存档没有这两个键，Gson 会照直塞 null。
     */
    val modelDesign: String? = null,
    val modelClone: String? = null,
) {
    val src: VoiceSource
        get() = runCatching { VoiceSource.valueOf(kind) }.getOrDefault(VoiceSource.OPENAI)

    /** 真正走的协议。老存档没有 proto，按 kind 推回去，行为不变。 */
    val protocol: String
        get() = (proto ?: "").ifBlank {
            when (src) {
                VoiceSource.MIMO -> "mimo"
                VoiceSource.SYSTEM -> "system"
                VoiceSource.OPENAI -> "speech"
            }
        }

    /** 这副嗓源底下的嗓音名。 */
    fun voiceList(): List<String> =
        (voices ?: "").split(',', '，', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 模型不能空。空着就用这份配置该有的那一个。 */
    fun modelOr(): String = (model ?: "").trim().ifBlank { Presets.defaultModel(base ?: "", protocol) }

    /** 用一句描述造一副新嗓音时用哪个模型。 */
    fun designOr(): String =
        (modelDesign ?: "").trim().ifBlank { Presets.defaultDesign(base ?: "", protocol) }

    /** 拿一段录音复刻嗓音时用哪个模型。 */
    fun cloneOr(): String =
        (modelClone ?: "").trim().ifBlank { Presets.defaultClone(base ?: "", protocol) }

    /** 界面上那句小字。不写解释，只写他现在什么状态。 */
    fun state(note: String): String = when {
        !on -> "关了"
        note.isNotBlank() -> note
        else -> ""
    }
}

/**
 * 一家服务的固定参数：地址、协议、模型清单、音色清单。
 *
 * 名字背后这几样要一次填对。缺一样就是一个空壳：地址协议模型全空，
 * 或者协议不对 —— 填了 tts-1 也一句话念不出来。
 *
 * @param proto  mimo / speech（OpenAI 标准 audio/speech）/ volc（火山豆包）
 * @param models 这家「模型」那格该填什么。火山那两个其实是 resource id。
 */
data class Preset(
    val key: String,
    val name: String,
    val base: String,
    val proto: String,
    val models: List<String>,
    val voices: List<String>,
    /** 造嗓音的模型。空 = 这家不能造，界面不给这一格。 */
    val design: List<String> = emptyList(),
    /** 复刻嗓音的模型。空 = 这家不能复刻。 */
    val clone: List<String> = emptyList(),
)

object Presets {

    val MIMO = Preset(
        key = "mimo", name = "MiMo", base = "https://api.xiaomimimo.com", proto = "mimo",
        // 朗读只放真能念的那一个。设计和克隆各有各的口，混在朗读里选就是自找静默降级。
        models = listOf("mimo-v2.5-tts"),
        voices = emptyList(),
        design = listOf("mimo-v2.5-tts-voicedesign"),
        clone = listOf("mimo-v2.5-tts-voiceclone"),
    )

    /** OpenAI 官方。标准 audio/speech 口。 */
    val OPENAI = Preset(
        key = "openai", name = "OpenAI", base = "https://api.openai.com", proto = "speech",
        models = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
        voices = listOf("alloy", "nova", "shimmer", "echo", "fable", "onyx", "sage", "coral"),
    )

    val SILICON = Preset(
        key = "silicon", name = "硅基流动", base = "https://api.siliconflow.cn", proto = "speech",
        models = listOf("FunAudioLLM/CosyVoice2-0.5B", "IndexTeam/IndexTTS-2", "fnlp/MOSS-TTSD-v0.5"),
        // 上传样本走 /v1/uploads/audio/voice，那个口只认 CosyVoice2。
        // 念的时候两个模型都能用 —— 所以「克隆用哪个」和「朗读用哪个」不是一回事。
        clone = listOf("FunAudioLLM/CosyVoice2-0.5B"),
        voices = listOf(
            "FunAudioLLM/CosyVoice2-0.5B:alex",
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            "FunAudioLLM/CosyVoice2-0.5B:bella",
            "FunAudioLLM/CosyVoice2-0.5B:claire",
            "FunAudioLLM/CosyVoice2-0.5B:benjamin",
            "FunAudioLLM/CosyVoice2-0.5B:charles",
            "FunAudioLLM/CosyVoice2-0.5B:david",
            "FunAudioLLM/CosyVoice2-0.5B:diana",
        ),
    )

    /**
     * 火山引擎 · 豆包语音。
     *
     * 它不走 OpenAI 兼容口。走的是 `openspeech.bytedance.com/api/v3/tts/unidirectional`：
     * 头是 `X-Api-Key` + `X-Api-Resource-Id`，回来的是按行分开的 JSON，音频在 `data` 里 base64。
     * 「模型」那一格填的是 resource id（seed-tts-2.0 / seed-icl-2.0），「音色」填 speaker id。
     * 截至 2026-11，这两个 resource id 与下面几个 speaker 是官方文档在用的；
     * 换代时查控制台 > 语音合成 > 音色库与 API Key 管理页。
     */
    val VOLC = Preset(
        key = "volc", name = "火山引擎", base = "https://openspeech.bytedance.com", proto = "volc",
        models = listOf("seed-tts-2.0", "seed-icl-2.0"),
        voices = listOf(
            "zh_female_vv_uranus_bigtts",
            "zh_male_liufei_uranus_bigtts",
            "zh_male_m191_uranus_bigtts",
            "zh_female_gaolengyujie_uranus_bigtts",
            "zh_male_xuanyijieshuo_uranus_bigtts",
            "zh_female_wenrouxiaoya_uranus_bigtts",
        ),
    )

    /**
     * MiniMax。中文小说最强的一档，而且情绪是**枚举字段**（emotion），
     * 不是往文本里塞自然语言 —— 不会多念出文章外的东西。
     *
     * 「模型」填 speech-2.8-hd / speech-2.8-turbo；「音色」填 voice_id，
     * 带 audiobook_ 前缀的那两个是它专为有声书做的。
     * 截至 2026-11 有效的地址与模型名；换代查 platform.minimaxi.com 的文档中心。
     */
    val MINIMAX = Preset(
        key = "minimax", name = "MiniMax", base = "https://api.minimaxi.com", proto = "minimax",
        models = listOf("speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-02-hd"),
        // 复刻是另一条路（/v1/files/upload → /v1/voice_clone），做完给个 voice_id，
        // 之后随便哪个朗读模型都能拿它念。
        clone = listOf("speech-02-hd"),
        voices = listOf(
            "audiobook_male_1", "audiobook_female_1",
            "male-qn-qingse", "male-qn-jingying", "male-qn-badao",
            "female-shaonv", "female-yujie", "female-chengshu",
            "presenter_male", "presenter_female",
            // 文档里点名过的几副系统音色，带空格的这些是它自己起的名字，原样填。
            "Chinese (Mandarin)_Lyrical_Voice",
            "Chinese (Mandarin)_HK_Flight_Attendant",
            "Cantonese_GentleLady",
            "English_Graceful_Lady",
        ),
    )

    /**
     * 阿里百炼 · 千问语音。
     *
     * 走的是 DashScope 的多模态生成口（不是 OpenAI 兼容），回来给一个音频 URL，要再下一次。
     * `qwen3-tts-flash` 单次 600 字符上限；我们按句切段，撞不上。
     * 开通后 90 天内 100 万字符免费（截至 2026-11）。
     */
    val QWEN = Preset(
        key = "qwen", name = "阿里百炼", base = "https://dashscope.aliyuncs.com", proto = "bailian",
        models = listOf("qwen3-tts-flash", "qwen3-tts-instruct-flash"),
        voices = listOf("Cherry", "Serena", "Ethan", "Chelsie", "Dylan", "Jada", "Sunny"),
    )


    /** 这几家的音色名直接写在嗓源上，音色页照着列。 */
    val WITH_VOICES = setOf("speech", "volc", "minimax", "bailian")

    /**
     * 按地址猜是谁家的，用来给「模型」那一栏列候选。
     *
     * 先按模板里写死的地址认；认不出再按域名关键字认一次 ——
     * 同一家常有新旧两个域名（minimax.cn / minimaxi.com，dashscope / bailian），
     * 只按一个字符串比，用户填了另一个就列不出候选。
     */
    fun byBase(base: String): Preset? {
        val b = base.trim().lowercase()
        if (b.isEmpty()) return null
        return when {
            b.contains("minimax") -> MINIMAX
            b.contains("dashscope") || b.contains("aliyuncs") || b.contains("bailian") -> QWEN
            b.contains("volces") || b.contains("openspeech") -> VOLC
            b.contains("siliconflow") -> SILICON
            b.contains("openai.com") -> OPENAI
            b.contains("xiaomimimo") || b.contains("mimo") -> MIMO
            else -> null
        }
    }

    /** 这一副嗓源该有的模型清单。认不出地址就看协议。 */
    fun modelsFor(base: String, proto: String): List<String> {
        byBase(base)?.let { return it.models }
        return when (proto) {
            "mimo" -> MIMO.models
            "volc" -> VOLC.models
            "minimax" -> MINIMAX.models
            "bailian" -> QWEN.models
            // 认不出的地址不给候选。宁可空着让人自己填，也不给一份猜的 —— 猜错只会 400。
            else -> emptyList()
        }
    }

    /**
     * 音色名里那个模型前缀，换成当前填的那个。
     *
     * 硅基这类家的音色名是一整串：「厂商/模型:名字」，比如
     * `FunAudioLLM/CosyVoice2-0.5B:alex`。前面那截就是**真正动手念的模型**。
     * 用户把朗读模型换成别的，这串不跟着变，对面照老模型念 ——
     * 于是「改了模型名，声音一模一样」，还找不出原因。
     *
     * 只动「前缀里带斜杠」的那种；`Cherry`、`male-qn-qingse` 这种一个词的
     * 音色名原样返回，MiniMax / 百炼 / OpenAI 都不受影响。
     */
    fun spokenToken(model: String, token: String): String {
        val m = model.trim()
        val t = token.trim()
        if (m.isEmpty() || t.isEmpty()) return t
        val i = t.indexOf(':')
        if (i <= 0) return t
        val head = t.substring(0, i)
        if (!head.contains('/')) return t
        return if (head == m) t else m + t.substring(i)
    }

    fun defaultModel(base: String, proto: String): String =
        modelsFor(base, proto).firstOrNull().orEmpty()

    fun designFor(base: String, proto: String): List<String> {
        byBase(base)?.let { return it.design }
        return when (proto) {
            "mimo" -> MIMO.design
            else -> emptyList()
        }
    }

    fun cloneFor(base: String, proto: String): List<String> {
        byBase(base)?.let { return it.clone }
        return when (proto) {
            "mimo" -> MIMO.clone
            "speech" -> SILICON.clone
            "minimax" -> MINIMAX.clone
            else -> emptyList()
        }
    }

    fun defaultDesign(base: String, proto: String): String =
        designFor(base, proto).firstOrNull().orEmpty()

    fun defaultClone(base: String, proto: String): String =
        cloneFor(base, proto).firstOrNull().orEmpty()

    fun voicesFor(base: String, proto: String = ""): List<String> {
        byBase(base)?.let { return it.voices }
        return when (proto) {
            "mimo" -> MIMO.voices
            "volc" -> VOLC.voices
            "minimax" -> MINIMAX.voices
            "bailian" -> QWEN.voices
            else -> emptyList()
        }
    }
}

/**
 * 嗓子仓。跟设置一起走 JSON，加字段不迁移。
 * 别家从「添一副」里挑，按模板建，不留空壳。
 */
object Engines {

    private val gson = Gson()

    /**
     * 声源表里永远在的七条，默认全关着。
     *
     * 地址和模型都填好了，缺的只有钥匙。开关打开、钥匙填上，就能念。
     */
    /** 出厂那一排嗓源。全部默认关着，都要自己的钥匙。 */
    val DEFAULT: List<EngineCfg> = listOf(
        /*
         * MiMo 的音色不写在这格 —— 它要带「怎么说」的说明词，走 Voice.kt 里那张表。
         * 写在这里会被当成一排干名字，念起来没有语气。
         */
        EngineCfg("mimo", "MiMo", VoiceSource.MIMO.name, base = "https://api.xiaomimimo.com", model = "mimo-v2.5-tts", proto = "mimo"),
        EngineCfg("openai", "OpenAI", VoiceSource.OPENAI.name, base = "https://api.openai.com", model = "gpt-4o-mini-tts", proto = "speech",
            voices = Presets.OPENAI.voices.joinToString(",")),
        EngineCfg("silicon", "硅基流动", VoiceSource.OPENAI.name, base = "https://api.siliconflow.cn", model = "FunAudioLLM/CosyVoice2-0.5B", proto = "speech",
            voices = Presets.SILICON.voices.joinToString(",")),
        EngineCfg("volc", "火山引擎", VoiceSource.OPENAI.name, base = "https://openspeech.bytedance.com", model = "seed-tts-2.0", proto = "volc",
            voices = Presets.VOLC.voices.joinToString(",")),
        EngineCfg("minimax", "MiniMax", VoiceSource.OPENAI.name, base = "https://api.minimaxi.com", model = "speech-2.8-hd", proto = "minimax",
            voices = Presets.MINIMAX.voices.joinToString(",")),
        EngineCfg("bailian", "阿里百炼", VoiceSource.OPENAI.name, base = "https://dashscope.aliyuncs.com", model = "qwen3-tts-flash", proto = "bailian",
            voices = Presets.QWEN.voices.joinToString(",")),
    )

    fun decode(raw: String?): List<EngineCfg> {
        if (raw.isNullOrBlank()) return DEFAULT
        val t = object : TypeToken<List<EngineCfg>>() {}.type
        val list = runCatching { gson.fromJson<List<EngineCfg>>(raw, t) }.getOrNull()
        if (list.isNullOrEmpty()) return DEFAULT
        // 老存档里的 Edge 条目已下架，读的时候丢掉。
        val out = list.filterNot { it.id == "edge" || it.protocol == "edge" }.toMutableList()
        DEFAULT.forEach { d -> if (out.none { it.id == d.id }) out.add(d) }
        // 模型不许空：空着的按地址补一个，同时把协议补齐。
        // 每条各包一层 —— 一条坏数据不该让整个声源表读不出来。
        return out.mapNotNull { e ->
            runCatching {
                val p = e.protocol
                /*
                 * 老存档里没有音色这一串 —— 那时候压根没往 DEFAULT 里写。
                 * 结果就是：开着硅基、钥匙也对，音色页里却一片空白，
                 * 播放时找不到能念的那副，看着像「这家不能用」。
                 *
                 * 所以读到空的时候，按地址回预设里补一份。用户自己改过的（非空）
                 * 一个字都不碰。
                 */
                val v = e.voices
                val filled = if (v.isNullOrBlank()) {
                    Presets.voicesFor(e.base ?: "", p).joinToString(",").ifBlank { null }
                } else v
                /*
                 * 模型只补 null，不补默认值。写成 e.modelOr() 是清不掉的原因：
                 * 删空之后落盘是空，下次读出来又被填回默认值，界面像根本没改。
                 *
                 * 默认值改成用的时候才取（见 [EngineCfg.modelOr]）。
                 */
                e.copy(proto = p, model = (e.model ?: "").trim(), voices = filled)
            }.getOrNull()
        }.ifEmpty { DEFAULT }
    }

    fun encode(list: List<EngineCfg>): String = gson.toJson(list)

    fun open(list: List<EngineCfg>): List<EngineCfg> = list.filter { it.on }

    fun first(list: List<EngineCfg>, kind: VoiceSource): EngineCfg? =
        list.firstOrNull { it.src == kind && it.on } ?: list.firstOrNull { it.src == kind }

    /**
     * 这副能不能真念出声。返回一句人话，null = 能用。
     *
     * 模型空着判「不能用」：留空发出去，对面回 400，界面上只说「取出声来」，
     * 看不出是这一格没填。
     */
    fun blocked(e: EngineCfg): String? {
        if (!e.on) return null
        if (e.protocol == "system") return null
        if (e.base.isNullOrBlank()) return "还没填接口地址"
        if (e.key.isNullOrBlank()) return "还没填钥匙"
        // 判原始值：modelOr() 会补默认，拿它判等于永远不空。
        if ((e.model ?: "").trim().isBlank()) return "还没填模型"
        return null
    }
}
