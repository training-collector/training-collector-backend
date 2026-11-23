package com.tc.gymtrainer.bot

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

internal class TestTelegram(private val id: Long, private val username: String) : RequestsExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    val messageId = AtomicInteger()
    val sent = Channel<SendTextMessage>(Channel.UNLIMITED)
    val edited = Channel<EditChatMessageText>(Channel.UNLIMITED)
    var failNextLoginRequest = false

    override suspend fun <T : Any> execute(request: Request<T>): T {
        val bot = """{"id":$id,"is_bot":true,"first_name":"Bot","username":"$username"}"""
        val response = when (request) {
            is SendTextMessage -> {
                if (failNextLoginRequest && request.text.startsWith("Вход в кабинет")) {
                    failNextLoginRequest = false
                    throw java.io.IOException("Test delivery interrupted")
                }
                val sentId = messageId.incrementAndGet()
                sent.send(request)
                """{"message_id":$sentId,"date":1700000000,"from":$bot,
                    "chat":{"id":123,"type":"private","first_name":"Trainer"},"text":"OK"}"""
            }
            is EditChatMessageText -> {
                edited.send(request)
                """{"message_id":1,"date":1700000000,"from":$bot,
                    "chat":{"id":123,"type":"private","first_name":"Trainer"},"text":"OK"}"""
            }
            else -> {
                when (request.method()) {
                    "getMe" -> bot
                    "answerCallbackQuery" -> "true"
                    else -> error("Unexpected Telegram request: ${request.method()}")
                }
            }
        }
        return json.decodeFromString(request.resultDeserializer, response)
    }

    override fun close() { sent.close() }
}
