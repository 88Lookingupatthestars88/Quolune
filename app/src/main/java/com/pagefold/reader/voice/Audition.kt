package com.pagefold.reader.voice

import android.media.MediaPlayer

/**
 * 试听用的播放器，全局一个。
 *
 * 放在页面里的话，一切页 State 就销毁、MediaPlayer 跟着 release，
 * 声音立刻断 —— 「切到另一页就不能继续播放了」就是这个。
 * 挂在这里，换页、换标签都不受影响；想停按同一个按钮。
 */
object Audition {

    private var mp: MediaPlayer? = null

    /** 正在响的那一副。null = 没在响。 */
    @Volatile
    var playingId: String? = null
        private set

    /** 每次换段落自增，回调里对一下，防止旧的回调把新的状态清掉。 */
    private var gen = 0

    fun isOn(id: String): Boolean = playingId == id

    fun stop() {
        gen++
        playingId = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
    }

    /** 播一个本地文件。失败回 false。 */
    fun play(id: String, path: String, onEnd: (() -> Unit)? = null): Boolean {
        stop()
        val mine = gen
        val p = MediaPlayer()
        return try {
            p.setDataSource(path)
            p.setOnCompletionListener {
                if (mine == gen) {
                    playingId = null
                    onEnd?.invoke()
                }
                runCatching { it.release() }
            }
            p.setOnErrorListener { _, _, _ ->
                if (mine == gen) playingId = null
                true
            }
            p.prepare()
            p.start()
            mp = p
            playingId = id
            true
        } catch (_: Throwable) {
            runCatching { p.release() }
            playingId = null
            false
        }
    }
}
