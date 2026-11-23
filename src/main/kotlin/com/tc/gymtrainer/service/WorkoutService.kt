package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.ExerciseDraft
import com.tc.gymtrainer.domain.ExerciseSet
import com.tc.gymtrainer.domain.SetDraft
import com.tc.gymtrainer.domain.WorkoutDetail
import com.tc.gymtrainer.domain.WorkoutDraft
import com.tc.gymtrainer.domain.WorkoutExercise
import com.tc.gymtrainer.domain.WorkoutSession
import com.tc.gymtrainer.domain.WorkoutStage
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.WorkoutRepository
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Ядро журнала тренировок: создание, пошаговая запись из бота, чтение, история
 * с фильтрами и полный CRUD над тренировкой/подходами.
 */
class WorkoutService(
    private val workouts: WorkoutRepository,
    private val clients: ClientRepository,
    private val catalog: CatalogService,
) {

    // --- Создание ------------------------------------------------------------

    /**
     * Создаёт проведённую тренировку из черновика. Упражнения берутся из общего
     * справочника, название денормализуется в записи.
     */
    suspend fun create(trainerId: Long, draft: WorkoutDraft): WorkoutDetail = dbQuery {
        requireOwnedClient(trainerId, draft.clientId)
        validateSession(draft.performedAt, draft.durationMin)
        val session = workouts.insertSession(
            clientId = draft.clientId,
            trainerId = trainerId,
            performedAt = draft.performedAt,
            durationMin = draft.durationMin,
            note = draft.note?.trim()?.ifBlank { null },
        )
        draft.exercises.forEachIndexed { index, exercise -> insertExercise(session, exercise, index) }
        // Read back, so the answer carries the timestamps exactly as later reads will show them.
        workouts.getDetail(requireSessionOwned(trainerId, session.id))
    }

    private fun insertExercise(session: WorkoutSession, draft: ExerciseDraft, orderIndex: Int): WorkoutExercise {
        draft.sets.forEach(::validateSet)
        val item = catalog.resolveExercise(draft.exerciseCatalogId, draft.exerciseName)
        val exercise = workouts.insertExercise(session.id, item.id, item.name, orderIndex, draft.note?.trim()?.ifBlank { null })
        if (draft.sets.isNotEmpty()) workouts.insertSets(exercise.id, 0, draft.sets)
        return exercise
    }

    suspend fun activeForClient(trainerId: Long, clientId: Long): WorkoutSession? = dbQuery {
        requireOwnedClient(trainerId, clientId)
        workouts.activeForClient(clientId)
    }

    suspend fun startOrResume(trainerId: Long, clientId: Long): WorkoutSession = dbQuery {
        requireOwnedClient(trainerId, clientId, lock = true)
        workouts.activeForClient(clientId)?.let { return@dbQuery it }
        workouts.insertSession(clientId, trainerId, OffsetDateTime.now(), null, null, stage = WorkoutStage.CHOOSE_EXERCISE)
    }

    suspend fun selectExercise(trainerId: Long, sessionId: Long, catalogId: Long) =
        advance(trainerId, sessionId, WorkoutStage.CHOOSE_EXERCISE) { session ->
            insertExercise(session, ExerciseDraft(catalogId, null), (workouts.lastOrderIndex(session.id) ?: -1) + 1)
            WorkoutStage.ENTER_SETS
        }

    suspend fun appendActiveSets(trainerId: Long, sessionId: Long, exerciseId: Long, sets: List<SetDraft>) =
        advance(trainerId, sessionId, WorkoutStage.ENTER_SETS) {
            requireCurrentExercise(sessionId, exerciseId)
            sets.forEach(::validateSet)
            if (sets.isNotEmpty()) workouts.insertSets(exerciseId, workouts.lastSetNumber(exerciseId), sets)
            WorkoutStage.ENTER_SETS
        }

    suspend fun undoActiveSet(trainerId: Long, sessionId: Long, exerciseId: Long) =
        advance(trainerId, sessionId, WorkoutStage.ENTER_SETS) {
            requireCurrentExercise(sessionId, exerciseId)
            workouts.lastSet(exerciseId)?.let { workouts.deleteSet(it) }
            WorkoutStage.ENTER_SETS
        }

    suspend fun finishExercise(trainerId: Long, sessionId: Long, exerciseId: Long) =
        advance(trainerId, sessionId, WorkoutStage.ENTER_SETS) {
            requireCurrentExercise(sessionId, exerciseId)
            if (workouts.lastSetNumber(exerciseId) == 0) throw ValidationException("Введите хотя бы один подход.")
            WorkoutStage.REVIEW
        }

    suspend fun chooseAnotherExercise(trainerId: Long, sessionId: Long) =
        advance(trainerId, sessionId, WorkoutStage.REVIEW) { WorkoutStage.CHOOSE_EXERCISE }

    suspend fun cancelActiveWorkout(trainerId: Long, clientId: Long, sessionId: Long): Boolean = dbQuery {
        requireOwnedClient(trainerId, clientId, lock = true)
        workouts.deleteActiveSession(clientId, sessionId)
    }

    suspend fun finishWorkout(trainerId: Long, sessionId: Long, durationMin: Int? = null): WorkoutDetail = dbQuery {
        val session = requireSessionOwned(trainerId, sessionId, lock = true)
        if (durationMin != null && durationMin <= 0) throw ValidationException("Введите положительное целое число минут.")
        if (session.stage == WorkoutStage.COMPLETED) return@dbQuery workouts.getDetail(session)
        if (session.stage == WorkoutStage.ENTER_SETS) {
            // An exercise that was chosen but got no sets is not part of the finished workout.
            workouts.lastExercise(sessionId)?.let { if (workouts.lastSetNumber(it.id) == 0) workouts.deleteExercise(it.id) }
        }
        workouts.setStage(sessionId, WorkoutStage.COMPLETED, durationMin)
        workouts.getDetail(requireSessionOwned(trainerId, sessionId))
    }

    private fun requireCurrentExercise(sessionId: Long, exerciseId: Long) {
        if (workouts.lastExercise(sessionId)?.id != exerciseId)
            throw ConflictException("Уже выбрано другое упражнение. Откройте тренировку из карточки.")
    }

    private suspend fun advance(trainerId: Long, sessionId: Long, expected: WorkoutStage, next: (WorkoutSession) -> WorkoutStage) = dbQuery {
        val session = requireSessionOwned(trainerId, sessionId, lock = true)
        if (session.stage != expected) throw ConflictException("Состояние тренировки изменилось. Откройте карточку подопечного.")
        workouts.setStage(sessionId, next(session))
    }

    // --- Чтение --------------------------------------------------------------

    suspend fun getDetail(trainerId: Long, sessionId: Long): WorkoutDetail = dbQuery {
        workouts.getDetail(requireSessionOwned(trainerId, sessionId))
    }

    /** История тренировок клиента с фильтрами по дате и по названию упражнения. */
    suspend fun history(
        trainerId: Long,
        clientId: Long,
        from: OffsetDateTime? = null,
        to: OffsetDateTime? = null,
        exerciseQuery: String? = null,
    ): List<WorkoutSession> = dbQuery {
        requireOwnedClient(trainerId, clientId)
        workouts.listSessions(clientId, from, to, exerciseQuery)
    }

    // --- Изменение / удаление ------------------------------------------------

    suspend fun updateSession(
        trainerId: Long,
        sessionId: Long,
        performedAt: OffsetDateTime,
        durationMin: Int?,
        note: String?,
    ): WorkoutDetail = dbQuery {
        completedForEdit(trainerId, sessionId)
        validateSession(performedAt, durationMin)
        workouts.updateSession(sessionId, performedAt, durationMin, note?.trim()?.ifBlank { null })
        workouts.getDetail(requireSessionOwned(trainerId, sessionId))
    }

    suspend fun deleteSession(trainerId: Long, sessionId: Long) = dbQuery {
        completedForEdit(trainerId, sessionId)
        workouts.deleteSession(sessionId)
    }

    /** Добавляет упражнение в существующую тренировку (в конец списка). */
    suspend fun addExercise(trainerId: Long, sessionId: Long, draft: ExerciseDraft): WorkoutExercise = dbQuery {
        val session = completedForEdit(trainerId, sessionId)
        val added = insertExercise(session, draft, (workouts.lastOrderIndex(sessionId) ?: -1) + 1)
        workouts.touchSession(sessionId)
        added
    }

    suspend fun deleteExercise(trainerId: Long, exerciseId: Long) = dbQuery {
        val exercise = ownedExercise(trainerId, exerciseId)
        workouts.deleteExercise(exerciseId)
        workouts.touchSession(exercise.sessionId)
    }

    suspend fun addSet(trainerId: Long, exerciseId: Long, set: SetDraft): ExerciseSet = dbQuery {
        val exercise = ownedExercise(trainerId, exerciseId)
        workouts.lockExercise(exerciseId) ?: throw NotFoundException("Упражнение не найдено.")
        validateSet(set)
        workouts.insertSets(exerciseId, workouts.lastSetNumber(exerciseId), listOf(set))
        workouts.touchSession(exercise.sessionId)
        checkNotNull(workouts.lastSet(exerciseId))
    }

    suspend fun updateSet(trainerId: Long, setId: Long, set: SetDraft) = dbQuery {
        val existing = workouts.findSetById(setId) ?: throw NotFoundException("Подход не найден.")
        val exercise = ownedExercise(trainerId, existing.exerciseId, "Подход не найден.")
        validateSet(set)
        workouts.updateSet(setId, set)
        workouts.touchSession(exercise.sessionId)
    }

    suspend fun deleteSet(trainerId: Long, setId: Long) = dbQuery {
        val existing = workouts.findSetById(setId) ?: throw NotFoundException("Подход не найден.")
        val exercise = ownedExercise(trainerId, existing.exerciseId, "Подход не найден.")
        workouts.lockExercise(exercise.id)
        workouts.deleteSet(existing)
        workouts.touchSession(exercise.sessionId)
    }

    // --- Внутреннее ----------------------------------------------------------

    /** Упражнение завершённой тренировки этого тренера; сессия блокируется на время правки. */
    private fun ownedExercise(trainerId: Long, exerciseId: Long, missing: String = "Упражнение не найдено."): WorkoutExercise {
        val exercise = workouts.findExerciseById(exerciseId) ?: throw NotFoundException(missing)
        val session = workouts.findOwnedSession(trainerId, exercise.sessionId, lock = true) ?: throw NotFoundException(missing)
        if (session.stage != WorkoutStage.COMPLETED) throw ConflictException("Тренировка ещё идёт. Продолжите её в боте.")
        return exercise
    }

    private fun completedForEdit(trainerId: Long, sessionId: Long): WorkoutSession {
        val session = requireSessionOwned(trainerId, sessionId, lock = true)
        if (session.stage != WorkoutStage.COMPLETED) throw ConflictException("Тренировка ещё идёт. Продолжите её в боте.")
        return session
    }

    private fun requireSessionOwned(trainerId: Long, sessionId: Long, lock: Boolean = false): WorkoutSession =
        workouts.findOwnedSession(trainerId, sessionId, lock) ?: throw NotFoundException("Тренировка не найдена.")

    private fun requireOwnedClient(trainerId: Long, clientId: Long, lock: Boolean = false) {
        clients.findOwned(trainerId, clientId, lock) ?: throw NotFoundException("Подопечный не найден.")
    }

    private fun validateSession(performedAt: OffsetDateTime, durationMin: Int?) {
        if (performedAt.toLocalDate() !in EARLIEST_DATE..LATEST_DATE) throw ValidationException("Дата должна быть между 1900 и 2100 годом.")
        if (durationMin != null && durationMin <= 0) throw ValidationException("Длительность должна быть положительной.")
    }
}

fun validateSet(set: SetDraft) {
    if ((set.reps == null) == (set.durationSec == null)) {
        throw ValidationException("Укажите один результат подхода: повторы или длительность в секундах.")
    }
    if (set.durationSec != null && set.durationSec <= 0) throw ValidationException("Длительность подхода должна быть положительной.")
    if (set.reps != null && set.reps <= 0) throw ValidationException("Количество повторений должно быть положительным.")
    set.weightKg?.let { if (it < BigDecimal.ZERO || !it.fitsDecimal(6, 2)) throw ValidationException("Недопустимый вес подхода (кг).") }
    set.restSec?.let { if (it < 0) throw ValidationException("Отдых не может быть отрицательным.") }
    set.rpe?.let {
        if (it < BigDecimal.ZERO || it > BigDecimal("10") || !it.fitsDecimal(3, 1)) throw ValidationException("RPE должен быть в диапазоне 0–10.")
    }
}
