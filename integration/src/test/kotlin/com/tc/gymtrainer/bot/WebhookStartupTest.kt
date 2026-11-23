package com.tc.gymtrainer.bot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class WebhookStartupTest {
    @Test
    fun `both production entrypoints serve HTTP without polling Telegram or contacting an idle backend`() {
        val mains = mapOf("training-collector-bot" to "com.tc.gymtrainer.bot.BotAppKt",
            "training-collector-auth-bot" to "com.tc.gymtrainer.authbot.AuthBotKt")
        for ((name, main) in mains) {
            val port = ServerSocket(0).use { it.localPort }
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val proc = ProcessBuilder(java, "-cp", System.getProperty("$name.lib") + "/*", main).apply {
                environment().putAll(mapOf("BOT_MODE" to "WEBHOOK", "PORT" to "$port",
                    "BOT_TOKEN" to "123456:synthetic", "AUTH_BOT_TOKEN" to "234567:synthetic",
                    "BOT_USERNAME" to "pilot_test_bot", "TELEGRAM_WEBHOOK_SECRET" to "test-secret",
                    "PLATFORM_URL" to "http://127.0.0.1:1", "TRAINING_SERVICE_KEY" to "training", "AUTH_SERVICE_KEY" to "auth"))
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectErrorStream(true)
            }.start()
            try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                fun req(path: String) = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(2))
                val health = req("/health").GET().build()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (true) {
                    assertTrue(proc.isAlive && System.nanoTime() < deadline, "$name failed to start")
                    if (runCatching { client.send(health, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull() == "OK") break
                    Thread.sleep(75)
                }
                fun update(secret: String) = req("/telegram").header("X-Telegram-Bot-Api-Secret-Token", secret)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"update_id":0}""")).build()
                assertEquals(403, client.send(update("incorrect"), HttpResponse.BodyHandlers.discarding()).statusCode())
                assertEquals(204, client.send(update("test-secret"), HttpResponse.BodyHandlers.discarding()).statusCode())
            } finally {
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly().waitFor()
            }
        }
    }
}
