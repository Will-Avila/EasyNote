# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`notas` (package/namespace `com.will.noteharbor`) is a native Android notes app — offline-first, color-coded text notes and checklists with encrypted local storage, an encrypted cloud-backup file, and reminder notifications. All user-facing copy is in Portuguese (pt-BR). There is no server, account, or remote API.

The full functional specification lives in `PROMPT_RECRIAR_APLICACAO.md` (Portuguese); `README.md` is a condensed English summary. Neither is authoritative over the code — treat them as the intended feature set.

## Build, test, lint

Requirements: JDK 17, Android SDK platform 35 / build-tools 35.0.0, Gradle wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21. The SDK path is in `local.properties` (gitignored) as `sdk.dir=/root/android-sdk`; set `ANDROID_SDK_ROOT=/root/android-sdk` if the build can't find it.

```bash
./gradlew testDebugUnitTest          # all JVM unit tests
./gradlew testDebugUnitTest --tests "com.will.noteharbor.data.SyncMergeTest"   # single test class
./gradlew lintDebug
./gradlew assembleDebug assembleRelease
```

Output APKs: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed) and `app/build/outputs/apk/release/app-release-unsigned.apk`.

There are no instrumented tests and no Compose previews — all tests are plain JVM unit tests under `app/src/test/java/com/will/noteharbor/`, one `*Test.kt` per subject, mirroring the `data`/`ui` package layout. `unitTests.isReturnDefaultValues = true` is set so Android framework calls return defaults in JVM tests. Visual validation requires a device/emulator over ADB; build/lint/test do not.

## Architecture

**UI is programmatic Views, not Compose.** There are no screen layouts in XML — only drawables and `values/`. `MainActivity` (a single ~2100-line file) builds every screen in code from `LinearLayout`/`FrameLayout`/`MaterialButton` etc. and renders three screens tracked by `enum Screen { HOME, VIEWER, EDITOR }`, swapped via `setScreenContent(root)`. Add UI here (or in small helpers), not in XML.

**ViewModel layer** (`ui/NotesViewModel.kt`): an `AndroidViewModel` exposing `LiveData<List<Note>> notes` and `LiveData<Long> changes`. The activity observes `notes` to re-render and `changes` to fire a silent auto cloud-sync. All mutations go through `ViewModel` methods that build a new immutable `Note` list and call `repository.save(...)`.

**Data layer** (`data/`): pure-Kotlin domain model plus persistence.

- `Note` is the domain model (title/body/type/color/pinned/archived/locked/passwordHash/updatedAt/updatedBy/items/reminder). `NoteColor`, `NoteType`, `ChecklistItem`, `ReminderSchedule` are enums/data classes here. Pure helpers like `NoteQueries` (filter/sort/search), `ChecklistParser`, `NoteDefaults`, `NotePreview` are JVM-testable without Android.
- Persistence is **Room over SQLCipher**, version 3, four entities (`notes`, `checklist_items`, `note_tombstones`, `database_metadata`) with two reminder migrations (1→2, 2→3) in `NoteDatabase.kt`. The database is opened with a passphrase via `EncryptedDatabaseFactory.open` (loads the native `sqlcipher` library, `TRUNCATE` journal mode, `allowMainThreadQueries`).
- **Snapshot pattern, not per-entity DAOs:** `NoteRepository` loads/writes the whole store at once. `NoteDao.loadSnapshot()` returns a `NoteStoreSnapshot` (notes + tombstones); `replaceSnapshot(...)` deletes-and-rewrites all rows inside a transaction. Reminders are flattened onto the note row (recurrence/hour/minute/days-of-week/day-of-month/date columns) and reassembled by `List<NoteEntity>.toNotes`.
- `NoteJsonCodec` is only for the legacy SharedPreferences JSON format, read once by `NoteRepository.migrateLegacyDataIfNeeded()`.

**Security** (all in `data/`):
- `SecureSecretStore` — AES-GCM through Android Keystore (alias `noteharbor.secret-store.v1`); protects the local DB passphrase, device id, and cloud passphrase.
- `NoteSecurity` — salted SHA-256 hash for per-note passwords (`salt$digest`, base64).
- `SyncKeyDerivation` — PBKDF2-HMAC-SHA256 (210k iterations) derives the cloud backup DB key from a ≥8-char backup password.

**Cloud backup** (`data/CloudBackup.kt`): `CloudBackupSynchronizer` reads/writes a single closed file through the Storage Access Framework (`ContentResolver`, persistable URI permission). The file is a `CloudBackupEnvelope` — magic `NOTASDB`, version, salt, then an entire encrypted SQLCipher database snapshot written to a temp file (never the live Room DB, avoiding WAL/SHM corruption). `CloudBackupSettingsStore` persists the URI and the Keystore-protected passphrase.

**Merge** (`data/SyncMerger.kt`): `merge(local, remote)` is a per-note last-write-wins (compare `updatedAt`, tie-break on `updatedBy` = device id) with deletion tombstones that prevent resurrecting deleted notes. Used both for cloud sync and for local delete bookkeeping.

**Reminders** (`reminder/`): `ReminderSchedule` (recurrence DAILY/WEEKLY/MONTHLY/ONCE with `nextOccurrenceOrNull`) is the source of truth; the editor only mirrors it. `ReminderScheduler.reconcile(context, notes)` diffs the current note list against a persisted set of scheduled ids and sets `AlarmManager.setAndAllowWhileIdle` alarms (never exact alarms). `ReminderAlarmReceiver` delivers the notification and reschedules/re-retries; `ReminderRescheduleReceiver` rebuilds alarms after boot/time/update. `ReminderNotification` uses a versioned high-importance channel (`lembretes_v3`).

## Conventions and gotchas

- **UI copy is Portuguese.** New strings are mostly hardcoded in `MainActivity` rather than `strings.xml`.
- **Passwords:** note-lock password minimum is 4 (`MIN_PASSWORD_LENGTH`); cloud backup password minimum is 8.
- **Notification channel is versioned** (`lembretes_v3`). Android forbids raising a channel's importance after creation, so if a delivery change requires a higher-importance or unblocked channel, bump the version and the id.
- **Don't downgrade a reminder's one-time schedule prematurely:** a one-time reminder stays persisted (retried via a 15-min bounded retry alarm, max 4 attempts) until its notification publishes successfully.
- **Persist before publishing LiveData:** `NotesViewModel.persist` writes the DB first, then sets `_notes`/`_changes`, because observers reconcile alarms and trigger cloud sync off those values.
- Sensitive `ByteArray` passphrases are zeroed with `.fill(0)` after use throughout the security and cloud code — preserve that pattern when touching it.
- The repo is not currently under git (`git` is not initialized); `local.properties`, `build/`, `.gradle/` are gitignored when it is.
