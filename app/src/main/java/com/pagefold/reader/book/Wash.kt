package com.pagefold.reader.book

/**
 * 念之前过一遍水。
 *
 * 只做一件事：把**不该出声的东西**从要送进嗓源的句子里去掉。
 * 不动字符区间 —— 高亮、进度、书签全靠 srcStart/srcEnd，这里一个字都不许挪位置，
 * 所以只做「原位删除」，不做「重排、合并、改写」。
 *
 * 去掉的三类：
 *   一、零宽与不可见字符（\u200B、\uFEFF、\u00A0…）。有的书源里混着，念起来是怪停顿。
 *   二、装饰符号（★ ☆ ● ■ □ ◆ ◇ ▲ △ ※ ＊ ～ 括号类的占位符）。有些嗓源会把它们念成
 *       「星号」「方块」「左方括号」，那就是「文章里没有的话」。
 *   三、成串的重复标点（，，，、、、）。念出来是拖长的一口气。
 *
 * **不去掉**：引号、书名号、省略号、破折号。那些是有意义的，也是句子的一部分。
 */
object Wash {

    private val DECOR = charArrayOf(
        '★', '☆', '●', '○', '■', '□', '◆', '◇', '▲', '△', '▼', '▽',
        '※', '＊', '～', '§', '¶', '†', '‡', '♪', '♫', '→', '←', '↑', '↓',
        '【', '】', '〖', '〗', '〔', '〕',
    )

    fun of(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var lastDot = ' '
        var run = 0
        raw.forEach { c ->
            val code = c.code
            // 零宽、BOM、不换行空格、控制字符
            val invisible = code == 0x200B || code == 0x200C || code == 0x200D ||
                code == 0xFEFF || code == 0x00A0 || (code < 0x20 && c != '\n')
            if (invisible) return@forEach
            if (c in DECOR) return@forEach
            // 连续重复的同一个标点，只留一个
            if (c == lastDot && c in "，、；：,.!?;:") {
                run++
                if (run >= 1) return@forEach
            } else {
                run = 0
            }
            lastDot = c
            sb.append(c)
        }
        val out = sb.toString()
        // 整段一个可念的字都没有 —— 这一句不该发请求。
        return if (out.any { it.isLetterOrDigit() }) out else ""
    }
}
