package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    @Test
    fun keepsTheNewestVersionOfEachNote() {
        val local = NoteStoreSnapshot(
            notes = listOf(Note(id = "same", title = "local", updatedAt = 100, updatedBy = "device-a")),
            tombstones = emptyMap(),
        )
        val remote = NoteStoreSnapshot(
            notes = listOf(Note(id = "same", title = "remota", updatedAt = 200, updatedBy = "device-b")),
            tombstones = emptyMap(),
        )

        val merged = SyncMerger.merge(local, remote)

        assertEquals("remota", merged.notes.single().title)
        assertTrue(merged.tombstones.isEmpty())
    }

    @Test
    fun deletionWinsWhenItIsNewerAndDoesNotResurrectOnNextMerge() {
        val local = NoteStoreSnapshot(
            notes = emptyList(),
            tombstones = mapOf("deleted" to 300),
        )
        val remote = NoteStoreSnapshot(
            notes = listOf(Note(id = "deleted", title = "antiga", updatedAt = 200, updatedBy = "remote")),
            tombstones = emptyMap(),
        )

        val merged = SyncMerger.merge(local, remote)
        val repeated = SyncMerger.merge(merged, remote)

        assertTrue(merged.notes.isEmpty())
        assertEquals(300L, merged.tombstones["deleted"])
        assertTrue(repeated.notes.isEmpty())
        assertEquals(300L, repeated.tombstones["deleted"])
    }

    @Test
    fun aLaterEditCanReplaceAnOlderDeletion() {
        val local = NoteStoreSnapshot(
            notes = emptyList(),
            tombstones = mapOf("restored" to 100),
        )
        val remote = NoteStoreSnapshot(
            notes = listOf(Note(id = "restored", title = "nova", updatedAt = 200, updatedBy = "remote")),
            tombstones = emptyMap(),
        )

        val merged = SyncMerger.merge(local, remote)

        assertEquals("nova", merged.notes.single().title)
        assertTrue(merged.tombstones.isEmpty())
    }

    @Test
    fun sameTimestampUsesModifierIdDeterministically() {
        val local = NoteStoreSnapshot(
            notes = listOf(Note(id = "same", title = "A", updatedAt = 100, updatedBy = "device-a")),
            tombstones = emptyMap(),
        )
        val remote = NoteStoreSnapshot(
            notes = listOf(Note(id = "same", title = "B", updatedAt = 100, updatedBy = "device-b")),
            tombstones = emptyMap(),
        )

        val merged = SyncMerger.merge(local, remote)

        assertEquals("B", merged.notes.single().title)
    }
}
