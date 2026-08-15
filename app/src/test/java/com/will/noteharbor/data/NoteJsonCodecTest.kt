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
}
