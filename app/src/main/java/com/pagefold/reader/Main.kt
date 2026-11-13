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

    /** 系统「文字转语音」里点 Quolune 右边那个齿轮。 */
    private fun cameFromTts(): Boolean {
        val i = intent ?: return false
        if (i.action == "android.speech.tts.engine.CONFIGURE_ENGINE") return true
        return i.action == null && i.component?.className?.startsWith(packageName) == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 导航条透明：整条不收，翻页时它自己回来时不会刷出一条白底。
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
        val a = application as App
        runCatching { a.field.flush() }
        runCatching { a.shelf.flush() }
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

        /* 顶上那一条：「AI 已排好」。 */
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

        // 头一次进来落「定音」：嗓源和钥匙没拨对，进书按播放是静音。
        LaunchedEffect(Unit) {
            if (!s.choseHome) app.field.edit { it.copy(homeTab = 3, choseHome = true) }
        }
    }
}


