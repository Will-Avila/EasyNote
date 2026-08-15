package com.will.noteharbor.data

/** Defines what is restored when the encrypted store is initialized or migrated. */
object NoteMigrationPolicy {
    fun notesForInitialStore(decodedLegacy: List<Note>?): List<Note> = decodedLegacy.orEmpty()
}
