package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.ClientProfile
import com.tc.gymtrainer.domain.ClientStatus
import com.tc.gymtrainer.domain.Goal
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.MeasurementRepository
import com.tc.gymtrainer.repository.WorkoutRepository

/** Use-cases по профилям подопечных: CRUD, архивация, удаление вместе с историей. */
class ClientService(
    private val clients: ClientRepository,
    private val workouts: WorkoutRepository,
    private val measurements: MeasurementRepository,
) {

    suspend fun create(
        trainerId: Long,
        fullName: String,
        phone: String?,
        goal: Goal?,
        notes: String?,
    ): Client = create(trainerId, legacyProfile(fullName, phone, goal, notes))

    suspend fun create(trainerId: Long, profile: ClientProfile, measurement: MeasurementInput? = null): Client = dbQuery {
        val client = clients.insert(trainerId, validateProfile(profile))
        saveMeasurement(client.id, measurement)
        client
    }

    suspend fun list(trainerId: Long, includeArchived: Boolean = false): List<Client> =
        dbQuery { clients.listByTrainer(trainerId, includeArchived) }

    /** Возвращает клиента, проверяя принадлежность тренеру. */
    suspend fun getOwned(trainerId: Long, clientId: Long): Client = dbQuery {
        requireOwned(trainerId, clientId)
    }

    suspend fun update(
        trainerId: Long,
        clientId: Long,
        fullName: String,
        phone: String?,
        goal: Goal?,
        notes: String?,
    ): Client = updateProfile(trainerId, clientId) { current ->
        val legacy = legacyProfile(fullName, phone, goal, notes)
        val keepNames = fullName.trim() == current.fullName
        current.copy(firstName = if (keepNames) current.firstName else legacy.firstName,
            lastName = if (keepNames) current.lastName else legacy.lastName, phone = phone, goal = goal, notes = notes)
    }

    suspend fun updateProfile(
        trainerId: Long, clientId: Long, measurement: MeasurementInput? = null, change: (ClientProfile) -> ClientProfile,
    ): Client = dbQuery {
        val current = requireOwned(trainerId, clientId, lock = true)
        clients.update(clientId, validateProfile(change(current.profile()), current.goal))
        saveMeasurement(clientId, measurement)
        requireOwned(trainerId, clientId)
    }

    private fun saveMeasurement(clientId: Long, measurement: MeasurementInput?) {
        if (measurement == null) return
        validateMeasurement(measurement)
        measurements.insert(clientId, measurement)
    }

    private fun legacyProfile(fullName: String, phone: String?, goal: Goal?, notes: String?): ClientProfile {
        val name = fullName.trim()
        return ClientProfile(name.substringBefore(' '), name.substringAfter(' ', "").ifEmpty { null },
            phone = phone, goal = goal, notes = notes)
    }

    fun validateProfile(profile: ClientProfile, previousGoal: Goal? = null): ClientProfile {
        val clean = profile.copy(firstName = profile.firstName.trim(), lastName = profile.lastName?.trim()?.ifEmpty { null },
            telegramUsername = profile.telegramUsername?.trim()?.ifEmpty { null }?.removePrefix("@"),
            phone = profile.phone?.trim()?.ifEmpty { null }, notes = profile.notes?.trim()?.ifEmpty { null })
        if (clean.firstName.isEmpty() || clean.fullName.length > 255) throw ValidationException("Имя и фамилия вместе должны занимать от 1 до 255 символов.")
        if (clean.phone != null && clean.phone.length > 64) throw ValidationException("Телефон слишком длинный.")
        if (clean.age != null && clean.age !in 0..130) throw ValidationException("Введите возраст в полных годах от 0 до 130.")
        if (clean.goal != null && clean.goal != previousGoal && clean.goal !in Goal.available) {
            throw ValidationException("Выберите доступную цель.")
        }
        if (clean.telegramUsername != null && !clean.telegramUsername.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}"))) {
            throw ValidationException("Введите ник Telegram латиницей, например @username.")
        }
        return clean
    }

    suspend fun archive(trainerId: Long, clientId: Long): Client = setStatus(trainerId, clientId, ClientStatus.ARCHIVED)

    suspend fun unarchive(trainerId: Long, clientId: Long): Client = setStatus(trainerId, clientId, ClientStatus.ACTIVE)

    private suspend fun setStatus(trainerId: Long, clientId: Long, status: ClientStatus): Client = dbQuery {
        requireOwned(trainerId, clientId)
        clients.setStatus(clientId, status)
        clients.findById(clientId) ?: throw NotFoundException("Подопечный не найден.")
    }

    /**
     * Полностью удаляет подопечного вместе с историей тренировок и замеров.
     * Тренировки удаляются каскадно (упражнения/подходы), замеры — явно.
     */
    suspend fun delete(trainerId: Long, clientId: Long) = dbQuery {
        requireOwned(trainerId, clientId, lock = true)
        workouts.deleteForClient(clientId)
        measurements.listByClient(clientId).forEach { measurements.delete(it.id) }
        clients.delete(clientId)
    }

    /** Проверка принадлежности клиента тренеру внутри уже открытой транзакции. */
    private fun requireOwned(trainerId: Long, clientId: Long, lock: Boolean = false): Client {
        val client = clients.findById(clientId, lock) ?: throw NotFoundException("Подопечный не найден.")
        if (client.trainerId != trainerId) throw AccessDeniedException("Этот подопечный принадлежит другому тренеру.")
        return client
    }
}
