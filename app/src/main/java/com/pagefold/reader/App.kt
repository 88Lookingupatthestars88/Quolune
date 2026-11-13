package com.pagefold.reader

import android.app.Application
import kotlinx.coroutines.launch
import java.io.File
import com.pagefold.reader.book.Shelf
import com.pagefold.reader.field.Field
import com.pagefold.reader.voice.Synth
import com.pagefold.reader.voice.SysVoice
import com.pagefold.reader.voice.VoiceBox

/** 应用级单例容器。所有长生命周期对象挂在这里：设置、书库、音色库、合成器、系统语音。 */
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
        // 给「验过的结论」一个落盘的地方，不接就只在内存里记。
        com.pagefold.reader.voice.Speak.attach(this)
        /* 旧书补版式快照。挪到后台线程：这一步要读 shelf.json、给每本书拼一份 JSON。 */
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            .launch {
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
    }

    /** 崩了别只留个闪退。异常栈写到 filesDir/last-crash.txt。 */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            /* 取消不是崩溃：协程 cancel 抛 CancellationException，不记崩溃、不杀进程。 */
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
            /* 再写一份到外置私有目录。只写主线程之外也不怕，runCatching 兜着。 */
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
