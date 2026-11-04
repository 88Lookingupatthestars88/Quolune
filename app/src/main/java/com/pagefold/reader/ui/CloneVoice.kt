package com.pagefold.reader.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pagefold.reader.App
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.voice.CloneApi
import com.pagefold.reader.voice.Rec
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 拿一段话，做一副嗓子。
 *
 * 两种来路摆在同一张纸上：当场录，或者挑一个现成的文件。
 * 录是一等入口 —— 想复刻自己的声音，谁都不会先去找个录音软件录好再回来。
 *
 * 各家做完给的东西不一样（见 [CloneApi]），所以字段跟着家的名字变：
 * 硅基多问一句「录音里说的是什么」，小米什么都不问。
 */
@Composable
internal fun CloneVoice(
    s: Settings,
    app: App,
    onClose: () -> Unit,
    onDone: (Voice, String) -> Unit,
) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val able = remember(s.enginesJson) {
        s.engines.filter { it.on && CloneApi.shape(it.protocol, it.base ?: "") != null }
    }

    var pick by remember { mutableStateOf(able.firstOrNull()?.id.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var record by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    // 手上这一段：可能是刚录的，也可能是刚挑的文件
    var clip by remember { mutableStateOf<ByteArray?>(null) }
    var clipSec by remember { mutableIntStateOf(0) }
    var fromFile by remember { mutableStateOf("") }

    // 录音
    var rec by remember { mutableStateOf<com.pagefold.reader.voice.Take?>(null) }
    var recSec by remember { mutableIntStateOf(0) }

    val engine = able.firstOrNull { it.id == pick }
    val shape = engine?.let { CloneApi.shape(it.protocol, it.base ?: "") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("打不开这个文件")
                    val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
                    bytes to mime
                }
            }
            busy = false
            r.onSuccess { (b, m) ->
                if (!okSize(b)) {
                    hint = "这段录音 ${mb(b)} MB，超过 10 MB 传不上去。换一段短的。"
                    return@onSuccess
                }
                clip = b
                clipSec = wavSec(b)
                fromFile = m
                hint = null
            }.onFailure { hint = it.message ?: "读不出来" }
        }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { hint = "没有麦克风权限，录不了。也可以挑一个现成的音频文件。"; return@rememberLauncherForActivityResult }
        val r = Rec.open()
        if (r == null) { hint = "麦克风打不开，可能被别的应用占着。"; return@rememberLauncherForActivityResult }
        rec = r
        recSec = 0
        clip = null
        hint = null
    }

    // 录音那一路：一格一格读，读满或按停就收。
    LaunchedEffect(rec) {
        val r = rec ?: return@LaunchedEffect
        while (true) {
            val alive = withContext(Dispatchers.IO) { r.pump() }
            recSec = r.sec
            if (!alive || r.full) break
            delay(80)
        }
        val bytes = withContext(Dispatchers.IO) { r.wav() }
        rec = null
        if (bytes.size < 44100 / 2) {
            hint = "录得太短了，说一句完整的话再停。"
            return@LaunchedEffect
        }
        clip = bytes
        clipSec = recSec
        fromFile = "audio/wav"
        hint = null
    }

    fun startRec() {
        if (rec != null) return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) askMic.launch(Manifest.permission.RECORD_AUDIO)
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    Modal(onClose) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 16.dp)
        ) {
            Text(
                "让引擎说出你的声音",
                fontSize = 20.sp, color = skin.ink, lineHeight = 27.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = skin.titleFont
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "说一段，它照着学。录得越干净越像你。",
                fontSize = 11.sp, color = skin.inkFaint, lineHeight = 16.sp
            )

            if (able.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "现在没有能复刻的嗓源。先去「定音」把 MiMo 打开，再回来。",
                    fontSize = 12.sp, color = skin.inkDim, lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                Btn("知道了", onClose, BtnKind.GHOST, modifier = Modifier.fillMaxWidth())
                return@Modal
            }

            if (able.size > 1) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    able.forEach { e ->
                        val on = e.id == pick
                        Text(
                            e.name, fontSize = 12.sp,
                            color = if (on) skin.flame else skin.inkDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (on) skin.flameGhost else skin.surface)
                                .border(
                                    1.dp,
                                    if (on) skin.flame.copy(alpha = 0.45f) else skin.hairSoft,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { pick = e.id }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // ── 一句话 ────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Saying(
                sec = recSec, recording = rec != null,
                has = clip != null && rec == null,
                ready = clipSec,
                onRec = { startRec() },
                onStop = { rec?.let { r -> scope.launch { withContext(Dispatchers.IO) { r.stop() } } } },
                onFile = { filePicker.launch(arrayOf("audio/*")) },
                onDrop = { clip = null; clipSec = 0; record = "" },
            )

            // ── 名字与描述 ────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text("叫什么", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(5.dp))
            SField(name, { name = it.take(12) }, "老周")

            Spacer(Modifier.height(11.dp))
            Text("一句话说清这副嗓子", fontSize = 11.sp, color = skin.inkFaint)
            Spacer(Modifier.height(5.dp))
            SField(note, { note = it.take(40) }, "四十多岁，低，说话慢")

            // 硅基那条上传时要一句参考文本对齐音色，别的家不问。
            if (shape?.needsText == true) {
                Spacer(Modifier.height(11.dp))
                Text("录音里说的是什么", fontSize = 11.sp, color = skin.inkFaint)
                Spacer(Modifier.height(5.dp))
                SField(
                    record, { record = it.take(400) },
                    "把录音里那句话原样写下来，一个字别差", 70.dp,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    shape?.localOnly == true ->
                        "只收 mp3 和 wav，10 MB 以内。这副嗓每念一句都要把它整段带上，长篇会慢。"
                    else ->
                        "10 秒到 5 分钟，20 MB 以内。做好了七天内得用一次，不然对面会删。"
                },
                fontSize = 10.sp, color = skin.inkFaint, lineHeight = 15.sp
            )

            hint?.let {
                Spacer(Modifier.height(9.dp))
                Text(it, fontSize = 11.sp, color = skin.bad, lineHeight = 16.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Btn("算了", onClose, BtnKind.GHOST, modifier = Modifier.weight(1f))
                Btn(
                    if (busy) "在做…" else "做成这副",
                    {
                        if (busy) return@Btn
                        val b = clip
                        if (b == null) { hint = "还差一段声音"; return@Btn }
                        if (name.isBlank()) { hint = "给它起个名字"; return@Btn }
                        if (shape?.needsText == true && record.isBlank()) {
                            hint = "还差一句：录音里说的是什么"
                            return@Btn
                        }
                        busy = true
                        hint = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    CloneApi.make(ctx, engine!!, name, b, fromFile, record)
                                }
                            }
                            r.onFailure {
                                busy = false
                                hint = it.message ?: "没做成"
                            }.onSuccess { made ->
                                val v = Voice(
                                    id = "u_" + System.currentTimeMillis().toString(36),
                                    name = freeName(app, name),
                                    kind = made.kind,
                                    engine = engine!!.id,
                                    param = made.param,
                                    desc = note.trim(),
                                )
                                /*
                                 * 做成之后立刻念一次。
                                 *
                                 * 不是为了试听 —— 是为了**激活**：MiniMax 那副
                                 * 七天内不用会被删，这次合成就是那条「用了」。
                                 * 顺带也验证它真能出声。失败不拦着保存，
                                 * 嗓子已经在对面的账号里了。
                                 */
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        app.synth.make(
                                            app.field.now, v, "这一段，念给你听。", null,
                                            force = true,
                                        )
                                    }
                                }
                                busy = false
                                onDone(v, made.note)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "只拿你本人、或已经获准使用的声音。",
                fontSize = 10.sp, color = skin.inkFaint
            )
        }
    }
}

/** 录音那一块：一个圆盘，别处不重复。 */
@Composable
private fun Saying(
    sec: Int,
    recording: Boolean,
    has: Boolean,
    ready: Int,
    onRec: () -> Unit,
    onStop: () -> Unit,
    onFile: () -> Unit,
    onDrop: () -> Unit,
) {
    val s = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        val lit = recording || has
        Box(
            Modifier.size(66.dp).clip(CircleShape)
                .background(
                    when {
                        recording -> s.flame
                        has -> s.flameGhost
                        else -> s.ground
                    }
                )
                .border(
                    if (lit) 1.2.dp else 1.dp,
                    if (lit) s.flame.copy(alpha = 0.55f) else s.hairSoft,
                    CircleShape
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    when {
                        recording -> onStop()
                        has -> onDrop()
                        else -> onRec()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    recording -> "停"
                    has -> "重录"
                    else -> "说一句"
                },
                fontSize = if (recording) 15.sp else 12.sp,
                color = if (recording) s.onFlame else s.flame,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    recording -> "${sec} 秒，说慢一点"
                    has -> if (ready > 0) "手上这段 ${ready} 秒" else "手上这段够用了"
                    else -> "从这儿开始，念一两句话"
                },
                fontSize = 12.sp,
                color = if (recording) s.flame else s.ink, lineHeight = 17.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (recording) "别停太久，一口气说完" else "也可以用现成的文件",
                fontSize = 10.sp, color = s.inkFaint, lineHeight = 15.sp
            )
            if (!recording) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "挑一个音频文件",
                    fontSize = 11.sp, color = s.inkDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onFile() }
                        .padding(vertical = 4.dp, horizontal = 1.dp)
                )
            }
        }
    }
}

/** 居中的一张纸，不是从底下推上来的长条。 */
@Composable
private fun Modal(onClose: () -> Unit, content: @Composable () -> Unit) {
    val s = LocalSkin.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .background(s.scrim, RoundedCornerShape(24.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(s.surface)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
            ) { content() }
        }
    }
}

@Composable
private fun SField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    minHeight: androidx.compose.ui.unit.Dp = 42.dp,
) {
    val s = LocalSkin.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(s.ground)
            .border(1.dp, s.hairSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(hint, fontSize = 13.sp, color = s.inkFaint, lineHeight = 18.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = s.ink, fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun mb(b: ByteArray): String = ((b.size + 512 * 1024) / (1024 * 1024)).toString()
private fun okSize(b: ByteArray): Boolean = b.size <= 10 * 1024 * 1024

/** WAV 的头能算出时长；别的格式没法算，返回 0，界面就不写秒数。 */
private fun wavSec(b: ByteArray): Int {
    if (b.size < 44) return 0
    if (String(b, 0, 4) != "RIFF") return 0
    var rate = 0
    var i = 12
    while (i + 8 <= b.size) {
        val id = String(b, i, 4)
        val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
            ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
        if (id == "fmt ") rate = (b[i + 12].toInt() and 0xFF) or ((b[i + 13].toInt() and 0xFF) shl 8) or
            ((b[i + 14].toInt() and 0xFF) shl 16) or ((b[i + 15].toInt() and 0xFF) shl 24)
        if (id == "data" && rate > 0) return (len / (rate * 2)).coerceAtLeast(0)
        i += 8 + len + (len and 1)
    }
    return 0
}
