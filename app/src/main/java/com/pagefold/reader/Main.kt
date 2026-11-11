package com.pagefold.reader

import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.pagefold.reader.book.BookMeta
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.Dock
import com.pagefold.reader.voice.AiJob
import com.pagefold.reader.kit.BgLayer
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.ThemeId
import com.pagefold.reader.ui.AboutScreen
import com.pagefold.reader.ui.LibraryScreen
import com.pagefold.reader.ui.PreviewScreen
import com.pagefold.reader.ui.ReadScreen
import com.pagefold.reader.ui.SettingsScreen
import com.pagefold.reader.ui.VoiceScreen

class Main : ComponentActivity() {

    /**
     * 系统「文字转语音」里点 Quolune 右边那个齿轮，走的就是这个 Activity
     * （tts_engine.xml 的 settingsActivity 指向这里）。
     * 那种启动没有 action，只有显式组件 —— 凭这一点认出它，直接落到音色页。
     */
    private fun cameFromTts(): Boolean {
        val i = intent ?: return false
        if (i.action == "android.speech.tts.engine.CONFIGURE_ENGINE") return true
        return i.action == null && i.component?.className?.startsWith(packageName) == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 导航条留在原地，但不要那条白底。
        //
        // 不把整条收起来：收起来更难看，翻页时系统会自己把它放回来，
        // 露出来是一块刷白的空条，跟纸色撞得厉害。所以不收了，
        // 改成「透过去」：条子透明，下面就是纸；图标跟着主题翻黑白。
        runCatching {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // 关掉系统给的那层半透明蒙版，否则浅色主题下还是灰的一条。
                window.isNavigationBarContrastEnforced = false
            }
        }
        val app = application as App
        val jumpVoice = cameFromTts()
        setContent {
            val s by app.field.state.collectAsState()
            val skin = ThemeId.of(s.theme).skin(s.dark)
            // 导航条三颗键的黑白，跟着纸色走。浅纸上是深色键，深纸上是浅色键。
            androidx.compose.runtime.SideEffect {
                runCatching {
                    val c = WindowCompat.getInsetsController(window, window.decorView)
                    c.isAppearanceLightNavigationBars = !s.dark
                    c.isAppearanceLightStatusBars = !s.dark
                }
            }
            CompositionLocalProvider(LocalSkin provides skin) {
                Root(s, app, jumpVoice)
            }
        }
    }

    /** 退到后台就把攒着的改动落盘，别等进程被杀。 */
    override fun onStop() {
        runCatching { (application as App).field.flush() }
        super.onStop()
    }
}

/** 撑满剩余高度：Column 里给 Box 用。 */
private fun Modifier.fillRest(): Modifier = this.layout { measurable, constraints ->
    val h = if (constraints.maxHeight == Int.MAX_VALUE) 0 else constraints.maxHeight
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = if (h == 0) Int.MAX_VALUE else h))
    layout(p.width, if (h == 0) p.height else h) { p.place(0, 0) }
}

@Composable
private fun Root(s: Settings, app: App, jumpVoice: Boolean = false) {
    var tab by remember { mutableIntStateOf(if (jumpVoice) 1 else s.homeTab.coerceIn(0, 4)) }
    var book by remember { mutableStateOf<BookMeta?>(null) }

    // 每页一个滚动位置。rememberLazyListState 内部是 rememberSaveable，
    // 所以切页回来、甚至转屏之后，位置都还在。
    val shelf = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scroll = listOf(
        androidx.compose.foundation.lazy.rememberLazyListState(),
        androidx.compose.foundation.lazy.rememberLazyListState(),
        androidx.compose.foundation.lazy.rememberLazyListState(),
        androidx.compose.foundation.lazy.rememberLazyListState(),
    )

    Box(Modifier.fillMaxSize()) {
        val opened = book
        BgLayer(
            page = if (opened != null) "reader" else when (tab) {
                0 -> "shelf"
                1 -> "voice"
                2 -> "preview"
                4 -> "about"
                else -> "tune"
            },
            settings = s
        )
        if (opened != null) {
            ReadScreen(book = opened, settings = s, app = app, onBack = { book = null })
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f).statusBarsPadding()) {
                    // 四个页各自记住自己滑到哪儿。放这一层，切页时组合被销毁也不会丢。
                    when (tab) {
                        0 -> LibraryScreen(s, app, grid = shelf, open = { book = it })
                        1 -> VoiceScreen(s, app, list = scroll[0])
                        2 -> PreviewScreen(s, app, list = scroll[1])
                        4 -> AboutScreen(s, app, list = scroll[3])
                        else -> SettingsScreen(s, app, list = scroll[2])
                    }
                }
                Dock(tab, onPick = { tab = it })
            }
        }

        /*
         * 顶上那一条：「AI 已排好」。
         *
         * 排这件事在 AiJob 里跑，人可能在别的页翻书、在音色页调调门。
         * 排完了不把人拽回去，只在这儿放一条 —— 他自己决定什么时候看。
         * 点它就是回那本书、把派角纸摊开、落在结果那一屏。
         */
        if (AiJob.fresh && !AiJob.open) {
            val sk = LocalSkin.current
            Row(
                Modifier.align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(sk.panelLift)
                    .border(1.dp, sk.flame.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable {
                        AiJob.wantCast = true
                        val bm = app.shelf.find(AiJob.bookId)
                        if (bm != null) book = bm else tab = 3
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AiJob.brief.ifBlank { "AI 已经排好" },
                    fontSize = 12.sp,
                    color = sk.ink,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Text("看看", fontSize = 12.sp, color = sk.flame, fontWeight = FontWeight.Medium)
            }
        }

        // 头一次进来落「定音」。
        //
        // 嗓源、钥匙、念法都在这张案上。不先把它们拨对，进了书按播放也是静音，
        // 那种「装了没声音」比「先看一页设置」难受得多。定完他自己会去书房。
        LaunchedEffect(Unit) {
            if (!s.choseHome) app.field.edit { it.copy(homeTab = 3, choseHome = true) }
        }
    }
}


