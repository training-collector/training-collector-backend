package com.tc.gymtrainer.api

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tc.gymtrainer.db.SharedTransaction
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.service.TelegramReceipts
import com.tc.gymtrainer.service.ValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import java.time.OffsetDateTime
import java.util.UUID

private val receiptJson = jacksonObjectMapper().registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
private const val TRAINING_BOT_PATH = "/internal/v1/training/"

// The mutation and its result commit together. Telegram can retry an update after the
// original function already changed the workout but before its HTTP response arrived.
internal suspend fun ApplicationCall.writeResult(
    status: HttpStatusCode = HttpStatusCode.OK, block: suspend () -> Any?,
) {
    val requestKey = request.headers["Idempotency-Key"]?.takeIf { request.path().startsWith(TRAINING_BOT_PATH) }
    if (requestKey == null) {
        val result = block()
        if (status == HttpStatusCode.NoContent) respond(status) else respond(status, requireNotNull(result))
        return
    }
    val id = try { UUID.fromString(requestKey) } catch (_: IllegalArgumentException) { throw ValidationException("Некорректный Idempotency-Key.") }
    val key = UUID.nameUUIDFromBytes("${request.httpMethod.value} ${request.uri} $id".toByteArray())
    val body = dbQuery {
        exec("SELECT pg_advisory_xact_lock(${key.mostSignificantBits})")
        TelegramReceipts.selectAll().where { TelegramReceipts.key eq key }.singleOrNull()?.get(TelegramReceipts.result)
            ?: withContext(SharedTransaction(this)) {
                val value = block()
                val encoded = if (status == HttpStatusCode.NoContent) "" else receiptJson.writeValueAsString(value)
                TelegramReceipts.insert {
                    it[TelegramReceipts.key] = key
                    it[result] = encoded
                    it[sender] = parameters["sender"]?.toLongOrNull()
                    it[created] = OffsetDateTime.now()
                }
                encoded
            }
    }
    if (status == HttpStatusCode.NoContent) respond(status) else respondText(body, ContentType.Application.Json, status)
}
