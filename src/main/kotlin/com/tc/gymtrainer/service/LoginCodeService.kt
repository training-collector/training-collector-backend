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
import java.time.OffsetDateTime

data class LoginChallenge(val challenge: String, val botUrl: String, val expiresAt: OffsetDateTime)
data class CodeDelivery(val challenge: String, val telegramId: Long, val headingSent: Boolean = false)

class LoginCodeService(private val trainers: TrainerRepository) {
    suspend fun request(username: String): LoginChallenge = dbQuery {
        val botUrl = "https://t.me/${BotRole.LOGIN_CODES.username()}?start=login"
        val now = OffsetDateTime.now()
        val response = LoginChallenge(Credentials.token(), botUrl, now.plusMinutes(5))
        val login = username.trim().lowercase()
        if (login.length !in 1..254) return@dbQuery response
        val trainer = Trainers.selectAll().where { Trainers.username eq login }.forUpdate().singleOrNull()?.toTrainer()
        if (trainer?.approvedAt == null || trainer.telegramLinkedAt == null) return@dbQuery response

        issueLoginChallenge(trainer.id, response, now)
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
        if (row[Codes.expiresAt] <= OffsetDateTime.now() || row[Codes.attempts] >= 5) return@dbQuery false
        // On retry the bot derives the same code for this challenge, including after a restart.
        Codes.update({ Codes.challengeId eq id }) { it[codeHash] = Credentials.hashLoginCode(code) }
        true
    }

    suspend fun delivered(id: String) = dbQuery {
        Codes.update({ Codes.challengeId eq id }) { it[deliveredAt] = OffsetDateTime.now() }
    }

    suspend fun markHeadingSent(id: String) = dbQuery {
        Codes.update({ Codes.challengeId eq id }) { it[headingSent] = true }
    }

    suspend fun verify(id: String, code: String): TrainerRegistration {
        if (!id.matches(Regex("[A-Za-z0-9_-]{43}")) || !code.matches(Regex("[0-9]{6}"))) throw LoginException()
        return dbQuery {
            val candidate = Codes.selectAll().where { Codes.challengeId eq id }.singleOrNull() ?: return@dbQuery null
            // The same lock order as request() also serializes verification with a change of approval.
            val trainer = Trainers.selectAll().where { Trainers.id eq candidate[Codes.trainerId] }.forUpdate().single().toTrainer()
            val row = Codes.selectAll().where { Codes.challengeId eq id }.forUpdate().singleOrNull() ?: return@dbQuery null
            val now = OffsetDateTime.now()
            if (trainer.approvedAt == null || trainer.telegramLinkedAt == null || row[Codes.expiresAt] <= now ||
                row[Codes.attempts] >= 5) return@dbQuery null
            val hash = row[Codes.codeHash] ?: return@dbQuery null
            if (!Credentials.matchesLoginCode(code, hash)) {
                Codes.update({ Codes.challengeId eq id }) { it[attempts] = row[Codes.attempts] + 1 }
                return@dbQuery null
            }
            Codes.deleteWhere { challengeId eq id }
            WebSessions.deleteWhere { (trainerId eq trainer.id) and (onboarding eq true) }
            val token = "session." + Credentials.token()
            trainers.createSession(trainer.id, Credentials.digest(token), now.plusDays(30))
            TrainerRegistration(trainer, token)
        } ?: throw LoginException()
    }
}

/** Called with the trainer row locked, for both first-time and returning logins. */
internal fun issueLoginChallenge(id: Long, request: LoginChallenge, now: OffsetDateTime): LoginChallenge {
    Codes.deleteWhere { (trainerId eq id) and (expiresAt lessEq now) }
    val existing = Codes.selectAll().where { Codes.trainerId eq id }.forUpdate().singleOrNull()
    if (existing != null) {
        val delivered = existing[Codes.deliveredAt]
        if (delivered != null && delivered < now.minusMinutes(1) && existing[Codes.attempts] < 5) {
            Codes.update({ Codes.trainerId eq id }) {
                it[deliveredAt] = null
                it[headingSent] = false
                it[nextDeliveryAt] = now
            }
        }
        return request.copy(challenge = existing[Codes.challengeId], expiresAt = existing[Codes.expiresAt])
    }
    Codes.insert {
        it[trainerId] = id
        it[challengeId] = request.challenge
        it[expiresAt] = request.expiresAt
        it[nextDeliveryAt] = now
    }
    return request
}
