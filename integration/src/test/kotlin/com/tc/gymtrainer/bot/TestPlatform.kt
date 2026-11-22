package com.tc.gymtrainer.bot

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.api.trainingApi
import com.tc.gymtrainer.authbot.platform.AuthPlatform
import com.tc.gymtrainer.bot.platform.TrainingPlatform
import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.config.BotAccess
import com.tc.gymtrainer.withTrainingServices
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.util.UUID

internal fun withTestPlatform(config: AppConfig, action: (TrainingServices, TrainingPlatform, AuthPlatform) -> Unit) {
    val keys = BotAccess(UUID.randomUUID().toString(), UUID.randomUUID().toString())
    withTrainingServices(config) { services ->
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { trainingApi(services, keys) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val url = "http://127.0.0.1:$port"
            action(services, TrainingPlatform(url, keys.trainingKey), AuthPlatform(url, keys.authKey))
        } finally {
            server.stop(1000, 5000)
        }
    }
}
