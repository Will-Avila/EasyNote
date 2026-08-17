package com.will.noteharbor.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Método de desbloqueio rápido das notas protegidas. */
enum class UnlockMethod {
    /** Digital / rosto (biometria forte). */
    BIOMETRIC,

    /** Desenho (padrão 3x3) definido dentro do app. */
    PATTERN,

    /** PIN numérico definido dentro do app (teclado próprio, sem o teclado do sistema). */
    NUMERIC_PIN,

    /** Código de 6 dígitos gerado por um app de autenticação (TOTP, RFC 6238). */
    TOTP,

    /**
     * Nenhum método ativo (desativado nas configurações de segurança). É um estado persistido:
     * as credenciais e os embrulhos do método anterior ficam intactos para que reativar o mesmo
     * método volte a liberar as notas protegidas.
     */
    NONE,
}

/**
 * Cofre local que embrulha as senhas das notas com uma chave do dispositivo, permitindo
 * desbloquear notas protegidas por biometria, desenho (padrão 3x3) ou PIN numérico — sem digitar
 * a senha completa de cada nota.
 *
 * A senha da nota continua sendo a raiz de recuperação: o conteúdo continua cifrado com ela
 * ([[NoteEncryption]]). Aqui guardamos apenas a senha da nota **embrulhada** por uma chave local
 * que, por sua vez, só é liberada pela autenticação escolhida (Keystore + biometria, ou Argon2id
 * do desenho/PIN). Se a chave local for invalidada, a senha da nota é o caminho de volta.
 */
object UnlockVault {
    internal const val PREFS = "noteharbor.security.preferences"
    private const val KEY_METHOD = "unlock_method"
    private const val KEY_LAST_METHOD = "last_active_method"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_VERIFIER = "pin_verifier"
    private const val KEY_PATTERN_SALT = "pattern_salt"
    private const val KEY_PATTERN_VERIFIER = "pattern_verifier"
    private const val KEY_APP_LOCK = "app_lock_enabled"
    private const val KEY_WRAPPED_PREFIX = "wrapped."

    private const val KEY_ALIAS = "noteharbor.unlock.v1"

    const val PIN_MIN = 4
    const val PIN_MAX = 8
    const val PATTERN_MIN = 4
    const val PATTERN_MAX = 9

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private const val ARGON2_T = NoteEncryption.ARGON2_T
    private const val ARGON2_M_KIB = NoteEncryption.ARGON2_M_KIB
    private const val ARGON2_P = NoteEncryption.ARGON2_P

    private val random = SecureRandom()

    // ---- Configuração do método ----

    fun currentMethod(context: Context): UnlockMethod = when (prefs(context).getString(KEY_METHOD, null)) {
        "NUMERIC_PIN" -> UnlockMethod.NUMERIC_PIN
        "PATTERN" -> UnlockMethod.PATTERN
        "TOTP" -> UnlockMethod.TOTP
        "BIOMETRIC" -> UnlockMethod.BIOMETRIC
        "NONE" -> UnlockMethod.NONE
        // Ausente (instalação antiga sem método explícito): biometria implícita, como sempre foi.
        // "DEVICE_CREDENTIAL" (legado) cai no padrão: o método do sistema foi substituído pelo desenho.
        else -> UnlockMethod.BIOMETRIC
    }

    /**
     * Último método que esteve ativo, mesmo que desativado depois. É a origem para reativar o mesmo
     * método (embrulhos continuam válidos) ou migrar para outro (recupera com o método desativado).
     */
    fun lastActiveMethod(context: Context): UnlockMethod? = when (prefs(context).getString(KEY_LAST_METHOD, null)) {
        "NUMERIC_PIN" -> UnlockMethod.NUMERIC_PIN
        "PATTERN" -> UnlockMethod.PATTERN
        "TOTP" -> UnlockMethod.TOTP
        "BIOMETRIC" -> UnlockMethod.BIOMETRIC
        else -> null
    }

    fun setMethod(context: Context, method: UnlockMethod) {
        val previous = currentMethod(context)
        val last = lastActiveMethod(context)
        prefs(context).edit()
            .putString(KEY_METHOD, method.name)
            .putString(KEY_LAST_METHOD, method.name)
            .apply()
        // Reativar o mesmo método depois de uma desativação mantém a mesma chave de embrulho
        // (a credencial não mudou) — os embrulhos continuam válidos e não devem ser apagados.
        // Trocas reais de método limpam: a migração re-embrulha as notas logo em seguida.
        if (previous != method && !(previous == UnlockMethod.NONE && last == method)) {
            clearWrapped(context)
        }
    }

    /**
     * Verdadeiro quando um método foi explicitamente escolhido (a chave `unlock_method` existe).
     * Distingue de [currentMethod], que devolve BIOMETRIC por padrão quando nada foi configurado.
     */
    fun isMethodConfigured(context: Context): Boolean = prefs(context).contains(KEY_METHOD)

    /**
     * Método atualmente ativo, ou null quando nenhum foi configurado (instalação nova/pós-limpeza
     * de dados). Diferente de [currentMethod] — que não deve ser usado para decidir o que está
     * marcado na UI, pois o default BIOMETRIC faria a biometria parecer ativa sem nunca ter sido
     * configurada.
     */
    fun configuredMethod(context: Context): UnlockMethod? =
        if (isMethodConfigured(context)) currentMethod(context) else null

    /**
     * Desativa o método de desbloqueio (nenhum método ativo). Exige ter resolvido o método atual
     * antes de chamar (ver `confirmCurrentMethod` na UI). Credencial e embrulhos ficam intactos,
     * para que reativar o mesmo método volte a liberar as notas protegidas; o bloqueio do app é
     * desligado (sem método não há como desbloquear o app).
     */
    fun deactivateMethod(context: Context): UnlockMethod {
        val previous = currentMethod(context)
        prefs(context).edit()
            .putString(KEY_LAST_METHOD, previous.name)
            .putString(KEY_METHOD, "NONE")
            .putBoolean(KEY_APP_LOCK, false)
            .apply()
        return previous
    }

    /**
     * Verdadeiro quando os embrulhos de notas estão sob a chave de [method]: ou o método está ativo,
     * ou (com nenhum método ativo) ele é o último que esteve ativo. Trocar a credencial desse método
     * exige recuperar as senhas com a credencial antiga e re-embrulhar com a nova — nunca apagar.
     */
    fun wrapsBelongTo(context: Context, method: UnlockMethod): Boolean {
        val current = currentMethod(context)
        return current == method || (current == UnlockMethod.NONE && lastActiveMethod(context) == method)
    }

    private fun UnlockMethod.usesKeystore(): Boolean = this == UnlockMethod.BIOMETRIC

    fun isMethodAvailable(context: Context, method: UnlockMethod): Boolean = when (method) {
        UnlockMethod.BIOMETRIC -> canAuthenticate(context, BiometricManager.Authenticators.BIOMETRIC_STRONG)
        UnlockMethod.PATTERN -> hasPattern(context)
        UnlockMethod.NUMERIC_PIN -> hasPin(context)
        UnlockMethod.TOTP -> hasTotp(context)
        UnlockMethod.NONE -> false
    }

    /** Verdadeiro quando o método atualmente selecionado está pronto para proteger/desbloquear notas. */
    fun isAnyMethodAvailable(context: Context): Boolean = isMethodAvailable(context, currentMethod(context))

    private fun canAuthenticate(context: Context, authenticators: Int): Boolean = runCatching {
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    // ---- Bloqueio do app ao iniciar ----

    /** Verdadeiro quando o app deve pedir desbloqueio ao abrir. */
    fun isAppLockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_APP_LOCK, false)

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    }

    // ---- PIN numérico ----

    fun hasPin(context: Context): Boolean = prefs(context).contains(KEY_PIN_SALT)

    fun setPin(context: Context, pin: String): Boolean {
        if (pin.length !in PIN_MIN..PIN_MAX || pin.any { it !in '0'..'9' }) return false
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = derivePinKey(pin, salt)
        val verifier = sha256(key + "verify".toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_PIN_SALT, b64(salt))
            .putString(KEY_PIN_VERIFIER, b64(verifier))
            .apply()
        key.fill(0)
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean = runCatching {
        val salt = loadPinSalt(context) ?: return false
        val stored = prefs(context).getString(KEY_PIN_VERIFIER, null) ?: return false
        val key = derivePinKey(pin, salt)
        val verifier = sha256(key + "verify".toByteArray(Charsets.UTF_8))
        key.fill(0)
        MessageDigest.isEqual(unb64(stored), verifier)
    }.getOrDefault(false)

    private fun loadPinSalt(context: Context): ByteArray? = runCatching {
        prefs(context).getString(KEY_PIN_SALT, null)?.let(::unb64)
    }.getOrNull()

    internal fun derivePinKey(pin: String, salt: ByteArray): ByteArray =
        NoteEncryption.keyDeriver(pin.toByteArray(Charsets.UTF_8), salt, ARGON2_T, ARGON2_M_KIB, ARGON2_P)

    private fun pinWrapKey(pin: String, salt: ByteArray): ByteArray {
        val key = derivePinKey(pin, salt)
        return sha256(key + "wrap".toByteArray(Charsets.UTF_8)).also { key.fill(0) }
    }

    // ---- Desenho (padrão 3x3) ----

    fun hasPattern(context: Context): Boolean = prefs(context).contains(KEY_PATTERN_SALT)

    fun setPattern(context: Context, pattern: String): Boolean {
        val valid = pattern.length in PATTERN_MIN..PATTERN_MAX &&
            pattern.all { it in '1'..'9' } &&
            pattern.toSet().size == pattern.length
        if (!valid) return false
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = derivePatternKey(pattern, salt)
        val verifier = sha256(key + "verify".toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_PATTERN_SALT, b64(salt))
            .putString(KEY_PATTERN_VERIFIER, b64(verifier))
            .apply()
        key.fill(0)
        return true
    }

    fun verifyPattern(context: Context, pattern: String): Boolean = runCatching {
        val salt = loadPatternSalt(context) ?: return false
        val stored = prefs(context).getString(KEY_PATTERN_VERIFIER, null) ?: return false
        val key = derivePatternKey(pattern, salt)
        val verifier = sha256(key + "verify".toByteArray(Charsets.UTF_8))
        key.fill(0)
        MessageDigest.isEqual(unb64(stored), verifier)
    }.getOrDefault(false)

    private fun loadPatternSalt(context: Context): ByteArray? = runCatching {
        prefs(context).getString(KEY_PATTERN_SALT, null)?.let(::unb64)
    }.getOrNull()

    internal fun derivePatternKey(pattern: String, salt: ByteArray): ByteArray =
        NoteEncryption.keyDeriver(pattern.toByteArray(Charsets.UTF_8), salt, ARGON2_T, ARGON2_M_KIB, ARGON2_P)

    private fun patternWrapKey(pattern: String, salt: ByteArray): ByteArray {
        val key = derivePatternKey(pattern, salt)
        return sha256(key + "wrap".toByteArray(Charsets.UTF_8)).also { key.fill(0) }
    }

    // ---- Código TOTP (app de autenticação) ----

    internal const val KEY_TOTP_SECRET = "totp-secret"
    private const val KEY_TOTP_SKEW = "totp-skew"
    private const val TOTP_SECRET_MIN_BYTES = 16

    fun hasTotp(context: Context): Boolean = SecureSecretStore(context).contains(KEY_TOTP_SECRET)

    fun totpSecret(context: Context): ByteArray? = SecureSecretStore(context).getBytes(KEY_TOTP_SECRET)

    /** Descompasso de relógio (em ms) capturado no setup — alinha este aparelho ao autenticador. */
    fun totpSkewMillis(context: Context): Long = prefs(context).getLong(KEY_TOTP_SKEW, 0L)

    /**
     * Grava um novo segredo TOTP (cifrado em repouso via Keystore) junto do descompasso de relógio
     * ([skewMillis]). Não apaga os embrulhos de notas aqui: o fluxo que reconfigura o TOTP recupera
     * as senhas com o segredo antigo (via [unwrapAllWithStoredTotp]) e as re-embrulha com o novo —
     * apagar os wraps ao trocar o segredo deixaria as notas inacessíveis para sempre.
     */
    fun setTotp(context: Context, secret: ByteArray, skewMillis: Long = 0L): Boolean {
        if (secret.size < TOTP_SECRET_MIN_BYTES) return false
        return runCatching {
            SecureSecretStore(context).putBytes(KEY_TOTP_SECRET, secret)
            clearRecoveryCodes(context) // novo setup gera códigos novos
            prefs(context).edit().putLong(KEY_TOTP_SKEW, skewMillis).apply()
        }.isSuccess
    }

    /**
     * Reset de fábrica do cofre: apaga o método selecionado, verifiers de PIN/desenho, o flag de
     * bloqueio do app, todos os embrulhos de notas, o TOTP e os códigos de recuperação. Mantém o
     * `device-id` e a chave do banco no [SecureSecretStore] — o banco precisa continuar decifrável.
     */
    fun resetAll(context: Context) {
        // clear() depois putString: o estado final fica com unlock_method = "NONE" (nenhum método
        // selecionado) e nenhuma credencial, embrulho, bloqueio de app ou método anterior.
        prefs(context).edit().clear().putString(KEY_METHOD, "NONE").commit()
        val store = SecureSecretStore(context)
        store.remove(KEY_TOTP_SECRET)
        store.remove(KEY_RECOVERY_CODES_LEGACY)
        // Higiene: o alias Keystore de embrulho biométrico não tem mais wraps; apaga-o (será
        // recriado por getOrCreateKey no próximo wrap). Nunca tocar no secret-store.v1.
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Confere o código de 6 dígitos aplicando o descompasso de relógio guardado no setup e com
     * tolerância ampla (±5 min) — aceita o código mesmo se o relógio do autenticador divergir.
     */
    fun verifyTotp(context: Context, code: String): Boolean {
        val secret = totpSecret(context) ?: return false
        val skew = totpSkewMillis(context)
        return try {
            Totp.verify(secret, code, System.currentTimeMillis() + skew, window = Totp.UNLOCK_WINDOW)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Embrulha sem pedir código: quem está no app já está autenticado, e a chave de embrulho
     * vem do segredo estável (não do código, que roda a cada 30s).
     */
    fun wrapWithTotp(context: Context, noteId: String, password: String): Boolean {
        val secret = totpSecret(context) ?: return false
        val key = totpWrapKey(secret)
        return try {
            val stored = wrapPasswordWithKey(password, key)
            prefs(context).edit().putString(KEY_WRAPPED_PREFIX + noteId, stored).apply()
            true
        } finally {
            key.fill(0)
            secret.fill(0)
        }
    }

    fun unwrapWithTotp(context: Context, noteId: String, code: String): String? {
        if (!verifyTotp(context, code)) return null
        val stored = loadWrapped(context, noteId) ?: return null
        val secret = totpSecret(context) ?: return null
        val key = totpWrapKey(secret)
        return try {
            unwrapPasswordWithKey(stored, key)
        } finally {
            key.fill(0)
            secret.fill(0)
        }
    }

    internal fun totpWrapKey(secret: ByteArray): ByteArray =
        sha256(secret + "wrap".toByteArray(Charsets.UTF_8))

    // ---- Códigos de recuperação (uso único, para quando o autenticador for perdido) ----
    //
    // Padrão dos serviços (Google, GitHub, Authy): os códigos são MOSTRADOS UMA ÚNICA VEZ no
    // setup e o que fica guardado são apenas hashes SHA-256. O texto nunca é armazenado nem
    // reexibido — assim ninguém com acesso ao aparelho consegue ler os códigos de volta.
    // Cada código é de uso único (o hash é removido ao ser usado); gerar um conjunto novo
    // invalida o anterior.

    private const val KEY_RECOVERY_PREFIX = "totp-recovery."
    const val RECOVERY_CODE_COUNT = 8
    private const val RECOVERY_CODE_GROUPS = 3
    private const val RECOVERY_CODE_GROUP_LEN = 4
    private const val RECOVERY_CODE_LEN = RECOVERY_CODE_GROUPS * RECOVERY_CODE_GROUP_LEN // 12
    // 32 caracteres sem ambíguos (I/L/O/0/1): cada código tem 32^12 ≈ 2^60 combinações.
    private const val RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Código novo: 12 caracteres do alfabeto sem ambíguos. Puro, testável. */
    internal fun newRecoveryCode(): String = buildString {
        repeat(RECOVERY_CODE_LEN) { append(RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)]) }
    }

    /** Normaliza o que a pessoa digitou: maiúsculas, sem hífens/espaços. Puro, testável. */
    internal fun normalizeRecoveryCode(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "")

    /**
     * Gera códigos de recuperação novos e guarda apenas o SHA-256 de cada um. Os códigos em texto
     * são devolvidos uma única vez (para a tela do setup); depois disso o texto não existe mais no
     * aparelho — perdê-los significa reconfigurar o TOTP, que invalida o conjunto antigo.
     */
    fun createRecoveryCodes(context: Context): List<String> {
        val codes = List(RECOVERY_CODE_COUNT) { newRecoveryCode() }
        val editor = prefs(context).edit()
        codes.forEachIndexed { i, code ->
            editor.putString(KEY_RECOVERY_PREFIX + i, b64(sha256(code.toByteArray(Charsets.UTF_8))))
        }
        editor.apply()
        return codes
    }

    /**
     * Confere um código de recuperação e o **consome** (uso único). Faz SHA-256 do que foi
     * digitado e compara em tempo constante contra os hashes guardados; remove o hash usado.
     */
    fun verifyRecoveryCode(context: Context, raw: String): Boolean {
        val code = normalizeRecoveryCode(raw)
        if (code.length != RECOVERY_CODE_LEN) return false
        val input = sha256(code.toByteArray(Charsets.UTF_8))
        for (i in 0 until RECOVERY_CODE_COUNT) {
            val stored = prefs(context).getString(KEY_RECOVERY_PREFIX + i, null) ?: continue
            if (MessageDigest.isEqual(unb64(stored), input)) {
                prefs(context).edit().remove(KEY_RECOVERY_PREFIX + i).apply()
                return true
            }
        }
        return false
    }

    // Chave usada na versão antiga (guardava o texto dos códigos, cifrado): removida para não
    // deixar códigos recuperáveis de instalações anteriores.
    private const val KEY_RECOVERY_CODES_LEGACY = "totp-recovery-codes"

    private fun clearRecoveryCodes(context: Context) {
        SecureSecretStore(context).remove(KEY_RECOVERY_CODES_LEGACY)
        val editor = prefs(context).edit()
        for (i in 0 until RECOVERY_CODE_COUNT) editor.remove(KEY_RECOVERY_PREFIX + i)
        editor.apply()
    }

    /**
     * Desembrulha todas as [noteIds] com um único código de recuperação: o código prova a
     * identidade (e é consumido), e a chave de embrulho vem do segredo TOTP estável (ainda no
     * aparelho, cifrado em repouso) — não depende do código rotativo.
     */
    fun unwrapAllWithRecoveryCode(context: Context, noteIds: List<String>, code: String): Map<String, String> {
        if (!verifyRecoveryCode(context, code)) return emptyMap()
        val secret = totpSecret(context) ?: return emptyMap()
        val key = totpWrapKey(secret)
        return try {
            val result = LinkedHashMap<String, String>()
            for (id in noteIds) {
                val stored = loadWrapped(context, id) ?: continue
                unwrapPasswordWithKey(stored, key)?.let { result[id] = it }
            }
            result
        } finally {
            key.fill(0)
            secret.fill(0)
        }
    }

    fun unwrapWithRecoveryCode(context: Context, noteId: String, code: String): String? =
        unwrapAllWithRecoveryCode(context, listOf(noteId), code)[noteId]

    /**
     * Desembrulha todas as [noteIds] com o segredo TOTP **atualmente armazenado**, sem pedir código.
     * Usado só dentro de um fluxo já autenticado (reconfigurar o TOTP após provar o código atual ou
     * um código de recuperação): a chave de embrulho deriva do segredo estável, então recuperar com
     * o segredo antigo é o que permite re-embrulhar com o novo sem perder as notas.
     */
    fun unwrapAllWithStoredTotp(context: Context, noteIds: List<String>): Map<String, String> {
        val secret = totpSecret(context) ?: return emptyMap()
        val key = totpWrapKey(secret)
        return try {
            val result = LinkedHashMap<String, String>()
            for (id in noteIds) {
                val stored = loadWrapped(context, id) ?: continue
                unwrapPasswordWithKey(stored, key)?.let { result[id] = it }
            }
            result
        } finally {
            key.fill(0)
            secret.fill(0)
        }
    }

    // ---- Embrulho de senha (formato "iv:ct", Base64) ----

    fun hasWrapped(context: Context, noteId: String): Boolean =
        prefs(context).contains(KEY_WRAPPED_PREFIX + noteId)

    /** IDs das notas que têm uma senha embrulhada (usado para migrar ao trocar o método). */
    fun wrappedNoteIds(context: Context): List<String> =
        prefs(context).all.keys
            .filter { it.startsWith(KEY_WRAPPED_PREFIX) }
            .map { it.removePrefix(KEY_WRAPPED_PREFIX) }

    fun loadWrapped(context: Context, noteId: String): String? =
        prefs(context).getString(KEY_WRAPPED_PREFIX + noteId, null)

    fun removeWrapped(context: Context, noteId: String) {
        prefs(context).edit().remove(KEY_WRAPPED_PREFIX + noteId).apply()
    }

    fun clearWrapped(context: Context) {
        val editor = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith(KEY_WRAPPED_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    // ---- Embrulho via Keystore (biometria) ----

    /** Cifra em ENCRYPT_MODE pronta para o BiometricPrompt (a chave só é liberada ao autenticar). */
    fun prepareWrapCipher(context: Context): Cipher? {
        val key = getOrCreateKey() ?: return null
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()
    }

    /** Cifra em DECRYPT_MODE com o IV do embrulho armazenado. Null se a chave foi invalidada. */
    fun prepareUnwrapCipher(context: Context, stored: String): Cipher? {
        val iv = parseStored(stored)?.first ?: return null
        val key = getKey() ?: return null
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        }.getOrNull()
    }

    fun finishWrap(context: Context, noteId: String, password: String, cipher: Cipher): Boolean = runCatching {
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val stored = "${b64(cipher.iv)}:${b64(ct)}"
        prefs(context).edit().putString(KEY_WRAPPED_PREFIX + noteId, stored).apply()
    }.isSuccess

    fun finishUnwrap(stored: String, cipher: Cipher): String? {
        val ct = parseStored(stored)?.second ?: return null
        return runCatching { String(cipher.doFinal(ct), Charsets.UTF_8) }.getOrNull()
    }

    // ---- Embrulho via PIN ----

    fun wrapWithPin(context: Context, noteId: String, password: String, pin: String): Boolean {
        if (!verifyPin(context, pin)) return false
        val salt = loadPinSalt(context) ?: return false
        val key = pinWrapKey(pin, salt)
        return try {
            val stored = wrapPasswordWithKey(password, key)
            prefs(context).edit().putString(KEY_WRAPPED_PREFIX + noteId, stored).apply()
            true
        } finally {
            key.fill(0)
        }
    }

    fun unwrapWithPin(context: Context, noteId: String, pin: String): String? {
        if (!verifyPin(context, pin)) return null
        val stored = loadWrapped(context, noteId) ?: return null
        val salt = loadPinSalt(context) ?: return null
        val key = pinWrapKey(pin, salt)
        return try {
            unwrapPasswordWithKey(stored, key)
        } finally {
            key.fill(0)
        }
    }

    // ---- Embrulho via desenho ----

    fun wrapWithPattern(context: Context, noteId: String, password: String, pattern: String): Boolean {
        if (!verifyPattern(context, pattern)) return false
        val salt = loadPatternSalt(context) ?: return false
        val key = patternWrapKey(pattern, salt)
        return try {
            val stored = wrapPasswordWithKey(password, key)
            prefs(context).edit().putString(KEY_WRAPPED_PREFIX + noteId, stored).apply()
            true
        } finally {
            key.fill(0)
        }
    }

    fun unwrapWithPattern(context: Context, noteId: String, pattern: String): String? {
        if (!verifyPattern(context, pattern)) return null
        val stored = loadWrapped(context, noteId) ?: return null
        val salt = loadPatternSalt(context) ?: return null
        val key = patternWrapKey(pattern, salt)
        return try {
            unwrapPasswordWithKey(stored, key)
        } finally {
            key.fill(0)
        }
    }

    // ---- Núcleo criptográfico (testável sem Android) ----

    internal fun wrapPasswordWithKey(password: String, key: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val ct = encryptAesGcm(password.toByteArray(Charsets.UTF_8), key, iv)
        return "${b64(iv)}:${b64(ct)}"
    }

    internal fun unwrapPasswordWithKey(stored: String, key: ByteArray): String? {
        val (iv, ct) = parseStored(stored) ?: return null
        return runCatching { String(decryptAesGcm(ct, key, iv), Charsets.UTF_8) }.getOrNull()
    }

    private fun encryptAesGcm(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(plain)
        }

    private fun decryptAesGcm(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ct)
        }

    // ---- Chave do Keystore ----

    private fun getOrCreateKey(): SecretKey? {
        getKey()?.let { return it }
        return runCatching {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Manter a chave ao reconfigurar a biometria evita perder o acesso às notas
                // protegidas (a chave embrulha o segredo de cada nota).
                .setInvalidatedByBiometricEnrollment(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                builder.setUserAuthenticationRequired(true)
            }
            generator.init(builder.build())
            generator.generateKey()
        }.getOrNull()
    }

    private fun getKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    // ---- Utilidades ----

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseStored(stored: String): Pair<ByteArray, ByteArray>? {
        val parts = stored.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching { unb64(parts[0]) to unb64(parts[1]) }.getOrNull()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
