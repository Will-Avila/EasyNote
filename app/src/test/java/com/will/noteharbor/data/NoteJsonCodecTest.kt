package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteJsonCodecTest {
    @Test
    fun lockedNotesRoundTripWithoutPlaintextPassword() {
        val storedHash = NoteSecurity.hash("segredo-123")
        val original = Note(
            id = "locked-1",
            title = "Anotação privada",
            body = "Conteúdo reservado",
            locked = true,
            passwordHash = storedHash,
        )

        val json = NoteJsonCodec.encode(listOf(original))
        val restored = NoteJsonCodec.decode(json).single()

        assertFalse(json.contains("segredo-123"))
        assertTrue(restored.locked)
        assertEquals(storedHash, restored.passwordHash)
        assertTrue(NoteSecurity.matches("segredo-123", restored.passwordHash))
    }

    @Test
    fun reminderSchedulesRoundTripAndLegacyNotesRemainWithoutReminder() {
        val original = listOf(
            Note(
                id = "daily",
                title = "Diário",
                reminder = ReminderSchedule.daily(8, 15),
            ),
            Note(
                id = "weekly",
                title = "Semanal",
                reminder = ReminderSchedule.weekly(9, 30, dayOfWeek = 2),
            ),
            Note(
                id = "monthly",
                title = "Mensal",
                reminder = ReminderSchedule.monthly(10, 45, dayOfMonth = 31),
            ),
        )

        val restored = NoteJsonCodec.decode(NoteJsonCodec.encode(original))

        assertEquals(original, restored)
        assertEquals(null, NoteJsonCodec.decode("[{\"id\":\"legacy\",\"title\":\"Antiga\"}]").single().reminder)
    }

    @Test
    fun invalidReminderIsIgnoredWithoutDroppingTheNote() {
        val raw = "[{\"id\":\"broken\",\"title\":\"Nota\",\"reminder\":{\"recurrence\":\"MONTHLY\",\"hour\":25,\"minute\":0,\"dayOfMonth\":31}}]"

        val restored = NoteJsonCodec.decode(raw).single()

        assertEquals("broken", restored.id)
        assertEquals(null, restored.reminder)
    }

    @Test
    fun encryptedContentRoundTripsAndLegacyPayloadDecodesToEmpty() {
        val original = Note(
            id = "enc-1",
            title = "Protegida",
            locked = true,
            encryptedContent = "dmVyc2lvbmFkbw==",
        )

        val restored = NoteJsonCodec.decode(NoteJsonCodec.encode(listOf(original))).single()

        assertEquals("dmVyc2lvbmFkbw==", restored.encryptedContent)
        assertEquals("", NoteJsonCodec.decode("[{\"id\":\"legacy\",\"title\":\"Antiga\"}]").single().encryptedContent)
    }

    @Test
    fun trashedAtRoundTripsAndLegacyPayloadDecodesToNull() {
        val original = Note(id = "t-1", title = "Lixeira", trashed = true, trashedAt = 1700000000000L)

        val restored = NoteJsonCodec.decode(NoteJsonCodec.encode(listOf(original))).single()

        assertEquals(1700000000000L, restored.trashedAt)
        assertEquals(null, NoteJsonCodec.decode("[{\"id\":\"legacy\",\"title\":\"Antiga\"}]").single().trashedAt)
    }

    @Test
    fun attachmentsRoundTripAndLegacyPayloadDecodesToEmptyList() {
        val original = Note(
            id = "att-1",
            title = "Com anexos",
            attachments = listOf(
                AttachmentMetadata("uuid-1", "foto.jpg", "image/jpeg", 2048L),
                AttachmentMetadata("uuid-2", "contrato.pdf", "application/pdf", 10485760L),
            ),
        )

        val restored = NoteJsonCodec.decode(NoteJsonCodec.encode(listOf(original))).single()

        assertEquals(original, restored)
        assertEquals(
            emptyList<AttachmentMetadata>(),
            NoteJsonCodec.decode("[{\"id\":\"legacy\",\"title\":\"Antiga\"}]").single().attachments,
        )
    }
}
