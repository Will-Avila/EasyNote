package com.will.noteharbor.data

/** A serializable state used to merge local and cloud note changes. */
data class NoteStoreSnapshot(
    val notes: List<Note>,
    val tombstones: Map<String, Long>,
)

object SyncMerger {
    fun merge(local: NoteStoreSnapshot, remote: NoteStoreSnapshot): NoteStoreSnapshot {
        val ids = (local.notes.asSequence().map { it.id } + remote.notes.asSequence().map { it.id } +
            local.tombstones.keys.asSequence() + remote.tombstones.keys.asSequence()).toSet()
        val mergedNotes = ids.mapNotNull { id ->
            val localNote = local.notes.firstOrNull { it.id == id }
            val remoteNote = remote.notes.firstOrNull { it.id == id }
            val localDeletedAt = local.tombstones[id] ?: Long.MIN_VALUE
            val remoteDeletedAt = remote.tombstones[id] ?: Long.MIN_VALUE
            val deletedAt = maxOf(localDeletedAt, remoteDeletedAt)
            val winner = listOfNotNull(localNote, remoteNote)
                .maxWithOrNull(compareBy<Note> { it.updatedAt }.thenBy { it.updatedBy })
            if (winner != null && winner.updatedAt > deletedAt) winner else null
        }
        val tombstones = ids.mapNotNull { id ->
            val deletedAt = maxOf(
                local.tombstones[id] ?: Long.MIN_VALUE,
                remote.tombstones[id] ?: Long.MIN_VALUE,
            )
            val latestNoteAt = maxOf(
                local.notes.firstOrNull { it.id == id }?.updatedAt ?: Long.MIN_VALUE,
                remote.notes.firstOrNull { it.id == id }?.updatedAt ?: Long.MIN_VALUE,
            )
            if (deletedAt != Long.MIN_VALUE && deletedAt >= latestNoteAt) id to deletedAt else null
        }.toMap()
        return NoteStoreSnapshot(
            notes = mergedNotes.sortedByDescending { it.updatedAt },
            tombstones = tombstones,
        )
    }
}
