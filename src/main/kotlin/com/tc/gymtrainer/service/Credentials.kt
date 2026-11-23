package com.tc.gymtrainer.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Credentials {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun token(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
    /** 128 random bits: short enough for Telegram button data. */
    fun shortToken(): String = encoder.encodeToString(ByteArray(16).also(random::nextBytes))
    fun number(range: IntRange): Int = range.first + random.nextInt(range.last - range.first + 1)
    fun digest(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
}

/** Kinds of bearer tokens. The kind is part of the signed token text. */
enum class TokenKind(val prefix: String) { SESSION("session"), SIGNUP("signup"), API_KEY("key"), ADMIN("admin") }

/**
 * A key derived from the server secret. Tokens carry a tag that is checked before any
 * database lookup: a forged or random token costs no query.
 */
class Secrets(seed: String) {
    private val tagKey = derive(seed, "training-collector/token-tag/v1")

    private fun derive(seed: String, label: String) = hmac(seed.toByteArray(Charsets.UTF_8), label)
    private fun hmac(key: ByteArray, value: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(value.toByteArray(Charsets.UTF_8))

    fun issue(kind: TokenKind): String {
        val body = kind.prefix + "." + Credentials.token()
        return body + "." + tag(body)
    }

    /** The kind of a token issued by this service, or null for anything else. */
    fun kindOf(token: String): TokenKind? {
        if (token.length > 128) return null
        val body = token.substringBeforeLast('.', "")
        val kind = TokenKind.entries.find { it.prefix == body.substringBefore('.') } ?: return null
        val valid = MessageDigest.isEqual(tag(body).toByteArray(), token.substringAfterLast('.').toByteArray())
        return kind.takeIf { valid }
    }

    private fun tag(body: String) = Credentials.encode(hmac(tagKey, body).copyOf(12))

    companion object {
        /** A per-process secret for tests and tools that do not load the application config. */
        val ephemeral: Secrets by lazy { Secrets(Credentials.token()) }
    }
}
