package com.pagefold.reader

import android.content.Intent
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toArgb
import com.pagefold.reader.book.BookMeta
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.Dock
import com.pagefold.reader.voice.AiJob
import com.pagefold.reader.kit.BgLayer
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.ThemeId
import com.pagefold.reader.ui.AboutScreen
import com.pagefold.reader.ui.Legal
import com.pagefold.reader.ui.CONSENT_REV
import com.pagefold.reader.ui.ConsentScreen
import com.pagefold.reader.ui.LibraryScreen
import com.pagefold.reader.ui.PreviewScreen
import com.pagefold.reader.ui.ReadScreen
import com.pagefold.reader.ui.SettingsScreen
import com.pagefold.reader.ui.Splash
import com.pagefold.reader.ui.VoiceScreen

/** 从外面递进来的落点：换到哪一页，或者直接开某一本。 */
private data class Ask(val tab: Int?, val book: String?, val seq: Int)

class Main : ComponentActivity() {

    companion object {
        /** 长按图标那几条带过来的页号。 */
        const val EXTRA_TAB = "tab"

        /** 通知、快捷方式带过来的书 id。 */
        const val EXTRA_BOOK = "resume"

        /** 系统「文字转语音」里点 Quolune 右边那个齿轮。 */
        private const val TTS_CONFIGURE = "android.speech.tts.engine.CONFIGURE_ENGINE"

        /** 桌面递快捷方式时带的那一格。框架里这个常量是私有的，只能用字面值。 */
        private const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    }

    private var ask by mutableStateOf<Ask?>(null)
    private var seq = 0

    /** 快捷方式 id 对应的页。com.android.launcher 递过来的意图里带这个 id。 */
    private fun tabOfShortcut(id: String?): Int? = when (id) {
        "shelf" -> 0
        "voice" -> 1
        "tune" -> 3
        else -> null
    }

    /** 读一次进来的意图。没有落点就什么也不做，留在原来那一屏。 */
    private fun take(raw: Intent?) {
        if (raw == null) return
        /* 有的桌面把快捷方式的意图套一层递过来。 */
        val i = raw.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: raw
        if (i.action == TTS_CONFIGURE) {
            ask = Ask(1, null, ++seq)
            return
        }
        /* xml 里 android:value 会被编成整型，直接 getStringExtra 取不到。 */
        val sid = runCatching {
            i.getStringExtra(EXTRA_SHORTCUT_ID)
        }.getOrNull()
        val tab = when (val t = i.extras?.get(EXTRA_TAB)) {
            is Number -> t.toInt()
            is String -> t.toIntOrNull()
            else -> tabOfShortcut(sid)
        }
        val book = when (val b = i.extras?.get(EXTRA_BOOK)) {
            is String -> b.ifBlank { null }
            else -> null
        }
        if (tab == null && book == null) return
        ask = Ask(tab, book, ++seq)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 导航条透明：整条不收，翻页时它自己回来时不会刷出一条白底。
        runCatching {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // 关掉系统那层半透明蒙版；留着会在浅色主题下多一条灰边。
                window.isNavigationBarContrastEnforced = false
            }
        }
        val app = application as App
        take(intent)
        /* 窗口底先铺成当前皮肤的地色：进程起来到第一帧之间不闪白。 */
        val skin0 = ThemeId.of(app.field.now.theme).skin(app.field.now.dark)
        runCatching {
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(skin0.ground.toArgb())
            )
        }
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
                var intro by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    TapAway { Root(s, app, ask) { ask = null } }
                    if (intro) Splash(skin) { intro = false }
                }
            }
        }
    }

    /* 已经开着的时候从通知或快捷方式进来，走这里。 */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        take(i)
    }

    /** 退到后台就把攒着的改动落盘，别等进程被杀。 */
    override fun onStop() {
        val a = application as App
        runCatching { a.field.flush() }
        runCatching { a.shelf.flush() }
        super.onStop()
    }
}

/**
 * 点到没被别处吃掉的地方，就收掉焦点、放下键盘。
 *
 * 走 Final 那一趟：输入框、按钮先自己处理完，剩下的空白才轮到这儿。
 */
@Composable
private fun TapAway(content: @Composable () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keys = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                    if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Press) continue
                    if (e.changes.any { it.isConsumed }) continue
                    focus.clearFocus()
                    keys?.hide()
                }
            }
        }
    ) { content() }
}

/** 撑满剩余高度：Column 里给 Box 用。 */
private fun Modifier.fillRest(): Modifier = this.layout { measurable, constraints ->
    val h = if (constraints.maxHeight == Int.MAX_VALUE) 0 else constraints.maxHeight
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = if (h == 0) Int.MAX_VALUE else h))
    layout(p.width, if (h == 0) p.height else h) { p.place(0, 0) }
}

@Composable
private fun Root(s: Settings, app: App, ask: Ask?, onAsk: () -> Unit) {
    if (s.consentRev < CONSENT_REV) {
        ConsentScreen(app)
        return
    }
    /* 同意过就直接进；升级上来的老用户还没建过书库，这里补一次。 */
    if (!app.ready) app.boot()
    Home(s, app, ask, onAsk)
}

@Composable
private fun Home(s: Settings, app: App, ask: Ask?, onAsk: () -> Unit) {
    var tab by remember { mutableIntStateOf(ask?.tab ?: s.homeTab.coerceIn(0, 4)) }
    /* 条款全文。压在整屏最上层，页签条也盖住。 */
    var legal by remember { mutableStateOf<Legal.Doc?>(null) }
    val first = remember {
        ask?.book?.let { id -> runCatching { app.shelf.all().firstOrNull { it.id == id } }.getOrNull() }
    }
    var book by remember { mutableStateOf<BookMeta?>(first) }
    var autoPlay by remember { mutableStateOf(first != null) }

    /* 界面已经开着时又进来一次：切页或开书，然后把落点清掉。 */
    LaunchedEffect(ask?.seq) {
        val a = ask ?: return@LaunchedEffect
        val target = a.book?.let { id ->
            runCatching { app.shelf.all().firstOrNull { it.id == id } }.getOrNull()
        }
        if (target != null) {
            book = target
            autoPlay = true
        } else if (a.tab != null) {
            /* 只要换页：正在看的那本书收起来，回到底下的页。 */
            book = null
            autoPlay = false
            tab = a.tab.coerceIn(0, 4)
        }
        onAsk()
    }

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
            ReadScreen(
                book = opened, settings = s, app = app, autoPlay = autoPlay,
                onBack = { book = null; autoPlay = false },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f).statusBarsPadding()) {
                    // 四个页各自记住自己滑到哪儿。放这一层，切页时组合被销毁也不会丢。
                    when (tab) {
                        0 -> LibraryScreen(
                            s, app, grid = shelf,
                            open = { b, play -> book = b; autoPlay = play },
                        )
                        1 -> VoiceScreen(s, app, list = scroll[0])
                        2 -> PreviewScreen(s, app, list = scroll[1])
                        4 -> AboutScreen(s, app, list = scroll[3], onShow = { legal = it })
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

        /* 条款全文。画在这一层才盖得住页签条，也不另开窗口，点开不闪。 */
        androidx.activity.compose.BackHandler(enabled = legal != null) { legal = null }
        legal?.let { d ->
            val sk = LocalSkin.current
            Box(Modifier.fillMaxSize().background(sk.ground)) {
                com.pagefold.reader.ui.LegalSheet(
                    doc = d,
                    onBack = { legal = null },
                    onOpen = { legal = it },
                )
            }
        }
    }
}


