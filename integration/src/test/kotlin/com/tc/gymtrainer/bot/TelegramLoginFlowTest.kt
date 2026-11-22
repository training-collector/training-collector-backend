package com.tc.gymtrainer.bot

import com.tc.gymtrainer.authbot.AuthBot
import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.config.ServerConfig
import dev.inmo.tgbotapi.extensions.behaviour_builder.DefaultBehaviourContext
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.message.textsources.CodeTextSource
import dev.inmo.tgbotapi.types.toChatId
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
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
    fun `auth bot sends the first code and training bot is connected separately after login`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, 3)
            withTestPlatform(AppConfig(db, ServerConfig("127.0.0.1", 0))) { services, training, auth ->
                DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("INSERT INTO trainer (telegram_user_id, full_name) VALUES (456, 'Прежний профиль')")
                    }
                }
                val telegram = TestTelegram(100, "training_test_bot")
                val authTelegram = TestTelegram(200, "auth_test_bot")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val context = DefaultBehaviourContext(telegram, scope)
                val authContext = DefaultBehaviourContext(authTelegram, scope)
                try {
                    runBlocking {
                        GymTrainerBot(training).install(context)
                        val authBot = AuthBot(auth, "200:synthetic-otp-token")
                        authBot.install(authContext)
                        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                            connection.createStatement().use { sql ->
                                sql.executeUpdate("TRUNCATE telegram_bot_identity")
                                withTimeout(15_000) {
                                    while (sql.executeQuery("SELECT count(*) FROM telegram_bot_identity").use { it.next(); it.getInt(1) } != 2) {
                                        delay(100)
                                    }
                                }
                            }
                        }
                        val account = services.trainers.registerForWeb("coach_local", "Анна", "Тест")
                        val link = services.telegramLinks.createSignupLink(account.trainer.id)
                        assertTrue(link.url.startsWith("https://t.me/auth_test_bot?start="))

                        suspend fun dispatch(id: Int, sender: Int, fields: String, auth: Boolean) {
                            val update = """{"update_id":$id,"message":{"message_id":$id,"date":1700000000,
                                "from":{"id":$sender,"is_bot":false,"first_name":"Trainer"},
                                "chat":{"id":$sender,"type":"private","first_name":"Trainer"},$fields}}"""
                            (if (auth) authContext else context).asUpdateReceiver(json.decodeFromString(UpdateDeserializationStrategy, update))
                        }
                        suspend fun send(id: Int, sender: Int, fields: String, auth: Boolean = false): SendTextMessage {
                            dispatch(id, sender, fields, auth)
                            return withTimeout(5000) { (if (auth) authTelegram else telegram).sent.receive() }
                        }
                        fun command(name: String, argument: String = "") =
                            """"text":"$name$argument","entities":[{"type":"bot_command","offset":0,"length":${name.length}}]"""
                        suspend fun receiveHeading() {
                            val heading = withTimeout(5000) { authTelegram.sent.receive() }
                            assertEquals("Код для авторизации", heading.text)
                            assertEquals(123L.toChatId(), heading.chatId)
                        }
                        suspend fun receiveCode(): String {
                            val code = withTimeout(5000) { authTelegram.sent.receive() }
                            assertEquals(123L.toChatId(), code.chatId)
                            assertTrue(code.text.matches(Regex("[0-9]{6}")))
                            assertFalse(code.protectContent)
                            assertTrue(code.textSources?.single() is CodeTextSource)
                            return code.text
                        }
                        val unlinkedActions = listOf(command("/start"), command("/menu"), command("/help"), """"text":"Замеры"""")
                        for ((i, entry) in unlinkedActions.withIndex()) {
                            val blocked = send(15 + i, 987, entry)
                            assertTrue(blocked.text.contains("Активация не подтверждена"))
                            assertTrue(blocked.replyMarkup is ReplyKeyboardRemove)
                        }
                        val locked = send(1, 456, command("/clients"))
                        assertTrue(locked.text.contains("Подключить бота для тренировок"))
                        val payload = " " + link.url.substringAfter("?start=")
                        assertTrue(send(2, 123, command("/start", payload)).text.contains("недействительна"))
                        send(3, 456, command("/start", " login"), auth = true)
                        assertTrue(!authBot.deliverNext(authTelegram))
                        dispatch(4, 123, command("/start", payload), auth = true)
                        withTimeout(5000) { while (!authBot.deliverNext(authTelegram)) delay(20) }
                        receiveHeading()
                        val code = receiveCode()
                        assertTrue(send(11, 123, command("/start", payload), auth = true).text.contains("Telegram уже привязан"))
                        assertTrue(!authBot.deliverNext(authTelegram))
                        val duplicate = services.trainers.registerForWeb("duplicate", "Тренер", "Тест")
                        val duplicateLink = services.telegramLinks.createSignupLink(duplicate.trainer.id).url.substringAfter("?start=")
                        val conflict = send(12, 123, command("/start", " $duplicateLink"), auth = true)
                        assertTrue(conflict.text.contains("Этот аккаунт Telegram уже зарегистрирован"))
                        assertTrue(services.trainers.authenticate(duplicate.token)!!.signupTelegramInUse)
                        assertTrue(!authBot.deliverNext(authTelegram))
                        val session = services.loginCodes.verify(link.challenge, code)
                        val profile = session.trainer
                        assertEquals(account.trainer.id, profile.id)
                        assertEquals(123L, profile.telegramUserId)
                        assertTrue(profile.approvedAt != null)
                        assertNull(profile.trainingBotLinkedAt)
                        assertNull(profile.phone)
                        assertTrue(session.token.startsWith("session."))
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        assertTrue(send(5, 123, command("/clients")).text.contains("Подключить"))
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

                        services.trainers.logout(session.token)
                        val next = services.loginCodes.request("coach_local")
                        authTelegram.failNextCode = true
                        assertTrue(runCatching { authBot.deliverNext(authTelegram) }.exceptionOrNull() is java.io.IOException)
                        receiveHeading()
                        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                            connection.prepareStatement("UPDATE login_challenge SET next_delivery_at = now() WHERE challenge_id = ?").use {
                                it.setString(1, next.challenge)
                                it.executeUpdate()
                            }
                        }
                        assertTrue(authBot.deliverNext(authTelegram))
                        val nextCode = receiveCode()
                        assertTrue(authTelegram.sent.tryReceive().isFailure)
                        assertEquals(profile.id, services.loginCodes.verify(next.challenge, nextCode).trainer.id)
                        assertTrue(telegram.sent.tryReceive().isFailure)
                    }
                } finally {
                    runBlocking { scope.coroutineContext.job.cancelAndJoin() }
                    telegram.close()
                    authTelegram.close()
                }
            }
        }
    }
}
