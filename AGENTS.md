# Repository Guidelines

## Project Overview

`notas` (package and namespace `com.will.noteharbor`) is a native, offline-first Android note-taking and checklist application. It provides encrypted local storage, multi-factor note protection (Biometric, PIN, Pattern, TOTP, Recovery Phrase), encrypted attachments, encrypted Google Drive cloud backup, and alarm-based reminder notifications.

- **Primary Language / Locale**: All user-facing copy MUST be in Brazilian Portuguese (`pt-BR`). Strings are hardcoded directly in UI code (`MainActivity.kt`).
- **Zero-Data Policy**: Clean installations MUST start empty (0 notes); sample or demo data in production builds is strictly prohibited.
- **Backend**: Serverless and account-free; synchronization operates directly against Google Drive's hidden `appDataFolder` using OAuth2.

---

## Architecture & Data Flow

The project follows a layered, reactive architecture with a persistence-first invariant:

```
[ MainActivity (Programmatic Views) ]
               │
      observes │ calls mutations
               ▼
       [ NotesViewModel ]
               │
   persist()   │ writes snapshot
   before LiveData
               ▼
       [ NoteRepository ]
               │
        load / replace snapshot
               ▼
[ Room over SQLCipher (notas.db v7) ]
```

### 1. Presentation Layer (Programmatic Views Only)
- **Zero XML Layouts & Zero Compose**: All UI screens, dialogs, cards, and custom components are constructed programmatically in Kotlin using AndroidX and Material Components. XML is reserved strictly for drawables, mipmaps, and resource values (`colors.xml`, `themes.xml`).
- **Screen State Machine**: `MainActivity.kt` manages navigation via `MainActivity.Screen` enum (`HOME`, `VIEWER`, `EDITOR`, `ARCHIVED`, `TRASH`, `SECURITY`, `WELCOME`, `LOCKED`). Screen transitions swap the root view using `setScreenContent(root)`.
- **Edge-to-Edge & Safe Area**: `SafeAreaPolicy.kt` provides pure calculation functions (`contentPadding`, `overlayBottomMargin`) resolving system bars and IME keyboard insets without hardcoded layout offsets.
- **Theming & Typography**: `UiPalette.kt` defines dynamic theme tokens (`isDark`, `canvas`, `text`, `accent`, `cardBackground`, `cardAccent`) resolving dark/light mode. `UiType.kt` defines typography scale constants.

### 2. Presentation Model (ViewModel)
- `NotesViewModel.kt` (`AndroidViewModel`) exposes state via `LiveData<List<Note>> notes` and `LiveData<Long> changes`.
- **Decrypted Session Cache**: In-memory `unlocked: MutableMap<String, UnlockedState>` retains decrypted plaintext and passwords during the active session. Cleared on note lock or ViewModel destruction (`onCleared`).
- **30-Day Trash Auto-Purge**: Notes in trash longer than 30 days (`TRASH_RETENTION_MS`) are automatically purged on ViewModel initialization and mutation events.
- **Persist-Before-Publish Invariant**: `NotesViewModel.persist(notes)` MUST write changes to the database before posting to `_notes` or `_changes` LiveData, ensuring observers (alarm reconciliation, cloud auto-sync) read committed data.

### 3. Domain Model
- Pure Kotlin data classes and helpers decoupled from the Android framework: `Note`, `NoteColor`, `NoteType`, `ChecklistItem`, `ReminderSchedule`, `AttachmentMetadata`.
- Pure utilities: `NoteQueries` (filter/search/sort), `ChecklistParser` (preserves checked state, strips markdown markers), `NoteDefaults` (fallback titles "Nota" / "Lista"), `NotePreview` (single-line card preview).

### 4. Persistence & Snapshot Pattern
- **Room over SQLCipher (v7)**: `NoteDatabase.kt` configures encrypted SQLite via SQLCipher with `TRUNCATE` journal mode (prevents WAL/SHM file locking during snapshot export) and `allowMainThreadQueries()`.
- **Snapshot Pattern**: Rather than granular per-entity updates, `NoteRepository` operates on `NoteStoreSnapshot(notes: List<Note>, tombstones: Map<String, Long>)`. `NoteDao.loadSnapshot()` reads the full graph and `NoteDatabase.replaceSnapshot()` wipes and replaces all rows inside an atomic transaction.
- **Database Migrations**: Incremental migrations handle reminder schema updates, trash support, encrypted content envelopes, and attachment metadata across database versions 1 through 7.

### 5. Security & Cryptography
- **`SecureSecretStore`**: Hardware-backed Android Keystore AES-256-GCM (`AndroidKeyStore`, alias `noteharbor.secret-store.v1`) stores local DB keys, device UUID, and recovery secrets.
- **`NoteEncryption`**: AES-256-GCM envelope encryption using Argon2id key derivation (`v: 1`, `alg: "AES-256-GCM"`, `kdf: "argon2id"`). Features a test seam (`NoteEncryption.keyDeriver`) allowing deterministic JVM unit testing without native JNI binaries.
- **`UnlockVault`**: Unified quick-unlock manager supporting `BIOMETRIC`, `PATTERN` (custom 3x3 `PatternLockView`), `NUMERIC_PIN`, `TOTP` (RFC 6238 HMAC-SHA1 + base32 single-use recovery codes), and `NONE`. Wraps individual 32-byte note keys (`wrapped.<noteId>`).
- **`AttachmentStore`**: Encrypts note attachment files on disk with AES-256-GCM using keys derived from the note secret (prepends `NOTAATT1` header).
- **Privacy Protections**: Sensitive `ByteArray` buffers are zeroed with `.fill(0)`. Screenshot capture is prevented on protected notes via `FLAG_SECURE` and `ScreenCaptureCallback`.

### 6. Cloud Backup & Conflict Resolution
- **Google Drive AppData**: Stores a standalone database file `notas-backup.db` in Google Drive's hidden `appDataFolder` (`DriveScopes.DRIVE_APPDATA`).
- **Closed File Snapshot**: `CloudBackupSynchronizer` exports an encrypted standalone SQLite database envelope (`NOTASDB` magic + salt + closed database bytes), avoiding SQLite WAL/SHM corruption.
- **Conflict Resolution (`SyncMerger`)**: Deterministic Last-Write-Wins (LWW) merge using `updatedAt` timestamps with `updatedBy` (device UUID) tie-breaking, preventing deleted note resurrection via `note_tombstones`.

### 7. Reminders & Scheduling
- **`ReminderSchedule`**: Domain recurrence rules (`ONCE`, `WEEKLY` ISO 1–7, `MONTHLY` 1–31 with month-end clamp, `DAILY`).
- **`ReminderScheduler`**: Reconciles active notes against persisted schedules via `AlarmManager.setAndAllowWhileIdle`. Uses versioned notification channel `lembretes_v3` (High Importance) and bounded 15-minute retries (max 4 attempts).
- **Broadcast Receivers**: `ReminderAlarmReceiver` delivers notifications using `goAsync()` on a single-thread executor; `ReminderRescheduleReceiver` reconstructs all alarms after device boot, app updates, or clock/timezone changes.

---

## Key Directories

```
app/src/main/java/com/will/noteharbor/
├── MainActivity.kt                  # Single-activity UI host & programmatic view router
├── ui/                              # Presentation & styling layer
│   ├── NotesViewModel.kt            # Presentation state, mutations, trash purge, session cache
│   ├── UiPalette.kt                 # Theme colors, dark/light token resolver, typography constants
│   ├── SafeAreaPolicy.kt            # Edge-to-edge window insets & IME calculations
│   └── PatternLockView.kt           # Custom 2D canvas 3x3 pattern unlock view
├── data/                            # Domain, persistence, security, and sync
│   ├── Note.kt                      # Domain models, queries, checklist parser, JSON codec
│   ├── NoteDatabase.kt              # Room DB v7, SQLCipher setup, migrations, snapshot DAO
│   ├── NoteRepository.kt            # Snapshot repository over SQLCipher
│   ├── NoteEncryption.kt            # AES-256-GCM envelope encryption & Argon2id key derivation
│   ├── SecureSecretStore.kt         # Android Keystore hardware-backed secret management
│   ├── UnlockVault.kt               # Multi-factor unlock vault (Biometric, PIN, Pattern, TOTP)
│   ├── SecurityRecovery.kt          # Security recovery payload serializers & restoration
│   ├── Totp.kt                      # RFC 6238 TOTP generator, clock skew compensation
│   ├── AttachmentStore.kt           # Encrypted note attachment storage (NOTAATT1)
│   ├── SyncMerger.kt                # Last-Write-Wins snapshot merge & tombstone tracker
│   ├── CloudBackup.kt               # Cloud backup synchronizer & envelope exporter
│   └── DriveBackup.kt               # Google Drive AppData REST client & storage
└── reminder/                        # AlarmManager & notification delivery
    ├── ReminderScheduler.kt         # Alarm scheduling, reconciliation & retry pipeline
    ├── ReminderAlarmReceiver.kt     # Alarm receiver, notification delivery, rescheduling
    └── ReminderRescheduleReceiver.kt# Rebuilds alarms after boot/time-change/app-update

app/src/test/java/com/will/noteharbor/
├── data/                            # Pure JVM unit tests for data, crypto, sync, reminders
└── ui/                              # Pure JVM unit tests for safe-area insets & policies

scripts/
└── build-and-share.sh               # Compiles debug APK and hosts it on HTTP server (port 8080)
```

---

## Development Commands

All tasks run through the Gradle wrapper (`./gradlew`):

```bash
# Build APKs
./gradlew assembleDebug              # Build debug-signed APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleRelease            # Build unsigned release APK (app/build/outputs/apk/release/app-release-unsigned.apk)

# Testing
./gradlew testDebugUnitTest          # Run all JVM unit tests
./gradlew testDebugUnitTest --tests "com.will.noteharbor.data.SyncMergeTest"                         # Run single test class
./gradlew testDebugUnitTest --tests "com.will.noteharbor.data.SyncMergeTest.keepsTheNewestVersion*"  # Run single test method
./gradlew testDebugUnitTest --tests "com.will.noteharbor.data.*"                                    # Run package tests

# Linting & Quality
./gradlew lintDebug                  # Run Android lint checks

# Build & Share APK locally
bash scripts/build-and-share.sh      # Compiles debug APK and shares on local HTTP server (port 8080)
```

---

## Code Conventions & Common Patterns

### 1. UI Construction & Language
- **No XML Layouts**: Never add `.xml` layout files. Build views programmatically using `LinearLayout`, `FrameLayout`, `MaterialButton`, `TextView`, etc.
- **Portuguese UI Strings**: Hardcode user-facing strings directly in `MainActivity.kt` in Brazilian Portuguese (`pt-BR`).
- **Color Selection**: Six note colors (`SUN`, `PEACH`, `MINT`, `LAVENDER`, `SKY`, `ROSE`). Indicate selected card colors using stroke thickness and elevation—never checkmark icons.

### 2. State & Mutation Invariants
- **Persist-Before-Publish**: Always write snapshot changes to `NoteRepository` before calling `_notes.value = ...` or `_changes.value = ...`.
- **Atomic Snapshot Updates**: Mutate immutable domain models by producing a new `List<Note>`, then save the complete snapshot via `NoteRepository.saveSnapshot()`.

### 3. Security & Memory Hygiene
- **Zero Sensitive Memory**: Always zero sensitive `ByteArray` buffers (passwords, keys, recovery codes) using `.fill(0)` in `finally` blocks.
- **Password Length Invariants**:
  - Note protection password: Minimum 4 characters (`MIN_PASSWORD_LENGTH = 4`).
  - Cloud backup & recovery passphrases: Minimum 8 characters (`MIN_BACKUP_PASSWORD_LENGTH = 8`).
- **JVM Test Seams**: When using `NoteEncryption` or Argon2id, use `NoteEncryption.keyDeriver` to provide deterministic SHA-256 derivation during tests.

### 4. Background Execution & Threading
- **Receivers**: Use `goAsync()` in broadcast receivers and delegate long-running work (DB queries, notification dispatch) to background executors.
- **Cloud Sync Guard**: Wrap background synchronization in a single-thread executor protected by `AtomicBoolean` to prevent concurrent sync executions.

---

## Important Files

| File Path | Purpose |
|---|---|
| `app/src/main/java/com/will/noteharbor/MainActivity.kt` | Single-activity UI host, programmatic view router, and dialog controller. |
| `app/src/main/java/com/will/noteharbor/ui/NotesViewModel.kt` | Presentation state, mutation actions, decrypted cache, 30-day trash auto-purge. |
| `app/src/main/java/com/will/noteharbor/data/Note.kt` | Domain models (`Note`, `NoteColor`, `ChecklistItem`), query helpers, and JSON codec. |
| `app/src/main/java/com/will/noteharbor/data/NoteDatabase.kt` | Room v7 database, SQLCipher integration, snapshot DAO, and schema migrations. |
| `app/src/main/java/com/will/noteharbor/data/NoteRepository.kt` | Coordinates atomic snapshot loading and persistence over SQLCipher. |
| `app/src/main/java/com/will/noteharbor/data/NoteEncryption.kt` | AES-256-GCM envelope encryption and Argon2id key derivation with test seams. |
| `app/src/main/java/com/will/noteharbor/data/UnlockVault.kt` | Multi-factor quick-unlock vault wrapping individual note keys. |
| `app/src/main/java/com/will/noteharbor/data/SyncMerger.kt` | Last-Write-Wins (LWW) conflict resolution and tombstone management. |
| `app/src/main/java/com/will/noteharbor/reminder/ReminderScheduler.kt` | Non-exact alarm scheduling and `lembretes_v3` notification channel management. |
| `app/build.gradle.kts` | App-level build configuration, SDK targets, Room kapt, and dependencies. |
| `CLAUDE.md` | Condensed developer guide and architecture summary. |
| `PROMPT_RECRIAR_APLICACAO.md` | Full functional specification (Portuguese). |

---

## Runtime & Tooling Preferences

- **JDK**: OpenJDK 17 (`java -version`).
- **Android SDK**:
  - `compileSdk`: 35 (Android 15)
  - `minSdk`: 26 (Android 8.0 Oreo)
  - `targetSdk`: 35
  - `buildToolsVersion`: 35.0.0
  - SDK Path: `/root/android-sdk` (`sdk.dir` in `local.properties` or `ANDROID_SDK_ROOT`).
- **Build System**: Gradle 8.9 Wrapper (`gradle-8.9-bin.zip`), Android Gradle Plugin (AGP) 8.7.3, Kotlin 2.0.21 (`kapt`).
- **Libraries**: AndroidX Core / AppCompat / Activity / Lifecycle KTX, Material Components 1.12.0, Room 2.8.4, SQLCipher Android 4.17.0, Argon2kt 1.6.0, Google Play Services Auth 20.7.0, Google Drive API v3.
- **Constraints**: No Jetpack Compose, no XML screen layouts, no Robolectric, no Mockito/MockK.

---

## Testing & QA

- **Framework**: JUnit 4.13.2 (`junit:junit:4.13.2`), `org.junit.Assert.*`, `org.json:json:20240303`.
- **Pure JVM Unit Tests**: 100% of tests live under `app/src/test/java/com/will/noteharbor/` and run on the host JVM without an emulator or device.
- **Framework Stubs**: Configured with `testOptions.unitTests.isReturnDefaultValues = true` in `app/build.gradle.kts` so Android SDK methods return default values instead of throwing stub errors.
- **No Mocking Frameworks**: Tests use deterministic state fakes and dependency injection hooks (such as `NoteEncryption.keyDeriver`).
- **Coverage Invariants**:
  - All domain logic, sync merges, reminder schedules, checklist parsing, and safe area calculations MUST have unit test coverage.
  - Test classes mirror source package structure (`com.will.noteharbor.data.*Test`, `com.will.noteharbor.ui.*Test`).
- **Visual & UI Verification**: Since there are no instrumented UI tests or Compose previews, visual verification requires installing the debug APK on an emulator/device or using `bash scripts/build-and-share.sh`.
