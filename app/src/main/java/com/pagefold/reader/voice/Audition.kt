package com.pagefold.reader.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 试听用的播放器，全局一个。
 *
 * 谁在响，只有一个地方记着：这里。界面不许自己再存一份。
 *
 * 以前每一页各存一个 `playing`，靠播放器的回调去清 —— 回调没来、来晚了、
 * 或者那份状态已经被组合销毁，都会让按钮停在「停」上，而且再点一下走的是
 * 「停止」那条路，永远听不到第二遍。现在 `playingId` 本身就是 Compose 状态，
 * 播完在哪儿清的、谁清的都不重要，界面上看到的就是它。
 *
 * 回调一律回到主线程再改 —— MediaPlayer 的回调在某些机型上不在主线程，
 * 在那儿直接改状态，重组有可能收不到。
 */
object Audition {

    private val main = Handler(Looper.getMainLooper())

    private var mp: MediaPlayer? = null

    /** 每次换一段自增。旧回调对不上就直接丢掉。 */
    private var gen = 0

    /** 正在响的那一副。null = 没在响。 */
    var playingId: String? by mutableStateOf(null)
        private set

    fun isOn(id: String): Boolean = playingId == id

    fun stop() {
        gen++
        playingId = null
        val p = mp
        mp = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
    }

    /** 播一个本地文件。失败回 false，不抛。 */
    fun play(id: String, path: String): Boolean {
        stop()
        val mine = gen
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(path)
            p.setOnCompletionListener {
                main.post { if (mine == gen) playingId = null }
                runCatching { it.release() }
                if (mp === it) mp = null
            }
            p.setOnErrorListener { bad, _, _ ->
                main.post { if (mine == gen) playingId = null }
                runCatching { bad.release() }
                if (mp === bad) mp = null
                true
            }
            p.prepare()
            p.start()
            mp = p
            playingId = id
            true
        } catch (_: Throwable) {
            runCatching { p.release() }
            if (mp === p) mp = null
            playingId = null
            false
        }
    }
}
