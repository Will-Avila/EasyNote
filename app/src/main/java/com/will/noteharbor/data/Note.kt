package com.will.noteharbor.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** A note can be free-form text or a small actionable checklist. */
data class ChecklistItem(
    val text: String,
    val completed: Boolean = false,
)

enum class NoteType {
    TEXT,
    CHECKLIST,
}

object NoteDefaults {
    fun titleFor(title: String, type: NoteType): String = title.trim().ifBlank {
        if (type == NoteType.CHECKLIST) "Lista" else "Nota"
    }
}

enum class NoteColor(
    val backgroundHex: String,
    val accentHex: String,
    val label: String,
    val darkBackgroundHex: String,
    val darkAccentHex: String,
) {
    SUN("#FFF1B8", "#D89216", "Sol", "#403417", "#F4C95D"),
    PEACH("#FFD9C7", "#D26947", "Pêssego", "#482D23", "#F4A07A"),
    MINT("#CDEFE5", "#2B8A78", "Menta", "#173C35", "#8CE0CC"),
    LAVENDER("#E3DDF8", "#6557B5", "Lavanda", "#302A50", "#BFB5FF"),
    SKY("#D7ECFA", "#32799E", "Céu", "#1B3846", "#8CD0F4"),
    ROSE("#F8D5DE", "#B34C69", "Rosa", "#452834", "#F0A4B9"),
}

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String = "",
    val type: NoteType = NoteType.TEXT,
    val color: NoteColor = NoteColor.SUN,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val locked: Boolean = false,
    val passwordHash: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String = "local",
    val items: List<ChecklistItem> = emptyList(),
    val reminder: ReminderSchedule? = null,
) {
    val isCompleted: Boolean
        get() = type == NoteType.CHECKLIST && items.isNotEmpty() && items.all { it.completed }

    fun searchableText(): String = buildString {
        append(title)
        append(' ')
        append(body)
        items.forEach {
            append(' ')
            append(it.text)
        }
    }
}

enum class NoteFilter {
    ALL,
    PINNED,
    CHECKLIST,
    ARCHIVED,
    TRASHED,
}

object ChecklistParser {
    fun parse(text: String, previousItems: List<ChecklistItem> = emptyList()): List<ChecklistItem> {
        return text.lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val normalized = rawLine
                    .trim()
                    .removePrefix("- ")
                    .removePrefix("[x] ")
                    .removePrefix("[X] ")
                    .removePrefix("[ ] ")
                    .trim()

                if (normalized.isBlank()) {
                    null
                } else {
                    ChecklistItem(
                        text = normalized,
                        completed = previousItems.getOrNull(index)?.completed == true,
                    )
                }
            }
            .toList()
    }
}

object NoteQueries {
    fun visible(
        notes: List<Note>,
        query: String,
        filter: NoteFilter,
    ): List<Note> {
        val normalizedQuery = query.trim().lowercase()
        return notes.asSequence()
            .filter { note ->
                when (filter) {
                    NoteFilter.ALL -> !note.archived && !note.trashed
                    NoteFilter.PINNED -> !note.archived && !note.trashed && note.pinned
                    NoteFilter.CHECKLIST -> !note.archived && !note.trashed && note.type == NoteType.CHECKLIST
                    NoteFilter.ARCHIVED -> note.archived && !note.trashed
                    NoteFilter.TRASHED -> note.trashed
                }
            }
            .filter { note ->
                normalizedQuery.isBlank() || note.searchableText().lowercase().contains(normalizedQuery)
            }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
            .toList()
    }
}

object NoteJsonCodec {
    private const val ID = "id"
    private const val TITLE = "title"
    private const val BODY = "body"
    private const val TYPE = "type"
    private const val COLOR = "color"
    private const val PINNED = "pinned"
    private const val ARCHIVED = "archived"
    private const val TRASHED = "trashed"
    private const val LOCKED = "locked"
    private const val PASSWORD_HASH = "passwordHash"
    private const val UPDATED_AT = "updatedAt"
    private const val UPDATED_BY = "updatedBy"
    private const val ITEMS = "items"
    private const val ITEM_TEXT = "text"
    private const val ITEM_COMPLETED = "completed"
    private const val REMINDER = "reminder"
    private const val REMINDER_RECURRENCE = "recurrence"
    private const val REMINDER_HOUR = "hour"
    private const val REMINDER_MINUTE = "minute"
    private const val REMINDER_DAYS_OF_WEEK = "daysOfWeek"
    private const val REMINDER_DAY_OF_MONTH = "dayOfMonth"
    private const val REMINDER_DATE = "date"

    fun encode(notes: List<Note>): String {
        val array = JSONArray()
        notes.forEach { note ->
            val json = JSONObject()
            json.put(ID, note.id)
            json.put(TITLE, note.title)
            json.put(BODY, note.body)
            json.put(TYPE, note.type.name)
            json.put(COLOR, note.color.name)
            json.put(PINNED, note.pinned)
            json.put(ARCHIVED, note.archived)
            json.put(TRASHED, note.trashed)
            json.put(LOCKED, note.locked)
            json.put(PASSWORD_HASH, note.passwordHash)
            json.put(UPDATED_AT, note.updatedAt)
            json.put(UPDATED_BY, note.updatedBy)

            val items = JSONArray()
            note.items.forEach { item ->
                val itemJson = JSONObject()
                itemJson.put(ITEM_TEXT, item.text)
                itemJson.put(ITEM_COMPLETED, item.completed)
                items.put(itemJson)
            }
            json.put(ITEMS, items)
            note.reminder?.let { reminder ->
                val reminderJson = JSONObject()
                reminderJson.put(REMINDER_RECURRENCE, reminder.recurrence.name)
                reminderJson.put(REMINDER_HOUR, reminder.hour)
                reminderJson.put(REMINDER_MINUTE, reminder.minute)
                if (reminder.daysOfWeek.isNotEmpty()) {
                    val days = JSONArray()
                    reminder.daysOfWeek.sorted().forEach(days::put)
                    reminderJson.put(REMINDER_DAYS_OF_WEEK, days)
                }
                reminder.dayOfMonth?.let { reminderJson.put(REMINDER_DAY_OF_MONTH, it) }
                reminder.date?.let { reminderJson.put(REMINDER_DATE, it.toString()) }
                json.put(REMINDER, reminderJson)
            }
            array.put(json)
        }
        return array.toString()
    }

    fun decode(raw: String): List<Note> {
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            val itemsJson = json.optJSONArray(ITEMS) ?: JSONArray()
            val items = List(itemsJson.length()) { itemIndex ->
                val item = itemsJson.getJSONObject(itemIndex)
                ChecklistItem(
                    text = item.optString(ITEM_TEXT),
                    completed = item.optBoolean(ITEM_COMPLETED),
                )
            }
            val reminder = json.optJSONObject(REMINDER)?.let { reminderJson ->
                runCatching {
                    val recurrence = ReminderRecurrence.valueOf(reminderJson.getString(REMINDER_RECURRENCE))
                    val legacyDay = reminderJson.optInt("dayOfWeek").takeIf { reminderJson.has("dayOfWeek") }
                    val daysOfWeek = reminderJson.optJSONArray(REMINDER_DAYS_OF_WEEK)
                        ?.let { days ->
                            buildSet {
                                repeat(days.length()) { index -> add(days.getInt(index)) }
                            }
                        }
                        .orEmpty()
                        .ifEmpty { legacyDay?.let(::setOf).orEmpty() }
                    ReminderSchedule(
                        recurrence = recurrence,
                        hour = reminderJson.getInt(REMINDER_HOUR),
                        minute = reminderJson.getInt(REMINDER_MINUTE),
                        daysOfWeek = daysOfWeek,
                        dayOfMonth = reminderJson.optInt(REMINDER_DAY_OF_MONTH).takeIf { reminderJson.has(REMINDER_DAY_OF_MONTH) },
                        date = reminderJson.optString(REMINDER_DATE).takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                    )
                }.getOrNull()
            }
            Note(
                id = json.optString(ID, UUID.randomUUID().toString()),
                title = json.optString(TITLE),
                body = json.optString(BODY),
                type = runCatching { NoteType.valueOf(json.optString(TYPE)) }.getOrDefault(NoteType.TEXT),
                color = runCatching { NoteColor.valueOf(json.optString(COLOR)) }.getOrDefault(NoteColor.SUN),
                pinned = json.optBoolean(PINNED),
                archived = json.optBoolean(ARCHIVED),
                trashed = json.optBoolean(TRASHED),
                locked = json.optBoolean(LOCKED),
                passwordHash = json.optString(PASSWORD_HASH),
                updatedAt = json.optLong(UPDATED_AT, System.currentTimeMillis()),
                updatedBy = json.optString(UPDATED_BY, "local"),
                items = items,
                reminder = reminder,
            )
        }
    }
}
