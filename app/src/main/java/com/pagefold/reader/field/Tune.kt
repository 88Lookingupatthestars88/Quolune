package com.pagefold.reader.field

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一本书自己的读法。定音里那一套是全书默认；这里只存「这本书跟默认不一样的那几格」。 */
object Tune {

    /** 键 → 说法 → 这一格管什么。界面照着这张表铺，加一项只要往这儿加一行。 */
    val KEYS: List<Triple<String, String, String>> = listOf(
        // ── 版式 ──
        //
        // 这五格属于这本书自己，不写全局（field/Settings 里那几个字段）。写全局的话两处抢同一格：
        // 在书里改成左右翻，全局跟着变；回定音点一下上下滚，所有书一起变。
        // 现在一页一把，收书那一刻从定音抄一份下来，之后各管各的。
        Triple("fontSize", "字号", "字多大"),
        Triple("lineGap", "行距", "按字号的百分比"),
        Triple("padH", "左右留白", "留得多，一行字就少"),
        Triple("paraGap2", "段间距", "两段之间空多少"),
        Triple("turn", "翻法", "0 左右翻，1 上下滚"),
        // ── 读法 ──
        Triple("context", "前情", "合成时连前几句一起发，接住上一句的口气；只是多花几个字，不念出来"),
        Triple("next", "后话", "连后几句一起发，判这一句该不该收着尾"),
        Triple("prefetch", "先备几段", "念着这一段，后面几段已经在合成，断一下网也不停"),
        Triple("chunkChars", "一气念多少字", "一次合成多长。长了出声慢，短了请求碎"),
        Triple("cacheChapters", "往后先备几章", "从这一章往后先合几章存在本机，断网也能听"),
        Triple("gapMs", "两句之间歇多久", "念完一句停一下。太短像赶，太长像卡"),
        Triple("reqIntervalMs", "隔多久再开口", "对同一家服务两次请求之间至少隔这么久，是限速闸，不是引擎慢"),
        Triple("retry", "不成再试几回", "超时、429、空音频都算没取到，再跑一趟"),
        Triple("retryDelayMs", "每回之间等多久", "两次重试之间隔多久。越短越容易连着撞墙"),
        Triple("concurrency", "同时开几路", "同时发几条。两路是平衡点，再多容易被挡回来"),
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
        "cacheChapters" -> "回"
        "gapMs", "reqIntervalMs", "retryDelayMs" -> "ms"
        "retry" -> "回"
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

    /**
     * 改一格：写进去，就这一件事。
     * 不要顺手做「跟全局一样就删掉这一格」。
     */
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
