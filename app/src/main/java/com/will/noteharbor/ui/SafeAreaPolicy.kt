package com.will.noteharbor.ui

import kotlin.math.max

/** Pure safe-area calculations shared by every programmatic screen. */
data class ContentPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

object SafeAreaPolicy {
    fun contentPadding(
        baseHorizontal: Int,
        baseTop: Int,
        baseBottom: Int,
        systemLeft: Int,
        systemTop: Int,
        systemRight: Int,
        systemBottom: Int,
        imeBottom: Int,
    ): ContentPadding = ContentPadding(
        left = baseHorizontal + systemLeft,
        top = baseTop + systemTop,
        right = baseHorizontal + systemRight,
        bottom = baseBottom + max(systemBottom, imeBottom),
    )

    fun overlayBottomMargin(
        baseMargin: Int,
        systemBottom: Int,
        imeBottom: Int,
    ): Int = baseMargin + max(systemBottom, imeBottom)
}
