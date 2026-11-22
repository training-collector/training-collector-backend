package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.Goals
import com.tc.gymtrainer.service.ValidationException
import com.tc.gymtrainer.db.Clients
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.ClientStatus
import com.tc.gymtrainer.domain.ClientProfile
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/** Доступ к профилям подопечных. Вызывать внутри Exposed-транзакции. */
class ClientRepository {

    fun insert(
        trainerId: Long,
        profile: ClientProfile,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Client {
        val id = Clients.insertAndGetId {
            it[Clients.trainerId] = trainerId
            it.writeProfile(profile)
            it[Clients.status] = ClientStatus.ACTIVE
            it[Clients.createdAt] = now
            it[Clients.updatedAt] = now
        }.value
        return requireNotNull(findById(id))
    }

    fun findById(id: Long, lock: Boolean = false): Client? =
        Clients.selectAll()
            .where { Clients.id eq id }
            .apply { if (lock) forUpdate() }
            .firstOrNull()
            ?.toClient()

    /** Список подопечных тренера; по умолчанию только активные, отсортированы по имени. */
    fun listByTrainer(trainerId: Long, includeArchived: Boolean = false): List<Client> {
        val condition = Op.build {
            if (includeArchived) {
                Clients.trainerId eq trainerId
            } else {
                (Clients.trainerId eq trainerId) and (Clients.status eq ClientStatus.ACTIVE)
            }
        }
        return Clients.selectAll()
            .where(condition)
            .orderBy(Clients.fullName to SortOrder.ASC)
            .map { it.toClient() }
    }

    fun update(
        id: Long,
        profile: ClientProfile,
        previousGoal: String?,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Boolean =
        Clients.update({ Clients.id eq id }) {
            it.writeProfile(profile, previousGoal)
            it[Clients.updatedAt] = now
        } > 0

    private fun UpdateBuilder<*>.writeProfile(profile: ClientProfile, previousGoal: String? = null) {
        this[Clients.fullName] = profile.fullName
        this[Clients.firstName] = profile.firstName
        this[Clients.lastName] = profile.lastName
        this[Clients.sex] = profile.sex
        this[Clients.age] = profile.age
        this[Clients.telegramUsername] = profile.telegramUsername
        this[Clients.phone] = profile.phone
        if (profile.goal != previousGoal) {
            this[Clients.goalId] = profile.goal?.let { name ->
                Goals.selectAll().where { (Goals.name eq name) and (Goals.hidden eq false) }
                    .forUpdate().singleOrNull()?.get(Goals.id) ?: throw ValidationException("Выберите доступную цель.")
            }
            this[Clients.goal] = profile.goal
        }
        this[Clients.notes] = profile.notes
    }

    fun setStatus(id: Long, status: ClientStatus, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        Clients.update({ Clients.id eq id }) {
            it[Clients.status] = status
            it[Clients.updatedAt] = now
        } > 0

    fun delete(id: Long): Boolean =
        Clients.deleteWhere { with(it) { Clients.id eq id } } > 0
}
