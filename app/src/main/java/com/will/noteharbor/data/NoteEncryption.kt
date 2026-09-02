package com.will.noteharbor.data

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Lançada quando o envelope não pode ser descriptografado (senha incorreta ou dados corrompidos). */
class NoteDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Criptografia do conteúdo das notas protegidas.
 *
 * O conteúdo (body + itens de checklist) é serializado em JSON e cifrado com AES-256-GCM,
 * usando uma chave derivada da senha da nota via Argon2id. O resultado é um envelope JSON
 * versionado, codificado em Base64:
 *
 * ```json
 * { "v":1, "alg":"AES-256-GCM", "kdf":"argon2id", "t":3, "m":65536, "p":1,
 *   "salt":"<b64>", "iv":"<b64>", "ct":"<b64, ciphertext + tag GCM>" }
 * ```
 *
 * Os campos `v`/`t`/`m`/`p`/`kdf` permitem evoluir os parâmetros no futuro sem quebrar
 * envelopes antigos (cada envelope carrega seus próprios parâmetros).
 */
object NoteEncryption {
    const val ENVELOPE_VERSION = 1
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BYTES = 32
    const val ARGON2_T = 3
    const val ARGON2_M_KIB = 64 * 1024 // 64 MiB
    const val ARGON2_P = 1

    private const val KDF = "argon2id"
    private const val ALG = "AES-256-GCM"
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    data class DecryptedContent(val body: String, val items: List<ChecklistItem>)

    /**
     * Seam de teste: permite trocar o Argon2 (JNI, indisponível no JVM de host) por um KDF
     * puro-JVM nos testes unitários. O valor de produção é [Argon2KeyDerivation::derive].
     */
    internal var keyDeriver: (password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int) -> ByteArray =
        Argon2KeyDerivation::derive

    /** Verdadeiro quando a lib nativa do Argon2 está carregável (usado para gating de testes). */
    internal val argon2Available: Boolean
        get() = runCatching {
            Argon2KeyDerivation.derive(ByteArray(16), ByteArray(16), 1, 8, 1).fill(0)
        }.isSuccess

    /**
     * Gera um segredo aleatório (Base64 de 32 bytes) usado como chave de criptografia de uma nota
     * protegida pelo método de desbloqueio global (biometria/padrão/PIN), sem senha por nota.
     */
    fun newSecret(): String = b64(ByteArray(KEY_BYTES).also(random::nextBytes))

    fun encrypt(body: String, items: List<ChecklistItem>, password: String): String {
        require(password.isNotBlank()) { "Password cannot be blank" }
        val contentBytes = encodeContent(body, items).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val key = try {
            keyDeriver(passBytes, salt, ARGON2_T, ARGON2_M_KIB, ARGON2_P)
        } finally {
            passBytes.fill(0)
        }
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(contentBytes)
        } finally {
            key.fill(0)
            contentBytes.fill(0)
        }
        val envelope = JSONObject().apply {
            put("v", ENVELOPE_VERSION)
            put("alg", ALG)
            put("kdf", KDF)
            put("t", ARGON2_T)
            put("m", ARGON2_M_KIB)
            put("p", ARGON2_P)
            put("salt", b64(salt))
            put("iv", b64(iv))
            put("ct", b64(ciphertext))
        }
        return b64(envelope.toString().toByteArray(Charsets.UTF_8))
    }

    fun decrypt(envelope: String, password: String): DecryptedContent {
        val json = try {
            JSONObject(String(unb64(envelope), Charsets.UTF_8))
        } catch (e: Exception) {
            throw NoteDecryptionException("Envelope inválido", e)
        }
        if (json.optInt("v", -1) != ENVELOPE_VERSION ||
            json.optString("kdf") != KDF ||
            json.optString("alg") != ALG
        ) {
            throw NoteDecryptionException("Versão ou algoritmo do envelope não suportado")
        }
        val t = json.optInt("t", ARGON2_T)
        val m = json.optInt("m", ARGON2_M_KIB)
        val p = json.optInt("p", ARGON2_P)
        val salt = unb64(json.optString("salt"))
        val iv = unb64(json.optString("iv"))
        val ciphertext = unb64(json.optString("ct"))

        val passBytes = password.toByteArray(Charsets.UTF_8)
        val key = try {
            keyDeriver(passBytes, salt, t, m, p)
        } finally {
            passBytes.fill(0)
        }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw NoteDecryptionException("Não foi possível descriptografar (senha incorreta?)", e)
        } finally {
            key.fill(0)
        }
        return try {
            decodeContent(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw NoteDecryptionException("Conteúdo descriptografado inválido", e)
        } finally {
            plaintext.fill(0)
        }
    }
    /**
     * Chave AES de 32 bytes para cifrar arquivos anexados a uma nota protegida. Deriva do segredo
     * aleatório da nota (que já é uma chave forte) com um DOMAIN separador — rápido, sem Argon2,
     * porque o segredo não é uma senha humana.
     */
    internal fun attachmentKey(secret: String): ByteArray {
        val secretBytes = unb64(secret)
        return MessageDigest.getInstance("SHA-256").digest(secretBytes + "attach".toByteArray(Charsets.UTF_8))
            .also { secretBytes.fill(0) }
    }

    /**
     * Cifra [plain] com AES-256-GCM usando [key]. Retorna `iv (12 bytes) + ciphertext (com tag)`.
     * Cada chamada gera um IV aleatório, então textos iguais produzem saídas distintas.
     */
    internal fun encryptBytes(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain)
        return iv + ciphertext
    }

    /** Descriptografa o resultado de [encryptBytes]. Falha (tag inválida) se [key] estiver errada. */
    internal fun decryptBytes(data: ByteArray, key: ByteArray): ByteArray {
        require(data.size > IV_BYTES) { "Dados cifrados inválidos" }
        val iv = data.copyOfRange(0, IV_BYTES)
        val ciphertext = data.copyOfRange(IV_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

    private fun encodeContent(body: String, items: List<ChecklistItem>): String {
        val itemsArray = JSONArray()
        items.forEach { item ->
            itemsArray.put(
                JSONObject().apply {
                    put("text", item.text)
                    put("completed", item.completed)
                },
            )
        }
        return JSONObject().apply {
            put("body", body)
            put("items", itemsArray)
        }.toString()
    }

    private fun decodeContent(raw: String): DecryptedContent {
        val json = JSONObject(raw)
        val itemsArray = json.optJSONArray("items") ?: JSONArray()
        val items = List(itemsArray.length()) { index ->
            val item = itemsArray.getJSONObject(index)
            ChecklistItem(
                text = item.optString("text"),
                completed = item.optBoolean("completed"),
            )
        }
        return DecryptedContent(
            body = json.optString("body"),
            items = items,
        )
    }
}

object Argon2KeyDerivation {
    fun derive(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int): ByteArray =
        Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = t,
            mCostInKibibyte = mKiB,
            parallelism = p,
            hashLengthInBytes = NoteEncryption.KEY_BYTES,
        ).rawHashAsByteArray()
}
