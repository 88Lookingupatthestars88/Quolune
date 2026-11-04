package com.pagefold.reader.voice

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 音色包的读与写。
 *
 * 为什么不用 Gson 直接映射：手里的包不止一种写法。
 * 咱自己做的大秦包长这样 ——
 *
 *   { "name": "...", "type": "design", "engineId": "mimo",
 *     "voiceParam": "...", "description": "...", "tags": "帝王,威严,沙哑" }
 *
 * tags 是逗号串，不是数组；id 全是 0；字段名也全是另一套。
 * Gson 撞上「要数组却给了字符串」直接抛异常，整包就废了 ——
 * 这就是「导入的 JSON 根本不行」的真因，不是文件坏了。
 *
 * 所以这里改成逐个字段去认：每种常见写法都收，缺的能推就推。
 * 名字在包里有、能听懂就行，格式不该成为门槛。
 */
object Pack {

    /** 读一包。返回 (音色, 一句话结果)；音色为空时那句话就是失败原因。 */
    fun read(raw: String): Pair<List<Voice>, String> {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return emptyList<Voice>() to "文件是空的"

        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?: return emptyList<Voice>() to "打不开这个文件，可能不是 JSON"

        val arr: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val o = root.asJsonObject
                val key = listOf("voices", "items", "list", "cast", "data", "音色", "音色包")
                    .firstOrNull { o.has(it) && o.get(it).isJsonArray }
                if (key != null) o.get(key).asJsonArray
                else JsonArray().apply { add(o) }
            }
            else -> return emptyList<Voice>() to "顶上既不是数组也不是对象"
        }
        val total = arr.count()
        if (total == 0) return emptyList<Voice>() to "包里一条也没有"

        val out = ArrayList<Voice>(total)
        var skip = 0
        val seen = HashSet<String>()

        arr.forEach { el ->
            if (!el.isJsonObject) { skip++; return@forEach }
            val v = one(el.asJsonObject) ?: run { skip++; return@forEach }
            // 一套包常常 id 全是 0，靠名字去重。
            val sig = v.name + "/" + v.engine
            if (!seen.add(sig)) { skip++; return@forEach }
            out += v
        }

        if (out.isEmpty()) return emptyList<Voice>() to "看得懂 $total 条，但没有一条带着名字"
        return out to if (skip > 0) "收下 ${out.size} 副，跳过 $skip 条" else "收下 ${out.size} 副"
    }

    // ── 单条 ─────────────────────────────────────────────────

    private fun one(o: JsonObject): Voice? {
        val name = pick(o, "name", "title", "名称", "名字", "voiceName")?.trim().orEmpty()
        if (name.isEmpty()) return null

        val engine = engineOf(
            pick(o, "engine", "engineId", "引擎", "provider")
                ?: pick(o, "model", "modelId")
        )
        /*
         * 没写 type 的包按 preset 处理。当 design 的话，音色描述会进「第一条消息」，
         * 容易被一起念出来。判法：名字或参数命中内置预设 → preset，否则才当 design。
         */
        val desc = pick(o, "desc", "description", "描述", "voiceDesc", "instruction").orEmpty()
        val param = pick(o, "param", "voiceParam", "voice", "参数", "voiceId")
            ?: desc.ifBlank { name }
        val declared = pick(o, "kind", "type", "类型")
        val kind = if (declared.isNullOrBlank()) {
            val looksPreset = Catalog.MIMO.any { it.param == param || it.name == name }
            if (looksPreset) "preset" else kindOf(pick(o, "model"))
        } else kindOf(declared)

        return Voice(
            id = stableId(name, engine),
            name = name,
            kind = kind,
            engine = engine,
            param = param,
            desc = desc,
            gender = genderOf(pick(o, "gender", "sex", "性别")),
            age = ageOf(pick(o, "age", "ageGroup", "年龄")),
            tags = tagsOf(o.get("tags") ?: o.get("标签")),
            builtin = false,
        )
    }

    private fun pick(o: JsonObject, vararg keys: String): String? {
        keys.forEach { k ->
            val e = o.get(k) ?: return@forEach
            if (e.isJsonNull) return@forEach
            val t = if (e.isJsonPrimitive) e.asString else e.toString()
            if (t.isNotBlank()) return t
        }
        return null
    }

    /** 只在名字重了的时候才换 id —— 名字是用户认得出的那个东西。 */
    private fun stableId(name: String, engine: String): String =
        "u_" + engine + "_" + name.hashCode().toString(16)

    private fun kindOf(raw: String?): String {
        val t = raw.orEmpty().lowercase()
        return when {
            t.contains("clone") || t.contains("克隆") -> "clone"
            t.contains("design") || t.contains("设计") -> "design"
            t.contains("preset") || t.contains("预设") -> "preset"
            t.contains("system") || t.contains("系统") -> "system"
            else -> "design"
        }
    }

    private fun engineOf(raw: String?): String {
        val t = raw.orEmpty().lowercase()
        return when {
            t.contains("system") || t.contains("系统") -> "system"
            t.contains("mimo") || t.contains("小米") -> "mimo"
            else -> "mimo"
        }
    }

    private fun genderOf(raw: String?): String {
        val t = raw.orEmpty().lowercase()
        return when {
            t.contains("女") || t == "f" || t.startsWith("female") -> "f"
            t.contains("男") || t == "m" || t.startsWith("male") -> "m"
            else -> ""
        }
    }

    private fun ageOf(raw: String?): String {
        val t = raw.orEmpty().lowercase()
        return when {
            t.contains("童") || t.contains("少") || t.contains("child") || t.contains("young") -> "young"
            t.contains("老") || t.contains("old") || t.contains("elder") -> "old"
            t.contains("中") || t.contains("middle") -> "middle"
            else -> ""
        }
    }

    /** tags 有两种写法：["a","b"] 和 "a,b,c"、"a、b"。都收。 */
    private fun tagsOf(e: JsonElement?): List<String> {
        if (e == null || e.isJsonNull) return emptyList()
        if (e.isJsonArray) {
            return e.asJsonArray.mapNotNull {
                if (it.isJsonPrimitive) it.asString.trim().ifBlank { null } else null
            }
        }
        if (e.isJsonPrimitive) {
            return e.asString.split(',', '，', '、', ';', '/', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    // ── 写 ───────────────────────────────────────────────────

    /**
     * 写一包。
     *
     * 内置的那几副也写进去 —— 换台手机、重装应用，不必再一个个找回来。
     * 但要标清 builtin，导回来的时候会按名字合并，不会长出二十个重影。
     */
    fun write(list: List<Voice>): String {
        val arr = JsonArray()
        list.forEach { v ->
            arr.add(JsonObject().apply {
                addProperty("name", v.name)
                addProperty("kind", v.kind)
                addProperty("engine", v.engine)
                addProperty("param", v.param)
                addProperty("desc", v.desc)
                addProperty("gender", v.gender)
                addProperty("age", v.age)
                val tg = JsonArray()
                v.tags.forEach { tg.add(it) }
                add("tags", tg)
                addProperty("builtin", v.builtin)
                // 老软件认这套名字，顺手带上，两边都能读。
                addProperty("type", v.kind)
                addProperty("voiceParam", v.param)
                addProperty("description", v.desc)
                addProperty("engineId", v.engine)
                addProperty("tagsText", v.tags.joinToString(","))
            })
        }
        return JsonParser.parseString(
            JsonObject().apply {
                addProperty("app", "Quolune")
                addProperty("version", 2)
                add("voices", arr)
            }.toString()
        ).let { pretty(it) }
    }

    private fun pretty(e: JsonElement): String {
        val b = StringBuilder()
        val g = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        b.append(g.toJson(e))
        return b.toString()
    }
}
