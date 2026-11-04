package com.pagefold.reader.voice

import java.io.IOException

/**
 * 请求头里的钥匙。OkHttp 只收可见 ASCII，遇上汉字会抛
 * IllegalArgumentException（RuntimeException，抓不住就闪退）。
 */
object Auth {

    const val MAX = 4096

    /** 只留可见 ASCII。输入框用它。 */
    fun clean(raw: String?): String {
        val s = raw.orEmpty()
        val sb = StringBuilder(s.length)
        for (c in s) if (c.code in 0x21..0x7E) sb.append(c)
        return sb.toString()
    }

    /** 能用返回 null，否则返回一句说明。 */
    fun why(raw: String?): String? {
        val s = raw.orEmpty()
        if (s.isBlank()) return "还没填钥匙"
        for (i in s.indices) {
            val c = s[i]
            if (c.code in 0x21..0x7E) continue
            val what = when {
                c == ' ' -> "空格"
                c == '\t' -> "制表符"
                c == '\n' || c == '\r' -> "换行"
                c.code < 0x20 || c.code == 0x7F -> "控制字符"
                c.code in 0x4E00..0x9FFF -> "汉字「$c」"
                c.code in 0xFF00..0xFFEF -> "全角字符「$c」"
                else -> "「$c」"
            }
            return "钥匙第 ${i + 1} 位是$what，念的时候会被拒。删掉它再试"
        }
        if (s.length > MAX) return "钥匙太长了（${s.length} 个字），多半是粘错了一段"
        return null
    }

    /** `Authorization: Bearer xxx` */
    @Throws(IOException::class)
    fun bearer(raw: String?): String {
        why(raw)?.let { throw IOException(it) }
        return "Bearer " + raw.orEmpty()
    }

    /** 原值放进自定义头。 */
    @Throws(IOException::class)
    fun head(raw: String?): String {
        why(raw)?.let { throw IOException(it) }
        return raw.orEmpty()
    }
}
