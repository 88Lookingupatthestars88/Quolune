package com.pagefold.reader.play

import android.os.SystemClock

/** 睡眠定时。只管一个绝对时刻，不管播不播 —— 所以随时暂停、随时继续，倒计时照走。 */
object Sleep {
    /** SystemClock.elapsedRealtime 的时刻。0 = 没开。 */
    @Volatile var deadline: Long = 0L
        private set

    val on: Boolean get() = deadline > 0L

    /** 还剩几秒。没开是 0。 */
    fun leftSec(): Int {
        val d = deadline
        if (d <= 0L) return 0
        val ms = d - SystemClock.elapsedRealtime()
        return if (ms <= 0L) 0 else ((ms + 999) / 1000).toInt()
    }

    /** 到点了没有。 */
    fun fired(): Boolean {
        val d = deadline
        return d > 0L && SystemClock.elapsedRealtime() >= d
    }

    fun start(minutes: Int) {
        val m = minutes.coerceIn(1, 600)
        deadline = SystemClock.elapsedRealtime() + m * 60_000L
    }

    fun stop() { deadline = 0L }

    /** 剩多久。过了半小时就带钟点，看着像 1:05:00，不像 65:00。 */
    fun mmss(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
}
