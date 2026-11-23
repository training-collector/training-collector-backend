package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.repository.TrainerRepository
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId

data class TrainerRegistration(val trainer: Trainer, val token: String)
data class ApiKey(val token: String, val expiresAt: OffsetDateTime)

/** How the caller proved who they are. Routes decide by this, not by the look of the token. */
enum class Access { SIGNUP, SESSION, API_KEY, BOT }
data class TrainerPrincipal(val trainer: Trainer, val access: Access)

private val USERNAME = Regex("""[\p{L}\p{M}\p{N}._-]+""")

/** Use-cases регистрации тренера, входа по токену и его профиля. */
class TrainerService(
    private val trainers: TrainerRepository,
    private val settings: Settings = Settings.defaults,
    private val secrets: Secrets = Secrets.ephemeral,
) {

    suspend fun findByTelegramUserId(telegramUserId: Long): Trainer? =
        dbQuery { trainers.findByTelegramUserId(telegramUserId) }

    private fun validatedNames(firstName: String, lastName: String): Pair<String, String> {
        val first = firstName.trim()
        val last = lastName.trim()
        if (first.isEmpty() || last.isEmpty() || first.length + last.length + 1 > 255) {
            throw ValidationException("Введите имя и фамилию общей длиной до 254 символов.")
        }
        return first to last
    }

    suspend fun updateName(trainerId: Long, firstName: String, lastName: String): Trainer {
        val (first, last) = validatedNames(firstName, lastName)
        return dbQuery { trainers.updateName(trainerId, first, last) ?: throw AccessDeniedException("Доступ к профилю закрыт.") }
    }

    /** Remembers the trainer's browser time zone, so the bot shows the same dates as the site. */
    suspend fun updateTimeZone(trainerId: Long, zone: String): Trainer {
        val id = try { ZoneId.of(zone.trim()).id } catch (_: DateTimeException) { throw ValidationException("Неизвестный часовой пояс.") }
        if (id.length > 64) throw ValidationException("Неизвестный часовой пояс.")
        return dbQuery { trainers.updateTimeZone(trainerId, id) ?: throw NotFoundException("Тренер не найден.") }
    }

    suspend fun registerForWeb(username: String, firstName: String, lastName: String, client: ClientInfo = ClientInfo()): TrainerRegistration {
        val (first, last) = validatedNames(firstName, lastName)
        val login = username.trim().lowercase()
        if (login.length !in 1..64 || !USERNAME.matches(login)) {
            throw ValidationException("Логин может содержать до 64 символов: буквы, цифры, точку, дефис и подчёркивание.")
        }
        return try {
            dbQuery {
                val now = OffsetDateTime.now()
                val expiry = now.plus(settings.signupTtl)
                trainers.releaseExpiredSignup(login, now)
                val trainer = trainers.insert(first, last, username = login, signupExpiresAt = expiry, now = now)
                val token = secrets.issue(TokenKind.SIGNUP)
                trainers.createSession(trainer.id, Credentials.digest(token), expiry, client, onboarding = true)
                TrainerRegistration(trainer, token)
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") throw ConflictException("Этот логин уже занят.")
            throw e
        }
    }

    /** A token that this service did not issue is rejected without a database query. */
    suspend fun authenticate(token: String): TrainerPrincipal? {
        val kind = secrets.kindOf(token) ?: return null
        if (kind == TokenKind.ADMIN) return null
        return dbQuery {
            val hash = Credentials.digest(token)
            val principal = if (kind == TokenKind.API_KEY) trainers.findByTokenHash(hash)?.let { TrainerPrincipal(it, Access.API_KEY) }
                else trainers.sessionTrainer(hash)?.let { (trainer, onboarding) ->
                    TrainerPrincipal(trainer, if (onboarding) Access.SIGNUP else Access.SESSION)
                }
            principal?.takeIf { it.trainer.deactivatedAt == null }
        }
    }

    /** Ends the session. Leaving an unfinished registration also cancels it. */
    suspend fun logout(principal: TrainerPrincipal, token: String) = dbQuery {
        if (principal.access == Access.SIGNUP) trainers.cancelSignup(principal.trainer.id)
        trainers.deleteSession(Credentials.digest(token))
    }

    /** Replaces the key that authenticated this request. The server generates the new one. */
    suspend fun rotateApiToken(trainerId: Long, current: String): ApiKey = dbQuery {
        val key = ApiKey(secrets.issue(TokenKind.API_KEY), OffsetDateTime.now().plus(settings.apiKeyTtl))
        val updated = trainers.setTokenHash(trainerId, Credentials.digest(key.token), key.expiresAt, Credentials.digest(current))
        if (updated != 1) throw AccessDeniedException("Ключ уже заменён.")
        key
    }

    suspend fun issueApiToken(trainerId: Long): ApiKey = dbQuery {
        val key = ApiKey(secrets.issue(TokenKind.API_KEY), OffsetDateTime.now().plus(settings.apiKeyTtl))
        if (trainers.setTokenHash(trainerId, Credentials.digest(key.token), key.expiresAt) != 1) throw NotFoundException("Тренер не найден.")
        key
    }
}
