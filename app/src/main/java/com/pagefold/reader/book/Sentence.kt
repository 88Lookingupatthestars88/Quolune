package com.pagefold.reader.book

/** 一句话：正文里从哪到哪，写的是什么。区间用章内偏移，跟 [Line.srcStart] 同一把尺子。 */
data class Sentence(
    val srcStart: Int,
    val srcEnd: Int,
    val text: String,
)

/**
 * 把 [Line] 再切成句子。只管显示与跳转，不参与认人，也不影响合成块。
 * 不跨行取字；`srcStart` 严格递增，[at] 的二分靠它；不产空句。
 */
object Sentences {

    /** 句末。分号与省略号也算：一整段常靠它们分句。 */
    private const val STOPS = "。！？…；!?;"

    /** 收尾符号：句末标点后面的引号、括号并进这一句，别让下一句从引号开头。 */
    private const val TAILS = "」』”’）)》〉】"

    /** 短于这个字数的并进前一句。`「是。」` 单独成句，念出来没有意义。 */
    private const val MIN = 4

    fun of(lines: List<Line>): List<Sentence> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<Sentence>(lines.size * 2)

        for (l in lines) {
            val t = l.text
            if (t.isBlank()) continue
            /* 起点是从章首数过来的偏移。没填的（-1）接在上一条后面，不退回 0 —— 退回 0
             * 会让整章的句子起点全部相等，二分定位就废了。 */
            val base = if (l.srcStart >= 0) l.srcStart
            else out.lastOrNull()?.srcEnd ?: 0

            var a = 0
            var i = 0
            while (i < t.length) {
                if (STOPS.indexOf(t[i]) < 0) {
                    i++
                    continue
                }
                var b = i + 1
                while (b < t.length && (STOPS.indexOf(t[b]) >= 0 || TAILS.indexOf(t[b]) >= 0)) b++
                emit(out, t, base, a, b)
                a = b
                i = b
            }
            if (a < t.length) emit(out, t, base, a, t.length)
        }
        return out
    }

    private fun emit(out: ArrayList<Sentence>, t: String, base: Int, a0: Int, b0: Int) {
        var a = a0
        var b = b0
        while (a < b && t[a].isWhitespace()) a++
        while (b > a && t[b - 1].isWhitespace()) b--
        if (b <= a) return

        val body = t.substring(a, b)
        val from = base + a
        val to = base + b

        /* 太短：并进前一句。只在本行内并 —— 上一句的尾巴落在这一行之前时不动它，
         * 否则要拿这一行的字符串去切上一行的偏移。 */
        val last = out.lastOrNull()
        if (body.length < MIN && last != null && last.srcEnd > base) {
            out[out.size - 1] = last.copy(srcEnd = to, text = last.text + body)
            return
        }
        /* 起点必须往前走。前面那一条比它更靠后（重排过、偏移填错），这一条跳过。 */
        if (last != null && from < last.srcStart) return
        if (last != null && from == last.srcStart) {
            if (to > last.srcEnd) out[out.size - 1] = last.copy(srcEnd = to, text = last.text + body)
            return
        }
        out.add(Sentence(from, to, body))
    }

    /** 落点在哪一句。句子表为空回 −1。 */
    fun at(list: List<Sentence>, pos: Int): Int {
        if (list.isEmpty()) return -1
        var lo = 0
        var hi = list.size - 1
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (list[m].srcStart <= pos) lo = m else hi = m - 1
        }
        return lo
    }
}
