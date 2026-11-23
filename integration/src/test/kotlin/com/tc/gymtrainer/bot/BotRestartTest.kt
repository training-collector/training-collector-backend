package com.tc.gymtrainer.bot

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.api.trainingApi
import com.tc.gymtrainer.authbot.AuthBot
import com.tc.gymtrainer.authbot.platform.AuthPlatform
import com.tc.gymtrainer.config.*
import com.tc.gymtrainer.db.DatabaseFactory
import com.tc.gymtrainer.service.BotRole
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.net.http.*
import java.sql.DriverManager

class BotRestartTest {
    @Test
    fun `a restarted bot continues the sign-in and repeated deliveries are handled once`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, 3)
            DatabaseFactory.init(db).use {
                val services = TrainingServices()
                val keys = BotAccess("test-auth", "test-training")
                val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    trainingApi(services, keys, AdminAccess("test", "pw"))
                }.start(wait = false)
                try {
                    runBlocking {
                        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
                        val http = HttpClient.newHttpClient()
                        fun post(path: String, body: String, token: String? = null, receipt: String? = null): HttpResponse<String> {
                            val builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                            if (token != null) builder.header("Authorization", "Bearer $token")
                            if (receipt != null) builder.header("Idempotency-Key", receipt)
                            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                        }
                        val platform = AuthPlatform(base, keys.authKey)
                        val telegram = TestTelegram(200, "test_auth_bot")
                        // Every call uses a new bot, as after a restart: it keeps no state of its own.
                        fun bot() = AuthBot(platform)
                        suspend fun deliverAll() { while (bot().deliverNextNotification(telegram) or bot().deliverNext(telegram)) Unit }
                        BotRole.LOGIN_CODES.record("test_auth_bot")
                        val trainer = services.trainers.registerForWeb("pilot", "Coach", "Test").trainer
                        val link = services.telegramLinks.createSignupLink(trainer.id)
                        val event = Json { ignoreUnknownKeys = true }.decodeFromString(UpdateDeserializationStrategy,
                            """{"update_id":5,"message":{"message_id":8,"date":1700000001,"chat":{"id":123,"type":"private","first_name":"A"},"from":{"id":123,"is_bot":false,"first_name":"A"},"text":"/start ${link.url.substringAfter("?start=")}"}}""")
                        repeat(2) { bot().handle(telegram, event, "test_auth_bot") }
                        assertTrue(telegram.sent.tryReceive().getOrThrow().text.contains("pilot"))
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        val login = post("/admin/v1/login", """{"login":"test","password":"pw"}""")
                        val admin = Json.parseToJsonElement(login.body()).jsonObject.getValue("token").jsonPrimitive.content
                        val approval = "/admin/v1/trainers/${trainer.id}/approve"
                        assertEquals(204, post(approval, "{}", admin).statusCode())
                        assertNotNull(services.admin.trainers().single().approvedAt)
                        // A repeated approval sends nothing new.
                        assertEquals(204, post(approval, "{}", admin).statusCode())
                        deliverAll()
                        assertTrue(telegram.sent.tryReceive().getOrThrow().text.contains("активирован"))
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        // Two browsers ask to sign in; each gets its own request.
                        val json = Json { ignoreUnknownKeys = true }
                        fun signIn() = json.parseToJsonElement(post("/api/v1/auth/login", """{"username":"pilot"}""").body()).jsonObject
                        val start = signIn()
                        val again = signIn()
                        deliverAll()
                        val messages = List(2) { telegram.sent.tryReceive().getOrThrow() }
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        // Each message repeats the number of its own request.
                        val numbers = listOf(start, again).map { it.getValue("number").jsonPrimitive.content }
                        assertTrue(messages.zip(numbers).all { (sent, shown) -> sent.text.contains("число $shown") })
                        val message = messages.first()
                        val allow = (message.replyMarkup as InlineKeyboardMarkup).keyboard.flatten().map { it as CallbackDataInlineKeyboardButton }
                            .single { it.text == "Разрешить вход" }.callbackData
                        fun status(of: kotlinx.serialization.json.JsonObject) = json.parseToJsonElement(
                            post("/api/v1/auth/login/status", """{"secret":"${of.getValue("secret").jsonPrimitive.content}"}""").body()).jsonObject
                        val press = json.decodeFromString(UpdateDeserializationStrategy, """{"update_id":6,"callback_query":{"id":"cb6",
                            "from":{"id":123,"is_bot":false,"first_name":"A"},"chat_instance":"c","data":"$allow",
                            "message":{"message_id":9,"date":1700000002,"chat":{"id":123,"type":"private","first_name":"A"},
                            "from":{"id":200,"is_bot":true,"first_name":"Bot"},"text":"Вход в кабинет pilot"}}}""")
                        // Telegram may deliver the press twice; it is handled once.
                        repeat(2) { bot().handle(telegram, press, "test_auth_bot") }
                        assertTrue(telegram.edited.tryReceive().getOrThrow().text.contains("Вход разрешён"))
                        assertTrue(telegram.edited.tryReceive().isFailure)
                        val approved = listOf(start, again).map(::status)
                        val session = approved.single { it.getValue("status").jsonPrimitive.content == "APPROVED" }.getValue("token").jsonPrimitive.content
                        assertTrue(session.startsWith("session."))

                        // Lose a platform response after commit, then retry the same webhook write.
                        BotRole.TRAINING.record("test_training_bot")
                        val trainingLink = services.telegramLinks.createTrainingLink(trainer.id)
                        services.telegramLinks.connectTrainingBot(trainingLink.url.substringAfter("?start="), 123)
                        val receipt = java.util.UUID.randomUUID().toString()
                        val path = "/internal/v1/training/chats/123/clients"
                        val body = """{"firstName":"Аня","lastName":"Тест"}"""
                        val created = post(path, body, keys.trainingKey, receipt)
                        assertEquals(201, created.statusCode())
                        val replayed = post(path, body, keys.trainingKey, receipt)
                        assertEquals(created.body(), replayed.body())
                        assertEquals(1, services.clients.list(trainer.id).size)
                    }
                } finally {
                    server.stop(100, 1000)
                }
            }
        }
    }
}
