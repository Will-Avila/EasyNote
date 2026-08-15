package com.will.noteharbor

import android.Manifest
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.will.noteharbor.data.ChecklistProgress
import com.will.noteharbor.data.CloudBackupSettings
import com.will.noteharbor.data.CloudBackupSettingsStore
import com.will.noteharbor.data.CloudBackupSynchronizer
import com.will.noteharbor.data.CloudSyncPhase
import com.will.noteharbor.data.CloudSyncState
import com.will.noteharbor.data.DriveServiceFactory
import com.will.noteharbor.data.Note
import com.will.noteharbor.data.NoteColor
import com.will.noteharbor.data.NoteFilter
import com.will.noteharbor.data.NoteQueries
import com.will.noteharbor.data.NotePreview
import com.will.noteharbor.data.NoteSecurity
import com.will.noteharbor.data.NoteType
import com.will.noteharbor.data.ReminderRecurrence
import com.will.noteharbor.data.ReminderDisplay
import com.will.noteharbor.data.ReminderEditorDefaults
import com.will.noteharbor.data.ReminderEditorSelection
import com.will.noteharbor.data.ReminderPermissionPolicy
import com.will.noteharbor.data.ReminderSchedule
import com.will.noteharbor.reminder.ReminderNotification
import com.will.noteharbor.reminder.ReminderScheduler
import com.will.noteharbor.ui.NotesViewModel
import com.will.noteharbor.ui.SafeAreaPolicy
import com.will.noteharbor.ui.UiPalette
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val TAG = "NoteHarbor"
private const val DRIVE_SCOPE_REQUEST_CODE = 1001

class MainActivity : AppCompatActivity() {
    private enum class Screen {
        HOME,
        VIEWER,
        EDITOR,
        ARCHIVED,
        TRASH,
        WELCOME,
    }

    private val viewModel: NotesViewModel by viewModels()
    private lateinit var palette: UiPalette
    private lateinit var notesContainer: LinearLayout
    private lateinit var archivedContainer: LinearLayout
    private lateinit var trashContainer: LinearLayout
    private lateinit var countLabel: TextView
    private val filterChips = mutableMapOf<NoteFilter, TextView>()
    private var activeFilter = NoteFilter.ALL
    private var searchQuery = ""
    private var currentScreen = Screen.HOME
    private var currentViewerNoteId: String? = null
    private var unlockedViewerNoteId: String? = null
    private var currentScreenRoot: View? = null
    private var editorReturnScreen = Screen.HOME
    private var editorReturnNoteId: String? = null
    private val cloudSettingsStore by lazy { CloudBackupSettingsStore(applicationContext) }
    private val cloudSynchronizer by lazy { CloudBackupSynchronizer(applicationContext, viewModel.repositoryForSync) }
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private val cloudSyncRunning = AtomicBoolean(false)
    private var cloudSyncState = CloudSyncState(CloudSyncPhase.DISCONNECTED)
    private var cloudSyncPending = false
    private var signedInEmail: String? = null
    private var reminderNotificationPermissionRequestInFlight = false
    private var reminderNotificationPermissionPrompted = false
    private var onboardingAwaitingPermission = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val completingOnboarding = onboardingAwaitingPermission
        onboardingAwaitingPermission = false
        reminderNotificationPermissionRequestInFlight = false
        reminderNotificationPermissionPrompted = true
        if (!granted) {
            if (!completingOnboarding) {
                Toast.makeText(this, "As notificações estão bloqueadas. Ative-as nas configurações para receber os lembretes.", Toast.LENGTH_LONG).show()
            }
        } else {
            viewModel.notes.value.orEmpty().let { ReminderScheduler.reconcile(applicationContext, it) }
        }
        if (completingOnboarding) {
            completeOnboarding()
        }
    }

    private val googleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, options)
    }
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        handleGoogleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedNightMode()
        super.onCreate(savedInstanceState)
        palette = UiPalette.from(this)
        configureSystemBars()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (isOnboardingComplete()) {
            currentScreen = Screen.HOME
            setScreenContent(buildContent())
        } else {
            currentScreen = Screen.WELCOME
            setScreenContent(buildWelcomeScreen())
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.HOME -> finish()
                    Screen.VIEWER -> showHome()
                    Screen.EDITOR -> navigateBackFromEditor()
                    Screen.ARCHIVED -> showHome()
                    Screen.TRASH -> showHome()
                    Screen.WELCOME -> finish()
                }
            }
        })
        viewModel.notes.observe(this) { notes ->
            ReminderScheduler.reconcile(applicationContext, notes)
            maybeRequestReminderNotificationPermission(notes)
            when (currentScreen) {
                Screen.HOME -> renderNotes(notes)
                Screen.VIEWER -> {
                    val note = currentViewerNoteId?.let { id -> notes.firstOrNull { it.id == id } }
                    if (note == null) showHome() else renderViewer(note)
                }
                Screen.EDITOR -> Unit
                Screen.ARCHIVED -> renderArchivedNotes(notes)
                Screen.TRASH -> renderTrashNotes(notes)
                Screen.WELCOME -> Unit
            }
        }
        viewModel.changes.observe(this) {
            requestAutomaticCloudSync()
        }
        window.decorView.post {
            if (cloudSettingsStore.load().automatic) synchronizeConfiguredCloud(showToast = false)
            handleReminderIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::palette.isInitialized) {
            // The alarm receiver may have cleared a one-time reminder while the
            // Activity was stopped. Reload persisted state before reconciling so
            // stale in-memory notes cannot recreate an already-delivered alarm.
            viewModel.reloadFromRepository()
            window.decorView.post {
                maybeRequestReminderNotificationPermission(viewModel.notes.value.orEmpty())
            }
        }
    }

    /**
     * Existing reminders can come from a previous install, migration or cloud
     * restore. In that case the editor is never opened and Android 13+ would
     * otherwise leave POST_NOTIFICATIONS unrequested forever.
     */
    private fun maybeRequestReminderNotificationPermission(notes: List<Note>) {
        val shouldRequest = ReminderPermissionPolicy.shouldRequest(
            apiLevel = Build.VERSION.SDK_INT,
            hasActiveReminder = notes.any { it.reminder != null },
            permissionGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            requestInFlight = reminderNotificationPermissionRequestInFlight,
            alreadyPromptedInActivity = reminderNotificationPermissionPrompted,
            activityResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            activityFinishing = isFinishing,
        )
        if (!shouldRequest) {
            return
        }
        reminderNotificationPermissionPrompted = true
        reminderNotificationPermissionRequestInFlight = true
        window.decorView.post {
            if (isFinishing ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                reminderNotificationPermissionRequestInFlight = false
                return@post
            }
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleReminderIntent(intent: Intent?) {
        val noteId = intent?.getStringExtra(ReminderNotification.EXTRA_NOTE_ID) ?: return
        intent.removeExtra(ReminderNotification.EXTRA_NOTE_ID)
        viewModel.notes.value.orEmpty().firstOrNull { it.id == noteId }?.let { openNote(it) }
    }

    override fun onDestroy() {
        cloudExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            currentScreenRoot?.post {
                currentScreenRoot?.let { root -> requestScreenInsets(root) }
            }
        }
    }

    private fun requestScreenInsets(root: View) {
        ViewCompat.requestApplyInsets(root)
        ViewCompat.getRootWindowInsets(root)?.let { ViewCompat.dispatchApplyWindowInsets(root, it) }
    }

    private fun setScreenContent(root: View) {
        currentScreenRoot = root
        setContentView(root)
        val requestInsets = {
            requestScreenInsets(root)
        }
        if (root.isAttachedToWindow) {
            requestInsets()
        } else {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    requestInsets()
                    view.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
        root.post { requestInsets() }
    }

    private fun applySavedNightMode() {
        val savedMode = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
            .getInt(NIGHT_MODE_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != savedMode) {
            AppCompatDelegate.setDefaultNightMode(savedMode)
        }
    }

    private fun toggleNightMode() {
        val nextMode = if (palette.isDark) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putInt(NIGHT_MODE_KEY, nextMode)
            .apply()
        AppCompatDelegate.setDefaultNightMode(nextMode)
    }

    private fun showMainMenu(anchor: View) {
        lateinit var popup: PopupWindow
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(palette.dialogSurface, 20)
        }
        fun item(text: String, iconRes: Int, tint: Int, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), 0, dp(16), 0)
                background = selectableItemBackgroundRounded()
                contentDescription = text
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
                addView(ImageView(this@MainActivity).apply {
                    contentDescription = null
                    setImageResource(iconRes)
                    setColorFilter(tint)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                addView(label(text, 15, palette.text, false), LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }
        fun divider(): View = View(this).apply { setBackgroundColor(palette.inputBorder) }
        val rowParams = LinearLayout.LayoutParams(MATCH, dp(52))
        val dividerParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }

        menu.addView(item(
            if (palette.isDark) "Tema claro" else "Tema escuro",
            if (palette.isDark) R.drawable.ic_mode_light else R.drawable.ic_mode_night,
            palette.text,
        ) { toggleNightMode() }, rowParams)
        menu.addView(divider(), dividerParams)
        menu.addView(item("Arquivadas", R.drawable.ic_archive, palette.text) { showArchived() }, rowParams)
        menu.addView(item("Lixeira", R.drawable.ic_delete, palette.text) { showTrash() }, rowParams)
        menu.addView(divider(), dividerParams)
        menu.addView(item("Backup na nuvem", R.drawable.ic_cloud_backup, palette.accent) { showCloudBackupDialog() }, rowParams)

        popup = PopupWindow(menu, dp(256), WRAP, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
        }
        anchor.post { popup.showAsDropDown(anchor, -dp(208), dp(8)) }
    }

    private fun showNoteMenu(anchor: View, note: Note, onNoteRemoved: (() -> Unit)? = null) {
        lateinit var popup: PopupWindow
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(palette.dialogSurface, 20)
        }
        fun item(text: String, iconRes: Int, tint: Int, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), 0, dp(16), 0)
                background = selectableItemBackgroundRounded()
                contentDescription = text
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
                addView(ImageView(this@MainActivity).apply {
                    contentDescription = null
                    setImageResource(iconRes)
                    setColorFilter(tint)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                addView(label(text, 15, palette.text, false), LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }
        fun divider(): View = View(this).apply { setBackgroundColor(palette.inputBorder) }
        val rowParams = LinearLayout.LayoutParams(MATCH, dp(52))
        val dividerParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        val destructiveTint = if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845")

        if (note.trashed) {
            menu.addView(item("Restaurar", R.drawable.ic_archive, palette.text) {
                viewModel.restore(note.id)
                onNoteRemoved?.invoke()
            }, rowParams)
            menu.addView(divider(), dividerParams)
            menu.addView(item("Excluir permanentemente", R.drawable.ic_delete, destructiveTint) {
                confirmPermanentDelete(note, onNoteRemoved)
            }, rowParams)
        } else {
            menu.addView(item(
                if (note.pinned) "Desafixar" else "Fixar",
                if (note.pinned) R.drawable.ic_pin_cut else R.drawable.ic_pin,
                palette.text,
            ) { viewModel.togglePinned(note.id) }, rowParams)
            menu.addView(item("Copiar", R.drawable.ic_copy, palette.text) { copyNote(note) }, rowParams)
            menu.addView(item("Compartilhar", R.drawable.ic_share, palette.text) { shareNote(note) }, rowParams)
            menu.addView(item(
                if (note.archived) "Restaurar" else "Arquivar",
                R.drawable.ic_archive,
                palette.text,
            ) {
                if (note.archived) viewModel.unarchive(note.id) else viewModel.archive(note.id)
                onNoteRemoved?.invoke()
            }, rowParams)
            menu.addView(divider(), dividerParams)
            menu.addView(item("Excluir", R.drawable.ic_delete, destructiveTint) {
                confirmTrash(note, onNoteRemoved)
            }, rowParams)
        }

        popup = PopupWindow(menu, dp(256), WRAP, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
        }
        anchor.post {
            popup.showAsDropDown(anchor, anchor.width - popup.width, dp(4))
        }
    }

    private fun showCloudBackupDialog() {
        val settings = cloudSettingsStore.load()
        lateinit var dialog: AlertDialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), 0)
            background = rounded(palette.dialogSurface, 24)
        }
        val status = cloudStatusText(settings)
        panel.addView(label(status.first, 16, palette.dialogText, true))
        panel.addView(label(status.second, 14, palette.secondaryText, false).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
        if (isDriveConfigured()) {
            val automatic = CheckBox(this).apply {
                text = "Sincronizar a cada modificação"
                isChecked = settings.automatic
                setTextColor(palette.dialogText)
                buttonTintList = checkboxTint()
                setOnCheckedChangeListener { _, checked ->
                    cloudSettingsStore.setAutomatic(checked)
                    if (checked) requestAutomaticCloudSync()
                }
            }
            panel.addView(automatic, LinearLayout.LayoutParams(MATCH, dp(52)))
            addCloudDialogButton(panel, "Sincronizar agora") {
                dialog.dismiss()
                synchronizeConfiguredCloud(showToast = true)
            }
            addCloudDialogButton(panel, "Desconectar", destructive = true) {
                dialog.dismiss()
                confirmCloudDisconnect()
            }
        } else {
            addCloudDialogButton(panel, "Entrar com o Google") {
                dialog.dismiss()
                signInToGoogle()
            }
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Backup na nuvem")
            .setView(panel)
            .setNegativeButton("Fechar", null)
            .create()
        dialog.setOnShowListener { styleUnlockDialog(dialog) }
        dialog.show()
    }

    private fun cloudStatusText(settings: CloudBackupSettings): Pair<String, String> {
        return when (cloudSyncState.phase) {
            CloudSyncPhase.SYNCING -> "Sincronizando" to "O backup está sendo atualizado no Google Drive."
            CloudSyncPhase.SYNCED -> "Backup sincronizado" to "${cloudSyncState.noteCount ?: 0} notas salvas no Google Drive."
            CloudSyncPhase.ERROR -> "Não foi possível sincronizar" to (cloudSyncState.message ?: "Tente novamente.")
            CloudSyncPhase.DISCONNECTED -> if (isDriveConfigured()) {
                val last = settings.lastSyncAt?.let(::formatDate) ?: "ainda não realizado"
                "Conectado ao Google Drive" to "Última sincronização: $last"
            } else {
                "Nenhuma conta conectada" to "Entre com o Google para salvar suas notas no Drive."
            }
        }
    }

    private fun addCloudDialogButton(
        panel: LinearLayout,
        text: String,
        destructive: Boolean = false,
        action: () -> Unit,
    ) {
        panel.addView(MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (destructive) Color.WHITE else palette.dialogButton)
            backgroundTintList = ColorStateList.valueOf(
                if (destructive) {
                    if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845")
                } else {
                    palette.inputSurface
                },
            )
            strokeColor = ColorStateList.valueOf(
                if (destructive) Color.TRANSPARENT else palette.inputBorder,
            )
            strokeWidth = if (destructive) 0 else dp(1)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                action()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(8) })
    }

    private fun signInToGoogle() {
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun handleGoogleSignInResult(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            signedInEmail = account.email
            if (GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
                connectDrive(account)
            } else {
                requestDriveScope(account)
            }
        } catch (_: ApiException) {
            cloudSyncState = CloudSyncState(CloudSyncPhase.ERROR, message = "Não foi possível entrar no Google.")
            Toast.makeText(this, "Não foi possível entrar no Google.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestDriveScope(account: GoogleSignInAccount) {
        GoogleSignIn.requestPermissions(
            this,
            DRIVE_SCOPE_REQUEST_CODE,
            account,
            Scope(DriveScopes.DRIVE_APPDATA),
        )
    }

    private fun handleDriveScopeResult(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            connectDrive(account)
        } catch (_: ApiException) {
            cloudSyncState = CloudSyncState(CloudSyncPhase.ERROR, message = "Não foi possível autorizar o Google Drive.")
            Toast.makeText(this, "Não foi possível autorizar o Google Drive.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated no Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DRIVE_SCOPE_REQUEST_CODE) {
            handleDriveScopeResult(data)
        }
    }

    private fun isDriveConfigured(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))
    }

    private fun connectDrive(account: GoogleSignInAccount) {
        cloudSyncPending = false
        val email = account.email ?: signedInEmail
        if (email.isNullOrBlank()) {
            cloudSyncState = CloudSyncState(CloudSyncPhase.ERROR, message = "Conta Google sem e-mail associado.")
            Toast.makeText(this, "Conta Google sem e-mail associado.", Toast.LENGTH_LONG).show()
            return
        }
        if (!cloudSyncRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "Já existe uma sincronização em andamento.", Toast.LENGTH_SHORT).show()
            return
        }
        cloudSyncState = CloudSyncState(CloudSyncPhase.SYNCING)
        cloudExecutor.execute {
            try {
                val drive = DriveServiceFactory.create(applicationContext, email)
                val result = cloudSynchronizer.synchronize(drive)
                cloudSettingsStore.markSynced()
                runOnUiThread {
                    cloudSyncState = CloudSyncState(CloudSyncPhase.SYNCED, result.noteCount)
                    viewModel.reloadFromRepository()
                    Toast.makeText(this, "Backup conectado e sincronizado.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Falha ao sincronizar (connect)", error)
                runOnUiThread {
                    cloudSyncState = CloudSyncState(CloudSyncPhase.ERROR, message = cloudErrorMessage(error))
                    Toast.makeText(this, cloudSyncState.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                cloudSyncRunning.set(false)
                runOnUiThread {
                    if (cloudSyncPending) {
                        cloudSyncPending = false
                        requestAutomaticCloudSync()
                    }
                }
            }
        }
    }

    private fun requestAutomaticCloudSync() {
        val settings = cloudSettingsStore.load()
        if (!settings.automatic || !isDriveConfigured()) return
        if (cloudSyncRunning.get()) {
            cloudSyncPending = true
            return
        }
        synchronizeConfiguredCloud(showToast = false)
    }

    private fun synchronizeConfiguredCloud(showToast: Boolean) {
        cloudSyncPending = false
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (!isDriveConfigured() || account == null) {
            if (showToast) {
                Toast.makeText(this, "Entre com sua conta Google para ativar o backup.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val email = account.email ?: signedInEmail
        if (email.isNullOrBlank()) {
            if (showToast) Toast.makeText(this, "Conta Google sem e-mail associado.", Toast.LENGTH_LONG).show()
            return
        }
        if (!cloudSyncRunning.compareAndSet(false, true)) {
            cloudSyncPending = true
            if (showToast) Toast.makeText(this, "Já existe uma sincronização em andamento.", Toast.LENGTH_SHORT).show()
            return
        }
        cloudSyncState = CloudSyncState(CloudSyncPhase.SYNCING)
        cloudExecutor.execute {
            try {
                val drive = DriveServiceFactory.create(applicationContext, email)
                val result = cloudSynchronizer.synchronize(drive)
                cloudSettingsStore.markSynced()
                runOnUiThread {
                    cloudSyncState = CloudSyncState(CloudSyncPhase.SYNCED, result.noteCount)
                    viewModel.reloadFromRepository()
                    if (showToast) Toast.makeText(this, "Backup sincronizado.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Falha ao sincronizar", error)
                runOnUiThread {
                    cloudSyncState = CloudSyncState(CloudSyncPhase.ERROR, message = cloudErrorMessage(error))
                    if (showToast) Toast.makeText(this, cloudSyncState.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                cloudSyncRunning.set(false)
                runOnUiThread {
                    if (cloudSyncPending) {
                        cloudSyncPending = false
                        requestAutomaticCloudSync()
                    }
                }
            }
        }
    }

    private fun confirmCloudDisconnect() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label("Desconectar backup?", 20, palette.dialogText, true))
        panel.addView(label(
            "As notas continuarão neste aparelho. O backup no Google Drive não será apagado.",
            15,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(12), 0, dp(20)) })
        val buttons = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        buttons.addView(MaterialButton(this).apply {
            text = "Cancelar"
            isAllCaps = false
            setTextColor(palette.secondaryText)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(8) })
        buttons.addView(MaterialButton(this).apply {
            text = "Desconectar"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845"))
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                googleSignInClient.signOut()
                cloudSettingsStore.clear()
                cloudSyncState = CloudSyncState(CloudSyncPhase.DISCONNECTED)
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Backup desconectado.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, dp(44)))
        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.show()
    }

    private fun cloudErrorMessage(error: Exception): String = when (error) {
        is ApiException -> "Não foi possível acessar o Google Drive (código ${error.statusCode}). Entre novamente."
        is SecurityException -> "Permissão de conta ausente. Atualize o app e tente novamente."
        else -> "Falha ao acessar o Google Drive: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = palette.canvas
        window.navigationBarColor = palette.canvas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    private fun buildContent(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(palette.canvas) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("EasyNote", 26, palette.text, true).apply {
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(ImageButton(this).apply {
            contentDescription = "Abrir menu"
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = outlined(palette.inputSurface, palette.inputBorder, 16)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showMainMenu(this) }
        })
        content.addView(header)
        content.addView(label("Crie, organize e proteja suas ideias.", 14, palette.secondaryText, false).apply {
            setPadding(0, dp(8), 0, dp(18))
        })

        val search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            textSize = 15f
            setSingleLine(true)
            setTextColor(palette.text)
            setHintTextColor(palette.mutedText)
            setPadding(dp(16), 0, dp(16), 0)
            background = inputBackground()
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    renderNotes(viewModel.notes.value.orEmpty())
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search, LinearLayout.LayoutParams(MATCH, dp(54)).apply { bottomMargin = dp(16) })

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addFilterChip(filters, NoteFilter.ALL, "Todas")
        addFilterChip(filters, NoteFilter.PINNED, "Fixadas")
        addFilterChip(filters, NoteFilter.CHECKLIST, "Listas")
        content.addView(filters, LinearLayout.LayoutParams(MATCH, dp(42)).apply { bottomMargin = dp(12) })

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countLabel = label("", 13, palette.secondaryText, true)
        summary.addView(countLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        content.addView(summary, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        notesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(96))
        }
        scroll.addView(notesContainer, ViewGroup.LayoutParams(MATCH, WRAP))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val fab = FloatingActionButton(this).apply {
            contentDescription = getString(R.string.add_note)
            setImageResource(R.drawable.ic_add)
            setColorFilter(palette.fabIcon)
            scaleType = ImageView.ScaleType.CENTER
            setMaxImageSize(dp(32))
            setPadding(0, 0, 0, 0)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            setOnClickListener { openEditor(null) }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(20), dp(22))
        })

        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            applyContentInsets(content, insets, 20, 22)
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            fab.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = SafeAreaPolicy.overlayBottomMargin(
                    baseMargin = dp(24),
                    systemBottom = systemInsets.bottom,
                    imeBottom = imeInsets.bottom,
                )
            }
            insets
        }
        refreshFilterStyles()
        return frame
    }

    private fun addFilterChip(parent: LinearLayout, filter: NoteFilter, text: String) {
        val chip = label(text, 13, palette.unselectedChipText, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                activeFilter = filter
                refreshFilterStyles()
                renderNotes(viewModel.notes.value.orEmpty())
            }
        }
        filterChips[filter] = chip
        parent.addView(chip, LinearLayout.LayoutParams(WRAP, MATCH).apply {
            marginEnd = dp(8)
        })
    }

    private fun refreshFilterStyles() {
        filterChips.forEach { (filter, chip) ->
            val selected = filter == activeFilter
            chip.setTextColor(if (selected) palette.selectedChipText else palette.unselectedChipText)
            chip.background = rounded(if (selected) palette.selectedChip else palette.unselectedChip, 18)
            chip.setPadding(dp(16), 0, dp(16), 0)
        }
    }

    private fun renderNotes(notes: List<Note>) {
        if (!::notesContainer.isInitialized) return
        val visible = NoteQueries.visible(notes, searchQuery, activeFilter)
        countLabel.text = when (visible.size) {
            0 -> "Nenhuma nota encontrada"
            1 -> "1 nota visível"
            else -> "${visible.size} notas visíveis"
        }
        notesContainer.removeAllViews()
        if (visible.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(64), dp(28), dp(64))
            }
            empty.addView(label("Tudo limpo por aqui", 20, palette.text, true))
            empty.addView(label("Crie uma nota para começar a tirar ideias da cabeça.", 14, palette.secondaryText, false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            notesContainer.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            visible.forEach { note -> notesContainer.addView(buildNoteCard(note)) }
        }
    }

    private fun buildNoteCard(note: Note): View {
        val card = FrameLayout(this).apply {
            background = rounded(UiPalette.cardBackground(note.color, palette.isDark), 22)
            clipToOutline = true
            elevation = dp(2).toFloat()
            setOnClickListener { openNote(note) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        card.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(label(
            if (note.type == NoteType.CHECKLIST) "CHECKLIST" else "NOTA",
            10,
            UiPalette.cardAccent(note.color, palette.isDark),
            true,
        ))
        titleBlock.addView(label(note.title, 19, palette.cardText, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        topRow.addView(titleBlock, LinearLayout.LayoutParams(0, WRAP, 1f))
        lateinit var menuButton: ImageButton
        menuButton = actionIcon(R.drawable.ic_more_vert, "Abrir menu da nota") {
            showNoteMenu(menuButton, note)
        }
        topRow.addView(menuButton)
        content.addView(topRow)

        if (note.locked) {
            content.addView(label("CONTEÚDO PROTEGIDO", 11, UiPalette.cardAccent(note.color, palette.isDark), true).apply {
                setPadding(0, dp(12), 0, 0)
            })
            content.addView(label("Toque para digitar a senha e visualizar.", 14, palette.cardBody, false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        } else if (note.type == NoteType.CHECKLIST) {
            val progress = ChecklistProgress.of(note.items)
            content.addView(label(progress.label, 12, UiPalette.cardAccent(note.color, palette.isDark), true).apply {
                setPadding(0, dp(12), 0, 0)
            })
        } else if (note.body.isNotBlank()) {
            content.addView(label(NotePreview.text(note.body), 15, palette.cardBody, false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(14), 0, 0)
            })
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val footerMeta = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        footerMeta.addView(label(formatDate(note.updatedAt), 11, palette.cardFooter, false), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (note.isCompleted && !note.locked) {
            footerMeta.addView(label("PRONTO", 10, UiPalette.cardAccent(note.color, palette.isDark), true))
        }
        footer.addView(footerMeta, LinearLayout.LayoutParams(MATCH, WRAP))
        note.reminder?.let { reminder ->
            footer.addView(label(ReminderDisplay.cardFooter(reminder), 11, palette.cardFooter, true).apply {
                setPadding(0, dp(8), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        content.addView(footer)
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
    }


    private fun openNote(note: Note) {
        withUnlockedContent(note) { openViewer(note) }
    }

    private fun withUnlockedContent(note: Note, onUnlocked: () -> Unit) {
        if (note.locked && unlockedViewerNoteId != note.id) {
            requestUnlock(note, onUnlocked)
        } else {
            onUnlocked()
        }
    }

    private fun requestUnlock(note: Note, onUnlocked: () -> Unit) {
        requestNotePassword(
            note = note,
            message = "Digite a senha para visualizar o conteúdo.",
            confirmLabel = "Visualizar",
        ) {
            unlockedViewerNoteId = note.id
            onUnlocked()
        }
    }

    private fun requestNotePassword(
        note: Note,
        message: String,
        confirmLabel: String,
        onVerified: () -> Unit,
    ) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), 0)
            background = rounded(palette.dialogSurface, 24)
        }
        form.addView(label(message, 14, palette.dialogText, false).apply {
            setPadding(0, 0, 0, dp(12))
        })
        val passwordInput = textInput("Senha", "", false).apply {
            configurePasswordField(this)
        }
        form.addView(passwordInput, LinearLayout.LayoutParams(MATCH, dp(56)))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Nota protegida")
            .setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(confirmLabel, null)
            .create()
        dialog.setOnShowListener {
            styleUnlockDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordInput.text?.toString().orEmpty()
                if (!NoteSecurity.matches(password, note.passwordHash)) {
                    passwordInput.error = "Senha incorreta"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onVerified()
            }
        }
        dialog.show()
    }

    private fun openEditor(existing: Note?, returnScreen: Screen = Screen.HOME, returnNoteId: String? = null) {
        currentScreen = Screen.EDITOR
        editorReturnScreen = returnScreen
        editorReturnNoteId = returnNoteId
        setScreenContent(buildEditorScreen(existing))
    }

    private fun showHome() {
        currentScreen = Screen.HOME
        currentViewerNoteId = null
        unlockedViewerNoteId = null
        editorReturnScreen = Screen.HOME
        editorReturnNoteId = null
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildContent())
        renderNotes(viewModel.notes.value.orEmpty())
    }

    private fun isOnboardingComplete(): Boolean =
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).getBoolean(ONBOARDING_COMPLETE_KEY, false)

    private fun completeOnboarding() {
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(ONBOARDING_COMPLETE_KEY, true)
            .apply()
        showHome()
    }

    private fun requestOnboardingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            onboardingAwaitingPermission = true
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            completeOnboarding()
        }
    }

    private fun buildWelcomeScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.canvas) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageButton(this).apply {
            contentDescription = "Voltar"
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        })
        header.addView(label("Permissões do Notes", 22, palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(8)
        })
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(24))
        }
        body.addView(label("Primeiros Passos", 24, palette.text, true))
        body.addView(label(
            "Para garantir que você aproveite ao máximo sua experiência, precisamos da sua permissão para alguns recursos-chave.",
            15,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(10), 0, dp(20)) })
        body.addView(buildPermissionCard())
        scroll.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        content.addView(MaterialButton(this).apply {
            text = "Próximo"
            isAllCaps = false
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            minHeight = dp(54)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { requestOnboardingPermissions() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        installSafeInsets(root, content, 20, 32)
        return root
    }

    private fun buildPermissionCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(palette.dialogSurface, 18)
        }
        val iconWrap = FrameLayout(this).apply {
            background = rounded(palette.accentSoft, 24)
        }
        iconWrap.addView(ImageView(this).apply {
            contentDescription = null
            setImageResource(R.drawable.ic_reminder_notification)
            setColorFilter(palette.accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, FrameLayout.LayoutParams(dp(48), dp(48)))
        card.addView(iconWrap, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label("Notificações", 16, palette.text, true))
        texts.addView(label("Receba lembretes das suas notas no horário marcado.", 14, palette.secondaryText, false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }

    private fun showArchived() {
        currentScreen = Screen.ARCHIVED
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildArchivedScreen())
        renderArchivedNotes(viewModel.notes.value.orEmpty())
    }

    private fun buildArchivedScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.canvas) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageButton(this).apply {
            contentDescription = "Voltar"
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showHome() }
        })
        header.addView(label("Arquivadas", 22, palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(8)
        })
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        archivedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }
        scroll.addView(archivedContainer, ViewGroup.LayoutParams(MATCH, WRAP))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        installSafeInsets(root, content, 20, 32)
        return root
    }

    private fun renderArchivedNotes(notes: List<Note>) {
        if (!::archivedContainer.isInitialized) return
        val archived = notes.filter { it.archived && !it.trashed }
        archivedContainer.removeAllViews()
        if (archived.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(64), dp(28), dp(64))
            }
            empty.addView(label("Nenhuma nota arquivada", 20, palette.text, true))
            empty.addView(label("As notas que você arquivar aparecem aqui.", 14, palette.secondaryText, false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            archivedContainer.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            archived.forEach { note -> archivedContainer.addView(buildNoteCard(note)) }
        }
    }

    private fun showTrash() {
        currentScreen = Screen.TRASH
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildTrashScreen())
        renderTrashNotes(viewModel.notes.value.orEmpty())
    }

    private fun buildTrashScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.canvas) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageButton(this).apply {
            contentDescription = "Voltar"
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showHome() }
        })
        header.addView(label("Lixeira", 22, palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(8)
        })
        header.addView(MaterialButton(this).apply {
            text = "Esvaziar"
            isAllCaps = false
            setTextColor(if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845"))
            backgroundTintList = ColorStateList.valueOf(palette.inputSurface)
            strokeColor = ColorStateList.valueOf(palette.inputBorder)
            strokeWidth = dp(1)
            cornerRadius = dp(14)
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { confirmEmptyTrash() }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        trashContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }
        scroll.addView(trashContainer, ViewGroup.LayoutParams(MATCH, WRAP))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        installSafeInsets(root, content, 20, 32)
        return root
    }

    private fun renderTrashNotes(notes: List<Note>) {
        if (!::trashContainer.isInitialized) return
        val trashed = notes.filter { it.trashed }
        trashContainer.removeAllViews()
        if (trashed.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(64), dp(28), dp(64))
            }
            empty.addView(label("Lixeira vazia", 20, palette.text, true))
            empty.addView(label("As notas que você excluir aparecem aqui.", 14, palette.secondaryText, false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            trashContainer.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            trashed.forEach { note -> trashContainer.addView(buildNoteCard(note)) }
        }
    }

    private fun openViewer(note: Note) {
        currentScreen = Screen.VIEWER
        currentViewerNoteId = note.id
        unlockedViewerNoteId = note.id
        renderViewer(note)
    }

    private fun renderViewer(note: Note) {
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildViewerScreen(note))
    }

    private fun navigateBackFromEditor() {
        if (editorReturnScreen == Screen.VIEWER && editorReturnNoteId != null) {
            viewModel.notes.value.orEmpty().firstOrNull { it.id == editorReturnNoteId }?.let { openViewer(it) }
                ?: showHome()
        } else {
            showHome()
        }
    }

    private fun buildViewerScreen(note: Note): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.canvas) }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setFillViewport(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, MATCH))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ImageButton(this).apply {
            contentDescription = "Voltar"
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showHome() }
        })
        toolbar.addView(label("Visualizar nota", 22, palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(8)
        })
        toolbar.addView(MaterialButton(this).apply {
            text = "Editar"
            isAllCaps = false
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { openEditor(note, Screen.VIEWER, note.id) }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        toolbar.addView(ImageButton(this).apply {
            contentDescription = "Abrir menu da nota"
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showNoteMenu(this, note) { showHome() } }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        content.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) })

        val noteSurface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(UiPalette.cardBackground(note.color, palette.isDark), 22)
        }
        noteSurface.addView(label(
            if (note.type == NoteType.CHECKLIST) "CHECKLIST" else "NOTA",
            10,
            UiPalette.cardAccent(note.color, palette.isDark),
            true,
        ))
        noteSurface.addView(label(note.title, 24, palette.cardText, true).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(18))
        })
        if (note.type == NoteType.CHECKLIST) {
            val checklistProgress = ChecklistProgress.of(note.items)
            noteSurface.addView(label(checklistProgress.label, 13, UiPalette.cardAccent(note.color, palette.isDark), true).apply {
                setPadding(0, 0, 0, dp(8))
            })
            noteSurface.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = checklistProgress.percent
                progressTintList = ColorStateList.valueOf(UiPalette.cardAccent(note.color, palette.isDark))
                progressBackgroundTintList = ColorStateList.valueOf(Color.argb(64, 0, 0, 0))
            }, LinearLayout.LayoutParams(MATCH, dp(8)).apply { bottomMargin = dp(12) })
            note.items.forEachIndexed { index, item ->
                noteSurface.addView(CheckBox(this).apply {
                    text = item.text
                    textSize = 16f
                    isChecked = item.completed
                    setTextColor(palette.cardBody)
                    alpha = if (item.completed) 0.64f else 1f
                    setPadding(0, dp(3), 0, dp(3))
                    buttonTintList = ColorStateList.valueOf(UiPalette.cardAccent(note.color, palette.isDark))
                    setOnCheckedChangeListener { _, checked ->
                        alpha = if (checked) 0.64f else 1f
                        viewModel.toggleItem(note.id, index, checked)
                    }
                })
            }
        } else {
            noteSurface.addView(label(note.body, 17, palette.cardBody, false).apply {
                setLineSpacing(dp(5).toFloat(), 1f)
                setTextIsSelectable(true)
            })
        }
        noteSurface.addView(label(formatDate(note.updatedAt), 11, palette.cardFooter, false).apply {
            setPadding(0, dp(20), 0, 0)
        })
        content.addView(noteSurface, LinearLayout.LayoutParams(MATCH, WRAP))

        installSafeInsets(root, content, 20, 32)
        return root
    }

    private fun buildEditorScreen(existing: Note?): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(palette.canvas)
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setFillViewport(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, MATCH))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(ImageButton(this).apply {
            contentDescription = "Voltar"
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { navigateBackFromEditor() }
        })
        val toolbarTitle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        toolbarTitle.addView(label(if (existing == null) "Nova nota" else "Editar nota", 22, palette.text, true))
        toolbar.addView(toolbarTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        val saveButton = MaterialButton(this).apply {
            text = "Salvar"
            isAllCaps = false
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
        }
        toolbar.addView(saveButton, LinearLayout.LayoutParams(WRAP, dp(44)))
        content.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) })

        val typeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textType = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Nota"
            textSize = 14f
            setTextColor(palette.dialogText)
            buttonTintList = checkboxTint()
            isChecked = existing?.type != NoteType.CHECKLIST
        }
        val checklistType = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Checklist"
            textSize = 14f
            setTextColor(palette.dialogText)
            buttonTintList = checkboxTint()
            isChecked = existing?.type == NoteType.CHECKLIST
        }
        typeGroup.addView(textType)
        typeGroup.addView(checklistType)
        content.addView(typeGroup, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        val titleInput = textInput("Título", existing?.title.orEmpty(), false)
        val bodyInput = textInput(
            if (existing?.type == NoteType.CHECKLIST) "Um item por linha" else "Escreva o que está na sua cabeça",
            existing?.let {
                if (it.type == NoteType.CHECKLIST) it.items.joinToString("\n") { item -> item.text } else it.body
            }.orEmpty(),
            true,
        )
        content.addView(titleInput, LinearLayout.LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(8) })
        val bodyParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            bottomMargin = dp(12)
        }
        content.addView(bodyInput, bodyParams)
        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            bodyInput.hint = if (checkedId == checklistType.id) "Um item por linha" else "Escreva o que está na sua cabeça"
        }

        var selectedColor = existing?.color ?: NoteColor.SUN
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        NoteColor.entries.forEach { color ->
            val swatch = TextView(this).apply {
                contentDescription = color.label
                gravity = Gravity.CENTER
                background = colorSwatchBackground(color, color == selectedColor)
                elevation = if (color == selectedColor) dp(3).toFloat() else 0f
                setOnClickListener {
                    selectedColor = color
                    NoteColor.entries.forEach { other ->
                        colors.findViewWithTag<TextView>(other.name)?.apply {
                            background = colorSwatchBackground(other, other == selectedColor)
                            elevation = if (other == selectedColor) dp(3).toFloat() else 0f
                        }
                    }
                }
                tag = color.name
            }
            colors.addView(swatch, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        }
        val colorOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Cor da nota", 12, palette.text, true).apply {
                setPadding(dp(2), 0, 0, dp(8))
            })
            addView(colors, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        content.addView(colorOptions, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(2)
            bottomMargin = dp(16)
        })

        var protectEnabled = existing?.locked == true
        var protectionPassword = ""
        val protectBox = CheckBox(this).apply {
            text = "Exigir senha para visualizar"
            textSize = 14f
            setTextColor(palette.dialogText)
            buttonTintList = checkboxTint()
            isChecked = protectEnabled
        }
        val protectSummary = label(
            if (protectEnabled) "Proteção configurada; toque para alterar." else "Sem proteção por senha.",
            12,
            palette.secondaryText,
            false,
        )
        val protectionOption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(protectBox, LinearLayout.LayoutParams(MATCH, dp(48)))
            addView(protectSummary, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        content.addView(protectionOption, LinearLayout.LayoutParams(MATCH, WRAP))
        fun openPasswordSettings() {
            val wasEnabled = protectEnabled
            showPasswordSettingsDialog(
                existingLocked = existing?.locked == true,
                onConfirmed = { password ->
                    protectEnabled = true
                    protectionPassword = password
                    protectBox.isChecked = true
                    protectSummary.text = if (protectEnabled) {
                        "Proteção configurada; toque para alterar."
                    } else {
                        "Sem proteção por senha."
                    }
                },
                onCancelled = { protectBox.isChecked = wasEnabled },
            )
        }
        protectBox.setOnClickListener {
            if (protectBox.isChecked) {
                openPasswordSettings()
            } else {
                protectEnabled = false
                protectionPassword = ""
                protectSummary.text = "Sem proteção por senha."
            }
        }
        protectionOption.setOnClickListener { openPasswordSettings() }

        var reminderSelection = ReminderEditorSelection(existing?.reminder)
        val reminderBox = CheckBox(this).apply {
            text = "Ativar lembrete"
            textSize = 14f
            setTextColor(palette.dialogText)
            buttonTintList = checkboxTint()
            isChecked = reminderSelection.isEnabled
        }
        val reminderSummaryView = label("", 12, palette.secondaryText, false)
        fun refreshReminderSummary() {
            val schedule = reminderSelection.schedule
            val scheduleText = reminderSummary(schedule)
            val notificationWarning = if (schedule != null && !ReminderNotification.canPost(applicationContext)) {
                "\nNotificações bloqueadas: permita as notificações nas configurações do sistema."
            } else {
                ""
            }
            reminderSummaryView.text = scheduleText + notificationWarning
            reminderSummaryView.isClickable = notificationWarning.isNotBlank()
            reminderSummaryView.isFocusable = reminderSummaryView.isClickable
            reminderSummaryView.setOnClickListener {
                if (reminderSummaryView.isClickable) openNotificationSettings()
            }
        }
        fun updateReminderSelection(schedule: ReminderSchedule?) {
            reminderSelection = if (schedule == null) {
                reminderSelection.disable()
            } else {
                reminderSelection.confirm(schedule)
            }
            // The schedule is the source of truth. The checkbox only mirrors it;
            // this prevents a click/listener race from dropping a configured
            // reminder during save.
            reminderBox.isChecked = reminderSelection.isEnabled
            refreshReminderSummary()
        }
        refreshReminderSummary()
        val reminderOption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(reminderBox, LinearLayout.LayoutParams(MATCH, dp(48)))
            addView(reminderSummaryView, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = dp(4)
            })
        }
        content.addView(reminderOption, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })
        fun openReminderSettings() {
            showReminderSettingsDialog(
                initial = reminderSelection.schedule,
                onConfirmed = { schedule ->
                    updateReminderSelection(schedule)
                    if (schedule != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        reminderNotificationPermissionPrompted = true
                        reminderNotificationPermissionRequestInFlight = true
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onCancelled = {
                    reminderBox.isChecked = reminderSelection.isEnabled
                },
            )
        }
        reminderBox.setOnClickListener {
            if (reminderBox.isChecked) {
                openReminderSettings()
            } else {
                updateReminderSelection(null)
            }
        }
        reminderOption.setOnClickListener { openReminderSettings() }

        saveButton.setOnClickListener {
            val title = titleInput.text?.toString().orEmpty().trim()
            val text = bodyInput.text?.toString().orEmpty().trim()
            if (title.isBlank() && text.isBlank()) {
                titleInput.error = "Dê um nome ou escreva alguma coisa"
                return@setOnClickListener
            }
            val passwordIsNew = existing?.locked != true || protectionPassword.isNotBlank()
            if (protectEnabled && passwordIsNew && protectionPassword.length < MIN_PASSWORD_LENGTH) {
                showPasswordSettingsDialog(
                    existingLocked = existing?.locked == true,
                    onConfirmed = { password -> protectionPassword = password },
                    onCancelled = {},
                )
                return@setOnClickListener
            }
            val type = if (checklistType.isChecked) NoteType.CHECKLIST else NoteType.TEXT
            val reminder = reminderSelection.schedule
            if (reminder != null && reminder.nextOccurrenceOrNull(java.time.ZonedDateTime.now()) == null) {
                Toast.makeText(this, "Escolha uma data e horário futuros para o lembrete.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.upsert(existing, title, text, type, selectedColor, protectEnabled, protectionPassword, reminder)
            if (editorReturnScreen == Screen.VIEWER && existing != null) {
                viewModel.notes.value.orEmpty().firstOrNull { it.id == existing.id }?.let { openViewer(it) }
                    ?: showHome()
            } else {
                showHome()
            }
        }

        installSafeInsets(root, content, 20, 32)
        return root
    }

    private fun showPasswordSettingsDialog(
        existingLocked: Boolean,
        onConfirmed: (String) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            background = rounded(palette.dialogSurface, 24)
        }
        form.addView(label(
            if (existingLocked) {
                "Digite uma nova senha ou deixe vazio para manter a senha atual."
            } else {
                "Defina uma senha para proteger o conteúdo desta nota."
            },
            14,
            palette.secondaryText,
            false,
        ).apply {
            setPadding(0, 0, 0, dp(14))
        })
        val password = textInput(
            if (existingLocked) "Nova senha (opcional)" else "Senha de visualização",
            "",
            false,
        ).apply {
            textSize = 16f
            configurePasswordField(this)
        }
        form.addView(password, LinearLayout.LayoutParams(MATCH, dp(60)))

        var finished = false
        fun cancelOnce() {
            if (!finished) {
                finished = true
                onCancelled()
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Proteção da nota")
            .setView(form)
            .setNegativeButton("Cancelar") { _, _ -> cancelOnce() }
            .setPositiveButton("Confirmar", null)
            .create()
        dialog.setOnCancelListener { cancelOnce() }
        dialog.setOnShowListener {
            styleUnlockDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = password.text?.toString().orEmpty()
                if (value.isBlank() && existingLocked) {
                    finished = true
                    dialog.dismiss()
                    onConfirmed("")
                    return@setOnClickListener
                }
                if (value.length < MIN_PASSWORD_LENGTH) {
                    password.error = "Use pelo menos $MIN_PASSWORD_LENGTH caracteres"
                    password.requestFocus()
                    return@setOnClickListener
                }
                finished = true
                dialog.dismiss()
                onConfirmed(value)
            }
        }
        dialog.show()
    }

    private fun showReminderSettingsDialog(
        initial: ReminderSchedule?,
        onConfirmed: (ReminderSchedule?) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        val newReminderDefaults = ReminderEditorDefaults.forNewReminder(ZonedDateTime.now())
        var reminderHour = initial?.hour ?: newReminderDefaults.hour
        var reminderMinute = initial?.minute ?: newReminderDefaults.minute
        var selectedRecurrence = when (initial?.recurrence) {
            null, ReminderRecurrence.ONCE -> ReminderRecurrence.ONCE
            ReminderRecurrence.DAILY -> ReminderRecurrence.WEEKLY
            ReminderRecurrence.WEEKLY, ReminderRecurrence.MONTHLY -> initial.recurrence
        }
        var selectedDays = when (initial?.recurrence) {
            ReminderRecurrence.WEEKLY -> initial.daysOfWeek.toSet()
            ReminderRecurrence.DAILY -> ReminderSchedule.ALL_WEEK_DAYS
            else -> ReminderSchedule.ALL_WEEK_DAYS
        }.ifEmpty { ReminderSchedule.ALL_WEEK_DAYS }
        var selectedDate = initial?.date ?: newReminderDefaults.date

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            background = rounded(palette.dialogSurface, 28)
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(palette.dialogSurface)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.dialogSurface)
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        val dialogContentHeight = (resources.displayMetrics.heightPixels * 0.62f).roundToInt()
        form.addView(scroll, LinearLayout.LayoutParams(MATCH, dialogContentHeight))

        fun sectionTitle(text: String): TextView = label(text, 13, palette.dialogText, true).apply {
            setPadding(0, 0, 0, dp(8))
        }

        body.addView(sectionTitle("Horário"), LinearLayout.LayoutParams(MATCH, WRAP))
        val timeButton = MaterialButton(this).apply {
            text = formatTime(reminderHour, reminderMinute)
            textSize = 18f
            gravity = Gravity.CENTER
            isAllCaps = false
            setTextColor(palette.dialogText)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            strokeColor = ColorStateList.valueOf(palette.dialogControlBorder)
            strokeWidth = dp(1)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(60)
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
        }
        body.addView(timeButton, LinearLayout.LayoutParams(MATCH, dp(60)).apply {
            bottomMargin = dp(18)
        })

        body.addView(sectionTitle("Repetição"), LinearLayout.LayoutParams(MATCH, WRAP))
        val recurrenceButtons = linkedMapOf<ReminderRecurrence, MaterialButton>()
        var refresh: () -> Unit = {}
        val recurrenceLabels = linkedMapOf(
            ReminderRecurrence.ONCE to "Lembrar uma vez",
            ReminderRecurrence.WEEKLY to "Semanal",
            ReminderRecurrence.MONTHLY to "Mensal",
        )
        recurrenceLabels.forEach { (recurrence, text) ->
            val button = MaterialButton(this).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                isAllCaps = false
                minWidth = 0
                minHeight = dp(48)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(12), 0, dp(12), 0)
                cornerRadius = dp(14)
                stateListAnimator = null
                setOnClickListener {
                    selectedRecurrence = recurrence
                    refreshReminderModeButtons(recurrenceButtons, selectedRecurrence)
                    refresh()
                }
            }
            recurrenceButtons[recurrence] = button
            body.addView(button, LinearLayout.LayoutParams(MATCH, dp(48)).apply {
                bottomMargin = dp(8)
            })
        }

        val daysTitle = sectionTitle("Dias da semana")
        body.addView(daysTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(8)
        })
        val dayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dayOptions = listOf(
            7 to "Dom",
            1 to "Seg",
            2 to "Ter",
            3 to "Qua",
            4 to "Qui",
            5 to "Sex",
            6 to "Sáb",
        )
        val dayButtons = linkedMapOf<Int, MaterialButton>()
        dayOptions.forEach { (dayValue, dayLabel) ->
            val button = MaterialButton(this).apply {
                text = dayLabel
                textSize = 12f
                gravity = Gravity.CENTER
                isAllCaps = false
                minWidth = 0
                minHeight = dp(48)
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                cornerRadius = dp(24)
                strokeWidth = dp(1)
                stateListAnimator = null
                setOnClickListener {
                    selectedDays = if (dayValue in selectedDays) {
                        if (selectedDays.size == 1) selectedDays else selectedDays - dayValue
                    } else {
                        selectedDays + dayValue
                    }
                    refreshReminderDayButtons(dayButtons, selectedDays)
                }
            }
            dayButtons[dayValue] = button
            dayRow.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        body.addView(dayRow, LinearLayout.LayoutParams(MATCH, dp(48)).apply {
            bottomMargin = dp(16)
        })

        val monthlyTitle = sectionTitle("Dia do mês")
        body.addView(monthlyTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        val monthlyDay = textInput("Escolha um dia entre 1 e 31", (initial?.dayOfMonth ?: 1).toString(), false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setHintTextColor(palette.secondaryText)
        }
        body.addView(monthlyDay, LinearLayout.LayoutParams(MATCH, dp(60)).apply {
            bottomMargin = dp(16)
        })

        val dateTitle = sectionTitle("Data específica")
        body.addView(dateTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        val dateButton = MaterialButton(this).apply {
            text = formatLocalDate(selectedDate)
            textSize = 17f
            gravity = Gravity.CENTER
            isAllCaps = false
            setTextColor(palette.dialogText)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            strokeColor = ColorStateList.valueOf(palette.dialogControlBorder)
            strokeWidth = dp(1)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(60)
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
        }
        body.addView(dateButton, LinearLayout.LayoutParams(MATCH, dp(60)).apply {
            bottomMargin = dp(10)
        })
        refresh = {
            val weekly = selectedRecurrence == ReminderRecurrence.WEEKLY
            val monthly = selectedRecurrence == ReminderRecurrence.MONTHLY
            val once = selectedRecurrence == ReminderRecurrence.ONCE
            daysTitle.visibility = if (weekly) View.VISIBLE else View.GONE
            dayRow.visibility = if (weekly) View.VISIBLE else View.GONE
            monthlyTitle.visibility = if (monthly) View.VISIBLE else View.GONE
            monthlyDay.visibility = if (monthly) View.VISIBLE else View.GONE
            dateTitle.visibility = if (once) View.VISIBLE else View.GONE
            dateButton.visibility = if (once) View.VISIBLE else View.GONE
        }

        refreshReminderModeButtons(recurrenceButtons, selectedRecurrence)
        refreshReminderDayButtons(dayButtons, selectedDays)
        refresh()

        timeButton.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTitleText("Escolha o horário")
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(reminderHour)
                .setMinute(reminderMinute)
                .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
                .build()
            picker.addOnPositiveButtonClickListener {
                reminderHour = picker.hour
                reminderMinute = picker.minute
                timeButton.text = formatTime(reminderHour, reminderMinute)
            }
            picker.show(supportFragmentManager, "reminder-time-picker")
        }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                dateButton.text = formatLocalDate(selectedDate)
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
        }

        var finished = false
        fun cancelOnce() {
            if (!finished) {
                finished = true
                onCancelled()
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Configurar lembrete")
            .setView(form)
            .setNegativeButton("Cancelar") { _, _ -> cancelOnce() }
            .setPositiveButton("Confirmar", null)
            .create()
        dialog.setOnCancelListener { cancelOnce() }
        dialog.setOnShowListener {
            styleUnlockDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val schedule = try {
                    when (selectedRecurrence) {
                        ReminderRecurrence.DAILY -> ReminderSchedule.daily(reminderHour, reminderMinute)
                        ReminderRecurrence.WEEKLY -> ReminderSchedule.weekly(reminderHour, reminderMinute, selectedDays)
                        ReminderRecurrence.MONTHLY -> {
                            val day = monthlyDay.text?.toString()?.toIntOrNull()
                            require(day in 1..31) { "Informe um dia entre 1 e 31" }
                            ReminderSchedule.monthly(reminderHour, reminderMinute, day!!)
                        }
                        ReminderRecurrence.ONCE -> ReminderSchedule.once(reminderHour, reminderMinute, selectedDate)
                    }
                } catch (_: IllegalArgumentException) {
                    monthlyDay.error = "Informe um dia entre 1 e 31"
                    monthlyDay.requestFocus()
                    return@setOnClickListener
                }
                if (schedule.nextOccurrenceOrNull(ZonedDateTime.now()) == null) {
                    if (selectedRecurrence == ReminderRecurrence.ONCE) {
                        dateButton.error = "Escolha uma data e horário futuros"
                    } else {
                        timeButton.error = "Escolha um horário válido"
                    }
                    return@setOnClickListener
                }
                finished = true
                dialog.dismiss()
                onConfirmed(schedule)
            }
        }
        dialog.show()
    }

    private fun refreshReminderDayButtons(
        buttons: Map<Int, MaterialButton>,
        selectedDays: Set<Int>,
    ) {
        buttons.forEach { (day, button) ->
            val selected = day in selectedDays
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) palette.accentSoft else palette.dialogControlSurface,
            )
            button.strokeColor = ColorStateList.valueOf(
                if (selected) palette.accent else palette.dialogControlBorder,
            )
            button.setTextColor(if (selected) palette.accent else palette.secondaryText)
        }
    }

    private fun refreshReminderModeButtons(
        buttons: Map<ReminderRecurrence, MaterialButton>,
        selectedRecurrence: ReminderRecurrence,
    ) {
        buttons.forEach { (recurrence, button) ->
            val selected = recurrence == selectedRecurrence
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) palette.accentSoft else palette.dialogControlSurface,
            )
            button.strokeColor = ColorStateList.valueOf(
                if (selected) palette.accent else palette.dialogControlBorder,
            )
            button.strokeWidth = dp(1)
            button.setTextColor(if (selected) palette.accent else palette.secondaryText)
        }
    }

    private fun reminderSummary(schedule: ReminderSchedule?): String = when (schedule?.recurrence) {
        null -> "Lembrete desativado."
        ReminderRecurrence.DAILY -> "Todos os dias · ${formatTime(schedule.hour, schedule.minute)}"
        ReminderRecurrence.WEEKLY -> {
            val labels = mapOf(1 to "seg", 2 to "ter", 3 to "qua", 4 to "qui", 5 to "sex", 6 to "sáb", 7 to "dom")
            schedule.daysOfWeek.sorted().joinToString(", ") { labels[it].orEmpty() } + " · " + formatTime(schedule.hour, schedule.minute)
        }
        ReminderRecurrence.MONTHLY -> "Todo dia ${schedule.dayOfMonth} · ${formatTime(schedule.hour, schedule.minute)}"
        ReminderRecurrence.ONCE -> "${formatLocalDate(schedule.date!!)} · ${formatTime(schedule.hour, schedule.minute)}"
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotification.CHANNEL_ID)
            }
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmLabel: String,
        action: () -> Unit,
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        panel.addView(label(message, 15, palette.secondaryText, false).apply {
            setPadding(0, dp(12), 0, dp(20))
        })
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttons.addView(MaterialButton(this).apply {
            text = "Cancelar"
            isAllCaps = false
            setTextColor(palette.secondaryText)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(8) })
        buttons.addView(MaterialButton(this).apply {
            text = confirmLabel
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845"))
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                dialog.dismiss()
                action()
            }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, dp(44)))
        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.show()
    }

    private fun confirmTrash(note: Note, onTrashed: (() -> Unit)? = null) {
        showConfirmDialog(
            "Mover para a lixeira?",
            "\"${note.title}\" será movida para a lixeira.",
            "Mover",
        ) {
            if (note.locked) {
                requestNotePassword(note, "Digite a senha para mover esta nota para a lixeira.", "Mover") {
                    viewModel.trash(note.id)
                    onTrashed?.invoke()
                }
            } else {
                viewModel.trash(note.id)
                onTrashed?.invoke()
            }
        }
    }

    private fun confirmPermanentDelete(note: Note, onDeleted: (() -> Unit)? = null) {
        showConfirmDialog(
            "Excluir permanentemente?",
            "\"${note.title}\" será excluída de vez. Essa ação não pode ser desfeita.",
            "Excluir",
        ) {
            viewModel.delete(note.id)
            onDeleted?.invoke()
        }
    }

    private fun confirmEmptyTrash() {
        showConfirmDialog(
            "Esvaziar lixeira?",
            "Todas as notas da lixeira serão excluídas permanentemente.",
            "Esvaziar",
        ) {
            viewModel.emptyTrash()
        }
    }

    private fun styleUnlockDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(rounded(palette.dialogSurface, 28))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(palette.dialogButton)
            isAllCaps = false
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(palette.secondaryText)
            isAllCaps = false
        }
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(palette.dialogText)
    }

    private fun copyNote(note: Note) {
        withUnlockedContent(note) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(note.title, noteContent(note)))
            Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareNote(note: Note) {
        withUnlockedContent(note) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, note.title)
                putExtra(Intent.EXTRA_TEXT, "${note.title}\n\n${noteContent(note)}")
            }
            startActivity(Intent.createChooser(intent, "Compartilhar nota"))
        }
    }

    private fun noteContent(note: Note): String = if (note.type == NoteType.CHECKLIST) {
        note.items.joinToString("\n") { item -> "[${if (item.completed) "x" else " "}] ${item.text}" }
    } else {
        note.body
    }

    private fun configurePasswordField(input: EditText) {
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.transformationMethod = PasswordTransformationMethod.getInstance()
        input.setCompoundDrawableTintList(ColorStateList.valueOf(palette.mutedText))
        var visible = false

        fun updateEyeIcon() {
            input.setCompoundDrawablesWithIntrinsicBounds(
                0,
                0,
                if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                0,
            )
        }

        updateEyeIcon()
        input.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP || event.x < input.width - input.totalPaddingRight) {
                return@setOnTouchListener false
            }
            visible = !visible
            val cursorPosition = input.selectionStart.coerceAtLeast(0)
            input.transformationMethod = if (visible) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            updateEyeIcon()
            input.setSelection(cursorPosition.coerceAtMost(input.length()))
            true
        }
    }

    private fun textInput(hintText: String, value: String, multiline: Boolean): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        textSize = 15f
        setTextColor(palette.text)
        setHintTextColor(palette.mutedText)
        gravity = if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = inputBackground()
        if (multiline) {
            minLines = 4
            setSingleLine(false)
        } else {
            setSingleLine(true)
        }
    }

    private fun applyContentInsets(
        content: View,
        insets: WindowInsetsCompat,
        baseTop: Int,
        baseHorizontal: Int,
        baseBottom: Int = 22,
    ) {
        val systemInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val padding = SafeAreaPolicy.contentPadding(
            baseHorizontal = dp(baseHorizontal),
            baseTop = dp(baseTop),
            baseBottom = dp(baseBottom),
            systemLeft = systemInsets.left,
            systemTop = systemInsets.top,
            systemRight = systemInsets.right,
            systemBottom = systemInsets.bottom,
            imeBottom = imeInsets.bottom,
        )
        content.setPadding(padding.left, padding.top, padding.right, padding.bottom)
    }

    private fun installSafeInsets(
        root: View,
        content: View,
        baseTop: Int,
        baseBottom: Int,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyContentInsets(content, insets, baseTop, 22, baseBottom)
            insets
        }
    }

    private fun actionIcon(
        iconRes: Int,
        description: String,
        iconTint: Int = palette.cardBody,
        backgroundColor: Int = Color.TRANSPARENT,
        action: () -> Unit,
    ): ImageButton = ImageButton(this).apply {
        contentDescription = description
        setImageResource(iconRes)
        setColorFilter(iconTint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        minimumWidth = 0
        minimumHeight = 0
        background = if (backgroundColor == Color.TRANSPARENT) {
            selectableItemBackgroundBorderless()
        } else {
            rounded(backgroundColor, 12)
        }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        setOnClickListener { action() }
    }

    private fun selectableItemBackgroundBorderless(): Drawable? {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        return if (value.resourceId != 0) ContextCompat.getDrawable(this, value.resourceId) else null
    }

    private fun selectableItemBackgroundRounded(): Drawable =
        android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(if (palette.isDark) Color.argb(48, 255, 255, 255) else Color.argb(24, 0, 0, 0)),
            null,
            rounded(Color.WHITE, 14),
        )

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans", Typeface.BOLD) else Typeface.create("sans", Typeface.NORMAL)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun outlined(color: Int, borderColor: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        setStroke(dp(1), borderColor)
        cornerRadius = dp(radius).toFloat()
    }

    private fun inputBackground(): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            outlined(palette.inputSurface, palette.accent, 16).apply {
                setStroke(dp(2), palette.accent)
            },
        )
        addState(
            intArrayOf(),
            outlined(palette.inputSurface, palette.inputBorder, 16),
        )
    }

    private fun checkboxTint(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(),
        ),
        intArrayOf(palette.accent, palette.mutedText, palette.secondaryText),
    )

    private fun colorSwatchBackground(color: NoteColor, selected: Boolean): Drawable =
        GradientDrawable().apply {
            val baseColor = Color.parseColor(if (palette.isDark) color.darkBackgroundHex else color.backgroundHex)
            val accentColor = Color.parseColor(if (palette.isDark) color.darkAccentHex else color.accentHex)
            setColor(if (palette.isDark) baseColor else ColorUtils.blendARGB(baseColor, accentColor, 0.18f))
            setStroke(
                dp(if (selected) 3 else 1),
                if (selected) accentColor else palette.inputBorder,
            )
            cornerRadius = dp(20).toFloat()
        }

    private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.ROOT, hour, minute)

    private fun formatLocalDate(date: LocalDate): String = "%02d/%02d/%04d".format(
        Locale.ROOT,
        date.dayOfMonth,
        date.monthValue,
        date.year,
    )

    private fun formatDate(timestamp: Long): String = SimpleDateFormat("dd MMM · HH:mm", Locale("pt", "BR"))
        .format(Date(timestamp))
        .replaceFirstChar { it.titlecase(Locale("pt", "BR")) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dialogWidth(): Int = (resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(420))

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val UI_PREFERENCES = "noteharbor.ui.preferences"
        const val NIGHT_MODE_KEY = "night_mode"
        const val ONBOARDING_COMPLETE_KEY = "onboarding_complete"
        const val MIN_PASSWORD_LENGTH = 4
    }
}
