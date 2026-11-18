package com.pagefold.reader.book

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 书名和作者拆开。文件名里「作者：」之后那截单放一格。 */
fun splitTitle(raw: String): Pair<String, String> {
    val t = raw.trim().trim('《', '》', '"', '“', '”')
    val k = t.indexOf("作者")
    if (k <= 0) return t.take(60) to ""
    val head = t.substring(0, k).trim().trimEnd('，', ',', '·', '-', '、', ' ')
    val who = t.substring(k + 2).trimStart('：', ':', ' ', '\u3000').trim().take(24)
    return if (head.isBlank()) t.take(60) to "" else head.take(60) to who
}

/** 一本书的元数据。正文不入库，只存文件路径和阅读位置。 */
data class BookMeta(
    val id: String,
    val title: String,
    /** 作者。收书时从文件名里拆下来，单放一格。 */
    val author: String = "",
    val path: String,
    val bytes: Long,
    val chars: Long,
    val chapters: Int,
    val addedAt: Long,
    val openedAt: Long = 0L,
    val chapterIndex: Int = 0,
    /** 这一章在书里叫「第几章」。目录开头常有一个「序」，它没有章号 —— 拿位置 + 1 当章号会多算一章。0 = 还没写过，退回位置 + 1。 */
    val chapterNo: Int = 0,
    /** 这一章在书里叫什么（「序」「第七十章 一封信」）。空 = 只知道第几章。 */
    val chapterName: String = "",
    val charOffset: Int = 0,
    val progress: Float = 0f,
    val tintSeed: Int = 0,
    /** 用户自己选的封面图，存在 files/covers 下。空 = 用书名生成的那块布面。 */
    val cover: String = "",
    /** 归在哪一架。空 = 未归类，只在「全部」里出现。 */
    val group: String = "",
    /** 这本书自己的角色表（CastBook 的 JSON）。空 = 用全局。 */
    val castJson: String = "",
    /** 这本书自己的念速 ×100。0 = 跟着全局那个走。 */
    val rate: Int = 0,
    /** 这本书自己的读法（前情、预取、合成长度…）。空 = 全跟定音。 */
    val tuneJson: String = "",
)

/** 书库。一个 JSON 文件，一次读写。 */
class Shelf(ctx: Context) {
    private val ctx: Context = ctx.applicationContext
    private val dir = File(ctx.filesDir, "books").apply { mkdirs() }
    private val idx = File(ctx.filesDir, "shelf.json")
    /* 每本书自己那份底：书名、作者、角色表、读法。总账坏了靠它把书捡回来。 */
    private val side = File(ctx.filesDir, "cast").apply { mkdirs() }
    private val gson = Gson()

    /* 内存里的那一份。所有方法经 all() 读它。 */
    private var mem: List<BookMeta>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    fun dir(): File = dir

    /** 全部的书，过一遍 [fix]。shelf.json 读得出来就用；读出来是空的就从 `books/` 重建。 */
    fun all(): List<BookMeta> {
        mem?.let { return it }
        val fromIdx = runCatching {
            if (!idx.exists()) return@runCatching emptyList()
            parse(idx.readText())
        }.getOrDefault(emptyList())

        /* 存档里没记着的书，从 `books/` 目录补回来。判据是「正文文件在、存档里没有这一条」—— 删书会把文件一起删掉，所以这不是误判。 */
        val have = fromIdx.map { it.id }.toHashSet()
        val orphans = runCatching { rebuild().filter { it.id !in have } }.getOrDefault(emptyList())
        val list = if (orphans.isEmpty()) fromIdx else {
            val merged = (fromIdx + orphans).sortedByDescending { it.addedAt }
            /* 捡回来这件事本身要落盘：下次不必再扫目录。 */
            runCatching { write(merged) }
            merged
        }
        mem = list
        return list
    }

    /** 从 `books/` 捞书。只认 .txt / .epub，文件名去掉扩展名就是 id（和句库、签子、进度同一个键）。 */
    private fun rebuild(): List<BookMeta> {
        val files = dir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".epub", true))
        } ?: return emptyList()
        if (files.isEmpty()) return emptyList()
        val out = ArrayList<BookMeta>(files.size)
        files.forEach { f ->
            /* 文件名就是 id（收书时是 id.ext 这么起的），拿它跟旧存档、句库、签子对得上。 */
            val id = f.name.substringBeforeLast('.')
            if (id.isBlank()) return@forEach
            /* 有底就读底：书名、角色表、读法、进度全在里头，比现猜的准。 */
            val kept = readAside(id)
            out += if (kept != null) {
                kept.copy(
                    path = f.absolutePath,
                    bytes = f.length(),
                    chars = if (kept.chars > 0) kept.chars else (f.length() / 3).coerceAtLeast(1),
                )
            } else {
                val (t, who) = titleOf(f, id)
                BookMeta(
                    id = id,
                    title = t,
                    author = who,
                    path = f.absolutePath,
                    bytes = f.length(),
                    /* 字数按字节估：这是给架上那一行看的，不参与排版。汉字 UTF-8 三字节。 */
                    chars = (f.length() / 3).coerceAtLeast(1),
                    /* 章数留 0，打开那本书的时候切出来再写回。 */
                    chapters = 0,
                    addedAt = f.lastModified(),
                )
            }
        }
        return out.sortedByDescending { it.addedAt }
    }

    /** 这本书自己那份底写到 `files/cast/<书号>.json`。 */
    private fun setAside(m: BookMeta) {
        runCatching { File(side, m.id + ".json").writeText(gson.toJson(m)) }
    }

    /** 读回来。没有就是 null。 */
    private fun readAside(id: String): BookMeta? = runCatching {
        val f = File(side, id + ".json")
        if (!f.exists() || f.length() <= 2) null else parse("[" + f.readText() + "]").firstOrNull()
    }.getOrNull()

    /** 重建时这本书叫什么。正文头一行通常是书名，取不到才退回文件名。 */
    private fun titleOf(f: File, id: String): Pair<String, String> {
        val head = runCatching {
            f.bufferedReader(Charsets.UTF_8).use { r ->
                val sb = StringBuilder(64)
                var n = 0
                var name = ""
                var who = ""
                while (n < 12 && sb.length < 400) {
                    val line = r.readLine() ?: break
                    val t = line.trim().trim('　', '《', '》', '"', '“', '”')
                    if (t.isNotEmpty()) {
                        if (name.isBlank()) name = t
                        else if (who.isBlank() && t.startsWith("作者")) who = t
                        sb.append(t).append('\n')
                    }
                    n++
                }
                name to who
            }
        }.getOrNull() ?: ("" to "")
        val (name, who) = head
        val split = splitTitle(name)
        val t = split.first.ifBlank { name }.take(40).trim()
        val author = split.second.ifBlank { splitTitle(who).first.take(24) }
            .removePrefix("作者").trimStart('：', ':', ' ', '\u3000')
        return (if (t.length in 2..40) t else id) to author.take(24)
    }

    /** 读存档。逐字段从 JSON 里取，缺的给默认值。 */
    private fun parse(raw: String): List<BookMeta> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<BookMeta>(arr.size())
        arr.forEach { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
            val id = str(o, "id")
            if (id.isBlank()) return@forEach
            /* 老存档里书名整串带着「作者：某某」，读出来顺手拆开。 */
            val rawTitle = str(o, "title").ifBlank { "未命名" }
            val rawAuthor = str(o, "author")
            val (t0, a0) = if (rawAuthor.isBlank() && rawTitle.contains("作者")) splitTitle(rawTitle)
            else rawTitle to rawAuthor
            out += BookMeta(
                id = id,
                title = t0.ifBlank { "未命名" },
                author = a0,
                path = str(o, "path"),
                bytes = lon(o, "bytes"),
                chars = lon(o, "chars"),
                chapters = num(o, "chapters"),
                addedAt = lon(o, "addedAt"),
                openedAt = lon(o, "openedAt"),
                chapterIndex = num(o, "chapterIndex"),
                chapterNo = num(o, "chapterNo"),
                chapterName = str(o, "chapterName"),
                charOffset = num(o, "charOffset"),
                progress = flt(o, "progress"),
                tintSeed = num(o, "tintSeed"),
                cover = str(o, "cover"),
                group = str(o, "group"),
                castJson = str(o, "castJson"),
                rate = num(o, "rate"),
                tuneJson = str(o, "tuneJson"),
            )
        }
        return out
    }

    private fun str(o: com.google.gson.JsonObject, k: String): String =
        runCatching { o.get(k)?.takeIf { !it.isJsonNull }?.asString }.getOrNull().orEmpty()

    private fun num(o: com.google.gson.JsonObject, k: String): Int =
        runCatching { o.get(k)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull() ?: 0

    private fun lon(o: com.google.gson.JsonObject, k: String): Long =
        runCatching { o.get(k)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull() ?: 0L

    private fun flt(o: com.google.gson.JsonObject, k: String): Float =
        runCatching { o.get(k)?.takeIf { !it.isJsonNull }?.asFloat }.getOrNull() ?: 0f

    /** 落盘：先写临时文件再改名；空表不盖非空存档。 */
    fun save(list: List<BookMeta>, allowEmpty: Boolean = false) {
        mem = list
        pending?.cancel()
        pending = scope.launch {
            delay(1500)
            write(list, allowEmpty)
        }
    }

    /** 落盘：先写临时文件再改名；空表不盖非空存档。 */
    private fun write(list: List<BookMeta>, allowEmpty: Boolean = false) {
        runCatching {
            if (!allowEmpty && list.isEmpty() && idx.exists() && idx.length() > 2) return@runCatching
            val tmp = File(idx.parentFile, idx.name + ".part")
            tmp.writeText(gson.toJson(list))
            if (idx.exists()) runCatching { idx.copyTo(File(idx.parentFile, idx.name + ".bak"), true) }
            if (!tmp.renameTo(idx)) {
                idx.delete()
                tmp.renameTo(idx)
            }
        }
    }

    /** 立刻落盘。退到后台、离开书的时候调一次。 */
    fun flush() {
        pending?.cancel()
        pending = null
        /* 退出时也走同一道闸：内存里是空的就一个字都别写。 */
        mem?.let { write(it, allowEmpty = false) }
    }

    fun add(meta: BookMeta): List<BookMeta> {
        val list = all().toMutableList()
        list.removeAll { it.id == meta.id }
        list.add(0, meta)
        save(list)
        flush()
        setAside(meta)
        return list
    }

    fun remove(id: String): List<BookMeta> {
        runCatching { File(side, id + ".json").delete() }
        val list = all().toMutableList()
        val target = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        save(list, allowEmpty = true)
        /* 正文文件下面马上就删了，索引必须同一刻落盘，否则中途被杀就留一条指向空文件的记录。 */
        flush()
        if (target != null) runCatching { File(target.path).delete() }
        /* 这本书的句库和章节目录跟着走，不然删了书还占十几 MB。 */
        runCatching { Lines.drop(ctx, id) }
        runCatching { Chapters.drop(ctx, id) }
        return list
    }

    fun touch(
        id: String,
        chapter: Int,
        offset: Int,
        progress: Float,
        chapterNo: Int = 0,
        chapterName: String = "",
    ): List<BookMeta> {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            val before = list[i]
            list[i] = before.copy(
                chapterIndex = chapter, charOffset = offset,
                progress = progress, openedAt = System.currentTimeMillis(),
                chapterNo = chapterNo,
                chapterName = chapterName,
            )
            save(list)
            /* 换章才落一次底。翻一页写一次，一页几百字就写几百回，闪存受不了。 */
            if (before.chapterIndex != chapter) setAside(list[i])
        }
        return list
    }

    /** 切出来的章数跟架上记的不一样时回写一次。架上写「941 章」、书里只有 94 章，是因为切章判据变过、老账没刷新。 */
    fun setChapters(id: String, n: Int): List<BookMeta> {
        val m = find(id) ?: return all()
        if (n <= 0 || m.chapters == n) return all()
        return update(m.copy(chapters = n))
    }

    fun update(meta: BookMeta): List<BookMeta> {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == meta.id }
        if (i >= 0) {
            list[i] = meta
            save(list)
            setAside(meta)
        }
        return list
    }

    fun rename(id: String, title: String): List<BookMeta> {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val t = title.trim()
        if (i >= 0 && t.isNotBlank()) {
            list[i] = list[i].copy(title = t.take(60))
            save(list)
        }
        return list
    }

    /** 读某一章的正文。台账要一段一段摆出来，但它只需要这一章。 */
    fun readChapter(id: String, ch: Int): String {
        val bm = find(id) ?: return ""
        /* 目录只读落盘那份，不读整本正文。 */
        val list = Chapters.cached(ctx, id) ?: return ""
        val c = list.getOrNull(ch) ?: return ""
        val from = c.start.coerceAtLeast(0)
        val to = c.end.coerceAtLeast(from)
        val out = StringBuilder(minOf(to - from, 1 shl 18) + 16)
        runCatching {
            File(bm.path).inputStream().use { ins ->
                val r = java.io.InputStreamReader(ins, Charsets.UTF_8)
                val buf = CharArray(1 shl 15)
                var pos = 0
                while (true) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    val a = pos
                    val b = pos + n
                    if (b > from && a < to) {
                        val s = (from - a).coerceAtLeast(0)
                        val e = (to - a).coerceAtMost(n)
                        if (e > s) out.append(buf, s, e - s)
                    }
                    pos = b
                    if (pos >= to) break
                }
            }
        }
        return out.toString()
    }

    /** 目录建成没有。台账要按这个说一句「先进这本书」。 */
    fun hasIndex(id: String): Boolean = Chapters.cached(ctx, id)?.isNotEmpty() == true

    fun find(id: String): BookMeta? = all().firstOrNull { it.id == id }

    fun setCover(id: String, path: String): List<BookMeta> {
        val m = find(id) ?: return all()
        return update(m.copy(cover = path))
    }

    fun setGroup(id: String, group: String): List<BookMeta> {
        val m = find(id) ?: return all()
        return update(m.copy(group = group))
    }

    fun setCast(id: String, json: String): List<BookMeta> {
        val m = find(id) ?: return all()
        return update(m.copy(castJson = json))
    }

    fun setTune(id: String, json: String): List<BookMeta> {
        val m = find(id) ?: return all()
        return update(m.copy(tuneJson = json))
    }

    fun setRate(id: String, rate: Int): List<BookMeta> {
        val m = find(id) ?: return all()
        return update(m.copy(rate = rate.coerceIn(50, 300)))
    }

    /** 封面图放这儿。原图可能是任意格式，交给 BitmapFactory 去解。 */
    fun coverDir(ctx: android.content.Context): File =
        File(ctx.filesDir, "covers").apply { mkdirs() }


    fun read(id: String): String? {
        val meta = all().firstOrNull { it.id == id } ?: return null
        return runCatching { File(meta.path).readText() }.getOrNull()
    }
}
