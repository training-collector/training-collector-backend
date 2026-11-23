package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.RateLimit
import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.AdminSessions
import com.tc.gymtrainer.db.AuditEvents
import com.tc.gymtrainer.db.LoginRequests
import com.tc.gymtrainer.db.RateLimits
import com.tc.gymtrainer.db.TelegramLinks
import com.tc.gymtrainer.db.TrainerNotices
import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WebSessions
import com.tc.gymtrainer.db.dbQuery
import org.jetbrains.exposed.sql.DoubleColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.StatementType
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Who is calling: recorded with sessions and audit events, and used as the rate-limit key. */
data class ClientInfo(val ip: String? = null, val userAgent: String? = null)

/**
 * Fixed-window request counters. Counters for anonymous routes live in the database so
 * that every instance shares them; a sleeping instance does not reset a limit.
 */
class RateLimiter(private val enabled: Boolean = true) {
    private val local = ConcurrentHashMap<String, LongArray>()

    suspend fun check(scope: String, key: String?, limit: RateLimit) {
        if (!enabled) return
        val bucket = scope + ":" + Credentials.digest(key ?: "unknown").take(40)
        val seconds = limit.window.toMillis() / 1000.0
        val (hits, retryAfter) = dbQuery {
            exec(
                """
                INSERT INTO rate_limit AS r (bucket, window_start, hits) VALUES (?, now(), 1)
                ON CONFLICT (bucket) DO UPDATE SET
                    hits = CASE WHEN r.window_start <= now() - make_interval(secs => ?) THEN 1 ELSE r.hits + 1 END,
                    window_start = CASE WHEN r.window_start <= now() - make_interval(secs => ?) THEN now() ELSE r.window_start END
                RETURNING hits, ceil(extract(epoch FROM window_start + make_interval(secs => ?) - now()))::bigint
                """.trimIndent(),
                listOf(VarCharColumnType() to bucket, DoubleColumnType() to seconds, DoubleColumnType() to seconds, DoubleColumnType() to seconds),
                StatementType.SELECT,
            ) { rows -> rows.next(); rows.getInt(1) to rows.getLong(2) }!!
        }
        if (hits > limit.requests) throw TooManyRequestsException(retryAfter.coerceAtLeast(1))
    }

    /** Per-instance counter for authenticated traffic: it must not add a query to every request. */
    fun checkLocal(scope: String, key: Any, limit: RateLimit) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val window = limit.window.toMillis()
        val state = local.compute("$scope:$key") { _, current ->
            if (current == null || now - current[0] >= window) longArrayOf(now, 1) else current.also { it[1]++ }
        }!!
        if (state[1] > limit.requests) throw TooManyRequestsException(((state[0] + window - now) / 1000).coerceAtLeast(1))
        if (local.size > 10_000) local.entries.removeIf { now - it.value[0] >= window }
    }
}

/** Security events and administrator actions. Hosted logs are short-lived, this table is not. */
class Audit {
    suspend fun record(actor: String, action: String, subject: String? = null, ip: String? = null, detail: String? = null) {
        try {
            dbQuery {
                AuditEvents.insert {
                    it[at] = OffsetDateTime.now()
                    it[AuditEvents.actor] = actor.take(64)
                    it[AuditEvents.action] = action.take(64)
                    it[AuditEvents.subject] = subject?.take(160)
                    it[AuditEvents.ip] = ip?.take(64)
                    it[AuditEvents.detail] = detail?.take(255)
                }
            }
        } catch (e: Exception) {
            // The audited action has already happened; a failed journal entry must not undo it.
            log.error("Audit record failed ({}) for {}", e.javaClass.simpleName, action)
        }
    }

    private companion object { val log = LoggerFactory.getLogger(Audit::class.java) }
}

/**
 * Deletes expired rows at most once per interval, on a request that already uses the
 * database. A timer would wake a sleeping database on its own.
 */
class Housekeeping(private val settings: Settings = Settings.defaults) {
    private val nextRun = AtomicLong(0)

    suspend fun runIfDue() {
        val now = System.currentTimeMillis()
        val due = nextRun.get()
        if (now < due || !nextRun.compareAndSet(due, now + settings.cleanupInterval.toMillis())) return
        try {
            dbQuery {
                val time = OffsetDateTime.now()
                // Sessions and links of an abandoned registration go with its trainer row.
                Trainers.deleteWhere { telegramLinkedAt.isNull() and (signupExpiresAt lessEq time) }
                WebSessions.deleteWhere { expiresAt lessEq time }
                AdminSessions.deleteWhere { expiresAt lessEq time }
                TelegramLinks.deleteWhere { expiresAt lessEq time }
                TrainerNotices.deleteWhere { expiresAt lessEq time }
                LoginRequests.deleteWhere { expiresAt lessEq time }
                TelegramDialogs.deleteWhere { seen less time.minus(settings.dialogTtl) }
                TelegramReceipts.deleteWhere { created less time.minus(settings.receiptTtl) }
                AuditEvents.deleteWhere { at less time.minus(settings.auditRetention) }
                RateLimits.deleteWhere { windowStart less time.minus(Duration.ofDays(1)) }
            }
        } catch (e: Exception) {
            LoggerFactory.getLogger(Housekeeping::class.java).warn("Cleanup failed ({})", e.javaClass.simpleName)
        }
    }
}
