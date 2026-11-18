package com.pagefold.reader.book

/** 念之前过一遍水。只做一件事：把不该出声的东西从要送进嗓源的句子里去掉。 */
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
