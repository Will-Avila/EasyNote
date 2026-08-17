package com.will.noteharbor.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238) — senha de uso único baseada em tempo, o padrão dos apps de autenticação
 * (Google Authenticator, Authy, Bitwarden, etc.). Puro Kotlin/JVM, testável sem Android.
 *
 * O segredo é o "shared secret" de 20 bytes (base32 no QR/otpauth). O código é HMAC-SHA1 do
 * contador (tempo / 30s) truncado para 6 dígitos. As funções recebem [timeMillis] para serem
 * determinísticas em teste; os chamadores passam `System.currentTimeMillis()`.
 */
object Totp {
    const val DIGITS = 6
    const val STEP_SECONDS = 30L
    const val WINDOW = 1
    const val SECRET_BYTES = 20

    /** Janela ampla de verificação de desbloqueio (±5 min): tolera relógio divergente. */
    const val UNLOCK_WINDOW = 10

    /** Busca de descompasso de relógio no setup (±10 min): cobre aparelhos com relógio bem divergente. */
    const val MAX_SKEW_STEPS = 20

    private const val HMAC_ALG = "HmacSHA1"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val random = SecureRandom()

    /** Novo segredo TOTP: 20 bytes aleatórios (160 bits — o tamanho clássico do RFC 6238). */
    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    /** Codifica o segredo em base32 (RFC 4648) sem padding — o formato aceito pelos autenticadores. */
    fun base32(secret: ByteArray): String {
        val sb = StringBuilder((secret.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in secret) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }

    /** Gera o código de [digits] dígitos no instante [timeMillis] (RFC 6238, HMAC-SHA1). */
    fun generate(
        secret: ByteArray,
        timeMillis: Long,
        digits: Int = DIGITS,
        stepSeconds: Long = STEP_SECONDS,
    ): String {
        val counter = timeMillis / 1000 / stepSeconds
        // Contador de 64 bits em big-endian, como exige o RFC 6238.
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance(HMAC_ALG).apply { init(SecretKeySpec(secret, HMAC_ALG)) }
        val hash = mac.doFinal(counterBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /** Confere [code] contra o segredo no instante [timeMillis], tolerando ±[window] passos de 30s. */
    fun verify(
        secret: ByteArray,
        code: String,
        timeMillis: Long,
        window: Int = WINDOW,
        digits: Int = DIGITS,
        stepSeconds: Long = STEP_SECONDS,
    ): Boolean {
        if (code.length != digits || code.any { it !in '0'..'9' }) return false
        for (i in -window..window) {
            val candidate = generate(secret, timeMillis + i * stepSeconds * 1000, digits, stepSeconds)
            // Comparação em tempo constante, mesmo comprimento já garantido.
            if (MessageDigest.isEqual(candidate.toByteArray(), code.toByteArray())) return true
        }
        return false
    }

    /**
     * Descobre o descompasso de relógio (em ms) entre o autenticador e este aparelho: procura em
     * qual passo de 30s o [code] digitado encaixa em volta de [timeMillis]. Usado no setup para
     * guardar o offset e aceitar o código mesmo com relógios divergentes (ex.: o autenticador em
     * outro aparelho). Null se o código não encaixar em nenhum passo dentro de ±[maxSteps].
     */
    fun findSkew(
        secret: ByteArray,
        code: String,
        timeMillis: Long,
        maxSteps: Int = MAX_SKEW_STEPS,
    ): Long? {
        if (code.length != DIGITS || code.any { it !in '0'..'9' }) return null
        for (s in -maxSteps..maxSteps) {
            if (generate(secret, timeMillis + s * STEP_SECONDS * 1000) == code) {
                return s * STEP_SECONDS * 1000
            }
        }
        return null
    }

    /** URI `otpauth://` que vai no QR code — o que os autenticadores escaneiam. */
    fun otpauthUri(
        secretBase32: String,
        account: String = "Notas",
        issuer: String = "Notas",
    ): String =
        "otpauth://totp/$issuer:$account?secret=$secretBase32&issuer=$issuer" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
}
