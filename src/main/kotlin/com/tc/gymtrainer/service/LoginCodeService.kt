package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.LoginChallenges as Codes
import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WebSessions
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.repository.TrainerRepository
import com.tc.gymtrainer.repository.toTrainer
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime

data class LoginChallenge(val challenge: String, val botUrl: String, val expiresAt: OffsetDateTime, val deliveryDelaySeconds: Long = 0)
data class CodeDelivery(val challenge: String, val telegramId: Long, val headingSent: Boolean = false)

class LoginCodeService(private val trainers: TrainerRepository) {
    suspend fun request(username: String, resend: Boolean = false): LoginChallenge = dbQuery {
        val botUrl = "https://t.me/${BotRole.LOGIN_CODES.username()}?start=login"
        val now = OffsetDateTime.now()
        val response = LoginChallenge(Credentials.token(), botUrl, now.plusMinutes(5))
        val login = username.trim().lowercase()
        if (login.length !in 1..254) return@dbQuery response
        val trainer = Trainers.selectAll().where { Trainers.username eq login }.forUpdate().singleOrNull()?.toTrainer()
        if (trainer?.approvedAt == null || trainer.telegramLinkedAt == null) return@dbQuery response

        issueLoginChallenge(trainer.id, response, now, resend)
    }

    suspend fun activateChat(sender: Long): Boolean = dbQuery {
        Trainers.update({ (Trainers.telegramUserId eq sender) and Trainers.approvedAt.isNotNull() and Trainers.telegramLinkedAt.isNotNull() }) {
            it[codeChatStarted] = true
        } == 1
    }

    /** Lease a delivery before network I/O. Only the dedicated bot knows how to derive the code. */
    suspend fun nextDelivery(): CodeDelivery? = dbQuery {
        val now = OffsetDateTime.now()
        val row = (Codes innerJoin Trainers).selectAll().where {
            Codes.deliveredAt.isNull() and (Codes.nextDeliveryAt lessEq now) and (Codes.expiresAt greater now) and
                (Codes.attempts less 5) and (Trainers.codeChatStarted eq true) and Trainers.approvedAt.isNotNull()
        }.orderBy(Codes.nextDeliveryAt).limit(1).forUpdate().singleOrNull() ?: return@dbQuery null
        val id = row[Codes.challengeId]
        Codes.update({ Codes.challengeId eq id }) { it[nextDeliveryAt] = now.plusMinutes(1) }
        CodeDelivery(id, row[Trainers.telegramUserId]!!, row[Codes.headingSent])
    }

    suspend fun prepareDelivery(id: String, code: String): Boolean = dbQuery {
        val row = Codes.selectAll().where { Codes.challengeId eq id }.forUpdate().singleOrNull() ?: return@dbQuery false
        val now = OffsetDateTime.now()
        if (row[Codes.expiresAt] <= now || row[Codes.attempts] >= 5) return@dbQuery false
        // On retry the bot derives the same code for this challenge, including after a restart.
        Codes.update({ Codes.challengeId eq id }) {
            if (row[Codes.codeHash] == null) it[expiresAt] = now.plusMinutes(5)
            it[codeHash] = Credentials.hashLoginCode(code)
        }
        true
    }

    suspend fun delivered(id: String) = dbQuery {
        Codes.update({ Codes.challengeId eq id }) { it[deliveredAt] = OffsetDateTime.now() }
    }

    suspend fun markHeadingSent(id: String) = dbQuery {
        Codes.update({ Codes.challengeId eq id }) { it[headingSent] = true }
    }

    suspend fun verify(id: String?, code: String, username: String? = null): TrainerRegistration {
        val login = username?.trim()?.lowercase()
        if (!code.matches(Regex("[0-9]{6}"))) throw LoginException()
        if (id != null) {
            if (login != null || !id.matches(Regex("[A-Za-z0-9_-]{43}"))) throw LoginException()
        } else if (login == null || login.length !in 1..254) throw LoginException()
        return dbQuery {
            // Lock the account first, as in approval and resend. A browser can have opened login before approval.
            val owner = if (id != null) {
                val candidate = Codes.select(Codes.trainerId).where { Codes.challengeId eq id }.singleOrNull() ?: return@dbQuery null
                Trainers.selectAll().where { Trainers.id eq candidate[Codes.trainerId] }
            } else Trainers.selectAll().where { Trainers.username eq login }
            val trainer = owner.forUpdate().singleOrNull()?.toTrainer() ?: return@dbQuery null
            val row = Codes.selectAll().where { Codes.trainerId eq trainer.id }.forUpdate().singleOrNull() ?: return@dbQuery null
            if (id != null && row[Codes.challengeId] != id) return@dbQuery null
            val now = OffsetDateTime.now()
            if (trainer.approvedAt == null || trainer.telegramLinkedAt == null || row[Codes.expiresAt] <= now ||
                row[Codes.attempts] >= 5) return@dbQuery null
            val hash = row[Codes.codeHash] ?: return@dbQuery null
            if (!Credentials.matchesLoginCode(code, hash)) {
                Codes.update({ Codes.trainerId eq trainer.id }) { it[attempts] = row[Codes.attempts] + 1 }
                return@dbQuery null
            }
            Codes.deleteWhere { trainerId eq trainer.id }
            WebSessions.deleteWhere { (trainerId eq trainer.id) and (onboarding eq true) }
            val token = "session." + Credentials.token()
            trainers.createSession(trainer.id, Credentials.digest(token), now.plusDays(30))
            TrainerRegistration(trainer, token)
        } ?: throw LoginException()
    }
}

/** Called with the trainer row locked, for both first-time and returning logins. */
internal fun issueLoginChallenge(id: Long, request: LoginChallenge, now: OffsetDateTime, resend: Boolean = false): LoginChallenge {
    Codes.deleteWhere { (trainerId eq id) and (expiresAt lessEq now) }
    val existing = Codes.selectAll().where { Codes.trainerId eq id }.forUpdate().singleOrNull()
    if (existing != null) {
        val delivered = existing[Codes.deliveredAt]
        val locked = existing[Codes.attempts] >= 5
        if (resend && (existing[Codes.codeHash] != null || locked)) {
            val sendAt = when {
                locked -> existing[Codes.expiresAt]
                delivered != null -> maxOf(now, delivered.plusMinutes(1))
                else -> maxOf(now, existing[Codes.nextDeliveryAt]) // The bot may still be acknowledging the last send.
            }
            val expiry = sendAt.plusMinutes(5)
            Codes.update({ Codes.trainerId eq id }) {
                it[challengeId] = request.challenge
                it[expiresAt] = expiry
                it[codeHash] = null
                it[deliveredAt] = null
                it[headingSent] = false
                it[nextDeliveryAt] = sendAt
                if (locked) it[attempts] = 0 // Delivery starts only after the previous lockout expires.
            }
            return request.copy(expiresAt = expiry, deliveryDelaySeconds = Duration.between(now, sendAt).toSeconds())
        }
        if (delivered != null && delivered < now.minusMinutes(1) && existing[Codes.attempts] < 5) {
            Codes.update({ Codes.trainerId eq id }) {
                it[deliveredAt] = null
                it[headingSent] = false
                it[nextDeliveryAt] = now
            }
        }
        val delay = if (delivered == null) Duration.between(now, existing[Codes.nextDeliveryAt]).toSeconds().coerceAtLeast(0) else 0
        return request.copy(challenge = existing[Codes.challengeId], expiresAt = existing[Codes.expiresAt], deliveryDelaySeconds = delay)
    }
    Codes.insert {
        it[trainerId] = id
        it[challengeId] = request.challenge
        it[expiresAt] = request.expiresAt
        it[nextDeliveryAt] = now
    }
    return request
}
