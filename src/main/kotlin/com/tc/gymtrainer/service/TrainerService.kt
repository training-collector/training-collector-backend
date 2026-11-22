package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.repository.TrainerRepository
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.OffsetDateTime

data class TrainerRegistration(val trainer: Trainer, val token: String)

/** Use-cases регистрации тренера и учёта согласия на обработку данных. */
class TrainerService(private val trainers: TrainerRepository) {

    suspend fun findByTelegramUserId(telegramUserId: Long): Trainer? =
        dbQuery { trainers.findByTelegramUserId(telegramUserId) }

    suspend fun registerForWeb(username: String, firstName: String, lastName: String): TrainerRegistration {
        val first = firstName.trim()
        val last = lastName.trim()
        if (first.isEmpty() || last.isEmpty() || first.length + last.length + 1 > 255) {
            throw ValidationException("Введите имя и фамилию общей длиной до 254 символов.")
        }
        val login = username.trim().lowercase()
        if (login.length !in 1..64 || !login.matches(Regex("""[\p{L}\p{M}\p{N}._-]+"""))) {
            throw ValidationException("Логин может содержать до 64 символов: буквы, цифры, точку, дефис и подчёркивание.")
        }
        return try {
            dbQuery {
                val now = OffsetDateTime.now()
                val expiry = now.plusDays(1)
                trainers.releaseExpiredSignup(login, now)
                val trainer = trainers.insert(null, "$first $last", null, null, username = login,
                    firstName = first, lastName = last, signupExpiresAt = expiry)
                val token = "signup." + Credentials.token()
                trainers.createSession(trainer.id, Credentials.digest(token), expiry, onboarding = true)
                TrainerRegistration(trainer, token)
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") throw ConflictException("Этот логин уже занят.")
            throw e
        }
    }

    suspend fun authenticate(token: String): Trainer? = dbQuery {
        val hash = Credentials.digest(token)
        val trainer = if (token.startsWith("session.") || token.startsWith("signup.")) trainers.sessionTrainer(hash) else trainers.findByTokenHash(hash)
        trainer?.takeIf { it.deactivatedAt == null }
    }

    suspend fun logout(token: String) = dbQuery {
        val hash = Credentials.digest(token)
        if (token.startsWith("signup.")) trainers.sessionTrainer(hash)?.let { trainers.cancelSignup(it.id) }
        trainers.deleteSession(hash)
    }

    suspend fun replaceApiToken(trainerId: Long, current: String, replacement: String) = dbQuery {
        if (current == replacement || !replacement.matches(Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]"))) {
            throw ValidationException("Требуется новый ключ из 32 байт в формате base64url без дополнения.")
        }
        val updated = try {
            trainers.replaceTokenHash(trainerId, Credentials.digest(current), Credentials.digest(replacement))
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") throw ValidationException("Выберите другой ключ.")
            throw e
        }
        if (updated != 1) {
            throw AccessDeniedException("Ключ уже заменён.")
        }
    }

    suspend fun issueApiToken(trainerId: Long): String = dbQuery {
        val token = Credentials.token()
        if (trainers.setTokenHash(trainerId, Credentials.digest(token)) != 1) throw NotFoundException("Тренер не найден.")
        token
    }

}
