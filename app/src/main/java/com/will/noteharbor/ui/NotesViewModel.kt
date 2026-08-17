package com.will.noteharbor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.will.noteharbor.data.AttachmentMetadata
import com.will.noteharbor.data.AttachmentStore
import com.will.noteharbor.data.ChecklistParser
import com.will.noteharbor.data.Note
import com.will.noteharbor.data.NoteColor
import com.will.noteharbor.data.NoteDefaults
import com.will.noteharbor.data.NoteEncryption
import com.will.noteharbor.data.NoteRepository
import com.will.noteharbor.data.NoteSecurity
import com.will.noteharbor.data.NoteType
import com.will.noteharbor.data.ReminderSchedule
import com.will.noteharbor.data.SecurityRecovery
import com.will.noteharbor.data.UnlockVault
import java.util.UUID

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NoteRepository(application)
    val repositoryForSync: NoteRepository
        get() = repository
    private val _notes = MutableLiveData<List<Note>>()
    private val _changes = MutableLiveData<Long>()

    init {
        val loaded = repository.load()
        val purged = purgeExpiredTrash(loaded)
        if (purged.size != loaded.size) repository.save(purged)
        _notes.value = purged
    }

    val notes: LiveData<List<Note>> = _notes
    val changes: LiveData<Long> = _changes

    private data class UnlockedState(
        val password: String,
        val content: NoteEncryption.DecryptedContent,
    )

    // Cache de notas desbloqueadas na sessão: guarda a senha (para re-criptografar no save)
    // e o conteúdo em texto puro (para materializar o viewer/editor). Limpo em lockNote/lockAll.
    private val unlocked = mutableMapOf<String, UnlockedState>()

    override fun onCleared() {
        unlocked.clear()
        repository.close()
        super.onCleared()
    }

    fun isUnlocked(noteId: String): Boolean = unlocked.containsKey(noteId)

    fun unlockedContent(noteId: String): NoteEncryption.DecryptedContent? = unlocked[noteId]?.content

    /** Segredo da nota protegida na sessão (usado para decifrar anexos); null se não estiver desbloqueada. */
    fun attachmentSecret(noteId: String): String? = unlocked[noteId]?.password

    fun lockNote(noteId: String) {
        unlocked.remove(noteId)
    }

    fun lockAll() {
        unlocked.clear()
    }

    /**
     * Desbloqueia uma nota protegida. Para notas já cifradas, deriva a chave e decripta; para
     * notas legadas (texto puro + passwordHash), verifica o hash e re-criptografa preguiçosamente.
     */
    fun unlock(noteId: String, password: String): Boolean {
        val note = _notes.value.orEmpty().firstOrNull { it.id == noteId } ?: return false
        return when {
            note.encryptedContent.isNotBlank() -> runCatching {
                val content = NoteEncryption.decrypt(note.encryptedContent, password)
                unlocked[noteId] = UnlockedState(password, content)
            }.isSuccess
            note.passwordHash.isNotBlank() && NoteSecurity.matches(password, note.passwordHash) -> {
                val content = NoteEncryption.DecryptedContent(note.body, note.items)
                unlocked[noteId] = UnlockedState(password, content)
                migrateLegacyNote(note, password, content)
                true
            }
            else -> false
        }
    }

    private fun migrateLegacyNote(note: Note, password: String, content: NoteEncryption.DecryptedContent) {
        val migrated = note.copy(
            body = "",
            items = emptyList(),
            passwordHash = "",
            encryptedContent = NoteEncryption.encrypt(content.body, content.items, password),
        )
        persist(_notes.value.orEmpty().map { if (it.id == note.id) migrated else it })
    }

    /**
     * Migra uma nota legada (texto puro + passwordHash da versão antiga) para o modelo atual:
     * "só o método". Re-cifra o conteúdo com [secret] (chave aleatória) e descarta a senha antiga.
     * O [secret] é embrulhado pelo método escolhido em seguida, na UI. Retorna false se a nota já
     * não for legada.
     */
    fun migrateLegacyToMethod(noteId: String, secret: String): Boolean {
        val note = _notes.value.orEmpty().firstOrNull { it.id == noteId } ?: return false
        if (note.passwordHash.isBlank() || note.encryptedContent.isNotBlank()) return false
        val content = NoteEncryption.DecryptedContent(note.body, note.items)
        val migrated = note.copy(
            body = "",
            items = emptyList(),
            passwordHash = "",
            encryptedContent = NoteEncryption.encrypt(content.body, content.items, secret),
        )
        unlocked[noteId] = UnlockedState(secret, content)
        SecurityRecovery.storeNoteSecret(getApplication(), noteId, secret)
        persist(_notes.value.orEmpty().map { if (it.id == note.id) migrated else it })
        return true
    }

    fun upsert(
        existing: Note?,
        title: String,
        text: String,
        type: NoteType,
        color: NoteColor,
        protect: Boolean,
        secret: String,
        reminder: ReminderSchedule?,
        attachments: List<AttachmentMetadata> = emptyList(),
        idOverride: String? = null,
    ): Note {
        val current = _notes.value.orEmpty()
        val previousItems = if (existing != null && existing.locked) {
            unlocked[existing.id]?.content?.items ?: existing.items
        } else {
            existing?.items.orEmpty()
        }
        val plainBody = if (type == NoteType.TEXT) text.trim() else ""
        val plainItems = if (type == NoteType.CHECKLIST) {
            ChecklistParser.parse(text, previousItems)
        } else {
            emptyList()
        }

        // O segredo é a senha da nota (legada) ou a chave aleatória gerada ao proteger pelo método
        // global. Em branco ao re-salvar uma nota já protegida: reusa a chave guardada em cache.
        val effectiveSecret = when {
            !protect -> ""
            secret.isNotBlank() -> secret
            else -> unlocked[existing?.id]?.password.orEmpty()
        }
        require(!protect || effectiveSecret.isNotBlank()) { "Método de proteção ausente" }

        val encryptedContent = if (protect) {
            NoteEncryption.encrypt(plainBody, plainItems, effectiveSecret)
        } else {
            ""
        }

        // Reconcilia os arquivos dos anexos com o novo estado de proteção: apaga os removidos e
        // cifra/decifra os mantidos conforme a nota passou a estar protegida ou não.
        reconcileAttachmentFiles(existing, attachments, protect, effectiveSecret)

        val note = Note(
            id = idOverride ?: existing?.id ?: UUID.randomUUID().toString(),
            title = NoteDefaults.titleFor(title, type),
            body = if (protect) "" else plainBody,
            type = type,
            color = color,
            pinned = existing?.pinned ?: false,
            archived = existing?.archived ?: false,
            locked = protect,
            passwordHash = "",
            encryptedContent = encryptedContent,
            updatedAt = System.currentTimeMillis(),
            updatedBy = repository.deviceId,
            items = if (protect) emptyList() else plainItems,
            reminder = reminder,
            attachments = attachments,
        )

        if (protect) {
            unlocked[note.id] = UnlockedState(effectiveSecret, NoteEncryption.DecryptedContent(plainBody, plainItems))
        } else {
            unlocked.remove(note.id)
        }

        // Ao remover a proteção, o embrulho de desbloqueio rápido não é mais necessário.
        if (existing != null && !protect && existing.locked) {
            UnlockVault.removeWrapped(getApplication(), existing.id)
        }

        // Mantém o segredo da nota recuperável (cifrado pela chave do aparelho) para que uma
        // restauração consiga re-embrulhar notas protegidas por biometria — cuja chave do Keystore
        // não viaja no envelope. Ao desproteger, o segredo deixa de ser necessário.
        if (protect && effectiveSecret.isNotBlank()) {
            SecurityRecovery.storeNoteSecret(getApplication(), note.id, effectiveSecret)
        } else if (!protect) {
            SecurityRecovery.removeNoteSecret(getApplication(), note.id)
        }

        val updated = if (existing == null) listOf(note) + current else current.map { if (it.id == note.id) note else it }
        persist(updated)
        return note
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
            if (note.locked) {
                val state = unlocked[noteId] ?: return@map note
                val newItems = state.content.items.mapIndexed { index, item ->
                    if (index == itemIndex) item.copy(completed = checked) else item
                }
                val newState = state.copy(content = state.content.copy(items = newItems))
                unlocked[noteId] = newState
                note.copy(
                    body = "",
                    items = emptyList(),
                    passwordHash = "",
                    encryptedContent = NoteEncryption.encrypt(newState.content.body, newItems, newState.password),
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = repository.deviceId,
                )
            } else {
                val updatedItems = note.items.mapIndexed { index, item ->
                    if (index == itemIndex) item.copy(completed = checked) else item
                }
                note.copy(
                    items = updatedItems,
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = repository.deviceId,
                )
            }
        })
    }

    fun delete(noteId: String) {
        deleteAttachmentFiles(_notes.value.orEmpty().firstOrNull { it.id == noteId })
        UnlockVault.removeWrapped(getApplication(), noteId)
        persist(_notes.value.orEmpty().filterNot { it.id == noteId })
    }

    fun trash(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                trashed = true,
                trashedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun restore(noteId: String) {
        persist(_notes.value.orEmpty().map { note ->
            if (note.id == noteId) note.copy(
                trashed = false,
                trashedAt = null,
                updatedAt = System.currentTimeMillis(),
                updatedBy = repository.deviceId,
            ) else note
        })
    }

    fun emptyTrash() {
        val current = _notes.value.orEmpty()
        current.filter { it.trashed }.forEach { note ->
            deleteAttachmentFiles(note)
            UnlockVault.removeWrapped(getApplication(), note.id)
        }
        persist(current.filterNot { it.trashed })
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
        val loaded = repository.load()
        val purged = purgeExpiredTrash(loaded)
        if (purged.size != loaded.size) repository.save(purged)
        _notes.postValue(purged)
    }

    private fun persist(notes: List<Note>) {
        val purged = purgeExpiredTrash(notes)
        repository.save(purged)
        // Persist first. The observer reconciles AlarmManager from this value;
        // publishing it before the database write could schedule an alarm for a
        // note that does not exist if the process dies or the write fails.
        _notes.value = purged
        _changes.value = System.currentTimeMillis()
    }

    /** Período de retenção da lixeira: exclui de vez depois de 30 dias. */
    private fun purgeExpiredTrash(notes: List<Note>): List<Note> {
        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
        val expired = notes.filter { note ->
            val trashedAt = note.trashedAt
            note.trashed && trashedAt != null && trashedAt <= cutoff
        }
        if (expired.isEmpty()) return notes
        expired.forEach { note ->
            deleteAttachmentFiles(note)
            UnlockVault.removeWrapped(getApplication(), note.id)
            SecurityRecovery.removeNoteSecret(getApplication(), note.id)
        }
        return notes.filterNot { expired.contains(it) }
    }

    /**
     * Reconciliar os arquivos dos anexos com o estado de proteção da nota:
     * - anexos removidos da lista → arquivo apagado;
     * - nota protegida → arquivos cifrados com o segredo;
     * - nota desprotegida → arquivos decifrados de volta a texto puro (usa o segredo antigo do cache).
     */
    private fun reconcileAttachmentFiles(
        existing: Note?,
        attachments: List<AttachmentMetadata>,
        protect: Boolean,
        newSecret: String,
    ) {
        val context: android.content.Context = getApplication()
        (existing?.attachments.orEmpty() - attachments).forEach { AttachmentStore.delete(context, it.id) }
        if (protect) {
            attachments.forEach { AttachmentStore.encryptFile(context, it.id, newSecret) }
        } else if (existing?.locked == true) {
            val oldSecret = unlocked[existing.id]?.password
            attachments.forEach { AttachmentStore.decryptFile(context, it.id, oldSecret) }
        }
    }

    private fun deleteAttachmentFiles(note: Note?) {
        if (note == null) return
        val context: android.content.Context = getApplication()
        note.attachments.forEach { AttachmentStore.delete(context, it.id) }
    }

    private companion object {
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30 dias
    }
}
