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
