package com.pagefold.reader.book

import android.content.Context
import java.io.File

/**
 * 一个角色说过的话，按章排好的索引。进纸只读这份，不再逐行解析 jsonl。
 */
object LineIndex {

    /* 第一版的索引是坏的（偏移算法见上）。 */
    private const val MAGIC = 0x4C4E4959 // LNIY（第二版）
    private const val REC = 12
    private const val CACHE = 3

    private class One(val ch: IntArray, val at: IntArray, val tx: IntArray, val body: ByteArray)

    private val mem = LinkedHashMap<String, One>()
    private val lock = Any()

    private fun f(ctx: Context, bookId: String, name: String): File =
        File(File(File(ctx.filesDir, "lines"), bookId), safe(name) + ".idx")

    /** 名字里可能有 `/`、`:`、空格。落盘用它的安全形式，读的时候用同一把尺子。 */
    fun safe(name: String): String {
        val sb = StringBuilder(name.length + 8)
        name.forEach { c ->
            sb.append(
                when {
                    c.isLetterOrDigit() -> c
                    c == '_' || c == '-' -> c
                    else -> '_' + Integer.toHexString(c.code)
                }
            )
        }
        return if (sb.length > 80) sb.substring(0, 80) + sb.hashCode().toString(16) else sb.toString()
    }

    fun write(ctx: Context, bookId: String, name: String, list: List<Quote>) {
        val dir = File(File(ctx.filesDir, "lines"), bookId).apply { mkdirs() }
        val out = File(dir, safe(name) + ".idx")
        val tmp = File(dir, out.name + ".part")
        /* 先把文本编码成字节，再记每一句的字节起点。 */
        val body = java.io.ByteArrayOutputStream(1 shl 16)
        val tx = IntArray(list.size)
        list.forEachIndexed { i, q ->
            tx[i] = body.size()
            body.write(q.text.toByteArray(Charsets.UTF_8))
            body.write(0)
        }
        val bytes = body.toByteArray()
        runCatching {
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(list.size)
                list.forEachIndexed { i, q ->
                    o.writeInt(q.ch); o.writeInt(q.at); o.writeInt(tx[i])
                }
                o.writeInt(bytes.size)
                o.write(bytes)
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        }.onFailure { runCatching { tmp.delete() } }
        synchronized(lock) { mem.remove(bookId + "/" + name) }
    }

    /** 这本书有没有为这个人建过索引。 */
    fun has(ctx: Context, bookId: String, name: String): Boolean =
        f(ctx, bookId, name).exists()

    /** 旧版（坏的）索引一律作废。读不到就当这本书这个人没有索引 —— 调用方退回读 jsonl，那份是对的。 */
    private fun stale(file: File): Boolean {
        val head = runCatching {
            java.io.DataInputStream(file.inputStream().buffered()).use { it.readInt() }
        }.getOrNull()
        return head != null && head != MAGIC
    }

    /**
     * 读它。没有就 null —— 调用方退回读 jsonl（旧书、还没补扫的）。
     * @return (章号, 章内偏移, 文本) 三条对齐的数组
     */
    fun of(ctx: Context, bookId: String, name: String): Triple<IntArray, IntArray, Array<String>>? {
        val key = bookId + "/" + name
        synchronized(lock) {
            mem[key]?.let { one ->
                val out = Array(one.ch.size) { i -> cut(one.body, one.tx[i]) }
                return Triple(one.ch, one.at, out)
            }
        }
        val file = f(ctx, bookId, name)
        if (!file.exists()) return null
        if (stale(file)) { runCatching { file.delete() }; return null }
        val one = runCatching { load(file) }.getOrNull() ?: return null
        synchronized(lock) {
            mem[key] = one
            if (mem.size > CACHE) {
                val first = mem.keys.firstOrNull()
                if (first != null && first != key) mem.remove(first)
            }
        }
        val out = Array(one.ch.size) { i -> cut(one.body, one.tx[i]) }
        return Triple(one.ch, one.at, out)
    }

    private fun cut(body: ByteArray, from: Int): String {
        if (from < 0 || from >= body.size) return ""
        /* 起点必须落在一个字的开头。UTF-8 里非 ASCII 的第一个字节是 110x/1110/11110，后续字节是 10xx。 */
        var s = from
        var guard = 0
        while (s < body.size && (body[s].toInt() and 0xC0) == 0x80 && guard++ < 4) s++
        var e = s
        while (e < body.size && body[e] != 0.toByte()) e++
        if (e <= s) return ""
        return String(body, s, e - s, Charsets.UTF_8)
    }

    private fun load(file: File): One {
        java.io.DataInputStream(file.inputStream().buffered()).use { i ->
            if (i.readInt() != MAGIC) throw IllegalStateException("bad")
            val n = i.readInt()
            if (n < 0 || n > 5_000_000) throw IllegalStateException("bad n")
            val ch = IntArray(n)
            val at = IntArray(n)
            val tx = IntArray(n)
            for (k in 0 until n) {
                ch[k] = i.readInt(); at[k] = i.readInt(); tx[k] = i.readInt()
            }
            val len = i.readInt()
            if (len < 0 || len > 64_000_000) throw IllegalStateException("bad len")
            val body = ByteArray(len)
            i.readFully(body)
            return One(ch, at, tx, body)
        }
    }

    /** 删掉这本书所有索引（删书时）。 */
    fun drop(ctx: Context, bookId: String) {
        runCatching { File(File(ctx.filesDir, "lines"), bookId).deleteRecursively() }
        synchronized(lock) { mem.keys.filter { it.startsWith("$bookId/") }.forEach { mem.remove(it) } }
    }
}
