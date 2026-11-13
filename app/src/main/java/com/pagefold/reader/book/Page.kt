package com.pagefold.reader.book

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** 一页：文字 + 在章节内的起始字符偏移（用于和朗读对齐）。 */
data class Page(val text: String, val start: Int, val end: Int)

/** 把一章正文切成页。做法：逐段累加，用 TextMeasurer 实测高度，超页高就收页。 */
fun paginate(
    m: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
    indent: Boolean = true,
): List<Page> {
    if (widthPx <= 0 || heightPx <= 0) return listOf(Page(text, 0, text.length))
    val out = ArrayList<Page>(64)
    val constraints = Constraints(maxWidth = widthPx)

    fun measure(s: String): Int {
        if (s.isEmpty()) return 0
        return m.measure(AnnotatedString(s), style = style, constraints = constraints).size.height
    }

    val paras = text.split('\n')
    val buf = StringBuilder()
    var bufStart = 0
    var cursor = 0

    fun flush() {
        val s = buf.toString()
        if (s.isNotBlank()) out.add(Page(s, bufStart, bufStart + s.length))
        buf.setLength(0)
    }
    fun fits(add: String): Boolean = measure(buf.toString() + add) <= heightPx

    for (para in paras) {
        val line = if (indent) "　　" + para.trim() else para.trim()
        if (line.isBlank()) {
            cursor += para.length + 1
            continue
        }
        val chunk = line + "\n"
        if (buf.isEmpty()) bufStart = cursor

        if (fits(chunk)) {
            buf.append(chunk)
            cursor += para.length + 1
            continue
        }
        // 整段放不下：先收页，再按句子拆
        if (buf.isNotEmpty()) {
            flush()
            bufStart = cursor
        }
        var rest = line
        while (rest.isNotEmpty()) {
            if (fits(rest + "\n")) {
                buf.append(rest).append("\n")
                rest = ""
                break
            }
            // 二分找这一页能放多少字
            var lo = 1
            var hi = rest.length
            var best = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (fits(rest.substring(0, mid) + "\n")) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (best <= 0) best = 1
            // 尽量在标点处断开
            var cut = best
            for (i in best downTo (best - 24).coerceAtLeast(1)) {
                if (i in rest.indices && rest[i - 1] in "。！？；，、”）」") { cut = i; break }
            }
            buf.append(rest.substring(0, cut)).append("\n")
            flush()
            bufStart = cursor + cut
            rest = rest.substring(cut).trimStart()
        }
        cursor += para.length + 1
    }
    flush()

    if (out.isEmpty()) out.add(Page(text, 0, text.length))
    // 补上结束位置
    return out.mapIndexed { i, p ->
        p.copy(end = if (i + 1 < out.size) out[i + 1].start else text.length)
    }
}
