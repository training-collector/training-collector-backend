package com.tc.gymtrainer

import com.tc.gymtrainer.domain.ExerciseDraft
import com.tc.gymtrainer.service.ExerciseEdit
import com.tc.gymtrainer.domain.SetDraft
import com.tc.gymtrainer.domain.WorkoutDraft
import com.tc.gymtrainer.domain.WorkoutStage
import com.tc.gymtrainer.service.ConflictException
import com.tc.gymtrainer.service.NotFoundException
import com.tc.gymtrainer.service.ValidationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class WorkoutServiceTest : AbstractDbTest() {

    @Test
    fun `cancelling only removes the matching active workout of the owned client`() = runBlocking {
        val trainer = newTrainer(2050)
        val otherTrainer = newTrainer(2051)
        val client = newClient(trainer)
        val otherClient = newClient(trainer, "Второй подопечный")
        val workouts = Services.workout
        val finished = workouts.startOrResume(trainer.id, client.id).id
        workouts.finishWorkout(trainer.id, finished)
        val active = workouts.startOrResume(trainer.id, client.id).id

        assertThrows(NotFoundException::class.java) {
            runBlocking { workouts.cancelActiveWorkout(otherTrainer.id, client.id, active) }
        }
        assertFalse(workouts.cancelActiveWorkout(trainer.id, otherClient.id, active))
        assertFalse(workouts.cancelActiveWorkout(trainer.id, client.id, finished))
        assertEquals(1, (1..2).map { async { workouts.cancelActiveWorkout(trainer.id, client.id, active) } }.awaitAll().count { it })
        val next = workouts.startOrResume(trainer.id, client.id)
        assertFalse(workouts.cancelActiveWorkout(trainer.id, client.id, active))
        assertEquals(next.id, workouts.activeForClient(trainer.id, client.id)?.id)
        assertEquals(listOf(finished), workouts.history(trainer.id, client.id).map { it.id })
    }

    @Test
    fun `first finish saves positive minutes once even when retried concurrently`() = runBlocking {
        val trainer = newTrainer(2052)
        val client = newClient(trainer)
        val workouts = Services.workout
        val session = workouts.startOrResume(trainer.id, client.id)
        for (invalid in listOf(-1, 0)) {
            assertThrows(ValidationException::class.java) {
                runBlocking { workouts.finishWorkout(trainer.id, session.id, invalid) }
            }
        }
        val active = workouts.activeForClient(trainer.id, client.id)
        assertEquals(session.id, active?.id)
        assertNull(active?.durationMin)
        val minutes = listOf(45, 50).map { duration ->
            async { workouts.finishWorkout(trainer.id, session.id, duration).session.durationMin }
        }.awaitAll().distinct().single()
        assertTrue(minutes == 45 || minutes == 50)
        assertEquals(minutes, workouts.finishWorkout(trainer.id, session.id).session.durationMin)
        assertEquals(minutes, workouts.history(trainer.id, client.id).single().durationMin)
    }

    @Test
    fun `starting concurrently opens one session per client and a late finish never closes the next one`() = runBlocking {
        val trainer = newTrainer(2010)
        val client = newClient(trainer)
        val otherClient = newClient(trainer, "Второй клиент")
        val otherTrainer = newTrainer(2011)
        val workouts = Services.workout
        val started = (1..6).map { async { workouts.startOrResume(trainer.id, client.id).id } }.awaitAll()
        val first = started.distinct().single()
        assertEquals(WorkoutStage.CHOOSE_EXERCISE, workouts.getDetail(trainer.id, first).session.stage)
        val independent = workouts.startOrResume(trainer.id, otherClient.id)
        assertTrue(first != independent.id)
        assertThrows(NotFoundException::class.java) { runBlocking { workouts.finishWorkout(otherTrainer.id, first) } }
        assertThrows(NotFoundException::class.java) { runBlocking { workouts.startOrResume(otherTrainer.id, client.id) } }
        val catalogItem = Services.catalog.saveExercise(null, ExerciseEdit("Отжимания", Services.catalog.listMuscleGroups().single().id))
        workouts.selectExercise(trainer.id, first, catalogItem.id)
        val ex = workouts.getDetail(trainer.id, first).exercises.single().exercise.id
        assertThrows(ValidationException::class.java) {
            runBlocking { workouts.appendActiveSets(trainer.id, first, ex, listOf(SetDraft(reps = 10), SetDraft(reps = 0))) }
        }
        assertTrue(workouts.getDetail(trainer.id, first).exercises.single().sets.isEmpty())
        assertThrows(ConflictException::class.java) { runBlocking { workouts.deleteExercise(trainer.id, ex) } }
        // Finishing from the profile before recording any sets must also free the client for a new session.
        assertTrue(workouts.finishWorkout(trainer.id, first).exercises.isEmpty())
        assertEquals(listOf(first), workouts.history(trainer.id, client.id).map { it.id })
        val next = workouts.startOrResume(trainer.id, client.id)
        assertTrue(next.id != first)
        workouts.finishWorkout(trainer.id, first)
        assertThrows(ConflictException::class.java) {
            runBlocking { workouts.appendActiveSets(trainer.id, first, ex, listOf(SetDraft(reps = 10))) }
        }
        assertEquals(next.id, workouts.activeForClient(trainer.id, client.id)?.id)
        Services.client.delete(trainer.id, client.id)
        assertEquals(independent.id, workouts.activeForClient(trainer.id, otherClient.id)?.id)
    }

    @org.junit.jupiter.api.BeforeEach
    fun publishExercises() {
        runBlocking {
            val group = Services.catalog.createMuscleGroup("Силовые")
            listOf("Жим штанги лёжа", "Подтягивания", "Гакк-присед (новое)", "Приседания со штангой").forEach {
                Services.catalog.saveExercise(null, ExerciseEdit(it, group.id))
            }
        }
    }

    private fun at(year: Int, month: Int, day: Int): OffsetDateTime =
        OffsetDateTime.of(year, month, day, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `create workout stores exercises, ordered sets from the shared catalog`() = runBlocking {
        val trainer = newTrainer(2001)
        val client = newClient(trainer)
        val draft = WorkoutDraft(
            clientId = client.id,
            performedAt = OffsetDateTime.now(),
            durationMin = 60,
            note = "лёгкая",
            exercises = listOf(
                ExerciseDraft(
                    null, "Жим штанги лёжа", null,
                    listOf(SetDraft(BigDecimal("60"), 10), SetDraft(BigDecimal("60"), 8)),
                ),
                ExerciseDraft(
                    null, "Гакк-присед (новое)", null,
                    listOf(SetDraft(null, 12)),
                ),
            ),
        )

        val detail = Services.workout.create(trainer.id, draft)

        assertEquals(2, detail.exercises.size)
        assertEquals(listOf(0, 1), detail.exercises.map { it.exercise.orderIndex })
        assertEquals(listOf(1, 2), detail.exercises[0].sets.map { it.setNumber })
        assertNull(detail.exercises[1].sets[0].weightKg) // вес тела
        assertNotNull(Services.catalog.exercises().firstOrNull { it.name.startsWith("Гакк-присед") })
    }

    @Test
    fun `history filters by date range and by exercise name`() = runBlocking {
        val trainer = newTrainer(2004)
        val client = newClient(trainer)
        Services.workout.create(
            trainer.id,
            WorkoutDraft(
                client.id, at(2026, 1, 15), null, null,
                listOf(ExerciseDraft(null, "Жим штанги лёжа", null, listOf(SetDraft(BigDecimal("50"), 10)))),
            ),
        )
        Services.workout.create(
            trainer.id,
            WorkoutDraft(
                client.id, at(2026, 6, 20), null, null,
                listOf(ExerciseDraft(null, "Приседания со штангой", null, listOf(SetDraft(BigDecimal("100"), 5)))),
            ),
        )

        val all = Services.workout.history(trainer.id, client.id)
        assertEquals(2, all.size)
        assertTrue(all[0].performedAt.isAfter(all[1].performedAt)) // новые сверху

        val jan = Services.workout.history(trainer.id, client.id, from = at(2026, 1, 1), to = at(2026, 2, 1))
        assertEquals(1, jan.size)
        assertEquals(at(2026, 1, 15), jan[0].performedAt.withOffsetSameInstant(ZoneOffset.UTC))

        val byExercise = Services.workout.history(trainer.id, client.id, exerciseQuery = "присед")
        assertEquals(1, byExercise.size)
        assertEquals(at(2026, 6, 20), byExercise[0].performedAt.withOffsetSameInstant(ZoneOffset.UTC))
    }

    @Test
    fun `update and delete set, exercise and session`() = runBlocking {
        val trainer = newTrainer(2005)
        val client = newClient(trainer)
        val detail = Services.workout.create(
            trainer.id,
            WorkoutDraft(
                client.id, OffsetDateTime.now(), null, null,
                listOf(ExerciseDraft(null, "Жим штанги лёжа", null, listOf(SetDraft(BigDecimal("60"), 10)))),
            ),
        )
        val setId = detail.exercises[0].sets[0].id
        val sessionId = detail.session.id

        Services.workout.updateSet(trainer.id, setId, SetDraft(BigDecimal("62.5"), 8, restSec = 90, rpe = BigDecimal("8")))
        val afterUpdate = Services.workout.getDetail(trainer.id, sessionId).exercises[0].sets[0]
        assertEquals(0, BigDecimal("62.5").compareTo(afterUpdate.weightKg))
        assertEquals(8, afterUpdate.reps)
        assertEquals(90, afterUpdate.restSec)

        Services.workout.deleteSet(trainer.id, setId)
        assertTrue(Services.workout.getDetail(trainer.id, sessionId).exercises[0].sets.isEmpty())

        Services.workout.deleteSession(trainer.id, sessionId)
        assertTrue(Services.workout.history(trainer.id, client.id).isEmpty())
    }

    @Test
    fun `deleting a set keeps the numbering of the rest contiguous`() = runBlocking {
        val trainer = newTrainer(2012)
        val client = newClient(trainer)
        val sets = List(4) { SetDraft(BigDecimal("40"), 10 + it) }
        val detail = Services.workout.create(trainer.id, WorkoutDraft(client.id, OffsetDateTime.now(), null, null,
            listOf(ExerciseDraft(null, "Жим штанги лёжа", null, sets))))
        val exercise = detail.exercises.single()
        assertEquals(listOf(1, 2, 3, 4), exercise.sets.map { it.setNumber })
        Services.workout.deleteSet(trainer.id, exercise.sets[1].id)
        val left = Services.workout.getDetail(trainer.id, detail.session.id).exercises.single().sets
        assertEquals(listOf(1, 2, 3), left.map { it.setNumber })
        assertEquals(listOf(10, 12, 13), left.map { it.reps })
        assertEquals(4, Services.workout.addSet(trainer.id, exercise.exercise.id, SetDraft(reps = 5)).setNumber)
        assertThrows(ValidationException::class.java) {
            runBlocking { Services.workout.updateSession(trainer.id, detail.session.id, OffsetDateTime.parse("0001-01-01T00:00:00Z"), null, null) }
        }
        Unit
    }

    @Test
    fun `deleting session cascades to exercises and sets`() = runBlocking {
        val trainer = newTrainer(2006)
        val client = newClient(trainer)
        val detail = Services.workout.create(
            trainer.id,
            WorkoutDraft(
                client.id, OffsetDateTime.now(), null, null,
                listOf(ExerciseDraft(null, "Жим штанги лёжа", null, listOf(SetDraft(BigDecimal("60"), 10)))),
            ),
        )
        Services.workout.deleteSession(trainer.id, detail.session.id)
        // getDetail чужого/удалённого — NotFound через сервис; проверяем через историю
        assertTrue(Services.workout.history(trainer.id, client.id).isEmpty())
    }

    @Test
    fun `rejects non-positive reps and rolls back`() = runBlocking {
        val trainer = newTrainer(2007)
        val client = newClient(trainer)
        assertThrows(ValidationException::class.java) {
            runBlocking {
                Services.workout.create(
                    trainer.id,
                    WorkoutDraft(
                        client.id, OffsetDateTime.now(), null, null,
                        listOf(ExerciseDraft(null, "Жим штанги лёжа", null, listOf(SetDraft(BigDecimal("60"), 0)))),
                    ),
                )
            }
        }
        assertTrue(Services.workout.history(trainer.id, client.id).isEmpty())
    }

    @Test
    fun `another trainer cannot read the workout`() = runBlocking {
        val owner = newTrainer(2008)
        val other = newTrainer(2009)
        val client = newClient(owner)
        val detail = Services.workout.create(
            owner.id,
            WorkoutDraft(
                client.id, OffsetDateTime.now(), null, null,
                listOf(ExerciseDraft(null, "Жим штанги лёжа", null, listOf(SetDraft(BigDecimal("60"), 10)))),
            ),
        )
        assertThrows(NotFoundException::class.java) {
            runBlocking { Services.workout.getDetail(other.id, detail.session.id) }
        }
        Unit
    }
}
