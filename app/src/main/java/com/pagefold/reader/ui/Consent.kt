package com.pagefold.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Rule
import com.pagefold.reader.kit.Sz
import com.pagefold.reader.kit.tap

/**
 * 已同意的条款版本。条款改到要重新征求同意时，换成一个大于此前所有值的数。
 * 5.0 之前存过 3，往小改会让那批机器一次都不弹。
 */
const val CONSENT_REV = 11

/**
 * 首次启动，或条款改版后第一次进来，先过这一屏。
 * 卡片在前；点书名号进全文，全文里还能再跳，返回一层一层退。
 */
@Composable
internal fun ConsentScreen(app: App) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<Legal.Doc>() }

    val leave: () -> Unit = { (ctx as? android.app.Activity)?.finishAffinity() }
    val done: () -> Unit = {
        app.field.edit { it.copy(consentRev = CONSENT_REV) }
        app.field.flush()
        app.boot()
    }

    BackHandler { if (stack.isEmpty()) leave() else stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize().background(skin.ground)) {
        if (stack.isEmpty()) {
            Card(leave = leave, done = done, onOpen = { stack.add(it) })
        } else {
            LegalSheet(
                doc = stack.last(),
                onBack = { stack.removeAt(stack.lastIndex) },
                onOpen = { stack.add(it) },
            )
        }
    }
}

/* ── 卡片 ───────────────────────────────────────────── */

@Composable
private fun Card(leave: () -> Unit, done: () -> Unit, onOpen: (Legal.Doc) -> Unit) {
    val skin = LocalSkin.current
    val terms = remember { mutableStateOf(false) }
    val privacy = remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    val ready = terms.value && privacy.value
    val lacking = buildList {
        if (!terms.value) add("《用户协议》")
        if (!privacy.value) add("《隐私政策》")
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 26.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(skin.panel)
                .border(1.dp, skin.hairSoft, RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 26.dp),
        ) {
            Text(
                "Quolune",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 30.sp,
                color = skin.ink,
                fontFamily = skin.titleFont,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "欢迎使用本应用。在使用本应用前，请仔细阅读下面的两份条款。",
                modifier = Modifier.fillMaxWidth(),
                fontSize = Sz.body.sp,
                lineHeight = 24.sp,
                color = skin.inkDim,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))
            Rule()
            Spacer(Modifier.height(6.dp))

            Check(terms.value, tried && !terms.value, Legal.TERMS, onOpen) { terms.value = !terms.value }
            Check(privacy.value, tried && !privacy.value, Legal.PRIVACY, onOpen) { privacy.value = !privacy.value }

            Spacer(Modifier.height(8.dp))

            /* 缺勾选时才出这一行，平时不占地方。末尾那个「全部勾上」点一下补齐。 */
            if (tried && !ready) {
                Text(
                    buildAnnotatedString {
                        append("请先勾选" + lacking.joinToString("与") + "\u3000")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "all",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = skin.flame,
                                        textDecoration = TextDecoration.Underline,
                                    )
                                ),
                            ) {
                                terms.value = true
                                privacy.value = true
                                tried = false
                            }
                        ) { append("全部勾上") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    fontSize = Sz.micro.sp,
                    color = skin.bad,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("下一步", {
                    if (ready) done() else tried = true
                }, modifier = Modifier.weight(1f))
                Btn("退出", leave, BtnKind.GHOST, Modifier.weight(1f))
            }
        }
    }
}

/** 一行勾选。方框管勾，书名号管跳，两件事分开。 */
@Composable
private fun Check(
    on: Boolean,
    wrong: Boolean,
    doc: Legal.Doc,
    onOpen: (Legal.Doc) -> Unit,
    onToggle: () -> Unit,
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).tap { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(19.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) skin.flame else Color.Transparent)
                    .border(1.5.dp, if (on) skin.flame else skin.hair, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Text("✓", fontSize = 13.sp, color = skin.ground, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            buildAnnotatedString {
                append("我已阅读")
                withLink(linkFor(doc, onOpen, skin.flame)) {
                    withStyle(SpanStyle(color = if (wrong) skin.bad else skin.ink)) {
                        append("《")
                        append(doc.name)
                        append("》")
                    }
                }
            },
            modifier = Modifier.weight(1f).tap { onToggle() },
            fontSize = Sz.body.sp,
            lineHeight = 24.sp,
            color = skin.inkDim,
        )
    }
}

/* ── 全文 ───────────────────────────────────────────── */

/** 一份条款的全文。卡片与关于页共用这一个。 */
@Composable
internal fun LegalSheet(doc: Legal.Doc, onBack: () -> Unit, onOpen: (Legal.Doc) -> Unit) {
    val skin = LocalSkin.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = 34.dp,
        ),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Sz.gutter)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "←  返回",
                    modifier = Modifier.tap { onBack() }.padding(vertical = 12.dp),
                    fontSize = Sz.label.sp,
                    color = skin.inkDim,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    doc.title,
                    fontSize = Sz.h2.sp,
                    color = skin.ink,
                    fontFamily = skin.titleFont,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "版本 " + doc.version + "\u3000生效 " + doc.effective + "\u3000更新 " + doc.updated,
                    fontSize = Sz.micro.sp, color = skin.inkFaint, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(18.dp))
                Rule()
            }
        }
        items(doc.sections) { (head, body) ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Sz.gutter).padding(top = 22.dp),
            ) {
                /* 前言那一段没有节标题，只铺正文。 */
                if (head.isNotEmpty()) {
                    Text(
                        head,
                        fontSize = 16.sp,
                        color = skin.ink,
                        fontFamily = skin.titleFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Paragraphs(body, doc, onOpen)
            }
        }
    }
}

/** 正文按空行分段，一段一个文本。行内遇到书名号就做成能点的。 */
@Composable
private fun Paragraphs(body: String, self: Legal.Doc, onOpen: (Legal.Doc) -> Unit) {
    val skin = LocalSkin.current
    val paras = remember(body) {
        body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column(Modifier.fillMaxWidth()) {
        paras.forEach { para ->
            Text(
                linkify(para, self, onOpen, skin.flame),
                fontSize = 15.sp,
                lineHeight = 27.sp,
                color = skin.inkDim,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 一份文档里提到自己不做链接，点了没反应。 */
private fun linkFor(doc: Legal.Doc, onOpen: (Legal.Doc) -> Unit, tint: Color): LinkAnnotation.Clickable {
    val styles = TextLinkStyles(style = SpanStyle(color = tint, textDecoration = TextDecoration.Underline))
    return LinkAnnotation.Clickable(tag = doc.name, styles = styles) { onOpen(doc) }
}

private val MENTION = Regex("《(用户协议|隐私政策|声音样本处理说明)》")

/** 把一段正文里的书名号换成链接。提到自己那份时留成普通字，点了没反应。 */
private fun linkify(
    text: String,
    self: Legal.Doc,
    onOpen: (Legal.Doc) -> Unit,
    tint: Color,
): AnnotatedString {
    if (!text.contains('《')) return AnnotatedString(text)
    val styles = TextLinkStyles(style = SpanStyle(color = tint, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        MENTION.findAll(text).forEach { m ->
            if (m.range.first > at) append(text.substring(at, m.range.first))
            val target = Legal.ALL.firstOrNull { it.name == m.groupValues[1] }?.takeIf { it.name != self.name }
            if (target != null) {
                withLink(LinkAnnotation.Clickable(tag = target.name, styles = styles) { onOpen(target) }) {
                    append(m.value)
                }
            } else {
                append(m.value)
            }
            at = m.range.last + 1
        }
        if (at < text.length) append(text.substring(at))
    }
}
