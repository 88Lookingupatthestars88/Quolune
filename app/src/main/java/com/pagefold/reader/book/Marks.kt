package com.pagefold.reader.book

import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.util.SortedMap
import java.util.TreeMap

/** 「这一章每一句归谁」的账。键 = 书 + 角色表 + 章号，落在 files/marks/<书>/<章>，头一行是角色表指纹，对得上就直接读盘。 */
object Marks {

    private val done = java.util.concurrent.ConcurrentHashMap<String, SortedMap<Int, String>>()
    private val busy = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val gen = mutableIntStateOf(0)

    /** 落盘的根。App.onCreate 里接一次；没接上就只在内存里记。 */
    private var root: File? = null

    fun attach(dir: File) {
        root = File(dir, "marks")
        runCatching { root?.mkdirs() }
    }

    fun key(bookId: String, castKey: String, ch: Int): String = "$bookId|$castKey|$ch"

    fun tick(): Int = gen.intValue

    fun get(k: String): SortedMap<Int, String>? = done[k]

    /** 占住这一章。false = 别人正在算。 */
    fun claim(k: String): Boolean = busy.putIfAbsent(k, true) == null

    fun yield(k: String) {
        busy.remove(k)
    }

    fun put(k: String, v: SortedMap<Int, String>) {
        done[k] = v
        gen.intValue = gen.intValue + 1
    }

    /** 一次落好几章，[tick] 只加一次。 */
    fun putAll(all: Map<String, SortedMap<Int, String>>) {
        if (all.isEmpty()) return
        done.putAll(all)
        gen.intValue = gen.intValue + 1
    }

    /** 角色表改过、书被换过：这一本的旧账全作废，盘上那份一起删。 */
    fun dropBook(bookId: String) {
        val pre = "$bookId|"
        done.keys.removeAll { it.startsWith(pre) }
        busy.keys.removeAll { it.startsWith(pre) }
        gen.intValue = gen.intValue + 1
        runCatching { File(root, safe(bookId)).deleteRecursively() }
    }

    /* ── 落盘 ── */

    /** 书名里可能有斜杠，落成目录名之前先换掉。 */
    private fun safe(bookId: String): String =
        bookId.replace('/', '_').replace('\\', '_').trim().ifBlank { "x" }

    /** 把这一章的账写下去。 */
    fun toDisk(bookId: String, castKey: String, ch: Int, v: SortedMap<Int, String>) {
        val r = root ?: return
        runCatching {
            val d = File(r, safe(bookId)).apply { mkdirs() }
            val f = File(d, ch.toString())
            val tmp = File(d, "$ch.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(castKey)
                w.write("\n")
                v.forEach { (at, who) -> w.write("$at\t$who\n") }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /** 从盘上捞一章回内存。 */
    private fun readOne(bookId: String, castKey: String, ch: Int): SortedMap<Int, String>? {
        val r = root ?: return null
        val f = File(File(r, safe(bookId)), ch.toString())
        if (!f.isFile) return null
        return runCatching {
            f.bufferedReader(Charsets.UTF_8).use { br ->
                if (br.readLine() != castKey) return@use null
                val m = TreeMap<Int, String>()
                while (true) {
                    val line = br.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val at = line.substring(0, tab).toIntOrNull() ?: continue
                    m[at] = line.substring(tab + 1)
                }
                m
            }
        }.getOrNull()
    }

    /**
     * 一次捞几章。全是 IO，调用方要放在 IO 线程。
     *
     * @return 捞回来的章数
     */
    fun preload(bookId: String, castKey: String, chs: List<Int>): Int {
        if (root == null) return 0
        var n = 0
        chs.forEach { ch ->
            val k = key(bookId, castKey, ch)
            if (done[k] != null) return@forEach
            val got = readOne(bookId, castKey, ch) ?: return@forEach
            done[k] = got
            n++
        }
        if (n > 0) gen.intValue = gen.intValue + 1
        return n
    }
}
