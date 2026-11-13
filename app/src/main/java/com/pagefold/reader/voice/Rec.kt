package com.pagefold.reader.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** 录一段话，直接录成 WAV。 */
object Rec {

    const val RATE = 44100

    /** 一路录到底最多这么久。超过就自己收手 —— 再长对面也不要。 */
    const val MAX_SEC = 58

    /**
     * 开一条录音。
     * @return null = 这台机器现在录不了（没权限、麦克风被占）
     */
    @SuppressLint("MissingPermission")
    fun open(): Take? = runCatching {
        val min = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            min * 4,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return null
        }
        ar.startRecording()
        Take(ar, min)
    }.getOrNull()
}

/** 一段正在进行的录音。只在 IO 线程上用。 */
class Take internal constructor(
    private val ar: AudioRecord,
    private val bufSize: Int,
) {
    private val pcm = ByteArrayOutputStream(Rec.RATE * 2 * 20)
    @Volatile private var stopped = false

    /** 已经录了多少秒。界面拿去走秒。 */
    val sec: Int get() = pcm.size() / (Rec.RATE * 2)

    /** 满了就该自己收手。 */
    val full: Boolean get() = sec >= Rec.MAX_SEC

    /**
     * 读一轮。阻塞一小会儿，别在主线程调。
     * @return false = 读不出来了，该收手
     */
    fun pump(): Boolean {
        if (stopped) return false
        val b = ByteArray(bufSize)
        val n = runCatching { ar.read(b, 0, b.size) }.getOrDefault(-1)
        if (n <= 0) return false
        pcm.write(b, 0, n)
        return true
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { ar.stop() }
        runCatching { ar.release() }
    }

    /** 收尾：补上 WAV 头，把整段交出去。 */
    fun wav(): ByteArray {
        stop()
        val body = pcm.toByteArray()
        val out = ByteArrayOutputStream(44 + body.size)

        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }

        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray()); le32(36 + body.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16)
        le16(1)                              // PCM，不压缩
        le16(1)                              // 单声道
        le32(Rec.RATE)
        le32(Rec.RATE * 2)                       // 每秒字节数
        le16(2)                              // 一帧 2 字节
        le16(16)                             // 16 位
        out.write("data".toByteArray()); le32(body.size)
        out.write(body)
        return out.toByteArray()
    }
}
