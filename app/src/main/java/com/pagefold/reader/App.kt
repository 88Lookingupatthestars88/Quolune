package com.pagefold.reader

import android.app.Application
import java.io.File
import com.pagefold.reader.book.Shelf
import com.pagefold.reader.field.Field
import com.pagefold.reader.voice.Synth
import com.pagefold.reader.voice.SysVoice
import com.pagefold.reader.voice.VoiceBox

/**
 * 应用级单例容器。所有长生命周期对象挂在这里：
 * 设置、书库、音色库、合成器、系统语音。
 * 播放器是 Service，单独活。
 */
class App : Application() {

    lateinit var field: Field
        private set
    lateinit var shelf: Shelf
        private set
    lateinit var box: VoiceBox
        private set
    /** 每副音色自己那份调法。没调过的音色不在这张表里，跟着全局走。 */
    lateinit var vtune: com.pagefold.reader.field.VoiceTune
        private set
    lateinit var sys: SysVoice
        private set
    lateinit var synth: Synth
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        field = Field(this)
        shelf = Shelf(this)
        box = VoiceBox(this)
        vtune = com.pagefold.reader.field.VoiceTune(this)
        sys = SysVoice(this)
        synth = Synth(this, sys)
        // 每副音色自己那份调法，合成层要用它改语速、音调、音量。
        synth.tunes = vtune
        synth.sync(field.now)
        // 验过的结论要落盘，得先给它一个地方。不接这一句，它只在内存里记，
        // 重启应用就忘，于是"每进一次定音页就重验一遍"的老毛病又回来了。
        com.pagefold.reader.voice.Speak.attach(this)
        // 旧书补一张版式快照。补的时候定音里是什么，这本书就是什么，
        // 之后不再跟随 —— 一页一把，谁也不拽谁。
        // 旧书补一张版式快照。shelf.all() 已经把旧存档里的 null 兜平了，
        // 这里再加一层 runCatching —— 迁移失败不该把应用带走。
        runCatching {
            val now = field.now
            val list = shelf.all()
            var dirty = false
            val out = list.map { b ->
                val j = b.tuneJson ?: ""
                if (com.pagefold.reader.field.Tune.has(j, "fontSize") && com.pagefold.reader.field.Tune.has(j, "turn")) b
                else {
                    dirty = true
                    b.copy(tuneJson = com.pagefold.reader.field.Tune.snapshot(now, j))
                }
            }
            if (dirty) shelf.save(out)
        }
    }

    /**
     * 崩了别只留个闪退。
     *
     * 异常栈写到 filesDir/last-crash.txt。下次再闪，把这个文件拿出来就能定位，
     * 不用再靠猜。用 MT 管理器就能看：
     * /data/data/com.pagefold.reader/files/last-crash.txt
     */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            /*
             * 取消不是崩溃。
             *
             * 协程被 cancel 时抛的是 CancellationException。它有时候会一路冒到
             * 默认处理器 —— 那不是「应用出错」，是「这件事不做了」。
             * 既不该写进崩溃记录（会让人以为又崩了一次），也不该交给系统杀进程。
             */
            if (e is kotlinx.coroutines.CancellationException) {
                return@setDefaultUncaughtExceptionHandler
            }
            val dump = buildString {
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()))
                append('\n').append(t?.name).append('\n')
                append(android.util.Log.getStackTraceString(e))
            }
            runCatching { File(filesDir, "last-crash.txt").writeText(dump) }
            /*
             * 再写一份到**外置**私有目录：
             * /sdcard/Android/data/com.pagefold.reader/files/last-crash.txt
             *
             * 内部目录要 root 才能看，绝大多数人拿不到；外置这一个用手机自带文件管理器
             * 就能打开，复制出来发给开发者即可。只写主线程之外也不怕，runCatching 兜着。
             */
            runCatching {
                getExternalFilesDir(null)?.let { File(it, "last-crash.txt").writeText(dump) }
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onTerminate() {
        runCatching { sys.shutdown() }
        super.onTerminate()
    }
}
