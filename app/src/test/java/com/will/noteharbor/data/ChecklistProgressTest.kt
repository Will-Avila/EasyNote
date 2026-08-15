package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistProgressTest {
    @Test
    fun reportsCompletedItemsAndPercentage() {
        val progress = ChecklistProgress.of(
            listOf(
                ChecklistItem("um", completed = true),
                ChecklistItem("dois"),
                ChecklistItem("três", completed = true),
                ChecklistItem("quatro"),
            ),
        )

        assertEquals(2, progress.completed)
        assertEquals(4, progress.total)
        assertEquals(50, progress.percent)
        assertEquals("2 de 4 concluídos", progress.label)
    }

    @Test
    fun emptyChecklistHasZeroProgress() {
        val progress = ChecklistProgress.of(emptyList())

        assertEquals(0, progress.completed)
        assertEquals(0, progress.total)
        assertEquals(0, progress.percent)
        assertEquals("0 de 0 concluídos", progress.label)
    }
}