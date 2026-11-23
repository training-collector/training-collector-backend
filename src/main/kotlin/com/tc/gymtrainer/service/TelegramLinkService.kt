package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.TelegramBotIdentity
import com.tc.gymtrainer.db.TelegramLinks
import com.tc.gymtrainer.db.WebSessions
import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.repository.toTrainer
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime

data class TelegramLink(val url: String, val expiresAt: OffsetDateTime)
data class ProfileBotLinks(val authUrl: String?, val trainingUrl: String?)

enum class SignupConfirmation { PENDING_APPROVAL, ALREADY_LINKED, TELEGRAM_IN_USE }

private val BOT_USERNAME = Regex("[A-Za-z0-9_]{1,32}")

enum class BotRole(val id: Int) {
    TRAINING(1), LOGIN_CODES(2);

    suspend fun record(username: String) = dbQuery {
        if (!BOT_USERNAME.matches(username)) throw ValidationException("Некорректное имя бота.")
        val current = TelegramBotIdentity.select(TelegramBotIdentity.username)
            .where { TelegramBotIdentity.id eq this@BotRole.id }.singleOrNull()?.get(TelegramBotIdentity.username)
        if (current == username) return@dbQuery
        TelegramBotIdentity.upsert(TelegramBotIdentity.id) {
            it[id] = this@BotRole.id
            it[TelegramBotIdentity.username] = username
        }
        Unit
    }

    internal fun username(): String = TelegramBotIdentity.selectAll().where { TelegramBotIdentity.id eq id }
        .singleOrNull()?.get(TelegramBotIdentity.username) ?: throw BotUnavailableException(
            if (this == LOGIN_CODES) "Бот авторизации пока не подключён." else "Основной Telegram-бот пока не подключён."
        )
}

class TelegramLinkService(private val settings: Settings = Settings.defaults) {
    suspend fun profileLinks(trainingLinked: Boolean): ProfileBotLinks = dbQuery {
        val urls = TelegramBotIdentity.selectAll().associate {
            it[TelegramBotIdentity.id] to "https://t.me/${it[TelegramBotIdentity.username]}"
        }
        ProfileBotLinks(urls[BotRole.LOGIN_CODES.id], if (trainingLinked) urls[BotRole.TRAINING.id] else null)
    }

    /** A link for a trainer who has not linked Telegram yet. Sign-in is requested separately. */
    suspend fun createSignupLink(trainerId: Long): TelegramLink = dbQuery {
        val trainer = Trainers.selectAll().where { Trainers.id eq trainerId }.forUpdate().singleOrNull() ?: throw expired()
        if (trainer[Trainers.telegramLinkedAt] != null) throw AccessDeniedException(
            if (trainer[Trainers.approvedAt] == null) "Регистрация ожидает одобрения." else "Эта ссылка предназначена для регистрации.")
        val now = OffsetDateTime.now()
        val expiry = minOf(now.plus(settings.signupLinkTtl), trainer[Trainers.signupExpiresAt] ?: OffsetDateTime.MAX)
        if (expiry <= now) throw expired()
        val role = BotRole.LOGIN_CODES
        TelegramLink("https://t.me/${role.username()}?start=" + issueToken(trainerId, role, expiry), expiry)
    }

    suspend fun confirmSignup(token: String, sender: Long): SignupConfirmation = dbQuery {
        val now = OffsetDateTime.now()
        val trainer = lockLinkOwner(token, BotRole.LOGIN_CODES, now)
        if (trainer.deactivatedAt != null) throw AccessDeniedException("Аккаунт деактивирован.")
        if (trainer.telegramLinkedAt != null) {
            if (trainer.telegramUserId != sender) throw expired()
            return@dbQuery if (trainer.approvedAt == null) SignupConfirmation.PENDING_APPROVAL else SignupConfirmation.ALREADY_LINKED
        }
        if (trainer.telegramUserId != null && trainer.telegramUserId != sender) throw wrongAccount()
        // Two signup links opened by the same Telegram account can arrive concurrently.
        exec("SELECT pg_advisory_xact_lock($sender)")
        val inUse = Trainers.select(Trainers.id).where {
            (Trainers.telegramUserId eq sender) and (Trainers.id neq trainer.id)
        }.any()
        if (inUse) {
            Trainers.update({ Trainers.id eq trainer.id }) { it[signupTelegramInUse] = true }
            return@dbQuery SignupConfirmation.TELEGRAM_IN_USE
        }
        Trainers.update({ Trainers.id eq trainer.id }) {
            it[telegramUserId] = sender
            it[telegramLinkedAt] = now
            it[codeChatStarted] = true
            it[signupExpiresAt] = null
            it[signupTelegramInUse] = false
        }
        // Keep only the consumed link so a second Start by the same sender can be acknowledged.
        val usedHash = linkHash(BotRole.LOGIN_CODES, token)
        TelegramLinks.deleteWhere { (trainerId eq trainer.id) and (tokenHash neq usedHash) }
        WebSessions.deleteWhere { (trainerId eq trainer.id) and (onboarding eq false) }
        SignupConfirmation.PENDING_APPROVAL
    }

    suspend fun createTrainingLink(trainerId: Long): TelegramLink = dbQuery {
        val trainer = Trainers.selectAll().where { Trainers.id eq trainerId }.forUpdate().single().toTrainer()
        if (trainer.approvedAt == null || trainer.telegramLinkedAt == null) throw AccessDeniedException("Сначала войдите на сайт.")
        if (trainer.trainingBotLinkedAt != null) throw ConflictException("Бот для тренировок уже подключён.")
        val now = OffsetDateTime.now()
        val expiry = now.plus(settings.trainingLinkTtl)
        val role = BotRole.TRAINING
        val token = issueToken(trainerId, role, expiry)
        TelegramLink("https://t.me/${role.username()}?start=$token", expiry)
    }

    suspend fun connectTrainingBot(token: String, sender: Long) = dbQuery {
        val now = OffsetDateTime.now()
        val trainer = lockLinkOwner(token, BotRole.TRAINING, now)
        if (trainer.telegramUserId != sender) throw wrongAccount()
        if (trainer.approvedAt == null || trainer.telegramLinkedAt == null || trainer.trainingBotLinkedAt != null) throw expired(BotRole.TRAINING)
        Trainers.update({ Trainers.id eq trainer.id }) { it[trainingBotLinkedAt] = now }
        TelegramLinks.deleteWhere { trainerId eq trainer.id }
        trainer.copy(trainingBotLinkedAt = now)
    }

    private fun issueToken(id: Long, role: BotRole, expiry: OffsetDateTime): String {
        val token = Credentials.token()
        TelegramLinks.insert {
            it[tokenHash] = linkHash(role, token)
            it[trainerId] = id
            it[expiresAt] = expiry
        }
        return token
    }

    private fun lockLinkOwner(token: String, role: BotRole, now: OffsetDateTime): Trainer {
        val hash = linkHash(role, token)
        val link = TelegramLinks.selectAll().where { TelegramLinks.tokenHash eq hash }.singleOrNull() ?: throw expired(role)
        if (link[TelegramLinks.expiresAt] <= now) throw expired(role)
        return Trainers.selectAll().where { Trainers.id eq link[TelegramLinks.trainerId] }.forUpdate().singleOrNull()?.toTrainer() ?: throw expired(role)
    }

    private fun linkHash(role: BotRole, token: String) = Credentials.digest("${role.id}:$token")

    private fun wrongAccount() = ConflictException("Откройте ссылку из того же аккаунта Telegram, с которым вы вошли на сайт.")
    private fun expired(role: BotRole = BotRole.LOGIN_CODES) = ValidationException(when (role) {
        BotRole.LOGIN_CODES -> "Ссылка недействительна. Получите новую ссылку на сайте и откройте бота по ней."
        BotRole.TRAINING -> "Ссылка недействительна. Войдите на сайт, откройте профиль справа вверху и нажмите «Подключить бота для тренировок»."
    })
}
