package com.pagefold.reader.field

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一本书自己的读法。定音里那一套是全书默认；这里只存「这本书跟默认不一样的那几格」。 */
object Tune {

    /** 键 → 说法 → 这一格管什么。界面照着这张表铺，加一项只要往这儿加一行。 */
    val KEYS: List<Triple<String, String, String>> = listOf(
        // ── 版式 ──
        //
        // 这五格属于这本书自己，不写全局（field/Settings）。收书时从定音抄一份，之后各改各的。
        Triple("fontSize", "字号", "正文字号，单位 sp"),
        Triple("lineGap", "行距", "行高与字号的百分比"),
        Triple("padH", "左右留白", "正文左右边距，数值增大则单行字数减少"),
        Triple("paraGap2", "段间距", "段落间的额外空白，0 表示仅换行"),
        Triple("turn", "翻法", "0 左右翻，1 上下滚"),
        // ── 读法 ──
        Triple("context", "前情", "合成时附带的上文句数，用于延续语气，不会单独念出"),
        Triple("next", "后话", "合成时附带的下文句数，用于判断句末停顿，不会单独念出"),
        Triple("prefetch", "先备几段", "预取段数，播放当前段时后续段落提前合成"),
        Triple("chunkChars", "一气念多少字", "单次合成的字数上限。数值增大则出音延迟增加，数值减小则请求次数增加"),
        Triple("cacheChapters", "往后先备几章", "当前章之后预缓存并存入本机的章数"),
        Triple("gapMs", "两句之间歇多久", "句与句之间的停顿，单位毫秒"),
        Triple("reqIntervalMs", "传输间隔", "向同一服务商发起两次请求的最小间隔，本机限流，与合成速度无关"),
        Triple("retry", "遇到失误，重试", "单段合成失败后的重试次数，0 表示不重试，该段跳过"),
        Triple("retryDelayMs", "失误后等待时长", "两次重试之间的固定间隔，单位毫秒"),
        Triple("concurrency", "同时开几路", "对同一服务商的并发请求上限"),
    )

    /** 每一格的上下限与步长。 */
    fun range(key: String): IntArray = when (key) {
        "fontSize" -> intArrayOf(13, 32, 1)
        "lineGap" -> intArrayOf(110, 220, 5)
        "padH" -> intArrayOf(8, 48, 2)
        "paraGap2" -> intArrayOf(0, 40, 2)
        "turn" -> intArrayOf(0, 1, 1)
        "context", "next" -> intArrayOf(0, 20, 1)
        "prefetch" -> intArrayOf(1, 12, 1)
        "chunkChars" -> intArrayOf(200, 1800, 100)
        "cacheChapters" -> intArrayOf(0, 20, 1)
        "gapMs", "reqIntervalMs" -> intArrayOf(0, 3000, 100)
        "retry" -> intArrayOf(0, 5, 1)
        "retryDelayMs" -> intArrayOf(200, 5000, 200)
        "concurrency" -> intArrayOf(1, 4, 1)
        else -> intArrayOf(0, 100, 1)
    }

    fun unitOf(key: String): String = when (key) {
        "fontSize" -> "号"
        "lineGap" -> "%"
        "padH" -> "dp"
        "paraGap2" -> "sp"
        "turn" -> ""
        "context", "next" -> "句"
        "prefetch" -> "段"
        "chunkChars" -> "字"
        "cacheChapters" -> "章"
        "gapMs", "reqIntervalMs", "retryDelayMs" -> "毫秒"
        "retry" -> "次"
        "concurrency" -> "路"
        else -> ""
    }

    fun valueOf(s: Settings, key: String): Int = when (key) {
        "fontSize" -> s.fontSize
        "lineGap" -> s.lineGap
        "padH" -> s.padH
        "paraGap2" -> s.paraGap
        "turn" -> if (s.turn == TurnMode.FLIP) 0 else 1
        "context" -> s.context
        "next" -> s.next
        "prefetch" -> s.prefetch
        "chunkChars" -> s.chunkChars
        "cacheChapters" -> s.cacheChapters
        "gapMs" -> s.gapMs
        "reqIntervalMs" -> s.reqIntervalMs
        "retry" -> s.retry
        "retryDelayMs" -> s.retryDelayMs
        "concurrency" -> s.concurrency
        else -> 0
    }

    private val gson = Gson()
    private val type = object : TypeToken<Map<String, Int>>() {}.type

    fun decode(json: String): Map<String, Int> =
        if (json.isBlank()) emptyMap()
        else runCatching { gson.fromJson<Map<String, Int>>(json, type) }.getOrNull() ?: emptyMap()

    fun encode(m: Map<String, Int>): String =
        if (m.isEmpty()) "" else gson.toJson(m.filterKeys { it in KEYS.map { k -> k.first } })

    /** 把这本书的改动摊到全局上。 */
    fun apply(s: Settings, json: String): Settings {
        val m = decode(json)
        if (m.isEmpty()) return s
        var out = s
        if (m.containsKey("fontSize")) out = out.copy(fontSize = m.getValue("fontSize").coerceIn(13, 32))
        if (m.containsKey("lineGap")) out = out.copy(lineGap = m.getValue("lineGap").coerceIn(110, 220))
        if (m.containsKey("padH")) out = out.copy(padH = m.getValue("padH").coerceIn(8, 48))
        if (m.containsKey("paraGap2")) out = out.copy(paraGap = m.getValue("paraGap2").coerceIn(0, 40))
        if (m.containsKey("turn")) out = out.copy(turnMode = if (m.getValue("turn") == 0) "FLIP" else "SCROLL")
        if (m.containsKey("context")) out = out.copy(context = m.getValue("context"))
        if (m.containsKey("next")) out = out.copy(next = m.getValue("next"))
        if (m.containsKey("prefetch")) out = out.copy(prefetch = m.getValue("prefetch"))
        if (m.containsKey("chunkChars")) out = out.copy(chunkChars = m.getValue("chunkChars"))
        if (m.containsKey("cacheChapters")) out = out.copy(cacheChapters = m.getValue("cacheChapters"))
        if (m.containsKey("gapMs")) out = out.copy(gapMs = m.getValue("gapMs"))
        if (m.containsKey("reqIntervalMs")) out = out.copy(reqIntervalMs = m.getValue("reqIntervalMs"))
        if (m.containsKey("retry")) out = out.copy(retry = m.getValue("retry"))
        if (m.containsKey("retryDelayMs")) out = out.copy(retryDelayMs = m.getValue("retryDelayMs"))
        if (m.containsKey("concurrency")) out = out.copy(concurrency = m.getValue("concurrency"))
        return out
    }

    /** 改一格：写进去，就这一件事。 */
    fun put(json: String, key: String, v: Int): String {
        val m = decode(json).toMutableMap()
        m[key] = v
        return encode(m)
    }

    /** 明确撤掉一格，让它重新跟随全局。 */
    fun drop(json: String, key: String): String {
        val m = decode(json).toMutableMap()
        m.remove(key)
        return encode(m)
    }

    fun has(json: String, key: String): Boolean = decode(json).containsKey(key)

    /** 把此刻全局那套抄成一张表。收书那一刻调一次：这本书从此有一份自己的版式，定音之后再改，不会回头把它拽走。 */
    fun snapshot(s: Settings): String = snapshot(s, "")

    /** 在已有那张表上盖版式五格，读法那几格原样留着。旧书迁移用这个。 */
    fun snapshot(s: Settings, baseJson: String): String {
        val m = decode(baseJson).toMutableMap()
        m["fontSize"] = s.fontSize
        m["lineGap"] = s.lineGap
        m["padH"] = s.padH
        m["paraGap2"] = s.paraGap
        m["turn"] = if (s.turn == TurnMode.FLIP) 0 else 1
        return encode(m)
    }
}
