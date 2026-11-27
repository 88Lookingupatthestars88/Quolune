package com.pagefold.reader.book

/** 一段话该怎么念。三根轴都是连续值，播放侧再按块序抹平。 */
data class Reading(
    val kind: Kind,
    val rate: Int,      // 相对正常语速的百分比
    val pitch: Int,     // Hz 偏移
    val volume: Int,    // 百分比偏移
    val pauseMs: Int,   // 这块念完停多久
    /** 这一句该用的调门。旁白一个、对白一个，空 = 跟随全局。 */
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
     * 判这一块怎么念。
     *
     * @param text   这块要念的字
     * @param prev   前一块
     * @param next   后一块
     * @param mood   用户挑的情绪档
     * @param off    用户把段落判定关掉了
     * @param style  这副嗓子自己的调门
     * @param dialog 这块是对白。调用方从 [Line.isDialog] 传，比在剥掉引号的文本里找引号可靠。
     */
    fun of(
        text: String,
        prev: String?,
        next: String?,
        mood: String,
        off: Boolean,
        style: String = "",
        dialog: Boolean = false,
    ): Reading {
        if (off) return Reading(Reading.Kind.NARRATE, 0, 0, 0, 300, style)

        val t = text.trim()
        val head = t.take(24)
        /* 试听和预览没有行信息，只能退回看引号。 */
        val dlg = dialog || (Q.containsMatchIn(t) && QEND.containsMatchIn(t))

        val kind = when {
            WHISPER.containsMatchIn(head) -> Reading.Kind.WHISPER
            SHOUT.containsMatchIn(head) -> Reading.Kind.SHOUT
            !dlg -> Reading.Kind.NARRATE
            t.endsWith("？") || t.endsWith("?") -> Reading.Kind.ASK
            t.endsWith("！") || t.endsWith("!") -> Reading.Kind.EXCLAIM
            else -> Reading.Kind.DIALOG
        }

        var rate = 0
        var pitch = 0
        var vol = 0
        when (kind) {
            Reading.Kind.DIALOG -> { rate = 2; pitch = 2; vol = 2 }
            Reading.Kind.ASK -> { rate = 1; pitch = 5 }
            Reading.Kind.EXCLAIM -> { rate = 6; pitch = 5; vol = 4 }
            Reading.Kind.SHOUT -> { rate = 5; pitch = 7; vol = 6 }
            Reading.Kind.WHISPER -> { rate = -12; pitch = -5; vol = -9 }
            else -> {}
        }

        /* 句长：长描写放慢，短句收快一点。只差几格，不做台阶。 */
        when {
            t.length <= 8 -> rate += 3
            t.length > 60 -> rate -= 3
        }

        var pause = pauseOf(t)
        /* 引子压低、拖慢，把话头递给下一句。 */
        if (!dlg && t.length <= 14 && TAG.containsMatchIn(t)) {
            rate -= 5
            pitch -= 3
            pause = 220
        }
        /* 上一块是引子、这块接话：贴近。 */
        val p = prev?.trim().orEmpty()
        if (dlg && TAG.containsMatchIn(p)) pause = 240
        /* 下一块马上有人接：别停太久。 */
        val n = next?.trim().orEmpty()
        if (n.length in 1..12 && Q.containsMatchIn(n)) {
            pause = (pause * 0.7f).toInt().coerceAtLeast(150)
        }

        val (mr, mp, mv) = MOOD[mood.trim()] ?: Triple(0, 0, 0)
        return Reading(
            kind = kind,
            rate = (rate + mr).coerceIn(-45, 60),
            pitch = (pitch + mp).coerceIn(-30, 30),
            volume = (vol + mv).coerceIn(-18, 18),
            pauseMs = pause,
            style = style.trim(),
        )
    }

    /** 这块念完之后留多久。标点定档，不按语气类型乱跳。 */
    private fun pauseOf(t: String): Int = when {
        t.isEmpty() -> 300
        t.endsWith("…") || t.endsWith("⋯") -> 700
        t.endsWith("。") -> 420
        t.endsWith("！") || t.endsWith("？") || t.endsWith("!") || t.endsWith("?") -> 340
        t.endsWith("；") || t.endsWith(";") -> 300
        t.endsWith("，") || t.endsWith("、") || t.endsWith(",") -> 160
        else -> 300
    }

    /** 交给嗓源的那一句「怎么念」。中性档不占位：发空串等于告诉对方照常念。 */
    fun say(style: String): String {
        val m = style.trim()
        if (m.isEmpty() || m == "平静" || m == "无") return ""

        /* 切出来的那段里但凡还带逗号顿号括号，说明填的是一句话而不是一个标签，整个不发。 */
        val head = m.split('，', ',', '。', '；', ';', '：', ':', '、', '（', '(', ' ', '\n')
            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (head.isEmpty() || head.length > 4) return ""
        if (head.any { it in "，,。；;：:、（）()【】[]/\\" }) return ""
        return if (head == "平静" || head == "无" || head == "常速") "" else head
    }
}
