package com.will.noteharbor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeAreaPolicyTest {
    @Test
    fun contentPaddingIsDerivedFromImmutableBaseValues() {
        val first = SafeAreaPolicy.contentPadding(
            baseHorizontal = 22,
            baseTop = 20,
            baseBottom = 32,
            systemLeft = 0,
            systemTop = 24,
            systemRight = 0,
            systemBottom = 48,
            imeBottom = 0,
        )
        val afterNavigation = SafeAreaPolicy.contentPadding(
            baseHorizontal = 22,
            baseTop = 20,
            baseBottom = 32,
            systemLeft = 0,
            systemTop = 24,
            systemRight = 0,
            systemBottom = 48,
            imeBottom = 0,
        )

        assertEquals(first, afterNavigation)
        assertEquals(22, first.left)
        assertEquals(44, first.top)
        assertEquals(22, first.right)
        assertEquals(80, first.bottom)
    }

    @Test
    fun keyboardInsetWinsOverNavigationInsetForContentAndOverlay() {
        val padding = SafeAreaPolicy.contentPadding(
            baseHorizontal = 22,
            baseTop = 12,
            baseBottom = 32,
            systemLeft = 3,
            systemTop = 24,
            systemRight = 5,
            systemBottom = 48,
            imeBottom = 420,
        )

        assertEquals(3 + 22, padding.left)
        assertEquals(12 + 24, padding.top)
        assertEquals(5 + 22, padding.right)
        assertEquals(32 + 420, padding.bottom)
        assertEquals(24 + 420, SafeAreaPolicy.overlayBottomMargin(24, 24, 420))
    }
}
