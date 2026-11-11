package com.pagefold.reader.book

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** 一本书的元数据。正文不入库，只存文件路径和阅读位置。 */
data class BookMeta(
    val id: String,
    val title: String,
    val path: String,
    val bytes: Long,
    val chars: Long,
    val chapters: Int,
    val addedAt: Long,
    val openedAt: Long = 0L,
    val chapterIndex: Int = 0,
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

/**
 * 书库。一个 JSON 文件，一次读写。
 * 正文永远留在原文件 / files/books 下，不复制、不拼接，避免内存爆掉。
 */
class Shelf(ctx: Context) {
    private val ctx: Context = ctx.applicationContext
    private val dir = File(ctx.filesDir, "books").apply { mkdirs() }
    private val idx = File(ctx.filesDir, "shelf.json")
    private val gson = Gson()

    fun dir(): File = dir

    /**
     * 全部的书。
     *
     * **必须过一遍 [fix]。** Gson 不走 Kotlin 构造器，也不看可空性 ——
     * 旧存档里没有的键（tuneJson、castJson、group、cover、rate…）会被塞成 null，
     * 声明成非空 String 也挡不住。之后任何一句 `.isBlank()` 就是 NPE，
     * 而这段代码在 Application.onCreate 里被调到，一炸应用直接消失。
     *
     * 「覆盖安装就闪退、删掉重装就好」就是这么来的：新装没有旧存档，一个 null 都碰不上。
     */
    fun all(): List<BookMeta> = runCatching {
        if (!idx.exists()) return@runCatching emptyList()
        val t = object : TypeToken<List<BookMeta>>() {}.type
        val raw = gson.fromJson<List<BookMeta>>(idx.readText(), t) ?: emptyList()
        raw.mapNotNull { runCatching { fix(it) }.getOrNull() }
    }.getOrDefault(emptyList())

    /** 把旧存档里读出来的 null 逐个兜平。只兜不猜，缺的给空值。 */
    private fun fix(b: BookMeta): BookMeta = b.copy(
        id = b.id ?: "",
        title = b.title ?: "未命名",
        path = b.path ?: "",
        cover = b.cover ?: "",
        group = b.group ?: "",
        castJson = b.castJson ?: "",
        tuneJson = b.tuneJson ?: "",
    )

    fun save(list: List<BookMeta>) {
        runCatching { idx.writeText(gson.toJson(list)) }
    }

    fun add(meta: BookMeta): List<BookMeta> {
        val list = all().toMutableList()
        list.removeAll { it.id == meta.id }
        list.add(0, meta)
        save(list)
        return list
    }

    fun remove(id: String): List<BookMeta> {
        val list = all().toMutableList()
        val target = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        save(list)
        if (target != null) runCatching { File(target.path).delete() }
        /* 这本书的句库和章节目录跟着走，不然删了书还占十几 MB。 */
        runCatching { Lines.drop(ctx, id) }
        runCatching { Chapters.drop(ctx, id) }
        return list
    }

    fun touch(id: String, chapter: Int, offset: Int, progress: Float): List<BookMeta> {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(
                chapterIndex = chapter, charOffset = offset,
                progress = progress, openedAt = System.currentTimeMillis()
            )
            save(list)
        }
        return list
    }

    /** 整条替换。改名、换封面、改字数统计都走这里。 */
    fun update(meta: BookMeta): List<BookMeta> {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == meta.id }
        if (i >= 0) {
            list[i] = meta
            save(list)
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

    /**
     * 读某一章的正文。
     *
     * 台账要一段一段摆出来，但它只需要**这一章**。整本读出来再切，
     * 一本三百万字的书就是七兆字符一次进堆 —— 在这样的机器上够呛。
     */
    fun readChapter(id: String, ch: Int): String {
        val bm = find(id) ?: return ""
        /*
         * 目录只读落盘那份，不读整本正文。
         *
         * 上一版是 read(id).substring(...)：读整本（三百万字 ≈ 七兆字符）+ 再切一份，
         * 台账翻一章就来一次，翻上七八页就顶到堆顶。现在只开一个 32K 的读缓冲，
         * 一边读一边累计字符数，只把落在这一章里的那几段留下来。
         */
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
