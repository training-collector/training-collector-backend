package com.tc.gymtrainer.bot

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.api.trainingApi
import com.tc.gymtrainer.authbot.AuthBot
import com.tc.gymtrainer.authbot.platform.AuthPlatform
import com.tc.gymtrainer.config.*
import com.tc.gymtrainer.db.DatabaseFactory
import com.tc.gymtrainer.service.AuthDispatch
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.BotUnavailableException
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

class HostedLoginTest {
    @Test
    fun `approval wakes the auth bot without another update and a delayed resend survives restart`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, 3)
            DatabaseFactory.init(db).use {
                var events = 0
                var available = false
                val services = TrainingServices(AuthDispatch {
                    if (!available) throw BotUnavailableException()
                    events++
                })
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
                        fun bot() = AuthBot(platform, "test-token")
                        BotRole.LOGIN_CODES.record("test_auth_bot")
                        val trainer = services.trainers.registerForWeb("pilot", "Coach", "Test").trainer
                        val link = services.telegramLinks.createSignupLink(trainer.id)
                        val event = Json { ignoreUnknownKeys = true }.decodeFromString(UpdateDeserializationStrategy,
                            """{"update_id":5,"message":{"message_id":8,"date":1700000001,"chat":{"id":123,"type":"private","first_name":"A"},"from":{"id":123,"is_bot":false,"first_name":"A"},"text":"/start ${link.challenge}"}}""")
                        repeat(2) { bot().webhook(telegram, event, "test_auth_bot") }
                        assertTrue(telegram.sent.tryReceive().getOrThrow().text.contains("pilot"))
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        val login = post("/admin/v1/login", """{"login":"test","password":"pw"}""")
                        val admin = Json.parseToJsonElement(login.body()).jsonObject.getValue("token").jsonPrimitive.content
                        val approval = "/admin/v1/trainers/${trainer.id}/approve"
                        assertEquals(503, post(approval, "{}", admin).statusCode())
                        assertNull(services.admin.trainers().single().approvedAt)
                        available = true
                        assertEquals(204, post(approval, "{}", admin).statusCode())
                        assertEquals(1, events)
                        assertNull(bot().drain(telegram))
                        assertTrue(telegram.sent.tryReceive().getOrThrow().text.contains("активирован"))
                        assertEquals("Код для авторизации", telegram.sent.tryReceive().getOrThrow().text)
                        val first = telegram.sent.tryReceive().getOrThrow().text
                        assertTrue(first.matches(Regex("[0-9]{6}")))
                        val resend = post("/api/v1/auth/login", """{"username":"pilot","resend":true}""")
                        assertEquals(200, resend.statusCode())
                        assertEquals(2, events)
                        assertTrue(requireNotNull(bot().drain(telegram)) in 1..60)
                        assertTrue(telegram.sent.tryReceive().isFailure)
                        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { conn ->
                            conn.createStatement().use { it.executeUpdate("UPDATE login_challenge SET next_delivery_at=now()") }
                        }
                        assertNull(bot().drain(telegram))
                        assertEquals("Код для авторизации", telegram.sent.tryReceive().getOrThrow().text)
                        val code = telegram.sent.tryReceive().getOrThrow().text
                        assertEquals(200, post("/api/v1/auth/login/verify", """{"username":"pilot","code":"$code"}""").statusCode())

                        // Lose a platform response after commit, then retry the same webhook write.
                        BotRole.TRAINING.record("test_training_bot")
                        val trainingLink = services.telegramLinks.createTrainingLink(trainer.id)
                        services.telegramLinks.connectTrainingBot(trainingLink.url.substringAfter("?start="), 123)
                        val receipt = java.util.UUID.randomUUID().toString()
                        val path = "/internal/v1/training/chats/123/clients/profile"
                        val body = """{"profile":{"firstName":"Аня","lastName":"Тест"}}"""
                        val created = post(path, body, keys.trainingKey, receipt)
                        assertEquals(200, created.statusCode())
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
