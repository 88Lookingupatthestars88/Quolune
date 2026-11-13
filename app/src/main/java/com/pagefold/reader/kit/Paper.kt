package com.pagefold.reader.kit

import androidx.activity.compose.BackHandler
import com.pagefold.reader.kit.tap
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

/** 一张纸：从底下抽上来，压在原来那一屏上。 */
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

    /* 返回键先收这张纸。纸是叠上去的，退的是最上面这一层。 */
    BackHandler { onClose() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = maxHeight * maxFraction
        Box(
            Modifier.fillMaxSize().background(s.scrim)
                .tap { onClose() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .then(
                    if (heightFraction != null) Modifier.fillMaxHeight(heightFraction)
                    else Modifier.heightIn(max = cap)
                )
                /* 纸和底下的遮罩必须不是一个颜色。 */
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
