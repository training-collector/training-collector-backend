package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.LoginRequests
import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WebSessions
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.repository.TrainerRepository
import com.tc.gymtrainer.repository.toTrainer
import org.jetbrains.exposed.sql.DoubleColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * What the browser gets after asking to sign in. It keeps [secret] and shows [number],
 * which the message in Telegram repeats. The answer looks the same for every login,
 * so it does not tell whether an account exists.
 */
data class LoginStart(val secret: String, val number: Int, val botUrl: String, val expiresAt: OffsetDateTime)

enum class LoginState { PENDING, APPROVED, DENIED, EXPIRED }

/** [token] and [trainer] are set once, when an approved request is exchanged for a session. */
data class LoginStatus(val status: LoginState, val token: String? = null, val trainer: Trainer? = null)

/** A request to put in front of the trainer in Telegram. */
data class LoginDelivery(
    val id: String, val telegramId: Long, val username: String, val number: Int, val browser: String?, val ip: String?,
)

enum class LoginDecision { APPROVED, DENIED, EXPIRED }

private val SECRET = Regex("[A-Za-z0-9_-]{43}")
private val REQUEST_ID = Regex("[A-Za-z0-9_-]{22}")
private const val PENDING = "PENDING"

/**
 * Sign-in without a code: the trainer allows or rejects each request with a button in the
 * auth bot. Nothing can be guessed, and a stranger's request only produces a message the
 * trainer rejects. Each request is independent, so it cannot cancel the trainer's own one.
 */
class LoginService(
    private val trainers: TrainerRepository,
    private val settings: Settings = Settings.defaults,
    private val secrets: Secrets = Secrets.ephemeral,
) {
    suspend fun request(username: String, client: ClientInfo = ClientInfo()): LoginStart = dbQuery {
        val now = OffsetDateTime.now()
        val secret = Credentials.token()
        val start = LoginStart(secret, Credentials.number(10..99), "https://t.me/${BotRole.LOGIN_CODES.username()}", now.plus(settings.loginRequestTtl))
        val login = username.trim().lowercase()
        if (login.length !in 1..254) return@dbQuery start
        val trainer = Trainers.selectAll().where { Trainers.username eq login }.forUpdate().singleOrNull()?.toTrainer() ?: return@dbQuery start
        if (trainer.approvedAt == null || trainer.telegramLinkedAt == null) return@dbQuery start
        LoginRequests.deleteWhere { (trainerId eq trainer.id) and (expiresAt lessEq now) }
        // A few requests may wait at once; beyond that the oldest ones are withdrawn.
        val older = LoginRequests.select(LoginRequests.id).where { LoginRequests.trainerId eq trainer.id }
            .orderBy(LoginRequests.createdAt to SortOrder.DESC).map { it[LoginRequests.id] }.drop(settings.maxPendingLogins - 1)
        if (older.isNotEmpty()) LoginRequests.deleteWhere { LoginRequests.id inList older }
        LoginRequests.insert {
            it[id] = Credentials.shortToken()
            it[trainerId] = trainer.id
            it[secretHash] = Credentials.digest(secret)
            it[number] = start.number.toShort()
            it[status] = PENDING
            it[ip] = client.ip?.take(64)
            it[userAgent] = client.userAgent?.take(255)
            it[createdAt] = now
            it[expiresAt] = start.expiresAt
        }
        start
    }

    /**
     * The state of the request that this browser started. An approved request is exchanged
     * for a session exactly once. An unknown secret looks like a request still waiting.
     */
    suspend fun status(secret: String, client: ClientInfo = ClientInfo()): LoginStatus = dbQuery {
        if (!SECRET.matches(secret)) return@dbQuery LoginStatus(LoginState.PENDING)
        val row = (LoginRequests innerJoin Trainers).selectAll()
            .where { LoginRequests.secretHash eq Credentials.digest(secret) }.forUpdate().singleOrNull()
            ?: return@dbQuery LoginStatus(LoginState.PENDING)
        val id = row[LoginRequests.id]
        val trainer = row.toTrainer()
        val now = OffsetDateTime.now()
        val usable = trainer.approvedAt != null && trainer.telegramLinkedAt != null && trainer.deactivatedAt == null
        when {
            row[LoginRequests.expiresAt] <= now || !usable -> {
                LoginRequests.deleteWhere { LoginRequests.id eq id }
                LoginStatus(LoginState.EXPIRED)
            }
            row[LoginRequests.status] == LoginState.DENIED.name -> {
                LoginRequests.deleteWhere { LoginRequests.id eq id }
                LoginStatus(LoginState.DENIED)
            }
            row[LoginRequests.status] == LoginState.APPROVED.name -> {
                // A successful sign-in withdraws the other requests and the unfinished registration.
                LoginRequests.deleteWhere { trainerId eq trainer.id }
                WebSessions.deleteWhere { (trainerId eq trainer.id) and (onboarding eq true) }
                val token = secrets.issue(TokenKind.SESSION)
                trainers.createSession(trainer.id, Credentials.digest(token), now.plus(settings.sessionTtl), client)
                LoginStatus(LoginState.APPROVED, token, trainer)
            }
            else -> LoginStatus(LoginState.PENDING)
        }
    }

    /** A button press in the auth bot. Only the Telegram account linked to the trainer decides. */
    suspend fun decide(id: String, sender: Long, approve: Boolean): LoginDecision = dbQuery {
        if (!REQUEST_ID.matches(id)) return@dbQuery LoginDecision.EXPIRED
        val row = (LoginRequests innerJoin Trainers).selectAll().where { LoginRequests.id eq id }.forUpdate().singleOrNull()
            ?: return@dbQuery LoginDecision.EXPIRED
        if (row[Trainers.telegramUserId] != sender || row[LoginRequests.expiresAt] <= OffsetDateTime.now()) return@dbQuery LoginDecision.EXPIRED
        // A repeated press reports the decision already made.
        if (row[LoginRequests.status] != PENDING) return@dbQuery LoginDecision.valueOf(row[LoginRequests.status])
        val decision = if (approve) LoginDecision.APPROVED else LoginDecision.DENIED
        LoginRequests.update({ LoginRequests.id eq id }) { it[status] = decision.name }
        decision
    }

    suspend fun activateChat(sender: Long): Boolean = dbQuery {
        Trainers.update({ (Trainers.telegramUserId eq sender) and Trainers.approvedAt.isNotNull() and Trainers.telegramLinkedAt.isNotNull() }) {
            it[codeChatStarted] = true
        } == 1
    }

    /** Leases one request before network I/O. If the bot does not confirm the send, it is offered again later. */
    suspend fun nextDelivery(): LoginDelivery? = dbQuery {
        exec(
            """
            WITH candidate AS (
                SELECT r.id FROM login_request r JOIN trainer t ON t.id = r.trainer_id
                WHERE r.delivered_at IS NULL AND r.status = 'PENDING' AND r.next_delivery_at <= now() AND r.expires_at > now()
                  AND t.code_chat_started AND t.approved_at IS NOT NULL
                ORDER BY r.next_delivery_at LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            )
            UPDATE login_request r SET next_delivery_at = now() + make_interval(secs => ?)
            FROM candidate c, trainer t WHERE r.id = c.id AND t.id = r.trainer_id
            RETURNING r.id, t.telegram_user_id, coalesce(t.username, t.full_name), r.number, r.user_agent, r.ip
            """.trimIndent(),
            listOf(DoubleColumnType() to settings.loginDeliveryRetry.seconds.toDouble()),
            StatementType.SELECT,
        ) { rows ->
            if (rows.next()) LoginDelivery(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getInt(4),
                describeBrowser(rows.getString(5)), rows.getString(6)) else null
        }
    }

    suspend fun delivered(id: String) = dbQuery {
        LoginRequests.update({ LoginRequests.id eq id }) { it[deliveredAt] = OffsetDateTime.now() }
    }
}

/** "Chrome, macOS" from a User-Agent header, so the trainer can recognise their own device. */
internal fun describeBrowser(userAgent: String?): String? {
    val ua = userAgent ?: return null
    val browser = when {
        "YaBrowser/" in ua -> "Яндекс Браузер"
        "Edg/" in ua -> "Edge"
        "OPR/" in ua -> "Opera"
        "Firefox/" in ua || "FxiOS/" in ua -> "Firefox"
        "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> null
    }
    val system = when {
        "iPhone" in ua || "iPad" in ua -> "iOS"
        "Android" in ua -> "Android"
        "Mac OS X" in ua || "Macintosh" in ua -> "macOS"
        "Windows" in ua -> "Windows"
        "Linux" in ua -> "Linux"
        else -> null
    }
    return listOfNotNull(browser, system).joinToString(", ").ifEmpty { null }
}
