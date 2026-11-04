package com.pagefold.reader.book

/**
 * 一段话该怎么念。
 *
 * 「上下文」不是把上一句一起发过去。真正要判的是：这一句是什么语气
 * —— 旁白、对白、嚷、耳语、问句、叹句。判出来再翻成引擎真能吃的东西：
 * 语速、音高、音量，外加念完停多久。
 *
 * 上下文只在这儿用，判完就只剩几个数字。请求里永远只有当前这一句正文，
 * 否则参考原文会被念出来，同一句话的缓存也会因为邻居改字而全废。
 *
 * 只看前后各一句，纯规则，不联网，不花钱，Edge 和 MiMo 都吃得上。
 */
data class Reading(
    val kind: Kind,
    val rate: Int,      // 相对正常语速的百分比
    val pitch: Int,     // Hz 偏移
    val volume: Int,    // 百分比偏移
    val pauseMs: Int,   // 这句念完停多久
    /**
     * 这一句该用的调门。
     *
     * 旁白一个、对白一个，空 = 跟随全局。
     * 这一格空着才回落到全局那一档；填了就以它为准。
     */
    val style: String = "",
) {
    enum class Kind(val cn: String) {
        NARRATE("旁白"),
        DIALOG("对白"),
        EXCLAIM("感叹"),
        ASK("问句"),
        SHOUT("嚷"),
        WHISPER("耳语"),
    }
}

object Cue {

    /** 六档情绪，用户自己挑的那一档。叠在段落判定之上。 */
    private val MOOD = mapOf(
        "平静" to Triple(0, 0, 0),
        "温和" to Triple(-4, -2, -1),
        "严肃" to Triple(-6, -5, 0),
        "激昂" to Triple(10, 7, 4),
        "悲伤" to Triple(-11, -6, -3),
        "欢快" to Triple(8, 5, 2),
        // MiMo 那边用的词，也认一认
        "生气" to Triple(8, 6, 5),
        "愤怒" to Triple(9, 7, 5),
        "恐惧" to Triple(4, 3, -3),
        "害怕" to Triple(4, 3, -3),
        "惊讶" to Triple(5, 6, 2),
        "悄悄话" to Triple(-12, -5, -9),
        "开心" to Triple(7, 4, 2),
    )

    private val Q = Regex("[「『\"“‘]")
    private val QEND = Regex("[」』\"”’]")
    private val SHOUT = Regex("吼|咆哮|怒喝|暴喝|厉声|喝道|吼道|大叫|嘶")
    private val WHISPER = Regex("低声|轻声|小声|耳语|压着嗓子|悄声|喃喃|呢喃")
    private val TAG = Regex("(说道|问道|答道|笑道|骂道|回道|应道|开口|沉声|低声)[：:，,]?\\s*$")

    /**
     * @param text  这一句
     * @param prev  前面一句（没有就 null）
     * @param next  后面一句（没有就 null）
     * @param mood  用户挑的情绪档
     * @param off   用户把段落判定关掉了
     */
    fun of(
        text: String,
        prev: String?,
        next: String?,
        mood: String,
        off: Boolean,
        style: String = "",
    ): Reading {
        if (off) return Reading(Reading.Kind.NARRATE, 0, 0, 0, 320, style)

        val t = text.trim()
        val head = t.take(24)

        val kind = when {
            WHISPER.containsMatchIn(head) -> Reading.Kind.WHISPER
            SHOUT.containsMatchIn(head) -> Reading.Kind.SHOUT
            t.endsWith("？") || t.endsWith("?") -> Reading.Kind.ASK
            t.endsWith("！") || t.endsWith("!") -> Reading.Kind.EXCLAIM
            Q.containsMatchIn(t) && QEND.containsMatchIn(t) -> Reading.Kind.DIALOG
            else -> Reading.Kind.NARRATE
        }

        var r = BASE.getValue(kind)

        // 上下文校准：单独一句对白听着会飘，前后有旁白托着才像念书
        val p = prev?.trim().orEmpty()
        val n = next?.trim().orEmpty()

        // 这句是「某某说道：」这种引子 —— 压低、拖慢，把话头递出去
        if (kind == Reading.Kind.NARRATE && t.length <= 14 && TAG.containsMatchIn(t)) {
            r = Reading(Reading.Kind.NARRATE, -5, -3, -2, 220)
        }
        // 上一句是引子、这句是对白 —— 接得紧一点
        if (kind == Reading.Kind.DIALOG && TAG.containsMatchIn(p)) {
            r = r.copy(pauseMs = 240)
        }
        // 下一句马上有人接话 —— 别停太久，像抢话
        if (n.length in 1..10 && Q.containsMatchIn(n)) {
            r = r.copy(pauseMs = (r.pauseMs * 0.6f).toInt().coerceAtLeast(150))
        }
        // 长段描写 —— 放慢一点点，别赶
        if (kind == Reading.Kind.NARRATE && t.length > 60) {
            r = r.copy(rate = r.rate - 3)
        }

        val (mr, mp, mv) = MOOD[mood.trim()] ?: Triple(0, 0, 0)
        return Reading(
            kind = kind,
            rate = (r.rate + mr).coerceIn(-45, 60),
            pitch = (r.pitch + mp).coerceIn(-30, 30),
            volume = (r.volume + mv).coerceIn(-18, 18),
            pauseMs = r.pauseMs,
            style = style.trim(),
        )
    }

    private val BASE: Map<Reading.Kind, Reading> = mapOf(
        Reading.Kind.NARRATE to Reading(Reading.Kind.NARRATE, 0, 0, 0, 360),
        Reading.Kind.DIALOG to Reading(Reading.Kind.DIALOG, 3, 2, 2, 300),
        Reading.Kind.EXCLAIM to Reading(Reading.Kind.EXCLAIM, 9, 6, 5, 280),
        Reading.Kind.ASK to Reading(Reading.Kind.ASK, 2, 5, 0, 320),
        Reading.Kind.SHOUT to Reading(Reading.Kind.SHOUT, 7, 8, 7, 250),
        Reading.Kind.WHISPER to Reading(Reading.Kind.WHISPER, -13, -6, -10, 420),
    )

    /**
     * 交给嗓源的那一句「怎么念」。只回一个词，没有就回空串。
     *
     * 这个返回值会被 [com.pagefold.reader.voice.MiMo] 当 user 消息发出去，
     * 模型有可能把它一并念进音频里。所以宁可短、宁可空，也不要一整句描述。
     */
    fun say(r: Reading, mood: String): String {
        val m = mood.trim()
        // 中性档不占位：发空串等于告诉对方「照常念」。
        if (m.isEmpty() || m == "平静" || m == "无") return ""

        /*
         * 按标点断开，只留第一个词。切出来的那段里但凡还带逗号顿号括号，
         * 说明用户填的是一句话而不是一个标签，那就整个不发。
         */
        val head = m.split('，', ',', '。', '；', ';', '：', ':', '、', '（', '(', ' ', '\n')
            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (head.isEmpty() || head.length > 4) return ""
        if (head.any { it in "，,。；;：:、（）()【】[]/\\" }) return ""
        return if (head == "平静" || head == "无" || head == "常速") "" else head
    }
}
