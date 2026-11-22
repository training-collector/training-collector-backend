package com.tc.gymtrainer

import com.tc.gymtrainer.domain.ClientStatus
import com.tc.gymtrainer.domain.ClientProfile
import com.tc.gymtrainer.domain.Goal
import com.tc.gymtrainer.service.AccessDeniedException
import com.tc.gymtrainer.service.ValidationException
import com.tc.gymtrainer.service.MeasurementInput
import com.tc.gymtrainer.db.Clients
import com.tc.gymtrainer.db.dbQuery
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientServiceTest : AbstractDbTest() {

    @Test
    fun `create and list only active by default`() = runBlocking {
        val trainer = newTrainer(1001)
        Services.client.create(trainer.id, "Аня", phone = "+7900", goal = Goal.WEIGHT_LOSS, notes = "колено")
        val boris = Services.client.create(trainer.id, "Борис", phone = null, goal = null, notes = null)
        Services.client.archive(trainer.id, boris.id)

        val active = Services.client.list(trainer.id)
        assertEquals(listOf("Аня"), active.map { it.fullName })

        val all = Services.client.list(trainer.id, includeArchived = true)
        assertEquals(setOf("Аня", "Борис"), all.map { it.fullName }.toSet())
    }

    @Test
    fun `update changes fields`() = runBlocking {
        val trainer = newTrainer(1002)
        val c = newClient(trainer, "Витя")
        val updated = Services.client.update(trainer.id, c.id, "Виктор", "+7", Goal.MASS_GAIN, "цель — масса")
        assertEquals("Виктор", updated.fullName)
        assertEquals(Goal.MASS_GAIN, updated.goal)
        assertEquals("+7", updated.phone)
    }

    @Test
    fun `archive and unarchive toggle status`() = runBlocking {
        val trainer = newTrainer(1003)
        val c = newClient(trainer)
        assertEquals(ClientStatus.ARCHIVED, Services.client.archive(trainer.id, c.id).status)
        assertEquals(ClientStatus.ACTIVE, Services.client.unarchive(trainer.id, c.id).status)
    }

    @Test
    fun `delete removes client and its history`() = runBlocking {
        val trainer = newTrainer(1004)
        val c = newClient(trainer)
        Services.measurement.add(
            trainer.id, c.id,
            com.tc.gymtrainer.service.MeasurementInput(measuredOn = java.time.LocalDate.now(), weightKg = java.math.BigDecimal("80")),
        )
        Services.client.delete(trainer.id, c.id)
        assertTrue(Services.client.list(trainer.id, includeArchived = true).isEmpty())
    }

    @Test
    fun `cannot touch another trainers client`() = runBlocking {
        val owner = newTrainer(1005)
        val other = newTrainer(1006)
        val c = newClient(owner)
        assertThrows(AccessDeniedException::class.java) {
            runBlocking { Services.client.getOwned(other.id, c.id) }
        }
        Unit
    }

    @Test
    fun `blank name is rejected`() = runBlocking {
        val trainer = newTrainer(1007)
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.client.create(trainer.id, "   ", null, null, null) }
        }
        assertFalse(Services.client.list(trainer.id).any { it.fullName.isBlank() })
    }

    @Test
    fun `a profile and its first measurement are saved together and retired goals can only be retained`() = runBlocking {
        val trainer = newTrainer(1008)
        val profile = ClientProfile("Имя", "Фамилия")
        val invalid = MeasurementInput(LocalDate.now(), weightKg = BigDecimal.ZERO)
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.client.create(trainer.id, profile, invalid) }
        }
        assertTrue(Services.client.list(trainer.id).isEmpty())
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.client.create(trainer.id, profile.copy(goal = Goal.REHAB)) }
        }
        val client = Services.client.create(trainer.id, profile)
        dbQuery { Clients.update({ Clients.id eq client.id }) { it[goal] = Goal.REHAB } }
        assertEquals(Goal.REHAB, Services.client.updateProfile(trainer.id, client.id) { it.copy(notes = "Заметка") }.goal)
        Services.client.updateProfile(trainer.id, client.id) { it.copy(goal = Goal.WEIGHT_LOSS) }
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.client.updateProfile(trainer.id, client.id) { it.copy(goal = Goal.REHAB) } }
        }
        Unit
    }
}
