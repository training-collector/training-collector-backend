package com.tc.gymtrainer

import com.tc.gymtrainer.service.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogServiceTest : AbstractDbTest() {
    @Test
    fun `hidden and deleted entries cannot be selected and assigned goals retain their history`() = runBlocking<Unit> {
        val catalog = Services.catalog
        assertTrue(catalog.goals().isEmpty())
        assertTrue(catalog.exercises().isEmpty())
        val goal = catalog.saveGoal(null, CatalogEdit("Баланс"))
        val trainer = newTrainer(188)
        val client = Services.client.create(trainer.id, "Саша", null, goal.name, null)
        catalog.saveGoal(goal.id, CatalogEdit("Координация", hidden = true))
        assertTrue(catalog.goals().isEmpty())
        assertEquals("Координация", Services.client.getOwned(trainer.id, client.id).goal)
        assertThrows(ValidationException::class.java) { runBlocking { Services.client.create(trainer.id, "Борис", null, "Координация", null) } }
        catalog.deleteGoal(goal.id)
        assertEquals("Координация", Services.client.getOwned(trainer.id, client.id).goal)
        val exercise = catalog.saveExercise(null, CatalogEdit("Выпады"))
        assertThrows(ConflictException::class.java) { runBlocking { catalog.saveExercise(null, CatalogEdit("ВЫПАДЫ")) } }
        catalog.saveExercise(exercise.id, CatalogEdit("Выпады", hidden = true))
        assertThrows(NotFoundException::class.java) { runBlocking { catalog.getExercise(exercise.id) } }
        catalog.saveExercise(exercise.id, CatalogEdit("Выпады"))
        assertEquals(exercise, catalog.getExercise(exercise.id))
        catalog.deleteExercise(exercise.id)
        assertTrue(catalog.exercises(includeHidden = true).isEmpty())
    }
}
