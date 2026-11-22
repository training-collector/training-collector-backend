package com.tc.gymtrainer.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object Credentials {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private const val rounds = 600_000

    fun token(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
    fun digest(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    fun hashLoginCode(code: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return listOf(rounds.toString(), encoder.encodeToString(salt), encoder.encodeToString(derive(code, salt, rounds))).joinToString(":")
    }

    fun matchesLoginCode(code: String, stored: String): Boolean {
        val (iterations, salt, expected) = stored.split(':')
        return MessageDigest.isEqual(decoder.decode(expected), derive(code, decoder.decode(salt), iterations.toInt()))
    }

    private fun derive(code: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
