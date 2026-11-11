package com.pagefold.reader.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 一张纸：从底下抽上来，压在原来那一屏上。
 *
 * **不用 Dialog 窗口。** 试着改过一版，纸上会多出两个毛病，都是窗口自己的：
 * 点下去先按窗口默认尺寸（包着内容、居屏幕中间）画一帧，然后才被改成满屏 ——
 * 看着就是「往上闪一下再倒到底下」；还有系统那层窗口动画会淡入一下。
 * 画在同一个界面里没有这两步，点下去就已经在那儿了。
 *
 * 纸套纸的规矩：**不许把纸写进另一张纸的内容里**，写在外层纸后面（同级），
 * 后画的压在上面。比开窗口省事，也还是原来那个手感。
 *
 * 内容区带 `weight(1f)` 的纸传 [heightFraction]，要一个确定高度才分得到空间。
 */
@Composable
internal fun Paper(
    title: String,
    onClose: () -> Unit,
    /** 标题下面那行小字。空就不占行。 */
    sub: String = "",
    /** 纸最多占屏幕多高。0.62 是篇目那种长列表，0.92 是设置那种满的。 */
    maxFraction: Float = 0.86f,
    /** 要一个确定高度的传这个（和 maxFraction 一样大）。 */
    heightFraction: Float? = null,
    /** 内容区自己滚不滚。里面是 LazyColumn 或自带滚动的传 false。 */
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalSkin.current

    /*
     * 返回键先收这张纸。
     *
     * 纸是叠上去的，按返回该退的是「最上面这一层」—— 上一版所有纸都靠调用方
     * 自己记得处理，漏掉一处就退回阅读页。写在纸里，一层都不用再记。
     * 后画的纸后注册，优先收到返回，正好是「先收子纸、再收父纸」。
     */
    BackHandler { onClose() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = maxHeight * maxFraction
        Box(
            Modifier.fillMaxSize().background(s.scrim)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClose() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .then(
                    if (heightFraction != null) Modifier.fillMaxHeight(heightFraction)
                    else Modifier.heightIn(max = cap)
                )
                /*
                 * 纸和底下的遮罩必须不是一个颜色。
                 *
                 * 上一版两处都写 s.ground（遮罩再压 0.72 的透明），于是整屏一个色：
                 * 纸的上沿看不出来，内容左边那点留白就成了「凭空空出来的一块」。
                 * 用 surface（各套材质的不透明底）+ 一道 hair 边，纸在哪儿一眼就看得见。
                 */
                .background(s.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .border(
                    1.dp, s.hair,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                .pointerInput(Unit) { detectTapGestures { } }
                .imePadding()
                .navigationBarsPadding()
                .then(
                    if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(title, size = 15, weight = FontWeight.Medium)
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        T(sub, size = 10, color = s.inkFaint, maxLines = 2)
                    }
                }
                IconBtn(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回上一级", onClose, size = 30.dp,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(14.dp))
        }
    }
}
