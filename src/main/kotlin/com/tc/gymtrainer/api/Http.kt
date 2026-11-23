package com.tc.gymtrainer.api

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.service.Access
import com.tc.gymtrainer.service.Audit
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.ClientInfo
import com.tc.gymtrainer.service.TrainerPrincipal
import com.tc.gymtrainer.service.ValidationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.util.UUID

internal const val ADMIN = "admin"
private val requestLog = LoggerFactory.getLogger("Request")
private val errorLog = LoggerFactory.getLogger("Failure")
private val startedAt = AttributeKey<Long>("requestStartedAt")
private val requestId = AttributeKey<String>("requestId")
private val clientIpHeader = AttributeKey<String>("clientIpHeader")
private val REQUEST_ID = Regex("[A-Za-z0-9_.:-]{1,64}")
private val QUEUE_POLLS = setOf("/internal/v1/auth/deliveries/next", "/internal/v1/auth/notifications/next")

internal val ApplicationCall.principalOfTrainer: TrainerPrincipal get() = checkNotNull(principal<TrainerPrincipal>())
internal val ApplicationCall.trainer: Trainer get() = principalOfTrainer.trainer
internal val ApplicationCall.bearer: String get() = request.headers[HttpHeaders.Authorization].orEmpty().substringAfter(' ').trim()

internal fun ApplicationCall.id(name: String): Long =
    parameters[name]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ValidationException("Некорректный идентификатор: $name.")

/**
 * The address of the caller. Behind a proxy the connection belongs to the proxy, so the
 * configured header is used instead. Its last entry is the one our own proxy appended.
 */
internal val ApplicationCall.clientIp: String
    get() = attributes.getOrNull(clientIpHeader)?.let { name -> request.header(name)?.substringAfterLast(',')?.trim()?.ifEmpty { null } }
        ?: request.local.remoteAddress

internal val ApplicationCall.client: ClientInfo get() = ClientInfo(clientIp, request.header(HttpHeaders.UserAgent))

/** Who made the request, for the request log and the audit journal. */
internal val ApplicationCall.actor: String
    get() = principal<TrainerPrincipal>()?.let { "trainer:${it.trainer.id}/${it.access.name.lowercase()}" }
        ?: principal<BotRole>()?.let { "bot:${it.name.lowercase()}" }
        ?: principal<String>()?.let { ADMIN }
        ?: "anonymous"

internal suspend fun ApplicationCall.audit(audit: Audit, action: String, subject: String? = null, detail: String? = null) =
    audit.record(actor, action, subject, clientIp, detail)

/** One line per request: method, path without query, status, duration, caller. Never bodies or tokens. */
internal fun requestLogging(settings: Settings, ipHeader: String) = createApplicationPlugin("RequestLog") {
    onCall { call ->
        call.attributes.put(startedAt, System.nanoTime())
        if (ipHeader.isNotEmpty()) call.attributes.put(clientIpHeader, ipHeader)
        val id = call.request.header(HttpHeaders.XRequestId)?.takeIf { REQUEST_ID.matches(it) } ?: UUID.randomUUID().toString()
        call.attributes.put(requestId, id)
        call.response.header(HttpHeaders.XRequestId, id)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        // The body is read only up to the declared length, so the declaration itself is checked.
        if (call.request.httpMethod in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
            val length = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (length == null && call.request.header(HttpHeaders.TransferEncoding) != null) {
                throw RequestRejected(HttpStatusCode.LengthRequired, "Укажите Content-Length.")
            }
            if (length != null && length > settings.maxBodyBytes) throw RequestRejected(HttpStatusCode.PayloadTooLarge, "Слишком большой запрос.")
        }
    }
    on(ResponseSent) { call ->
        val path = call.request.path()
        if (path == "/health") return@on
        // The auth bot asks for work every second when polling locally. An empty answer is not an event.
        if (path in QUEUE_POLLS && call.response.status() == HttpStatusCode.NoContent) return@on
        val millis = call.attributes.getOrNull(startedAt)?.let { (System.nanoTime() - it) / 1_000_000 }
        requestLog.info("{} {} {} {}ms {} {} {}", call.request.httpMethod.value, path, call.response.status()?.value ?: 0,
            millis, call.actor, call.clientIp, call.attributes.getOrNull(requestId))
    }
}

private data class ClientErrorInput(val message: String, val source: String? = null, val stack: String? = null)
private val clientErrorLog = LoggerFactory.getLogger("ClientError")
private val WHITESPACE = Regex("\\s+")
internal fun String.singleLine(limit: Int) = replace(WHITESPACE, " ").take(limit)

/** Writes a browser error report to the log as one bounded line. Anyone can call it, so it is rate-limited. */
internal suspend fun ApplicationCall.acceptClientError(services: TrainingServices) {
    services.limiter.check("client-errors", clientIp, services.settings.clientErrorsLimit)
    val input = receive<ClientErrorInput>()
    clientErrorLog.warn("{} | {} | {}", input.source.orEmpty().singleLine(80), input.message.singleLine(300), input.stack.orEmpty().singleLine(1500))
    respond(HttpStatusCode.NoContent)
}

internal class RequestRejected(val status: HttpStatusCode, message: String) : RuntimeException(message)

/** Runs after the route handler has completed without an exception. */
private object AfterCall : Hook<suspend (ApplicationCall) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall) -> Unit) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) {
            proceed()
            handler(context)
        }
    }
}

/** Journals every successful change made through the routes it is installed on. */
internal fun auditChanges(audit: Audit, methods: Set<HttpMethod>) = createRouteScopedPlugin("AuditChanges") {
    on(AfterCall) { call ->
        if (call.request.httpMethod in methods && call.response.status()?.isSuccess() == true) {
            call.audit(audit, call.request.httpMethod.value.lowercase(), call.request.path())
        }
    }
}

/**
 * Logs an unexpected failure without the data it carries. A database error message
 * contains the statement with its values: names, notes, measurements.
 */
internal fun logFailure(call: ApplicationCall, cause: Throwable) {
    val safe = when (cause) {
        is ExposedSQLException -> {
            val server = (cause.cause as? PSQLException)?.serverErrorMessage
            RuntimeException("Database error ${cause.sqlState}: ${server?.message ?: "no server message"}").also { it.stackTrace = cause.stackTrace }
        }
        else -> cause
    }
    errorLog.error("{} {} failed [{}]", call.request.httpMethod.value, call.request.path(), call.attributes.getOrNull(requestId), safe)
}
