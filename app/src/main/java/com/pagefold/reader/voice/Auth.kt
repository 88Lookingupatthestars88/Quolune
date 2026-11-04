package com.pagefold.reader.voice

import java.io.IOException

/**
 * 出站请求里的钥匙。
 *
 * 从输入框、剪贴板、旧存档来的钥匙都可能混进中文、全角空格、换行。
 * OkHttp 的 header 只收可见 ASCII（0x21–0x7E）与空格、制表符，撞上别的字符就抛
 * IllegalArgumentException —— 那是 RuntimeException，不是 IOException，
 * 调用方的 catch 抓不住，一路冒到协程外层，整个应用当场退出。
 *
 * 所以每一处拼 header 之前都从这里过一遍：
 * · [clean] 给输入框用，粘进来的脏字符当场剔掉，不让他有机会存下坏的；
 * · [why] 给请求前用，说清第几个字不对，而不是丢一个 IllegalArgumentException；
 * · [bearer] / [head] 直接产出能放进 header 的值，不合规抛 IOException。
 *
 * 一句话：钥匙坏了是「填错了」，不是「程序崩了」。
 */
object Auth {

    /** 钥匙最长这么多。真钥匙都在 200 以内，超过的是粘错了整段。 */
    const val MAX = 4096

    /** 只留可见 ASCII。输入框用它，粘进来什么脏的都上不了屏。 */
    fun clean(raw: String?): String {
        val s = raw.orEmpty()
        val sb = StringBuilder(s.length)
        for (c in s) if (c.code in 0x21..0x7E) sb.append(c)
        return sb.toString()
    }

    /**
     * 能用返回 null；不能用返回一句给人看的话。
     *
     * 指出**位置和那个字**：只说「钥匙不对」他要一个一个删着试，
     * 说「第 7 位是「再」」，一眼就知道是粘的时候多带了东西。
     */
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

    /** `Authorization: Bearer xxx`。 */
    @Throws(IOException::class)
    fun bearer(raw: String?): String {
        why(raw)?.let { throw IOException(it) }
        return "Bearer " + raw.orEmpty()
    }

    /** 直接放进自定义头（`api-key`、`X-Api-Key`）的原值。 */
    @Throws(IOException::class)
    fun head(raw: String?): String {
        why(raw)?.let { throw IOException(it) }
        return raw.orEmpty()
    }
}
