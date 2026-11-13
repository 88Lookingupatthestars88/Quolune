package com.pagefold.reader.field

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 一副音色自己的调法。
 * @param mood     情绪档。空 = 跟随全局那一档。
 * @param lang     语种。只有百炼认这一格。
 * @param bright   低沉 ↔ 明亮。MiniMax 的 voice_modify.pitch，-100…100。
 * @param soft     刚劲 ↔ 轻柔。MiniMax 的 voice_modify.intensity。
 * @param clear    浑厚 ↔ 清脆。MiniMax 的 voice_modify.timbre。
 * @param fx       音效，空 = 不加。MiniMax 的 sound_effects。
 * @param dialect  方言，空 = 不指定。火山的 explicit_dialect。
 * @param silence  段尾留多少静音，毫秒。火山的 silence_duration。
 */
data class Vt(
    val speed: Int = 100,
    val pitch: Int = 100,
    val volume: Int = 100,
    val mood: String = "",
    val lang: String = "",
    val bright: Int = 100,
    val soft: Int = 100,
    val clear: Int = 100,
    val fx: String = "",
    val dialect: String = "",
    val silence: Int = 0,
    /** 采样温度 ×100：0–100 就是 0.00–1.00。50 = 0.50。 */
    val temp: Int = 50,
    /** 语种增强。MiniMax 的 language_boost，空 = 不指定。 */
    val boost: String = "",
    /** 文本归一化。MiniMax 的 basic / quality，空 = 不传。 */
    val norm: String = "",
) {
    /** 一格都没动过。这种不落盘，永远跟着全局走。 */
    val virgin: Boolean get() = this == Vt()
}

/**
 * 音色调法仓。跟 [Tune] 一个思路：没调过的音色根本不进这张表，所以外面改了全局，它立刻跟着变。
 * 调过的才留一行，键是音色 id。
 */
class VoiceTune(ctx: Context) {

    private val f = File(ctx.filesDir, "voice-tune.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, Vt>>() {}.type

    /** 播放时每一句都要查一次，读文件太贵。这个类只有自己在写，写完顺手更新。 */
    @Volatile private var mem: MutableMap<String, Vt>? = null

    private fun all(): MutableMap<String, Vt> {
        mem?.let { return it }
        val m = runCatching {
            if (!f.exists()) return@runCatching mutableMapOf<String, Vt>()
            gson.fromJson<MutableMap<String, Vt>>(f.readText(), type)
                ?: mutableMapOf()
        }.getOrDefault(mutableMapOf())
        /* 老存档里没有后来才加的那几个键，Gson 不走 Kotlin 构造器、也不看可空性，照直往字段里塞 null —— 一读就是 NPE。 */
        val clean = m.mapValues { (_, v) ->
            v.copy(
                mood = v.mood ?: "",
                lang = v.lang ?: "",
                fx = v.fx ?: "",
                dialect = v.dialect ?: "",
            )
        }.toMutableMap()
        mem = clean
        return clean
    }

    /** 这一副调过没有。 */
    fun has(id: String): Boolean = all().containsKey(id)

    /** 这一副调成什么样。没调过就是原样。 */
    fun of(id: String): Vt = all()[id] ?: Vt()

    /** 存一副。存回原样等于「没调过」—— 直接把这一行摘掉，让它重新跟着全局走。 */
    fun put(id: String, v: Vt) {
        val m = all()
        if (v.virgin) m.remove(id) else m[id] = v
        runCatching { f.writeText(gson.toJson(m)) }
    }

    /** 音色没了，它那份调法也别留着。 */
    fun drop(id: String) {
        val m = all()
        if (m.remove(id) == null) return
        runCatching { f.writeText(gson.toJson(m)) }
    }
}

/** 音色卡上能调哪几样。 */
enum class Knob(val cn: String) {
    SPEED("念多快"),
    PITCH("音调"),
    VOLUME("音量"),
    MOOD("调门"),
    LANG("语种"),
    BRIGHT("低沉 ↔ 明亮"),
    SOFT("刚劲 ↔ 轻柔"),
    CLEAR("浑厚 ↔ 清脆"),
    FX("音效"),
    DIALECT("方言"),
    SILENCE("段尾留白"),
    TEMP("采样温度"),
    BOOST("语种增强"),
    NORM("文本归一化"),
}

/**
 * 一套协议能调哪几样。
 * @param model 有的家看模型分能力：小米的 voicedesign 不接受 voice，
 * @param kind  造出来的音色只认那句描述，复刻的只认那段录音 —— 这两类不给调。
 */
object Caps {

    fun langOptions(): List<String> = listOf(
        "Chinese", "Auto", "English", "Japanese", "Korean",
        "German", "French", "Russian", "Italian", "Portuguese", "Spanish",
    )

    fun fxOptions(): List<Pair<String, String>> = listOf(
        "spacious_echo" to "空旷回音",
        "auditorium_echo" to "礼堂广播",
        "lofi_telephone" to "电话失真",
        "robotic" to "电音",
    )

    fun dialectOptions(): List<Pair<String, String>> = listOf(
        "beijing" to "北京话",
        "dongbei" to "东北话",
        "henan" to "河南话",
        "shaanxi" to "陕西话",
        "shanghai" to "上海话",
        "sichuan" to "四川话",
        "tianjin" to "天津话",
        "yue" to "粤语",
    )

    fun of(proto: String, model: String, kind: String = ""): List<Knob> {
        val m = model.lowercase()
        /* 造出来的音色：声音是那句描述生成的，但快慢、音调、温度它照样认 ——这几样是跟那句描述一起从同一条 user 消息发过去的，不冲突。 */
        if (kind == "design" || m.contains("voicedesign")) {
            return if (proto == "mimo") listOf(Knob.SPEED, Knob.PITCH, Knob.TEMP) else emptyList()
        }
        /* 小米的复刻音色更要紧：它的「快慢」只能靠一条 user 消息表达，而复刻音色发 user 正是「开头多一句话」的入口。 */
        if (proto == "mimo" && kind == "clone") return emptyList()
        return when (proto) {
            "system" -> listOf(Knob.SPEED, Knob.PITCH)
            // 小米没有数值字段，但官方示例就是拿 user 消息说「快一点、高一点」。
            "mimo" -> listOf(Knob.SPEED, Knob.PITCH, Knob.MOOD, Knob.TEMP)
            // 标准口：speed 一定有；gain 是硅基的，gpt-4o 多一个 instructions。
            "speech" -> buildList {
                add(Knob.SPEED)
                if (isSilicon(m)) add(Knob.VOLUME)
                if (m.contains("gpt-4o")) add(Knob.MOOD)
            }
            // 这一家给得最多：四格基础 + 三个效果器 + 音效。
            "minimax" -> listOf(
                Knob.SPEED, Knob.PITCH, Knob.VOLUME, Knob.MOOD,
                Knob.BRIGHT, Knob.SOFT, Knob.CLEAR, Knob.FX,
                Knob.BOOST, Knob.NORM,
            )
            "volc" -> listOf(
                Knob.SPEED, Knob.PITCH, Knob.VOLUME, Knob.MOOD,
                Knob.DIALECT, Knob.SILENCE,
            )
            "bailian" -> listOf(Knob.LANG)
            else -> emptyList()
        }
    }

    /** 硅基的音色名带模型前缀、模型名带斜杠，拿这个认；OpenAI 的不带。 */
    fun isSilicon(model: String): Boolean =
        model.contains("silicon") || model.contains("cosyvoice") || model.contains("moss") ||
            model.contains("indextts") || model.contains('/')

    /** 这一家能给哪几样，一句写完，给分组标题右边用。 */
    fun brief(proto: String, model: String): String = when (proto) {
        "system" -> "快慢 · 音调"
        "mimo" -> "快慢 · 音调 · 调门 · 温度"
        "speech" -> if (isSilicon(model)) "快慢 · 增益" else "快慢"
        "minimax" -> "快慢 · 音调 · 轻重 · 质地 · 音效"
        "volc" -> "快慢 · 音调 · 轻重 · 方言"
        "bailian" -> "语种"
        else -> ""
    }

    /** 这一副为什么没法调。界面上写一行字，别让人以为是坏了。 */
    fun why(proto: String, model: String, kind: String = ""): String {
        val m = model.lowercase()
        if (kind == "design" || m.contains("voicedesign")) {
            return if (proto == "mimo") "" else "造音色的模型只认那句描述，声音是描述生成的"
        }
        if (proto == "mimo" && kind == "clone") return "复刻的音色只认那段录音，快慢和语气不给调"
        return when (proto) {
            "bailian" -> "这家只认文字、音色、语种三样，快慢交给播放器"
            "speech" -> if (isSilicon(m)) "这家没有音调，只有快慢和增益" else ""
            else -> ""
        }
    }
}
