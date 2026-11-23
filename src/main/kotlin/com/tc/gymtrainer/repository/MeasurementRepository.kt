package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.BodyMeasurements
import com.tc.gymtrainer.db.Clients
import com.tc.gymtrainer.domain.BodyMeasurement
import com.tc.gymtrainer.domain.MeasurementInput
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.updateReturning
import java.time.OffsetDateTime

/** Доступ к замерам тела подопечных. Вызывать в транзакции. */
class MeasurementRepository {

    fun insert(clientId: Long, input: MeasurementInput): BodyMeasurement =
        BodyMeasurements.insertReturning {
            it[BodyMeasurements.clientId] = clientId
            it[createdAt] = OffsetDateTime.now()
            it.setValues(input)
        }.single().toBodyMeasurement()

    private fun UpdateBuilder<*>.setValues(input: MeasurementInput) {
        this[BodyMeasurements.measuredOn] = input.measuredOn
        this[BodyMeasurements.heightCm] = input.heightCm
        this[BodyMeasurements.weightKg] = input.weightKg
        this[BodyMeasurements.bodyFatPct] = input.bodyFatPct
        this[BodyMeasurements.chestCm] = input.chestCm
        this[BodyMeasurements.waistCm] = input.waistCm
        this[BodyMeasurements.hipsCm] = input.hipsCm
        this[BodyMeasurements.bicepsCm] = input.bicepsCm
        this[BodyMeasurements.thighCm] = input.thighCm
        this[BodyMeasurements.calfCm] = input.calfCm
        this[BodyMeasurements.rightBicepsCm] = input.rightBicepsCm
        this[BodyMeasurements.leftBicepsCm] = input.leftBicepsCm
        this[BodyMeasurements.rightForearmCm] = input.rightForearmCm
        this[BodyMeasurements.leftForearmCm] = input.leftForearmCm
        this[BodyMeasurements.backWidthCm] = input.backWidthCm
        this[BodyMeasurements.rightThighCm] = input.rightThighCm
        this[BodyMeasurements.leftThighCm] = input.leftThighCm
        this[BodyMeasurements.rightCalfCm] = input.rightCalfCm
        this[BodyMeasurements.leftCalfCm] = input.leftCalfCm
        this[BodyMeasurements.note] = input.note?.trim()?.ifBlank { null }
    }

    private fun ownedBy(trainerId: Long) = Clients.select(Clients.id).where { Clients.trainerId eq trainerId }

    /** Замер подопечного этого тренера. Чужой и несуществующий неразличимы. */
    fun findOwned(trainerId: Long, id: Long, lock: Boolean = false): BodyMeasurement? =
        BodyMeasurements.selectAll()
            .where { (BodyMeasurements.id eq id) and (BodyMeasurements.clientId inSubQuery ownedBy(trainerId)) }
            .apply { if (lock) forUpdate() }
            .firstOrNull()
            ?.toBodyMeasurement()

    /** История замеров клиента, новые сверху. */
    fun listByClient(clientId: Long): List<BodyMeasurement> =
        BodyMeasurements.selectAll()
            .where { BodyMeasurements.clientId eq clientId }
            .orderBy(BodyMeasurements.measuredOn to SortOrder.DESC, BodyMeasurements.id to SortOrder.DESC)
            .map { it.toBodyMeasurement() }

    fun update(id: Long, input: MeasurementInput, version: Int): BodyMeasurement =
        BodyMeasurements.updateReturning(where = { BodyMeasurements.id eq id }) {
            it.setValues(input)
            it[BodyMeasurements.version] = version
        }.single().toBodyMeasurement()

    fun delete(id: Long): Boolean =
        BodyMeasurements.deleteWhere { BodyMeasurements.id eq id } > 0
}
