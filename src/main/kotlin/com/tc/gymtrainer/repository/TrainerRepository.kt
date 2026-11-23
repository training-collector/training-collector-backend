package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WebSessions
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.service.ClientInfo
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.updateReturning
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
        firstName: String,
        lastName: String?,
        username: String? = null,
        signupExpiresAt: OffsetDateTime? = null,
        telegramUserId: Long? = null,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Trainer =
        Trainers.insertReturning {
            it[Trainers.username] = username
            it[Trainers.firstName] = firstName
            it[Trainers.lastName] = lastName
            it[Trainers.signupExpiresAt] = signupExpiresAt
            it[Trainers.telegramUserId] = telegramUserId
            it[Trainers.createdAt] = now
        }.single().toTrainer()

    fun releaseExpiredSignup(username: String, now: OffsetDateTime) = Trainers.deleteWhere {
        (Trainers.username eq username) and telegramLinkedAt.isNull() and (signupExpiresAt lessEq now)
    }

    fun cancelSignup(trainerId: Long) = Trainers.deleteWhere {
        (Trainers.id eq trainerId) and telegramLinkedAt.isNull() and signupExpiresAt.isNotNull()
    }

    fun createSession(trainerId: Long, hash: String, expiry: OffsetDateTime, client: ClientInfo, onboarding: Boolean = false) {
        WebSessions.insert {
            it[WebSessions.trainerId] = trainerId
            it[WebSessions.onboarding] = onboarding
            it[tokenHash] = hash
            it[expiresAt] = expiry
            it[createdAt] = OffsetDateTime.now()
            it[ip] = client.ip?.take(64)
            it[userAgent] = client.userAgent?.take(255)
        }
    }

    /** The trainer of a live session and whether that session only allows finishing the registration. */
    fun sessionTrainer(hash: String): Pair<Trainer, Boolean>? =
        (WebSessions innerJoin Trainers).selectAll().where {
            (WebSessions.tokenHash eq hash) and (WebSessions.expiresAt greater OffsetDateTime.now())
        }.singleOrNull()?.let { it.toTrainer() to it[WebSessions.onboarding] }

    fun deleteSession(hash: String) = WebSessions.deleteWhere { tokenHash eq hash }

    fun findByTokenHash(hash: String): Trainer? =
        Trainers.selectAll().where {
            (Trainers.apiTokenHash eq hash) and (Trainers.apiTokenExpiresAt greater OffsetDateTime.now())
        }.firstOrNull()?.toTrainer()

    /** Sets the key of a trainer. With [currentHash] the key is replaced only if it is still the current one. */
    fun setTokenHash(id: Long, hash: String, expiry: OffsetDateTime, currentHash: String? = null) =
        Trainers.update({
            if (currentHash == null) Trainers.id eq id else (Trainers.id eq id) and (Trainers.apiTokenHash eq currentHash)
        }) {
            it[apiTokenHash] = hash
            it[apiTokenExpiresAt] = expiry
        }

    fun updateName(id: Long, first: String, last: String): Trainer? =
        Trainers.updateReturning(where = { (Trainers.id eq id) and Trainers.approvedAt.isNotNull() and Trainers.deactivatedAt.isNull() }) {
            it[firstName] = first
            it[lastName] = last
        }.singleOrNull()?.toTrainer()

    fun updateTimeZone(id: Long, zone: String): Trainer? =
        Trainers.updateReturning(where = { Trainers.id eq id }) { it[timeZone] = zone }.singleOrNull()?.toTrainer()
}
