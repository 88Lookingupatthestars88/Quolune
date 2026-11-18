package com.pagefold.reader.book

/** 每章在书里叫第几章。 */
object ChapterNo {

    /** 「第几章」里的那个数。数字、中文数字、全角数字都认。 */
    private val NO = Regex(
        """第\s*([0-9０-９〇零一二三四五六七八九十百千两]{1,9})\s*[章回节篇]"""
    )

    /**
* 从标题里取章号。
*
* @return 章号；标题里没有号（序、楔子、后记）返回 0。
*/
    fun parse(title: String): Int {
        val all = NO.findAll(title).toList()
        val m = all.lastOrNull() ?: return 0
        val raw = m.groupValues[1]
        val n = raw.map { if (it in '０'..'９') ('0' + (it - '０')) else it }
            .joinToString("").toIntOrNull() ?: cnNumber(raw)
        return if (n != null && n > 0) n else 0
    }

    /** 整本一份：每章的号，0 = 这一章没有号。 */
    fun of(chapters: List<Chapter>): IntArray {
        val out = IntArray(chapters.size)
        var any = false
        chapters.forEachIndexed { i, c ->
            val n = parse(c.title)
            out[i] = n
            if (n > 0) any = true
        }
        if (!any) chapters.indices.forEach { out[it] = it + 1 }
        return out
    }

    /**
* 界面上写的那一行。
*
* @param no    这一章的号。0 = 没号。
* @param index 它在目录里排第几个（0 起）。没号的时候用它兜底。
* @param title 书里那一行标题。没号又没位置可兜时，直接写它（「序」「楔子」）。
*/
    fun label(no: Int, index: Int, title: String): String {
        if (no > 0) return "第 $no 章"
        val t = title.trim()
        if (t.isNotEmpty() && !ChapterHead.startsWithNo(t)) return t.take(14)
        return "第 ${index + 1} 章"
    }

    /** 中文数字转整数（「一百二十三」→ 123）。用字面表，不用码位区间。认不出返回 null。 */
    private val DIGIT = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    fun cnNumber(s: String): Int? {
        var total = 0
        var cur = 0
        for (ch in s) {
            when {
                ch == '零' || ch == '〇' -> {}
                ch in DIGIT -> cur = DIGIT[ch] ?: 0
                ch == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                ch == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                ch == '千' -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                else -> return null
            }
        }
        return total + cur
    }
}
