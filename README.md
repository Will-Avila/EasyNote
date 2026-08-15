# EasyNote

EasyNote is an original Android notes MVP inspired by the public feature set of classic color-coded notepad apps. It is not affiliated with or a distribution of ColorNote.

## Included

- Color-coded text notes and checklists.
- Dark theme with darker card variants and light text.
- Compact cards with one-line previews and a dedicated full-note viewer.
- The home screen shows checklist progress without interactive item controls; checklist items are editable only in the full viewer.
- The full viewer includes checklist progress plus copy, share and delete actions, while pinning remains a home-screen action.
- Dedicated create/edit screen with back and save actions, with the editor prioritized for text input.
- Custom themed delete-confirmation modal instead of the default Android confirmation dialog.
- High-contrast menu containing the persistent theme switch and **Backup na nuvem**.
- Edge-to-edge system-bar and keyboard inset handling.
- Password-protected notes with hidden content and eye visibility toggle.
- Encrypted local persistence with Room + SQLCipher.
- One-time migration from the former JSON/SharedPreferences store to the encrypted database.
- Encrypted cloud backup as a closed SQLCipher database file selected through Android Storage Access Framework.
- Compatible with providers exposed by the Android file picker, including Google Drive, OneDrive and Dropbox.
- Separate backup password, minimum 8 characters, used to derive the remote database key with PBKDF2-HMAC-SHA256.
- Backup password key material is protected locally with Android Keystore; the plaintext password is never persisted.
- Per-note merge using stable IDs, modification timestamps and deterministic device IDs.
- Deletion tombstones prevent deleted notes from reappearing during offline synchronization.
- Automatic synchronization on every local modification when enabled, plus an optional synchronization when the app starts; automatic runs are silent, while manual synchronization reports its result.
- Recurring reminders attached to notes: weekly (Monday–Sunday), monthly (day 1–31) or one-time reminders, with a local time and Android notifications. Legacy daily schedules remain readable for compatibility but are no longer offered in the editor.
- Reminder schedules are stored with the encrypted note, included in cloud snapshots and migrated safely from database version 1 through version 3.
- Android 13+ notification permission is requested when a reminder is enabled; reminders can be disabled without deleting the note.
- Reminder notifications open the note; protected notes use a generic notification and still require the note password.
- Reminders are rebuilt after boot, time changes, timezone changes and app updates. Monthly day 29–31 falls back to the last available day in shorter months.
- Reminder delivery uses `AlarmManager.setAndAllowWhileIdle`, so delivery may have small system-controlled timing variance rather than exact-alarm guarantees.
- Reminder delivery keeps a one-time reminder persisted until the notification is published, retries temporary database/permission/channel failures with a bounded retry alarm, and uses a versioned high-importance channel (`lembretes_v3`) so an older low-importance or blocked channel cannot silently suppress the alert. Existing reminders also trigger the Android 13+ permission request when the app returns to the foreground, including reminders restored from storage or cloud backup.
- When Android has blocked notification permission or the reminder channel, the reminder summary in the editor opens the app's notification settings directly.
- Note cards show the configured reminder schedule in the footer when a reminder is active.
- Search across titles, body text and checklist items.
- Pinned, copy, share and delete actions.
- Default titles: `Nota` for text notes and `Lista` for checklists.
- Offline-first interface; cloud access is optional.

## Cloud backup flow

1. Open the main menu and choose **Backup na nuvem**.
2. Choose **Criar novo arquivo** and select a provider in the Android file picker, or choose **Usar arquivo existente** on another device.
3. Create or enter the separate backup password. This password is required on every new device and cannot be recovered by the app.
4. The app creates a consistent encrypted database snapshot, merges it by note, and writes the closed file to the selected provider.
5. On another device, select the same file and enter the same password.

The remote file is not JSON and is not a live copy of the local Room database. The app creates a consistent snapshot before writing it, avoiding SQLite WAL/SHM corruption. Losing the backup password makes the remote file unrecoverable.

Android backup and device-transfer rules exclude the local database and secret preferences so the protected material is not copied as an unencrypted legacy backup. The provider's own availability and file permissions still apply.

## Build

Requirements: JDK 17 and Android SDK platform 35/build-tools 35.0.0.

```bash
export ANDROID_SDK_ROOT=/root/android-sdk
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug assembleRelease
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` and is signed with the local debug key. The release build is generated as `app/build/outputs/apk/release/app-release-unsigned.apk` until a production signing key is configured outside the repository.

Visual device validation requires an Android device or emulator connected through ADB; JVM tests, lint and APK inspection remain available without one.
