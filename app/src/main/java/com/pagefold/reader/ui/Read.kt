package com.pagefold.reader.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.pagefold.reader.kit.Glyph
import com.pagefold.reader.App
import com.pagefold.reader.book.BookMeta
import com.pagefold.reader.book.Cast
import com.pagefold.reader.book.Ban
import com.pagefold.reader.book.CastProfile
import com.pagefold.reader.book.Chapter
import com.pagefold.reader.book.Chapters
import com.pagefold.reader.book.Line
import com.pagefold.reader.book.Parse
import com.pagefold.reader.book.RuleCodec
import com.pagefold.reader.voice.AutoCast
import com.pagefold.reader.field.Settings
import com.pagefold.reader.field.TurnMode
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.IconBtn
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.PickChip
import com.pagefold.reader.kit.Paper
import com.pagefold.reader.kit.T
import com.pagefold.reader.play.BookPlayer
import com.pagefold.reader.play.Feed
import com.pagefold.reader.play.Sleep
import com.pagefold.reader.voice.Catalog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── 书的骨架：章 → 段 → 块 ─────────────────────────

private class Atlas(
    val text: String,
    val chStart: IntArray,
    val chEnd: IntArray,
    val chTitle: Array<String>,
    val pFrom: IntArray,
    val pTo: IntArray,
    val pCh: IntArray,
    val bFrom: IntArray,
    val bTo: IntArray,
    val bParaFrom: IntArray,
    val bParaTo: IntArray,
) {
    val bCount: Int get() = bFrom.size

    fun chOfAbs(abs: Int): Int {
        if (chStart.isEmpty()) return 0
        var lo = 0
        var hi = chStart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chStart[mid] <= abs) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun pStartOf(ch: Int): Int {
        if (pCh.isEmpty()) return 0
        var lo = 0
        var hi = pCh.size
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (pCh[m] < ch) lo = m + 1 else hi = m
        }
        return lo
    }

    fun pEndOf(ch: Int): Int {
        if (pCh.isEmpty()) return 0
        var lo = 0
        var hi = pCh.size
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (pCh[m] <= ch) lo = m + 1 else hi = m
        }
        return lo
    }

    fun blockOf(abs: Int): Int {
        if (bCount == 0) return 0
        var lo = 0
        var hi = bCount - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (bTo[mid] > abs) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        /*
         * 一块多大。
         *
         * **必须比一屏矮。** 选字时 SelectionContainer 会请求「让这一块可见」，
         * LazyColumn 对超过一屏的块只能做到「块的边对齐视口」——露出一角就算数，
         * 于是拖着手柄往下选，滚一下就停住，再也下不去。
         * 块缩到半屏左右之后，这个请求就能真正把下一块拉进视野，选字可以一路拖到底。
         *
         * 顺带两个好处：跟读定位更细（块内行偏移算得更准）、单块重排更便宜。
         */
        /*
         * 一块多大。
         *
         * 选字只能在本块内，所以块不能太小 —— 太小就是「只能选三个字」。
         * 一屏大约六百到八百字，取 700 字 / 5 段，一屏多一点，
         * 既能选到完整的一两段话，又不会让单块排版太重。
         */
        private const val BLOCK_CHARS = 700
        private const val BLOCK_PARAS = 5

        fun build(text: String, chapters: List<Chapter>): Atlas {
            val n = chapters.size
            val cs = IntArray(n)
            val ce = IntArray(n)
            val ct = Array(n) { "" }
            val pf = ArrayList<Int>(8192)
            val pt = ArrayList<Int>(8192)
            val pc = ArrayList<Int>(8192)
            val bf = ArrayList<Int>(1024)
            val bt = ArrayList<Int>(1024)
            val bp = ArrayList<Int>(2048)

            chapters.forEachIndexed { ci, c ->
                val a0 = c.start.coerceIn(0, text.length)
                val b0 = c.end.coerceIn(0, text.length)
                cs[ci] = a0
                ce[ci] = b0
                ct[ci] = c.title

                var head = -1
                var tail = -1
                var chars = 0
                var paras = 0
                var segFrom = -1

                fun shut() {
                    if (head >= 0 && tail > head) {
                        bf.add(head); bt.add(tail)
                        bp.add(segFrom); bp.add(pf.size)
                    }
                    head = -1; tail = -1; chars = 0; paras = 0; segFrom = -1
                }

                var p = a0
                while (p < b0) {
                    var nl = text.indexOf('\n', p)
                    if (nl < 0 || nl > b0) nl = b0
                    var a = p
                    var b = nl
                    while (a < b && text[a].isWhitespace()) a++
                    while (b > a && text[b - 1].isWhitespace()) b--
                    if (b > a) {
                        if (head < 0) { head = a; segFrom = pf.size }
                        tail = b
                        chars += b - a
                        paras++
                        pf.add(a); pt.add(b); pc.add(ci)
                        if (chars >= BLOCK_CHARS || paras >= BLOCK_PARAS) shut()
                    }
                    if (nl >= b0) break
                    p = nl + 1
                }
                shut()
            }

            return Atlas(
                text, cs, ce, ct,
                pf.toIntArray(), pt.toIntArray(), pc.toIntArray(),
                bf.toIntArray(), bt.toIntArray(),
                bp.filterIndexed { i, _ -> i % 2 == 0 }.toIntArray(),
                bp.filterIndexed { i, _ -> i % 2 == 1 }.toIntArray(),
            )
        }
    }
}

private class Slot<T>(var v: T? = null)

/**
 * 一章排好的字。
 *
 * **它是「长出来的」，不是一次排完的。**
 *
 * 上一版 buildLaid 一上来就把整章的段全拼成一串、再交给排版器 ——
 * 一章五十万字的时候，那一串是五十万个字符、几万个段落，量一次要好几百毫秒，
 * 页表还要再切上百页。点开就卡死，用户那边「一章五十万字直接卡死」就是它。
 *
 * 现在按**段**往里长：要多少字长多少字（[grow]），翻到哪儿长到哪儿。
 * 每页只从这串字里切自己那一段（[slice]），排版器每次只量一页。
 *
 * 坐标还是老规矩：外面用「原文偏移」，里面用「排好的位置」，两头靠
 * [rendered] / [abs] 换。签子插在正文中间，那几个字符要减掉，不然差几个字。
 */
private class Laid(
    private val a: Atlas,
    private val ch: Int,
    private val style: TextStyle,
    private var marks: java.util.SortedMap<Int, String>?,
    private var tagColor: Color,
    /** 从第几段排到第几段（Atlas 的段索引）。默认整章。 */
    private val segFrom: Int = a.pStartOf(ch),
    private val segTo: Int = a.pEndOf(ch),
    /** 块首要不要补一行章名。空 = 不补（整章那个已经在段里带上标题了）。 */
    private val title0: String = "",
) {
    private val sb = StringBuilder(1 shl 15)
    private val ss = ArrayList<Int>(512)
    private val sa = ArrayList<Int>(512)
    private val tf = ArrayList<Int>(32)
    private val tt = ArrayList<Int>(32)
    private val ta = ArrayList<Int>(32)
    private val gt = ArrayList<Int>(512)
    private val gn = ArrayList<Int>(512)
    /** 每个签是不是「未识别出」。跟 tf/tt/ta 一一对应。 */
    private val tu = ArrayList<Boolean>(512)

    val headLen: Int
    private var k: Int
    private val stop: Int

    /** 全章都排完了。 */
    var built = false
        private set

    /** 这一章从第几个段到第几个段（滚动那一档用一小块）。 */
    init {
        var s0 = segFrom.coerceIn(0, a.pFrom.size)
        val e0 = segTo.coerceIn(s0, a.pFrom.size)
        var t = title0.trim()
        /*
         * 这一块前面要不要写章名。
         *
         * 整章排版时 t 是空的，但章首那一行本来就常常是章名（正文第一段就是
         * 「第一章　河湾的雾」）。以前 buildLaid 无条件补一行标题、再把重复的那段
         * 跳掉；现在照旧 —— 不补的话，翻页那一档整个章名就没了。
         */
        if (t.isEmpty() && s0 == a.pStartOf(ch) && s0 < e0) {
            t = a.chTitle.getOrElse(ch) { "" }.trim()
        }
        if (t.isNotEmpty()) {
            sb.append(t).append("\n\n")
            /* 正文第一段就是这行章名，别再排一遍（否则屏幕上两行标题）。 */
            if (s0 < e0) {
                val ct = a.text.substring(
                    a.pFrom[s0].coerceIn(0, a.text.length),
                    a.pTo[s0].coerceIn(0, a.text.length)
                ).trim()
                if (ct == t || (ct.length in 8..60 && ct.endsWith(t))) s0++
            }
        }
        k = s0
        stop = e0
        headLen = sb.length
    }

    /** 排好的那串字现在有多长。 */
    val len: Int get() = sb.length

    /** 已经排到第几个段（相对块首）。 */
    val segDone: Int get() = k - segFrom

    /** 再长 n 个字符（或者长到全章排完）。 */
    fun grow(n: Int) {
        var guard = 0
        while (!built && sb.length < n && guard++ < 100_000) step()
    }

    /** 整章排完。只有「退到上一章最后一页」这种一次性动作才该叫它。 */
    fun growAll() {
        var guard = 0
        while (!built && guard++ < 100_000) step()
    }

    private fun step() {
        if (k >= stop) { built = true; return }
        if (sb.length > headLen) sb.append('\n')
        val p0 = a.pFrom[k]
        val p1 = a.pTo[k]
        sb.append(INDENT)
        ss.add(sb.length)
        sa.add(p0)
        gt.add(tf.size)
        /*
         * 名字签挂在**每一句**的引号前，不挂在段首。
         *
         * 一段里两个人说话就两个签，各落在自己那句头上。签不换行，
         * 只在行里插两三个小字，版式一行不多。
         */
        if (marks == null || marks!!.isEmpty() || p1 <= p0) {
            gn.add(0)
            sb.append(a.text, p0, p1)
        } else {
            var cur = p0
            var n = 0
            for (e in marks!!.subMap(p0, p1)) {
                val at = e.key
                if (at < cur) continue
                sb.append(a.text, cur, at)
                tf.add(sb.length)
                tu.add(e.value == UNKNOWN)
                sb.append(e.value).append('·')
                tt.add(sb.length)
                ta.add(at)
                cur = at
                n++
            }
            sb.append(a.text, cur, p1)
            gn.add(n)
        }
        k++
        if (k >= stop) built = true
    }

    /** 点到的这一格落在哪个名字签上。不在任何签上就是 null。 */
    fun tagAt(r: Int): Int? {
        /* 签子的位置是递增的，二分找最后一个 from <= r 的。 */
        var lo = 0
        var hi = tf.size - 1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (tf[m] <= r) lo = m + 1 else hi = m - 1
        }
        val i = hi
        if (i < 0) return null
        return if (r < tt[i]) ta[i] else null
    }

    /** 原文位置 → 排好的那串字里的位置。 */
    fun rendered(abs: Int): Int {
        if (sa.isEmpty()) return 0
        if (abs <= sa[0]) return ss[0]
        var lo = 0
        var hi = sa.size - 1
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (sa[m] <= abs) lo = m else hi = m - 1
        }
        var r = ss[lo] + (abs - sa[lo])
        if (lo < gt.size) {
            var i = gt[lo]
            val end = i + gn[lo]
            while (i < end) {
                if (ta[i] < abs) r += tt[i] - tf[i]
                i++
            }
        }
        return r.coerceIn(0, sb.length)
    }

    /** 排好的那串字里的位置 → 原文位置。 */
    fun abs(r: Int): Int {
        if (ss.isEmpty()) return 0
        tagAt(r)?.let { return it }
        if (r <= ss[0]) return sa[0]
        var lo = 0
        var hi = ss.size - 1
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (ss[m] <= r) lo = m else hi = m - 1
        }
        var shift = 0
        if (lo < gt.size) {
            var i = gt[lo]
            val end = i + gn[lo]
            while (i < end) {
                if (tf[i] < r) shift += tt[i] - tf[i]
                i++
            }
        }
        return (sa[lo] + (r - ss[lo] - shift)).coerceAtLeast(0)
    }

    /**
     * 切出 [from, to) 这一段，连样式一起带上。
     *
     * 只重建这一段，不碰整章 —— 这正是「五十万字一章也不卡」的来由。
     * 尾部的换行掐掉：带着它，画的时候会在末尾多排一条空行。
     */
    fun slice(from: Int, to: Int): AnnotatedString {
        var s0 = from.coerceIn(0, sb.length)
        var s1 = to.coerceIn(s0, sb.length)
        while (s1 > s0 && sb[s1 - 1] == '\n') s1--
        if (s1 <= s0) return AnnotatedString("")
        val sub = sb.substring(s0, s1)
        return buildAnnotatedString {
            append(sub)
            /* 章名那一段放大加粗。范围夹在本段里，越界会让 addStyle 抛。 */
            val hEnd = (headLen - 2 - s0).coerceAtMost(s1 - s0)
            if (headLen > 2 && s0 < headLen - 2 && hEnd > 0) {
                addStyle(
                    SpanStyle(fontSize = style.fontSize * 1.34f, fontWeight = FontWeight.Bold),
                    0, hEnd,
                )
            }
            /*
             * 签子要一眼看得见：0.78 倍、加粗、满朱色。
             * 上一版 0.62 倍还压暗，缩在正文里几乎认不出来。
             */
            val c = if (tagColor == Color.Unspecified) Color(0xFF8C3A2E) else tagColor
            /* 「未识别出」压淡一档：它是一个待办，不该跟真的名字一样抢眼。 */
            val mute = c.copy(alpha = 0.48f)
            for (i in tf.indices) {
                val f = tf[i]
                if (f >= s1) break
                val t = tt[i]
                if (t <= s0) continue
                addStyle(
                    SpanStyle(
                        fontSize = style.fontSize * 0.78f,
                        fontWeight = FontWeight.Bold,
                        color = if (i < tu.size && tu[i]) mute else c,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(
                            0.4f, androidx.compose.ui.unit.TextUnitType.Sp
                        ),
                    ),
                    (f - s0).coerceAtLeast(0), (t - s0).coerceAtMost(s1 - s0),
                )
            }
        }
    }
}

private const val INDENT = "\u3000\u3000"

/**
 * 开着「标出谁在说话」、可这一句没认出人来时，挂的那个签。
 *
 * 不写空：空的签画不出来，用户看到的就是「这里有对白，什么都没有」。
 */
private const val UNKNOWN = "未识别出"

/** 「读的时候标出谁在说话」那一遍最多算这么多字。理由同上。 */
private const val MARK_CAP = 150_000

/** 一上来先切几页。够铺满第一屏还多两页，点开不用等。 */
private const val FIRST = 6

/** 一次接着切几页。翻到倒数第二页就补，手感上看不出来。 */
private const val MORE = 8

/**
 * 排一小块（滚动那一档专用）。
 *
 * 传进来的是**段索引**区间：滚动那一档本来就是一段一段画的，一块几百字，
 * 排完就画，不存在「整章」。左右翻那条路不走它。
 */
private fun buildLaid(
    a: Atlas,
    from: Int,
    to: Int,
    title0: String,
    style: TextStyle,
): Laid {
    val c = if (from < a.pFrom.size) a.pCh[from] else 0
    val one = Laid(a, c, style, null, Color.Unspecified, from, to, title0)
    one.growAll()
    return one
}

/**
 * 一页：排好的那串字里，从哪到哪，一共几行。
 *
 * `lines` 是切页时**自己量出来的行数**。画的时候按它限住 ——
 * 少了它，画出来的排版只要跟量出来的差一点点，最后那一行就会在
 * 页脚上面露出小半个字身：既没画全，下一页又没有它。
 */
private class Pg(val start: Int, val end: Int, val lines: Int = 0)

/** 一屏要画的东西：哪一章、排好的那一章、哪一页、第几页共几页。 */
private data class View(
    val ch: Int,
    val laid: Laid,
    val pg: Pg,
    val pageNo: Int,
    val pageAll: Int,
)

/**
 * 把一章切成一页一页。每页都拿自己那一段文字量一次，不用「整章量完再按行号切」。
 *
 * 为什么不能按行号切：量整章和只画其中一段，断行会差一行。段落缩进、
 * 行尾换行、章名那行的 1.34 倍字号，都会让同一段字在两边排得不一样。
 * 差一行，页尾就多画出半行，被裁掉；下一页接着往下排，那个字就没了。
 *
 * 切的时候顺手记下这一页放了几行（[Pg.lines]），[TextBlock] 画的时候拿它当
 * `maxLines`。量和画用的是同一次结果。
 */
/**
 * 真正拿去画的那串字：掐掉页尾的换行。
 *
 * 带一个尾换行，[BasicText] 会在末尾多排一条空行出来 —— 那条空行占一行高，
 * 页尾就多出半个字身的位置，或者干脆把最后一行挤到 `maxLines` 之外、直接抹掉。
 * 量的时候带着它、画的时候也带着它，两边一起错，看着就像「这一页排得下但其实排不下」。
 */
private fun pageBody(loom: Laid, from: Int, to: Int): AnnotatedString = loom.slice(from, to)

/**
 * 接着往下切页。
 *
 * 为什么能「接着」：一章可能五十万字（有人把整本当一章切），一次切完要好几百次
 * 排版量算，全在主线程上，点开就是几秒钟不动 —— 用户说的「卡死」就是它。
 * 现在先切够看得见的那几页，往后翻一页再切一页；[out] 一直往下长。
 *
 * @param from0  从这串字里的哪个位置接着切（第一次传 0）
 * @param maxNew 这一趟最多再切几页
 * @return 下一次该从哪儿接着切；等于 text.length 就是切完了
 */
private fun splitInto(
    m: TextMeasurer,
    loom: Laid,
    style: TextStyle,
    w: Int,
    h: Int,
    out: MutableList<Pg>,
    from0: Int,
    maxNew: Int,
): Int {
    if (loom.len <= 0 && loom.built) {
        if (out.isEmpty()) out.add(Pg(0, 0))
        return 0
    }
    if (w <= 0 || h <= 0) {
        loom.growAll()
        out.add(Pg(0, loom.len))
        return loom.len
    }

    val cs = Constraints(maxWidth = w)
    var from = from0.coerceIn(0, loom.len)
    var added = 0
    while (added < maxNew) {
        /* 先保证手上这 700 字排出来了。 */
        loom.grow(from + 700)
        val total = loom.len
        if (from >= total) break
        // 段与段之间那个换行上一页已经用掉了，页首不该再吃一行空的。
        while (from < total && loom.slice(from, from + 1).text == "\n") from++
        if (from >= total) break

        var span = 700
        var page: Pg? = null
        while (page == null) {
            loom.grow(from + span)
            val total2 = loom.len
            val upto = (from + span).coerceAtMost(total2)
            val piece = loom.slice(from, upto)
            val lay = runCatching {
                m.measure(piece, style = style, constraints = cs)
            }.getOrNull()
            if (lay == null) {
                loom.growAll()
                out.add(Pg(from, loom.len, 0))
                return loom.len
            }

            // 这一页能完整放下几行。行底超过 h 的都不要 —— 宁可少一行，不留半行。
            var use = -1
            while (use + 1 < lay.lineCount && lay.getLineBottom(use + 1) <= h) use++

            val atEnd = upto >= total2
            if (use < 0 && !atEnd && span < 65536) {
                // 一行都放不下，是这段前缀太短，不是页面太矮。
                span *= 2
                continue
            }
            if (use + 1 >= lay.lineCount && !atEnd && span < 65536) {
                // 前缀用光了还没填满一页，得再多给一点才能算出这一页收在哪儿。
                span *= 2
                continue
            }
            if (use < 0) use = 0

            /*
             * 落点取第 use 行的**可见**行尾：`visibleEnd = true` 把行尾的换行和空白掐掉。
             * 不掐的话那个换行会跟着这一页走，画的时候末尾多一条空行，
             * 整页字往下挪一行 —— 页脚上吊着的那半个字就是它。
             */
            var e = from + lay.getLineEnd(use, visibleEnd = true)
            if (e <= from) e = (from + 1).coerceAtMost(total2)

            /*
             * 落到「真正要画的那串字」自己的排版上。
             *
             * 量前缀和量这一段，断行并不一样（行尾标点能不能落到下一行、段落缩进、
             * 章名那行 1.34 倍字号）。所以不能拿前缀的第 use 行去当结论。
             *
             * 做法是一步步收敛：先按前缀给一个落点，再拿这段字自己量一遍，
             * 用它自己的「最后一条放得下的行」重新定落点，直到它自己说「全都放得下」。
             * 每轮只会往前走，最多三四轮就一定停 —— 不会像「一行一行往回退」那样
             * 退过头，把一页退成半页。
             */
            var guard = 0
            while (guard++ < 6) {
                val body = pageBody(loom, from, e)
                val t2 = runCatching {
                    m.measure(body, style = style, constraints = cs)
                }.getOrNull()
                if (t2 == null || t2.lineCount <= 0) {
                    page = Pg(from, e, 1)
                    break
                }
                // 这段字自己排下来，最后一条底边还落在 h 之内的行是第几行。
                var fit = -1
                while (fit + 1 < t2.lineCount && t2.getLineBottom(fit + 1) <= h) fit++
                if (fit == t2.lineCount - 1) {
                    // 全放得下。就收到这里，行数按它自己数的来，画的时候按这个数收住。
                    page = Pg(from, e, t2.lineCount)
                    break
                }
                if (fit < 0) {
                    // 一行都放不下（页高比一行还矮），收一个字，宁可留白，不许丢字。
                    page = Pg(from, (from + 1).coerceAtMost(total2), 1)
                    break
                }
                val cut = from + t2.getLineEnd(fit, visibleEnd = true)
                e = if (cut > from) cut.coerceAtMost(total2)
                else (from + 1).coerceAtMost(total2)
            }
            if (page == null) page = Pg(from, e, 0)
        }
        out.add(page)
        added++
        from = page.end
    }
    return from
}

/**
 * 排好的章留着，换章 / 翻回来都不再重量一遍。
 *
 * 一章两个东西：排版结果（含段映射）和切好的页。两个都缓存，
 * 所以左右翻到边界那一刻，邻章的下一页早就在手上了，不用当场量。
 *
 * 只留最近十来章，书再厚也不会把内存吃穿。
 */
private class ChapterCache(
    private val a: Atlas,
    private val style: TextStyle,
    private val w: Int,
    private val h: Int,
    private val measurer: TextMeasurer,
) {
    private val lays = LinkedHashMap<Int, Laid>()

    /**
     * 已经切出来的页。**会长**，不是一次切完。
     *
     * 见 [splitInto]：五十万字一章一次切完要几百次排版量算，全在主线程，
     * 点开就是几秒钟不动 —— 用户说的「卡死」就是它。现在先切够眼前这几页，
     * 往后翻再接着切。
     */
    private val pgs = LinkedHashMap<Int, ArrayList<Pg>>()
    /** 这一章的页已经切到章尾。 */
    private val done = HashSet<Int>()
    /** 每章已经切到第几个字。 */
    private val at = HashMap<Int, Int>()

    /** 页表长了一点。读它的地方靠这个跟着刷新。 */
    var ver by mutableIntStateOf(0)
        private set

    /**
     * 名字签：**按章取**。
     *
     * 为什么要按章：上下滚那一档，一屏上下跨着好几章。以前只算当前这一章，
     * 滚到下一章就一个签都没有 —— 用户说的「滑到下一章就完全不会自动标出来」。
     * 现在每一章排的时候各自问一次。
     *
     * 换一次（或者这一章刚算出来）就把排好的全丢掉：签子插在正文中间，
     * 一个字不同，整章的断行、页数、页码全变，留着旧的只会让页码和画面打架。
     */
    var marksOf: ((Int) -> java.util.SortedMap<Int, String>?)? = null
        private set

    /**
     * 设一次签子来源。
     *
     * [sig] 是这个闭包的「第几版」（算完一章、开关换了都会变）。同一个 sig 反复设
     * 不算变化 —— 每帧都设一次会把缓存每帧清一次，页面就一直重排。
     */
    fun tags(sig: Int, f: ((Int) -> java.util.SortedMap<Int, String>?)?) {
        if (tagSig == sig && markSet) return
        tagSig = sig
        markSet = true
        marksOf = f
        dropAll()
    }

    private var tagSig = Int.MIN_VALUE
    private var markSet = false

    /** 签子那份变了（重算完一章、或者开关换了）：排好的全丢，重排。 */
    fun dropAll() {
        lays.clear()
        pgs.clear()
        at.clear()
        done.clear()
        ver++
    }

    var tagColor: Color = Color.Unspecified

    fun laid(ch: Int): Laid {
        lays[ch]?.let { return it }
        val m = marksOf?.invoke(ch)
        /* 整章那块：**只建壳，不排**。翻到哪页长到哪页，见 [Laid.grow]。 */
        val v = Laid(a, ch, style, m, tagColor)
        if (lays.size > 12) {
            val first = lays.keys.firstOrNull()
            if (first != null && first != ch) lays.remove(first)
        }
        lays[ch] = v
        return v
    }

    /** 现在能画的那几页。第一次读它的时候先切 [FIRST] 页。 */
    fun pages(ch: Int): List<Pg> {
        ver
        val got = pgs[ch]
        if (got != null && got.isNotEmpty()) return got
        grow(ch, FIRST)
        return pgs[ch] ?: ArrayList<Pg>(1).apply { add(Pg(0, 0)) }
    }

    /** 至少要有 [want] 页。翻到头了就叫它。 */
    fun ensure(ch: Int, want: Int) {
        ver
        val got = pgs[ch]
        if (got != null && got.size >= want) return
        grow(ch, want)
    }

    /** 这一章切到章尾了没有。 */
    fun allPages(ch: Int): Boolean = ch in done

    /**
     * 把这一章切到章尾。只在「退到上一章最后一页」这种一次性动作里用。
     *
     * 五十万字一章会在这里排上几秒 —— 但它只在按「上一章」、而且上一章特别长时发生一次。
     */
    fun ensureAll(ch: Int) {
        val loom = laid(ch)
        loom.growAll()
        var guard = 0
        while (ch !in done && guard++ < 4000) grow(ch, (pgs[ch]?.size ?: 0) + MORE)
    }

    /**
     * 这一章「排好的那串字」里某个位置落在第几页。
     *
     * 续着切，切到它为止 —— 恢复上次读到哪儿要用它。五十万字一章要切到很后面
     * 才停，但那是「上一次真的读到那么后面」，只发生一次。
     */
    fun pageOf(ch: Int, absInLaid: Int): Int {
        var guard = 0
        while (ch !in done && guard++ < 80) {
            val last = pgs[ch]?.lastOrNull()
            /* 已经盖住这个位置了，不用再往后切。 */
            if (last != null && last.end > absInLaid) break
            grow(ch, (pgs[ch]?.size ?: 0) + MORE)
        }
        val list = pgs[ch] ?: return 0
        var i = 0
        for (k in list.indices) if (list[k].start <= absInLaid) i = k else break
        return i.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    private fun grow(ch: Int, want: Int) {
        if (ch in done) return
        val loom = laid(ch)
        val list = pgs.getOrPut(ch) { ArrayList<Pg>(64) }
        var from = at[ch] ?: 0
        var rounds = 0
        /* 一帧里最多八轮、每轮 [MORE] 页；要得更多下一帧接着来，别把这一帧占满。 */
        while (ch !in done && list.size < want && rounds++ < 8) {
            from = splitInto(measurer, loom, style, w, h, list, from, MORE)
            at[ch] = from
            /* 全章排完、页也切到头了，才算这一章切完。 */
            if (loom.built && from >= loom.len) {
                done += ch
                if (list.isEmpty()) list.add(Pg(0, loom.len))
            }
            /* 没切到章尾就继续下一轮 —— 每轮最多 [MORE] 页，有轮数上限兜着。 */
        }
        if (pgs.size > 12) {
            val first = pgs.keys.firstOrNull { it != ch }
            if (first != null) {
                pgs.remove(first); at.remove(first); done.remove(first); lays.remove(first)
            }
        }
        ver++
    }

    fun warmOne(k: Int) {
        if (k in 0 until a.chStart.size) {
            laid(k)
            /* 只替邻章铺第一屏，不替它切整章 —— 邻章要是五十万字，翻过去就卡死。 */
            ensure(k, FIRST)
        }
    }
}

private fun sigOf(style: TextStyle, w: Int, h: Int): String =
    "${style.fontSize.value}|${style.lineHeight.value}|$w|$h"

// ───────────────────────── 阅读页 ─────────────────────────

@Composable
fun ReadScreen(book: BookMeta, settings: Settings, app: App, onBack: () -> Unit) {
    val s = LocalSkin.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()

    val full by produceState<String?>(null, book.id) {
        value = withContext(Dispatchers.IO) { app.shelf.read(book.id) }
    }
    // 目录要切五万行，每次打开都重跑就是「载入极慢」的根。切一次，落盘，下次直接读。
    val chapters by produceState<List<Chapter>>(emptyList(), full, book.id) {
        val t = full
        value = if (t == null) emptyList() else withContext(Dispatchers.IO) { Chapters.of(app, book, t) }
    }
    val atlas by produceState<Atlas?>(null, full, chapters) {
        val t = full
        value = if (t == null || chapters.isEmpty()) null
        else withContext(Dispatchers.Default) { Atlas.build(t, chapters) }
    }

    var ch by remember(book.id) { mutableIntStateOf(0) }
    // 上次读到哪儿，存的是「这一章里的第几个字」，不是「第几页」。
    // 章内字偏移不能直接当页号用，一个字符号能有几千，夹过去会落到末页。
    // 一进书就翻到章尾，看着像「往后跳了一页」，其实是这一格错了。
    var restoreAt by remember(book.id) { mutableIntStateOf(book.charOffset) }
    var wantPage by remember(book.id) { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    // 翻法切换时用来强制「重新落位」一次，见下面那个 LaunchedEffect。
    var modeTick by remember { mutableIntStateOf(0) }

    // 目录还没到之前不落位；到了才按存档跳过去，否则会掉回第一章。
    LaunchedEffect(chapters.size) {
        if (chapters.isNotEmpty()) {
            ch = book.chapterIndex.coerceIn(0, chapters.size - 1)
            restoreAt = book.charOffset
            wantPage = 0
        }
    }

    var desk by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    var bookSet by remember { mutableStateOf(false) }
    var castOpen by remember { mutableStateOf(false) }
    /** 点开的那个名字签（那一段的全书偏移）。null = 没开。 */
    var whoAbs by remember { mutableStateOf<Int?>(null) }
    /*
     * 顶上那条横幅点进来的：这一页要把派角纸撑开。
     * 只认一次，认得之后立刻收旗，免得退出去再进来又弹一遍。
     */
    LaunchedEffect(com.pagefold.reader.voice.AiJob.wantCast) {
        if (com.pagefold.reader.voice.AiJob.wantCast) {
            com.pagefold.reader.voice.AiJob.wantCast = false
            com.pagefold.reader.voice.AiJob.show()
            bookSet = false
            castOpen = true
        }
    }
    var rateOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }

    var armed by remember(book.id) { mutableStateOf(false) }
    // 这一回有没有按存档落好位。
    //
    // 没落好就先画第 1 页、落好了再滑到第 5 页 —— 眼睛看见的就是「一进来往后翻了一页」。
    // 所以落位算完之前，正文不画。
    var placed by remember(book.id) { mutableStateOf(false) }
    var settled by remember(book.id) { mutableStateOf(false) }
    var anchor by remember { mutableIntStateOf(-1) }
    var selKey by remember { mutableIntStateOf(0) }
    var cacheRef by remember { mutableStateOf<ChapterCache?>(null) }
    // 刚拖过进度条：这几秒不要跟着念的位置滚，不然手一松就被拽回去。
    var quietUntil by remember { mutableLongStateOf(0L) }
    // 最近一次滑动结束的时刻。「滑一下顺手把控制条带出来」就是没有这一格的后果。
    var scrollAt by remember { mutableLongStateOf(0L) }
    // 点空白收选区的那一刻记下来。同一次抬手不要再干第二件事（比如把听书台弹出来），
    // 否则「想收选区」会变成「收选区 + 弹面板」，看着就是没听手。
    var shutAt by remember { mutableLongStateOf(0L) }
    /*
     * 最近一次翻页的时刻。
     *
     * 翻页那一下的抬手不该再被判成「点了一下空白」—— 判成了，底下的听书台就弹出来，
     * 看着就是「翻一下就跳出控制栏」。旁边那两个（滑完、收完选区）是同一类闸门，
     * 这一格是给左右翻补的。
     */
    var flipAt by remember { mutableLongStateOf(0L) }

    /*
     * 块号 → 这一块排好的字 + 它的排版结果。
     *
     * 跟读要「滚到正在念的那一行」，就得知道那一行在块里离顶多少像素。
     * 只有块被画出来之后才有 TextLayoutResult，所以画的时候顺手记一笔；
     * 没画过的块先滚过去让它画出来，下一帧再精确对位。
     */
    val laidMap = remember { HashMap<Int, Pair<Laid, TextLayoutResult>>() }
    // 我们自己发起的滚动，别当成用户手动划走。
    var autoScroll by remember { mutableStateOf(false) }
    /*
     * 刚按下的那一会儿。
     *
     * SelectionContainer 在选词落定的瞬间会发一次 bring-into-view，让「选中的那几个字」可见。
     * 我们的一块（1400 字 / 20 段）比一屏高，LazyColumn 没法让它整块可见，
     * 就去把**块的边**对齐视口 —— 手刚按下去，屏幕自己往上或往下蹿一下。
     * 这是「明明选的是中间，却跑到上面或下面」的真正原因。
     *
     * 所以按下后 800ms 内，把那条请求夹回「已经可见」的范围里，等于什么也不做；
     * 过了这段时间（手指还在拖手柄）就放行，手柄拖到屏幕边缘照常继续滚。
     */
    var pressedAt by remember { mutableLongStateOf(0L) }
    /*
     * 选词落定那一下，LazyColumn 会收到一次 bring-into-view。
     *
     * 我们一块（1400 字 / 20 段）比一屏高，LazyColumn 没法让它整块可见，
     * 就去把**块的边**对齐视口 —— 手刚按下去，屏幕自己往上或往下蹿一下。
     * 这就是「明明选的是中间，却跑到上面或下面」。
     *
     * 不去碰 SelectionContainer 内部：按下的瞬间记下滚动位置，
     * 半秒内只要位置被推走了，就原样拉回来。之后手指拖手柄到边缘照常继续滚。
     */
    var guardAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /*
     * 手指/选择手柄压在屏幕上下边缘时，持续往那个方向滚。
     *
     * 为什么必须有这一段：我们一块（1400 字 / 20 段）比一屏高，LazyColumn 的
     * bring-into-view 只会把「块的边」对齐视口 —— 块已经露出来一角就算数，
     * 于是拖着手柄往下一路选，滚到某处就停住，再也下不去。
     * 「选字时不能一直往下拖」就是这个。
     *
     * 这里不看是谁在拖：只要有一个手指按着、且压在边缘带里，就按距离给速度。
     * 自己拖手柄、拖动选区、甚至普通滑动，都照常。
     */
    val barPush = remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    var userMoved by remember { mutableStateOf(false) }

    var castVer by remember { mutableIntStateOf(0) }
    // 刚改过嗓子或调门。这一趟要按新嗓子重念，并且从正在念的那一句接上。
    var recue by remember { mutableStateOf(false) }
    var bookNow by remember { mutableStateOf(book) }

    /** 这一本真正会用的嗓子、调门。界面上写的和念出来的必须是同一份。 */
    val castNow = remember(bookNow.castJson, settings.castJson, settings.narratorVoice, settings.defaultVoice, settings.engines, castVer) {
        profileOf(app, bookNow, com.pagefold.reader.field.Tune.apply(settings, bookNow.tuneJson))
    }

    /** 定音那一套为底，这本书自己的改动盖上去。整页所有「读法」都看这一个。 */
    val effNow = remember(settings, bookNow.tuneJson) {
        com.pagefold.reader.field.Tune.apply(settings, bookNow.tuneJson)
    }

    var sleepLeft by remember { mutableIntStateOf(Sleep.leftSec()) }
    /*
     * 这一本的念速从它自己的档案里读回来。
     *
     * 从 0 起的话，每次进书都当成「没设过」，退回全局那一档 ——
     * 书里调到 125，当场生效，退出再进就变回原速，看着像根本没存。
     * 存的 BookMeta.rate 一直是好的，只是没人读它。
     */
    var bookRate by remember(book.id) {
        mutableIntStateOf(bookNow.rate.takeIf { it in 50..300 } ?: 0)
    }
    LaunchedEffect(bookNow.rate) {
        val r = bookNow.rate
        if (r in 50..300 && r != bookRate) bookRate = r
    }

    /** 备货的实况。心里记着「要备到哪一回」，段数从播放器现取。 */
    var prep by remember(book.id) { mutableStateOf<PrepNow?>(null) }

    val bar = remember { BookBar2() }
    val list = rememberLazyListState()

    /*
     * 选区拖到屏幕上下边缘，就持续往那边滚。
     *
     * 看的是选区矩形，不是手指。选择手柄画在另一个窗口（Popup）里，
     * 手指的移动不会经过这一层的 pointerInput。
     * SelectionContainer 每次选区变化都会回调 showMenu(rect)，矩形跟着手柄走：
     * 贴着下边就往下滚，贴着上边就往上滚。
     */
    LaunchedEffect(list, bar.state) {
        while (isActive) {
            if (bar.state != TextToolbarStatus.Shown) { barPush.floatValue = 0f; delay(60); continue }
            val r = bar.rect
            val h = list.layoutInfo.viewportSize.height.toFloat()
            if (h <= 0f || r == Rect.Zero) { barPush.floatValue = 0f; delay(30); continue }
            val band = h * 0.16f
            val endY = r.bottom - listTop          // 选区末端在可视区里的位置
            val startY = r.top - listTop
            barPush.floatValue = when {
                endY > h - band -> ((endY - (h - band)) / band).coerceIn(0f, 1f) * 20f
                startY < band -> -((band - startY) / band).coerceIn(0f, 1f) * 20f
                else -> 0f
            }
            delay(16)
        }
    }

    // 真正滚的那一步。
    LaunchedEffect(list) {
        while (isActive) {
            val d = barPush.floatValue
            if (d != 0f) {
                val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                runCatching { list.scrollBy(d) }
                if ((list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) == before) delay(60)
                else delay(16)
            } else delay(40)
        }
    }

    /*
     * 什么才算「点了一下空白」。三条一起看，缺一条不算：
     * 手没在滑；刚滑完 260ms 内不算；没在收着选区。
     *
     * 少了后两条，往下滑的抬手也会被当成点击，控制条自己蹦出来。
     */
    val canTap: () -> Boolean = {
        val now = System.currentTimeMillis()
        now - shutAt > 350 &&
            !list.isScrollInProgress &&
            now - scrollAt > 260 &&
            now - flipAt > 320 &&
            bar.state != TextToolbarStatus.Shown
    }

    val ps by BookPlayer.flow.collectAsState()
    val playing = ps.playing

    /** 把「第几章、第几个字」落盘。听书、滚动、翻页三条路共用这一处算法。 */
    fun saveAt(a: Atlas, c: Int, rel: Int) {
        if (c !in a.chStart.indices) return
        val base0 = a.chStart[c]
        val off = rel.coerceIn(0, (a.chEnd.getOrElse(c) { base0 } - base0).coerceAtLeast(0))
        val abs = (base0 + off).coerceIn(0, a.text.length)
        app.shelf.touch(book.id, c, off, abs.toFloat() / a.text.length.coerceAtLeast(1))
    }

    /*
     * 听书时的进度：手动节流 3 秒一次，换章立即写。
     *
     * 不用 delay+collectLatest：那样一直念下去就永远不写。
     * 也不用 sample：换章那一下要立刻落，等 3 秒就可能漏掉一章。
     */
    LaunchedEffect(playing, atlas) {
        val a = atlas ?: return@LaunchedEffect
        if (!playing) {
            val st = BookPlayer.flow.value
            if (st.total > 0 && st.srcStart >= 0 && BookPlayer.owns(bookNow.title)) {
                withContext(Dispatchers.IO) { saveAt(a, st.chapter, st.srcStart) }
            }
            return@LaunchedEffect
        }
        var last = 0L
        var lastCh = -1
        snapshotFlow { ps.chapter to ps.srcStart }.collect { (c, ss) ->
            if (ss < 0) return@collect
            if (!BookPlayer.owns(bookNow.title)) return@collect
            val now = System.currentTimeMillis()
            if (c != lastCh || now - last > 3000L) {
                lastCh = c
                last = now
                withContext(Dispatchers.IO) { saveAt(a, c, ss) }
            }
        }
    }

    // 离开这一页时再保一次 —— 最后一句往往没赶上那个 3 秒窗口。
    DisposableEffect(book.id) {
        onDispose {
            val a = atlas
            val st = BookPlayer.flow.value
            if (a != null && st.total > 0 && st.srcStart >= 0 && BookPlayer.owns(bookNow.title)) {
                runCatching { saveAt(a, st.chapter, st.srcStart) }
            }
        }
    }


    // 备货是「只合成不播」那一趟。播着别的书的时候，这一趟不算数。
    val prepNow = remember(prep, ps.cachedOnly, ps.chapter, ps.index, ps.total, ps.book, ps.loading) {
        val base = prep
        if (base == null) null
        else if (ps.cachedOnly && ps.book == bookNow.title) {
            base.copy(
                running = ps.loading,
                ch = ps.chapter,
                at = ps.index.coerceAtLeast(0),
                all = ps.total.coerceAtLeast(0),
            )
        } else {
            base.copy(running = false, at = 0, all = 0, note = "上次备完了")
        }
    }
    val rate = if (bookRate in 50..300) bookRate else settings.rate

    /*
     * 正文版式一律取 effNow（定音为底，叠上这本书自己那几格），不取 settings。
     *
     * 取 settings 的话，「这一本怎么读」里改字号、行距、留白、翻法全都落不了地：
     * 值存进了 tuneJson，朗读也用上了，屏幕却不动。看起来就是「书里改没用」。
     */
    val bodyStyle = remember(effNow.fontSize, effNow.lineGap, s.ink, s.titleFont) {
        TextStyle(
            fontSize = effNow.fontSize.sp,
            lineHeight = (effNow.fontSize * effNow.lineGap / 100f).sp,
            color = s.ink,
            fontFamily = s.titleFont,
        )
    }

    /*
     * 这一句是谁在说话。只有开了那个开关才算。
     *
     * 算的是**整章的对白归属**（跟播放器同一个算法），一句一个签，
     * 落在这句引号前面。一段里两个人说话就两个签 —— 点哪个改哪个。
     *
     * 结果**按章存**：上下滚那一档一屏跨几章，滚到下一章要有签。
     * 一次算当前这一章和前后各一章，翻过去时手上已经是热的。
     */
    val cbMark = remember(bookNow.castJson, settings.castJson) {
        runCatching {
            com.pagefold.reader.book.RuleCodec
                .fromJson(bookNow.castJson.ifBlank { settings.castJson })
        }.getOrDefault(com.pagefold.reader.book.CastBook())
    }
    val markOn = cbMark.mark
    /* 章号 → 这一章每一句归谁。改过角色、换了章就重算。 */
    val markMap = remember(bookNow.castJson, castVer, markOn) {
        java.util.concurrent.ConcurrentHashMap<Int, java.util.SortedMap<Int, String>>()
    }
    /* 正在算第几章。避免同一章算两遍。 */
    val markBusy = remember(bookNow.castJson, castVer, markOn) {
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    }
    var markTick by remember(bookNow.castJson, castVer, markOn) { mutableIntStateOf(0) }

    /*
     * 滚动那一档当前看的是哪一章。它跟 ch 不一定同步（ch 也是滚出来的），
     * 但算签的优先级按它来更准 —— 用户在读的那一章先算，别算到两章之外。
     */
    var seenCh by remember { mutableIntStateOf(0) }
    /*
     * `chapters` 必须进 key。
     *
     * 上一版没有它：进书那一刻 `full` 先到、`chapters` 还是空的，这一遍一章都算不了
     * （`k !in chapters.indices` 全被跳过）；而它是个协程，读到空表**不会**被重新触发。
     * 于是「明明开着标出谁在说话，签子就是不出现」—— 直到去派角转一圈，
     * `castVer` 一变这个 effect 才重跑，那时 chapters 已经有了。（用户报的就是这个。）
     */
    LaunchedEffect(ch, bookNow.castJson, castVer, markOn, full, chapters, seenCh) {
        if (!markOn) return@LaunchedEffect
        val t = full ?: return@LaunchedEffect
        val cb = cbMark
        /* 眼前这几章先算：正在读的、前后各一。 */
        val order = intArrayOf(seenCh, ch, ch + 1, ch - 1, ch + 2)
        for (k in order) {
            if (k !in chapters.indices) continue
            if (markMap.containsKey(k) || markBusy.putIfAbsent(k, true) != null) continue
            val c = chapters[k]
            val body = withContext(Dispatchers.Default) {
                runCatching {
                    val s0 = c.start.coerceIn(0, t.length)
                    val s1 = minOf(c.end.coerceIn(0, t.length), s0 + MARK_CAP)
                    if (s1 <= s0) return@runCatching java.util.TreeMap<Int, String>()
                    val cut = t.substring(s0, s1)
                    val out = java.util.TreeMap<Int, String>()
                    val pins = cb.pins.filter { it.ch == k }.associate { it.at to it.who }
                    com.pagefold.reader.book.Cast.plan(cut, profileOf(app, bookNow, effNow), 0, pins)
                        .filter { it.isDialog }
                        .forEach { ln ->
                            if (ln.srcStart !in cut.indices) return@forEach
                            /*
                             * 键是**全书偏移**。
                             *
                             * [buildLaid] 拿段落起点（全书偏移）去 subMap 里取签，
                             * 上回存的是章内偏移，两边差着一个 c.start ——
                             * 签子于是插进了句子中间（「正午，街口的沈泊·电线杆下」）。
                             */
                            val who = cb.pinAt(k, ln.srcStart)?.who ?: ln.speaker
                            /*
                             * 认不出来的也要挂一个签。
                             *
                             * 用户开了「读的时候标出谁在说话」，引号里明明有人说话，
                             * 却一个标都没有 —— 他不知道是「没人说话」还是「没认出来」，
                             * 也就没法改。挂「未识别出」，点它就能指给谁。
                             */
                            out[s0 + ln.srcStart] = if (who.isBlank()) UNKNOWN else who
                        }
                    out
                }.getOrDefault(java.util.TreeMap())
            }
            markMap[k] = body
            markBusy.remove(k)
            markTick++
            cacheRef?.dropAll()
        }
    }

    /*
     * 点名字签要开的那张纸上，得知道「这一句现在归谁」。
     * 直接查那份按章存好的表 —— 章一定算过了，因为签是从它画出来的。
     */
    fun whoAt(abs: Int): String? {
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (chapters[m].start <= abs) lo = m else hi = m - 1
        }
        return markMap[lo]?.get(abs)
    }

    val feed = remember(full, chapters, bookNow.castJson, castVer, settings.castJson, settings.rulesJson, effNow.chunkChars) {
        val t = full ?: ""
        val cache = LinkedHashMap<Int, List<Line>>()
        val p = profileOf(app, bookNow, effNow)
        val chunk = effNow.chunkChars.coerceIn(200, 2000)
        Feed(
            book = bookNow.title,
            count = chapters.size,
            titleOf = { i -> chapters.getOrNull(i)?.title ?: "" },
            linesOf = { i ->
                cache[i] ?: run {
                    val c = chapters.getOrNull(i)
                    val text = if (c == null) "" else
                        t.substring(c.start.coerceIn(0, t.length), c.end.coerceIn(0, t.length))
                    val ls = if (text.isBlank()) emptyList() else
                        withContext(Dispatchers.Default) {
                            /*
                             * 单句改派要跟着念的那条路走。
                             *
                             * 只画在签子上、不交给 plan 的话，用户改完一句，
                             * 屏幕上写着新人，念出来还是原来那个嗓子。
                             */
                            val pins = cbMark.pins.filter { it.ch == i }.associate { it.at to it.who }
                            Cast.plan(text, p, chunk, pins)
                        }
                    if (cache.size > 6) cache.clear()
                    cache[i] = ls
                    ls
                }
            },
            /*
             * 进度写在这里，不写在界面上。
             *
             * 声音是后台念的，界面随时可能被回收；睡眠定时也是后台到点停的。
             * 这个 lambda 跟着 Feed 一起被播放器持有，播放器活着它就活着。
             */
            onPos = { c, off ->
                val base = chapters.getOrNull(c)?.start ?: 0
                val abs = (base + off).coerceIn(0, t.length.coerceAtLeast(0))
                app.shelf.touch(
                    book.id, c, off,
                    if (t.isEmpty()) 0f else abs.toFloat() / t.length
                )
            },
        )
    }

    // 换了嗓子：还从这一句这儿念，只是换个人。
    LaunchedEffect(recue, castVer) {
        if (!recue) return@LaunchedEffect
        recue = false
        val st = BookPlayer.flow.value
        if (st.total <= 0) return@LaunchedEffect
        val c = st.chapter.coerceAtLeast(0)
        val at = st.srcStart.coerceAtLeast(0)
        feed.rate = rate
        beginAt(app, feed, c, at, effNow, bookNow.tuneJson)
    }

    DisposableEffect(Unit) { onDispose { BookPlayer.onChapter = null } }
    LaunchedEffect(bookNow.title) {
        BookPlayer.onChapter = { c ->
            // 念的是别人的书，别把我的回目也一起改。
            if (BookPlayer.owns(bookNow.title) && c != ch) { ch = c; wantPage = 0; page = 0 }
        }
    }

    // 睡眠定时：每秒走一格，到点就停。暂停也照走 —— 它只认墙上那个时刻。
    LaunchedEffect(Unit) {
        while (true) {
            sleepLeft = Sleep.leftSec()
            delay(1000)
        }
    }

    BackHandler {
        when {
            bar.state == TextToolbarStatus.Shown -> { bar.hide(); selKey++ }
            castOpen -> castOpen = false
            bookSet -> bookSet = false
            toc -> toc = false
            rateOpen -> rateOpen = false
            timerOpen -> timerOpen = false
            desk -> desk = false
            else -> onBack()
        }
    }

    // 记下滑动的起止：一是给 canTap 判「这一抬手是不是滑动」，
    // 二是分清「我们自己跟读滚的」和「用户手动划走的」——
    // 后者要停一会儿，不然手刚划走就被拽回去。
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            scrollAt = System.currentTimeMillis()
            if (moving) {
                if (!autoScroll) userMoved = true
            } else if (userMoved) {
                userMoved = false
                quietUntil = System.currentTimeMillis() + 1800
            }
        }
    }

    // 按下后 500ms 内，位置被自动对齐推走了就拉回来。
    // 只认这段短窗：之后再滚，是用户自己在拖手柄，不能拦。
    LaunchedEffect(pressedAt) {
        val g = guardAt ?: return@LaunchedEffect
        if (pressedAt <= 0L) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 500L) {
            delay(16)
            if (autoScroll) continue
            val idx = list.firstVisibleItemIndex
            val off = list.firstVisibleItemScrollOffset
            if (idx != g.first || kotlin.math.abs(off - g.second) > 4) {
                runCatching { list.scrollToItem(g.first, g.second) }
                break
            }
        }
    }

    // 换了书或重排了，旧账一概作废。
    LaunchedEffect(atlas, bodyStyle) { laidMap.clear() }

    // 换了一章，上一次点下的那个位置就不作数了。
    // 不清的话，隔了几十章再按播放，会拿几十章前那个 anchor 去 chOfAbs —— 一下跳到那儿去。
    LaunchedEffect(ch) { anchor = -1 }

    LaunchedEffect(atlas) {
        val a = atlas ?: return@LaunchedEffect
        if (effNow.turn != TurnMode.FLIP) {
            val abs = a.chStart.getOrElse(book.chapterIndex) { 0 } + book.charOffset
            list.scrollToItem(a.blockOf(abs))
        }
        delay(50)
        settled = true
        delay(160)
        armed = true
    }

    /*
     * 换翻法：左右翻 ↔ 上下滚。
     *
     * 两套视图各记各的位置，切过去等于从头来：上下滚从第一块铺起，左右翻从第一章翻起。
     * 所以切之前先取「现在读到哪个字」，切过去按这个字落位 ——
     * 切到左右翻，算出它在第几章、章内第几个字，交给落位那一格；
     * 切到上下滚，把列表滚到那个字所在的块。
     *
     * 首次进页面不触发：lastTurn 初值等于当前值。
     */
    var lastTurn by remember { mutableStateOf(effNow.turn) }
    LaunchedEffect(effNow.turn) {
        if (lastTurn == effNow.turn) return@LaunchedEffect
        lastTurn = effNow.turn
        val at = atlas ?: return@LaunchedEffect
        if (effNow.turn == TurnMode.FLIP) {
            // 记住现在眼睛停在哪个字上
            val bi = list.firstVisibleItemIndex
            val abs = at.bFrom.getOrNull(bi) ?: return@LaunchedEffect
            val c = at.chOfAbs(abs)
            ch = c
            restoreAt = (abs - at.chStart.getOrElse(c) { 0 }).coerceAtLeast(0)
            wantPage = 0
            modeTick++
        } else {
            // 从翻页切回滚动：按「当前页的头一个字」放
            val abs = at.chStart.getOrElse(ch) { 0 } + when {
                ps.chapter == ch && ps.srcStart >= 0 -> ps.srcStart
                restoreAt > 0 -> restoreAt
                else -> 0
            }
            restoreAt = -1
            runCatching { list.scrollToItem(at.blockOf(abs)) }
        }
    }

    LaunchedEffect(atlas, armed, effNow.turn) {
        val a = atlas ?: return@LaunchedEffect
        if (!armed || effNow.turn == TurnMode.FLIP) return@LaunchedEffect
        snapshotFlow { list.firstVisibleItemIndex }.collect { bi ->
            delay(600)
            if (bi !in a.bFrom.indices) return@collect
            val abs = a.bFrom[bi]
            val c = a.chOfAbs(abs)
            saveAt(a, c, abs - a.chStart[c])
        }
    }

    /*
     * 念到哪儿就把进度写回书架。
     *
     * 听书时画面是被跟读逻辑带着走的，不经过用户手势，翻页和滚动那两条路都不会触发。
     * 这一条自己写：节流 4 秒一次，换章立即写。写的是「本章第几个字」和全书百分比，
     * 坐标跟别处一致。
     */
    val chapterTitle = chapters.getOrNull(ch)?.title ?: book.title
    val leftCh = (chapters.size - ch - 1).coerceAtLeast(0)

    // 这一段是不是「我这本书在念」。念的是别人的书，就别在自己身上画高亮 ——
    // 不然会出现「高亮在这一段，声音还是上一本」。
    val mine = ps.book.isNotEmpty() && ps.book == bookNow.title
    val hlFrom by remember(ps.srcStart, ps.chapter, atlas, playing, mine) {
        derivedStateOf {
            val a = atlas
            if (a == null || !playing || !mine || ps.srcStart < 0) -1
            else (a.chStart.getOrElse(ps.chapter) { 0 } + ps.srcStart).coerceIn(0, a.text.length)
        }
    }
    val hlTo by remember(ps.srcEnd, ps.chapter, atlas, playing) {
        derivedStateOf {
            val a = atlas
            if (a == null || !playing || !mine || ps.srcEnd <= ps.srcStart) -1
            else (a.chStart.getOrElse(ps.chapter) { 0 } + ps.srcEnd).coerceIn(0, a.text.length)
        }
    }

    Box(Modifier.fillMaxSize().background(s.ground)) {
        CompositionLocalProvider(LocalTextToolbar provides bar) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val padH = effNow.padH.dp
                val density = LocalDensity.current
                /*
                 * 宽度和高度都直接从**约束的像素值**来，不经 Dp 来回换算。
                 *
                 * 以前是 (maxWidth − padH*2).roundToPx()：Dp 减 Dp、再取整。
                 * 布局那边是「左边取整一次、右边取整一次、两者相减」。
                 * 密度 2.75 时这两种算法差 1px 以上。差 1px 的后果不是「窄一点」——
                 * 是某一个字本来该落在这一行，被挤到下一行，整页多一行；
                 * 画的时候 maxLines 收着，最后那一行被直接抹掉：这页看不见它，下一页又不收它。
                 *
                 * constraints.maxWidth / maxHeight 是这一层的真实像素，减掉和布局同一套
                 * roundToPx 的内边距，剩下的就是正文真正拿到的那个框。
                 */
                val wpx = with(density) {
                    (constraints.maxWidth - padH.roundToPx() * 2).coerceAtLeast(1)
                }
                /*
                 * 正文可用高度，按**逐边取整**算，跟布局走同一条路。
                 *
                 * 以前写的是「(maxHeight − 42dp).roundToPx()」：先把两个内边距合成一个数再取整，
                 * 和布局那边「上边取整一次、下边取整一次、两者相减」不是一个结果 ——
                 * density 不是整数时能差好几个像素。差一行不到，但那一行就是压在页脚上、
                 * 下一页又不收的那一行。
                 *
                 * 现在逐边取整，跟 Box 的 padding 用的是同一个函数、同一个密度，算出来就是布局用的那个数。
                 * 再让掉 1dp：亚像素那种差半格的情况不该由用户来发现。
                 */
                val hpx = with(density) {
                    val padTopPx = 14.dp.roundToPx()
                    val padBottomPx = 26.dp.roundToPx()
                    (constraints.maxHeight - padTopPx - padBottomPx).coerceAtLeast(1)
                }
                val sig = sigOf(bodyStyle, wpx, hpx)
                val a = atlas

                // 本章、上一章、下一章一起备好。翻过去是一瞬间的事，不是「正在加载」。
                // 排版器只能在主线程用，所以这里不切线程，一帧一帧地量。
                val cache = a?.let { at ->
                    remember(at, sig) { ChapterCache(at, bodyStyle, wpx, hpx, measurer) }
                }
                cache?.let { c ->
                    /*
                     * 按章取签。markTick 变了（新算出一章）就整块重排一次 ——
                     * 签子插在正文里，字一变，断行和页码都跟着变。
                     */
                    c.tags(
                        sig = if (markOn) markTick + 1 else 0,
                        f = if (markOn) ({ k: Int -> markMap[k] }) else null,
                    )
                    if (c.tagColor != s.flame) c.tagColor = s.flame.copy(alpha = 0.85f)
                }
                // 底下的控制条也要按页走，所以把这份排好的页露出去。
                LaunchedEffect(cache) { cacheRef = cache }
                /*
                 * 铺纸：先眼前这一屏，再往后一页一页备。
                 *
                 * 一章可能五十万字（有人整本切成一两章）。一上来就把整章切完，
                 * 主线程要量几百次，点开几秒钟不动 —— 用户说的「直接卡死」。
                 * 所以这里只保证「当前页 + 往后两页」，剩下的等翻到再切；
                 * 每备一页让出一帧，手感上是「翻过去就已经在了」。
                 */
                LaunchedEffect(cache, ch, chapters.size) {
                    cache?.warmOne(ch)
                    withFrameNanos { }
                    cache?.warmOne(ch + 1)
                    withFrameNanos { }
                    cache?.warmOne(ch - 1)
                }
                /* 当前章往后多备几页（不占这一帧）。这一条才是「进书就有缓存」。 */
                LaunchedEffect(cache, ch) {
                    withFrameNanos { }
                    cache?.ensure(ch, 12)
                    withFrameNanos { }
                    cache?.ensure(ch, 24)
                }

                if (effNow.turn == TurnMode.FLIP) {
                    if (cache == null || a == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            T("正在铺纸…", size = 12, color = s.inkFaint)
                        }
                    } else {
                        val pagesNow = cache.pages(ch)
                        val laidNow = cache.laid(ch)
                        val last = (pagesNow.size - 1).coerceAtLeast(0)
                        val pi = page.coerceIn(0, last)

                        LaunchedEffect(pagesNow.size) { pageCount = pagesNow.size.coerceAtLeast(1) }

                        /*
                         * 快翻到底了就把后面几页切出来。
                         *
                         * 页是接着切的（见 [splitInto]），一本五十万字一章的书
                         * 才不会一开就往主线程上堆几百次量算。留三页余量，
                         * 手指翻到的那一页永远已经在了。
                         */
                        LaunchedEffect(ch, page, pagesNow.size) {
                            cache.ensure(ch, page + 3)
                        }
                        // 第一次落位：把「第几个字」翻成「第几页」。
                        // 只做一次，之后翻页全靠 page 自己走，不再回头覆盖。
                        LaunchedEffect(ch, pagesNow.size, modeTick) {
                            val at = restoreAt
                            if (at < 0) return@LaunchedEffect
                            val abs = a.chStart.getOrElse(ch) { 0 } + at
                            /*
                             * 落到哪一页由排版位置说了算，且**接着往下切**。
                             *
                             * 上一版只在「已经切出来的那几页」里找，一本五十万字
                             * 一章的书存到第 300 页，回来只会落到第 6 页 —— 看着像
                             * 「上次读到哪儿它不记得」。
                             */
                            val i = cache.pageOf(ch, laidNow.rendered(abs))
                            restoreAt = -1
                            page = i
                            // 已经按存档落好位，就别让下面那一格再用 0 把它顶回去。
                            wantPage = -1
                            placed = true
                        }
                        LaunchedEffect(armed, ch, pagesNow.size, wantPage) {
                            if (!armed) return@LaunchedEffect
                            if (restoreAt >= 0) return@LaunchedEffect
                            placed = true
                            // -1 = 没有人在请求翻页。这一格只在「明确要求翻到第几页」时动手。
                            if (wantPage < 0) return@LaunchedEffect
                            val t0 = wantPage.coerceIn(0, last)
                            wantPage = -1
                            if (t0 != page) page = t0
                        }
                        LaunchedEffect(armed, ch) {
                            if (!armed) return@LaunchedEffect
                            snapshotFlow { page }.collectLatest { p0 ->
                                // 还没落稳就写回去，会把「章尾」记成书签，下回照抄。
                                if (restoreAt >= 0) return@collectLatest
                                delay(600)
                                val pg = pagesNow.getOrNull(p0) ?: return@collectLatest
                                val abs = laidNow.abs(pg.start)
                                withContext(Dispatchers.IO) { saveAt(a, ch, abs - a.chStart[ch]) }
                            }
                        }
                        // 念到哪一句，就翻到那一页。
                        // 比的是原文偏移：srcStart 记的是章内第几个字，不是排版后的位置。
                        // 两套坐标一比，永远算成第 0 页，于是「左定在章首页」。现在统一用原文偏移。
                        LaunchedEffect(ps.chapter, ps.srcStart, playing, bar.state) {
                            if (!playing || ps.srcStart < 0 || pagesNow.isEmpty()) return@LaunchedEffect
                            if (ps.chapter != ch) return@LaunchedEffect
                            // 选着字的时候不翻页。
                            if (bar.state == TextToolbarStatus.Shown) return@LaunchedEffect
                            val abs = a.chStart.getOrElse(ch) { 0 } + ps.srcStart
                            var i = 0
                            for (k in pagesNow.indices) {
                                if (laidNow.abs(pagesNow[k].start) <= abs) i = k else break
                            }
                            if (i != page) page = i
                        }

                        // 翻一页。章内和跨章走同一条路：没有过道页，没有第二套坐标，
                        // 换章只换内容，不重建这一块，免得整页闪一下。
                        var dir by remember { mutableIntStateOf(1) }
                        val turn: (Int) -> Unit = { d ->
                            dir = d
                            flipAt = System.currentTimeMillis()
                            if (d > 0) {
                                if (page + 1 < pagesNow.size) page += 1
                                else if (ch + 1 < chapters.size) { ch += 1; page = 0; wantPage = 0 }
                            } else {
                                if (page > 0) page -= 1
                                else if (ch > 0) {
                                    /*
                                     * 退到上一章的**最后一页**。
                                     *
                                     * 页是接着切的，上一章现在手上可能只有头几页 ——
                                     * 不能拿 pages().size 当总数，那会退到第 6 页。
                                     * 让它一直切到章尾，再取最后一页。
                                     */
                                    cache.ensureAll(ch - 1)
                                    val n = cache.pages(ch - 1).size
                                    ch -= 1
                                    page = (n - 1).coerceAtLeast(0)
                                    wantPage = page
                                }
                            }
                        }
                        // 只有一页的时候，左右划没有任何意义 —— 划了也不动，
                        // 手感上就是「这块卡住了」。索性不收这个手势。
                        Box(
                            Modifier.fillMaxSize()
                                /*
                                 * 左右翻：最外层自己接一道手势，抬手时判定。
                                 *
                                 * 走 Initial 阶段，外部节点先于内部拿到事件。正文里的
                                 * SelectionContainer 在按下那一刻就把 down 收走了，Main 阶段
                                 * 的拖动回调根本等不到「开始拖动」。
                                 *
                                 * 过程中一律不 consume，不跟选字抢；只在抬手时算总位移：
                                 * 横向过 56dp 且明显大于纵向 1.6 倍，翻一页。
                                 */
                                .pointerInput(ch, pi, pagesNow.size) {
                                    val need = 56.dp.toPx()
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitPointerEvent(PointerEventPass.Initial)
                                                .changes.firstOrNull { it.pressed && !it.previousPressed }
                                                ?: continue
                                            val id = down.id
                                            val from = down.position
                                            var to = from
                                            while (true) {
                                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                                val c = e.changes.firstOrNull { it.id == id } ?: break
                                                to = c.position
                                                if (!c.pressed) break
                                            }
                                            val dx = to.x - from.x
                                            val dy = to.y - from.y
                                            if (kotlin.math.abs(dx) > need &&
                                                kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f
                                            ) {
                                                if (bar.state == TextToolbarStatus.Shown) {
                                                    bar.hide(); selKey++
                                                }
                                                turn(if (dx > 0) -1 else 1)
                                            }
                                        }
                                    }
                                }
                        ) {
                            // 底下这一层接「点空白」。字上的点击由正文自己接，不会重复。
                            Box(
                                Modifier.fillMaxSize().clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { if (canTap()) desk = !desk }
                            )

                            // 落好位之前只画一层纸。落好了再画正文，
                            // 这样第一帧就是「存档那一页」，不会先画第 1 页再滑过去。
                            if (!placed) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    T("正在铺纸…", size = 12, color = s.inkFaint)
                                }
                            } else AnimatedContent(
                                targetState = ch to pi,
                                transitionSpec = {
                                    val f = dir
                                    slideInHorizontally(tween(210)) { w -> w * f } togetherWith
                                        slideOutHorizontally(tween(210)) { w -> -w * f }
                                },
                                label = "page",
                                modifier = Modifier.fillMaxSize(),
                            ) { target ->
                                val c = target.first
                                val pgNo = target.second
                                val lp = cache.pages(c)
                                val ld = cache.laid(c)
                                val idx = pgNo.coerceIn(0, (lp.size - 1).coerceAtLeast(0))
                                val pg = lp[idx]
                                /*
                                 * 一页 = 正文层 + 页脚层，两层叠在一起。
                                 *
                                 * 页脚是 Box 的最后一个子节点，画在最上面；正文层按「让出页脚那一条」
                                 * 的高度排版并裁切。做成 Column 的上下兄弟就不行：正文一高就把页脚顶出屏幕，
                                 * 章首页（标题 1.34 倍字号）最容易出这种情况，那页就没有页码了。
                                 */
                                Box(Modifier.fillMaxSize()) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(start = padH, end = padH, top = 14.dp, bottom = 26.dp)
                                            .clipToBounds()
                                    ) {
                                        TextBlock(
                                            laid = ld, from = pg.start, to = pg.end, style = bodyStyle,
                                            lines = pg.lines,
                                            hlFrom = hlFrom, hlTo = hlTo, selKey = selKey,
                                            onDown = { abs -> anchor = abs; pressedAt = System.currentTimeMillis() },
                                            onTag = { abs -> whoAbs = abs },
                                            onTap = { if (canTap()) desk = !desk },
                                            canTap = { canTap() },
                                            // 左右翻不用这条账：它按页切，不按块滚。
                                            onLaid = { _, _ -> },

                                        )
                                    }
                                    Row(
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = padH, end = padH, bottom = 10.dp)
                                            .fillMaxWidth()
                                            .height(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        T("${idx + 1} / ${lp.size}", size = 10, color = s.inkFaint)
                                        Spacer(Modifier.weight(1f))
                                        T(
                                            chapters.getOrNull(c)?.title ?: book.title,
                                            size = 10, color = s.inkFaint, maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (a == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            T("正在铺纸…", size = 12, color = s.inkFaint)
                        }
                    } else {
                        // 上下滚的时候，「第几回、第几页」都得跟着眼睛走。
                        LaunchedEffect(list) {
                            snapshotFlow { list.firstVisibleItemIndex }.collect { bi ->
                                // 刚拖完条，滚过去的路上会路过大半本书。
                                // 这一秒内照滚动位置反算页码，条子会被一路拽着走。
                                if (System.currentTimeMillis() < quietUntil) return@collect
                                val abs = a.bFrom.getOrNull(bi) ?: return@collect
                                val c = a.chOfAbs(abs)
                                if (c != ch) ch = c
                                if (cache == null) return@collect
                                val pgs = cache.pages(c)
                                if (pgs.isEmpty()) return@collect
                                val ld = cache.laid(c)
                                // 夹在这一回的正文范围里。块会跨过章尾，不夹的话
                                // 「最后一页」会被顶成 100%，看着就是进度条自己跑到最右。
                                // 页表里的 start 是「排好版的那串字」里的位置，不是全书位置。
                                // 拿全书位置去跟它比，会整整错开一章的缩进和标题，页码算出来是假的。
                                // 页码取「正在念的那一页」。取错的话条子先冲到头再弹回来。
                                pageCount = pgs.size
                                page = pgs.indexOfLast { ld.abs(it.start) <= abs }
                                    .coerceIn(0, pgs.size - 1)
                            }
                        }
                        // 念到哪，屏幕跟到哪。
                        //
                        // 同一页内不重启滚动动画：句子一短，动画还没走完就被新的打断。
                        // 看着就是「它停在那儿一直念，屏幕不动」。现在一句一句排着队滚，
                        // 手上的动画不被打断；自己正在滑的时候也不插手。
                        /*
                         * 把正在念的那个字摆到屏幕上。
                         *
                         * 算到行，不按块：块里那个字离顶多少像素，就把它放到视口 22% 处。
                         * 按块顶对齐的话，一块几十段，刚开口时屏幕会停在正在念的那段上面好几段，
                         * 念到块中间又回头再对一次。
                         *
                         * 块还没画过（没有排版结果）就先滚过去让它画，下一帧再对。
                         */
                        suspend fun follow(abs: Int) {
                            val bi = a.blockOf(abs).coerceIn(0, (a.bCount - 1).coerceAtLeast(0))
                            val vh = list.layoutInfo.viewportSize.height
                            if (vh <= 0) return
                            val want = (vh * 0.22f).toInt()
                            val slackLo = (vh * 0.10f).toInt()
                            val slackHi = (vh * 0.55f).toInt()

                            var rec = laidMap[bi]
                            if (rec == null) {
                                // 这一块还没画出来。先把它滚进视口，等它画完再来一次。
                                autoScroll = true
                                runCatching { list.scrollToItem(bi) }
                                withFrameNanos { }
                                withFrameNanos { }
                                autoScroll = false
                                rec = laidMap[bi]
                            }
                            if (rec == null) return

                            val (laid, lay) = rec
                            val r = laid.rendered(abs)
                            val box = runCatching {
                                if (r in 0 until lay.layoutInput.text.length) lay.getBoundingBox(r) else null
                            }.getOrNull()
                            val y = box?.top ?: 0f          // 这一行离块顶的像素
                            val yo = box?.bottom ?: 0f

                            /*
                             * 目标位置不能是负数。
                             *
                             * animateScrollToItem(bi, 负数) 不是「滚不动」，而是「把这一块往上推」：
                             * 这一块顶出屏幕，露出**上一块**的内容。本章第一块就是这种情况 ——
                             * 正在念的字在块顶附近，target 算出来负四百，屏幕一路往上，
                             * 上一章的尾巴就翻出来了。章与章之间正好在块边界上，所以「念本章头三个字，
                             * 跳到上一章」几乎是必然。
                             *
                             * 夹到 0：这一块顶对齐屏幕上沿，正在念的那个字就停在它本来的位置。
                             * 宁可让它比 target 低一点，也不能滚出这一块。
                             */
                            val target = ((y - want).toInt()).coerceIn(0, y.toInt().coerceAtLeast(0))
                            val top = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == bi }
                            if (top != null) {
                                // 这一行现在离视口顶有多远
                                val base = top.offset.toFloat()
                                val cur = base + y
                                val curBot = base + yo
                                // 已经舒舒服服在中间那一带，就不动 —— 每句都滚反而晃。
                                if (cur >= slackLo && curBot <= vh - 60) return
                            }
                            // top == null：这一块根本不在视口里（在很下面或很上面）。
                            // 这里不能提前 return：一屏翻完就再也不跟了。
                            autoScroll = true
                            runCatching { list.animateScrollToItem(bi, target) }
                            delay(120)
                            autoScroll = false

                            // 记的账别无限长：超了就清，反正画出来的块会重新记。
                            if (laidMap.size > 28) laidMap.clear()
                        }

                        LaunchedEffect(playing, list, bookNow.title) {
                            if (!playing) return@LaunchedEffect
                            /*
                             * 这一条必须「怎么着都还活着」。
                             *
                             * 旧写法一个 collect 到底：animateScrollToItem 被中断时会抛
                             * （手指一碰、切后台、重建，都会），collect 一结束，这个
                             * LaunchedEffect 的 key 又没变，就再没人重启它 ——
                             * 表现就是「放着不动，字不跟着念了」，而且非得退出重进才好。
                             * 现在外面套一层重来的环：出一次错，停半秒接着跟。
                             */
                            while (isActive) {
                                try {
                                    snapshotFlow { Triple(ps.book, ps.chapter, ps.srcStart) }.collect { tri ->
                                        if (tri.first != bookNow.title) return@collect
                                        val pc = tri.second
                                        val ss = tri.third
                                        if (ss < 0) return@collect
                                        if (list.isScrollInProgress) return@collect
                                        // 正选着字呢，别把画面拽走 —— 一拽就跑到别处去，
                                        // 手上那段选区也跟着没了。
                                        if (bar.state == TextToolbarStatus.Shown) return@collect
                                        if (System.currentTimeMillis() < quietUntil) return@collect
                                        val abs = a.chStart.getOrElse(pc) { 0 } + ss
                                        follow(abs)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    delay(400)
                                }
                            }
                        }
                        LazyColumn(
                            state = list,
                            /*
                             * 选字的时候照样能滚。
                             *
                             * 锁滚动能防选区错位，但拖手柄到屏幕边缘就选不下去了。
                             * 这里只掐掉按住那一刻的自动对齐（见 pressedAt），滚动不拦。
                             */
                            modifier = Modifier
                                .fillMaxSize()
                                // 记下列表在窗口里的纵向起点。选区那套坐标是「窗口坐标」，
                                // 要减掉这个才能和可视区比。
                                .onGloballyPositioned { c -> listTop = c.positionInWindow().y },
                            contentPadding = PaddingValues(start = padH, end = padH, top = 4.dp, bottom = 180.dp),
                        ) {
                            items(count = a.bCount, key = { k -> a.bFrom[k] }) { bi ->
                                /* 这一块的章、这一章的签。章一换或签子重算，块自己重排。 */
                                val bc = runCatching { a.pCh[a.bParaFrom[bi]] }.getOrDefault(0)
                                BlockText(
                                    a = a, bi = bi, style = bodyStyle,
                                    hlFrom = hlFrom, hlTo = hlTo, selKey = selKey,
                                    tag = if (markOn) markMap[bc] else null,
                                    tagSig = if (markOn) markTick + 1 else 0,
                                    onDown = { abs -> anchor = abs; pressedAt = System.currentTimeMillis(); guardAt = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset },
                                    onTag = { abs -> whoAbs = abs },
                                    onTap = { if (canTap()) desk = !desk },
                                    canTap = { canTap() },
                                    onLaid = { ld, r -> laidMap[bi] = ld to r },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!settled) Box(Modifier.fillMaxSize().background(s.ground))

        // 选着字的时候，点屏幕任何地方都收起工具条、把选区也一起放掉。
        if (bar.state == TextToolbarStatus.Shown) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.changes.any { it.changedToDownIgnoreConsumed() }) {
                                shutAt = System.currentTimeMillis()
                                bar.hide()
                                selKey++
                            }
                        }
                    }
                }
            )
        }

        val a = atlas
        AnimatedVisibility(
            visible = desk && bar.state != TextToolbarStatus.Shown,
            enter = slideInVertically(tween(200)) { it } + fadeIn(),
            exit = slideOutVertically(tween(170)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Desk(
                title = chapterTitle,
                ch = ch,
                totalCh = chapters.size,
                page = page,
                pageCount = pageCount,
                leftCh = leftCh,
                progress = if (effNow.turn == TurnMode.FLIP) {
                    if (pageCount <= 1) 0f else page.toFloat() / (pageCount - 1)
                } else if (pageCount <= 1) 0f else page.toFloat() / (pageCount - 1),
                playing = playing,
                rate = rate,
                stopHere = settings.stopAtChapter,
                sleepLeft = sleepLeft,
                onBack = onBack,
                onToc = { toc = true },
                onSet = { bookSet = true },
                onSeek = { frac ->
                    // 拖到哪就停在哪，而且**念也跟过去**。
                    //
                    // 画面和音频一起跳。只挪画面的话，屏幕上跳了，声音还在原处。
                    // 隔一会儿「跟着念的位置滚」那条又把画面拽回去，看着就是「跳转回去」。
                    quietUntil = System.currentTimeMillis() + 2200
                    val n = pageCount.coerceAtLeast(1)
                    val target = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    val a2 = atlas
                    val cc = cacheRef
                    var charAt = -1
                    if (a2 != null && cc != null) {
                        val pgs = cc.pages(ch)
                        val pg = pgs.getOrNull(target.coerceIn(0, pgs.size - 1))
                        if (pg != null) charAt = cc.laid(ch).abs(pg.start)
                    }
                    page = target
                    wantPage = -1
                    if (effNow.turn != TurnMode.FLIP && a2 != null && charAt >= 0) {
                        val abs = a2.chStart.getOrElse(ch) { 0 } + charAt
                        scope.launch { list.scrollToItem(a2.blockOf(abs)) }
                    }
                    if (playing && charAt >= 0) {
                        scope.launch { beginAt(app, feed, ch, charAt, effNow, bookNow.tuneJson) }
                    }
                },
                onPrevCh = {
                    if (ch > 0) {
                        ch -= 1; wantPage = 0; page = 0
                        val a2 = atlas
                        if (effNow.turn != TurnMode.FLIP && a2 != null) {
                            scope.launch { list.scrollToItem(a2.blockOf(a2.chStart.getOrElse(ch) { 0 })) }
                        }
                    }
                },
                onNextCh = {
                    if (ch + 1 < chapters.size) {
                        ch += 1; wantPage = 0; page = 0
                        val a2 = atlas
                        if (effNow.turn != TurnMode.FLIP && a2 != null) {
                            scope.launch { list.scrollToItem(a2.blockOf(a2.chStart.getOrElse(ch) { 0 })) }
                        }
                    }
                },
                onPrevLine = { BookPlayer.holder?.seekLine(-1) },
                onNextLine = { BookPlayer.holder?.seekLine(1) },
                onPlay = {
                    if (playing) BookPlayer.holder?.pauseNow()
                    else {
                        val st = BookPlayer.flow.value
                        if (st.total > 0) BookPlayer.holder?.resumeNow()
                        else scope.launch {
                            // 只有「点的位置就在眼下这一章」才拿它当起点。
                            // 隔着几十章的老 anchor 一律作废 —— 那一跳就是它干的。
                            val at = anchor.takeIf { it > 0 && a != null && a.chOfAbs(it) == ch } ?: -1
                            anchor = -1
                            feed.rate = rate
                            beginAt(app, feed, ch, anchorRel(a, ch, at), effNow, bookNow.tuneJson)
                        }
                    }
                },
                onRate = { rateOpen = true },
                onTimer = { timerOpen = true },
                onStopHere = {
                    app.field.edit { it.copy(stopAtChapter = !it.stopAtChapter, autoNext = it.stopAtChapter) }
                },
                // 「不听了」：连通知一起收掉。之前只能在通知里暂停，关不掉。
                onQuit = { BookPlayer.quit() },
                hasSession = ps.total > 0,
            )
        }

        // 念多快 / 念到几时停，都摆在整个屏幕的底层。
        //
        // 挂在整页这一层，不塞进控制条那个 Box：那个 Box 只有一条控制条那么高，
        // 「念到几时停」于是从屏幕顶上翻出来，把标题顶进状态栏。
        if (rateOpen) {
            SheetPop(title = "念多快", onClose = { rateOpen = false }) {
                FlowChips(
                    listOf(75, 90, 100, 110, 125, 150, 175, 200).map { it to rateText(it) },
                    rate,
                ) { r ->
                    bookRate = r
                    app.shelf.setRate(book.id, r)
                    bookNow = bookNow.copy(rate = r)
                    BookPlayer.holder?.setRate(r)
                    rateOpen = false
                }
            }
        }
        /*
         * 点名字签 → 就地挑人。
         *
         * 以前这里先弹一张「这一段谁在说话」的清单，再点一行才挑 —— 两步。
         * 签子已经写着是谁了，点它要的就是「换一个人」，中间那一步纯属多余。
         */
        whoAbs?.let { abs ->
            /*
             * 这一签在**哪一章**。
             *
             * 上下滚那一档一屏跨几章，签子可能来自下一章；按「当前读的那一章」
             * 存 pin，位置就偏了一整章，改了不生效。这里按全书偏移反查章号。
             */
            val pinCh = atlas?.chOfAbs(abs) ?: ch
            /*
             * 候选人是**这本书里数出来的所有人**，不是「配过嗓子的角色表」。
             *
             * 上一版读 roles：只从书里数过人、还没手动配角色的《大秦》，这儿是空的，
             * 于是点签子得到「这张表里还没有别人」。
             */
            val cb0 = runCatching {
                com.pagefold.reader.book.RuleCodec
                    .fromJson(bookNow.castJson.ifBlank { settings.castJson })
            }.getOrDefault(com.pagefold.reader.book.CastBook())
            val roles = rowsOf(cb0).map { it.name }
            val cur = whoAt(abs)?.takeIf { it != UNKNOWN }
            com.pagefold.reader.ui.PickWho(
                title = "这一句谁在说",
                roles = roles,
                current = cur,
                onPick = { who ->
                    val cb = com.pagefold.reader.book.RuleCodec
                        .fromJson(bookNow.castJson.ifBlank { settings.castJson })
                    /* 这一句的原文，存进 pin 里给台账看。只取这一句，不整本读。 */
                    val e = runCatching {
                        val rel0 = (abs - (chapters.getOrNull(pinCh)?.start ?: 0)).coerceAtLeast(0)
                        app.shelf.readChapter(book.id, pinCh).let { lineSrc ->
                            if (rel0 in lineSrc.indices) {
                                var k = rel0
                                while (k < lineSrc.length && lineSrc[k] != '\u300D' &&
                                    lineSrc[k] != '\u201D' && lineSrc[k] != '\n'
                                ) k++
                                lineSrc.substring(rel0, k).take(120)
                            } else ""
                        }
                    }.getOrDefault("")
                    /*
                     * 签子上带的是**全书偏移**（跟 Laid 的坐标一致），
                     * 但 pin.at 跟 Cast.plan 的 srcStart 同一把尺子 —— 章内偏移。
                     * 不换回来的话这条 pin 永远对不上任何一句，改了等于没改。
                     */
                    val rel = (abs - (chapters.getOrNull(pinCh)?.start ?: 0)).coerceAtLeast(0)
                    val keep = cb.pins.filterNot { it.ch == pinCh && it.at == rel }
                    val next = cb.copy(pins = keep + com.pagefold.reader.book.Pin(pinCh, rel, who, e))
                    val json = com.pagefold.reader.book.RuleCodec.toJson(next)
                    app.shelf.setCast(book.id, json)
                    bookNow = bookNow.copy(castJson = json)
                    castVer++
                    recue = true
                    whoAbs = null
                },
                onClose = { whoAbs = null },
            )
        }

        if (timerOpen) {
            TimerPop(
                leftSec = sleepLeft,
                onStart = { m ->
                    Sleep.start(m)
                    sleepLeft = Sleep.leftSec()
                    app.field.edit { it.copy(stopAtChapter = false, autoNext = true) }
                    timerOpen = false
                },
                onClear = {
                    Sleep.stop()
                    sleepLeft = 0
                    timerOpen = false
                },
                onClose = { timerOpen = false },
            )
        }

        if (bar.state == TextToolbarStatus.Shown) {
            SelPop(
                bar = bar,
                onCopy = {
                    runCatching { bar.copyAction?.invoke() }
                    bar.hide(); selKey++
                },
                onRead = {
                    val at = anchor
                    anchor = -1
                    val a2 = atlas
                    bar.hide(); selKey++
                    scope.launch {
                        runCatching {
                            // 同上：选中的那一段只在本章里认。不然「读这段」会跳到别章。
                            val keep = at.takeIf { it > 0 && a2 != null && a2.chOfAbs(it) == ch } ?: -1
                            feed.rate = rate
                            beginAt(app, feed, ch, anchorRel(a2, ch, keep), effNow, bookNow.tuneJson)
                        }
                    }
                },
            )
        }

        if (toc) {
            TocSheet(
                titles = chapters.map { it.title },
                current = ch,
                onPick = { i ->
                    ch = i.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                    wantPage = 0
                    val a2 = atlas
                    if (effNow.turn != TurnMode.FLIP && a2 != null) {
                        scope.launch { list.scrollToItem(a2.blockOf(a2.chStart.getOrElse(ch) { 0 })) }
                    }
                    if (playing) scope.launch { beginAt(app, feed, ch, 0, effNow, bookNow.tuneJson) }
                    toc = false
                },
                onClose = { toc = false },
            )
        }
        if (bookSet) {
            BookSetSheet(
                settings = settings,
                book = bookNow,
                app = app,
                title = chapterTitle,
                onCast = { castOpen = true },
                narName = castNow.narratorVoice,
                diaName = castNow.defaultVoice,
                onVoice = { narrator, name ->
                    val base = RuleCodec.fromJson(bookNow.castJson.ifBlank { settings.castJson })
                    val next = if (narrator) base.copy(narrator = name) else base.copy(dialog = name)
                    val json = RuleCodec.toJson(next)
                    app.shelf.setCast(book.id, json)
                    bookNow = bookNow.copy(castJson = json)
                    castVer++
                    // 嗓子变了，旧那段音频就不作数了：缓存键里带嗓音，天然分开。
                    // 但正在放的那一段还是老嗓子 —— 得从这一句重新开口。
                    recue = true
                },
                onStyle = { narrator, style ->
                    val base = RuleCodec.fromJson(bookNow.castJson.ifBlank { settings.castJson })
                    val next = if (narrator) base.copy(narratorStyle = style) else base.copy(dialogStyle = style)
                    val json = RuleCodec.toJson(next)
                    app.shelf.setCast(book.id, json)
                    bookNow = bookNow.copy(castJson = json)
                    castVer++
                    recue = true
                },
                prep = prepNow,
                onPrefetch = {
                    // 正在备的时候，这颗按钮是「停手」。
                    if (prep?.running == true) {
                        BookPlayer.quit()
                        prep = null
                        BookPlayer.say("不备了")
                    } else {
                    val from = ch + 1
                    val want = effNow.cacheChapters.coerceIn(1, 30)
                    if (from >= chapters.size) {
                        BookPlayer.say("已经是最后一章了，没得往后备")
                    } else {
                        val pool = Catalog.ours(effNow.engines, app.box.custom(), emptyList())
                        if (pool.isEmpty()) {
                            BookPlayer.say("一副嗓音都没开。先去定音里开一路")
                        } else {
                            // 备货要用同一套合成通道，所以得先把手上的播放让开。
                            BookPlayer.holder?.stopNow()
                            BookPlayer.say("从第 ${from + 1} 章起备 $want 章，备完自己收手")
                            prep = PrepNow(true, from, 0, 0, (from + want - 1).coerceAtMost(chapters.size - 1), "")
                            BookPlayer.run(app) {
                                it.play(
                                    feed, from, 0, effNow, pool,
                                    cacheOnlyMode = true, tune = bookNow.tuneJson,
                                )
                            }
                        }
                    }
                    }
                },
                onTune = { j ->
                    app.shelf.setTune(book.id, j)
                    bookNow = bookNow.copy(tuneJson = j)
                },
                onClose = { bookSet = false },
            )
        }
        if (castOpen) {
            /*
             * 派角是一整屏，不是一张纸。
             *
             * 上一版做成 84% 高的纸、底色透明，结果正文、控制条全从字缝里透出来，
             * 「添加角色」和「上一句」叠在一起，看着像整页都坏了。用户要的是
             * 「后面不要露出来」—— 那就给它一整屏、不透明底、自己吃状态栏。
             *
             * 「这一本怎么读」**不关**。它是下面那一层，被这屏盖住，状态（滚到哪儿）
             * 原样留着；从这里退出去，落回的还是刚才那一格，不会回到阅读页。
             */
            CastScreen(
                bookNow, app,
                onChanged = {
                    castVer++
                    bookNow = app.shelf.all().firstOrNull { it.id == book.id } ?: bookNow
                },
                /* 点「跳到书里」：翻到那一章那一句，两层都收掉，回正文。 */
                onJump = { c, at ->
                    castOpen = false
                    bookSet = false
                    wantPage = 0
                    restoreAt = at
                    ch = c.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                },
                chapters = chapters,
                sheet = false,
                onBack = { castOpen = false },
            )
        }

        /*
         * 一句话条。让开状态栏，横着留足边，四秒自己走。
         *
         * 它不是要人处理的对话框，是一句过场话，看清就够了。
         */
        ps.error?.let { m ->
            var alive by remember(m) { mutableStateOf(true) }
            LaunchedEffect(m) {
                delay(4200)
                alive = false
                // 把这条从播放状态里撤掉，免得下次进页面又看见。
                BookPlayer.forget()
            }
            if (alive) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 66.dp)
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(s.surface)
                            .border(1.dp, s.bad.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) { T(m, size = 11, color = s.bad) }
                }
            }
        }
    }
}

private fun anchorRel(a: Atlas?, ch: Int, anchor: Int): Int {
    if (a == null) return 0
    val base = a.chStart.getOrElse(ch) { 0 }
    if (anchor <= 0) return 0
    return (anchor - base).coerceIn(0, (a.chEnd.getOrElse(ch) { base } - base).coerceAtLeast(0))
}

private fun profileOf(app: App, book: BookMeta, s: Settings): CastProfile {
    val own = book.castJson.ifBlank { s.castJson }
    val cb = if (own.isNotBlank()) RuleCodec.fromJson(own) else null
    // 存下来的那个名字未必还活着（关了 MiMo，「白桦」就没了）。
    // 先看它在不在音色库里，不在就按开着的嗓源给一副，再不行留空 —— 空就是「未设置」。
    val pool = Catalog.ours(s.engines, app.box.custom(), emptyList())
    val narWant = cb?.narrator?.ifBlank { s.narratorVoice } ?: s.narratorVoice
    val diaWant = cb?.dialog?.ifBlank { s.defaultVoice } ?: s.defaultVoice
    /*
     * 引擎认谁：勾上的名单 + 角色自己的名字。两边并起来，免得「勾了没建角色」这一段
     * 和「建了角色没勾」那一段互相看不见。
     */
    val known = LinkedHashSet<String>()
    val canon = LinkedHashMap<String, String>()
    cb?.pool?.filter { it.on }?.forEach { n ->
        known += n.name
        n.alt.forEach { if (it.isNotBlank()) { known += it; canon[it] = n.name } }
    }
    cb?.roles?.forEach { r ->
        if (r.name.isNotBlank()) {
            known += r.name
            r.alt.forEach { if (it.isNotBlank()) { known += it; canon[it] = r.name } }
        }
    }
    val ban = if (known.isEmpty()) null else Ban(
        names = known,
        gender = cb?.sex.orEmpty(),
        packs = cb?.packs.orEmpty(),
        extraVerb = cb?.extraVerb.orEmpty(),
        extraAdv = cb?.extraAdv.orEmpty(),
        extraTitle = cb?.extraTitle.orEmpty(),
        off = cb?.off.orEmpty(),
        canon = canon,
    )
    return CastProfile(
        narratorVoice = AutoCast.resolve(pool, s.engines, narWant, dialog = false),
        defaultVoice = AutoCast.resolve(pool, s.engines, diaWant, dialog = true),
        narratorStyle = cb?.narratorStyle?.ifBlank { s.narratorStyle } ?: s.narratorStyle,
        dialogStyle = cb?.dialogStyle?.ifBlank { s.dialogStyle } ?: s.dialogStyle,
        rules = cb?.active().orEmpty(),
        voices = cb?.roles?.filter { it.voice.isNotBlank() }?.associate { it.name to it.voice }.orEmpty(),
        styles = cb?.roles?.filter { it.style.isNotBlank() }?.associate { it.name to it.style }.orEmpty(),
        ban = ban,
    )
}

private suspend fun beginAt(
    app: App,
    feed: Feed,
    ch: Int,
    rel: Int,
    s: Settings,
    tune: String,
) = runCatching {
    beginAtUnsafe(app, feed, ch, rel, s, tune)
}.onFailure {
    /*
     * 走到这儿是切句或取章出了问题，不是念不出来。
     *
     * 文案要指到事上：这一章还没切出句子，或者那一句的位置对不上。
     * 写成「换一段再试」会让人以为合成失败，去查钥匙和网络，白费工夫。
     */
    BookPlayer.say("这一章还没切好句，先翻两页再开口")
}

private suspend fun beginAtUnsafe(
    app: App,
    feed: Feed,
    ch: Int,
    rel: Int,
    s: Settings,
    tune: String,
) {
    val raw = withContext(Dispatchers.IO) { feed.linesOf(ch) }
    if (raw.isEmpty()) {
        BookPlayer.say("这一章没切出句子，先停在这儿")
        return
    }
    var at = raw.indexOfFirst { it.srcEnd > rel }
    if (at < 0) at = raw.size - 1
    val head = raw[at]
    val cut = (rel - head.srcStart).coerceIn(0, head.text.length)

    // 只用真正开着的嗓子。一副都没开就直说，别拿写死的名字去念。
    val pool = Catalog.ours(s.engines, app.box.custom(), emptyList())
    if (pool.isEmpty()) {
        BookPlayer.say("一副嗓音都没开。去定音里开一路，再来开讲")
        return
    }

    val f2 = if (cut <= 0) feed else Feed(
        book = feed.book,
        count = feed.count,
        titleOf = feed.titleOf,
        rate = feed.rate,
        onPos = feed.onPos,
        linesOf = { i ->
            val ls = feed.linesOf(i)
            // 空章节（整章都是空白、或者切句没切出东西）。
            // 原来这里直接 m.size - 1 = -1 去 coerceIn，Kotlin 会抛
            // 「Cannot coerce value to an empty range」——在协程里没人接，就是闪退。
            if (i != ch || ls.isEmpty()) ls
            else ls.toMutableList().also { m ->
                val l = m[at.coerceIn(0, m.size - 1)]
                val body = l.text.substring(cut.coerceAtMost(l.text.length))
                val lead = body.length - body.trimStart().length
                m[at.coerceIn(0, m.size - 1)] = l.copy(
                    text = body.trimStart(),
                    srcStart = (l.srcStart + cut + lead).coerceAtLeast(0),
                )
            }
        },
    )

    // 手上这一场如果念的是别的书，先彻底停掉 —— 否则新书在眼前，声音还是上一本。
    val cur = BookPlayer.flow.value
    if (cur.book.isNotEmpty() && cur.book != f2.book) BookPlayer.holder?.stopNow()

    BookPlayer.run(app) {
        it.play(f2, ch, at.coerceIn(0, raw.size - 1), s, pool, tune = tune)
    }
}

// ───────────────────────── 正文 ─────────────────────────

@Composable
private fun BlockText(
    a: Atlas,
    bi: Int,
    style: TextStyle,
    hlFrom: Int,
    hlTo: Int,
    selKey: Int,
    onDown: (Int) -> Unit,
    onTap: () -> Unit,
    canTap: () -> Boolean,
    onLaid: (Laid, TextLayoutResult) -> Unit,
    onTag: ((Int) -> Unit)? = null,
    /**
     * 这一块的名字签，和它的「第几版」[tagSig]。
     *
     * 上下滚那一档，一屏上下跨着好几章；上一版这里一个签都没传，
     * 所以「滑到下一章就完全不会自动标出来」。现在每块各自问一次自己那一章的。
     */
    tag: java.util.SortedMap<Int, String>? = null,
    tagSig: Int = 0,
) {
    val laid = remember(a, bi, style, tag, tagSig) {
        val f = a.bParaFrom.getOrElse(bi) { 0 }
        val t = a.bParaTo.getOrElse(bi) { f }
        val c = if (f < a.pCh.size) a.pCh[f] else 0
        val title = if (f == a.pStartOf(c)) a.chTitle.getOrElse(c) { "" } else ""
        /*
         * 块是段区间，不是整章 —— 直接把这一块的段范围交给 Laid，
         * 别让它从章首排到章尾（一章五十万字，那一量就是几秒）。
         */
        Laid(a, c, style, tag, Color.Unspecified, f, t, title).also { it.growAll() }
    }
    TextBlock(
        laid = laid, from = 0, to = laid.len, style = style,
        hlFrom = hlFrom, hlTo = hlTo, selKey = selKey,
        onDown = onDown, onTap = onTap, canTap = canTap, onLaid = onLaid, onTag = onTag,
    )
}

@Composable
private fun TextBlock(
    laid: Laid,
    from: Int,
    to: Int,
    style: TextStyle,
    hlFrom: Int,
    hlTo: Int,
    selKey: Int,
    onDown: (Int) -> Unit,
    onTap: () -> Unit,
    canTap: () -> Boolean,
    /**
     * 这一页最多画几行。0 = 不限（上下滚那条路用）。
     *
     * 左右翻必须给。切页时量过「这段字在这么高的地方能放几行」，
     * 画的时候就得按同一个行数收住 —— 中间只要差一行，
     * 页脚上面就会露出小半个字身，那半个字下一页又没有。
     */
    lines: Int = 0,
    onLaid: ((Laid, TextLayoutResult) -> Unit)? = null,
    /** 点到名字签上。参数是那一段的全书偏移。 */
    onTag: ((Int) -> Unit)? = null,

) {
    val s = LocalSkin.current
    // 和切页那边用的是同一个取字法：掐掉页尾换行，两边量出来、画出来才是同一份。
    val body = remember(laid, from, to) { laid.slice(from, to) }
    val lay = remember(laid, from) { Slot<TextLayoutResult>() }
    val a = if (hlFrom >= 0) laid.rendered(hlFrom) - from else -1
    val b = if (hlTo >= 0) laid.rendered(hlTo) - from else -1

    key(selKey) {
        /*
         * 选择域放在**块自己身上**，不往上包。
         *
         * 试过把 SelectionContainer 包在 LazyColumn 外面（想让选区跨块连下去），
         * 后果是整个上下滚都废了：选择容器会把拖动的 move 事件先吃掉，
         * LazyColumn 只在抬手那一下收到残余 —— 看着就是「松手才滚一点点」。
         * 这是 Compose 里选择与滚动抢同一份指针事件的必然冲突，不是参数没调好。
         *
         * 所以：滚动优先。选字范围 = 一块（约七百字、五段），够选一两段话。
         */
        SelectionContainer {
            BasicText(
                text = body,
                style = style,
                maxLines = if (lines > 0) lines else Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
                onTextLayout = { r ->
                    lay.v = r
                    onLaid?.invoke(laid, r)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (a < 0 || b <= a) return@drawBehind
                        val l = lay.v ?: return@drawBehind
                        val s0 = a.coerceIn(0, body.length)
                        val e0 = b.coerceIn(s0, body.length)
                        if (e0 > s0) drawPath(l.getPathForRange(s0, e0), s.flame.copy(alpha = 0.18f))
                    }
                    .pointerInput(laid, from) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val c = e.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                                if (c != null) {
                                    val off = lay.v?.getOffsetForPosition(c.position)
                                    if (off != null && off >= 0) {
                                        /*
                                         * 先看是不是点在名字签上。签子是旁注，点它要的是
                                         * 「这一句归谁」，不是「从这里开始读」。
                                         */
                                        val tg = laid.tagAt(from + off)
                                        if (tg != null && onTag != null) onTag(tg)
                                        else onDown(laid.abs(from + off))
                                    }
                                }
                            }
                        }
                    }
                    /*
                     * 抬手算不算「点了一下」，四件事缺一不算：
                     *   一、整趟手势里没有任何一个事件被上层滚动吃掉（吃了就是滑动）；
                     *   二、手指走过的最大距离 < 12px —— 只看起点终点会被「绕一圈回来」骗过去；
                     *   三、按下到抬起 < 320ms；
                     *   四、外面那几道闸（正在滑、刚滑完、正收着选区）都放行。
                     *
                     * 少了第一条，LazyColumn 一边滑一边吃事件，抬手照样被当成点空白。
                     */
                    .pointerInput(laid, from) {
                        awaitPointerEventScope {
                            var downPos: Offset? = null
                            var downAt = 0L
                            var far = 0f
                            var eaten = false
                            /*
                             * 按下那一下是不是落在名字签上。
                             *
                             * 落在签上就不算「点了一下空白」。不然点签改人这件事
                             * 会顺手把控制栏翻出来 —— 上一版就是点一下签，
                             * 上面那条控制栏跟着弹，正文还被顶走半屏。
                             */
                            var onTagDown = false
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Final)
                                val c0 = e.changes.firstOrNull() ?: continue
                                if (c0.pressed && !c0.previousPressed) {
                                    downPos = c0.position
                                    downAt = c0.uptimeMillis
                                    far = 0f
                                    eaten = c0.isConsumed
                                    val off0 = lay.v?.getOffsetForPosition(c0.position)
                                    onTagDown = off0 != null && off0 >= 0 &&
                                        laid.tagAt(from + off0) != null
                                } else if (c0.pressed) {
                                    val f0 = downPos
                                    if (f0 != null) {
                                        val d = (c0.position - f0).getDistance()
                                        if (d > far) far = d
                                        if (c0.isConsumed) eaten = true
                                    }
                                } else if (c0.previousPressed) {
                                    val f0 = downPos
                                    if (f0 != null) {
                                        val d = (c0.position - f0).getDistance()
                                        if (d > far) far = d
                                        if (c0.isConsumed) eaten = true
                                        if (!eaten && !onTagDown && far < 12f &&
                                            c0.uptimeMillis - downAt < 320L && canTap()
                                        ) onTap()
                                        onTagDown = false
                                    }
                                    downPos = null
                                }
                            }
                        }
                    }
            )
        }
    }
}

// ───────────────────────── 选中工具条 ─────────────────────────

class BookBar2 : TextToolbar {
    var state by mutableStateOf(TextToolbarStatus.Hidden)
    override val status: TextToolbarStatus get() = state
    var copyAction: (() -> Unit)? = null
    var rect by mutableStateOf(Rect.Zero)

    override fun hide() {
        state = TextToolbarStatus.Hidden
        copyAction = null
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        state = TextToolbarStatus.Shown
        copyAction = onCopyRequested
    }
}

@Composable
private fun SelPop(bar: BookBar2, onCopy: () -> Unit, onRead: () -> Unit) {
    val s = LocalSkin.current
    val r = bar.rect
    val gap = with(LocalDensity.current) { 52.dp.toPx() }
    Popup(
        offset = IntOffset(
            (r.left.toInt() - 40).coerceIn(8, 2000),
            (r.top - gap).toInt().coerceAtLeast(8),
        ),
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(s.surface)
                .border(1.dp, s.hairSoft, RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarText("复制", onCopy)
            Box(Modifier.width(1.dp).height(14.dp).background(s.hairSoft))
            BarText("从这里读", onRead)
        }
    }
}

@Composable
private fun BarText(text: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Text(
        text, fontSize = 12.sp, color = s.inkDim,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

// ───────────────────────── 篇目 ─────────────────────────

/**
 * 篇目。半张纸，不是整屏。
 *
 * 整屏的列表会把人从正文里拽出来 —— 看书看到一半想跳回，眼睛还想留在原地。
 * 半张纸能看到十来回，够翻，也不遮住正在念的那一段。
 */
@Composable
private fun TocSheet(
    titles: List<String>,
    current: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Paper(
        title = "篇目",
        sub = "共 ${titles.size} 章",
        onClose = onClose,
        maxFraction = 0.64f,
        scroll = false,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
        LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 20.dp),
            ) {
                items(count = titles.size) { i ->
                    val on = i == current
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) s.flameGhost else Color.Transparent)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onPick(i) }
                            .padding(vertical = 11.dp, horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            titles.getOrNull(i).orEmpty().ifBlank { "第 ${i + 1} 章" },
                            fontSize = 13.sp,
                            color = if (on) s.flame else s.ink,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (on) T("在读", size = 10, color = s.flame)
                    }
                }
        }
    }
}

// ───────────────────────── 书内设置 ─────────────────────────

private val TONE_PRESETS = listOf(
    "平静", "温和", "激昂", "低沉", "讲述",
    "慵懒", "严肃", "轻快", "磁性", "清冷", "急促", "耳语",
)

/** 正在备货的那一段：备到第几回、第几段、一共几段、要备到哪一回。 */
private data class PrepNow(
    val running: Boolean,
    val ch: Int,
    val at: Int,
    val all: Int,
    val to: Int,
    val note: String,
)

/**
 * 预缓存实况一行。
 *
 * 「第 12 回 · 3 / 47 段」比任何说明都好用 —— 它回答的是「还要多久」。
 * 没在备货的时候，写清上次备到第几回，别留一片空白。
 */
@Composable
private fun PrepLine(prep: PrepNow?, gap: Int) {
    val s = LocalSkin.current
    val row = prep
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(s.panelSink)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        if (row == null) {
            T("还没备过。按下去就从下一章开始备。", size = 11, color = s.inkFaint)
        } else if (row.running) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("正在备", size = 11, color = s.flame)
                    Spacer(Modifier.width(7.dp))
                    T("第 ${row.ch + 1} 章", size = 13, color = s.ink, weight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    T(
                        if (row.all > 0) "${row.at} / ${row.all} 段" else "正在切句",
                        size = 11, color = s.inkDim,
                    )
                }
                Spacer(Modifier.height(5.dp))
                T(
                    "要备到第 ${row.to + 1} 章" + if (row.all > 0) "，这一章还剩 ${(row.all - row.at).coerceAtLeast(0)} 段" else "",
                    size = 10, color = s.inkFaint,
                )
                if (row.all > 0) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(s.hair)) {
                        Box(
                            Modifier.fillMaxWidth((row.at.toFloat() / row.all).coerceIn(0f, 1f))
                                .height(2.dp).background(s.flame)
                        )
                    }
                }
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(row.note.ifBlank { "备到" }, size = 11, color = s.inkDim)
                    Spacer(Modifier.weight(1f))
                    T(if (gap > 0) "往后 $gap 章" else "没开", size = 10, color = s.inkFaint)
                }
                Spacer(Modifier.height(3.dp))
                T(
                    "上一次停在 第 ${row.ch + 1} 章" + if (row.all > 0) " · ${row.at} / ${row.all} 段" else "",
                    size = 11, color = s.ink,
                )
            }
        }
    }
}

@Composable
private fun BookSetSheet(
    settings: Settings,
    book: BookMeta,
    app: App,
    title: String,
    onCast: () -> Unit,
    narName: String,
    diaName: String,
    onVoice: (Boolean, String) -> Unit,
    onStyle: (Boolean, String) -> Unit,
    onTune: (String) -> Unit,
    prep: PrepNow?,
    onPrefetch: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    var pickVoice by remember { mutableStateOf<String?>(null) }
    var pickStyle by remember { mutableStateOf<String?>(null) }

    /**
     * 这本书的改动先存在这张纸上，再整张交出去。
     *
     * 每格都回头读 book.tuneJson 不行：那是外层传进来的旧值，改第二格时读到的是
     * 改第一格之前的表，第一格就被抹掉了。
     */
    var tuneJson by remember(book.id) { mutableStateOf(book.tuneJson) }

    val eff = remember(settings, tuneJson) {
        com.pagefold.reader.field.Tune.apply(settings, tuneJson)
    }

    fun shut() {
        focus.clearFocus()
        onClose()
    }

    fun tune(key: String, v: Int) {
        tuneJson = com.pagefold.reader.field.Tune.put(tuneJson, key, v)
        onTune(tuneJson)
    }

    /*
     * 子纸（挑嗓子、挑调门）必须摆在**这张纸外面**，同级。
     *
     * 写进这张纸的内容里，它拿到的是外层那段滚动的无限高度约束：
     * 量不出来，点下去先画一帧错的、再跳到位置上 —— 用户说的
     * 「往上闪了一下才倒到最下面」就是它。同级就没有这回事。
     */
    Box(Modifier.fillMaxSize()) {
    Paper(
        title = "这一本怎么读",
        sub = title,
        onClose = { shut() },
        /*
         * 半屏往上一点。用户的原话：「再稍微往上一点，到 65% 的状态就可以，不然太低了」。
         *
         * 50% 那版纸沿压得太低，第一屏只露出「版式」几行，看不出这张纸还有多少东西。
         * 65% 正好把「版式 + 声音头两行」一起露出来。
         */
        maxFraction = 0.65f,
        heightFraction = 0.65f,
        scroll = false,
    ) {
        Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.changes.any { it.changedToDownIgnoreConsumed() }) focus.clearFocus()
                            }
                        }
                    }
            ) {
                // ── 版式 ────────────────────────────────
                //
                // 这五格写的是**这一本**，不是全局。
                //
                // 只写进 book.tuneJson。写回 field 的话，定音台和这里抢同一个格子：
                // 书里改成左右翻，去定音点一下上下滚，回来又变成上下滚。
                // 现在一页一把：新书收进来时从定音抄一份快照，之后各改各的。
                Group("版式")
                TuneRow("fontSize", eff, ::tune)
                TuneRow("lineGap", eff, ::tune)
                TuneRow("padH", eff, ::tune)
                TuneRow("paraGap", eff, ::tune)
                Row2("翻法", "左边左右翻，右边上下滚") {
                    TwoPick(
                        listOf("左右翻", "上下滚"),
                        if (eff.turn == TurnMode.FLIP) 0 else 1,
                    ) { i -> tune("turn", i) }
                }

                // ── 声音 ────────────────────────────────
                Group("声音")
                // 显示的必须是**真正会用的那一副**。
                // 存的那个名字可能早就没在音色库里了（关了 MiMo，「白桦」就不存在），
                // 照抄它等于骗人；一副都挑不出来就写「未设置」。
                Row2("旁白", "念叙述的那一副") {
                    PickChip(narName.ifBlank { "未设置" }, narName.isNotBlank(), { pickVoice = "n" })
                }
                Row2("旁白调门", "留空就跟全局那档一样") {
                    PickChip(eff.narratorStyle.ifBlank { "跟随全局" }, eff.narratorStyle.isNotBlank(), { pickStyle = "n" })
                }
                Row2("对白", "念引号里的话") {
                    PickChip(diaName.ifBlank { "未设置" }, diaName.isNotBlank(), { pickVoice = "d" })
                }
                Row2("对白调门", "留空就跟全局那档一样") {
                    PickChip(eff.dialogStyle.ifBlank { "跟随全局" }, eff.dialogStyle.isNotBlank(), { pickStyle = "d" })
                }
                Row2("派角", "认出来是谁说的，就换一副嗓音") {
                    PickChip("去设", false, { onCast() })
                }

                // ── 读法 ──────────────────────────────
                Group("念的语气")
                TuneRow("context", eff, ::tune)
                TuneRow("next", eff, ::tune)
                TuneRow("chunkChars", eff, ::tune)
                TuneRow("gapMs", eff, ::tune)

                Group("预缓存")
                TuneRow("prefetch", eff, ::tune)
                TuneRow("cacheChapters", eff, ::tune)

                // 备到哪儿了，得写出来。
                //
                // 之前只有一个按钮，按下去什么也看不见 —— 不知道在备、备到了第几回、
                // 备没备完。断网前想确认一下，只能去猜。
                Spacer(Modifier.height(10.dp))
                PrepLine(prep, gap = eff.cacheChapters.coerceIn(0, 20))

                Spacer(Modifier.height(8.dp))
                Btn(
                    if (prep?.running == true) "这就停手" else "把往后 ${eff.cacheChapters.coerceIn(0, 20)} 章先备下",
                    onPrefetch,
                    if (prep?.running == true) BtnKind.DANGER else BtnKind.GHOST,
                    Modifier.fillMaxWidth(),
                )

                Group("取音频的方式")
                TuneRow("reqIntervalMs", eff, ::tune)
                TuneRow("retry", eff, ::tune)
                TuneRow("retryDelayMs", eff, ::tune)
                TuneRow("concurrency", eff, ::tune)

                // 一个按钮，全部回默认：这一本单独改过的那十格，加上版式、调门，一次清干净。
                Spacer(Modifier.height(18.dp))
                Btn(
                    "这一本全照默认",
                    {
                        // 「全照默认」= 抄一份此刻定音里那套，不是清空。
                        // 清空等于没有版式，落回全局 —— 而全局之后还会被人改，
                        // 那样这本书的版式就又跟着外面跑了。
                        val snap = com.pagefold.reader.field.Tune.snapshot(settings)
                        tuneJson = snap
                        onTune(snap)
                        app.field.edit { it.copy(narratorStyle = "", dialogStyle = "") }
                    },
                    BtnKind.GHOST,
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                T("版式、调门、读法，一起按定音里那套来", size = 10, color = s.inkFaint)
                Spacer(Modifier.height(28.dp))
        }
    }

        val vKey = pickVoice
        if (vKey != null) {
            val pool = remember(settings.engines, app.box.custom().size) {
                Catalog.groups(settings.engines, app.box.custom())
            }
            VoiceSheet(
                title = if (vKey == "n") "旁白用谁" else "对白用谁",
                groups = pool,
                current = if (vKey == "n") narName else diaName,
                onPick = { v ->
                    // 写进「这一本」，不写全局。
                    //
                    // 这张纸的标题是「这一本怎么读」，末尾还有「这一本全照默认」——
                    // 结果改旁白改的是全书库的默认，等于换个书就变回去了。
                    onVoice(vKey == "n", v.name)
                    pickVoice = null
                },
                onClose = { pickVoice = null },
            )
        }

        val sKey = pickStyle
        if (sKey != null) {
            StyleSheet(
                title = if (sKey == "n") "旁白什么调门" else "对白什么调门",
                current = if (sKey == "n") eff.narratorStyle else eff.dialogStyle,
                fallback = settings.style,
                presets = eff.style,
                onPick = { v ->
                    onStyle(sKey == "n", v)
                    pickStyle = null
                },
                onClose = { pickStyle = null },
            )
        }
    }
}

/**
 * 一格「读法」。
 *
 * 右边两个数：一个是你现在设的，一个是定音里那份。
 * 一样就写「跟定音」，不一样就点亮，让你一眼看出这本被单独调过。
 */
@Composable
private fun TuneRow(
    key: String,
    eff: Settings,
    onTune: (String, Int) -> Unit,
) {
    val r = com.pagefold.reader.field.Tune.range(key)
    val v = com.pagefold.reader.field.Tune.valueOf(eff, key)
    val meta = com.pagefold.reader.field.Tune.KEYS.firstOrNull { it.first == key }
    Row2(meta?.second ?: key, meta?.third) {
        Num(v, { onTune(key, it) }, r[0], r[1], r[2], com.pagefold.reader.field.Tune.unitOf(key))
    }
}

/** 两个并排，选中的那个填色。左边第一个就是左边那个。 */
@Composable
private fun TwoPick(options: List<String>, index: Int, onPick: (Int) -> Unit) {
    val s = LocalSkin.current
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(s.panelSink).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == index
            Text(
                label, fontSize = 12.sp,
                color = if (on) s.ground else s.inkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) s.flame else Color.Transparent)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(i) }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun Group(text: String) {
    val s = LocalSkin.current
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(text, size = 11, color = s.inkFaint)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(s.hairSoft))
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Row2(label: String, note: String?, right: @Composable () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            T(label, size = 13)
            if (note != null) T(note, size = 10, color = s.inkFaint)
        }
        right()
    }
}

@Composable
private fun Num(value: Int, onChange: (Int) -> Unit, min: Int, max: Int, step: Int, unit: String) {
    val s = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Key("−") { if (value - step >= min) onChange(value - step) }
        Box(
            Modifier.width(62.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(s.panelSink),
            contentAlignment = Alignment.Center
        ) { T("$value$unit", size = 12, color = s.ink) }
        Key("+") { if (value + step <= max) onChange(value + step) }
    }
}

@Composable
private fun Key(text: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(s.panel)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 15.sp, color = s.inkDim) }
}

/**
 * 挑嗓子。
 *
 * 列表来自你真正配好的那些路嗓源，按路分组；一路没开就是空的，
 * 不再拿写死的八个名字充数 —— 那样挑出来的嗓子根本念不出声。
 */
@Composable
private fun VoiceSheet(
    title: String,
    groups: List<Pair<String, List<com.pagefold.reader.voice.Voice>>>,
    current: String,
    onPick: (com.pagefold.reader.voice.Voice) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    Paper(
        title = title, onClose = onClose,
        maxFraction = 0.78f, heightFraction = 0.78f, scroll = false,
    ) {
        if (groups.isEmpty() || groups.all { it.second.isEmpty() }) {
            T("一副嗓音都没有。", size = 13, color = s.ink)
            Spacer(Modifier.height(4.dp))
            T("去定音里开一路声源，添一副，回来就有得挑了。", size = 11, color = s.inkFaint)
            return@Paper
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { (engineName, list) ->
                if (list.isEmpty()) return@forEach
                item(key = "h_$engineName") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T(engineName, size = 10, color = s.inkFaint)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(s.hairSoft))
                        Spacer(Modifier.width(8.dp))
                        T("${list.size} 副", size = 9, color = s.inkFaint)
                    }
                }
                items(count = list.size, key = { i -> engineName + "/" + list[i].id }) { i ->
                    val v = list[i]
                    val on = v.name == current
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onPick(v) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(v.name, size = 13, color = if (on) s.flame else s.ink)
                            if (v.desc.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                T(v.desc, size = 10, color = s.inkFaint, maxLines = 1)
                            }
                        }
                        if (on) Icon(Icons.Default.Check, null, tint = s.flame, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleSheet(
    title: String,
    current: String,
    fallback: String,
    presets: String = "",
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(current) }
    Paper(
        title = title,
        sub = "留空就跟「$fallback」一样",
        onClose = { focus.clearFocus(); onClose() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TONE_PRESETS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { st ->
                        PickChip(st, current == st, { onPick(st) })
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = s.ink),
                cursorBrush = SolidColor(s.flame),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(10.dp)).background(s.panelSink)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) T("自己写一个，比如：评书腔", size = 12, color = s.inkFaint)
                    inner()
                },
            )
            Spacer(Modifier.width(10.dp))
            Btn("就用这个", { onPick(text.trim()) }, BtnKind.PRIMARY)
        }
        if (current.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Btn("清空（跟随全局）", { onPick("") }, BtnKind.GHOST, Modifier.fillMaxWidth())
        }
    }
}

// ───────────────────────── 听书台 ─────────────────────────

@Composable
private fun Desk(
    title: String,
    ch: Int,
    totalCh: Int,
    page: Int,
    pageCount: Int,
    leftCh: Int,
    progress: Float,
    playing: Boolean,
    rate: Int,
    stopHere: Boolean,
    sleepLeft: Int,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSet: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrevCh: () -> Unit,
    onNextCh: () -> Unit,
    onPrevLine: () -> Unit,
    onNextLine: () -> Unit,
    onPlay: () -> Unit,
    onRate: () -> Unit,
    onTimer: () -> Unit,
    onStopHere: () -> Unit,
    onQuit: () -> Unit,
    hasSession: Boolean,
) {
    val s = LocalSkin.current
    val total = pageCount.coerceAtLeast(1)
    // 手指按在进度条上的时候，页码跟着手指走 —— 松手之前就知道会跳到哪儿。
    var seekAt by remember { mutableStateOf<Float?>(null) }
    val shownPage = seekAt?.let { ((it * (total - 1)).roundToInt() + 1).coerceIn(1, total) }
        ?: (page + 1).coerceIn(1, total)
    val left = leftCh

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .background(s.ground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                // 导航条收起来以后，底下没有任何东西挡着了 —— 再留一指宽，
                // 免得最下面那颗按钮贴着屏幕边缘。
                .padding(bottom = 8.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.08f)))
            Spacer(Modifier.height(6.dp))

            // 一、这本书与出口
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack, size = 32.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T(title.ifBlank { "未命名" }, size = 14, weight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    T(
                        "第 ${ch + 1} 章，共 $totalCh 章" + if (left > 0) "　还剩 $left 章" else "",
                        size = 10, color = s.inkFaint, maxLines = 1,
                    )
                }
                Tap("篇目", onToc)
                Spacer(Modifier.width(6.dp))
                Tap("怎么读", onSet)
            }

            // 二、上一章 ｜ 第几页 ｜ 下一章
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArrowWord("上一章", alignEnd = false, onPrevCh)
                T(
                    "第 $shownPage 页，共 $total 页",
                    size = 12,
                    color = if (seekAt != null) s.flame else s.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                ArrowWord("下一章", alignEnd = true, onNextCh)
            }

            Box(Modifier.padding(horizontal = 8.dp)) {
                // 这一章只有一页的时候，条子照样画（它说明「到头了」），
                // 但不接手势 —— 拖也拖不动，不如让它安安静静摊在那儿。
                PageSeek(
                    progress = progress,
                    onSeek = onSeek,
                    pages = total,
                    at = seekAt,
                    onAt = { seekAt = it },
                    movable = total > 1,
                )
            }

            // 三、定时。一整条横带，只有一行字。
            //
            // 定了就摆倒计时，没定就摆「定时关闭」四个字。
            // 底下的解释一律不要 —— 那是在替用户判断他看不看得懂。
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (sleepLeft > 0) s.flameGhost else s.panelSink)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onTimer() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sleepLeft > 0) {
                    T("定时关闭", size = 12, color = s.flame)
                    Spacer(Modifier.weight(1f))
                    Text(
                        Sleep.mmss(sleepLeft),
                        fontSize = 21.sp,
                        lineHeight = 24.sp,
                        color = s.flame,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    T("定时关闭", size = 13, color = s.inkDim)
                    Spacer(Modifier.weight(1f))
                    T("定", size = 12, color = s.inkFaint)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tap("上一句", onPrevLine, wide = true)
                Spacer(Modifier.width(24.dp))
                BigPause(playing, onPlay)
                Spacer(Modifier.width(24.dp))
                Tap("下一句", onNextLine, wide = true)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tap(rateText(rate), onRate, on = rate != 100)
                Spacer(Modifier.width(8.dp))
                Tap("读完这停", onStopHere, on = stopHere)
                // 没在念的时候不摆「不听了」—— 一颗永远按不动的按钮只是噪音。
                if (hasSession) {
                    Spacer(Modifier.width(8.dp))
                    Tap("不听了", onQuit)
                }
            }
        }

    }
}

/** 上一章 / 下一章。字轻，箭头收在靠里那一侧，一行里三样东西重量相当。 */
@Composable
private fun ArrowWord(label: String, alignEnd: Boolean, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!alignEnd) {
            T("‹", size = 14, color = s.inkFaint)
            Spacer(Modifier.width(3.dp))
            T(label, size = 12, color = s.inkDim)
        } else {
            T(label, size = 12, color = s.inkDim)
            Spacer(Modifier.width(3.dp))
            T("›", size = 14, color = s.inkFaint)
        }
    }
}

/**
 * 一个能被按的「字」。
 *
 * 不画边框：一屏六个带框的格子，看着像仪表盘。靠一层很淡的底色说明这里能按，
 * 按下时深一格。选中的用朱色，别的只有底色变化。
 */
@Composable
private fun Tap(
    label: String,
    onClick: () -> Unit,
    on: Boolean = false,
    enabled: Boolean = true,
    wide: Boolean = false,
) {
    val s = LocalSkin.current
    val fg = when {
        !enabled -> s.inkFaint.copy(alpha = 0.45f)
        on -> s.flame
        else -> s.inkDim
    }
    val bg = when {
        !enabled -> Color.Transparent
        on -> s.flameGhost
        else -> s.ink.copy(alpha = if (s.dark) 0.055f else 0.04f)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .then(
                if (enabled) Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onClick() } else Modifier
            )
            .padding(horizontal = if (wide) 20.dp else 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = fg, maxLines = 1)
    }
}

private fun rateText(r: Int): String {
    if (r == 100) return "原速"
    val v = r / 100f
    val t = if (v * 100 % 100 == 0f) "%.0f".format(v) else "%.2f".format(v).trimEnd('0')
    return t + "x"
}

private fun sleepShort(sec: Int): String {
    val m = (sec + 59) / 60
    return if (m >= 60) "${m / 60} 时 ${m % 60} 分" else "$m 分"
}

@Composable
private fun SheetPop(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    /*
     * 遮罩压一层纸色，不压黑。
     *
     * 黑色遮罩在任何一套主题里都是「另一个系统弹窗」，跟纸一点都不搭。
     * 现在这层由 [Paper] 画，点它等于关这一张 —— 这是人能预期的动作。
     *
     * **高度跟着内容长，不给定高。**
     *
     * 原来写死 0.82：八个小方块只占三行，下面大半张纸全是空的，纸沿还顶到屏幕
     * 五分之四那么高 —— 用户的原话是「不应该直接顶到顶格，不要有下面的空白」。
     * 现在给上限（长内容到顶再滚），短内容就收成一张小纸。
     */
    Paper(title = title, onClose = onClose) { content() }
}

/** 闹钟那样拨：上面一行常用，下面两列滚轮，时和分各拨各的。 */
/**
 * 念到几时停。
 *
 * 不用「定时关闭」这个词 —— 那是一个功能名，不是一个说法。
 * 六个常用的点，横着一排，点一下就是它；要别的数自己加减。
 * 每档都带一句老话（一刻 / 半时 / 三刻），认得出是多久，看着也不像仪表盘。
 */
/**
 * 念到几时停。
 *
 * 六个常用的点横着排，点一下就是它；要别的数自己加减。
 * 只留必要的一行状态，不写解释 —— 面板上每一句解释都是在替用户判断。
 */
@Composable
private fun TimerPop(
    leftSec: Int,
    onStart: (Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    val marks = listOf(15, 30, 45, 60, 90, 120)
    var mins by remember { mutableIntStateOf(if (leftSec > 0) (leftSec / 60).coerceIn(1, 180) else 30) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(s.scrim))
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                /*
                 * 跟 [Paper] 一样：纸和遮罩不能一个颜色。
                 *
                 * 两处都写 s.ground 的话，纸沿看不出来，内容外围那圈内边距就成了
                 * 「凭空空出来的一块」。用 surface + 一道 hair 边，纸在哪儿一眼看得见。
                 */
                .background(s.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .border(1.dp, s.hair, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                // 面板自己吃掉点击。
                // 面板要吃掉点击：不接的话，点在字与字之间的空隙会穿到遮罩上，面板直接关掉。
                // 表现就是「点一下数字，整张纸就没了」。
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("念到几时停", size = 16, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                // 一张能滑出来的纸，得有一处明说的出口。
                // 只有底下那颗「算了」，人往上找半天找不到关的地方。
                IconBtn(Icons.Default.Close, "关上", onClose, size = 30.dp)
            }
            // 正在走的时候，这里就是一块表。不给它位置，人只能去猜还剩多少。
            if (leftSec > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    T(Sleep.mmss(leftSec), size = 42, color = s.flame, weight = FontWeight.Medium)
                    Spacer(Modifier.width(9.dp))
                    T("到点自己打住", size = 11, color = s.inkFaint,
                        modifier = Modifier.padding(bottom = 9.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            marks.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { m ->
                        val on = mins == m
                        Box(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) s.flame else s.panelSink)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    mins = m
                                }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "$m",
                                    fontSize = 17.sp,
                                    color = if (on) s.onFlame else s.ink,
                                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "分",
                                    fontSize = 9.sp,
                                    color = if (on) s.onFlame else s.inkFaint,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T("自己定", size = 12, color = s.inkDim)
                Spacer(Modifier.weight(1f))
                Key("−") { if (mins - 5 >= 1) mins -= 5 }
                Box(
                    Modifier.width(78.dp).height(38.dp)
                        .clip(RoundedCornerShape(9.dp)).background(s.panelSink),
                    contentAlignment = Alignment.Center
                ) { T("$mins 分", size = 15, color = s.ink) }
                Key("+") { if (mins + 5 <= 180) mins += 5 }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (leftSec > 0) Btn("不设了", onClear, BtnKind.GHOST, Modifier.weight(1f))
                else Btn("算了", onClose, BtnKind.GHOST, Modifier.weight(1f))
                Btn("就这么定", {
                    // 定时这条链上任何一步出错（都是老接口），都不该把整个阅读页带走。
                    runCatching { onStart(mins) }
                }, BtnKind.PRIMARY, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Wheel(count: Int, initial: Int, unit: String, fmt: (Int) -> String, onChange: (Int) -> Unit) {
    val s = LocalSkin.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, (count - 1).coerceAtLeast(0)))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    Box(Modifier.height(132.dp).width(64.dp), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = 44.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count) { i ->
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        fmt(i),
                        fontSize = 18.sp,
                        color = if (state.firstVisibleItemIndex == i) s.ink else s.inkFaint,
                        fontWeight = if (state.firstVisibleItemIndex == i) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .border(1.dp, s.hair, RoundedCornerShape(10.dp))
        )
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { onChange(it) }
    }
}

@Composable
private fun TopWord(text: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Text(
        text, fontSize = 12.sp, color = s.inkDim,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 9.dp, vertical = 7.dp)
    )
}

@Composable
private fun SoftWord(text: String, onClick: () -> Unit, on: Boolean = false, enabled: Boolean = true) {
    val s = LocalSkin.current
    val fg = when {
        !enabled -> s.inkFaint.copy(alpha = 0.45f)
        on -> s.flame
        else -> s.inkDim
    }
    Text(
        text, fontSize = 12.sp, color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on && enabled) s.flameGhost else Color.Transparent)
            .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 11.dp, vertical = 8.dp)
    )
}

@Composable
private fun BigPause(playing: Boolean, onClick: () -> Unit) {
    val s = LocalSkin.current
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(s.flame)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (playing) Glyph.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "暂停" else "开始",
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

/**
 * 一条拖到底的进度条。做法跟定音里的滑杆（`Ruler`）一致：
 * 整条都能按 —— 横着推接手，一路跟到抬手；竖着推让路给列表；按一下不挪就是跳过去。
 *
 * 手柄只画不接手势，两端不会被裁掉半颗。轨道铺满整宽，第一页时手柄贴着左边。
 */
@Composable
private fun PageSeek(
    progress: Float,
    onSeek: (Float) -> Unit,
    pages: Int = 1,
    at: Float? = null,
    onAt: (Float?) -> Unit = {},
    /** 只有一页的时候没有可去的地方，别让手指以为能推。 */
    movable: Boolean = true,
) {
    val s = LocalSkin.current
    val v = (at ?: progress).coerceIn(0f, 1f)
    val gripDp = 26.dp

    Box(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp)) {
            val travel = (maxWidth - gripDp).coerceAtLeast(1.dp)
            val frac = v

            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2
                val half = gripDp.toPx() / 2
                val l = half
                val r = size.width - half
                drawLine(s.hair, Offset(l, cy), Offset(r, cy), 3.dp.toPx(), cap = StrokeCap.Round)
                if (frac > 0f) {
                    drawLine(
                        s.flame, Offset(l, cy), Offset(l + (r - l) * frac, cy),
                        3.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }

            Box(
                Modifier.fillMaxSize().pointerInput(travel, movable) {
                    if (!movable) return@pointerInput
                    val gripPx = gripDp.toPx()
                    val slop = viewConfiguration.touchSlop

                    fun valueAt(x: Float): Float {
                        val half = gripPx / 2
                        val travelPx = (size.width - gripPx).coerceAtLeast(1f)
                        return ((x - half) / travelPx).coerceIn(0f, 1f)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0   // 0 还没定，1 横着拖，2 让路
                        var endX = down.position.x
                        var f = frac

                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            endX = ch.position.x
                            val dx = ch.position.x - down.position.x
                            val dy = ch.position.y - down.position.y

                            if (mode == 0) {
                                if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                    mode = 1
                                    f = valueAt(ch.position.x)
                                    onAt(f)
                                    ch.consume()
                                } else if (abs(dy) > slop) {
                                    mode = 2
                                    break
                                }
                            } else {
                                f = valueAt(ch.position.x)
                                onAt(f)
                                ch.consume()
                            }
                            if (!ch.pressed) break
                        }

                        if (mode == 2) {
                            onAt(null)
                        } else {
                            if (mode == 0) {
                                // 点数：按哪儿跳哪儿
                                f = valueAt(endX)
                                onAt(f)
                            }
                            onSeek(f)
                            onAt(null)
                        }
                    }
                }
            )

            // 手柄只画，不接手势。
            Box(
                Modifier
                    .offset(x = travel * frac - (gripDp - 24.dp) / 2)
                    .size(gripDp)
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(gripDp)) {
                    val rad = size.minDimension / 2
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(s.flame.copy(alpha = if (at != null) 0.26f else 0.14f), rad, c)
                    drawCircle(s.surface, rad - 2.dp.toPx(), c)
                    drawLine(
                        s.flame,
                        Offset(c.x, size.height * 0.27f),
                        Offset(c.x, size.height * 0.73f),
                        (if (at != null) 4.5f else 3.5f).dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowChips(items: List<Pair<Int, String>>, picked: Int, onPick: (Int) -> Unit) {
    Column {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (v, label) ->
                    PickChip(label, picked == v, { onPick(v) }, Modifier.weight(1f))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
