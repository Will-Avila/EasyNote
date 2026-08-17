package com.will.noteharbor.data

import android.content.Context
import java.security.MessageDigest
import java.util.Base64

/**
 * Pacote de segurança embutido no backup na nuvem: o mapa de [UnlockVault.PREFS] (método de
 * desbloqueio, verifiers de PIN/desenho, embrulhos `wrapped.<nota>`, hashes de recuperação TOTP)
 * mais o segredo TOTP em texto puro. Tudo cifrado com AES-256-GCM por uma chave derivada de uma
 * **senha de recuperação** escolhida pelo usuário (Argon2id, determinística — portável entre
 * aparelhos). Assim, depois de limpar os dados do app ou trocar de celular, digitar a senha
 * restaura o acesso às notas protegidas.
 *
 * O pacote vive no backup dentro de `database_metadata` (chave [METADATA_KEY]) e é opaco no
 * arquivo do Drive: sem a senha de recuperação, nada nele é legível. A chave derivada fica
 * guardada no [SecureSecretStore] deste aparelho para a sincronização automática cifrar o pacote
 * sem pedir a senha de novo.
 */
object SecurityRecovery {
    const val MIN_PASSPHRASE = 8

    /** Chave em `database_metadata` do banco de backup (vivo e de nuvem). */
    const val METADATA_KEY = "security-state-v1"

    private const val SECURE_STORE_KEY = "security-recovery-key"
    private const val PAYLOAD_TOTP_KEY = "__totp_secret"
    /** Envelope portável: cifrado por senha de recuperação (qualquer aparelho). */
    private const val MAGIC = "NOTSRC1"
    /** Envelope local: cifrado pela chave do aparelho (Keystore) — restaura no mesmo aparelho após
     *  limpar dados, sem exigir senha; não viaja para outro aparelho. */
    private const val MAGIC_LOCAL = "NOTSRC2"
    private const val VERSION: Byte = 1
    private const val DOMAIN = "noteharbor-security-recovery-v1"

    // Salt fixo em código: a chave é determinística e rederivável em qualquer aparelho a partir
    // apenas da senha. Argon2id já protege contra força bruta e não há comparação entre usuários
    // que torne o salt compartilhado um problema.
    private val FIXED_SALT = "noteharbor-recovery-salt-v1".toByteArray(Charsets.UTF_8)

    // ---- Derivação de chave ----

    /** Deriva a chave de 32 bytes a partir da senha de recuperação. Determinística (salt fixo). */
    internal fun deriveKey(passphrase: String): ByteArray {
        val input = (DOMAIN + passphrase).toByteArray(Charsets.UTF_8)
        return NoteEncryption.keyDeriver(input, FIXED_SALT, NoteEncryption.ARGON2_T, NoteEncryption.ARGON2_M_KIB, NoteEncryption.ARGON2_P)
    }

    // ---- Chave no aparelho (para a sync automática cifrar sem pedir a senha) ----

    fun hasKey(context: Context): Boolean = SecureSecretStore(context).contains(SECURE_STORE_KEY)

    fun setKey(context: Context, passphrase: String): Boolean {
        if (passphrase.length < MIN_PASSPHRASE) return false
        val key = deriveKey(passphrase)
        return try {
            SecureSecretStore(context).putBytes(SECURE_STORE_KEY, key)
            true
        } finally {
            key.fill(0)
        }
    }

    fun removeKey(context: Context) {
        SecureSecretStore(context).remove(SECURE_STORE_KEY)
    }

    /** Confere uma senha de recuperação contra a chave guardada (autorizar a troca). */
    fun verifyPassphrase(context: Context, passphrase: String): Boolean {
        val stored = SecureSecretStore(context).getBytes(SECURE_STORE_KEY) ?: return false
        val derived = deriveKey(passphrase)
        return try {
            stored.size == derived.size && MessageDigest.isEqual(stored, derived)
        } finally {
            stored.fill(0)
            derived.fill(0)
        }
    }

    /** Verdadeiro quando o aparelho não tem NENHUMA configuração de segurança (pós-limpeza/instalação). */
    fun isFresh(context: Context): Boolean = prefs(context).all.isEmpty()

    // ---- Segredos de nota recuperáveis ----
    //
    // O segredo aleatório que cifra cada nota protegida fica, além de embrulhado pelo método
    // (`wrapped.<id>`), guardado aqui de forma recuperável — cifrado pela chave do aparelho no
    // [SecureSecretStore]. É o que permite re-embrulhar notas protegidas por **biometria** após uma
    // restauração: a chave biométrica do Keystore não viaja no envelope, então o segredo precisa de
    // um caminho de volta independente dela (e este caminho, por sua vez, viaja dentro do envelope).

    private const val NOTE_SECRET_PREFIX = "note-secret."

    /** Guarda o segredo de uma nota protegida de forma recuperável (cifrado em repouso pela chave do aparelho). */
    fun storeNoteSecret(context: Context, noteId: String, secret: String) {
        SecureSecretStore(context).putString(NOTE_SECRET_PREFIX + noteId, secret)
    }

    /** Lê o segredo recuperável de uma nota, ou null se não houver. */
    fun noteSecret(context: Context, noteId: String): String? =
        SecureSecretStore(context).getString(NOTE_SECRET_PREFIX + noteId)

    fun removeNoteSecret(context: Context, noteId: String) {
        SecureSecretStore(context).remove(NOTE_SECRET_PREFIX + noteId)
    }

    /** Apaga todos os segredos de nota recuperáveis (reset de fábrica). */
    fun clearNoteSecrets(context: Context) {
        val store = SecureSecretStore(context)
        noteSecretIds(context).forEach { store.remove(NOTE_SECRET_PREFIX + it) }
    }

    /** IDs das notas com segredo recuperável guardado. */
    fun noteSecretIds(context: Context): List<String> =
        SecureSecretStore(context).keys()
            .filter { it.startsWith(NOTE_SECRET_PREFIX) }
            .map { it.removePrefix(NOTE_SECRET_PREFIX) }

    // ---- Codec do pacote (puro, testável em JVM) ----

    internal data class ParsedPayload(val map: Map<String, Any>, val totpSecretB64: String?)

    /**
     * Serializa o mapa de segurança em linhas `chave|T|valor`. Os valores conhecidos (base64 sem
     * padding, nomes de enum, números, booleanos) nunca contêm `|` nem `\n`, então o formato é
     * seguro. O segredo TOTP entra sob [PAYLOAD_TOTP_KEY] como string base64.
     */
    internal fun buildPayload(map: Map<String, Any?>, totpSecretB64: String?): String = buildString {
        map.forEach { (key, value) ->
            when (value) {
                is String -> append(key).append('|').append('S').append('|').append(value).append('\n')
                is Long -> append(key).append('|').append('L').append('|').append(value).append('\n')
                is Boolean -> append(key).append('|').append('B').append('|').append(value).append('\n')
                else -> Unit // tipo desconhecido: ignora
            }
        }
        totpSecretB64?.let {
            append(PAYLOAD_TOTP_KEY).append('|').append('S').append('|').append(it).append('\n')
        }
    }

    internal fun parsePayload(raw: String): ParsedPayload {
        val map = LinkedHashMap<String, Any>()
        var totp: String? = null
        raw.split('\n').forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('|', limit = 3)
            if (parts.size != 3) return@forEach
            val (key, type, value) = parts
            if (key == PAYLOAD_TOTP_KEY) {
                totp = value
                return@forEach
            }
            map[key] = when (type) {
                "L" -> value.toLongOrNull() ?: return@forEach
                "B" -> value.toBoolean()
                else -> value
            }
        }
        return ParsedPayload(map, totp)
    }

    // ---- Cifrar para o backup / restaurar do backup ----

    /**
     * Cifra o estado de segurança atual para embutir no backup. Retorna null quando não há nada a
     * proteger (prefs vazias). Duas formas:
     *  - Com senha de recuperação definida: envelope [MAGIC] portável — qualquer aparelho, via senha.
     *  - Sem senha de recuperação: envelope [MAGIC_LOCAL] com a chave do aparelho — restaura sozinho
     *    no mesmo aparelho após "limpar dados" (a chave do Keystore sobrevive à limpeza), mas não
     *    viaja para outro aparelho (lá exige a senha de recuperação).
     */
    fun encryptCurrent(context: Context): String? {
        val store = SecureSecretStore(context)
        val secret = store.getBytes(UnlockVault.KEY_TOTP_SECRET)
        try {
            val map = prefs(context).all
            if (map.isEmpty()) return null
            // Além do mapa de segurança (método + embrulhos), leva os segredos de nota recuperáveis:
            // sem eles, uma nota protegida por biometria fica irrecuperável após a restauração, pois
            // a chave do Keystore que embrulha seu segredo não viaja no envelope.
            val combined = LinkedHashMap<String, Any?>(map)
            noteSecretIds(context).forEach { id ->
                store.getString(NOTE_SECRET_PREFIX + id)?.let { combined[NOTE_SECRET_PREFIX + id] = it }
            }
            val payload = buildPayload(combined, secret?.let { b64(it) })
            if (payload.isBlank()) return null
            val plain = payload.toByteArray(Charsets.UTF_8)
            val recoveryKey = store.getBytes(SECURE_STORE_KEY)
            return if (recoveryKey != null) {
                try {
                    val ciphertext = NoteEncryption.encryptBytes(plain, recoveryKey)
                    b64(MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION) + ciphertext)
                } finally {
                    recoveryKey.fill(0)
                }
            } else {
                val ct = store.encryptWithDeviceKey(plain) ?: return null
                b64(MAGIC_LOCAL.toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION) + ct)
            }
        } finally {
            secret?.fill(0)
        }
    }

    /**
     * Decifra [envelope] com a [passphrase] e restaura o estado de segurança nas preferências.
     * Nunca lança: retorna false em senha incorreta, envelope inválido ou falha de gravação.
     */
    fun restore(context: Context, passphrase: String, envelope: String): Boolean {
        if (passphrase.length < MIN_PASSPHRASE) return false
        val key = deriveKey(passphrase)
        val result = try {
            applyEnvelope(context, key, envelope)
        } catch (_: Exception) {
            false
        } finally {
            key.fill(0)
        }
        return result
    }

    /**
     * Tenta restaurar a segurança com a chave do aparelho (envelope local, mesmo aparelho após
     * "limpar dados"). Não exige senha e não guarda chave de recuperação (não há senha): as
     * próximas syncs voltam a cifrar o envelope local sozinhas. False quando o envelope não é o
     * local, é de outro aparelho, ou está corrompido.
     */
    fun tryDeviceRestore(context: Context, envelope: String): Boolean {
        val decoded = runCatching { unb64(envelope) }.getOrNull() ?: return false
        val magic = MAGIC_LOCAL.toByteArray(Charsets.US_ASCII)
        if (decoded.size <= magic.size + 1 || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) return false
        if (decoded[magic.size] != VERSION) return false
        val ciphertext = decoded.copyOfRange(magic.size + 1, decoded.size)
        val plain = SecureSecretStore(context).decryptWithDeviceKey(ciphertext) ?: return false
        return applyPlain(context, plain)
    }

    /**
     * Verdadeiro quando [envelope] é o portável (senha de recuperação) — o único que o diálogo de
     * restauração consegue decifrar. Envelopes locais de OUTRO aparelho não têm senha que os libere.
     */
    fun isPassphraseEnvelope(envelope: String): Boolean {
        val decoded = runCatching { unb64(envelope) }.getOrNull() ?: return false
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        return decoded.size > magic.size + 1 &&
            decoded.copyOfRange(0, magic.size).contentEquals(magic) &&
            decoded[magic.size] == VERSION
    }

    /**
     * Decifra [envelope] com [key] e grava o estado de segurança. As exceções ficam para o caller
     * (senha errada → falha da tag GCM; envelope inválido → false).
     */
    private fun applyEnvelope(context: Context, key: ByteArray, envelope: String): Boolean {
        val decoded = runCatching { unb64(envelope) }.getOrNull() ?: return false
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        if (decoded.size <= magic.size + 1 || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) return false
        if (decoded[magic.size] != VERSION) return false
        val ciphertext = decoded.copyOfRange(magic.size + 1, decoded.size)
        val plain = try {
            NoteEncryption.decryptBytes(ciphertext, key)
        } catch (_: Exception) {
            return false
        }
        if (!applyPlain(context, plain)) return false
        // Re-guarda a chave derivada: a partir daqui a sync volta a cifrar automaticamente.
        SecureSecretStore(context).putBytes(SECURE_STORE_KEY, key)
        return true
    }

    /** Grava o mapa de segurança decifrado nas preferências + o segredo TOTP no SecureSecretStore.
     *  Base compartilhada dos dois caminhos de restauração (senha de recuperação e envelope local). */
    private fun applyPlain(context: Context, plain: ByteArray): Boolean {
        val parsed = parsePayload(String(plain, Charsets.UTF_8))
        val store = SecureSecretStore(context)
        val editor = prefs(context).edit()
        parsed.map.forEach { (k, v) ->
            when {
                // Segredos de nota são recuperáveis: vão para o SecureSecretStore (cifrados em
                // repouso), não para as preferências em texto puro.
                k.startsWith(NOTE_SECRET_PREFIX) -> if (v is String) store.putString(k, v)
                v is String -> editor.putString(k, v)
                v is Long -> editor.putLong(k, v)
                v is Boolean -> editor.putBoolean(k, v)
            }
        }
        if (!editor.commit()) return false
        parsed.totpSecretB64?.let { totp ->
            val bytes = unb64(totp)
            try {
                store.putBytes(UnlockVault.KEY_TOTP_SECRET, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(UnlockVault.PREFS, Context.MODE_PRIVATE)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)
}
