package com.pagefold.reader.book

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** 章节目录的落盘缓存。 */
object Chapters {

    private const val MAGIC = 0x544C4348 // TLCH

    /** 切章的判据改过就加一。目录缓存不带版本的话，改完还是读老账，架上和书里对不上。 */
    private const val VER = 3

    fun of(ctx: Context, book: BookMeta, text: String): List<Chapter> {
        val dir = File(ctx.filesDir, "ch").apply { mkdirs() }
        val f = File(dir, book.id + ".idx")
        val sig = sigOf(text)

        if (f.exists()) {
            runCatching { load(f, sig)?.first }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }

        val cut = Parse.split(text)
        val bytes = Parse.byteStarts(text, cut)
        runCatching { save(f, sig, cut, bytes) }
        return cut
    }

    /** 只读落盘的那份目录，不切、不比对指纹。 */
    fun cached(ctx: Context, id: String): List<Chapter>? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { loadAny(f)?.first }.getOrNull()
    }

    /** 每章的 UTF-8 字节起点。没有缓存或版本对不上就是 null。 */
    fun bytes(ctx: Context, id: String): LongArray? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { loadAny(f)?.second }.getOrNull()
    }

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "ch"), "$id.idx")

    private fun loadAny(f: File): Pair<List<Chapter>, LongArray>? {
        DataInputStream(f.inputStream().buffered()).use { i ->
            if (i.readInt() != MAGIC) return null
            if (i.readInt() != VER) return null
            i.readLong()
            val n = i.readInt()
            if (n <= 0 || n > 200_000) return null
            val out = ArrayList<Chapter>(n)
            val bytes = LongArray(n)
            repeat(n) {
                val idx = i.readInt()
                val s = i.readInt()
                val e = i.readInt()
                bytes[idx] = i.readLong()
                val auto = i.readByte().toInt() != 0
                val title = i.readUTF()
                out.add(Chapter(idx, title, s, e, auto))
            }
            return out to bytes
        }
    }

    /** 清掉某本书的目录缓存（正文被替换、删书时用）。 */
    fun drop(ctx: Context, id: String) {
        runCatching { file(ctx, id).delete() }
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

    private fun save(f: File, sig: Long, list: List<Chapter>, bytes: LongArray) {
        val tmp = File(f.parentFile, f.name + ".part")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(VER)
            o.writeLong(sig)
            o.writeInt(list.size)
            list.forEachIndexed { k, c ->
                o.writeInt(c.index)
                o.writeInt(c.start)
                o.writeInt(c.end)
                o.writeLong(bytes.getOrElse(k) { 0L })
                o.writeByte(if (c.auto) 1 else 0)
                o.writeUTF(c.title)
            }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    private fun load(f: File, sig: Long): Pair<List<Chapter>, LongArray>? {
        DataInputStream(f.inputStream().buffered()).use { i ->
            if (i.readInt() != MAGIC) return null
            if (i.readInt() != VER) return null
            if (i.readLong() != sig) return null
            val n = i.readInt()
            if (n <= 0 || n > 200_000) return null
            val out = ArrayList<Chapter>(n)
            val bytes = LongArray(n)
            repeat(n) {
                val idx = i.readInt()
                val s = i.readInt()
                val e = i.readInt()
                bytes[idx] = i.readLong()
                val auto = i.readByte().toInt() != 0
                val title = i.readUTF()
                out.add(Chapter(idx, title, s, e, auto))
            }
            return out to bytes
        }
    }
}
