package com.pagefold.reader.book

import androidx.compose.runtime.mutableIntStateOf
import java.util.SortedMap

/**
 * 「这一章每一句归谁」的账。键是「书 + 角色表 + 章号」。
 * [tick] 每落一章加一，界面读它决定要不要重排。
 */
object Marks {

    private val done = java.util.concurrent.ConcurrentHashMap<String, SortedMap<Int, String>>()
    private val busy = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val gen = mutableIntStateOf(0)

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

    /** 角色表改过、书被换过：这一本的旧账全作废。 */
    fun dropBook(bookId: String) {
        val pre = "$bookId|"
        done.keys.removeAll { it.startsWith(pre) }
        busy.keys.removeAll { it.startsWith(pre) }
        gen.intValue = gen.intValue + 1
    }
}
