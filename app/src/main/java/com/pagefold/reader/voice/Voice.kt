package com.pagefold.reader.voice

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 一个可用的音色。
 * param 的含义随 kind 变化：
 *   PRESET / DESIGN / CLONE → MiMo 的 voice 字段（预设名或描述原文）
 *   SYSTEM                  → 系统 TTS 的 voice name
 */
data class Voice(
    val id: String,
    val name: String,
    val kind: String,          // preset / design / clone / system
    val engine: String,        // mimo / system / 嗓源 id
    val param: String,
    val desc: String = "",
    val gender: String = "",   // f / m / ""
    val age: String = "",      // young / middle / old
    val tags: List<String> = emptyList(),
    val builtin: Boolean = false,
) {
    val modelId: String
        get() = when (kind) {
            "design" -> "mimo-v2.5-tts-voicedesign"
            "clone" -> "mimo-v2.5-tts-voiceclone"
            else -> "mimo-v2.5-tts"
        }

    /** 自建音色不读 style 参数，永远一个调；只有预设会随情绪变。 */
    val reactsToStyle: Boolean get() = kind == "preset"
}

/** 音色仓：内置目录 + 用户导入，存成一个 JSON。 */
class VoiceBox(ctx: Context) {
    private val f = File(ctx.filesDir, "voices.json")
    private val gson = Gson()

    /**
     * 内存里缓存的那一份。
     *
     * 播放时每一句都要重算音色池，每次都读文件、跑 Gson 就是每句两次磁盘读。
     * 文件只有这个类在写，写完顺手更新缓存。
     */
    @Volatile private var mem: List<Voice>? = null

    /**
     * 用户自己那几副。
     *
     * 同样要兜 null：老 voices.json 里可能没有 desc / gender / age / tags / builtin，
     * Gson 会给它们塞 null，之后 `v.tags.joinToString()` 就是 NPE ——
     * 也就是「配好音色包之后莫名闪一下」那一类。
     */
    fun custom(): List<Voice> {
        mem?.let { return it }
        val list = runCatching {
            if (!f.exists()) return@runCatching emptyList()
            val t = object : TypeToken<List<Voice>>() {}.type
            val raw = gson.fromJson<List<Voice>>(f.readText(), t) ?: emptyList()
            raw.mapNotNull { v ->
                runCatching {
                    v.copy(
                        id = v.id ?: "",
                        name = v.name ?: "",
                        kind = v.kind ?: "preset",
                        engine = v.engine ?: "mimo",
                        param = v.param ?: "",
                        desc = v.desc ?: "",
                        gender = v.gender ?: "",
                        age = v.age ?: "",
                        tags = v.tags ?: emptyList(),
                    )
                }.getOrNull()?.takeIf { it.name.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        mem = list
        return list
    }

    fun save(list: List<Voice>) {
        mem = list
        runCatching { f.writeText(gson.toJson(list)) }
    }

    fun add(v: Voice): List<Voice> {
        val list = custom().toMutableList()
        if (list.none { it.id == v.id }) list.add(v)
        save(list)
        return list
    }

    fun remove(id: String): List<Voice> {
        val list = custom().filterNot { it.id == id }
        save(list)
        return list
    }

    /**
     * 导入一包。
     *
     * 认的写法见 [Pack] —— 咱自己做的包、别家软件导出的包、纯数组、
     * 包了一层 { voices: [...] }，都能读进来。内置已有的按名字合并，
     * 不会长出重影。
     */
    fun import(raw: String): Pair<Int, String> {
        val (incoming, note) = Pack.read(raw)
        if (incoming.isEmpty()) return 0 to note

        val cur = custom().toMutableList()
        val builtinNames = Catalog.MIMO.map { it.name + "/" + it.engine }.toSet()
        var n = 0
        var dup = 0
        incoming.forEach { v ->
            val sig = v.name + "/" + v.engine
            val exists = builtinNames.contains(sig) ||
                cur.any { it.name == v.name && it.engine == v.engine }
            if (exists) {
                dup++
            } else {
                cur.add(v); n++
            }
        }
        save(cur)
        return n to when {
            n > 0 && dup > 0 -> "收下 $n 副，另有 $dup 副已经有了"
            n > 0 -> "收下 $n 副"
            else -> note + "，都已经有了"
        }
    }

    /** 只导自建。 */
    fun export(): String = Pack.write(custom())

    /** 连内置一起导 —— 换手机、重装，不必再一个个找回来。 */
    fun exportAll(): String = Pack.write(Catalog.MIMO + custom())

}

/** 内置音色目录。不落盘，每次拼在自定义前面。 */
object Catalog {

    val MIMO: List<Voice> = listOf(
        Voice("mimo_bingtang", "冰糖", "preset", "mimo", "冰糖", "甜美活泼的少女音", "f", "young", listOf("甜", "少女"), true),
        Voice("mimo_moli", "茉莉", "preset", "mimo", "茉莉", "温柔知性的女声", "f", "middle", listOf("知性", "温柔"), true),
        Voice("mimo_suda", "苏打", "preset", "mimo", "苏打", "阳光有活力的少年音", "m", "young", listOf("少年", "阳光"), true),
        Voice("mimo_baihua", "白桦", "preset", "mimo", "白桦", "沉稳磁性的成熟男声", "m", "middle", listOf("成熟", "磁性"), true),
        Voice("mimo_mia", "Mia", "preset", "mimo", "Mia", "English female", "f", "young", listOf("en"), true),
        Voice("mimo_chloe", "Chloe", "preset", "mimo", "Chloe", "English female", "f", "young", listOf("en"), true),
        Voice("mimo_milo", "Milo", "preset", "mimo", "Milo", "English male", "m", "young", listOf("en"), true),
        Voice("mimo_dean", "Dean", "preset", "mimo", "Dean", "English male", "m", "middle", listOf("en"), true),
    )

    fun all(custom: List<Voice>): List<Voice> = MIMO + custom

    /**
     * 现在真正能用的那些音色。
     *
     * 只看开着的嗓子：MiMo 开着才有那 8 副，本机开着才有手机里的，
     * 一副没开就是空。这样音色页上列出来的，每一副都真能响。
     *
     * @param sys 本机语音包（调用方自己读好了传进来；系统 TTS 那条路上传空，避免自己调自己）
     */
    fun ours(
        engines: List<com.pagefold.reader.field.EngineCfg>,
        custom: List<Voice>,
        sys: List<Voice> = emptyList(),
    ): List<Voice> {
        val on = engines.filter { it.on }.map { it.src }.toSet()
        if (on.isEmpty()) return emptyList()
        val out = ArrayList<Voice>(24)
        if (com.pagefold.reader.field.VoiceSource.MIMO in on) {
            out += MIMO.filter { it.engine == "mimo" }
            out += custom.filter { it.engine == "mimo" }
        }
        // 「添一副」建出来的嗓源：音色名直接写在嗓源上（OpenAI 的 alloy、硅基的 alex…），
        // 这里照着列出来。不然一副新嗓源开起来是空的，得先手工造音色才能听 —— 那道坎不该有。
        engines.filter { it.on && it.protocol in com.pagefold.reader.field.Presets.WITH_VOICES }.forEach { e ->
            e.voiceList().forEach { tok -> out += spoken(e, tok) }
            out += custom.filter { it.engine == e.id }
        }
        if (com.pagefold.reader.field.VoiceSource.OPENAI in on) {
            out += custom.filter { it.engine != "mimo" && it.engine != "system" }
        }
        if (com.pagefold.reader.field.VoiceSource.SYSTEM in on) out += sys
        return out
    }

    /** 按嗓子分组，给界面用。没开的不出现。 */
    fun groups(
        engines: List<com.pagefold.reader.field.EngineCfg>,
        custom: List<Voice>,
        sys: List<Voice> = emptyList(),
    ): List<Pair<String, List<Voice>>> {
        /*
         * 模型空着的一副不列音色。音色挂在这副嗓源上，模型没填，点了也念不出来。
         * 列出来还会让卡片写「8 个」，看着像清掉模型没生效。
         */
        val on = engines.filter {
            it.on && (it.protocol == "system" || (it.model ?: "").isNotBlank())
        }
        if (on.isEmpty()) return emptyList()
        return on.map { e ->
            val mine = when (e.src) {
                com.pagefold.reader.field.VoiceSource.MIMO ->
                    MIMO.filter { it.engine == "mimo" } + custom.filter { it.engine == "mimo" }
                com.pagefold.reader.field.VoiceSource.SYSTEM -> sys
                com.pagefold.reader.field.VoiceSource.OPENAI ->
                    custom.filter { it.engine != "mimo" && it.engine != "system" }
            }
            // 标准口的嗓源自己带着音色名，一并列出来；它底下自造的音色也挂这里。
            val mine2 = if (e.protocol in com.pagefold.reader.field.Presets.WITH_VOICES) {
                e.voiceList().map { spoken(e, it) } + custom.filter { it.engine == e.id }
            } else mine
            e.name to mine2
        }.filter { it.second.isNotEmpty() }
    }

    /** 一副「标准口」音色：名字就是一个词，直接当 voice 发出去。 */
    private fun spoken(e: com.pagefold.reader.field.EngineCfg, token: String): Voice {
        // 名字里那截模型前缀要跟着当前填的模型走，否则换了模型还是老声音。
        val t = com.pagefold.reader.field.Presets.spokenToken(e.modelOr(), token)
        return Voice(
            id = "s_" + e.id + "_" + t,
            name = label(t),
            kind = "spoken",
            engine = e.id,
            param = t,
            /*
             * 描述留空。这一行的第二排已经有「家里名字 + 描述」两块，
             * 这里再写一遍家名，屏幕上就是「硅基流动 硅基流动」。
             */
            desc = "",
            builtin = true,
        )
    }

    /**
     * 音色名太长，界面上得给它一个能读的写法。
     *
     * 硅基的音色名是一整串「厂商/模型:名字」，`alex` 那一截才是人认得出的一半；
     * 光写 `FunAudioLLM/CosyVoice2-0.5B:alex`，一屏放不下三个。
     * 但英文名也不能删 —— 它对日志、对报错、对别人问「你用的哪个」的时候是唯一能对上的东西，
     * 所以两个都留着：「沉稳男声 alex」。
     */
    private fun label(token: String): String {
        val t = token.trim()
        val bare = if (t.contains(':')) t.substringAfterLast(':').trim() else t
        val nice = NICE[bare.lowercase()] ?: return bare
        return "$nice $bare"
    }

    /** 官方音色表上写着的中文名。只有查得到的才写，查不到的显示原名。 */
    private val NICE = mapOf(
        "alex" to "沉稳男声",
        "benjamin" to "低沉男声",
        "charles" to "磁性男声",
        "david" to "欢快男声",
        "anna" to "沉稳女声",
        "bella" to "激情女声",
        "claire" to "温柔女声",
        "diana" to "欢快女声",
    )

    fun byName(list: List<Voice>, name: String): Voice? =
        list.firstOrNull { it.name == name } ?: list.firstOrNull { it.param == name } ?: list.firstOrNull { it.id == name }
}
