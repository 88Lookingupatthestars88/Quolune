package com.pagefold.reader.kit

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 左右翻页。关键设计：不做跟手位移。 */
@Composable
fun FlipDeck(
    count: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 已经在本章最后一页还往前拖 —— 交给调用方去翻章。 */
    onOverflowNext: (() -> Unit)? = null,
    onOverflowPrev: (() -> Unit)? = null,
    content: @Composable (Int) -> Unit,
) {
    if (count <= 0) return
    val pager = rememberPagerState(initialPage = index.coerceIn(0, count - 1)) { count }
    val scope = rememberCoroutineScope()
    var acc by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(index, count) {
        val target = index.coerceIn(0, count - 1)
        if (pager.currentPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != index) onIndex(pager.currentPage)
    }

    Box(
        modifier.fillMaxSize().pointerInput(count) {
            val thr = 46.dp.toPx()
            detectDragGestures(
                onDragStart = { acc = 0f },
                onDragEnd = {
                    if (abs(acc) > thr) {
                        val next = if (acc < 0) pager.currentPage + 1 else pager.currentPage - 1
                        if (next in 0 until count) {
                            scope.launch { pager.animateScrollToPage(next) }
                        } else if (next >= count) {
                            onOverflowNext?.invoke()
                        } else {
                            onOverflowPrev?.invoke()
                        }
                    }
                    acc = 0f
                },
                onDragCancel = { acc = 0f }
            ) { _, drag ->
                acc += drag.x
            }
        }
    ) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            content(page)
        }
    }
}

/** 上下滚动：交给调用方的 LazyColumn，这里只提供统一容器。 */
@Composable
fun ScrollDeck(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) { content() }
}
