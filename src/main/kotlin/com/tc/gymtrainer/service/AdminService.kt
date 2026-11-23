package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.AdminAccess
import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.repository.toTrainer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.StatementType
import java.security.MessageDigest
import java.time.OffsetDateTime

enum class NoticeKind { ACTIVATED, DEACTIVATED }

data class TrainerNotice(val id: Long, val telegramId: Long, val username: String, val kind: NoticeKind)

/**
 * Keeps one pending message per trainer: only the latest state matters.
 * A replaced message gets a new id, so a late acknowledgement of the old one changes nothing.
 */
internal fun notifyTrainer(trainer: Long, kind: NoticeKind, now: OffsetDateTime, settings: Settings) {
    TrainerNotices.deleteWhere { trainerId eq trainer }
    TrainerNotices.insert {
        it[trainerId] = trainer
        it[TrainerNotices.kind] = kind.name
        it[expiresAt] = now.plus(settings.notificationTtl)
    }
}

class AdminService(private val settings: Settings = Settings.defaults) {
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
            // The trainer then signs in on the site and confirms it in the auth bot.
            notifyTrainer(id, NoticeKind.ACTIVATED, now, settings)
        }
    }

    suspend fun deactivate(id: Long) = dbQuery {
        val trainer = locked(id)
        if (trainer.deactivatedAt != null) return@dbQuery
        if (trainer.approvedAt == null) throw ConflictException("Тренер ещё не активирован.")
        val now = OffsetDateTime.now()
        Trainers.update({ Trainers.id eq id }) {
            it[approvedAt] = null
            it[deactivatedAt] = now
            it[trainingBotLinkedAt] = null
            it[codeChatStarted] = false
            it[apiTokenHash] = null
            it[apiTokenExpiresAt] = null
        }
        trainer.telegramUserId?.let { forgetDialogData(it, keepProgress = false) }
        WebSessions.deleteWhere { trainerId eq id }
        TelegramLinks.deleteWhere { trainerId eq id }
        LoginRequests.deleteWhere { trainerId eq id }
        notifyTrainer(id, NoticeKind.DEACTIVATED, now, settings)
    }

    suspend fun reactivate(id: Long) = dbQuery {
        val trainer = locked(id)
        if (trainer.approvedAt != null) return@dbQuery
        if (trainer.deactivatedAt == null || trainer.telegramLinkedAt == null) throw ConflictException("Сначала одобрите заявку тренера.")
        val now = OffsetDateTime.now()
        Trainers.update({ Trainers.id eq id }) {
            it[approvedAt] = now
            it[deactivatedAt] = null
            it[codeChatStarted] = true
        }
        notifyTrainer(id, NoticeKind.ACTIVATED, now, settings)
    }

    /** Leases one message. An undeliverable one is retried after a pause until its term ends. */
    suspend fun nextNotification(): TrainerNotice? = dbQuery {
        exec("""
            WITH candidate AS (
                SELECT n.id FROM trainer_notice n
                WHERE n.next_attempt_at <= now() AND n.expires_at > now()
                ORDER BY n.next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE trainer_notice n SET next_attempt_at = now() + make_interval(secs => ?)
            FROM candidate c, trainer t WHERE n.id = c.id AND t.id = n.trainer_id
            RETURNING n.id, t.telegram_user_id, coalesce(t.username, t.full_name), n.kind
        """.trimIndent(), listOf(DoubleColumnType() to settings.notificationRetryPause.seconds.toDouble()), StatementType.SELECT) { rows ->
            if (!rows.next()) null
            else TrainerNotice(rows.getLong(1), rows.getLong(2), rows.getString(3), NoticeKind.valueOf(rows.getString(4)))
        }
    }

    suspend fun acknowledgeNotification(id: Long) = dbQuery {
        TrainerNotices.deleteWhere { TrainerNotices.id eq id }
    }

    /** Clients, workouts and measurements go with the trainer row (ON DELETE CASCADE). */
    suspend fun deleteTrainer(id: Long) = dbQuery {
        locked(id).telegramUserId?.let { forgetDialogData(it, keepProgress = false) }
        Trainers.deleteWhere { Trainers.id eq id }
    }

    private fun locked(id: Long) = Trainers.selectAll().where { Trainers.id eq id }.forUpdate()
        .singleOrNull()?.toTrainer() ?: throw NotFoundException("Тренер не найден.")
}

class AdminAuth(
    private val access: AdminAccess,
    private val settings: Settings = Settings.defaults,
    private val secrets: Secrets = Secrets.ephemeral,
) {
    private val enabled = access.login.isNotBlank() && access.password.isNotBlank()
    private fun digest(token: String) = Credentials.digest("${access.login}\u0000${access.password}\u0000$token")
    private fun matches(a: String, b: String) = MessageDigest.isEqual(Credentials.digest(a).toByteArray(), Credentials.digest(b).toByteArray())

    suspend fun login(login: String, password: String, client: ClientInfo = ClientInfo()): String? {
        if (!enabled) return null
        val accepted = matches(access.login, login) and matches(access.password, password)
        if (!accepted) return null
        val token = secrets.issue(TokenKind.ADMIN)
        dbQuery {
            val now = OffsetDateTime.now()
            AdminSessions.insert {
                it[tokenHash] = digest(token)
                it[expiresAt] = now.plus(settings.adminSessionTtl)
                it[createdAt] = now
                it[ip] = client.ip?.take(64)
                it[userAgent] = client.userAgent?.take(255)
            }
        }
        return token
    }

    /** A token without this service's tag is rejected before the database is asked. */
    suspend fun valid(token: String) = enabled && secrets.kindOf(token) == TokenKind.ADMIN && dbQuery {
        AdminSessions.select(AdminSessions.tokenHash).where {
            (AdminSessions.tokenHash eq digest(token)) and (AdminSessions.expiresAt greater OffsetDateTime.now())
        }.any()
    }

    suspend fun logout(token: String) = dbQuery { AdminSessions.deleteWhere { tokenHash eq digest(token) } }
}
