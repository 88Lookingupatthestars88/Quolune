package com.pagefold.reader.book

/** 给「让 AI 排」切上下文：一句话所在的那一页，和它前面那一页。 */
object Excerpt {

    /** 目标段前后各带几段。 */
    const val AROUND = 3
    /** 前一页带几段。 */
    const val BACK = 6
    /** 框住目标句的符号，[com.pagefold.reader.voice.Assign] 那边按同一对认。 */
    const val L = "⟦"
    const val R = "⟧"

    /** [a,b) 这一段去掉首尾空白；空段和章节标题返回空串。 */
    private fun para(text: String, a: Int, b: Int): String {
        val s = text.substring(a.coerceIn(0, text.length), b.coerceIn(0, text.length)).trim()
        if (s.isEmpty()) return ""
        if (ChapterHead.isLine(s)) return ""
        return s
    }

    /** [abs] 所在那一段的区间。 */
    fun span(text: String, abs: Int): Pair<Int, Int> {
        val at = abs.coerceIn(0, text.length)
        val a = text.lastIndexOf('\n', maxOf(0, at - 1)) + 1
        var b = text.indexOf('\n', at)
        if (b < 0) b = text.length
        return a to b
    }

    /** 摘下这一段，[say] 那半截左右各套一个框。 */
    fun mark(text: String, a: Int, b: Int, abs: Int, say: String): String {
        val s0 = a.coerceIn(0, text.length)
        val s1 = b.coerceIn(0, text.length)
        if (say.isNotEmpty() && abs >= s0 && abs + say.length <= s1 && text.startsWith(say, abs)) {
            val l = text.substring(s0, abs).trimEnd()
            val r = text.substring(abs + say.length, s1).trimStart()
            return l + L + say + R + r
        }
        return text.substring(s0, s1).trim()
    }

    /**
* 这一页：目标段连同前后各 [AROUND] 段，目标句用 ⟦ ⟧ 框住。
*
* @param abs 这一句在全文里的位置
*/
    fun page(text: String, abs: Int, say: String): String {
        val (pa, pb) = span(text, abs)
        val segs = ArrayList<String>(AROUND * 2 + 1)
        var a = pa
        repeat(AROUND) {
            if (a <= 0) return@repeat
            val prevB = text.lastIndexOf('\n', maxOf(0, a - 2)) + 1
            if (prevB <= 0) return@repeat
            val s = para(text, prevB, a - 1)
            if (s.isNotEmpty()) segs.add(0, s)
            a = prevB
        }
        segs.add(mark(text, pa, pb, abs, say))
        var after = pb
        repeat(AROUND) {
            if (after >= text.length - 1) return@repeat
            val nextB = text.indexOf('\n', after + 1).let { if (it < 0) text.length else it }
            val s = para(text, after + 1, nextB)
            if (s.isNotEmpty()) segs.add(s)
            after = nextB
        }
        return segs.joinToString("\n")
    }

    /** [page] 的第一段在全文里的起点。前一页从这儿往前数，两页不重叠。 */
    private fun pageStart(text: String, abs: Int): Int {
        val (pa, _) = span(text, abs)
        var a = pa
        var n = 0
        while (n < AROUND && a > 0) {
            val prevB = text.lastIndexOf('\n', maxOf(0, a - 2)) + 1
            if (prevB <= 0) break
            a = prevB
            n++
        }
        return a
    }

    /** 前一页：这一页开头之前那 [BACK] 段。 */
    fun prevPage(text: String, abs: Int): String {
        val segs = ArrayList<String>(BACK)
        var a = pageStart(text, abs)
        repeat(BACK) {
            if (a <= 0) return@repeat
            val prevB = text.lastIndexOf('\n', maxOf(0, a - 2)) + 1
            if (prevB <= 0) return@repeat
            val s = para(text, prevB, a - 1)
            if (s.isNotEmpty()) segs.add(0, s)
            a = prevB
        }
        return segs.joinToString("\n")
    }

    /**
* 这一章能开口的人：这一章正文里出现过、又在 [names] 里的。
*
* @param names 全书名单，按句数降序；这一章一个人名都没有时取前六个
*/
    fun castOf(text: String, chStart: List<Int>, ch: Int, names: List<String>): List<String> {
        val s = chStart.getOrNull(ch) ?: return names.take(6)
        val e = chStart.getOrNull(ch + 1) ?: text.length
        if (e <= s) return names.take(6)
        val body = text.substring(s, e)
        val hit = names.filter { it.length >= 2 && body.contains(it) }
        return if (hit.size >= 2) hit else (hit + names.take(6)).distinct()
    }
}
