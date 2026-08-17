package com.will.noteharbor.data

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * Palavra aleatória de confirmação do reset de fábrica ("Redefinir aplicativo"): antes de apagar
 * tudo, o usuário precisa digitar uma palavra sorteada desta lista. É a prova de intenção — sem
 * ela, qualquer um com o aparelho zeraria o app por acidente. Puro Kotlin/JVM, testável sem Android.
 */
object AppReset {
    /** Palavras simples, sem acento — fáceis de digitar no teclado do sistema. */
    val WORDS: List<String> = listOf(
        "FOGUETE", "GIRASSOL", "LARANJA", "OCEANO", "HORIZONTE",
        "TESOURA", "MARTELO", "SOLDADO", "CAVERNA", "TELHADO",
    )

    /** Sorteia a palavra que o usuário precisa digitar para confirmar o reset. */
    fun randomConfirmationWord(): String = WORDS[Random.nextInt(WORDS.size)]

    /** Confere o que foi digitado contra a palavra exibida, ignorando caixa, espaços e acentos. */
    fun matches(word: String, typed: String): Boolean = normalize(word) == normalize(typed)

    internal fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()
            .uppercase(Locale.ROOT)
}
