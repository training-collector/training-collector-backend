package com.tc.gymtrainer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogServiceTest : AbstractDbTest() {
    @Test
    fun `concurrent additions reuse one personal exercise and suggestions respect ownership`() = runBlocking {
        val owner = newTrainer(301).id
        val other = newTrainer(302).id
        assertTrue(Services.catalog.exercisesFor(owner).isEmpty())
        val items = (1..8).map {
            async { Services.catalog.addExercise(owner, name = "Жим с паузой") }
        }.awaitAll()
        assertEquals(1, items.toSet().size)
        assertEquals(listOf(items.first()), Services.catalog.suggest(owner, "пауз"))
        assertTrue(Services.catalog.suggest(other, "пауз").isEmpty())
    }

    @Test
    fun `legacy exercises with the same name keep their ids and have one choice in the flat catalog`() = runBlocking {
        val owner = newTrainer(303).id
        val groups = Services.catalog.listMuscleGroups()
        val original = Services.catalog.addExercise(owner, groups[0].id, "Тяга")
        val second = Services.catalog.addExercise(owner, groups[1].id, "тяга")
        assertTrue(original.id != second.id)
        assertEquals(listOf(second), Services.catalog.exercisesFor(owner, groups[1].id))
        assertEquals(original, Services.catalog.addExercise(owner, name = " ТЯГА "))
        assertEquals(listOf(original), Services.catalog.exercisesFor(owner))
        Services.catalog.updateExercise(owner, original.id, "Тяга блока", null)
        assertEquals(listOf(original.copy(name = "Тяга блока")), Services.catalog.exercisesFor(owner))
        assertEquals(listOf(second.copy(name = "Тяга блока")), Services.catalog.exercisesFor(owner, groups[1].id))
    }
}
