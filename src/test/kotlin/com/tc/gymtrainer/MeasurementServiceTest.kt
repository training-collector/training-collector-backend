package com.tc.gymtrainer

import com.tc.gymtrainer.service.NotFoundException
import com.tc.gymtrainer.service.ConflictException
import com.tc.gymtrainer.service.MeasurementInput
import com.tc.gymtrainer.service.ValidationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MeasurementServiceTest : AbstractDbTest() {

    @Test
    fun `add and list measurements newest first`() = runBlocking {
        val trainer = newTrainer(3001)
        val client = newClient(trainer)
        Services.measurement.add(trainer.id, client.id, MeasurementInput(LocalDate.of(2026, 1, 1), weightKg = BigDecimal("82.5")))
        Services.measurement.add(trainer.id, client.id, MeasurementInput(LocalDate.of(2026, 3, 1), weightKg = BigDecimal("80.0")))

        val history = Services.measurement.history(trainer.id, client.id)
        assertEquals(2, history.size)
        assertEquals(LocalDate.of(2026, 3, 1), history[0].measuredOn)
        assertEquals(BigDecimal("80.00"), history[0].weightKg)
    }

    @Test
    fun `update and delete measurement`() = runBlocking {
        val trainer = newTrainer(3002)
        val client = newClient(trainer)
        val m = Services.measurement.add(trainer.id, client.id, MeasurementInput(LocalDate.of(2026, 1, 1), weightKg = BigDecimal("82")))
        val updated = Services.measurement.update(
            trainer.id, m.id,
            MeasurementInput(LocalDate.of(2026, 1, 1), weightKg = BigDecimal("81"), waistCm = BigDecimal("80")),
        )
        assertEquals(BigDecimal("81.00"), updated.weightKg)
        assertEquals(BigDecimal("80.0"), updated.waistCm)

        assertThrows(ConflictException::class.java) {
            runBlocking { Services.measurement.update(trainer.id, m.id, MeasurementInput(m.measuredOn, weightKg = BigDecimal("83")), expectedVersion = m.version) }
        }
        assertEquals(updated, Services.measurement.history(trainer.id, client.id).single())
        assertEquals(BigDecimal("83.00"), Services.measurement.update(trainer.id, m.id,
            MeasurementInput(m.measuredOn, weightKg = BigDecimal("83")), expectedVersion = updated.version).weightKg)
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.measurement.add(trainer.id, client.id, MeasurementInput(LocalDate.of(999_999, 1, 1), weightKg = BigDecimal("80"))) }
        }
        Services.measurement.delete(trainer.id, m.id)
        assertTrue(Services.measurement.history(trainer.id, client.id).isEmpty())
    }

    @Test
    fun `empty measurement is rejected`() = runBlocking {
        val trainer = newTrainer(3003)
        val client = newClient(trainer)
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.measurement.add(trainer.id, client.id, MeasurementInput(LocalDate.now())) }
        }
        Unit
    }

    @Test
    fun `another trainer cannot add measurement`() = runBlocking {
        val owner = newTrainer(3004)
        val other = newTrainer(3005)
        val client = newClient(owner)
        assertThrows(NotFoundException::class.java) {
            runBlocking {
                Services.measurement.add(other.id, client.id, MeasurementInput(LocalDate.now(), weightKg = BigDecimal("70")))
            }
        }
        Unit
    }
}
