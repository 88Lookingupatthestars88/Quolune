package com.pagefold.reader.book

/**
* 一本书的写法。数人之前先量一遍，判据按这个分档走 ——白描靠段首主语，剧本靠冒号，传统网文靠引导语，长篇噪声多要收紧。
*
* @param n       全书字数
* @param lead    引号前有「名字 + 说话动词」的比例（引导语在前）
* @param trail   引号后有这个的比例（引导语在后）
* @param colon   引号前是「名字：」的比例（剧本体）
* @param bare    三样都没有的比例（白描体）
* @param para    平均段长
*/
data class Shape(
    val n: Int = 0,
    val lead: Double = 0.0,
    val trail: Double = 0.0,
    val colon: Double = 0.0,
    val bare: Double = 0.0,
    val para: Double = 40.0,
) {
    /** 短篇。人物一辈子只说一两回，判据得松。 */
    val small: Boolean get() = n < 80_000

    /** 长篇。候选上万，噪声跟着涨，判据得紧。 */
    val huge: Boolean get() = n >= 1_000_000

    /** 剧本体：一句一个「名字：」。 */
    val script: Boolean get() = colon >= 0.35

    /** 白描体：整段没有引导语，全靠动作交代谁在说。 */
    val plain: Boolean get() = bare >= 0.45 && colon < 0.15

    /** 说话人的最少次数。短篇一次也算，长篇要两次。 */
    val speakMin: Int get() = if (small) 1 else 2

    /** 过关要求的右邻字种数。剧本体右邻永远只有「：」，按最低的来。 */
    val rightMin: Int get() = if (script || small) 2 else 3

    /** 句界比例门槛。长篇收紧。 */
    val nearMin: Double get() = if (huge) 0.35 else 0.30

    /** 段首出场要几次才算数。 */
    val headMin: Int get() = if (huge) 2 else 1

    /** 段首那只右邻字要几种。 */
    val headRight: Int get() = if (small) 2 else 3

    companion object {

        /** 只量开头这么多字。一本书的写法是均匀的，全书扫一遍没意义。 */
        private const val SPAN = 80_000

        private const val OPEN_Q = "\u201c\u300c\u300e\u0022"

        /** 量这本书：数一段对白的引导语落在哪儿 —— 在引号前面（传统）、在引号后面（倒装）、只有「名字：」（剧本）、或者压根没有（白描）。 */
        fun of(text: String): Shape {
            val n = text.length
            if (n < 200) return Shape(n = n)
            val end = minOf(n, SPAN)

            val advLens = Words.advs.map { it.length }.distinct().sortedDescending()
            val sayHeads = Words.verbs.filter { it.isNotEmpty() }.groupBy { it[0] }
                .mapValues { (_, v) -> v.sortedByDescending { it.length } }
            val sayOne: Set<Char> = Words.verbs1.mapNotNull { it.firstOrNull() }.toSet()

            fun han(c: Char) = c in '\u4e00'..'\u9fa5'

            /** [p] 起是不是一个说类动词。 */
            fun verbAt(p: Int): Boolean {
                if (p >= end) return false
                val c = text[p]
                sayHeads[c]?.forEach { v -> if (text.startsWith(v, p)) return true }
                if (c in sayOne) {
                    val q = p + 1
                    return q >= end || text[q] !in '\u4e00'..'\u9fa5'
                }
                return false
            }

            /** [lo, hi) 里有没有说类动词。 */
            fun sayIn(lo: Int, hi: Int): Boolean {
                var k = maxOf(lo, 0)
                val e = minOf(hi, end)
                while (k < e) {
                    if (verbAt(k)) return true
                    k++
                }
                return false
            }

            /** [lo, hi) 里有没有「两到四个汉字 + 冒号」。 */
            fun colonIn(lo: Int, hi: Int): Boolean {
                var k = maxOf(lo, 0)
                val e = minOf(hi, end)
                while (k < e) {
                    if (text[k] == '：' || text[k] == ':') {
                        var len = 0
                        var s = k
                        while (len < 4 && s > 0 && han(text[s - 1])) { s--; len++ }
                        if (len >= 2) return true
                    }
                    k++
                }
                return false
            }

            var quotes = 0
            var lead = 0
            var trail = 0
            var colon = 0
            var bare = 0
            var paras = 0
            var paraChars = 0
            var lineStart = 0

            var i = 0
            while (i < end) {
                val c = text[i]
                if (c == '\n') {
                    if (i > lineStart) { paras++; paraChars += i - lineStart }
                    lineStart = i + 1
                    i++
                    continue
                }
                if (c !in OPEN_Q) { i++; continue }
                quotes++
                val hasColon = colonIn(i - 12, i)
                val hasLead = sayIn(i - 30, i)
                val close = when (c) {
                    '\u201c' -> '\u201d'
                    '\u300c' -> '\u300d'
                    '\u300e' -> '\u300f'
                    else -> '"'
                }
                var e = i + 1
                while (e < end && text[e] != close && text[e] != '\n') e++
                val hasTrail = sayIn(e + 1, minOf(e + 30, end)) || sayIn(i + 1, minOf(i + 16, e))
                when {
                    hasColon -> colon++
                    hasLead -> lead++
                    hasTrail -> trail++
                    else -> bare++
                }
                i = if (e < end) e + 1 else e
            }
            if (end > lineStart) { paras++; paraChars += end - lineStart }

            val para = if (paras > 0) paraChars.toDouble() / paras else 40.0
            if (quotes < 8) return Shape(n = n, para = para)
            val q = quotes.toDouble()
            return Shape(
                n = n,
                lead = lead / q,
                trail = trail / q,
                colon = colon / q,
                bare = bare / q,
                para = para,
            )
        }
    }
}
