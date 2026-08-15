package com.will.noteharbor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.will.noteharbor.data.ChecklistParser
import com.will.noteharbor.data.Note
import com.will.noteharbor.data.NoteColor
import com.will.noteharbor.data.NoteDefaults
import com.will.noteharbor.data.NoteRepository
import com.will.noteharbor.data.NoteSecurity
import com.will.noteharbor.data.NoteType
import com.will.noteharbor.data.ReminderSchedule
import java.util.UUID

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NoteRepository(application)
    val repositoryForSync: NoteRepository
        get() = repository
    private val _notes = MutableLiveData(repository.load())
    private val _changes = MutableLiveData<Long>()

    val notes: LiveData<List<Note>> = _notes
    val changes: LiveData<Long> = _changes

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }

    fun upsert(
        existing: Note?,
        title: String,
        text: String,
        type: NoteType,
        color: NoteColor,
        protect: Boolean,
        password: String,
        reminder: ReminderSchedule?,
    ) {
        val current = _notes.value.orEmpty()
        val items = if (type == NoteType.CHECKLIST) {
            ChecklistParser.parse(text, existing?.items.orEmpty())
        } else {
            emptyList()
        }
        val passwordHash = when {
            !protect -> ""
            password.isNotBlank() -> NoteSecurity.hash(password)
            else -> existing?.passwordHash.orEmpty()
        }
        val note = Note(
            id = existing?.id ?: UUID.randomUUID().toString(),
            title = NoteDefaults.titleFor(title, type),
            body = if (type == NoteType.TEXT) text.trim() else "",
            type = type,
            color = color,
            pinned = existing?.pinned ?: false,
            archived = existing?.archived ?: false,
            locked = protect && passwordHash.isNotBlank(),
            passwordHash = passwordHash,
            updatedAt = System.currentTimeMillis(),
            updatedBy = repository.deviceId,
            items = items,
            reminder = reminder,
        )
        val updated = if (existing == null) listOf(note) + current else current.map { if (it.id == note.id) note else it }
        persist(updated)
    }

    fun togglePinned(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                pinned = !note.pinned,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun toggleItem(noteId: String, itemIndex: Int, checked: Boolean) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id != noteId) return@map note
            val updatedItems = note.items.mapIndexed { index, item ->
                if (index == itemIndex) item.copy(completed = checked) else item
            }
            note.copy(
                items = updatedItems,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            )
        })
    }

    fun delete(noteId: String) {
        persist(_notes.value.orEmpty().filterNot { it.id == noteId })
    }

    fun trash(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                trashed = true,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun restore(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                trashed = false,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun emptyTrash() {
        persist(_notes.value.orEmpty().filterNot { it.trashed })
    }

    fun archive(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                archived = true,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun unarchive(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                archived = false,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun reloadFromRepository() {
        _notes.postValue(repository.load())
    }

    private fun persist(notes: List<Note>) {
        repository.save(notes)
        // Persist first. The observer reconciles AlarmManager from this value;
        // publishing it before the database write could schedule an alarm for a
        // note that does not exist if the process dies or the write fails.
        _notes.value = notes
        _changes.value = System.currentTimeMillis()
    }
}
