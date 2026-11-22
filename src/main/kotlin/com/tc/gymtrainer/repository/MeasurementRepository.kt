package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.BodyMeasurements
import com.tc.gymtrainer.domain.BodyMeasurement
import com.tc.gymtrainer.domain.MeasurementInput
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/** Доступ к замерам тела подопечных. Вызывать в транзакции. */
class MeasurementRepository {

    fun insert(clientId: Long, input: MeasurementInput): BodyMeasurement {
        val id = BodyMeasurements.insertAndGetId {
            it[BodyMeasurements.clientId] = clientId
            it[createdAt] = OffsetDateTime.now()
            it.setValues(input)
        }.value
        return checkNotNull(findById(id))
    }

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

    fun findById(id: Long, lock: Boolean = false): BodyMeasurement? =
        BodyMeasurements.selectAll()
            .where { BodyMeasurements.id eq id }
            .apply { if (lock) forUpdate() }
            .firstOrNull()
            ?.toBodyMeasurement()

    /** История замеров клиента, новые сверху. */
    fun listByClient(clientId: Long): List<BodyMeasurement> =
        BodyMeasurements.selectAll()
            .where { BodyMeasurements.clientId eq clientId }
            .orderBy(BodyMeasurements.measuredOn to SortOrder.DESC, BodyMeasurements.id to SortOrder.DESC)
            .map { it.toBodyMeasurement() }

    fun update(id: Long, input: MeasurementInput): Boolean =
        BodyMeasurements.update({ BodyMeasurements.id eq id }) { it.setValues(input) } > 0

    fun delete(id: Long): Boolean =
        BodyMeasurements.deleteWhere { with(it) { BodyMeasurements.id eq id } } > 0
}
