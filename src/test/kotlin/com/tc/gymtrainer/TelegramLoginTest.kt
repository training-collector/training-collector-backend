package com.tc.gymtrainer

import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.service.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class TelegramLoginTest : AbstractDbTest() {
    private val services = TrainingServices()

    private fun String.payload() = substringAfter("?start=")
    private suspend fun logout(token: String) = services.trainers.logout(services.trainers.authenticate(token)!!, token)

    /** Registers, links Telegram and approves a trainer. */
    private suspend fun approved(username: String, sender: Long, with: TrainingServices = services): Long {
        BotRole.LOGIN_CODES.record("codes_bot")
        val id = with.trainers.registerForWeb(username, "Анна", "Тест").trainer.id
        with.telegramLinks.confirmSignup(with.telegramLinks.createSignupLink(id).url.payload(), sender)
        with.admin.approve(id)
        return id
    }

    /** The bot's part: take the queued request and report it sent. */
    private suspend fun LoginService.deliver(): LoginDelivery = nextDelivery()!!.also { delivered(it.id) }

    @Test
    fun `a login becomes available after cancelling or expiring an unlinked registration`() = runBlocking<Unit> {
        suspend fun register() = services.trainers.registerForWeb("coach", "Анна", "Тест")
        BotRole.LOGIN_CODES.record("auth_test_bot")
        val cancelled = register()
        val link = services.telegramLinks.createSignupLink(cancelled.trainer.id)
        assertEquals(Access.SIGNUP, services.trainers.authenticate(cancelled.token)!!.access)
        logout(cancelled.token)
        assertNull(services.trainers.authenticate(cancelled.token))
        assertThrows(ValidationException::class.java) {
            runBlocking { services.telegramLinks.confirmSignup(link.url.payload(), 123) }
        }

        val pending = register()
        dbQuery { Trainers.update({ Trainers.id eq pending.trainer.id }) { it[signupExpiresAt] = OffsetDateTime.now().minusMinutes(1) } }
        val linked = register()
        assertNull(services.trainers.authenticate(pending.token))
        val valid = services.telegramLinks.createSignupLink(linked.trainer.id).url.payload()
        services.telegramLinks.confirmSignup(valid, 123)
        // A linked trainer no longer gets signup links: the next step belongs to the administrator.
        assertThrows(AccessDeniedException::class.java) { runBlocking { services.telegramLinks.createSignupLink(linked.trainer.id) } }
        logout(linked.token)
        assertEquals(linked.trainer.id, services.trainers.findByTelegramUserId(123)?.id)
        assertThrows(ConflictException::class.java) { runBlocking { register() } }
    }

    @Test
    fun `tokens are checked before the database and cleanup removes abandoned registrations`() = runBlocking<Unit> {
        val account = services.trainers.registerForWeb("coach", "Анна", "Тест")
        assertTrue(account.token.startsWith("signup."))
        // Neither a random string nor a token signed with another secret is looked up.
        assertNull(services.trainers.authenticate("session." + "a".repeat(43)))
        assertNull(services.trainers.authenticate(Secrets("another").issue(TokenKind.SESSION)))
        assertNull(services.trainers.authenticate(account.token.dropLast(2) + "zz"))
        assertEquals(account.trainer.id, services.trainers.authenticate(account.token)!!.trainer.id)

        dbQuery { Trainers.update({ Trainers.id eq account.trainer.id }) { it[signupExpiresAt] = OffsetDateTime.now().minusMinutes(1) } }
        val housekeeping = Housekeeping(Settings.defaults)
        housekeeping.runIfDue()
        assertNull(services.trainers.authenticate(account.token))
        assertEquals(0, dbQuery { Trainers.selectAll().count() })
        housekeeping.runIfDue() // Not due again within the interval.
    }

    @Test
    fun `links expire and Telegram cannot be reused for another profile`() = runBlocking {
        val first = services.trainers.registerForWeb("first", "Анна", "Тест").trainer
        val second = services.trainers.registerForWeb("second", "Анна", "Тест").trainer
        BotRole.LOGIN_CODES.record("auth_test_bot")
        suspend fun link(id: Long) = services.telegramLinks.createSignupLink(id).url.payload()
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
        val links = accounts.map { services.telegramLinks.createSignupLink(it.trainer.id).url.payload() }
        val results = links.map { async { services.telegramLinks.confirmSignup(it, 123) } }.awaitAll()
        assertEquals(setOf(SignupConfirmation.PENDING_APPROVAL, SignupConfirmation.TELEGRAM_IN_USE), results.toSet())
        val duplicate = accounts[results.indexOf(SignupConfirmation.TELEGRAM_IN_USE)]
        val original = accounts[results.indexOf(SignupConfirmation.PENDING_APPROVAL)]
        val pending = services.trainers.authenticate(duplicate.token)!!.trainer
        assertTrue(pending.signupTelegramInUse)
        assertNull(pending.telegramUserId)
        assertNull(pending.approvedAt)
        assertFalse(services.trainers.authenticate(original.token)!!.trainer.signupTelegramInUse)
        logout(duplicate.token)
        assertEquals(original.trainer.id, services.trainers.findByTelegramUserId(123)!!.id)
    }

    @Test
    fun `the trainer confirms a sign-in in Telegram and only the asking browser gets the session`() = runBlocking<Unit> {
        val id = approved("coach", 123)
        val logins = services.logins
        // Approval announces the account; the sign-in itself is requested from the site.
        assertNull(logins.nextDelivery())
        val start = logins.request("Coach", ClientInfo("203.0.113.7",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"))
        assertTrue(start.number in 10..99)
        assertEquals("https://t.me/codes_bot", start.botUrl)
        val job = logins.nextDelivery()!!
        assertEquals(123, job.telegramId)
        assertEquals(start.number, job.number)
        assertEquals("Chrome, macOS", job.browser)
        assertEquals("203.0.113.7", job.ip)
        // The request is leased to one worker at a time.
        assertNull(logins.nextDelivery())
        logins.delivered(job.id)
        assertEquals(LoginState.PENDING, logins.status(start.secret).status)
        // Only the linked Telegram account can answer.
        assertEquals(LoginDecision.EXPIRED, logins.decide(job.id, 456, approve = true))
        assertEquals(LoginDecision.APPROVED, logins.decide(job.id, 123, approve = true))
        assertEquals(LoginDecision.APPROVED, logins.decide(job.id, 123, approve = false))
        val approved = logins.status(start.secret)
        assertEquals(LoginState.APPROVED, approved.status)
        assertEquals(id, services.trainers.authenticate(approved.token!!)!!.trainer.id)
        // The session is issued once; afterwards the secret means nothing.
        assertEquals(LoginStatus(LoginState.PENDING), logins.status(start.secret))
    }

    @Test
    fun `a rejected or expired request gives no session`() = runBlocking<Unit> {
        approved("coach", 123)
        val logins = services.logins
        val rejected = logins.request("coach")
        assertEquals(LoginDecision.DENIED, logins.decide(logins.deliver().id, 123, approve = false))
        assertEquals(LoginStatus(LoginState.DENIED), logins.status(rejected.secret))
        assertEquals(LoginStatus(LoginState.PENDING), logins.status(rejected.secret))

        val late = logins.request("coach")
        val job = logins.deliver()
        dbQuery { LoginRequests.update { it[expiresAt] = OffsetDateTime.now().minusSeconds(1) } }
        assertEquals(LoginDecision.EXPIRED, logins.decide(job.id, 123, approve = true))
        assertEquals(LoginStatus(LoginState.EXPIRED), logins.status(late.secret))
        assertNull(logins.nextDelivery())
        assertEquals(LoginDecision.EXPIRED, logins.decide("not-a-request", 123, approve = true))
    }

    @Test
    fun `an unknown or unapproved login looks like a request that is still waiting`() = runBlocking<Unit> {
        BotRole.LOGIN_CODES.record("codes_bot")
        val logins = services.logins
        val account = services.trainers.registerForWeb("waiting", "Анна", "Тест")
        services.telegramLinks.confirmSignup(services.telegramLinks.createSignupLink(account.trainer.id).url.payload(), 123)
        for (name in listOf("nobody", "waiting", "")) {
            val result = logins.request(name)
            assertEquals(LoginStatus(LoginState.PENDING), logins.status(result.secret))
        }
        assertNull(logins.nextDelivery())
        assertEquals(LoginStatus(LoginState.PENDING), logins.status("short"))
    }

    @Test
    fun `a stranger's requests do not withdraw the trainer's own one until the limit of waiting requests`() = runBlocking<Unit> {
        val strict = TrainingServices(Settings.defaults.copy(maxPendingLogins = 3))
        approved("coach", 123, strict)
        val logins = strict.logins
        val own = logins.request("coach")
        repeat(2) { logins.request("coach") }
        val ownJob = logins.deliver()
        repeat(2) { logins.deliver() }
        assertEquals(LoginDecision.APPROVED, logins.decide(ownJob.id, 123, approve = true))
        assertEquals(LoginState.APPROVED, logins.status(own.secret).status)
        // A successful sign-in withdrew the other requests.
        assertEquals(0, dbQuery { LoginRequests.selectAll().count() })

        val oldest = logins.request("coach")
        repeat(3) { logins.request("coach") }
        assertEquals(3, dbQuery { LoginRequests.selectAll().count() })
        assertEquals(LoginStatus(LoginState.PENDING), logins.status(oldest.secret))
        assertEquals(LoginDecision.EXPIRED, logins.decide("x".repeat(22), 123, approve = true))
    }

    @Test
    fun `a request made before the trainer opened the chat waits for it and deactivation withdraws requests`() = runBlocking<Unit> {
        val id = approved("coach", 123)
        dbQuery { Trainers.update { it[codeChatStarted] = false } }
        val logins = services.logins
        services.admin.acknowledgeNotification(services.admin.nextNotification()!!.id)
        logins.request("coach")
        assertNull(logins.nextDelivery())
        assertTrue(logins.activateChat(123))
        logins.delivered(logins.nextDelivery()!!.id)
        val pending = logins.request("coach")
        services.admin.deactivate(id)
        assertNull(logins.nextDelivery())
        assertEquals(LoginStatus(LoginState.PENDING), logins.status(pending.secret))
    }

    @Test
    fun `a notice that cannot be delivered is retried until its term ends and replaced by a newer state`() = runBlocking<Unit> {
        val id = approved("coach", 123)
        val first = services.admin.nextNotification()!!
        assertEquals(NoticeKind.ACTIVATED, first.kind)
        assertNull(services.admin.nextNotification()) // leased for a retry pause
        services.admin.deactivate(id)
        // Only the latest state is announced; acknowledging the replaced message changes nothing.
        services.admin.acknowledgeNotification(first.id)
        val latest = services.admin.nextNotification()!!
        assertEquals(NoticeKind.DEACTIVATED, latest.kind)
        dbQuery { TrainerNotices.update { it[nextAttemptAt] = OffsetDateTime.now().minusSeconds(1); it[expiresAt] = OffsetDateTime.now().minusSeconds(1) } }
        assertNull(services.admin.nextNotification())
    }
}
