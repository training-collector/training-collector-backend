package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.Goals
import com.tc.gymtrainer.service.ValidationException
import com.tc.gymtrainer.db.Clients
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.ClientStatus
import com.tc.gymtrainer.domain.ClientProfile
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.updateReturning
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import java.time.OffsetDateTime

/** Доступ к профилям подопечных. Вызывать внутри Exposed-транзакции. */
class ClientRepository {

    fun insert(trainerId: Long, profile: ClientProfile, now: OffsetDateTime = OffsetDateTime.now()): Client =
        Clients.insertReturning {
            it[Clients.trainerId] = trainerId
            it.writeProfile(profile)
            it[Clients.status] = ClientStatus.ACTIVE
            it[Clients.createdAt] = now
            it[Clients.updatedAt] = now
        }.single().toClient()

    /** Подопечный этого тренера. Чужой и несуществующий неразличимы. */
    fun findOwned(trainerId: Long, id: Long, lock: Boolean = false): Client? =
        Clients.selectAll()
            .where { (Clients.id eq id) and (Clients.trainerId eq trainerId) }
            .apply { if (lock) forUpdate() }
            .firstOrNull()
            ?.toClient()

    /** Список подопечных тренера; по умолчанию только активные, отсортированы по имени. */
    fun listByTrainer(trainerId: Long, includeArchived: Boolean = false): List<Client> {
        val rows = Clients.selectAll().where { Clients.trainerId eq trainerId }
        if (!includeArchived) rows.andWhere { Clients.status eq ClientStatus.ACTIVE }
        return rows.orderBy(Clients.fullName to SortOrder.ASC).map { it.toClient() }
    }

    fun update(id: Long, profile: ClientProfile, previousGoal: String?, now: OffsetDateTime = OffsetDateTime.now()): Client =
        Clients.updateReturning(where = { Clients.id eq id }) {
            it.writeProfile(profile, previousGoal)
            it[Clients.updatedAt] = now
        }.single().toClient()

    private fun UpdateBuilder<*>.writeProfile(profile: ClientProfile, previousGoal: String? = null) {
        this[Clients.firstName] = profile.firstName
        this[Clients.lastName] = profile.lastName
        this[Clients.sex] = profile.sex
        this[Clients.age] = profile.age
        this[Clients.telegramUsername] = profile.telegramUsername
        if (profile.goal != previousGoal) {
            // A shared lock is enough: the goal cannot be renamed or removed until this transaction ends.
            this[Clients.goalId] = profile.goal?.let { name ->
                Goals.selectAll().where { (Goals.name eq name) and (Goals.hidden eq false) }
                    .forUpdate(ForUpdateOption.PostgreSQL.ForShare()).singleOrNull()?.get(Goals.id)
                    ?: throw ValidationException("Выберите доступную цель.")
            }
            this[Clients.goal] = profile.goal
        }
        this[Clients.notes] = profile.notes
    }

    fun setStatus(id: Long, status: ClientStatus, now: OffsetDateTime = OffsetDateTime.now()): Client =
        Clients.updateReturning(where = { Clients.id eq id }) {
            it[Clients.status] = status
            it[Clients.updatedAt] = now
        }.single().toClient()

    /** Тренировки и замеры удаляются вместе с подопечным (ON DELETE CASCADE). */
    fun delete(id: Long): Boolean =
        Clients.deleteWhere { Clients.id eq id } > 0
}
