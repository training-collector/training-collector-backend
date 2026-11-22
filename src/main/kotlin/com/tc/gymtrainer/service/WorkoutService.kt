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
import org.jetbrains.exposed.sql.Transaction
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Ядро журнала тренировок: создание (в т.ч. переиспользование прошлой),
 * чтение, история с фильтрами и полный CRUD над тренировкой/подходами.
 */
class WorkoutService(
    private val workouts: WorkoutRepository,
    private val clients: ClientRepository,
    private val catalog: CatalogService,
) {

    // --- Создание ------------------------------------------------------------

    /**
     * Создаёт проведённую тренировку из черновика. Новые упражнения автоматически
     * попадают в справочник (getOrCreate), имя денормализуется в записи.
     */
    suspend fun create(trainerId: Long, draft: WorkoutDraft): WorkoutDetail = dbQuery {
        val client = clients.findById(draft.clientId) ?: throw NotFoundException("Подопечный не найден.")
        if (client.trainerId != trainerId) throw AccessDeniedException("Этот подопечный принадлежит другому тренеру.")
        draft.durationMin?.let { if (it <= 0) throw ValidationException("Длительность должна быть положительной.") }

        val now = OffsetDateTime.now()
        val session = workouts.insertSession(
            clientId = draft.clientId,
            trainerId = trainerId,
            performedAt = draft.performedAt,
            durationMin = draft.durationMin,
            note = draft.note?.trim()?.ifBlank { null },
            now = now,
        )
        draft.exercises.forEachIndexed { index, exercise -> insertExercise(session, exercise, index) }
        workouts.getDetail(session.id) ?: throw NotFoundException("Тренировка не найдена после создания.")
    }

    /** Вставляет одно упражнение с подходами, регистрируя новое в справочнике. */
    private fun insertExercise(session: WorkoutSession, draft: ExerciseDraft, orderIndex: Int): WorkoutExercise {
        val item = catalog.resolveExercise(session.trainerId, draft.exerciseCatalogId, draft.muscleGroupId, draft.exerciseName)
        val exercise = workouts.insertExercise(
            sessionId = session.id,
            muscleGroupId = item.muscleGroupId,
            exerciseCatalogId = item.id,
            exerciseName = item.name,
            orderIndex = orderIndex,
            note = draft.note?.trim()?.ifBlank { null },
        )
        insertSets(exercise.id, 0, draft.sets)
        return exercise
    }

    private fun insertSets(exerciseId: Long, after: Int, sets: List<SetDraft>) {
        sets.forEachIndexed { index, set ->
            validateSet(set)
            workouts.insertSet(
                exerciseId = exerciseId,
                setNumber = after + index + 1,
                weightKg = set.weightKg,
                reps = set.reps,
                durationSec = set.durationSec,
                restSec = set.restSec,
                rpe = set.rpe,
            )
        }
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

    /** Запись, открытая в старой версии бота, могла остаться на выборе шаблона. */
    suspend fun skipLegacyTemplate(trainerId: Long, sessionId: Long) =
        advance(trainerId, sessionId, WorkoutStage.CHOOSE_TEMPLATE) { WorkoutStage.CHOOSE_EXERCISE }

    suspend fun selectExercise(trainerId: Long, sessionId: Long, catalogId: Long) =
        advance(trainerId, sessionId, WorkoutStage.CHOOSE_EXERCISE) { session ->
            val next = (workouts.listExercises(session.id).lastOrNull()?.orderIndex ?: -1) + 1
            insertExercise(session, ExerciseDraft(null, catalogId, null), next)
            WorkoutStage.ENTER_SETS
        }

    suspend fun appendActiveSets(trainerId: Long, sessionId: Long, exerciseId: Long, sets: List<SetDraft>) =
        advance(trainerId, sessionId, WorkoutStage.ENTER_SETS) {
            val existing = currentExerciseSets(sessionId, exerciseId)
            insertSets(exerciseId, existing.lastOrNull()?.setNumber ?: 0, sets)
            WorkoutStage.ENTER_SETS
        }

    suspend fun undoActiveSet(trainerId: Long, sessionId: Long, exerciseId: Long) =
        advance(trainerId, sessionId, WorkoutStage.ENTER_SETS) {
            currentExerciseSets(sessionId, exerciseId).lastOrNull()?.let { workouts.deleteSet(it.id) }
            WorkoutStage.ENTER_SETS
        }

    suspend fun finishExercise(trainerId: Long, sessionId: Long, exerciseId: Long) =
        advance(trainerId, sessionId, WorkoutStage.ENTER_SETS) {
            if (currentExerciseSets(sessionId, exerciseId).isEmpty()) throw ValidationException("Введите хотя бы один подход.")
            WorkoutStage.REVIEW
        }

    suspend fun chooseAnotherExercise(trainerId: Long, sessionId: Long) =
        advance(trainerId, sessionId, WorkoutStage.REVIEW) { WorkoutStage.CHOOSE_EXERCISE }

    suspend fun finishWorkout(trainerId: Long, sessionId: Long): WorkoutDetail = dbQuery {
        val session = requireSessionOwned(trainerId, sessionId, lock = true)
        if (session.stage != WorkoutStage.COMPLETED) {
            if (session.stage == WorkoutStage.ENTER_SETS) {
                val last = workouts.listExercises(sessionId).last()
                if (workouts.listSets(last.id).isEmpty()) workouts.deleteExercise(last.id)
            }
            workouts.setStage(sessionId, WorkoutStage.COMPLETED)
        }
        checkNotNull(workouts.getDetail(sessionId))
    }

    private fun currentExerciseSets(sessionId: Long, exerciseId: Long): List<ExerciseSet> {
        if (workouts.listExercises(sessionId).lastOrNull()?.id != exerciseId)
            throw ConflictException("Уже выбрано другое упражнение. Откройте тренировку из карточки.")
        return workouts.listSets(exerciseId)
    }

    private suspend fun advance(trainerId: Long, sessionId: Long, expected: WorkoutStage, next: (WorkoutSession) -> WorkoutStage) = dbQuery {
        val session = requireSessionOwned(trainerId, sessionId, lock = true)
        if (session.stage != expected) throw ConflictException("Состояние тренировки изменилось. Откройте карточку подопечного.")
        workouts.setStage(sessionId, next(session))
    }

    // --- Чтение --------------------------------------------------------------

    suspend fun getSession(trainerId: Long, sessionId: Long): WorkoutSession = dbQuery {
        requireSessionOwned(trainerId, sessionId)
    }

    suspend fun getDetail(trainerId: Long, sessionId: Long): WorkoutDetail = dbQuery {
        requireSessionOwned(trainerId, sessionId)
        workouts.getDetail(sessionId) ?: throw NotFoundException("Тренировка не найдена.")
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

    /** Последняя тренировка клиента целиком (для показа/переиспользования). */
    suspend fun lastWorkout(trainerId: Long, clientId: Long): WorkoutDetail? = dbQuery {
        requireOwnedClient(trainerId, clientId)
        val last = workouts.lastSessionForClient(clientId) ?: return@dbQuery null
        workouts.getDetail(last.id)
    }

    /**
     * Готовит черновик новой тренировки как копию последней тренировки клиента
     * (переиспользование: тренер правит веса/повторения, а не вводит всё заново).
     * Возвращает null, если у клиента ещё нет тренировок.
     */
    suspend fun copyLastAsDraft(
        trainerId: Long,
        clientId: Long,
        performedAt: OffsetDateTime = OffsetDateTime.now(),
    ): WorkoutDraft? = dbQuery {
        requireOwnedClient(trainerId, clientId)
        val last = workouts.lastSessionForClient(clientId) ?: return@dbQuery null
        val detail = workouts.getDetail(last.id) ?: return@dbQuery null
        WorkoutDraft(clientId, performedAt, exercises = detail.exerciseDrafts())
    }

    private fun WorkoutDetail.exerciseDrafts() = exercises.map { (exercise, sets) ->
        ExerciseDraft(
            muscleGroupId = exercise.muscleGroupId,
            exerciseCatalogId = exercise.exerciseCatalogId,
            exerciseName = exercise.exerciseName,
            note = exercise.note,
            sets = sets.map { SetDraft(it.weightKg, it.reps, it.restSec, it.rpe, it.durationSec) },
        )
    }

    // --- Изменение / удаление ------------------------------------------------

    suspend fun updateSession(
        trainerId: Long,
        sessionId: Long,
        performedAt: OffsetDateTime,
        durationMin: Int?,
        note: String?,
    ): WorkoutDetail = updateSession(trainerId, sessionId) {
        it.copy(performedAt = performedAt, durationMin = durationMin, note = note)
    }

    suspend fun updateSession(
        trainerId: Long, sessionId: Long, change: (WorkoutSession) -> WorkoutSession,
    ): WorkoutDetail = dbQuery {
        val updated = change(completedForEdit(trainerId, sessionId))
        if (updated.durationMin != null && updated.durationMin <= 0) throw ValidationException("Длительность должна быть положительной.")
        workouts.updateSession(sessionId, updated.performedAt, updated.durationMin, updated.note?.trim()?.ifBlank { null })
        workouts.getDetail(sessionId) ?: throw NotFoundException("Тренировка не найдена.")
    }

    suspend fun deleteSession(trainerId: Long, sessionId: Long) = dbQuery {
        completedForEdit(trainerId, sessionId)
        workouts.deleteSession(sessionId)
    }

    /** Добавляет упражнение в существующую тренировку (в конец списка). */
    suspend fun addExercise(trainerId: Long, sessionId: Long, draft: ExerciseDraft): WorkoutExercise = dbQuery {
        val session = completedForEdit(trainerId, sessionId)
        val nextIndex = (workouts.listExercises(sessionId).lastOrNull()?.orderIndex ?: -1) + 1
        val added = insertExercise(session, draft, nextIndex)
        workouts.touchSession(sessionId)
        added
    }

    suspend fun deleteExercise(trainerId: Long, exerciseId: Long) = dbQuery {
        val exercise = workouts.findExerciseById(exerciseId) ?: throw NotFoundException("Упражнение не найдено.")
        completedForEdit(trainerId, exercise.sessionId)
        workouts.deleteExercise(exerciseId)
        workouts.touchSession(exercise.sessionId)
    }

    suspend fun addSet(trainerId: Long, exerciseId: Long, set: SetDraft) = dbQuery {
        val exercise = workouts.findExerciseById(exerciseId) ?: throw NotFoundException("Упражнение не найдено.")
        completedForEdit(trainerId, exercise.sessionId)
        workouts.lockExercise(exerciseId) ?: throw NotFoundException("Упражнение не найдено.")
        validateSet(set)
        val nextNumber = (workouts.listSets(exerciseId).lastOrNull()?.setNumber ?: 0) + 1
        val inserted = workouts.insertSet(exerciseId, nextNumber, set.weightKg, set.reps, set.restSec, set.rpe, set.durationSec)
        workouts.touchSession(exercise.sessionId)
        inserted
    }

    suspend fun updateSet(
        trainerId: Long,
        setId: Long,
        weightKg: BigDecimal?,
        reps: Int?,
        restSec: Int?,
        rpe: BigDecimal?,
        durationSec: Int? = null,
    ) = dbQuery {
        val existing = workouts.findSetById(setId) ?: throw NotFoundException("Подход не найден.")
        val exercise = workouts.findExerciseById(existing.exerciseId) ?: throw NotFoundException("Упражнение не найдено.")
        completedForEdit(trainerId, exercise.sessionId)
        validateSet(SetDraft(weightKg, reps, restSec, rpe, durationSec))
        workouts.updateSet(setId, weightKg, reps, restSec, rpe, durationSec)
        workouts.touchSession(exercise.sessionId)
    }

    suspend fun deleteSet(trainerId: Long, setId: Long) = dbQuery {
        val existing = workouts.findSetById(setId) ?: throw NotFoundException("Подход не найден.")
        val exercise = workouts.findExerciseById(existing.exerciseId) ?: throw NotFoundException("Упражнение не найдено.")
        completedForEdit(trainerId, exercise.sessionId)
        workouts.deleteSet(setId)
        workouts.touchSession(exercise.sessionId)
    }

    // --- Внутреннее ----------------------------------------------------------

    private fun Transaction.completedForEdit(trainerId: Long, sessionId: Long): WorkoutSession {
        val session = requireSessionOwned(trainerId, sessionId, lock = true)
        if (session.stage != WorkoutStage.COMPLETED) throw ConflictException("Тренировка ещё идёт. Продолжите её в боте.")
        return session
    }

    private fun Transaction.requireSessionOwned(trainerId: Long, sessionId: Long, lock: Boolean = false): WorkoutSession {
        val session = (if (lock) workouts.lockSession(sessionId) else workouts.findSessionById(sessionId))
            ?: throw NotFoundException("Тренировка не найдена.")
        if (session.trainerId != trainerId) throw AccessDeniedException("Эта тренировка принадлежит другому тренеру.")
        return session
    }

    private fun Transaction.requireOwnedClient(trainerId: Long, clientId: Long, lock: Boolean = false) {
        val client = clients.findById(clientId, lock) ?: throw NotFoundException("Подопечный не найден.")
        if (client.trainerId != trainerId) throw AccessDeniedException("Этот подопечный принадлежит другому тренеру.")
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
