package com.will.noteharbor

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputFilter
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
import android.view.WindowManager
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
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
import com.will.noteharbor.data.CloudSyncResult
import com.will.noteharbor.data.CloudSyncState
import com.will.noteharbor.data.SecurityRecovery
import com.will.noteharbor.data.AppReset
import com.will.noteharbor.data.AttachmentMetadata
import com.will.noteharbor.data.AttachmentStore
import com.will.noteharbor.data.AttachmentTooLargeException
import com.will.noteharbor.data.DriveServiceFactory
import com.will.noteharbor.data.Note
import com.will.noteharbor.data.NoteColor
import com.will.noteharbor.data.NoteEncryption
import com.will.noteharbor.data.NoteFilter
import com.will.noteharbor.data.NoteQueries
import com.will.noteharbor.data.NotePreview
import com.will.noteharbor.data.NoteType
import com.will.noteharbor.data.ReminderRecurrence
import com.will.noteharbor.data.ReminderDisplay
import com.will.noteharbor.data.ReminderEditorDefaults
import com.will.noteharbor.data.ReminderEditorSelection
import com.will.noteharbor.data.ReminderPermissionPolicy
import com.will.noteharbor.data.ReminderSchedule
import com.will.noteharbor.data.Totp
import com.will.noteharbor.data.UnlockMethod
import com.will.noteharbor.data.UnlockVault
import com.will.noteharbor.reminder.ReminderNotification
import com.will.noteharbor.reminder.ReminderScheduler
import com.will.noteharbor.ui.NotesViewModel
import com.will.noteharbor.ui.PatternLockView
import com.will.noteharbor.ui.SafeAreaPolicy
import com.will.noteharbor.ui.UiPalette
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID
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
        SECURITY,
        WELCOME,
        LOCKED,
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
    private var currentScreenRoot: View? = null
    private var editorReturnScreen = Screen.HOME
    private var editorReturnNoteId: String? = null
    private var secureFlagActive = false
    private var appWentToBackground = false
    // Senhas das notas recuperadas na DESATIVAÇÃO do método (a desativação já resolveu o método).
    // Ficam só em memória, durante a sessão, para ativar outro método depois sem pedir o anterior
    // de novo. São zeradas ao serem consumidas; se o processo morrer, o app volta a pedir o método.
    private var pendingHandoffSecrets: Map<String, String>? = null
    private val screenCaptureCallback: Activity.ScreenCaptureCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Activity.ScreenCaptureCallback {
                if (secureFlagActive) {
                    Toast.makeText(this, "Captura de tela bloqueada por segurança.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            null
        }
    private val cloudSettingsStore by lazy { CloudBackupSettingsStore(applicationContext) }
    private val cloudSynchronizer by lazy { CloudBackupSynchronizer(applicationContext, viewModel.repositoryForSync) }
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private val cloudSyncRunning = AtomicBoolean(false)
    private var cloudSyncState = CloudSyncState(CloudSyncPhase.DISCONNECTED)
    private var cloudSyncPending = false
    private var recoveryRestoreDialogShowing = false
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

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        handleDocumentsPicked(uris)
    }

    // Estado dos anexos no editor: lista viva, ids novos desta sessão (para limpeza ao descartar) e
    // callback de re-render. Setados por `buildEditorScreen`, limpos em `navigateBackFromEditor`.
    private var editorAttachments: MutableList<AttachmentMetadata>? = null
    private var editorAttachmentNewIds: MutableList<String>? = null
    private var editorAttachmentsRefresh: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedNightMode()
        super.onCreate(savedInstanceState)
        palette = UiPalette.from(this)
        configureSystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let { registerScreenCaptureCallback(ContextCompat.getMainExecutor(this), it) }
        }
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
                    Screen.SECURITY -> showHome()
                    Screen.WELCOME -> finish()
                    Screen.LOCKED -> finish()
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
                Screen.SECURITY -> Unit
                Screen.WELCOME -> Unit
                Screen.LOCKED -> Unit
            }
        }
        viewModel.changes.observe(this) {
            requestAutomaticCloudSync()
        }
        window.decorView.post {
            if (cloudSettingsStore.load().automatic) synchronizeConfiguredCloud(showToast = false)
            handleReminderIntent(intent)
        }
        maybeLockAppIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (appWentToBackground) {
            appWentToBackground = false
            maybeLockAppIfNeeded()
        }
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

    override fun onStop() {
        super.onStop()
        // Marca que o app saiu do primeiro plano: ao voltar, re-bloqueia se o bloqueio ao iniciar
        // estiver ativo. Usamos onStop (e não onPause) para não re-bloquear quando o BiometricPrompt
        // ou a caixa de notificações do sistema cobre a Activity sem ela deixar de ser visível.
        appWentToBackground = true
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
        pendingHandoffSecrets = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let { unregisterScreenCaptureCallback(it) }
        }
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
        menu.addView(divider(), dividerParams)
        menu.addView(item("Segurança", R.drawable.ic_lock, palette.text) { showSecuritySettings() }, rowParams)

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
            val contentView = popup.contentView
            contentView.measure(
                View.MeasureSpec.makeMeasureSpec(dp(256), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val popupWidth = contentView.measuredWidth
            val popupHeight = contentView.measuredHeight

            val anchorLocation = IntArray(2)
            anchor.getLocationOnScreen(anchorLocation)
            val anchorX = anchorLocation[0]
            val anchorY = anchorLocation[1]

            val margin = dp(4)
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            // Alinha a borda direita do popup com a borda direita do botão,
            // mantendo-o dentro da tela na horizontal.
            val x = (anchorX + anchor.width - popupWidth).coerceIn(0, (screenWidth - popupWidth).coerceAtLeast(0))

            // Abre abaixo do botão quando couber; senão, abre acima para não cortar.
            val spaceBelow = screenHeight - (anchorY + anchor.height + margin)
            val y = if (spaceBelow >= popupHeight) {
                anchorY + anchor.height + margin
            } else {
                (anchorY - popupHeight - margin).coerceAtLeast(0)
            }

            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
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
                    handleCloudSyncSuccess(result, "Backup conectado e sincronizado.")
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
                    handleCloudSyncSuccess(result, if (showToast) "Backup sincronizado." else null)
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

    /** Bloqueia capturas de tela apenas enquanto uma nota protegida está visível. */
    private fun setSecureFlag(enabled: Boolean) {
        secureFlagActive = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
        brand.addView(ImageView(this).apply {
            contentDescription = "EasyNote"
            setImageResource(if (palette.isDark) R.drawable.logo_dark_bg else R.drawable.logo_light_bg)
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(WRAP, dp(40)))
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
        topRow.addView(actionIcon(
            if (note.pinned) R.drawable.ic_star else R.drawable.ic_star_outline,
            if (note.pinned) "Desafixar nota" else "Fixar nota",
            iconTint = if (note.pinned) UiPalette.cardAccent(note.color, palette.isDark) else palette.cardBody,
        ) { viewModel.togglePinned(note.id) })
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
            content.addView(label("Toque para desbloquear e visualizar.", 14, palette.cardBody, false).apply {
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
        if (note.locked && !viewModel.isUnlocked(note.id)) {
            if (UnlockVault.currentMethod(this) == UnlockMethod.NONE) {
                // Nenhum método de desbloqueio ativo: sem ele não há como desembrulhar a nota.
                // Avisa e aponta para as configurações de segurança em vez de pedir credencial.
                showNoUnlockMethodModal()
            } else {
                requestUnlock(note, onUnlocked)
            }
        } else {
            onUnlocked()
        }
    }

    private fun requestUnlock(note: Note, onUnlocked: () -> Unit) {
        // Uma nota protegida usa apenas o método escolhido — nunca uma senha própria da nota.
        when (UnlockVault.currentMethod(this)) {
            UnlockMethod.NUMERIC_PIN -> requestPinUnlock(note, onUnlocked)
            UnlockMethod.PATTERN -> requestPatternUnlock(note, onUnlocked)
            UnlockMethod.BIOMETRIC -> requestBiometricUnlock(note, onUnlocked)
            UnlockMethod.TOTP -> requestTotpUnlock(note, onUnlocked)
            UnlockMethod.NONE -> showNoUnlockMethodModal()
        }
    }

    private fun requestBiometricUnlock(note: Note, onUnlocked: () -> Unit) {
        if (UnlockVault.hasWrapped(this, note.id)) {
            val stored = UnlockVault.loadWrapped(this, note.id)
            val cipher = stored?.let { UnlockVault.prepareUnwrapCipher(this, it) }
            if (stored != null && cipher != null) {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Desbloquear nota")
                    .setSubtitle(note.title)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Cancelar")
                    .build()
                val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val password = UnlockVault.finishUnwrap(stored, cipher)
                        if (password != null && viewModel.unlock(note.id, password)) {
                            onUnlocked()
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
                })
                runCatching {
                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                }
                return
            }
            // Chave do Keystore indisponível (restauração/troca de aparelho): o embrulho restaurado
            // não serve — cai no caminho recuperável abaixo.
        }

        // Segredo recuperável (restaurado do pacote de segurança): re-embrulha com a biometria
        // atual e desbloqueia — a chave antiga do Keystore não viajou no backup.
        val recoverable = SecurityRecovery.noteSecret(this, note.id)
        if (recoverable != null) {
            rewrapAndUnlockBiometric(note, recoverable, onUnlocked)
            return
        }

        // Nota legada (senha antiga + texto puro): migra para "só o método", re-cifrando o conteúdo
        // com um segredo aleatório e embrulhando-o com a biometria.
        if (note.passwordHash.isBlank()) {
            Toast.makeText(this, "Não foi possível desbloquear com a biometria.", Toast.LENGTH_SHORT).show()
            return
        }
        val secret = NoteEncryption.newSecret()
        val wrapCipher = UnlockVault.prepareWrapCipher(this)
        if (wrapCipher == null) {
            Toast.makeText(this, "Não foi possível usar a biometria.", Toast.LENGTH_SHORT).show()
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear nota")
            .setSubtitle(note.title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar")
            .build()
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (viewModel.migrateLegacyToMethod(note.id, secret) &&
                    UnlockVault.finishWrap(this@MainActivity, note.id, secret, wrapCipher)
                ) {
                    onUnlocked()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
        })
        runCatching {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(wrapCipher))
        }
    }

    /** Re-embrulha um segredo recuperável com a chave biométrica atual e desbloqueia a nota. Usado
     *  quando a chave do Keystore que criou o embrulho original não existe mais (restauração/troca
     *  de aparelho): o conteúdo já está cifrado com [secret], então basta re-embrulhar e desbloquear. */
    private fun rewrapAndUnlockBiometric(note: Note, secret: String, onUnlocked: () -> Unit) {
        val wrapCipher = UnlockVault.prepareWrapCipher(this)
        if (wrapCipher == null) {
            Toast.makeText(this, "Não foi possível usar a biometria.", Toast.LENGTH_SHORT).show()
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear nota")
            .setSubtitle(note.title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar")
            .build()
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (UnlockVault.finishWrap(this@MainActivity, note.id, secret, wrapCipher) &&
                    viewModel.unlock(note.id, secret)
                ) {
                    onUnlocked()
                } else {
                    Toast.makeText(this@MainActivity, "Não foi possível desbloquear com a biometria.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
        })
        runCatching {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(wrapCipher))
        }
    }

    private fun requestPinUnlock(note: Note, onUnlocked: () -> Unit) {
        showPinEntryDialog(
            title = "Desbloquear nota",
            subtitle = note.title,
            fallbackLabel = "Cancelar",
            onPin = { pin ->
                if (UnlockVault.hasWrapped(this, note.id)) {
                    val password = UnlockVault.unwrapWithPin(this, note.id, pin)
                    if (password == null) {
                        false
                    } else if (!viewModel.unlock(note.id, password)) {
                        false
                    } else {
                        onUnlocked()
                        true
                    }
                } else if (note.passwordHash.isNotBlank()) {
                    // Nota legada: confere o PIN do método e migra para "só o método".
                    if (!UnlockVault.verifyPin(this, pin)) {
                        false
                    } else {
                        val secret = NoteEncryption.newSecret()
                        if (!viewModel.migrateLegacyToMethod(note.id, secret)) {
                            false
                        } else if (!UnlockVault.wrapWithPin(this, note.id, secret, pin)) {
                            false
                        } else {
                            onUnlocked()
                            true
                        }
                    }
                } else {
                    false
                }
            },
            onFallback = {},
        )
    }

    private fun requestPatternUnlock(note: Note, onUnlocked: () -> Unit) {
        showPatternEntryDialog(
            title = "Desbloquear nota",
            subtitle = note.title,
            fallbackLabel = "Cancelar",
            onPattern = { pattern ->
                if (UnlockVault.hasWrapped(this, note.id)) {
                    val password = UnlockVault.unwrapWithPattern(this, note.id, pattern)
                    if (password == null) {
                        false
                    } else if (!viewModel.unlock(note.id, password)) {
                        false
                    } else {
                        onUnlocked()
                        true
                    }
                } else if (note.passwordHash.isNotBlank()) {
                    // Nota legada: confere o desenho do método e migra para "só o método".
                    if (!UnlockVault.verifyPattern(this, pattern)) {
                        false
                    } else {
                        val secret = NoteEncryption.newSecret()
                        if (!viewModel.migrateLegacyToMethod(note.id, secret)) {
                            false
                        } else if (!UnlockVault.wrapWithPattern(this, note.id, secret, pattern)) {
                            false
                        } else {
                            onUnlocked()
                            true
                        }
                    }
                } else {
                    false
                }
            },
            onFallback = {},
        )
    }

    private fun requestTotpUnlock(note: Note, onUnlocked: () -> Unit) {
        fun unlockWithCode(code: String): Boolean {
            if (UnlockVault.hasWrapped(this, note.id)) {
                val password = UnlockVault.unwrapWithTotp(this, note.id, code)
                if (password != null && viewModel.unlock(note.id, password)) {
                    onUnlocked()
                    return true
                }
                return false
            }
            if (note.passwordHash.isNotBlank()) {
                // Nota legada: confere o código do método e migra para "só o método".
                if (UnlockVault.verifyTotp(this, code)) {
                    val secret = NoteEncryption.newSecret()
                    if (viewModel.migrateLegacyToMethod(note.id, secret) && UnlockVault.wrapWithTotp(this, note.id, secret)) {
                        onUnlocked()
                        return true
                    }
                }
                return false
            }
            return false
        }
        showPinEntryDialog(
            title = "Desbloquear nota",
            subtitle = note.title,
            fallbackLabel = "Cancelar",
            minLength = 6,
            maxLength = 6,
            lengthHint = "Digite o código de 6 dígitos.",
            errorMessage = "Código incorreto.",
            onPin = { code ->
                if (unlockWithCode(code)) true else false
            },
            onFallback = {},
            extraLabel = "Usar código de recuperação",
            onExtra = {
                showRecoveryCodeDialog(
                    title = "Código de recuperação",
                    subtitle = "Digite um código de recuperação salvo na configuração do TOTP.",
                    onVerified = { code ->
                        if (UnlockVault.hasWrapped(this, note.id)) {
                            val password = UnlockVault.unwrapWithRecoveryCode(this, note.id, code)
                            if (password != null && viewModel.unlock(note.id, password)) {
                                onUnlocked()
                            } else {
                                Toast.makeText(this, "Não foi possível desbloquear com esse código.", Toast.LENGTH_SHORT).show()
                            }
                        } else if (note.passwordHash.isNotBlank()) {
                            // Nota legada: o código já foi conferido e consumido; migra para "só o método".
                            val secret = NoteEncryption.newSecret()
                            if (viewModel.migrateLegacyToMethod(note.id, secret) && UnlockVault.wrapWithTotp(this, note.id, secret)) {
                                onUnlocked()
                            } else {
                                Toast.makeText(this, "Não foi possível desbloquear com esse código.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            },
        )
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
        setSecureFlag(false)
        viewModel.lockAll()
        // Sem nota protegida em tela: remove as cópias decifradas de anexos do cache.
        AttachmentStore.clearCacheDir(this)
        editorReturnScreen = Screen.HOME
        editorReturnNoteId = null
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildContent())
        renderNotes(viewModel.notes.value.orEmpty())
    }

    private fun maybeLockAppIfNeeded() {
        if (isOnboardingComplete() &&
            UnlockVault.isAppLockEnabled(this) &&
            UnlockVault.isAnyMethodAvailable(this)
        ) {
            lockApp()
        }
    }

    private fun lockApp() {
        if (currentScreen == Screen.LOCKED) return
        currentScreen = Screen.LOCKED
        currentViewerNoteId = null
        setSecureFlag(false)
        viewModel.lockAll()
        // App bloqueado: remove as cópias decifradas de anexos do cache.
        AttachmentStore.clearCacheDir(this)
        editorReturnScreen = Screen.HOME
        editorReturnNoteId = null
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildLockScreen())
    }

    private fun finishAppUnlock() {
        // O BiometricPrompt pode disparar onStop/onResume ao abrir; limpa a marca para não
        // re-bloquear imediatamente após o desbloqueio bem-sucedido.
        appWentToBackground = false
        showHome()
    }

    private fun buildLockScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.canvas) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val iconWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        iconWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(palette.accent)
            setPadding(dp(26), dp(26), dp(26), dp(26))
            background = rounded(palette.accentSoft, 48)
        }, LinearLayout.LayoutParams(dp(96), dp(96)))
        content.addView(iconWrap, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(28) })

        content.addView(label("Aplicativo bloqueado", 24, palette.text, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        content.addView(label(
            "Desbloqueie com ${unlockMethodLabel()} para acessar suas notas.",
            15,
            palette.secondaryText,
            false,
        ).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(32) })

        content.addView(MaterialButton(this).apply {
            text = "Desbloquear"
            isAllCaps = false
            textSize = 16f
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { requestAppUnlock { finishAppUnlock() } }
        }, LinearLayout.LayoutParams(MATCH, dp(56)))

        content.addView(
            makeResetAppLink { startResetAppFlow() },
            LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(8) },
        )

        return root
    }

    private fun requestAppUnlock(onUnlocked: () -> Unit) {
        when (UnlockVault.currentMethod(this)) {
            UnlockMethod.NUMERIC_PIN -> showPinEntryDialog(
                title = "Desbloquear",
                subtitle = "Digite seu PIN numérico.",
                fallbackLabel = "Cancelar",
                onPin = { pin ->
                    if (UnlockVault.verifyPin(this, pin)) {
                        onUnlocked()
                        true
                    } else {
                        false
                    }
                },
                onFallback = {},
            )
            UnlockMethod.PATTERN -> showPatternEntryDialog(
                title = "Desbloquear",
                subtitle = "Desenhe seu padrão.",
                fallbackLabel = "Cancelar",
                onPattern = { pattern ->
                    if (UnlockVault.verifyPattern(this, pattern)) {
                        onUnlocked()
                        true
                    } else {
                        false
                    }
                },
                onFallback = {},
            )
            UnlockMethod.BIOMETRIC -> requestBiometricAppUnlock(onUnlocked)
            UnlockMethod.TOTP -> showPinEntryDialog(
                title = "Desbloquear",
                subtitle = "Digite o código atual do seu autenticador.",
                fallbackLabel = "Cancelar",
                minLength = 6,
                maxLength = 6,
                lengthHint = "Digite o código de 6 dígitos.",
                errorMessage = "Código incorreto.",
                onPin = { code ->
                    if (UnlockVault.verifyTotp(this, code)) {
                        onUnlocked()
                        true
                    } else {
                        false
                    }
                },
                onFallback = {},
                extraLabel = "Usar código de recuperação",
                onExtra = {
                    showRecoveryCodeDialog(
                        title = "Código de recuperação",
                        subtitle = "Digite um código de recuperação salvo na configuração do TOTP.",
                        onVerified = { onUnlocked() },
                    )
                },
            )
            // Defensivo: o bloqueio do app só liga com método ativo, então não chega aqui com NONE.
            UnlockMethod.NONE -> onUnlocked()
        }
    }

    private fun requestBiometricAppUnlock(onUnlocked: () -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear aplicativo")
            .setSubtitle("Confirme sua biometria para continuar.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar")
            .build()
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
        })
        runCatching { prompt.authenticate(promptInfo) }
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
        setSecureFlag(false)
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
        setSecureFlag(false)
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
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        content.addView(label(
            "Notas na lixeira são excluídas permanentemente após 30 dias.",
            13,
            palette.secondaryText,
            false,
        ).apply {
            setPadding(0, 0, 0, dp(12))
        })

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
            empty.addView(label("As notas que você excluir aparecem aqui e são apagadas de vez após 30 dias.", 14, palette.secondaryText, false).apply {
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
        renderViewer(note)
    }

    private fun renderViewer(note: Note) {
        setSecureFlag(note.locked)
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildViewerScreen(materialized(note)))
    }

    private fun navigateBackFromEditor() {
        // Anexos novos desta sessão que não foram salvos: apaga os arquivos para não deixar órfãos.
        editorAttachmentNewIds?.forEach { AttachmentStore.delete(applicationContext, it) }
        clearEditorAttachmentState()
        if (editorReturnScreen == Screen.VIEWER && editorReturnNoteId != null) {
            viewModel.notes.value.orEmpty().firstOrNull { it.id == editorReturnNoteId }?.let { openViewer(it) }
                ?: showHome()
        } else {
            showHome()
        }
    }

    private fun clearEditorAttachmentState() {
        editorAttachments = null
        editorAttachmentNewIds = null
        editorAttachmentsRefresh = null
    }

    // ---- Anexos: picker, linhas do editor/viewer e abertura externa ----

    private fun handleDocumentsPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val list = editorAttachments ?: return
        val newIds = editorAttachmentNewIds ?: return
        Thread {
            for (uri in uris) {
                try {
                    val id = UUID.randomUUID().toString()
                    // Limite conferido primeiro pelo metadado (quando o provider informa) e
                    // reforçado no copy (quando o tamanho é desconhecido).
                    val announced = querySize(uri)
                    if (announced > AttachmentStore.MAX_BYTES) {
                        runOnUiThread {
                            Toast.makeText(this, "Arquivo maior que 20 MB — não anexado.", Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }
                    val size = AttachmentStore.store(applicationContext, id, uri, secret = null)
                    val name = queryDisplayName(uri)
                    val mime = contentResolver.getType(uri) ?: inferMime(name)
                    val meta = AttachmentMetadata(id, name, mime, size)
                    list.add(meta)
                    newIds.add(id)
                    runOnUiThread { editorAttachmentsRefresh?.invoke() }
                } catch (e: AttachmentTooLargeException) {
                    runOnUiThread {
                        Toast.makeText(this, "Arquivo maior que 20 MB — não anexado.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao anexar arquivo", e)
                    runOnUiThread {
                        Toast.makeText(this, "Não foi possível anexar o arquivo.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        val resolver = contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
    }

    private fun querySize(uri: Uri): Long {
        val resolver = contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        return -1L
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun inferMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun attachmentIcon(mimeType: String): Int = when {
        mimeType.startsWith("image/") -> R.drawable.ic_image
        mimeType.startsWith("video/") -> R.drawable.ic_video
        mimeType.startsWith("audio/") -> R.drawable.ic_file
        mimeType == "application/pdf" ||
            mimeType.startsWith("text/") ||
            mimeType.contains("document") ||
            mimeType.contains("spreadsheet") ||
            mimeType.contains("presentation") -> R.drawable.ic_document
        else -> R.drawable.ic_file
    }

    /**
     * Miniatura/ícone de um anexo. Imagens são decodificadas em background (com amostragem);
     * [secret] só é necessário quando o arquivo está cifrado (nota protegida) para decifrar antes
     * de decodificar. Se a decodificação falhar, cai de volta para o ícone por tipo.
     */
    private fun attachmentThumbView(attachment: AttachmentMetadata, sizeDp: Int, secret: String?): ImageView {
        val iconId = attachmentIcon(attachment.mimeType)
        val view = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundColor(palette.inputSurface)
            setImageResource(iconId)
            setColorFilter(palette.secondaryText)
        }
        if (attachment.mimeType.startsWith("image/")) {
            loadThumbnailAsync(view, attachment, secret, iconId)
        }
        return view
    }

    private fun loadThumbnailAsync(view: ImageView, attachment: AttachmentMetadata, secret: String?, fallbackIcon: Int) {
        Thread {
            val file = AttachmentStore.openForRead(this, attachment.id, secret)
            val bitmap = file?.takeIf { it.isFile }?.let { decodeSampledBitmap(it, dp(160), dp(160)) }
            runOnUiThread {
                if (bitmap != null) {
                    view.setImageBitmap(bitmap)
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.clearColorFilter()
                } else {
                    view.setImageResource(fallbackIcon)
                    view.setColorFilter(palette.secondaryText)
                }
            }
        }.start()
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqWidth || bounds.outHeight / sample > reqHeight) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun buildEditorAttachmentRow(attachment: AttachmentMetadata, secret: String?, onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackground(rounded(palette.inputSurface, 14))
            setPadding(dp(10), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        row.addView(attachmentThumbView(attachment, 44, secret))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setPadding(dp(12), 0, dp(4), 0) }
        }
        texts.addView(label(attachment.name, 13, palette.text, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(label(formatFileSize(attachment.sizeBytes), 11, palette.secondaryText, false))
        row.addView(texts)
        row.addView(ImageButton(this).apply {
            contentDescription = "Remover anexo"
            setImageResource(R.drawable.ic_delete)
            setColorFilter(palette.secondaryText)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = selectableItemBackgroundBorderless()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onRemove() }
        })
        return row
    }

    private fun buildViewerAttachmentRow(attachment: AttachmentMetadata, noteId: String, noteColor: NoteColor): View {
        val secret = if (viewModel.isUnlocked(noteId)) viewModel.attachmentSecret(noteId) else null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackground(rounded(palette.inputSurface, 14))
            foreground = selectableItemBackgroundRounded()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
            isClickable = true
            isFocusable = true
            setOnClickListener { openAttachment(attachment, noteId) }
        }
        row.addView(attachmentThumbView(attachment, 44, secret))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setPadding(dp(12), 0, dp(8), 0) }
        }
        texts.addView(label(attachment.name, 13, palette.cardText, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(label(formatFileSize(attachment.sizeBytes), 11, palette.cardFooter, false))
        row.addView(texts)
        row.addView(label("Abrir", 12, UiPalette.cardAccent(noteColor, palette.isDark), true))
        return row
    }

    private fun openAttachment(attachment: AttachmentMetadata, noteId: String) {
        val secret = if (viewModel.isUnlocked(noteId)) viewModel.attachmentSecret(noteId) else null
        val file = AttachmentStore.openForRead(this, attachment.id, secret)
        if (file == null || !file.isFile) {
            Toast.makeText(this, "Arquivo do anexo não encontrado.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Nenhum aplicativo instalado para abrir este arquivo.", Toast.LENGTH_SHORT).show()
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
        // Anexos só são exibidos quando a nota não está bloqueada (ou está desbloqueada na sessão):
        // nada de um anexo de nota protegida vaza enquanto o conteúdo não é liberado.
        if (note.attachments.isNotEmpty() && (!note.locked || viewModel.isUnlocked(note.id))) {
            noteSurface.addView(label("Anexos", 12, UiPalette.cardAccent(note.color, palette.isDark), true).apply {
                setPadding(0, dp(18), 0, dp(6))
            })
            note.attachments.forEach { attachment ->
                noteSurface.addView(buildViewerAttachmentRow(attachment, note.id, note.color))
            }
        }
        noteSurface.addView(label(formatDate(note.updatedAt), 11, palette.cardFooter, false).apply {
            setPadding(0, dp(20), 0, 0)
        })
        content.addView(noteSurface, LinearLayout.LayoutParams(MATCH, WRAP))

        installSafeInsets(root, content, 20, 32)
        return root
    }

    private fun buildEditorScreen(existing: Note?): View {
        setSecureFlag(existing?.locked == true)
        val source = existing?.let { materialized(it) }
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
        // Linha do tipo de nota com o clipe de anexo no canto direito — mesmo plano da lista.
        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        typeRow.addView(typeGroup, LinearLayout.LayoutParams(0, WRAP, 1f))
        typeRow.addView(ImageButton(this).apply {
            contentDescription = "Anexar arquivo"
            setImageResource(R.drawable.ic_attach)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = rounded(palette.fab, 14)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { openDocumentLauncher.launch(arrayOf("*/*")) }
        })
        content.addView(typeRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        val titleInput = textInput("Título", existing?.title.orEmpty(), false)
        val bodyInput = textInput(
            if (existing?.type == NoteType.CHECKLIST) "Um item por linha" else "Escreva o que está na sua cabeça",
            source?.let {
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
        val protectBox = CheckBox(this).apply {
            text = "Proteger nota"
            textSize = 14f
            setTextColor(palette.dialogText)
            buttonTintList = checkboxTint()
            isChecked = protectEnabled
        }
        val protectSummary = label("", 12, palette.secondaryText, false)
        fun refreshProtectSummary() {
            protectSummary.text = if (protectEnabled) {
                "Protegida por ${unlockMethodLabel()}. Toque para desproteger."
            } else {
                "Conteúdo visível para quem abrir o app."
            }
        }
        val protectionOption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(protectBox, LinearLayout.LayoutParams(MATCH, dp(48)))
            addView(protectSummary, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        content.addView(protectionOption, LinearLayout.LayoutParams(MATCH, WRAP))
        fun enableProtection() {
            if (!UnlockVault.isAnyMethodAvailable(this@MainActivity)) {
                protectBox.isChecked = false
                protectEnabled = false
                refreshProtectSummary()
                showConfirmDialog(
                    title = "Proteção não configurada",
                    message = "Escolha primeiro um método de proteção (biometria, desenho ou senha numérica) em Segurança.",
                    confirmLabel = "Ir para Segurança",
                    destructive = false,
                ) {
                    showSecuritySettings()
                }
                return
            }
            protectEnabled = true
            refreshProtectSummary()
        }
        protectBox.setOnClickListener {
            if (protectBox.isChecked) {
                enableProtection()
            } else {
                protectEnabled = false
                refreshProtectSummary()
            }
        }
        protectionOption.setOnClickListener { protectBox.toggle() }
        refreshProtectSummary()

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

        // ---- Anexos ----
        val attachmentList = existing?.attachments?.toMutableList() ?: mutableListOf()
        editorAttachments = attachmentList
        editorAttachmentNewIds = mutableListOf()
        val attachmentsSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderAttachments() {
            attachmentsSection.removeAllViews()
            // Sem anexos a seção fica vazia (e ocupa zero): o clipe na barra superior é o affordance.
            if (attachmentList.isEmpty()) {
                attachmentsSection.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                return
            }
            attachmentsSection.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
            attachmentsSection.addView(label("Anexos", 14, palette.text, true))
            val secret = if (protectEnabled) existing?.id?.let { viewModel.attachmentSecret(it) } else null
            attachmentList.forEachIndexed { index, attachment ->
                attachmentsSection.addView(buildEditorAttachmentRow(attachment, secret) {
                    showConfirmDialog(
                        title = "Remover anexo?",
                        message = "\"${attachment.name}\" será removido desta nota. A mudança vale quando você salvar.",
                        confirmLabel = "Remover",
                        destructive = true,
                    ) {
                        attachmentList.removeAt(index)
                        renderAttachments()
                    }
                })
            }
        }
        editorAttachmentsRefresh = { renderAttachments() }
        renderAttachments()
        content.addView(attachmentsSection, LinearLayout.LayoutParams(MATCH, WRAP))

        fun commit(title: String, text: String, secret: String, idOverride: String?) {
            val type = if (checklistType.isChecked) NoteType.CHECKLIST else NoteType.TEXT
            val reminder = reminderSelection.schedule
            val attachments = editorAttachments.orEmpty()
            viewModel.upsert(existing, title, text, type, selectedColor, protectEnabled, secret, reminder, attachments, idOverride)
            clearEditorAttachmentState()
            if (editorReturnScreen == Screen.VIEWER && existing != null) {
                viewModel.notes.value.orEmpty().firstOrNull { it.id == existing.id }?.let { openViewer(it) }
                    ?: showHome()
            } else {
                showHome()
            }
        }

        saveButton.setOnClickListener {
            val title = titleInput.text?.toString().orEmpty().trim()
            val text = bodyInput.text?.toString().orEmpty().trim()
            if (title.isBlank() && text.isBlank()) {
                titleInput.error = "Dê um nome ou escreva alguma coisa"
                return@setOnClickListener
            }
            val reminder = reminderSelection.schedule
            if (reminder != null && reminder.nextOccurrenceOrNull(java.time.ZonedDateTime.now()) == null) {
                Toast.makeText(this, "Escolha uma data e horário futuros para o lembrete.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Protegendo pela primeira vez: gera uma chave aleatória e a embrulha no método global.
            val newlyProtecting = protectEnabled && (existing == null || !existing.locked)
            if (newlyProtecting) {
                if (!UnlockVault.isAnyMethodAvailable(this)) {
                    showConfirmDialog(
                        title = "Proteção não configurada",
                        message = "Escolha primeiro um método de proteção em Segurança.",
                        confirmLabel = "Ir para Segurança",
                        destructive = false,
                    ) { showSecuritySettings() }
                    return@setOnClickListener
                }
                val noteId = existing?.id ?: UUID.randomUUID().toString()
                val secret = NoteEncryption.newSecret()
                wrapSecretForNote(noteId, secret) {
                    commit(title, text, secret, noteId)
                }
            } else {
                commit(title, text, "", null)
            }
        }

        installSafeInsets(root, content, 20, 32)
        return root
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

    /**
     * Modal exibido ao tentar visualizar uma nota protegida com nenhum método de desbloqueio ativo:
     * informa que é preciso ativar um método e leva às configurações de segurança.
     */
    private fun showNoUnlockMethodModal() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label("Método de desbloqueio necessário", 20, palette.dialogText, true))
        panel.addView(label(
            "Nenhum método de desbloqueio está ativo. Ative um método (biometria, desenho, PIN ou " +
                "código TOTP) nas configurações de segurança para visualizar notas protegidas.",
            15,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(12), 0, dp(20)) })
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttons.addView(MaterialButton(this).apply {
            text = "Fechar"
            isAllCaps = false
            setTextColor(palette.secondaryText)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(8) })
        buttons.addView(MaterialButton(this).apply {
            text = "Ir para Segurança"
            isAllCaps = false
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                dialog.dismiss()
                showSecuritySettings()
            }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, dp(44)))
        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = true,
        action: () -> Unit,
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        panel.addView(dialogScroll(label(message, 15, palette.secondaryText, false).apply {
            setPadding(0, dp(12), 0, dp(20))
        }))
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
            setTextColor(if (destructive) Color.WHITE else palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(
                if (destructive) {
                    if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845")
                } else {
                    palette.fab
                },
            )
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
            "\"${note.title}\" será movida para a lixeira e excluída de vez após 30 dias.",
            "Mover",
        ) {
            // Excluir/mover para a lixeira não exige desbloquear a nota: uma nota antiga cuja
            // senha foi esquecida deve continuar sendo possível de remover. O conteúdo continua
            // cifrado dentro da lixeira até a exclusão definitiva.
            viewModel.trash(note.id)
            onTrashed?.invoke()
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

    /**
     * Escape final de segurança ("Redefinir aplicativo"), acessível de todas as confirmações de
     * credencial. Apaga TUDO (notas, métodos de desbloqueio, códigos de recuperação e backup) —
     * por isso exige primeiro um aviso explícito e depois digitar uma palavra aleatória sorteada.
     * Não é um bypass: quem reseta perde os dados.
     */
    private fun startResetAppFlow() {
        val word = AppReset.randomConfirmationWord()
        showConfirmDialog(
            "Redefinir aplicativo",
            "Todos os seus dados serão apagados permanentemente: notas, métodos de desbloqueio, " +
                "códigos de recuperação e backup. Esta ação não pode ser desfeita.",
            "Continuar",
            destructive = true,
        ) {
            showResetWordDialog(word)
        }
    }

    private fun showResetWordDialog(word: String) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label("Digite a palavra", 20, palette.dialogText, true))
        panel.addView(label(
            "Para confirmar a exclusão de todos os dados, digite a palavra exibida abaixo.",
            14,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        panel.addView(TextView(this).apply {
            text = word
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(palette.dialogText)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(palette.dialogControlSurface, 10)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(palette.dialogText)
            hint = "Digite a palavra"
            setHintTextColor(palette.mutedText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.dialogControlSurface, 12)
        }
        panel.addView(input, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
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
            text = "Apagar tudo"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845")
            )
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                if (AppReset.matches(word, input.text.toString())) {
                    dialog.dismiss()
                    performReset()
                } else {
                    Toast.makeText(this@MainActivity, "Palavra incorreta.", Toast.LENGTH_SHORT).show()
                    input.text.clear()
                }
            }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(10) })

        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun performReset() {
        // Cada passo isolado: uma falha pontual não impede o reset de seguir até a tela inicial.
        runCatching { viewModel.repositoryForSync.clearAllData() }
        runCatching { UnlockVault.resetAll(this) }
        runCatching { SecurityRecovery.removeKey(this) }
        runCatching { SecurityRecovery.clearNoteSecrets(this) }
        runCatching { cloudSettingsStore.clear() }
        runCatching { googleSignInClient.signOut() }
        cloudSyncState = CloudSyncState(CloudSyncPhase.DISCONNECTED)
        runCatching { ReminderScheduler.reconcile(this, emptyList()) }
        runCatching { AttachmentStore.deleteAll(this) }
        runCatching { getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).edit().clear().commit() }
        runCatching { getSharedPreferences("noteharbor.preferences", MODE_PRIVATE).edit().clear().commit() }
        runCatching { viewModel.reloadFromRepository() }
        currentScreen = Screen.WELCOME
        setScreenContent(buildWelcomeScreen())
    }

    /** Botão-texto "Não tem mais acesso ao desbloqueio? Redefinir aplicativo" das confirmações. */
    private fun makeResetAppLink(onClick: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = "Não tem mais acesso ao desbloqueio? Redefinir aplicativo"
        isAllCaps = false
        textSize = 15f
        setTextColor(palette.secondaryText)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { onClick() }
    }

    /** Sucesso de uma sincronização: atualiza estado, recarrega as notas e, se o backup trouxe a
     *  configuração de segurança e este aparelho está sem nenhuma, restaura o acesso.
     *  Primeiro tenta o envelope local (mesmo aparelho, sem senha); se for um envelope de senha de
     *  recuperação, pede a senha; envelope local de outro aparelho não tem como ser decifrado aqui. */
    private fun handleCloudSyncSuccess(result: CloudSyncResult, toast: String?) {
        cloudSyncState = CloudSyncState(CloudSyncPhase.SYNCED, result.noteCount)
        viewModel.reloadFromRepository()
        if (result.securityRestorePending) {
            val envelope = result.securityPayload.orEmpty()
            if (SecurityRecovery.tryDeviceRestore(this, envelope)) {
                // Restaurou sozinho com a chave do aparelho (pós-limpar dados no mesmo aparelho).
                viewModel.reloadFromRepository()
                if (currentScreen == Screen.SECURITY) {
                    palette = UiPalette.from(this)
                    setScreenContent(buildSecurityScreen())
                }
            } else if (SecurityRecovery.isPassphraseEnvelope(envelope)) {
                showRecoveryRestoreDialog(envelope)
            } else {
                Toast.makeText(
                    this,
                    "Notas protegidas não puderam ser restauradas neste aparelho.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    /** Modal de um campo de senha, no estilo dos demais diálogos do app. */
    private fun passwordDialog(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: (Dialog, String) -> Unit,
    ): Dialog {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        panel.addView(label(message, 14, palette.secondaryText, false).apply { setPadding(0, dp(8), 0, dp(4)) })

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(palette.dialogText)
            setHintTextColor(palette.mutedText)
            hint = title
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.dialogControlSurface, 12)
        }
        panel.addView(input, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
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
            setTextColor(palette.dialogButton)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onConfirm(dialog, input.text.toString()) }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(10) })

        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    private fun showRecoveryPassphraseSetup() {
        if (SecurityRecovery.hasKey(this)) {
            showCurrentRecoveryPassphraseDialog()
        } else {
            showNewRecoveryPassphraseDialog()
        }
    }

    /** Pede a senha atual para autorizar a alteração. */
    private fun showCurrentRecoveryPassphraseDialog() {
        val dialog = passwordDialog(
            title = "Senha atual",
            message = "Digite a senha de recuperação atual para alterá-la.",
            confirmLabel = "Continuar",
            onConfirm = { dlg, senha ->
                if (SecurityRecovery.verifyPassphrase(this, senha)) {
                    dlg.dismiss()
                    showNewRecoveryPassphraseDialog()
                } else {
                    Toast.makeText(this, "Senha de recuperação incorreta.", Toast.LENGTH_SHORT).show()
                }
            },
        )
        dialog.show()
    }

    /** Define ou altera a senha de recuperação: nova senha + confirmação. */
    private fun showNewRecoveryPassphraseDialog() {
        val dialog = Dialog(this)
        val changing = SecurityRecovery.hasKey(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(
            if (changing) "Alterar senha de recuperação" else "Definir senha de recuperação",
            20, palette.dialogText, true,
        ))
        panel.addView(label(
            "Esta senha protege a configuração de segurança dentro do backup no Google. Após limpar " +
                "os dados do app ou trocar de celular, digite-a ao sincronizar para recuperar o acesso " +
                "às notas protegidas.",
            14, palette.secondaryText, false,
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        fun field(hintText: String): EditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(palette.dialogText)
            setHintTextColor(palette.mutedText)
            hint = hintText
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.dialogControlSurface, 12)
        }
        val nova = field("Nova senha (mín. 8 caracteres)")
        val confirma = field("Confirmar nova senha")
        panel.addView(nova, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) })
        panel.addView(confirma, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })
        panel.addView(label(
            "Se esquecer esta senha, as notas protegidas não poderão ser recuperadas.",
            13, palette.secondaryText, false,
        ).apply { setPadding(0, dp(8), 0, 0) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
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
            text = "Continuar"
            isAllCaps = false
            setTextColor(palette.dialogButton)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                val pass = nova.text.toString()
                when {
                    pass.length < SecurityRecovery.MIN_PASSPHRASE ->
                        Toast.makeText(this@MainActivity, "A senha deve ter pelo menos 8 caracteres.", Toast.LENGTH_SHORT).show()
                    pass != confirma.text.toString() ->
                        Toast.makeText(this@MainActivity, "As senhas não coincidem.", Toast.LENGTH_SHORT).show()
                    SecurityRecovery.setKey(this@MainActivity, pass) -> {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Senha de recuperação definida.", Toast.LENGTH_SHORT).show()
                        if (currentScreen == Screen.SECURITY) {
                            palette = UiPalette.from(this@MainActivity)
                            setScreenContent(buildSecurityScreen())
                        }
                    }
                }
            }
        }, LinearLayout.LayoutParams(WRAP, dp(44)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(10) })

        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    /** Pedido de restauração da segurança vindo do backup (disparado após sincronizar). */
    private fun showRecoveryRestoreDialog(envelope: String) {
        if (recoveryRestoreDialogShowing || isFinishing || isDestroyed) return
        recoveryRestoreDialogShowing = true
        val dialog = passwordDialog(
            title = "Restaurar segurança",
            message = "Seu backup contém a configuração de segurança deste app. Digite a senha de " +
                "recuperação para restaurar o acesso às notas protegidas.",
            confirmLabel = "Restaurar",
            onConfirm = { dlg, senha ->
                if (SecurityRecovery.restore(this, senha, envelope)) {
                    dlg.dismiss()
                    Toast.makeText(this, "Segurança restaurada do backup.", Toast.LENGTH_SHORT).show()
                    viewModel.reloadFromRepository()
                    if (currentScreen == Screen.SECURITY) {
                        palette = UiPalette.from(this)
                        setScreenContent(buildSecurityScreen())
                    }
                } else {
                    Toast.makeText(this, "Senha de recuperação incorreta.", Toast.LENGTH_SHORT).show()
                }
            },
        )
        dialog.setOnDismissListener { recoveryRestoreDialogShowing = false }
        dialog.show()
    }

    private fun showSecuritySettings() {
        currentScreen = Screen.SECURITY
        setSecureFlag(false)
        palette = UiPalette.from(this)
        configureSystemBars()
        setScreenContent(buildSecurityScreen())
    }

    private fun buildSecurityScreen(): View {
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
        header.addView(label("Segurança", 22, palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(8)
        })
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        body.addView(label("Acesso", 15, palette.secondaryText, true))
        val appLockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = rounded(palette.dialogSurface, 18)
        }
        val appLockCheck = CheckBox(this).apply {
            text = "Exigir desbloqueio ao abrir o app"
            textSize = 15f
            setTextColor(palette.text)
            buttonTintList = checkboxTint()
            isChecked = UnlockVault.isAppLockEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!UnlockVault.isAnyMethodAvailable(this@MainActivity)) {
                        isChecked = false
                        showConfirmDialog(
                            title = "Método não configurado",
                            message = "Escolha primeiro um método de desbloqueio (biometria, desenho, PIN ou código TOTP).",
                            confirmLabel = "Entendi",
                            destructive = false,
                        ) {}
                        return@setOnCheckedChangeListener
                    }
                    UnlockVault.setAppLockEnabled(this@MainActivity, true)
                    Toast.makeText(this@MainActivity, "O app pedirá desbloqueio ao abrir.", Toast.LENGTH_SHORT).show()
                } else {
                    UnlockVault.setAppLockEnabled(this@MainActivity, false)
                }
            }
        }
        appLockRow.addView(appLockCheck, LinearLayout.LayoutParams(MATCH, dp(48)))
        body.addView(appLockRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        body.addView(label(
            "Bloqueia o app ao iniciar ou voltar do segundo plano. Use o método escolhido abaixo para desbloquear.",
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(dp(4), 0, 0, dp(20)) })

        body.addView(label("Método de desbloqueio", 15, palette.secondaryText, true))
        body.addView(label(
            "Toda nota protegida é desbloqueada com este método. Escolha entre biometria, desenho, PIN numérico ou código TOTP.",
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(8), 0, dp(16)) })

        val indicators = mutableMapOf<UnlockMethod, TextView>()
        val cards = mutableMapOf<UnlockMethod, LinearLayout>()
        // Sem método configurado (instalação nova, reset de fábrica ou método desativado), nenhum
        // indicador fica marcado — o default BIOMETRIC do currentMethod não deve parecer selecionado.
        var selected = UnlockVault.configuredMethod(this)?.takeIf { UnlockVault.isMethodAvailable(this, it) }

        fun refresh() {
            indicators.forEach { (method, indicator) ->
                val isSelected = method == selected
                if (isSelected) {
                    indicator.text = "✓"
                    indicator.setTextColor(palette.fabIcon)
                    indicator.setBackgroundDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(palette.accent)
                    })
                } else {
                    indicator.text = ""
                    indicator.setBackgroundDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), palette.mutedText)
                    })
                }
            }
            cards.forEach { (method, card) ->
                if (method == selected) {
                    card.background = outlined(palette.dialogSurface, palette.accent, 18).apply {
                        setStroke(dp(2), palette.accent)
                    }
                } else {
                    card.background = rounded(palette.dialogSurface, 18)
                }
            }
        }

        fun apply(method: UnlockMethod) {
            selected = method
            UnlockVault.setMethod(this, method)
            refresh()
        }

        // Cada método é um botão que abre o modal de ativação/configuração/desativação.
        fun methodCard(method: UnlockMethod, title: String, subtitle: String): LinearLayout {
            val indicator = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 22f
                isClickable = false
                isFocusable = false
            }
            indicators[method] = indicator
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(palette.dialogSurface, 18)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showMethodDetailModal(
                        method,
                        onActivated = { apply(it) },
                        onDeactivated = {
                            selected = null
                            appLockCheck.isChecked = false
                            refresh()
                        },
                    )
                }
            }
            cards[method] = row
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(title, 16, palette.text, true))
            texts.addView(label(subtitle, 13, palette.secondaryText, false).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(indicator, LinearLayout.LayoutParams(dp(36), dp(36)))
            return row
        }

        body.addView(
            methodCard(UnlockMethod.BIOMETRIC, "Biometria", "Digital ou rosto."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) },
        )
        body.addView(
            methodCard(UnlockMethod.PATTERN, "Desenho", "Padrão 3x3 desenhado por você, dentro do app."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) },
        )
        body.addView(
            methodCard(UnlockMethod.NUMERIC_PIN, "Senha numérica", "PIN de 4 a 8 dígitos, sem o teclado do sistema."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) },
        )
        body.addView(
            methodCard(
                UnlockMethod.TOTP,
                "Código TOTP",
                "Código de 6 dígitos de um app de autenticação (Google Authenticator, Authy…).",
            ),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) },
        )

        body.addView(label("Backup da segurança", 15, palette.secondaryText, true).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        body.addView(label(
            "Protege a configuração de segurança (método de desbloqueio e acesso às notas protegidas) " +
                "dentro do backup no Google. Depois de limpar os dados do app ou trocar de celular, " +
                "digite a senha ao sincronizar.",
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, 0, 0, dp(8)) })

        val recoverySet = SecurityRecovery.hasKey(this)
        val recoveryCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(palette.dialogSurface, 18)
        }
        val recoveryTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recoveryTexts.addView(label("Senha de recuperação", 15, palette.text, true))
        recoveryTexts.addView(label(
            if (recoverySet) {
                "Definida — a segurança do backup está protegida."
            } else {
                "Não definida — notas protegidas não voltam após limpar os dados."
            },
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(3), 0, 0) })
        recoveryCard.addView(recoveryTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(recoveryCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        body.addView(MaterialButton(this).apply {
            text = if (recoverySet) "Alterar senha de recuperação" else "Definir senha de recuperação"
            isAllCaps = false
            textSize = 15f
            setTextColor(palette.dialogButton)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            strokeColor = ColorStateList.valueOf(palette.dialogControlBorder)
            strokeWidth = dp(1)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showRecoveryPassphraseSetup() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(12) })

        body.addView(MaterialButton(this).apply {
            text = "Redefinir aplicativo"
            isAllCaps = false
            textSize = 15f
            setTextColor(if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845"))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { startResetAppFlow() }
        }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(4) })

        refresh()

        installSafeInsets(root, content, 20, 32)
        return root
    }

    /**
     * Modal de ativação de um método de desbloqueio: um interruptor liga o método e, quando
     * preciso, a configuração correspondente é exibida (desenho, PIN ou código TOTP). Ao ativar
     * um método com notas protegidas no método anterior, elas são re-embrulhadas (migração).
     */
    private fun showMethodDetailModal(
        method: UnlockMethod,
        onActivated: (UnlockMethod) -> Unit,
        onDeactivated: () -> Unit,
    ) {
        val (title, switchLabel) = when (method) {
            UnlockMethod.BIOMETRIC -> "Biometria" to "Ativar biometria"
            UnlockMethod.PATTERN -> "Desenho" to "Ativar desenho"
            UnlockMethod.NUMERIC_PIN -> "Senha numérica" to "Ativar senha numérica"
            UnlockMethod.TOTP -> "Código TOTP" to "Ativar código TOTP"
            UnlockMethod.NONE -> "—" to "—"
        }

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        panel.addView(label(
            when (method) {
                UnlockMethod.BIOMETRIC -> "Digital ou rosto registrada no aparelho."
                UnlockMethod.PATTERN -> "Padrão 3x3 desenhado por você, dentro do app."
                UnlockMethod.NUMERIC_PIN -> "PIN de 4 a 8 dígitos, sem o teclado do sistema."
                UnlockMethod.TOTP -> "Código de 6 dígitos de um app de autenticação (Google Authenticator, Authy…)."
                UnlockMethod.NONE -> ""
            },
            14,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = rounded(palette.dialogControlSurface, 14)
        }
        switchRow.addView(label(switchLabel, 15, palette.dialogText, false), LinearLayout.LayoutParams(0, WRAP, 1f))
        val switchView = SwitchCompat(this)
        switchRow.addView(switchView, LinearLayout.LayoutParams(WRAP, WRAP))
        panel.addView(switchRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })

        val statusText = label("", 13, palette.secondaryText, false).apply { setPadding(0, dp(14), 0, dp(8)) }
        panel.addView(statusText, LinearLayout.LayoutParams(MATCH, WRAP))
        val configButton = MaterialButton(this).apply {
            isAllCaps = false
            textSize = 15f
            setTextColor(palette.dialogButton)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
        }
        panel.addView(configButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(4) })

        val deactivateButton = MaterialButton(this).apply {
            text = "Desativar método"
            isAllCaps = false
            textSize = 15f
            setTextColor(if (palette.isDark) Color.parseColor("#D96B68") else Color.parseColor("#C44845"))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
        }
        panel.addView(deactivateButton, LinearLayout.LayoutParams(MATCH, dp(44)).apply { bottomMargin = dp(4) })

        panel.addView(MaterialButton(this).apply {
            text = "Concluir"
            isAllCaps = false
            textSize = 16f
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })

        var syncing = false

        fun syncConfig() {
            when (method) {
                UnlockMethod.BIOMETRIC -> {
                    statusText.text = if (UnlockVault.isMethodAvailable(this@MainActivity, method)) {
                        "Biometria disponível neste aparelho."
                    } else {
                        "Nenhuma biometria registrada neste aparelho."
                    }
                    configButton.visibility = View.GONE
                }
                UnlockMethod.PATTERN -> {
                    val has = UnlockVault.hasPattern(this@MainActivity)
                    statusText.text = if (has) "Desenho definido." else "Nenhum desenho definido."
                    configButton.text = if (has) "Alterar desenho" else "Definir desenho"
                    configButton.visibility = View.VISIBLE
                }
                UnlockMethod.NUMERIC_PIN -> {
                    val has = UnlockVault.hasPin(this@MainActivity)
                    statusText.text = if (has) "PIN numérico definido." else "Nenhum PIN numérico definido."
                    configButton.text = if (has) "Alterar PIN" else "Definir PIN"
                    configButton.visibility = View.VISIBLE
                }
                UnlockMethod.TOTP -> {
                    val has = UnlockVault.hasTotp(this@MainActivity)
                    statusText.text = if (has) "Código TOTP configurado." else "Nenhum código TOTP configurado."
                    configButton.text = if (has) "Alterar código TOTP" else "Configurar código TOTP"
                    configButton.visibility = View.VISIBLE
                }
                UnlockMethod.NONE -> { }
            }
        }

        fun sync() {
            syncing = true
            switchView.isChecked = UnlockVault.configuredMethod(this@MainActivity) == method
            syncing = false
            syncConfig()
            deactivateButton.visibility =
                if (UnlockVault.configuredMethod(this@MainActivity) == method) View.VISIBLE else View.GONE
        }

        // Troca o método. A ordem é: aviso explicando qual método está selecionado → resolver esse
        // método (prova a credencial e recupera as senhas embrulhadas, se houver) → só então
        // configurar/ativar o escolhido. Nunca se configura o novo método antes de resolver o atual.
        // Exceções sem resolução:
        //  - reativar o próprio método desativado: credencial e embrulhos continuam na chave dele —
        //    ativa direto;
        //  - com nenhum método ativo e notas embrulhadas, a desativação já resolveu o método e
        //    guardou as senhas em memória (pendingHandoffSecrets). Ativar OUTRO método usa essas
        //    senhas para re-embrulhar sob o novo método, sem pedir o anterior de novo. Se o cache
        //    sumiu (app reiniciado), cai no aviso + resolução abaixo.
        fun activate() {
            // configuredMethod (não currentMethod): numa instalação nova o padrão BIOMETRIC não é um
            // método configurado — ativar qualquer método entra direto, sem "resolver a biometria".
            val current = UnlockVault.configuredMethod(this@MainActivity)
            val wrappedIds = UnlockVault.wrappedNoteIds(this@MainActivity)
            if (method == current) {
                sync()
                return
            }
            if (method == UnlockMethod.BIOMETRIC && !UnlockVault.isMethodAvailable(this@MainActivity, method)) {
                Toast.makeText(this@MainActivity, "Este método não está disponível neste aparelho.", Toast.LENGTH_SHORT).show()
                sync()
                return
            }
            val origin = if (current == UnlockMethod.NONE) UnlockVault.lastActiveMethod(this@MainActivity) else current
            if (origin == null || origin == method) {
                // Reativar o próprio método desativado: credencial e embrulhos continuam na chave
                // dele — ativa direto, sem provar nada.
                finishSwitchTo(method, emptyMap(), onApplied = {
                    onActivated(method)
                    dialog.dismiss()
                }, onCancel = { sync() })
                return
            }
            // Com nenhum método ativo e notas embrulhadas, usa as senhas recuperadas na desativação
            // (ainda nesta sessão) para migrar para o novo método, sem resolver o anterior de novo.
            if (current == UnlockMethod.NONE && wrappedIds.isNotEmpty()) {
                val cached = pendingHandoffSecrets
                if (cached != null && cached.keys.toSet() == wrappedIds.toSet()) {
                    pendingHandoffSecrets = null
                    finishSwitchTo(method, cached, onApplied = {
                        onActivated(method)
                        dialog.dismiss()
                    }, onCancel = { sync() })
                    return
                }
            }
            if (current == UnlockMethod.NONE && wrappedIds.isEmpty()) {
                // Todos os métodos desativados e nenhuma nota embrulhada sob o último método: nada a
                // provar nem migrar — o novo método entra direto.
                finishSwitchTo(method, emptyMap(), onApplied = {
                    onActivated(method)
                    dialog.dismiss()
                }, onCancel = { sync() })
                return
            }
            // Aviso + resolução do método ativo (ou do último, quando nenhum está ativo) antes da
            // troca. Com notas embrulhadas, a resolução recupera as senhas para re-embrulhar; sem
            // notas, prova só a identidade (não se troca o bloqueio sem resolver o método atual).
            showConfirmDialog(
                title = "Trocar método de desbloqueio",
                message = "O método selecionado é ${unlockMethodLabel(origin)}. Para trocar para " +
                    "${unlockMethodLabel(method)}, primeiro resolva ${unlockMethodLabel(origin)}.",
                confirmLabel = "Continuar",
                destructive = false,
            ) {
                fun proceed(secrets: Map<String, String>) {
                    finishSwitchTo(method, secrets, onApplied = {
                        onActivated(method)
                        dialog.dismiss()
                    }, onCancel = { sync() })
                }
                if (wrappedIds.isNotEmpty()) {
                    recoverWrappedSecrets(origin, wrappedIds, onCancel = { sync() }) { secrets -> proceed(secrets) }
                } else {
                    confirmCurrentMethod(onSuccess = { proceed(emptyMap()) }, onCancel = { sync() })
                }
            }
        }

        // Desativar o método exige: avisar as consequências e resolver o método atual. Só o dono
        // consegue remover o bloqueio; quem está de fora não tem como (precisa da credencial).
        // A mesma resolução também move as senhas embrulhadas para a chave de handoff do aparelho,
        // para que ativar outro método depois não volte a pedir este método (a desativação já
        // resolveu o método — não faz sentido resolver de novo).
        fun deactivateMethodWithProof() {
            showConfirmDialog(
                title = "Desativar método",
                message = "Nenhum método de desbloqueio ficará ativo. As notas protegidas continuarão " +
                    "protegidas e só poderão ser visualizadas depois que você ativar um método novamente — " +
                    "sem precisar resolver este método de novo. O bloqueio do app ao abrir será desligado.",
                confirmLabel = "Continuar",
                destructive = true,
            ) {
                // Só é visível quando um método está de fato configurado; same valor de currentMethod.
                val current = UnlockVault.configuredMethod(this@MainActivity) ?: UnlockVault.currentMethod(this@MainActivity)
                val wrappedIds = UnlockVault.wrappedNoteIds(this@MainActivity)
                recoverWrappedSecrets(
                    current,
                    wrappedIds,
                    subtitle = "Resolva o método atual para desativá-lo e manter as notas protegidas.",
                    onCancel = {},
                ) { secrets ->
                    if (wrappedIds.isNotEmpty()) {
                        pendingHandoffSecrets = LinkedHashMap(secrets)
                    }
                    UnlockVault.deactivateMethod(this@MainActivity)
                    onDeactivated()
                    dialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "Método desativado. Nenhum método de desbloqueio está ativo.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        switchView.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (checked) {
                activate()
            } else if (UnlockVault.configuredMethod(this@MainActivity) == method) {
                // Desativar o método exige aviso + prova; o caminho é o botão "Desativar método".
                Toast.makeText(
                    this@MainActivity,
                    "Use \"Desativar método\" abaixo para remover o método de desbloqueio.",
                    Toast.LENGTH_SHORT,
                ).show()
                sync()
            }
        }

        configButton.setOnClickListener {
            when (method) {
                UnlockMethod.PATTERN -> startPatternSetup(onDone = { _ -> syncConfig() }, onCancel = { })
                UnlockMethod.NUMERIC_PIN -> startPinSetup(onDone = { _ -> syncConfig() }, onCancel = { })
                UnlockMethod.TOTP -> startTotpSetup(onDone = { syncConfig() }, onCancel = { })
                UnlockMethod.BIOMETRIC -> { }
                UnlockMethod.NONE -> { }
            }
        }

        deactivateButton.setOnClickListener { deactivateMethodWithProof() }

        sync()

        // Painel alto (descrição + switch + botões): numa tela pequena o conjunto rola em vez de
        // cortar a frase de baixo; numa tela normal fica como está (a rolagem colapsa na altura).
        val (root, dialogHeight) = boundedDialogRoot(panel)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), dialogHeight)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * Exige resolver o método atualmente selecionado antes de permitir trocá-lo. Usado quando não
     * há notas embrulhadas a migrar (nesse caso a migração já pede a credencial atual no
     * `recoverWrappedSecrets`) — sem isto, qualquer um com o aparelho na mão trocaria o método de
     * desbloqueio sem provar o atual. [onCancel] é chamado se o usuário desistir de resolver.
     */
    private fun confirmCurrentMethod(onSuccess: () -> Unit, onCancel: () -> Unit = {}) {
        val current = UnlockVault.currentMethod(this)
        // Método sem credencial real (ex.: padrão, com biometria não cadastrada): nada a resolver.
        if (!UnlockVault.isMethodAvailable(this, current)) {
            onSuccess()
            return
        }
        when (current) {
            UnlockMethod.NUMERIC_PIN -> showPinEntryDialog(
                title = "Confirmar PIN",
                subtitle = "Digite seu PIN para trocar o método.",
                fallbackLabel = "Cancelar",
                onPin = { pin ->
                    if (UnlockVault.verifyPin(this, pin)) {
                        onSuccess()
                        true
                    } else {
                        false
                    }
                },
                onFallback = onCancel,
            )
            UnlockMethod.PATTERN -> showPatternEntryDialog(
                title = "Confirmar desenho",
                subtitle = "Desenhe seu padrão para trocar o método.",
                fallbackLabel = "Cancelar",
                onPattern = { pattern ->
                    if (UnlockVault.verifyPattern(this, pattern)) {
                        onSuccess()
                        true
                    } else {
                        false
                    }
                },
                onFallback = onCancel,
            )
            UnlockMethod.BIOMETRIC -> {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirmar biometria")
                    .setSubtitle("Confirme sua identidade para trocar o método.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Cancelar")
                    .build()
                val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
                })
                runCatching { prompt.authenticate(promptInfo) }
            }
            UnlockMethod.TOTP -> showPinEntryDialog(
                title = "Confirmar código TOTP",
                subtitle = "Digite o código de 6 dígitos do seu autenticador para trocar o método.",
                fallbackLabel = "Cancelar",
                minLength = 6,
                maxLength = 6,
                lengthHint = "Digite o código de 6 dígitos.",
                errorMessage = "Código incorreto.",
                onPin = { code ->
                    if (UnlockVault.verifyTotp(this, code)) {
                        onSuccess()
                        true
                    } else {
                        false
                    }
                },
                onFallback = onCancel,
                extraLabel = "Usar código de recuperação",
                onExtra = {
                    showRecoveryCodeDialog(
                        title = "Código de recuperação",
                        subtitle = "Digite um código de recuperação para trocar o método.",
                        onVerified = { onSuccess() },
                    )
                },
            )
            UnlockMethod.NONE -> onSuccess() // sem método ativo, nada a resolver
        }
    }

    private fun startPinSetup(onDone: (String) -> Unit, onCancel: () -> Unit) {
        if (UnlockVault.hasPin(this)) {
            showPinEntryDialog(
                title = "PIN atual",
                subtitle = "Digite o PIN numérico atual.",
                fallbackLabel = "Cancelar",
                onPin = { pin ->
                    if (UnlockVault.verifyPin(this, pin)) {
                        collectNewPin(onDone, onCancel, oldPin = pin)
                        true
                    } else {
                        false
                    }
                },
                onFallback = onCancel,
            )
        } else {
            collectNewPin(onDone, onCancel)
        }
    }

    private fun startPatternSetup(onDone: (String) -> Unit, onCancel: () -> Unit) {
        if (UnlockVault.hasPattern(this)) {
            showPatternEntryDialog(
                title = "Desenho atual",
                subtitle = "Desenhe o padrão 3x3 atual.",
                fallbackLabel = "Cancelar",
                onPattern = { pattern ->
                    if (UnlockVault.verifyPattern(this, pattern)) {
                        collectNewPattern(onDone, onCancel, oldPattern = pattern)
                        true
                    } else {
                        false
                    }
                },
                onFallback = onCancel,
            )
        } else {
            collectNewPattern(onDone, onCancel)
        }
    }

    /**
     * Coleta o novo desenho e troca a credencial. Se os embrulhos das notas estão sob o desenho
     * (método ativo, ou o último ativo com nenhum método selecionado) e [oldPattern] foi verificado,
     * recupera as senhas com o desenho antigo antes de [UnlockVault.setPattern] e re-embrulha com o
     * novo depois — trocar o desenho nunca pode deixar as notas sem acesso. Se a recuperação
     * falhar, aborta a troca (as notas continuam sob o desenho antigo, ainda válido).
     */
    private fun collectNewPattern(onDone: (String) -> Unit, onCancel: () -> Unit, oldPattern: String? = null) {
        var first: String? = null
        showPatternEntryDialog(
            title = "Novo desenho",
            subtitle = "Ligue pelo menos ${UnlockVault.PATTERN_MIN} pontos.",
            fallbackLabel = "Cancelar",
            onPattern = { pattern ->
                first = pattern
                true
            },
            onFallback = onCancel,
            onDismissed = {
                val expected = first
                if (expected != null) {
                    showPatternEntryDialog(
                        title = "Confirmar desenho",
                        subtitle = "Desenhe o mesmo padrão novamente.",
                        fallbackLabel = "Cancelar",
                        onPattern = { pattern ->
                            if (pattern != expected) {
                                false
                            } else {
                                val wrapsArePatterns = UnlockVault.wrapsBelongTo(this@MainActivity, UnlockMethod.PATTERN)
                                val recovered: Map<String, String>? =
                                    if (wrapsArePatterns && oldPattern != null) recoverWrappedWithPattern(oldPattern) else emptyMap()
                                if (wrapsArePatterns && oldPattern != null && recovered == null) {
                                    Toast.makeText(this@MainActivity, "Não foi possível re-proteger as notas com o novo desenho.", Toast.LENGTH_SHORT).show()
                                    false
                                } else if (UnlockVault.setPattern(this, pattern)) {
                                    (recovered ?: emptyMap()).forEach { (id, secret) ->
                                        UnlockVault.wrapWithPattern(this@MainActivity, id, secret, pattern)
                                    }
                                    onDone(pattern)
                                    true
                                } else {
                                    false
                                }
                            }
                        },
                        onFallback = onCancel,
                    )
                }
            },
        )
    }

    /**
     * Coleta o novo PIN e troca a credencial. Se os embrulhos das notas estão sob o PIN (método
     * ativo, ou o último ativo com nenhum método selecionado) e [oldPin] foi verificado, recupera
     * as senhas com o PIN antigo antes de [UnlockVault.setPin] e re-embrulha com o novo depois —
     * trocar o PIN nunca pode deixar as notas sem acesso. Se a recuperação falhar, aborta a troca
     * (as notas continuam sob o PIN antigo, ainda válido).
     */
    private fun collectNewPin(onDone: (String) -> Unit, onCancel: () -> Unit, oldPin: String? = null) {
        var first: String? = null
        showPinEntryDialog(
            title = "Novo PIN",
            subtitle = "Crie um PIN de ${UnlockVault.PIN_MIN} a ${UnlockVault.PIN_MAX} dígitos.",
            fallbackLabel = "Cancelar",
            onPin = { pin ->
                first = pin
                true
            },
            onFallback = onCancel,
            onDismissed = {
                val expected = first
                if (expected != null) {
                    showPinEntryDialog(
                        title = "Confirmar PIN",
                        subtitle = "Digite o PIN novamente.",
                        fallbackLabel = "Cancelar",
                        onPin = { pin ->
                            if (pin != expected) {
                                false
                            } else {
                                val wrapsArePins = UnlockVault.wrapsBelongTo(this@MainActivity, UnlockMethod.NUMERIC_PIN)
                                val recovered: Map<String, String>? =
                                    if (wrapsArePins && oldPin != null) recoverWrappedWithPin(oldPin) else emptyMap()
                                if (wrapsArePins && oldPin != null && recovered == null) {
                                    Toast.makeText(this@MainActivity, "Não foi possível re-proteger as notas com o novo PIN.", Toast.LENGTH_SHORT).show()
                                    false
                                } else if (UnlockVault.setPin(this, pin)) {
                                    (recovered ?: emptyMap()).forEach { (id, secret) ->
                                        UnlockVault.wrapWithPin(this@MainActivity, id, secret, pin)
                                    }
                                    onDone(pin)
                                    true
                                } else {
                                    false
                                }
                            }
                        },
                        onFallback = onCancel,
                    )
                }
            },
        )
    }

    /** Recupera todas as senhas embrulhadas com o PIN [oldPin]; null se qualquer nota falhar. */
    private fun recoverWrappedWithPin(oldPin: String): Map<String, String>? {
        val ids = UnlockVault.wrappedNoteIds(this)
        if (ids.isEmpty()) return emptyMap()
        val recovered = HashMap<String, String>()
        for (id in ids) {
            val secret = UnlockVault.unwrapWithPin(this, id, oldPin) ?: return null
            recovered[id] = secret
        }
        return recovered
    }

    /** Recupera todas as senhas embrulhadas com o desenho [oldPattern]; null se qualquer nota falhar. */
    private fun recoverWrappedWithPattern(oldPattern: String): Map<String, String>? {
        val ids = UnlockVault.wrappedNoteIds(this)
        if (ids.isEmpty()) return emptyMap()
        val recovered = HashMap<String, String>()
        for (id in ids) {
            val secret = UnlockVault.unwrapWithPattern(this, id, oldPattern) ?: return null
            recovered[id] = secret
        }
        return recovered
    }

    private fun startTotpSetup(onDone: () -> Unit, onCancel: () -> Unit) {
        if (UnlockVault.hasTotp(this)) {
            showPinEntryDialog(
                title = "Código atual",
                subtitle = "Digite o código atual do seu autenticador.",
                fallbackLabel = "Cancelar",
                minLength = 6,
                maxLength = 6,
                lengthHint = "Digite o código de 6 dígitos.",
                errorMessage = "Código incorreto.",
                onPin = { code ->
                    if (UnlockVault.verifyTotp(this, code)) {
                        showTotpSetupContent(onDone, onCancel, totpCode = code)
                        true
                    } else {
                        false
                    }
                },
                onFallback = onCancel,
                extraLabel = "Usar código de recuperação",
                onExtra = {
                    // Um código de recuperação (uso único) prova a posse do TOTP e autoriza
                    // reconfigurar. Não existe mais redefinição sem prova — perder o autenticador e
                    // os códigos significa perda permanente do acesso TOTP.
                    showRecoveryCodeDialog(
                        title = "Código de recuperação",
                        subtitle = "Digite um código de recuperação ainda não usado para reconfigurar.",
                        onVerified = { recoveryCode ->
                            showTotpSetupContent(onDone, onCancel, recoveryCode = recoveryCode)
                        },
                    )
                },
            )
        } else {
            showTotpSetupContent(onDone, onCancel)
        }
    }

    /**
     * Tela de novo segredo TOTP. Quando o fluxo chegou aqui já autenticado ([totpCode] = código
     * atual verificado, [recoveryCode] = código de recuperação consumido) e os embrulhos das notas
     * estão sob o TOTP, recupera as senhas com o segredo antigo antes de trocar e re-embrulha com o
     * novo — reconfigurar o TOTP nunca pode deixar as notas sem acesso. Na primeira configuração
     * (sem TOTP prévio) não há o que re-embrulhar.
     */
    private fun showTotpSetupContent(onDone: () -> Unit, onCancel: () -> Unit, totpCode: String? = null, recoveryCode: String? = null) {
        val secret = Totp.newSecret()
        val secretBase32 = Totp.base32(secret)
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label("Configurar código TOTP", 20, palette.dialogText, true))
        panel.addView(label(
            "Escaneie o QR code com seu app de autenticação (Google Authenticator, Authy, Bitwarden…).",
            14,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        val qrSize = dp(220)
        panel.addView(ImageView(this).apply {
            setImageBitmap(qrBitmap(Totp.otpauthUri(secretBase32), qrSize))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(qrSize, qrSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        panel.addView(label(
            "Ou digite o segredo manualmente no app de autenticação:",
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(12), 0, dp(4)) })
        panel.addView(TextView(this).apply {
            text = secretBase32
            textSize = 15f
            setTextColor(palette.dialogText)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(palette.dialogControlSurface, 10)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        panel.addView(label(
            "Guarde o segredo. Sem ele e sem o autenticador, não será possível desbloquear as notas protegidas.",
            13,
            palette.dialogButton,
            false,
        ).apply { setPadding(0, dp(12), 0, dp(12)) })
        panel.addView(label(
            "Se o código não for aceito, confira o relógio deste aparelho e o do aparelho com o autenticador.",
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(0), 0, dp(12)) })

        panel.addView(label(
            "Confirme digitando o código de 6 dígitos que seu autenticador exibe agora:",
            13,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(14), 0, dp(6)) })
        val codeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(Totp.DIGITS))
            textSize = 20f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(palette.dialogText)
            hint = "000000"
            setHintTextColor(palette.mutedText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.dialogControlSurface, 12)
        }
        panel.addView(codeInput, LinearLayout.LayoutParams(MATCH, dp(52)))

        fun confirmCode() {
            val code = codeInput.text.toString().trim()
            if (code.length != Totp.DIGITS || code.any { it !in '0'..'9' }) {
                Toast.makeText(this@MainActivity, "Digite o código de 6 dígitos.", Toast.LENGTH_SHORT).show()
                return
            }
            // Aceita mesmo com relógio divergente (até ±10 min) e guarda o descompasso para os
            // desbloqueios seguintes (o autenticador pode estar em outro aparelho, com relógio diferente).
            val skew = Totp.findSkew(secret, code, System.currentTimeMillis())
            if (skew != null) {
                // Reconfigurando o TOTP já ativo: a posse foi provada no início do fluxo (código
                // atual ou código de recuperação) e os embrulhos usam o segredo estável — recupera
                // as senhas com o segredo antigo e re-embrulha com o novo. setTotp não apaga os
                // embrulhos; se a recuperação falhar, aborta (o segredo antigo continua valendo).
                val wrapsAreTotp = UnlockVault.wrapsBelongTo(this@MainActivity, UnlockMethod.TOTP)
                val wrappedIds = UnlockVault.wrappedNoteIds(this@MainActivity)
                val needsRewrap = wrapsAreTotp && (totpCode != null || recoveryCode != null)
                val recovered: Map<String, String>? = if (needsRewrap && wrappedIds.isNotEmpty()) {
                    UnlockVault.unwrapAllWithStoredTotp(this@MainActivity, wrappedIds)
                        .takeIf { it.size == wrappedIds.size }
                } else {
                    emptyMap()
                }
                if (needsRewrap && wrappedIds.isNotEmpty() && recovered == null) {
                    secret.fill(0)
                    Toast.makeText(this@MainActivity, "Não foi possível re-proteger as notas com o novo código TOTP.", Toast.LENGTH_SHORT).show()
                } else {
                    val ok = UnlockVault.setTotp(this@MainActivity, secret, skew)
                    secret.fill(0)
                    if (ok) {
                        (recovered ?: emptyMap()).forEach { (id, s) ->
                            UnlockVault.wrapWithTotp(this@MainActivity, id, s)
                        }
                        dialog.dismiss()
                        val codes = UnlockVault.createRecoveryCodes(this@MainActivity)
                        showTotpRecoveryCodes(codes, onDone)
                    } else {
                        Toast.makeText(this@MainActivity, "Não foi possível configurar o TOTP.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this@MainActivity, "Código incorreto.", Toast.LENGTH_SHORT).show()
                codeInput.text.clear()
            }
        }

        codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s?.length == Totp.DIGITS) confirmCode()
            }
        })

        panel.addView(MaterialButton(this).apply {
            text = "Confirmar"
            isAllCaps = false
            textSize = 16f
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { confirmCode() }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })

        // Painel alto (QR + textos + input + botão): numa tela pequena o conjunto rola em vez de
        // cortar o rodapé; numa tela normal fica como está (a rolagem colapsa na altura do painel).
        val (root, dialogHeight) = boundedDialogRoot(panel)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), dialogHeight)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onCancel() }
        dialog.show()
    }

    /**
     * Tela de chaves de recuperação mostrada logo após configurar o TOTP: os códigos de uso único.
     * Cada um desbloqueia uma nota ou o app sem o autenticador; consumido ao ser usado. Guardados
     * cifrados em repouso (Keystore) para exibição; a verificação consome o código.
     */
    private fun showTotpRecoveryCodes(codes: List<String>, onDone: () -> Unit) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label("Códigos de recuperação", 20, palette.dialogText, true))
        panel.addView(label(
            "Cada código é de USO ÚNICO e desbloqueia sem o autenticador. Eles só aparecem " +
                "AGORA — depois de fechar esta tela não há como vê-los de novo. Guarde-os em um " +
                "lugar seguro: quem tiver um código desbloqueia como você.",
            14,
            palette.secondaryText,
            false,
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        val codesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        codes.forEach { code ->
            codesBox.addView(TextView(this).apply {
                text = code.chunked(4).joinToString("-")
                textSize = 15f
                setTextColor(palette.dialogText)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextIsSelectable(true)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(palette.dialogControlSurface, 8)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        panel.addView(ScrollView(this).apply { addView(codesBox) },
            LinearLayout.LayoutParams(MATCH, dp(250)).apply { topMargin = dp(10) })

        panel.addView(label(
            "Toque e segure sobre um código para selecioná-lo. Eles são a ÚNICA saída se você " +
                "perder o autenticador — não há como redefinir o TOTP sem o código atual ou um " +
                "destes códigos. Guarde-os em local seguro.",
            13,
            palette.dialogButton,
            false,
        ).apply { setPadding(0, dp(12), 0, dp(10)) })

        panel.addView(MaterialButton(this).apply {
            text = "Concluir"
            isAllCaps = false
            textSize = 16f
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                dialog.dismiss()
                onDone()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(4) })

        // Painel alto (códigos de recuperação + botão): numa tela pequena o conjunto rola em vez de
        // cortar o rodapé; numa tela normal fica como está (a rolagem colapsa na altura do painel).
        val (root, dialogHeight) = boundedDialogRoot(panel)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), dialogHeight)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onDone() }
        dialog.show()
    }

    /**
     * Diálogo para digitar um código de recuperação (uso único). O teclado do sistema aparece
     * aqui porque o código é alfanumérico — caminho raro, usado quando o autenticador foi perdido.
     * [onVerified] recebe o código já conferido (e consumido). Não há saída de emergência: sem o
     * código atual nem um código de recuperação não há como desbloquear ou redefinir o TOTP.
     */
    private fun showRecoveryCodeDialog(
        title: String,
        subtitle: String?,
        onVerified: (String) -> Unit,
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        if (subtitle != null) {
            panel.addView(dialogScroll(label(subtitle, 14, palette.secondaryText, false).apply {
                setPadding(0, dp(8), 0, dp(4))
            }))
        }

        val codeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 18f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(palette.dialogText)
            hint = "ABCD-EFGH-JKMN"
            setHintTextColor(palette.mutedText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.dialogControlSurface, 12)
        }
        panel.addView(codeInput, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) })

        fun confirm() {
            val code = codeInput.text.toString()
            if (UnlockVault.verifyRecoveryCode(this@MainActivity, code)) {
                dialog.dismiss()
                onVerified(code)
            } else {
                Toast.makeText(this@MainActivity, "Código de recuperação inválido ou já usado.", Toast.LENGTH_SHORT).show()
                codeInput.text.clear()
            }
        }

        panel.addView(MaterialButton(this).apply {
            text = "Confirmar"
            isAllCaps = false
            textSize = 16f
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { confirm() }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) })

        panel.addView(MaterialButton(this).apply {
            text = "Cancelar"
            isAllCaps = false
            textSize = 15f
            setTextColor(palette.dialogButton)
            backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(4) })
        panel.addView(
            makeResetAppLink { dialog.dismiss(); startResetAppFlow() },
            LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(2) },
        )

        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /** Renderiza um QR code em um [Bitmap] preto/branco (zxing, local — sem internet). */
    private fun qrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun showPinEntryDialog(
        title: String,
        subtitle: String?,
        fallbackLabel: String,
        onPin: (String) -> Boolean,
        onFallback: () -> Unit,
        onDismissed: (() -> Unit)? = null,
        minLength: Int = UnlockVault.PIN_MIN,
        maxLength: Int = UnlockVault.PIN_MAX,
        lengthHint: String? = null,
        errorMessage: String = "PIN incorreto.",
        extraLabel: String? = null,
        onExtra: (() -> Unit)? = null,
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        if (subtitle != null) {
            panel.addView(dialogScroll(label(subtitle, 14, palette.secondaryText, false).apply {
                setPadding(0, dp(8), 0, dp(4))
            }))
        }

        val pinDots = label("", 24, palette.dialogText, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            letterSpacing = 0.3f
        }
        panel.addView(pinDots, LinearLayout.LayoutParams(MATCH, WRAP))

        var pin = ""
        fun refreshDots() {
            pinDots.text = "•".repeat(pin.length)
        }
        fun digit(d: Char) {
            if (pin.length < maxLength) {
                pin += d
                refreshDots()
            }
        }
        fun backspace() {
            if (pin.isNotEmpty()) {
                pin = pin.dropLast(1)
                refreshDots()
            }
        }
        fun submit() {
            if (pin.length < minLength) {
                Toast.makeText(this, lengthHint ?: "Digite $minLength a $maxLength dígitos.", Toast.LENGTH_SHORT).show()
                return
            }
            if (onPin(pin)) {
                dialog.dismiss()
                onDismissed?.invoke()
            } else {
                pin = ""
                refreshDots()
                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            }
        }

        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf('∅', '0', '⌫'),
        )
        rows.forEach { row ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { key ->
                rowView.addView(MaterialButton(this).apply {
                    isAllCaps = false
                    text = if (key == '∅') "" else key.toString()
                    isEnabled = key != '∅'
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(palette.dialogText)
                    backgroundTintList = ColorStateList.valueOf(palette.dialogControlSurface)
                    cornerRadius = dp(16)
                    insetTop = 0
                    insetBottom = 0
                    setPadding(0, 0, 0, 0)
                    stateListAnimator = null
                    setOnClickListener {
                        when (key) {
                            '⌫' -> backspace()
                            else -> digit(key)
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
            keypad.addView(rowView, LinearLayout.LayoutParams(MATCH, dp(64)))
        }
        panel.addView(keypad, LinearLayout.LayoutParams(MATCH, WRAP))

        if (extraLabel != null && onExtra != null) {
            panel.addView(MaterialButton(this).apply {
                text = extraLabel
                isAllCaps = false
                textSize = 15f
                setTextColor(palette.secondaryText)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                insetTop = 0
                insetBottom = 0
                setOnClickListener {
                    dialog.dismiss()
                    onExtra()
                }
            }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(2) })
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(MaterialButton(this).apply {
            text = fallbackLabel
            isAllCaps = false
            setTextColor(palette.secondaryText)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.cancel() }
        }, LinearLayout.LayoutParams(WRAP, dp(48)).apply { marginEnd = dp(8) })
        bottom.addView(MaterialButton(this).apply {
            text = "Confirmar"
            isAllCaps = false
            setTextColor(palette.fabIcon)
            backgroundTintList = ColorStateList.valueOf(palette.fab)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(bottom, LinearLayout.LayoutParams(MATCH, dp(56)).apply { topMargin = dp(4) })
        panel.addView(
            makeResetAppLink { dialog.dismiss(); startResetAppFlow() },
            LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(2) },
        )

        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onFallback() }
        dialog.show()
    }

    private fun showPatternEntryDialog(
        title: String,
        subtitle: String?,
        fallbackLabel: String,
        onPattern: (String) -> Boolean,
        onFallback: () -> Unit,
        onDismissed: (() -> Unit)? = null,
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = rounded(palette.dialogSurface, 28)
        }
        panel.addView(label(title, 20, palette.dialogText, true))
        if (subtitle != null) {
            panel.addView(dialogScroll(label(subtitle, 14, palette.secondaryText, false).apply {
                setPadding(0, dp(8), 0, dp(4))
            }))
        }

        val patternView = PatternLockView(this).apply {
            dotColor = palette.mutedText
            accentColor = palette.accent
            errorColor = Color.parseColor("#E5484D")
            onPatternCompleted = { pattern ->
                if (onPattern(pattern)) {
                    dialog.dismiss()
                    onDismissed?.invoke()
                } else {
                    Toast.makeText(this@MainActivity, "Desenho incorreto.", Toast.LENGTH_SHORT).show()
                    showError()
                }
            }
        }
        panel.addView(patternView, LinearLayout.LayoutParams(MATCH, dp(280)))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(MaterialButton(this).apply {
            text = fallbackLabel
            isAllCaps = false
            setTextColor(palette.secondaryText)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.cancel() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(bottom, LinearLayout.LayoutParams(MATCH, dp(56)).apply { topMargin = dp(4) })
        panel.addView(
            makeResetAppLink { dialog.dismiss(); startResetAppFlow() },
            LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(2) },
        )

        panel.layoutParams = ViewGroup.LayoutParams(dialogWidth(), WRAP)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(), WRAP)
            setWindowAnimations(0)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onFallback() }
        dialog.show()
    }

    private fun unlockMethodLabel(method: UnlockMethod = UnlockVault.currentMethod(this)): String = when (method) {
        UnlockMethod.BIOMETRIC -> "biometria"
        UnlockMethod.PATTERN -> "o desenho"
        UnlockMethod.NUMERIC_PIN -> "o PIN numérico"
        UnlockMethod.TOTP -> "o código TOTP"
        UnlockMethod.NONE -> "nenhum método"
    }

    /**
     * Configura e ativa o método [target] DEPOIS de resolver o método de origem (feito antes, pela
     * chamadora). [secrets] são as senhas recuperadas da origem — quando não vazio, são re-embrulhadas
     * sob a nova chave (a troca nunca pode deixar as notas sem acesso; o segredo aleatório de cada
     * nota só existe no embrulho local). O setup do destino roda aqui, já na ordem correta: resolver
     * o atual primeiro, configurar o escolhido depois. [onCancel] é chamado se o usuário desistir do
     * setup/confirmação do destino (o estado da origem fica intacto).
     */
    private fun finishSwitchTo(
        target: UnlockMethod,
        secrets: Map<String, String>,
        onApplied: () -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val needsSetup = when (target) {
            UnlockMethod.BIOMETRIC -> !UnlockVault.isMethodAvailable(this, target)
            UnlockMethod.PATTERN -> !UnlockVault.hasPattern(this)
            UnlockMethod.NUMERIC_PIN -> !UnlockVault.hasPin(this)
            UnlockMethod.TOTP -> !UnlockVault.hasTotp(this)
            UnlockMethod.NONE -> false
        }
        val hasRewrap = secrets.isNotEmpty()
        when (target) {
            UnlockMethod.BIOMETRIC -> {
                if (needsSetup) {
                    Toast.makeText(this, "Este método não está disponível neste aparelho.", Toast.LENGTH_SHORT).show()
                    onCancel()
                    return
                }
                onApplied()
                if (hasRewrap) {
                    val ids = secrets.keys.toList()
                    fun wrapNext(i: Int) {
                        if (i < ids.size) {
                            wrapSecretWithBiometric(ids[i], secrets.getValue(ids[i])) { wrapNext(i + 1) }
                        }
                    }
                    wrapNext(0)
                }
            }
            UnlockMethod.PATTERN, UnlockMethod.NUMERIC_PIN -> {
                if (needsSetup || hasRewrap) {
                    // Cria a credencial do destino (setup) ou só a confirma para re-embrulhar.
                    promptForMethodCredential(target, onCredential = { credential ->
                        onApplied()
                        secrets.forEach { (id, secret) ->
                            when (target) {
                                UnlockMethod.PATTERN -> UnlockVault.wrapWithPattern(this, id, secret, credential)
                                UnlockMethod.NUMERIC_PIN -> UnlockVault.wrapWithPin(this, id, secret, credential)
                                else -> Unit
                            }
                        }
                    }, onCancel = onCancel)
                } else {
                    onApplied()
                }
            }
            UnlockMethod.TOTP -> {
                if (needsSetup) {
                    // O setup do TOTP grava o novo segredo; o re-embrulho usa esse segredo.
                    startTotpSetup(onDone = {
                        onApplied()
                        secrets.forEach { (id, secret) -> UnlockVault.wrapWithTotp(this, id, secret) }
                    }, onCancel = onCancel)
                } else {
                    onApplied()
                    secrets.forEach { (id, secret) -> UnlockVault.wrapWithTotp(this, id, secret) }
                }
            }
            UnlockMethod.NONE -> onApplied()
        }
    }

    private fun recoverWrappedSecrets(
        oldMethod: UnlockMethod,
        noteIds: List<String>,
        subtitle: String? = null,
        onCancel: () -> Unit = {},
        onRecovered: (Map<String, String>) -> Unit,
    ) {
        when (oldMethod) {
            UnlockMethod.NUMERIC_PIN -> showPinEntryDialog(
                title = "Confirmar PIN atual",
                subtitle = subtitle ?: "Digite seu PIN para migrar ${noteIds.size} nota(s) protegida(s).",
                fallbackLabel = "Cancelar",
                onPin = { pin ->
                    val recovered = HashMap<String, String>()
                    var ok = true
                    for (id in noteIds) {
                        val secret = UnlockVault.unwrapWithPin(this, id, pin)
                        if (secret == null) { ok = false; break }
                        recovered[id] = secret
                    }
                    if (ok) { onRecovered(recovered); true } else false
                },
                onFallback = onCancel,
            )
            UnlockMethod.PATTERN -> showPatternEntryDialog(
                title = "Confirmar desenho atual",
                subtitle = subtitle ?: "Desenhe o padrão para migrar ${noteIds.size} nota(s) protegida(s).",
                fallbackLabel = "Cancelar",
                onPattern = { pattern ->
                    val recovered = HashMap<String, String>()
                    var ok = true
                    for (id in noteIds) {
                        val secret = UnlockVault.unwrapWithPattern(this, id, pattern)
                        if (secret == null) { ok = false; break }
                        recovered[id] = secret
                    }
                    if (ok) { onRecovered(recovered); true } else false
                },
                onFallback = onCancel,
            )
            UnlockMethod.BIOMETRIC -> recoverBiometricSecrets(noteIds, 0, linkedMapOf(), onRecovered)
            UnlockMethod.TOTP -> showPinEntryDialog(
                title = "Confirmar código TOTP atual",
                subtitle = subtitle ?: "Digite o código do seu autenticador para migrar ${noteIds.size} nota(s) protegida(s).",
                fallbackLabel = "Cancelar",
                minLength = 6,
                maxLength = 6,
                lengthHint = "Digite o código de 6 dígitos.",
                errorMessage = "Código incorreto.",
                onPin = { code ->
                    val recovered = HashMap<String, String>()
                    var ok = true
                    for (id in noteIds) {
                        val secret = UnlockVault.unwrapWithTotp(this, id, code)
                        if (secret == null) { ok = false; break }
                        recovered[id] = secret
                    }
                    if (ok) { onRecovered(recovered); true } else false
                },
                onFallback = onCancel,
                extraLabel = "Usar código de recuperação",
                onExtra = {
                    showRecoveryCodeDialog(
                        title = "Código de recuperação",
                        subtitle = "Digite um código de recuperação para desembrulhar as notas.",
                        onVerified = { code ->
                            val recovered = UnlockVault.unwrapAllWithRecoveryCode(this, noteIds, code)
                            if (recovered.isEmpty()) {
                                Toast.makeText(this, "Não foi possível recuperar as notas com esse código.", Toast.LENGTH_SHORT).show()
                            } else {
                                onRecovered(recovered)
                            }
                        },
                    )
                },
            )
            UnlockMethod.NONE -> onRecovered(emptyMap()) // nunca migra DE nenhum método
        }
    }

    private fun recoverBiometricSecrets(
        noteIds: List<String>,
        index: Int,
        acc: LinkedHashMap<String, String>,
        onRecovered: (Map<String, String>) -> Unit,
    ) {
        if (index >= noteIds.size) { onRecovered(acc); return }
        val id = noteIds[index]
        val stored = UnlockVault.loadWrapped(this, id)
        val cipher = stored?.let { UnlockVault.prepareUnwrapCipher(this, it) }
        if (stored == null || cipher == null) {
            recoverBiometricSecrets(noteIds, index + 1, acc, onRecovered)
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirmar biometria")
            .setSubtitle("Migrando nota ${index + 1} de ${noteIds.size}.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar")
            .build()
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                UnlockVault.finishUnwrap(stored, cipher)?.let { acc[id] = it }
                recoverBiometricSecrets(noteIds, index + 1, acc, onRecovered)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Sem biometria não dá para recuperar o segredo; aborta a troca (a nota permanece
                // no método antigo em vez de se perder).
            }
        })
        runCatching {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }.onFailure {
            // Não foi possível iniciar a autenticação; aborta a troca.
        }
    }

    private fun promptForMethodCredential(
        method: UnlockMethod,
        onCredential: (String) -> Unit,
        onCancel: () -> Unit,
    ) {
        when (method) {
            UnlockMethod.PATTERN -> {
                if (UnlockVault.hasPattern(this)) {
                    showPatternEntryDialog(
                        title = "Desenho atual",
                        subtitle = "Desenhe seu padrão para re-proteger as notas.",
                        fallbackLabel = "Cancelar",
                        onPattern = { pattern ->
                            if (UnlockVault.verifyPattern(this, pattern)) {
                                onCredential(pattern)
                                true
                            } else {
                                false
                            }
                        },
                        onFallback = onCancel,
                    )
                } else {
                    collectNewPattern(onDone = onCredential, onCancel = onCancel)
                }
            }
            UnlockMethod.NUMERIC_PIN -> {
                if (UnlockVault.hasPin(this)) {
                    showPinEntryDialog(
                        title = "PIN atual",
                        subtitle = "Digite seu PIN para re-proteger as notas.",
                        fallbackLabel = "Cancelar",
                        onPin = { pin ->
                            if (UnlockVault.verifyPin(this, pin)) {
                                onCredential(pin)
                                true
                            } else {
                                false
                            }
                        },
                        onFallback = onCancel,
                    )
                } else {
                    collectNewPin(onDone = onCredential, onCancel = onCancel)
                }
            }
            else -> onCancel()
        }
    }

    private fun wrapSecretWithBiometric(noteId: String, secret: String, onDone: (Boolean) -> Unit) {
        val cipher = UnlockVault.prepareWrapCipher(this)
        if (cipher == null) {
            onDone(false)
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Proteger nota")
            .setSubtitle("Confirme para re-proteger esta nota.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar")
            .build()
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onDone(UnlockVault.finishWrap(this@MainActivity, noteId, secret, cipher))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
        })
        runCatching {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }.onFailure {
            onDone(false)
        }
    }

    private fun wrapSecretForNote(noteId: String, secret: String, onDone: () -> Unit) {
        when (UnlockVault.currentMethod(this)) {
            UnlockMethod.NUMERIC_PIN -> showPinEntryDialog(
                title = "Proteger nota",
                subtitle = "Digite seu PIN numérico para proteger esta nota.",
                fallbackLabel = "Cancelar",
                onPin = { pin ->
                    if (UnlockVault.wrapWithPin(this, noteId, secret, pin)) {
                        onDone()
                        true
                    } else {
                        false
                    }
                },
                onFallback = {},
            )
            UnlockMethod.PATTERN -> showPatternEntryDialog(
                title = "Proteger nota",
                subtitle = "Desenhe seu padrão para proteger esta nota.",
                fallbackLabel = "Cancelar",
                onPattern = { pattern ->
                    if (UnlockVault.wrapWithPattern(this, noteId, secret, pattern)) {
                        onDone()
                        true
                    } else {
                        false
                    }
                },
                onFallback = {},
            )
            UnlockMethod.BIOMETRIC -> {
                val cipher = UnlockVault.prepareWrapCipher(this)
                if (cipher == null) {
                    Toast.makeText(this, "Não foi possível usar o método de proteção.", Toast.LENGTH_SHORT).show()
                    return
                }
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Proteger nota")
                    .setSubtitle("Confirme para proteger esta nota.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Cancelar")
                    .build()
                val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (UnlockVault.finishWrap(this@MainActivity, noteId, secret, cipher)) {
                            onDone()
                        } else {
                            Toast.makeText(this@MainActivity, "Não foi possível proteger a nota.", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = Unit
                })
                runCatching {
                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Não foi possível proteger a nota.", Toast.LENGTH_SHORT).show()
                }
            }
            UnlockMethod.TOTP -> {
                // Sem código: quem está no app já está autenticado; a chave vem do segredo armazenado.
                if (!UnlockVault.wrapWithTotp(this, noteId, secret)) {
                    Toast.makeText(this, "Não foi possível proteger a nota.", Toast.LENGTH_SHORT).show()
                } else {
                    onDone()
                }
            }
            UnlockMethod.NONE -> showNoUnlockMethodModal() // sem método não há como proteger
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
            val content = materialized(note)
            val clip = ClipData.newPlainText(note.title, noteContent(content))
            // Marca o conteúdo copiado como sensível: teclados/launchers omitem o preview do texto
            // no clipboard (o texto em si continua copiado — apenas o aviso visual some).
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareNote(note: Note) {
        withUnlockedContent(note) {
            val content = materialized(note)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, note.title)
                putExtra(Intent.EXTRA_TEXT, "${note.title}\n\n${noteContent(content)}")
            }
            startActivity(Intent.createChooser(intent, "Compartilhar nota"))
        }
    }

    private fun noteContent(note: Note): String = if (note.type == NoteType.CHECKLIST) {
        note.items.joinToString("\n") { item -> "[${if (item.completed) "x" else " "}] ${item.text}" }
    } else {
        note.body
    }

    private fun materialized(note: Note): Note {
        if (!note.locked) return note
        val content = viewModel.unlockedContent(note.id) ?: return note
        return note.copy(body = content.body, items = content.items)
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

    /**
     * Envolve um texto de diálogo (mensagem/subtítulo) numa rolagem com altura máxima. A altura é
     * medida de forma determinística (antes de exibir o diálogo) e limitada a ~55% da tela; um texto
     * curto fica como está (a rolagem colapsa na altura do texto) e um texto comprido rola — a barra
     * só aparece quando rola de verdade, então nunca corta a última linha nem os botões.
     */
    private fun dialogScroll(text: View): ScrollView {
        val max = (resources.displayMetrics.heightPixels * 0.55).toInt()
        text.measure(
            View.MeasureSpec.makeMeasureSpec(dialogWidth() - dp(48), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = minOf(text.measuredHeight, max)
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            addView(text)
            layoutParams = LinearLayout.LayoutParams(MATCH, height)
        }
    }

    /**
     * Conteúdo de um diálogo com altura segura para telas pequenas e fontes grandes. Mede o painel
     * de forma determinística (antes de exibir — sem depender do layout posterior à janela) e devolve
     * a view a passar ao `setContentView` junto com a altura a usar em `setLayout`. Quando o painel
     * passa de ~90% da tela, a janela fica nessa altura e rola (barra visível); quando cabe, a altura
     * devolvida é exatamente a do conteúdo (sem espaço morto, sem rolagem).
     */
    private fun boundedDialogRoot(panel: View): Pair<ScrollView, Int> {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
        val width = dialogWidth()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = minOf(panel.measuredHeight, maxHeight)
        val root = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            addView(panel)
        }
        return root to height
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
    }
}
