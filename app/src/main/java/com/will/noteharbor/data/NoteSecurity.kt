package com.will.noteharbor.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object NoteSecurity {
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun hash(password: String): String {
        require(password.isNotBlank()) { "Password cannot be blank" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = digest(password, salt)
        val encoder = Base64.getEncoder().withoutPadding()
        return "${encoder.encodeToString(salt)}$${encoder.encodeToString(digest)}"
    }

    fun matches(password: String, storedHash: String): Boolean {
        val separator = storedHash.indexOf('$')
        if (separator <= 0 || separator == storedHash.lastIndex) return false
        return runCatching {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(storedHash.substring(0, separator))
            val expected = decoder.decode(storedHash.substring(separator + 1))
            MessageDigest.isEqual(expected, digest(password, salt))
        }.getOrDefault(false)
    }

    private fun digest(password: String, salt: ByteArray): ByteArray {
        val input = salt + password.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input)
    }
}
