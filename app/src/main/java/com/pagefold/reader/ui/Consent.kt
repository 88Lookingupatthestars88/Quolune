package com.pagefold.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Ornament
import com.pagefold.reader.kit.Rule
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.kit.tap
import kotlinx.coroutines.launch

/** 已同意的条款版本。条款改到要重新征求同意时，换成一个大于此前所有值的数。 */
const val CONSENT_REV = 1

/**
 * 首次进入，或条款改版后第一次进来，先看这一屏。
 * 滚到末尾才允许同意；不同意的按钮一直都在。
 */
@Composable
internal fun ConsentScreen(app: App) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val leave: () -> Unit = { (ctx as? android.app.Activity)?.finishAffinity() }
    BackHandler { leave() }

    /* 末项露头就算到底。这一屏很长，首帧看不到末项，所以判一次就够。 */
    val atEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount > 0 && last != null && last.index >= info.totalItemsCount - 1
        }
    }
    /* 到底就解锁，之后往回滑也不锁回去。 */
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(atEnd) { if (atEnd) open = true }

    Box(Modifier.fillMaxSize().background(skin.ground)) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 128.dp,
            ),
        ) {
            itemsIndexed(DOC.rows) { _, line ->
                when (line) {
                    Line.Mast -> Column(
                        Modifier.fillMaxWidth().padding(horizontal = Sz.gutter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(30.dp))
                        Text(
                            "Quolune",
                            fontSize = 30.sp,
                            color = skin.ink,
                            fontFamily = skin.titleFont,
                            letterSpacing = 1.5.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "使用条款与隐私政策",
                            fontSize = 16.sp,
                            color = skin.ink,
                            fontFamily = skin.titleFont,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Ornament(Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "使用前，请阅读下列全部条款。",
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            color = skin.inkDim,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is Line.Note -> Text(
                        line.text,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = Sz.gutter).padding(top = 12.dp),
                        fontSize = 13.sp,
                        lineHeight = 26.sp,
                        color = skin.inkDim,
                    )

                    Line.Index -> Column(Modifier.fillMaxWidth().padding(horizontal = Sz.gutter)) {
                        Spacer(Modifier.height(22.dp))
                        Text(
                            "目录",
                            fontSize = 15.sp,
                            color = skin.ink,
                            fontFamily = skin.titleFont,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(skin.panelLift)
                                .border(1.dp, skin.hairSoft, RoundedCornerShape(12.dp))
                                .padding(vertical = 6.dp)
                        ) {
                            TOC.forEach { (title, target) ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .tap {
                                            scope.launch {
                                                /* 先滚到顶，再按实测项高补一次偏移，让这一条落在正中。 */
                                                list.animateScrollToItem(target)
                                                val info = list.layoutInfo
                                                val it2 = info.visibleItemsInfo
                                                    .firstOrNull { i -> i.index == target }
                                                if (it2 != null) {
                                                    val want = (info.viewportSize.height - it2.size) / 2
                                                    list.animateScrollBy((it2.offset - want).toFloat())
                                                }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        title,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        color = skin.inkDim,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("↓", fontSize = 11.sp, color = skin.inkFaint)
                                }
                            }
                        }
                    }

                    is Line.Head -> Column(Modifier.fillMaxWidth().padding(horizontal = Sz.gutter)) {
                        Spacer(Modifier.height(26.dp))
                        Rule()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            line.text,
                            fontSize = 17.sp,
                            color = skin.ink,
                            fontFamily = skin.titleFont,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 2.sp,
                        )
                    }

                    is Line.Body -> Column(
                        Modifier.fillMaxWidth().padding(horizontal = Sz.gutter)
                            .padding(top = 14.dp)
                    ) {
                        Text(
                            line.title,
                            fontSize = 13.sp,
                            color = skin.ink,
                            fontWeight = FontWeight.Medium,
                            fontFamily = skin.titleFont,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            line.text,
                            fontSize = 13.sp,
                            lineHeight = 26.sp,
                            color = skin.inkDim,
                        )
                    }
                }
            }
        }

        /* 底部两条路：不同意一直都在；同意滚到底才亮。 */
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(skin.ground)
                .navigationBarsPadding()
        ) {
            Rule(Modifier.padding(horizontal = Sz.gutter))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Sz.gutter)
                    .padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Btn(
                    text = "不同意，退出",
                    onClick = leave,
                    kind = BtnKind.GHOST,
                    modifier = Modifier.weight(1f),
                )
                Btn(
                    text = "同意并继续",
                    onClick = {
                        app.field.edit { it.copy(consentRev = CONSENT_REV) }
                        app.field.flush()
                    },
                    enabled = open,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private sealed interface Line {
    object Mast : Line
    class Note(val text: String) : Line
    object Index : Line
    class Head(val text: String) : Line
    class Body(val title: String, val text: String) : Line
}

private class Doc(val rows: List<Line>, val jump: LinkedHashMap<String, Int>)

/** 正文只拼一次。目录要的是行号，所以拼的时候顺手记下来。 */
private val DOC: Doc = run {
    val rows = ArrayList<Line>()
    val jump = LinkedHashMap<String, Int>()

    rows += Line.Mast
    rows += Line.Note(
        "本应用是一款运行于您本机的阅读与朗读工具。它没有服务器，不收集您的任何信息，" +
            "也不附带任何书籍。您导入的内容由您自行取得，并对其合法性与使用方式负责。"
    )
    rows += Line.Index

    rows += Line.Head("免责声明")
    rows += Line.Note(Legal.IMPORTANT)
    Legal.CLAUSES.forEach { (t, b) ->
        jump[t] = rows.size
        rows += Line.Body(t, b)
    }
    rows += Line.Note("使用本应用即代表您已阅读、理解并同意本声明的全部条款。")

    rows += Line.Head("隐私政策")
    rows += Line.Note("本应用没有服务器，不收集、不上传、不存储您的任何个人信息。")
    Legal.PRIVACY.forEach { (t, b) ->
        jump[t] = rows.size
        rows += Line.Body(t, b)
    }

    Doc(rows, jump)
}

/** 目录里一行：标题 + 跳到第几行。放在 DOC 之后 —— 顶层属性按文件顺序初始化。 */
private val TOC: List<Pair<String, Int>> = DOC.jump.map { (k, v) -> k to v }
