package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.SharedTransaction
import com.tc.gymtrainer.db.dbQuery
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Register the wake-up before committing the change. A failed publish rolls back the
// change; a fast consumer waits for the same lock before declaring the outbox empty.
class AuthDispatch(private val publish: (suspend () -> Unit)? = null) {
    suspend fun <T> change(block: suspend () -> T): T {
        if (publish == null) return block()
        return dbQuery {
            exec(LOCK)
            withContext(SharedTransaction(this)) {
                val result = block()
                if (nextDelay() != null) publish.invoke()
                result
            }
        }
    }

    suspend fun nextDelay(): Long? = dbQuery {
        exec(LOCK)
        exec("""
            SELECT ceil(extract(epoch FROM greatest(due - now(), interval '0 seconds')))::bigint
            FROM (
                SELECT min(c.next_delivery_at) AS due FROM login_challenge c JOIN trainer t ON c.trainer_id=t.id
                WHERE c.delivered_at IS NULL AND c.attempts < 5 AND c.expires_at > now()
                  AND t.code_chat_started AND t.approved_at IS NOT NULL
                UNION ALL
                SELECT min(first.next_attempt_at) FROM (
                    SELECT DISTINCT ON (trainer_id) next_attempt_at
                    FROM trainer_access_notification ORDER BY trainer_id, id
                ) first
            ) jobs WHERE due IS NOT NULL ORDER BY due LIMIT 1
        """.trimIndent()) { rows -> if (rows.next()) rows.getLong(1) else null }
    }

    companion object {
        private const val LOCK = "SELECT pg_advisory_xact_lock(741635, 1)"
        fun http(url: String?, serviceKey: String): AuthDispatch {
            if (url.isNullOrBlank()) {
                require(System.getenv("VERCEL_ENV") != "production") { "Set AUTH_DISPATCH_URL" }
                return AuthDispatch()
            }
            val uri = URI.create(url)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && serviceKey.isNotBlank())
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val request = HttpRequest.newBuilder(uri).header("Authorization", "Bearer $serviceKey")
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build()
            return AuthDispatch {
                val response = try { client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await() }
                    catch (e: java.io.IOException) { throw BotUnavailableException("Очередь доставки временно недоступна.") }
                if (response.statusCode() !in 200..299) throw BotUnavailableException("Очередь доставки временно недоступна.")
            }
        }
    }
}
