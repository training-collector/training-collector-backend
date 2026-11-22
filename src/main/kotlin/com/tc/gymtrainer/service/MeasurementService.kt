package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.BodyMeasurement
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.MeasurementRepository
import java.math.BigDecimal
import java.time.LocalDate

typealias MeasurementInput = com.tc.gymtrainer.domain.MeasurementInput

data class LatestMeasurement(val value: BigDecimal, val measuredOn: LocalDate)

/** Use-cases по замерам тела подопечного. */
class MeasurementService(
    private val measurements: MeasurementRepository,
    private val clients: ClientRepository,
) {

    suspend fun add(trainerId: Long, clientId: Long, input: MeasurementInput): BodyMeasurement = dbQuery {
        requireOwnedClient(trainerId, clientId)
        validateMeasurement(input)
        measurements.insert(clientId, input)
    }

    suspend fun history(trainerId: Long, clientId: Long): List<BodyMeasurement> = dbQuery {
        requireOwnedClient(trainerId, clientId)
        measurements.listByClient(clientId)
    }

    suspend fun get(trainerId: Long, measurementId: Long): BodyMeasurement = dbQuery {
        val measurement = measurements.findById(measurementId) ?: throw NotFoundException("Замер не найден.")
        requireOwnedClient(trainerId, measurement.clientId)
        measurement
    }

    suspend fun current(trainerId: Long, clientId: Long): Map<String, LatestMeasurement> {
        val result = mutableMapOf<String, LatestMeasurement>()
        for (row in history(trainerId, clientId)) {
            fun collect(name: String, value: BigDecimal?) {
                if (value != null) result.putIfAbsent(name, LatestMeasurement(value, row.measuredOn))
            }
            collect("heightCm", row.heightCm)
            collect("weightKg", row.weightKg)
            collect("bodyFatPct", row.bodyFatPct)
            collect("bicepsCm", row.bicepsCm)
            collect("waistCm", row.waistCm)
            collect("chestCm", row.chestCm)
            collect("hipsCm", row.hipsCm)
            collect("thighCm", row.thighCm)
            collect("calfCm", row.calfCm)
            collect("rightBicepsCm", row.rightBicepsCm)
            collect("leftBicepsCm", row.leftBicepsCm)
            collect("rightForearmCm", row.rightForearmCm)
            collect("leftForearmCm", row.leftForearmCm)
            collect("backWidthCm", row.backWidthCm)
            collect("rightThighCm", row.rightThighCm)
            collect("leftThighCm", row.leftThighCm)
            collect("rightCalfCm", row.rightCalfCm)
            collect("leftCalfCm", row.leftCalfCm)
        }
        return result
    }

    suspend fun update(
        trainerId: Long, measurementId: Long, input: MeasurementInput, expected: BodyMeasurement? = null,
    ): BodyMeasurement = dbQuery {
        val existing = measurements.findById(measurementId, lock = true) ?: throw NotFoundException("Замер не найден.")
        requireOwnedClient(trainerId, existing.clientId)
        if (expected != null && existing != expected) throw ConflictException("Замер уже изменён. Откройте его снова.")
        validateMeasurement(input)
        measurements.update(measurementId, input)
        measurements.findById(measurementId) ?: throw NotFoundException("Замер не найден.")
    }

    suspend fun delete(trainerId: Long, measurementId: Long) = dbQuery {
        val existing = measurements.findById(measurementId) ?: throw NotFoundException("Замер не найден.")
        requireOwnedClient(trainerId, existing.clientId)
        measurements.delete(measurementId)
    }

    private fun requireOwnedClient(trainerId: Long, clientId: Long) {
        val client = clients.findById(clientId) ?: throw NotFoundException("Подопечный не найден.")
        if (client.trainerId != trainerId) throw AccessDeniedException("Этот подопечный принадлежит другому тренеру.")
    }
}

fun validateMeasurement(input: MeasurementInput) {
    val lengths = listOf(
        input.heightCm, input.chestCm, input.waistCm, input.hipsCm, input.bicepsCm, input.thighCm, input.calfCm,
        input.rightBicepsCm, input.leftBicepsCm, input.rightForearmCm, input.leftForearmCm,
        input.rightThighCm, input.leftThighCm, input.rightCalfCm, input.leftCalfCm,
        input.backWidthCm,
    ).filterNotNull()
    val hasAnyValue = lengths.isNotEmpty() || input.weightKg != null || input.bodyFatPct != null || !input.note.isNullOrBlank()
    if (!hasAnyValue) throw ValidationException("Замер пустой: укажите хотя бы один показатель.")
    input.weightKg?.let {
        if (it <= BigDecimal.ZERO || !it.fitsDecimal(5, 2)) throw ValidationException("Недопустимый вес (кг).")
    }
    if (lengths.any { it <= BigDecimal.ZERO || !it.fitsDecimal(5, 1) }) {
        throw ValidationException("Рост и обхваты должны быть положительными, с точностью до 0,1 см.")
    }
    input.bodyFatPct?.let {
        if (it < BigDecimal.ZERO || it > BigDecimal("100") || !it.fitsDecimal(4, 1)) throw ValidationException("% жира должен быть в диапазоне 0–100.")
    }
}
