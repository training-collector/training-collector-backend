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

    suspend fun get(trainerId: Long, measurementId: Long): BodyMeasurement = dbQuery { requireOwned(trainerId, measurementId) }

    /** Последнее значение каждого показателя. История идёт от новых записей к старым. */
    suspend fun current(trainerId: Long, clientId: Long): Map<String, LatestMeasurement> {
        val result = LinkedHashMap<String, LatestMeasurement>()
        for (row in history(trainerId, clientId)) {
            for ((name, read) in METRICS) {
                val value = read(row) ?: continue
                result.putIfAbsent(name, LatestMeasurement(value, row.measuredOn))
            }
            if (result.size == METRICS.size) break
        }
        return result
    }

    /** [expectedVersion] отклоняет правку записи, которую уже изменили после её открытия. */
    suspend fun update(
        trainerId: Long, measurementId: Long, input: MeasurementInput, expectedVersion: Int? = null,
    ): BodyMeasurement = dbQuery {
        val existing = requireOwned(trainerId, measurementId, lock = true)
        if (expectedVersion != null && existing.version != expectedVersion) throw ConflictException("Замер уже изменён. Откройте его снова.")
        validateMeasurement(input)
        measurements.update(measurementId, input, existing.version + 1)
    }

    suspend fun delete(trainerId: Long, measurementId: Long) = dbQuery {
        requireOwned(trainerId, measurementId)
        measurements.delete(measurementId)
    }

    private fun requireOwned(trainerId: Long, measurementId: Long, lock: Boolean = false): BodyMeasurement =
        measurements.findOwned(trainerId, measurementId, lock) ?: throw NotFoundException("Замер не найден.")

    private fun requireOwnedClient(trainerId: Long, clientId: Long) {
        clients.findOwned(trainerId, clientId) ?: throw NotFoundException("Подопечный не найден.")
    }
}

private val METRICS: List<Pair<String, (BodyMeasurement) -> BigDecimal?>> = listOf(
    "heightCm" to { it.heightCm }, "weightKg" to { it.weightKg }, "bodyFatPct" to { it.bodyFatPct },
    "bicepsCm" to { it.bicepsCm }, "waistCm" to { it.waistCm }, "chestCm" to { it.chestCm },
    "hipsCm" to { it.hipsCm }, "thighCm" to { it.thighCm }, "calfCm" to { it.calfCm },
    "rightBicepsCm" to { it.rightBicepsCm }, "leftBicepsCm" to { it.leftBicepsCm },
    "rightForearmCm" to { it.rightForearmCm }, "leftForearmCm" to { it.leftForearmCm },
    "backWidthCm" to { it.backWidthCm }, "rightThighCm" to { it.rightThighCm }, "leftThighCm" to { it.leftThighCm },
    "rightCalfCm" to { it.rightCalfCm }, "leftCalfCm" to { it.leftCalfCm },
)

/** Границы дат, которые принимают бот и API: за ними база отвечает ошибкой. */
internal val EARLIEST_DATE: LocalDate = LocalDate.of(1900, 1, 1)
internal val LATEST_DATE: LocalDate = LocalDate.of(2100, 12, 31)

fun validateMeasurement(input: MeasurementInput) {
    if (input.measuredOn !in EARLIEST_DATE..LATEST_DATE) throw ValidationException("Дата должна быть между 1900 и 2100 годом.")
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
