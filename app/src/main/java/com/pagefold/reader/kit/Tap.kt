package com.pagefold.reader.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 无涟漪点击。
 *
 * 全应用统一用这一个：以前每一处都写
 * `clickable(indication = null, interactionSource = remember { MutableInteractionSource() })`，
 * 一个界面能重复几十遍，每遍各建一份 InteractionSource。
 */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        enabled = enabled,
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick,
    )
}
