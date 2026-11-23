package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.ClientProfile
import com.tc.gymtrainer.domain.ClientStatus
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.MeasurementRepository
import org.jetbrains.exposed.sql.select

private val TELEGRAM_USERNAME = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")

/** Use-cases по профилям подопечных: CRUD, архивация, удаление вместе с историей. */
class ClientService(
    private val clients: ClientRepository,
    private val measurements: MeasurementRepository,
) {

    suspend fun create(trainerId: Long, profile: ClientProfile, measurement: MeasurementInput? = null): Client = dbQuery {
        val client = clients.insert(trainerId, validateProfile(profile))
        if (measurement != null) {
            validateMeasurement(measurement)
            measurements.insert(client.id, measurement)
        }
        client
    }

    suspend fun list(trainerId: Long, includeArchived: Boolean = false): List<Client> =
        dbQuery { clients.listByTrainer(trainerId, includeArchived) }

    suspend fun getOwned(trainerId: Long, clientId: Long): Client = dbQuery { requireOwned(trainerId, clientId) }

    suspend fun update(trainerId: Long, clientId: Long, change: (ClientProfile) -> ClientProfile): Client = dbQuery {
        val current = requireOwned(trainerId, clientId, lock = true)
        clients.update(clientId, validateProfile(change(current.profile())), current.goal)
    }

    fun validateProfile(profile: ClientProfile): ClientProfile {
        val clean = profile.copy(firstName = profile.firstName.trim(), lastName = profile.lastName?.trim()?.ifEmpty { null },
            telegramUsername = profile.telegramUsername?.trim()?.ifEmpty { null }?.removePrefix("@"),
            notes = profile.notes?.trim()?.ifEmpty { null })
        if (clean.firstName.isEmpty() || clean.fullName.length > 255) throw ValidationException("Имя и фамилия вместе должны занимать от 1 до 255 символов.")
        if (clean.age != null && clean.age !in 0..130) throw ValidationException("Введите возраст в полных годах от 0 до 130.")
        if (clean.telegramUsername != null && !TELEGRAM_USERNAME.matches(clean.telegramUsername)) {
            throw ValidationException("Введите ник Telegram латиницей, например @username.")
        }
        return clean
    }

    suspend fun archive(trainerId: Long, clientId: Long): Client = setStatus(trainerId, clientId, ClientStatus.ARCHIVED)

    suspend fun unarchive(trainerId: Long, clientId: Long): Client = setStatus(trainerId, clientId, ClientStatus.ACTIVE)

    private suspend fun setStatus(trainerId: Long, clientId: Long, status: ClientStatus): Client = dbQuery {
        requireOwned(trainerId, clientId)
        clients.setStatus(clientId, status)
    }

    /** Удаляет подопечного вместе с тренировками и замерами и копиями его данных в диалогах бота. */
    suspend fun delete(trainerId: Long, clientId: Long) = dbQuery {
        requireOwned(trainerId, clientId, lock = true)
        clients.delete(clientId)
        Trainers.select(Trainers.telegramUserId).where { Trainers.id eq trainerId }.singleOrNull()
            ?.get(Trainers.telegramUserId)?.let { forgetDialogData(it, keepProgress = true) }
    }

    private fun requireOwned(trainerId: Long, clientId: Long, lock: Boolean = false): Client =
        clients.findOwned(trainerId, clientId, lock) ?: throw NotFoundException("Подопечный не найден.")
}
