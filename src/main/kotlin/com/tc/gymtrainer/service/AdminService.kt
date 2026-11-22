package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.AdminAccess
import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.repository.toTrainer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import java.security.MessageDigest
import java.time.OffsetDateTime

class AdminService {
    suspend fun trainers() = dbQuery {
        Trainers.selectAll().where { Trainers.telegramLinkedAt.isNotNull() }
            .orderBy(Trainers.telegramLinkedAt to SortOrder.DESC, Trainers.id to SortOrder.DESC).map { it.toTrainer() }
    }

    suspend fun approve(id: Long) = dbQuery {
        val trainer = locked(id)
        if (trainer.telegramLinkedAt == null || trainer.deactivatedAt != null) throw ConflictException("Заявка недоступна.")
        if (trainer.approvedAt == null) {
            val now = OffsetDateTime.now()
            Trainers.update({ Trainers.id eq id }) { it[approvedAt] = now }
            // The administrator may respond days after the signup link was issued.
            LoginChallenges.deleteWhere { trainerId eq id }
            LoginChallenges.insert {
                it[trainerId] = id
                it[challengeId] = Credentials.token()
                it[expiresAt] = now.plusMinutes(5)
                it[nextDeliveryAt] = now
            }
        }
    }

    suspend fun deactivate(id: Long) = dbQuery {
        val trainer = locked(id)
        if (trainer.deactivatedAt != null) return@dbQuery
        if (trainer.approvedAt == null) throw ConflictException("Тренер ещё не активирован.")
        Trainers.update({ Trainers.id eq id }) {
            it[approvedAt] = null
            it[deactivatedAt] = OffsetDateTime.now()
            it[trainingBotLinkedAt] = null
            it[codeChatStarted] = false
            it[apiTokenHash] = null
        }
        WebSessions.deleteWhere { trainerId eq id }
        TelegramLinks.deleteWhere { trainerId eq id }
        LoginChallenges.deleteWhere { trainerId eq id }
    }

    suspend fun deleteTrainer(id: Long) = dbQuery {
        locked(id)
        val clientIds = Clients.select(Clients.id).where { Clients.trainerId eq id }
        BodyMeasurements.deleteWhere { clientId inSubQuery clientIds }
        WorkoutSessions.deleteWhere { trainerId eq id }
        Clients.deleteWhere { trainerId eq id }
        Trainers.deleteWhere { Trainers.id eq id }
    }

    private fun locked(id: Long) = Trainers.selectAll().where { Trainers.id eq id }.forUpdate()
        .singleOrNull()?.toTrainer() ?: throw NotFoundException("Тренер не найден.")
}

class AdminAuth(private val access: AdminAccess) {
    private val enabled = access.login.isNotBlank() && access.password.isNotBlank()
    private fun digest(token: String) = Credentials.digest("${access.login}\u0000${access.password}\u0000$token")
    private fun matches(a: String, b: String) = MessageDigest.isEqual(Credentials.digest(a).toByteArray(), Credentials.digest(b).toByteArray())

    suspend fun login(login: String, password: String): String? {
        if (!enabled) return null
        val accepted = matches(access.login, login) and matches(access.password, password)
        if (!accepted) return null
        val token = "admin." + Credentials.token()
        dbQuery {
            val now = OffsetDateTime.now()
            AdminSessions.deleteWhere { expiresAt lessEq now }
            AdminSessions.insert { it[tokenHash] = digest(token); it[expiresAt] = now.plusHours(8) }
        }
        return token
    }

    suspend fun valid(token: String) = enabled && token.startsWith("admin.") && dbQuery {
        AdminSessions.select(AdminSessions.tokenHash).where {
            (AdminSessions.tokenHash eq digest(token)) and (AdminSessions.expiresAt greater OffsetDateTime.now())
        }.any()
    }

    suspend fun logout(token: String) = dbQuery { AdminSessions.deleteWhere { tokenHash eq digest(token) } }
}
