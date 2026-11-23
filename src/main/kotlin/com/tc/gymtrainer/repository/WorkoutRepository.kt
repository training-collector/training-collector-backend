package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.ExerciseSets
import com.tc.gymtrainer.db.WorkoutExercises
import com.tc.gymtrainer.db.WorkoutSessions
import com.tc.gymtrainer.domain.ExerciseSet
import com.tc.gymtrainer.domain.ExerciseWithSets
import com.tc.gymtrainer.domain.SetDraft
import com.tc.gymtrainer.domain.WorkoutDetail
import com.tc.gymtrainer.domain.WorkoutExercise
import com.tc.gymtrainer.domain.WorkoutSession
import com.tc.gymtrainer.domain.WorkoutStage
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/** Доступ к тренировкам (сессии, упражнения, подходы). Вызывать в транзакции. */
class WorkoutRepository {

    // --- Запись --------------------------------------------------------------

    fun insertSession(
        clientId: Long,
        trainerId: Long,
        performedAt: OffsetDateTime,
        durationMin: Int?,
        note: String?,
        now: OffsetDateTime = OffsetDateTime.now(),
        stage: WorkoutStage = WorkoutStage.COMPLETED,
    ): WorkoutSession {
        val id = WorkoutSessions.insertAndGetId {
            it[WorkoutSessions.stage] = stage
            it[WorkoutSessions.clientId] = clientId
            it[WorkoutSessions.trainerId] = trainerId
            it[WorkoutSessions.performedAt] = performedAt
            it[WorkoutSessions.durationMin] = durationMin
            it[WorkoutSessions.note] = note
            it[WorkoutSessions.createdAt] = now
            it[WorkoutSessions.updatedAt] = now
        }.value
        return WorkoutSession(id, clientId, trainerId, performedAt, durationMin, note, now, now, stage)
    }

    fun insertExercise(sessionId: Long, exerciseCatalogId: Long?, exerciseName: String, orderIndex: Int, note: String?): WorkoutExercise {
        val id = WorkoutExercises.insertAndGetId {
            it[WorkoutExercises.sessionId] = sessionId
            it[WorkoutExercises.exerciseCatalogId] = exerciseCatalogId
            it[WorkoutExercises.exerciseName] = exerciseName
            it[WorkoutExercises.orderIndex] = orderIndex
            it[WorkoutExercises.note] = note
        }.value
        return WorkoutExercise(id, sessionId, exerciseCatalogId, exerciseName, orderIndex, note)
    }

    /** Все подходы одним запросом. Номера продолжают [after]. */
    fun insertSets(exerciseId: Long, after: Int, sets: List<SetDraft>) {
        ExerciseSets.batchInsert(sets.withIndex(), shouldReturnGeneratedValues = false) { (index, set) ->
            this[ExerciseSets.exerciseId] = exerciseId
            this[ExerciseSets.setNumber] = after + index + 1
            this[ExerciseSets.weightKg] = set.weightKg
            this[ExerciseSets.durationSec] = set.durationSec
            this[ExerciseSets.reps] = set.reps
            this[ExerciseSets.restSec] = set.restSec
            this[ExerciseSets.rpe] = set.rpe
        }
    }

    // --- Чтение --------------------------------------------------------------

    /** Тренировка этого тренера. Чужая и несуществующая неразличимы. */
    fun findOwnedSession(trainerId: Long, id: Long, lock: Boolean = false): WorkoutSession? =
        WorkoutSessions.selectAll()
            .where { (WorkoutSessions.id eq id) and (WorkoutSessions.trainerId eq trainerId) }
            .apply { if (lock) forUpdate() }
            .firstOrNull()?.toWorkoutSession()

    fun lockExercise(id: Long): WorkoutExercise? =
        WorkoutExercises.selectAll().where { WorkoutExercises.id eq id }.forUpdate().firstOrNull()?.toWorkoutExercise()

    fun listExercises(sessionId: Long): List<WorkoutExercise> =
        WorkoutExercises.selectAll()
            .where { WorkoutExercises.sessionId eq sessionId }
            .orderBy(WorkoutExercises.orderIndex to SortOrder.ASC)
            .map { it.toWorkoutExercise() }

    fun lastExercise(sessionId: Long): WorkoutExercise? =
        WorkoutExercises.selectAll()
            .where { WorkoutExercises.sessionId eq sessionId }
            .orderBy(WorkoutExercises.orderIndex to SortOrder.DESC)
            .limit(1).firstOrNull()?.toWorkoutExercise()

    fun lastOrderIndex(sessionId: Long): Int? {
        val last = WorkoutExercises.orderIndex.max()
        return WorkoutExercises.select(last).where { WorkoutExercises.sessionId eq sessionId }.single()[last]
    }

    fun lastSetNumber(exerciseId: Long): Int {
        val last = ExerciseSets.setNumber.max()
        return ExerciseSets.select(last).where { ExerciseSets.exerciseId eq exerciseId }.single()[last] ?: 0
    }

    fun lastSet(exerciseId: Long): ExerciseSet? =
        ExerciseSets.selectAll()
            .where { ExerciseSets.exerciseId eq exerciseId }
            .orderBy(ExerciseSets.setNumber to SortOrder.DESC)
            .limit(1).firstOrNull()?.toExerciseSet()

    /** Полная тренировка одним набором запросов (без N+1 по подходам). */
    fun getDetail(session: WorkoutSession): WorkoutDetail {
        val exercises = listExercises(session.id)
        if (exercises.isEmpty()) return WorkoutDetail(session, emptyList())

        val exerciseIds = exercises.map { it.id }
        val setsByExercise = ExerciseSets.selectAll()
            .where { ExerciseSets.exerciseId inList exerciseIds }
            .orderBy(ExerciseSets.setNumber to SortOrder.ASC)
            .map { it.toExerciseSet() }
            .groupBy { it.exerciseId }

        return WorkoutDetail(session, exercises.map { ex -> ExerciseWithSets(ex, setsByExercise[ex.id].orEmpty()) })
    }

    /**
     * История тренировок клиента с фильтрами по дате (полуинтервал [from, to)) и по
     * названию упражнения (подстрока, без учёта регистра). Сортировка — новые сверху.
     */
    fun listSessions(
        clientId: Long,
        from: OffsetDateTime? = null,
        to: OffsetDateTime? = null,
        exerciseNameQuery: String? = null,
    ): List<WorkoutSession> {
        // Если задан фильтр по упражнению — сначала находим id подходящих сессий через join.
        if (!exerciseNameQuery.isNullOrBlank()) {
            val pattern = "%${exerciseNameQuery.lowercase()}%"
            val ids = WorkoutSessions.innerJoin(WorkoutExercises)
                .select(WorkoutSessions.id)
                .where {
                    dateCondition(clientId, from, to) and (WorkoutExercises.exerciseName.lowerCase() like pattern)
                }
                .withDistinct()
                .map { it[WorkoutSessions.id].value }
            if (ids.isEmpty()) return emptyList()
            return WorkoutSessions.selectAll()
                .where { WorkoutSessions.id inList ids }
                .orderBy(WorkoutSessions.performedAt to SortOrder.DESC, WorkoutSessions.id to SortOrder.DESC)
                .map { it.toWorkoutSession() }
        }

        return WorkoutSessions.selectAll()
            .where { dateCondition(clientId, from, to) }
            .orderBy(WorkoutSessions.performedAt to SortOrder.DESC, WorkoutSessions.id to SortOrder.DESC)
            .map { it.toWorkoutSession() }
    }

    private fun dateCondition(clientId: Long, from: OffsetDateTime?, to: OffsetDateTime?): Op<Boolean> =
        Op.build {
            var cond: Op<Boolean> = (WorkoutSessions.clientId eq clientId) and (WorkoutSessions.stage eq WorkoutStage.COMPLETED)
            if (from != null) cond = cond and (WorkoutSessions.performedAt greaterEq from)
            if (to != null) cond = cond and (WorkoutSessions.performedAt less to)
            cond
        }

    // --- Изменение / удаление ------------------------------------------------

    fun activeForClient(clientId: Long): WorkoutSession? =
        WorkoutSessions.selectAll()
            .where { (WorkoutSessions.clientId eq clientId) and (WorkoutSessions.stage neq WorkoutStage.COMPLETED) }
            .singleOrNull()?.toWorkoutSession()

    fun setStage(sessionId: Long, stage: WorkoutStage, durationMin: Int? = null) {
        WorkoutSessions.update({ WorkoutSessions.id eq sessionId }) {
            it[WorkoutSessions.stage] = stage
            if (durationMin != null) it[WorkoutSessions.durationMin] = durationMin
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updateSession(
        id: Long,
        performedAt: OffsetDateTime,
        durationMin: Int?,
        note: String?,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Boolean =
        WorkoutSessions.update({ WorkoutSessions.id eq id }) {
            it[WorkoutSessions.performedAt] = performedAt
            it[WorkoutSessions.durationMin] = durationMin
            it[WorkoutSessions.note] = note
            it[WorkoutSessions.updatedAt] = now
        } > 0

    fun touchSession(id: Long, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        WorkoutSessions.update({ WorkoutSessions.id eq id }) {
            it[updatedAt] = now
        } > 0

    /** Удаление сессии каскадно удаляет упражнения и подходы (ON DELETE CASCADE). */
    fun deleteSession(id: Long): Boolean =
        WorkoutSessions.deleteWhere { WorkoutSessions.id eq id } > 0

    fun deleteActiveSession(clientId: Long, sessionId: Long): Boolean =
        WorkoutSessions.deleteWhere {
            (WorkoutSessions.id eq sessionId) and (WorkoutSessions.clientId eq clientId) and
                (WorkoutSessions.stage neq WorkoutStage.COMPLETED)
        } > 0

    fun deleteExercise(id: Long): Boolean =
        WorkoutExercises.deleteWhere { WorkoutExercises.id eq id } > 0

    fun updateSet(id: Long, set: SetDraft): Boolean =
        ExerciseSets.update({ ExerciseSets.id eq id }) {
            it[ExerciseSets.weightKg] = set.weightKg
            it[ExerciseSets.durationSec] = set.durationSec
            it[ExerciseSets.reps] = set.reps
            it[ExerciseSets.restSec] = set.restSec
            it[ExerciseSets.rpe] = set.rpe
        } > 0

    fun findSetById(id: Long): ExerciseSet? =
        ExerciseSets.selectAll()
            .where { ExerciseSets.id eq id }
            .firstOrNull()
            ?.toExerciseSet()

    fun findExerciseById(id: Long): WorkoutExercise? =
        WorkoutExercises.selectAll()
            .where { WorkoutExercises.id eq id }
            .firstOrNull()
            ?.toWorkoutExercise()

    /** Удаляет подход и сдвигает номера следующих, чтобы нумерация осталась сплошной. */
    fun deleteSet(set: ExerciseSet): Boolean {
        val deleted = ExerciseSets.deleteWhere { ExerciseSets.id eq set.id } > 0
        ExerciseSets.update({ (ExerciseSets.exerciseId eq set.exerciseId) and (ExerciseSets.setNumber greater set.setNumber) }) {
            it[setNumber] = setNumber - 1
        }
        return deleted
    }
}
