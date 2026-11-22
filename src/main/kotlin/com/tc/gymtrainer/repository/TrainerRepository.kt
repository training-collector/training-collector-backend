package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WebSessions
import com.tc.gymtrainer.domain.Trainer
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * Доступ к тренерам. Все методы должны вызываться внутри Exposed-транзакции
 * (её открывает сервисный слой).
 */
class TrainerRepository {

    fun findByTelegramUserId(telegramUserId: Long): Trainer? =
        Trainers.selectAll()
            .where { Trainers.telegramUserId eq telegramUserId }
            .firstOrNull()
            ?.toTrainer()

    fun insert(
        telegramUserId: Long?,
        fullName: String,
        phone: String?,
        consentAt: OffsetDateTime?,
        now: OffsetDateTime = OffsetDateTime.now(),
        username: String? = null,
        firstName: String? = null,
        lastName: String? = null,
        signupExpiresAt: OffsetDateTime? = null,
    ): Trainer {
        val id = Trainers.insertAndGetId {
            it[Trainers.username] = username
            it[Trainers.firstName] = firstName
            it[Trainers.lastName] = lastName
            it[Trainers.signupExpiresAt] = signupExpiresAt
            it[Trainers.telegramUserId] = telegramUserId
            it[Trainers.fullName] = fullName
            it[Trainers.phone] = phone
            it[Trainers.consentAt] = consentAt
            it[Trainers.createdAt] = now
        }.value
        return Trainer(id, telegramUserId, fullName, phone, consentAt, now, username, firstName, lastName)
    }

    fun releaseExpiredSignup(username: String, now: OffsetDateTime) = Trainers.deleteWhere {
        (Trainers.username eq username) and telegramLinkedAt.isNull() and (signupExpiresAt lessEq now)
    }

    fun cancelSignup(trainerId: Long) = Trainers.deleteWhere {
        (Trainers.id eq trainerId) and telegramLinkedAt.isNull() and signupExpiresAt.isNotNull()
    }

    fun createSession(trainerId: Long, hash: String, expiry: OffsetDateTime, onboarding: Boolean = false) {
        WebSessions.deleteWhere { expiresAt lessEq OffsetDateTime.now() }
        WebSessions.insert {
            it[WebSessions.trainerId] = trainerId
            it[WebSessions.onboarding] = onboarding
            it[tokenHash] = hash
            it[expiresAt] = expiry
        }
    }

    fun sessionTrainer(hash: String): Trainer? =
        (WebSessions innerJoin Trainers).selectAll().where {
            (WebSessions.tokenHash eq hash) and (WebSessions.expiresAt greater OffsetDateTime.now())
        }.singleOrNull()?.toTrainer()

    fun deleteSession(hash: String) = WebSessions.deleteWhere { tokenHash eq hash }

    fun findByTokenHash(hash: String): Trainer? =
        Trainers.selectAll().where { Trainers.apiTokenHash eq hash }.firstOrNull()?.toTrainer()

    fun setTokenHash(id: Long, hash: String) =
        Trainers.update({ Trainers.id eq id }) { it[apiTokenHash] = hash }

    fun replaceTokenHash(id: Long, currentHash: String, newHash: String) =
        Trainers.update({ (Trainers.id eq id) and (Trainers.apiTokenHash eq currentHash) }) { it[apiTokenHash] = newHash }

}
