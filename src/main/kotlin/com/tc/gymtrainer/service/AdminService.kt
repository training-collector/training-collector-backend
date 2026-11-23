package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.AdminAccess
import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.repository.toTrainer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.statements.StatementType
import java.security.MessageDigest
import java.time.OffsetDateTime

data class AccessNotification(val id: Long, val telegramId: Long, val username: String, val active: Boolean)

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
            notifyAccess(id, active = true)
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
        trainer.telegramUserId?.let { sender -> TelegramDialogs.deleteWhere { TelegramDialogs.sender eq sender } }
        WebSessions.deleteWhere { trainerId eq id }
        TelegramLinks.deleteWhere { trainerId eq id }
        LoginChallenges.deleteWhere { trainerId eq id }
        notifyAccess(id, active = false)
    }

    suspend fun reactivate(id: Long) = dbQuery {
        val trainer = locked(id)
        if (trainer.approvedAt != null) return@dbQuery
        if (trainer.deactivatedAt == null || trainer.telegramLinkedAt == null) throw ConflictException("Сначала одобрите заявку тренера.")
        Trainers.update({ Trainers.id eq id }) {
            it[approvedAt] = OffsetDateTime.now()
            it[deactivatedAt] = null
            it[codeChatStarted] = true
        }
        notifyAccess(id, active = true)
    }

    private fun notifyAccess(trainer: Long, active: Boolean) {
        AccessNotifications.insert {
            it[trainerId] = trainer
            it[AccessNotifications.active] = active
        }
    }

    suspend fun nextNotification(): AccessNotification? = dbQuery {
        // Lease one event. Earlier unacknowledged changes for the same trainer must be delivered first.
        exec("""
            WITH candidate AS (
                SELECT n.id FROM trainer_access_notification n
                WHERE n.next_attempt_at <= now() AND NOT EXISTS (
                    SELECT FROM trainer_access_notification prior WHERE prior.trainer_id = n.trainer_id AND prior.id < n.id
                )
                ORDER BY n.id LIMIT 1 FOR UPDATE OF n SKIP LOCKED
            )
            UPDATE trainer_access_notification n SET next_attempt_at = now() + interval '1 minute'
            FROM candidate c, trainer t WHERE n.id = c.id AND t.id = n.trainer_id
            RETURNING n.id, t.telegram_user_id, coalesce(t.username, t.full_name), n.active
        """.trimIndent(), explicitStatementType = StatementType.SELECT) { rows ->
            if (rows.next()) AccessNotification(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getBoolean(4)) else null
        }
    }

    suspend fun acknowledgeNotification(id: Long) = dbQuery {
        AccessNotifications.deleteWhere { AccessNotifications.id eq id }
    }

    suspend fun deleteTrainer(id: Long) = dbQuery {
        locked(id).telegramUserId?.let { sender -> TelegramDialogs.deleteWhere { TelegramDialogs.sender eq sender } }
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
