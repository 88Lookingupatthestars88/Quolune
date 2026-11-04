package com.pagefold.reader.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * 一句待朗读的文本。
 *
 * srcStart / srcEnd 是这一句在**本章正文**里的字符区间（闭开区间）。
 * 没有它，播放器只知道「第几行」，界面只知道「第几段」，
 * 两套坐标对不上 —— 从某处开始听就会偏、念到哪也没法高亮。
 */
data class Line(
    val text: String,
    val isDialog: Boolean,
    val speaker: String,
    val voice: String,
    val srcStart: Int = -1,
    val srcEnd: Int = -1,
)

/**
 * 一条匹配规则：用什么正则认出一个人，认出来派给谁。
 *
 * @param character 这条规则派给谁。必须跟角色表里的名字一模一样。
 * @param pattern   正则。只在引号外面那半句里找，不碰正文。
 * @param afterDialog 看引号后面那半句（true）还是前面（false）
 * @param enabled   关掉的那条不参与
 */
data class CastRule(
    val character: String = "",
    val pattern: String = "",
    val afterDialog: Boolean = false,
    val enabled: Boolean = true,
    /** 只有「说白 / 白口」这两行用它：那一行第二列写的是嗓子。别的角色嗓子在 CastRole 上。 */
    val slotVoice: String = "",
) {
    private var compiled: Regex? = null
    private var tried = false

    fun regex(): Regex? {
        if (tried) return compiled
        tried = true
        compiled = runCatching { Regex(pattern) }.getOrNull()
        return compiled
    }

    fun bad(): Boolean = character.isBlank() || pattern.isBlank() || regex() == null
}

/** 一个角色：名字 + 嗓子 + 调门。嗓子空 = 用白口那副。 */
data class CastRole(
    val name: String,
    val voice: String = "",
    val style: String = "",
)

/** 拿去认人的一条。plan 只认识这个，不关心它是怎么存下来的。 */
data class Rule(
    val scope: Scope,
    val role: String,
    val voice: String,
    val pattern: String,
) {
    enum class Scope { BEFORE, AFTER, ANY }

    private var compiled: Regex? = null

    fun regex(): Regex? {
        compiled?.let { return it }
        return runCatching { Regex(pattern) }.also { compiled = it.getOrNull() }.getOrNull()
    }

    fun bad(): Boolean = regex() == null
}

/**
 * 一本书的角色表。存成 .tlcast。
 *
 * 分两层：
 *   roles  —— 有谁，各用哪副嗓子
 *   rules  —— 怎么认出他，一条一行正则
 * 一个人可以有好几种叫法，所以是几条规则指向同一个人，不是一个人一条规则。
 */
data class CastBook(
    val v: Int = 4,
    val name: String = "",
    val narrator: String = "",
    val dialog: String = "",
    val narratorStyle: String = "",
    val dialogStyle: String = "",
    val roles: List<CastRole> = emptyList(),
    val rules: List<CastRule> = emptyList(),
) {
    fun voiceOf(role: String): String =
        roles.firstOrNull { it.name == role }?.voice.orEmpty()

    fun styleOf(role: String): String =
        roles.firstOrNull { it.name == role }?.style.orEmpty()

    /** 交给 plan 的那串规则。关掉的、写坏的全部剔掉。 */
    fun active(): List<Rule> = rules
        .filter { it.enabled && !it.bad() }
        .map {
            Rule(
                scope = if (it.afterDialog) Rule.Scope.AFTER else Rule.Scope.ANY,
                role = it.character,
                voice = voiceOf(it.character),
                pattern = it.pattern,
            )
        }

    fun with(role: CastRole): CastBook {
        val list = roles.toMutableList()
        val i = list.indexOfFirst { it.name == role.name }
        if (i >= 0) list[i] = role else list.add(role)
        return copy(roles = list)
    }

    fun without(role: String): CastBook = copy(
        roles = roles.filter { it.name != role },
        rules = rules.filter { it.character != role },
    )
}

/**
 * 角色表的读写。
 *
 * 写出去的长这样：
 *   {"narration":{"voice":"白桦","style":""},"dialogue":{…},
 *    "characters":{"林瑾":{"voice":"云健"}},"matchRules":[{"character":"林瑾","pattern":"…"}]}
 *
 * 读进来三种都认：上面这个、带 v 的老格式、和一行一条的文本。
 * 三种最后都汇成 [CastBook]，界面只认那一个。
 */
object RuleCodec {

    private val gson = Gson()

    private fun str(o: com.google.gson.JsonObject?, k: String): String =
        runCatching { o?.get(k)?.takeIf { !it.isJsonNull }?.asString }.getOrNull().orEmpty()

    private fun styleOf(o: com.google.gson.JsonObject?): Pair<String, String> =
        str(o, "voice") to str(o, "style")

    fun toJson(cast: CastBook): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("v", 4)
        fun slot(voice: String, style: String) = com.google.gson.JsonObject().apply {
            addProperty("voice", voice)
            addProperty("style", style)
        }
        root.add("narration", slot(cast.narrator, cast.narratorStyle))
        root.add("dialogue", slot(cast.dialog, cast.dialogStyle))
        val chars = com.google.gson.JsonObject()
        cast.roles.forEach { chars.add(it.name, slot(it.voice, it.style)) }
        root.add("characters", chars)
        val rules = com.google.gson.JsonArray()
        cast.rules.forEach { r ->
            rules.add(com.google.gson.JsonObject().apply {
                addProperty("character", r.character)
                addProperty("pattern", r.pattern)
                addProperty("afterDialog", r.afterDialog)
                addProperty("enabled", r.enabled)
            })
        }
        root.add("matchRules", rules)
        return gson.toJson(root)
    }

    fun fromJson(raw: String): CastBook {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return CastBook()
        // 新格式：narration / dialogue / characters / matchRules 四个键里有一个就算
        val hasNew = root.has("characters") || root.has("matchRules") ||
            root.has("narration") || root.has("dialogue")
        if (hasNew) {
            val (nv, ns) = styleOf(root.getAsJsonObject("narration"))
            val (dv, ds) = styleOf(root.getAsJsonObject("dialogue"))
            val roles = ArrayList<CastRole>()
            runCatching {
                root.getAsJsonObject("characters")?.entrySet()?.forEach { e ->
                    val (v, s) = styleOf(e.value.takeIf { it.isJsonObject }?.asJsonObject)
                    roles += CastRole(e.key, v, s)
                }
            }
            val rules = ArrayList<CastRule>()
            runCatching {
                root.getAsJsonArray("matchRules")?.forEach { el ->
                    val o = el.asJsonObject
                    val who = str(o, "character")
                    val pat = str(o, "pattern")
                    if (who.isNotBlank() && pat.isNotBlank()) {
                        rules += CastRule(
                            character = who,
                            pattern = pat,
                            afterDialog = runCatching { o.get("afterDialog")?.asBoolean }.getOrNull() ?: false,
                            enabled = runCatching { o.get("enabled")?.asBoolean }.getOrNull() ?: true,
                        )
                    }
                }
            }
            return CastBook(
                narrator = nv, dialog = dv, narratorStyle = ns, dialogStyle = ds,
                roles = roles, rules = rules,
            ).let { b ->
                // 规则提到了、角色表里却没有的人，补一个空嗓子，否则永远配不上人
                val missing = b.rules.map { it.character }.distinct().filter { n ->
                    b.roles.none { it.name == n }
                }
                b.copy(roles = b.roles + missing.map { CastRole(it) })
            }
        }
        // 我们自己 v2/v3 的老格式：roles 里带着 pattern/scope
        val out = CastBook(
            narrator = str(root, "narrator"),
            dialog = str(root, "dialog"),
            narratorStyle = str(root, "narratorStyle"),
            dialogStyle = str(root, "dialogStyle"),
        )
        val roles = ArrayList<CastRole>()
        val rules = ArrayList<CastRule>()
        runCatching {
            root.getAsJsonArray("roles")?.forEach { el ->
                val o = el.asJsonObject
                val n = str(o, "name")
                if (n.isBlank()) return@forEach
                roles += CastRole(n, str(o, "voice"), str(o, "style"))
                val pat = str(o, "pattern")
                if (pat.isNotBlank()) {
                    rules += CastRule(
                        character = n,
                        pattern = pat,
                        afterDialog = str(o, "scope").equals("after", true),
                        enabled = runCatching { o.get("enabled")?.asBoolean }.getOrNull() ?: true,
                    )
                }
            }
        }
        // 老格式里另一处：顶层的 rules 数组
        runCatching {
            root.getAsJsonArray("rules")?.forEach { el ->
                val o = el.asJsonObject
                val who = str(o, "role").ifBlank { str(o, "voice") }
                val pat = str(o, "pattern")
                if (who.isNotBlank() && pat.isNotBlank()) {
                    rules += CastRule(who, pat, str(o, "scope").equals("after", true), true)
                }
            }
        }
        return out.copy(roles = roles, rules = rules)
    }

    /**
     * 一行一条：谁 | 嗓子 | 正则 [| 前/后]
     *
     * 第二、三列可以空。正则里带竖线是常态（`(说道|沉声道)`），
     * 所以只从左往右切两刀，后面整段都算正则。split 全切会把正则切碎。
     */
    fun fromText(raw: String): List<CastRule> {
        val out = ArrayList<CastRule>()
        raw.lines().forEach { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) return@forEach
            val t = s.replace('｜', '|').replace('\t', '|')
            val i1 = t.indexOf('|')
            if (i1 <= 0) return@forEach
            val i2 = t.indexOf('|', i1 + 1)
            val who = t.substring(0, i1).trim()
            val voice = if (i2 < 0) t.substring(i1 + 1).trim() else t.substring(i1 + 1, i2).trim()
            var pat = if (i2 < 0) "" else t.substring(i2 + 1).trim()
            var after = false
            // 末尾单独挂一个「前 / 后」，其余全算正则
            if (pat.endsWith("|前") || pat.endsWith("|后")) {
                after = pat.endsWith("|后")
                pat = pat.dropLast(2).trim()
            }
            if (who.isBlank()) return@forEach
            out += CastRule(who, pat, after, true, slotVoice = voice)
        }
        return out
    }

    fun encode(rules: List<CastRule>): String = buildString {
        appendLine("# 一行一条：谁 | 嗓音 | 正则 [| 后]")
        appendLine("# 竖线只切前两刀，正则里的竖线不会被切坏。以 # 开头的行不算。")
        rules.forEach {
            append(it.character).append('|').append(it.slotVoice).append('|').append(it.pattern)
            if (it.afterDialog) append("|后")
            appendLine()
        }
    }

    /** 把一串规则收成一张表：说到「说白 / 白口」的那两行，改的是旁白和对白。 */
    fun toBook(rules: List<CastRule>, narrator: String, dialog: String): CastBook {
        var nar = narrator
        var dia = dialog
        val roles = LinkedHashMap<String, CastRole>()
        val keep = ArrayList<CastRule>()
        rules.forEach { r ->
            when (r.character) {
                "说白", "旁白", "narrator" -> { nar = r.slotVoice.ifBlank { nar }; return@forEach }
                "白口", "对白", "dialog" -> { dia = r.slotVoice.ifBlank { dia }; return@forEach }
            }
            val v = r.slotVoice
            if (!roles.containsKey(r.character)) roles[r.character] = CastRole(r.character, v)
            keep += r
        }
        return CastBook(narrator = nar, dialog = dia, roles = roles.values.toList(), rules = keep)
    }
}

/** 一份完整的角色方案，交给 plan 用。 */
data class CastProfile(
    val narratorVoice: String,
    val defaultVoice: String,
    val narratorStyle: String = "",
    val dialogStyle: String = "",
    val rules: List<Rule>,
    /** 角色名 → 嗓子。查不到就用 defaultVoice，绝不拿名字当嗓子用。 */
    val voices: Map<String, String> = emptyMap(),
)

/**
 * 分角色引擎。
 *
 * 处理顺序：
 *   段落 → 切成「旁白 / 对白」交替片段 → 每句对白找说话人 → 找不到就交替推断 → 再不行用默认音色
 *
 * 比「只看引号前 256 字」的做法多两层兜底：
 *   1. 连续对白：第一句定了人，第二句如果没线索，按 A→B→A 交替推
 *   2. 段落级的「某某说」会被整段共享，不再局限于紧邻的一句
 */
object Cast {

    private val QUOTE_PAIRS = listOf(
        '“' to '”', '「' to '」', '『' to '』', '"' to '"', '\'' to '\''
    )

    /**
     * 把一章正文切成带说话人、带字符区间的行序列。
     *
     * @param chunk 单行上限。超过就找最近的句末切一刀 —— 太长的段一次合成等得久，
     *              而且服务端会静默截断。0 = 不切。
     */
    fun plan(text: String, p: CastProfile, chunk: Int = 800): List<Line> {
        val out = ArrayList<Line>(256)
        var lastSpeaker: String? = null
        var beforeLastSpeaker: String? = null

        var pStart = 0
        while (pStart <= text.length) {
            val base = pStart
            var pEnd = text.indexOf('\n', base)
            if (pEnd < 0) pEnd = text.length
            val para = text.substring(base, pEnd)
            pStart = pEnd + 1

            if (para.isBlank()) { if (pEnd >= text.length) break else continue }

            val segs = splitQuotes(para)
            val hasDialog = segs.any { it.isDialog }
            if (!hasDialog) {
                // 整段没有对白：纯旁白
                val whole = trimRange(para, 0, para.length)
                if (whole != null) {
                    cut(para, whole.first, whole.second, chunk) { a, b ->
                        Wash.of(para.substring(a, b)).takeIf { it.isNotBlank() }?.let { t ->
                            out.add(Line(t, false, "", p.narratorVoice, base + a, base + b))
                        }
                    }
                }
                if (pEnd >= text.length) break else continue
            }

            val paraNames = namesIn(para, p.rules)
            for ((idx, seg) in segs.withIndex()) {
                val body = para.substring(seg.from, seg.to)
                if (body.isBlank()) continue
                val absA = base + seg.from
                val absB = base + seg.to
                if (!seg.isDialog) {
                    cut(para, seg.from, seg.to, chunk) { a, b ->
                        Wash.of(para.substring(a, b)).takeIf { it.isNotBlank() }?.let { t ->
                            out.add(Line(t, false, "", p.narratorVoice, base + a, base + b))
                        }
                    }
                    continue
                }
                val beforeCtx = if (idx > 0) para.substring(segs[idx - 1].from, segs[idx - 1].to).takeLast(140) else ""
                val afterCtx = if (idx + 1 < segs.size) para.substring(segs[idx + 1].from, segs[idx + 1].to).take(140) else ""
                var hit = matchRules(beforeCtx, afterCtx, p.rules)
                var name = hit?.role
                if (name == null && paraNames.size == 1) name = paraNames.first()
                if (name == null && paraNames.size > 1) {
                    name = paraNames.firstOrNull { it != lastSpeaker } ?: paraNames.last()
                }
                if (name == null) {
                    name = when {
                        beforeLastSpeaker != null && beforeLastSpeaker != lastSpeaker -> beforeLastSpeaker
                        lastSpeaker == null -> null
                        else -> lastSpeaker
                    }
                }
                // 规则里写了嗓子就用它；没写就按名字查一次；再没有就用白口那副。
                val voice = hit?.voice?.takeIf { it.isNotBlank() }
                    ?: (if (name != null) voiceFor(name, p) else p.defaultVoice)
                cut(para, seg.from, seg.to, chunk) { a, b ->
                    Wash.of(para.substring(a, b)).takeIf { it.isNotBlank() }?.let { t ->
                        out.add(Line(t, true, name.orEmpty(), voice, base + a, base + b))
                    }
                }
                if (name != null) {
                    beforeLastSpeaker = lastSpeaker
                    lastSpeaker = name
                }
                if (absB <= absA) continue
            }
            if (pEnd >= text.length) break
        }
        return out
    }

    /** 把 [a,b) 按 chunk 上限切成几块，切点尽量落在句末。 */
    private inline fun cut(
        para: String,
        a0: Int,
        b0: Int,
        chunk: Int,
        emit: (Int, Int) -> Unit,
    ) {
        if (chunk <= 0 || b0 - a0 <= chunk) { emit(a0, b0); return }
        var a = a0
        while (a < b0) {
            var b = (a + chunk).coerceAtMost(b0)
            if (b < b0) {
                var k = b
                while (k > a + chunk / 3) {
                    val c = para[k - 1]
                    if (c == '。' || c == '！' || c == '？' || c == '；' || c == '…') break
                    k--
                }
                if (k > a + chunk / 3) b = k
            }
            val t = trimRange(para, a, b)
            if (t != null) emit(t.first, t.second)
            a = b
        }
    }

    /** 去掉首尾空白，返回新区间；全空白回 null。 */
    private fun trimRange(s: String, a0: Int, b0: Int): Pair<Int, Int>? {
        var a = a0
        var b = b0
        while (a < b && s[a].isWhitespace()) a++
        while (b > a && s[b - 1].isWhitespace()) b--
        return if (b > a) a to b else null
    }

    private class Seg(val isDialog: Boolean, val from: Int, val to: Int)

    /** 把一段拆成（旁白 / 对白）交替的片段，带段内区间。 */
    private fun splitQuotes(s: String): List<Seg> {
        val res = ArrayList<Seg>()
        var i = 0
        var segStart = 0
        while (i < s.length) {
            val c = s[i]
            val pair = QUOTE_PAIRS.firstOrNull { it.first == c }
            if (pair == null) { i++; continue }
            trimRange(s, segStart, i)?.let { res.add(Seg(false, it.first, it.second)) }
            val from = i + 1
            var j = from
            var close = -1
            while (j < s.length) {
                if (s[j] == pair.second) { close = j; break }
                j++
            }
            if (close < 0) {
                // 引号没配对：剩下的都当旁白
                trimRange(s, i, s.length)?.let { res.add(Seg(false, it.first, it.second)) }
                return res
            }
            trimRange(s, from, close)?.let { res.add(Seg(true, it.first, it.second)) }
            i = close + 1
            segStart = i
        }
        trimRange(s, segStart, s.length)?.let { res.add(Seg(false, it.first, it.second)) }
        return res
    }

    /** 找出文本里命中规则的音色（用于段落级共享）。 */
    private fun namesIn(text: String, rules: List<Rule>): List<String> {
        val hit = ArrayList<Pair<Int, String>>()
        for (r in rules) {
            val re = r.regex() ?: continue
            val m = re.find(text) ?: continue
            hit.add(m.range.first to r.role)
        }
        return hit.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** 按顺序匹配规则，返回音色。 */
    fun matchRules(before: String, after: String, rules: List<Rule>): Rule? {
        for (r in rules) {
            val re = r.regex() ?: continue
            val hay = when (r.scope) {
                Rule.Scope.BEFORE -> before
                Rule.Scope.AFTER -> after
                Rule.Scope.ANY -> "$before\n$after"
            }
            if (hay.isBlank()) continue
            if (re.containsMatchIn(hay)) return r
        }
        return null
    }

    /**
     * 这个角色用哪副嗓子。
     *
     * 查角色表，查不到才用白口那副。返回 `name.ifBlank { defaultVoice }` 不行：
     * 那样每一行 voice 都是「林瑾」「赵高」这种人名，音色库里没有，合成时全落回默认。
     */
    private fun voiceFor(name: String, p: CastProfile): String =
        p.voices[name]?.takeIf { it.isNotBlank() } ?: p.defaultVoice

    // ─────────── 内置模板 ───────────

    /**
     * 通用中文网文模板。
     * 覆盖：说道 / 道 / 笑道 / 冷声道 / 沉声 / 怒道 / 低声 / 开口 / 问 / 答 / 喝道 …
     * 名字用「2–4 个汉字」捕获，不依赖具体人名，所以任何书都能先跑起来。
     */
    val TEMPLATE: String = """
# 一行一条：谁 | 嗓音 | 正则 [| 后]
# 正则里带竖线没问题，只切前两刀。

# 名字在引号后面：「……，某某说道」
某某 |  | ([\u4e00-\u9fa5]{2,4})(说道|问道|答道|笑道|冷笑道|沉声道|低声道|怒道|喝道|叹道|开口)

# 名字在引号前面：「某某说道：“……”」
某某 |  | ([\u4e00-\u9fa5]{2,4})(说道|问道|笑道|沉声道)|后

# 只有称呼
陛下 |  | (陛下|殿下|将军|大人|先生|姑娘|公子)(说道|道|开口)
""".trim()

    fun templateRules(): List<Rule> = listOf()
}
