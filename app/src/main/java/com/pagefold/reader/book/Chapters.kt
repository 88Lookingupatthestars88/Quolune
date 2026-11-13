package com.pagefold.reader.book

import android.content.Context
import com.pagefold.reader.App
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 章节目录的落盘缓存。一本 172 万字的书切一遍章要跑五万行、五行正则，几百毫秒到一秒。
 * 每次打开都重跑，就是「点开一本书要等半天」的根。
 */
object Chapters {

    private const val MAGIC = 0x544C4348 // TLCH

    fun of(ctx: android.content.Context, book: BookMeta, text: String): List<Chapter> {
        val dir = File(ctx.filesDir, "ch").apply { mkdirs() }
        val f = File(dir, book.id + ".idx")
        val sig = sigOf(text)

        if (f.exists()) {
            runCatching { load(f, sig) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }

        val cut = Parse.split(text)
        runCatching { save(f, sig, cut) }
        return cut
    }

    /** 只读落盘的那份目录，不切、不比对指纹。 */
    fun cached(ctx: Context, id: String): List<Chapter>? {
        val f = File(File(ctx.filesDir, "ch"), "$id.idx")
        if (!f.exists()) return null
        return runCatching { loadAny(f) }.getOrNull()
    }

    private fun loadAny(f: File): List<Chapter>? {
        DataInputStream(f.inputStream().buffered()).use { i ->
            if (i.readInt() != MAGIC) return null
            i.readLong()
            val n = i.readInt()
            if (n <= 0 || n > 200_000) return null
            val out = ArrayList<Chapter>(n)
            repeat(n) {
                val idx = i.readInt()
                val s = i.readInt()
                val e = i.readInt()
                val title = i.readUTF()
                out.add(Chapter(idx, title, s, e))
            }
            return out
        }
    }

    /** 清掉某本书的目录缓存（正文被替换、删书时用）。 */
    fun drop(app: Context, id: String) {
        runCatching { File(File(app.filesDir, "ch"), "$id.idx").delete() }
    }

    private fun sigOf(t: String): Long {
        var h = 1125899906842597L
        h = h * 31 + t.length
        val head = t.take(64)
        val tail = if (t.length > 128) t.takeLast(64) else ""
        for (c in head) h = h * 31 + c.code
        for (c in tail) h = h * 31 + c.code
        return h
    }

    private fun save(f: File, sig: Long, list: List<Chapter>) {
        val tmp = File(f.parentFile, f.name + ".part")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC)
            o.writeLong(sig)
            o.writeInt(list.size)
            list.forEach { c ->
                o.writeInt(c.index)
                o.writeInt(c.start)
                o.writeInt(c.end)
                o.writeUTF(c.title)
            }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    private fun load(f: File, sig: Long): List<Chapter>? {
        DataInputStream(f.inputStream().buffered()).use { i ->
            if (i.readInt() != MAGIC) return null
            if (i.readLong() != sig) return null
            val n = i.readInt()
            if (n <= 0 || n > 200_000) return null
            val out = ArrayList<Chapter>(n)
            repeat(n) {
                val idx = i.readInt()
                val s = i.readInt()
                val e = i.readInt()
                val title = i.readUTF()
                out.add(Chapter(idx, title, s, e))
            }
            return out
        }
    }
}
