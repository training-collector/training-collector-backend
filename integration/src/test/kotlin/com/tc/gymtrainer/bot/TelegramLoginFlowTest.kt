package com.tc.gymtrainer.bot

import com.tc.gymtrainer.authbot.AuthBot
import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.config.ServerConfig
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import com.tc.gymtrainer.service.LoginState
import com.tc.gymtrainer.service.LoginStatus
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.toChatId
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class TelegramLoginFlowTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `auth bot confirms sign-in with buttons and training bot is connected separately after login`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, 3)
            withTestPlatform(AppConfig(db, ServerConfig("127.0.0.1", 0))) { services, training, auth ->
                DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("INSERT INTO trainer (telegram_user_id, first_name, last_name) VALUES (456, 'Прежний', 'профиль')")
                    }
                }
                val telegram = TestTelegram(100, "training_test_bot")
                val authTelegram = TestTelegram(200, "auth_test_bot")
                try {
                    runBlocking {
                        // Each bot announces its Telegram name to the platform once, at startup or deployment.
                        training.register("training_test_bot")
                        auth.register("auth_test_bot")
                        val trainingBot = UpdateHandler(training, telegram, "training_test_bot")
                        val authBot = AuthBot(auth)
                        var updates = 0
                        val account = services.trainers.registerForWeb("coach_local", "Анна", "Тест")
                        val link = services.telegramLinks.createSignupLink(account.trainer.id)
                        assertTrue(link.url.startsWith("https://t.me/auth_test_bot?start="))

                        // Telegram numbers updates in order; the bots skip an update they have already handled.
                        @Suppress("UNUSED_PARAMETER")
                        suspend fun dispatch(id: Int, sender: Int, fields: String, auth: Boolean) {
                            val number = ++updates
                            val update = json.decodeFromString(UpdateDeserializationStrategy, """{"update_id":$number,"message":{"message_id":$number,"date":1700000000,
                                "from":{"id":$sender,"is_bot":false,"first_name":"Trainer"},
                                "chat":{"id":$sender,"type":"private","first_name":"Trainer"},$fields}}""")
                            if (auth) authBot.handle(authTelegram, update, "auth_test_bot") else trainingBot.process(update)
                        }
                        suspend fun send(id: Int, sender: Int, fields: String, auth: Boolean = false): SendTextMessage {
                            dispatch(id, sender, fields, auth)
                            return withTimeout(5000) { (if (auth) authTelegram else telegram).sent.receive() }
                        }
                        fun command(name: String, argument: String = "") =
                            """"text":"$name$argument","entities":[{"type":"bot_command","offset":0,"length":${name.length}}]"""
                        /** The trainer presses a button under a message of the auth bot. */
                        suspend fun pressInAuthBot(data: String, sender: Int = 123) {
                            val number = ++updates
                            authBot.handle(authTelegram, json.decodeFromString(UpdateDeserializationStrategy, """{"update_id":$number,
                                "callback_query":{"id":"cb$number","from":{"id":$sender,"is_bot":false,"first_name":"Trainer"},"chat_instance":"c",
                                "data":"$data","message":{"message_id":$number,"date":1700000000,"chat":{"id":$sender,"type":"private","first_name":"Trainer"},
                                "from":{"id":200,"is_bot":true,"first_name":"Bot"},"text":"Вход в кабинет"}}}"""), "auth_test_bot")
                        }
                        /** The request the bot put in front of the trainer, with its two buttons. */
                        suspend fun receiveLoginRequest(number: Int): List<CallbackDataInlineKeyboardButton> {
                            val message = withTimeout(5000) { authTelegram.sent.receive() }
                            assertEquals(123L.toChatId(), message.chatId)
                            assertTrue(message.text.startsWith("Вход в кабинет coach_local"), message.text)
                            assertTrue(message.text.contains("число $number"), message.text)
                            val buttons = (message.replyMarkup as InlineKeyboardMarkup).keyboard.flatten().map { it as CallbackDataInlineKeyboardButton }
                            assertEquals(listOf("Разрешить вход", "Отклонить вход"), buttons.map { it.text })
                            return buttons
                        }
                        /** The site asks to sign in, the bot delivers it, the trainer answers. */
                        suspend fun signIn(approve: Boolean = true): LoginStatus {
                            val start = services.logins.request("coach_local")
                            assertTrue(authBot.deliverNext(authTelegram))
                            val buttons = receiveLoginRequest(start.number)
                            pressInAuthBot(buttons[if (approve) 0 else 1].callbackData)
                            val outcome = withTimeout(5000) { authTelegram.edited.receive() }
                            assertTrue(outcome.text.contains(if (approve) "Вход разрешён" else "Вход отклонён"), outcome.text)
                            assertNull(outcome.replyMarkup)
                            return services.logins.status(start.secret)
                        }
                        val unlinkedActions = listOf(command("/start"), command("/menu"), command("/help"), """"text":"Замеры"""")
                        for ((i, entry) in unlinkedActions.withIndex()) {
                            val blocked = send(15 + i, 987, entry)
                            assertTrue(blocked.text.contains("Активация не подтверждена"))
                            assertTrue(blocked.replyMarkup is ReplyKeyboardRemove)
                        }
                        val locked = send(1, 456, command("/clients"))
                        assertTrue(locked.text.contains("Активация не подтверждена"))
                        val payload = " " + link.url.substringAfter("?start=")
                        assertTrue(send(2, 123, command("/start", payload)).text.contains("недействительна"))
                        send(3, 456, command("/start", " login"), auth = true)
                        assertTrue(!authBot.deliverNext(authTelegram))
                        assertTrue(services.admin.trainers().isEmpty())
                        val pending = send(4, 123, command("/start", payload), auth = true).text
                        assertTrue(pending.contains("подтверждения администратора"))
                        assertTrue(pending.contains("Логин для входа: coach_local"))
                        assertTrue(send(53, 123, command("/start"), auth = true).text.contains("Логин для входа: coach_local"))
                        assertEquals(account.trainer.id, services.admin.trainers().single().id)
                        assertTrue(!authBot.deliverNext(authTelegram))
                        services.trainers.logout(services.trainers.authenticate(account.token)!!, account.token)
                        services.logins.request("coach_local")
                        services.admin.approve(account.trainer.id)
                        suspend fun expectAccessNotice(active: Boolean) {
                            assertTrue(authBot.deliverNextNotification(authTelegram))
                            val msg = withTimeout(5000) { authTelegram.sent.receive() }
                            assertEquals(123L.toChatId(), msg.chatId)
                            assertEquals(if (active) "Аккаунт coach_local активирован. Войдите на сайте и подтвердите вход в этом чате."
                                else "Аккаунт coach_local деактивирован. Доступ к кабинету закрыт.", msg.text)
                        }
                        services.admin.approve(account.trainer.id)
                        expectAccessNotice(true)
                        assertFalse(authBot.deliverNextNotification(authTelegram))
                        // Approval sends no sign-in request by itself.
                        assertFalse(authBot.deliverNext(authTelegram))
                        assertTrue(send(11, 123, command("/start", payload), auth = true).text.contains("Telegram уже привязан"))
                        assertTrue(!authBot.deliverNext(authTelegram))
                        val duplicate = services.trainers.registerForWeb("duplicate", "Тренер", "Тест")
                        val duplicateLink = services.telegramLinks.createSignupLink(duplicate.trainer.id).url.substringAfter("?start=")
                        val conflict = send(12, 123, command("/start", " $duplicateLink"), auth = true)
                        assertTrue(conflict.text.contains("Этот аккаунт Telegram уже зарегистрирован"))
                        assertTrue(services.trainers.authenticate(duplicate.token)!!.trainer.signupTelegramInUse)
                        assertTrue(!authBot.deliverNext(authTelegram))
                        val session = signIn()
                        assertEquals(LoginState.APPROVED, session.status)
                        val profile = session.trainer!!
                        assertEquals(account.trainer.id, profile.id)
                        assertEquals(123L, profile.telegramUserId)
                        assertTrue(profile.approvedAt != null)
                        assertNull(profile.trainingBotLinkedAt)
                        assertTrue(session.token!!.startsWith("session."))
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        assertTrue(send(5, 123, command("/clients")).text.contains("Активация не подтверждена"))
                        val training = services.telegramLinks.createTrainingLink(profile.id)
                        assertTrue(training.url.startsWith("https://t.me/training_test_bot?start="))
                        val connect = command("/start", " " + training.url.substringAfter("?start="))
                        assertTrue(send(6, 123, connect, auth = true).text.contains("недействительна"))
                        assertTrue(send(7, 456, connect).text.contains("того же аккаунта"))
                        val welcome = send(8, 123, connect)
                        assertTrue(welcome.text.contains("coach_local"))
                        val keyboard = welcome.replyMarkup as ReplyKeyboardMarkup
                        assertEquals(listOf(listOf("Подопечные"), listOf("Упражнения")),
                            keyboard.keyboard.map { row -> row.map { it.text } })
                        assertEquals(true, keyboard.persistent)
                        assertTrue(send(9, 123, command("/clients")).text.contains("подопечн"))
                        assertTrue(send(10, 123, connect).text.contains("недействительна"))
                        suspend fun press(id: Int, label: String) = send(id, 123, """"text":"$label"""")
                        assertTrue(press(20, "Добавить подопечного").text.contains("Имя нового"))
                        for ((i, label) in listOf("Подопечные", "Записать тренировку", "История тренировок", "Замеры").withIndex()) {
                            assertTrue(press(21 + i, label).text.contains("нет подопечных"))
                        }
                        assertTrue(press(25, "Помощь").text.contains("подхода"))
                        assertTrue(services.clients.list(profile.id).isEmpty())

                        services.trainers.logout(services.trainers.authenticate(session.token!!)!!, session.token!!)
                        // A send that failed is offered again later and goes out once.
                        val retried = services.logins.request("coach_local")
                        authTelegram.failNextLoginRequest = true
                        assertTrue(runCatching { authBot.deliverNext(authTelegram) }.exceptionOrNull() is java.io.IOException)
                        assertFalse(authBot.deliverNext(authTelegram))
                        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                            connection.createStatement().use { it.executeUpdate("UPDATE login_request SET next_delivery_at = now()") }
                        }
                        assertTrue(authBot.deliverNext(authTelegram))
                        val buttons = receiveLoginRequest(retried.number)
                        assertTrue(authTelegram.sent.tryReceive().isFailure)
                        // Another Telegram account cannot answer a request it never received.
                        pressInAuthBot(buttons[0].callbackData, sender = 456)
                        assertTrue(withTimeout(5000) { authTelegram.edited.receive() }.text.contains("устарел"))
                        assertEquals(LoginState.PENDING, services.logins.status(retried.secret).status)
                        pressInAuthBot(buttons[1].callbackData)
                        assertTrue(withTimeout(5000) { authTelegram.edited.receive() }.text.contains("Вход отклонён"))
                        assertEquals(LoginState.DENIED, services.logins.status(retried.secret).status)
                        // Once the site has seen the answer, a late press finds no request.
                        pressInAuthBot(buttons[0].callbackData)
                        assertTrue(withTimeout(5000) { authTelegram.edited.receive() }.text.contains("устарел"))
                        assertEquals(profile.id, signIn().trainer!!.id)
                        assertTrue(press(50, "Добавить подопечного").text.contains("Имя нового"))
                        services.admin.deactivate(profile.id)
                        // The unfinished dialog went with the access: its next answer is not processed.
                        dispatch(51, 123, """"text":"Анна"""", false)
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        val revoked = press(51, "Подопечные")
                        assertTrue(revoked.text.contains("Активация не подтверждена"))
                        assertTrue(revoked.replyMarkup is ReplyKeyboardRemove)
                        assertTrue(send(52, 123, command("/start"), auth = true).text.contains("деактивирован"))
                        services.logins.request("coach_local")
                        assertFalse(authBot.deliverNext(authTelegram))
                        // A worker stopped after claiming the notice. The account is reactivated meanwhile.
                        val missed = auth.nextNotification()!!
                        assertEquals("DEACTIVATED", missed.kind)
                        services.admin.deactivate(profile.id)
                        services.admin.reactivate(profile.id)
                        services.admin.reactivate(profile.id)
                        // Only the latest state is announced. The late acknowledgement of the old notice changes nothing.
                        expectAccessNotice(true)
                        auth.acknowledgeNotification(missed.id)
                        assertFalse(authBot.deliverNextNotification(authTelegram))
                        assertEquals(profile.id, signIn().trainer!!.id)
                        assertTrue(telegram.sent.tryReceive().isFailure)
                    }
                } finally {
                    telegram.close()
                    authTelegram.close()
                }
            }
        }
    }
}
