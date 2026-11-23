package com.tc.gymtrainer

import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.service.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class TelegramLoginTest : AbstractDbTest() {
    private val services = TrainingServices()

    @Test
    fun `a login becomes available after cancelling or expiring an unlinked registration`() = runBlocking<Unit> {
        suspend fun register() = services.trainers.registerForWeb("coach", "Анна", "Тест")
        BotRole.LOGIN_CODES.record("auth_test_bot")
        val cancelled = register()
        val link = services.telegramLinks.createSignupLink(cancelled.trainer.id)
        services.trainers.logout(cancelled.token)
        assertNull(services.trainers.authenticate(cancelled.token))
        assertThrows(ValidationException::class.java) {
            runBlocking { services.telegramLinks.confirmSignup(link.url.substringAfter("?start="), 123) }
        }

        val pending = register()
        dbQuery { Trainers.update({ Trainers.id eq pending.trainer.id }) { it[signupExpiresAt] = OffsetDateTime.now().minusMinutes(1) } }
        val linked = register()
        assertNull(services.trainers.authenticate(pending.token))
        val valid = services.telegramLinks.createSignupLink(linked.trainer.id).url.substringAfter("?start=")
        services.telegramLinks.confirmSignup(valid, 123)
        services.trainers.logout(linked.token)
        assertEquals(linked.trainer.id, services.trainers.findByTelegramUserId(123)?.id)
        assertThrows(ConflictException::class.java) { runBlocking { register() } }
    }

    @Test
    fun `links expire and Telegram cannot be reused for another profile`() = runBlocking {
        val first = services.trainers.registerForWeb("first", "Анна", "Тест").trainer
        val second = services.trainers.registerForWeb("second", "Анна", "Тест").trainer
        BotRole.LOGIN_CODES.record("auth_test_bot")
        suspend fun link(id: Long) = services.telegramLinks.createSignupLink(id).url.substringAfter("?start=")
        val expired = link(first.id)
        dbQuery { TelegramLinks.update { it[expiresAt] = OffsetDateTime.now().minusMinutes(1) } }
        assertThrows(ValidationException::class.java) { runBlocking { services.telegramLinks.confirmSignup(expired, 123) } }
        val valid = link(first.id)
        services.telegramLinks.confirmSignup(valid, 123)
        assertThrows(ValidationException::class.java) { runBlocking { services.telegramLinks.confirmSignup(valid, 234) } }
        val other = link(second.id)
        assertEquals(SignupConfirmation.TELEGRAM_IN_USE, services.telegramLinks.confirmSignup(other, 123))
        assertEquals(first.id, services.trainers.findByTelegramUserId(123)?.id)
        assertEquals(SignupConfirmation.PENDING_APPROVAL, services.telegramLinks.confirmSignup(other, 345))
        assertFalse(services.trainers.findByTelegramUserId(345)!!.signupTelegramInUse)
    }

    @Test
    fun `concurrent registrations with the same Telegram notify only the duplicate signup`() = runBlocking {
        BotRole.LOGIN_CODES.record("auth_test_bot")
        val accounts = listOf("one", "two").map { services.trainers.registerForWeb(it, "Имя", "Фамилия") }
        val links = accounts.map { services.telegramLinks.createSignupLink(it.trainer.id).url.substringAfter("?start=") }
        val results = links.map { async { services.telegramLinks.confirmSignup(it, 123) } }.awaitAll()
        assertEquals(setOf(SignupConfirmation.PENDING_APPROVAL, SignupConfirmation.TELEGRAM_IN_USE), results.toSet())
        val duplicate = accounts[results.indexOf(SignupConfirmation.TELEGRAM_IN_USE)]
        val original = accounts[results.indexOf(SignupConfirmation.PENDING_APPROVAL)]
        val pending = services.trainers.authenticate(duplicate.token)!!
        assertTrue(pending.signupTelegramInUse)
        assertNull(pending.telegramUserId)
        assertNull(pending.approvedAt)
        assertFalse(services.trainers.authenticate(original.token)!!.signupTelegramInUse)
        services.trainers.logout(duplicate.token)
        assertEquals(original.trainer.id, services.trainers.findByTelegramUserId(123)!!.id)
    }

    @Test
    fun `approval after the signup link expires schedules a single fresh code`() = runBlocking<Unit> {
        BotRole.LOGIN_CODES.record("codes_bot")
        val account = services.trainers.registerForWeb("long_wait", "Анна", "Тест")
        val link = services.telegramLinks.createSignupLink(account.trainer.id)
        assertTrue(services.admin.trainers().isEmpty())
        services.telegramLinks.confirmSignup(link.url.substringAfter("?start="), 123)
        dbQuery { TelegramLinks.update { it[expiresAt] = OffsetDateTime.now().minusDays(2) } }
        assertNull(services.loginCodes.nextDelivery())
        List(2) { async { services.admin.approve(account.trainer.id) } }.awaitAll()
        val job = services.loginCodes.nextDelivery()!!
        val challenge = services.loginCodes.request("long_wait")
        assertEquals(job.challenge, challenge.challenge)
        assertTrue(challenge.expiresAt > OffsetDateTime.now().plusMinutes(4))
        assertNull(services.loginCodes.nextDelivery())
        assertTrue(services.loginCodes.prepareDelivery(job.challenge, "123456"))
        assertEquals(account.trainer.id, services.loginCodes.verify(job.challenge, "123456").trainer.id)
        val next = services.loginCodes.request("long_wait")
        assertEquals(next.challenge, services.loginCodes.nextDelivery()!!.challenge)
    }

    @Test
    fun `two browsers and Telegram senders receive only their own challenge`() = runBlocking {
        BotRole.LOGIN_CODES.record("codes_bot")
        suspend fun signup(name: String) = services.telegramLinks.createSignupLink(
            services.trainers.registerForWeb(name, "Анна", "Тест").trainer.id)
        val first = signup("first")
        val second = signup("second")
        val codes = services.loginCodes
        assertNull(codes.nextDelivery())
        assertNotEquals(first.challenge, codes.request("first").challenge)
        services.telegramLinks.confirmSignup(second.url.substringAfter("?start="), 222)
        assertNull(codes.nextDelivery())
        services.admin.approve(services.trainers.findByTelegramUserId(222)!!.id)
        val secondCode = codes.request("second").challenge
        assertEquals(CodeDelivery(secondCode, 222), codes.nextDelivery())
        codes.prepareDelivery(secondCode, "222222")
        services.telegramLinks.confirmSignup(first.url.substringAfter("?start="), 111)
        assertNull(codes.nextDelivery())
        services.admin.approve(services.trainers.findByTelegramUserId(111)!!.id)
        val firstCode = codes.request("first").challenge
        assertEquals(CodeDelivery(firstCode, 111), codes.nextDelivery())
        codes.prepareDelivery(firstCode, "111111")
        assertThrows(LoginException::class.java) { runBlocking { codes.verify(firstCode, "222222") } }
        assertEquals("first", codes.verify(firstCode, "111111").trainer.username)
        assertEquals("second", codes.verify(secondCode, "222222").trainer.username)
    }

    @Test
    fun `resends during delivery or cooldown schedule one fresh code without clearing failed attempts`() = runBlocking<Unit> {
        BotRole.LOGIN_CODES.record("codes_bot")
        val owner = services.trainers.registerForWeb("coach", "Имя", "Тест").trainer
        val link = services.telegramLinks.createSignupLink(owner.id).url.substringAfter("?start=")
        services.telegramLinks.confirmSignup(link, 123)
        services.admin.approve(owner.id)
        val codes = services.loginCodes
        val first = codes.nextDelivery()!!.challenge
        assertTrue(codes.prepareDelivery(first, "010101"))
        repeat(4) { assertThrows(LoginException::class.java) { runBlocking { codes.verify(null, "000000", "coach") } } }
        val requests = List(2) { async { codes.request("coach", resend = true) } }.awaitAll()
        val next = requests.first().challenge
        assertNotEquals(first, next)
        assertEquals(next, requests.last().challenge)
        assertTrue(requests.all { it.deliveryDelaySeconds in 1..60 })
        codes.markHeadingSent(first)
        codes.delivered(first) // Late acknowledgements must not swallow the replacement.
        assertNull(codes.nextDelivery())
        assertThrows(LoginException::class.java) { runBlocking { codes.verify(null, "010101", "coach") } }
        dbQuery { LoginChallenges.update { it[nextDeliveryAt] = OffsetDateTime.now().minusSeconds(1) } }
        assertEquals(CodeDelivery(next, 123), codes.nextDelivery())
        assertTrue(codes.prepareDelivery(next, "020202"))
        codes.delivered(next)
        assertThrows(LoginException::class.java) { runBlocking { codes.verify(null, "010101", "coach") } }
        assertThrows(LoginException::class.java) { runBlocking { codes.verify(null, "020202", "coach") } }
        val afterLockout = codes.request("coach", resend = true)
        assertTrue(afterLockout.deliveryDelaySeconds > 60)
        assertEquals(afterLockout.challenge, codes.request("coach", resend = true).challenge)
        assertNull(codes.nextDelivery())
    }

    @Test
    fun `attempt limits expiry and one time consumption survive new requests`() = runBlocking {
        val account = services.trainers.registerForWeb("coach", "Анна", "Тест")
        val links = services.telegramLinks
        val codes = services.loginCodes
        BotRole.LOGIN_CODES.record("auth_test_bot")
        links.confirmSignup(links.createSignupLink(account.trainer.id).url.substringAfter("?start="), 123)
        services.admin.approve(account.trainer.id)
        val id = codes.request("coach").challenge
        codes.prepareDelivery(id, "000123")
        repeat(5) { assertThrows(LoginException::class.java) { runBlocking { codes.verify(id, "111111") } } }
        assertEquals(id, codes.request("coach").challenge)
        assertThrows(LoginException::class.java) { runBlocking { codes.verify(id, "000123") } }
        dbQuery { LoginChallenges.update { it[expiresAt] = OffsetDateTime.now().minusSeconds(1) } }
        assertThrows(LoginException::class.java) { runBlocking { codes.verify(id, "000123") } }
        val next = codes.request("coach").challenge
        assertNotEquals(id, next)
        codes.prepareDelivery(next, "000234")
        val results = List(2) { async { runCatching { codes.verify(next, "000234") }.isSuccess } }.awaitAll()
        assertEquals(1, results.count { it })
    }
}
