package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.domain.ExerciseCatalogItem
import com.tc.gymtrainer.repository.toExerciseCatalogItem
import com.tc.gymtrainer.repository.toMuscleGroup
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

data class GoalItem(val id: Long, val name: String, val hidden: Boolean)
data class CatalogEdit(val name: String, val description: String? = null, val hidden: Boolean = false)

class CatalogService {
    suspend fun listMuscleGroups() = dbQuery { MuscleGroups.selectAll().orderBy(MuscleGroups.id).map { it.toMuscleGroup() } }

    suspend fun exercises(muscleGroupId: Long? = null, includeHidden: Boolean = false) = dbQuery {
        val rows = ExerciseCatalog.selectAll()
        if (!includeHidden) rows.andWhere { ExerciseCatalog.hidden eq false }
        if (muscleGroupId != null) rows.andWhere { ExerciseCatalog.muscleGroupId eq muscleGroupId }
        rows.orderBy(ExerciseCatalog.name.lowerCase(), order = SortOrder.ASC).orderBy(ExerciseCatalog.id).map { it.toExerciseCatalogItem() }
    }

    suspend fun getExercise(id: Long) = dbQuery { resolveExercise(id, null) }

    internal fun resolveExercise(id: Long?, name: String?): ExerciseCatalogItem {
        val rows = ExerciseCatalog.selectAll().where { ExerciseCatalog.hidden eq false }
        if (id != null) rows.andWhere { ExerciseCatalog.id eq id }
        else rows.andWhere { ExerciseCatalog.name.lowerCase() eq stringParam(name.orEmpty().trim()).lowerCase() }
        return rows.forUpdate().singleOrNull()?.toExerciseCatalogItem()
            ?: throw NotFoundException("Выберите доступное упражнение из справочника.")
    }

    suspend fun saveExercise(id: Long?, edit: CatalogEdit) = uniqueName {
        val name = validName(edit.name, 255)
        val description = edit.description?.trim()?.ifEmpty { null }
        val saved = if (id == null) ExerciseCatalog.insertAndGetId {
            it[ExerciseCatalog.name] = name
            it[ExerciseCatalog.description] = description
            it[hidden] = edit.hidden
        }.value else {
            if (ExerciseCatalog.update({ ExerciseCatalog.id eq id }) {
                it[ExerciseCatalog.name] = name
                it[ExerciseCatalog.description] = description
                it[hidden] = edit.hidden
            } == 0) throw NotFoundException("Упражнение не найдено.")
            id
        }
        ExerciseCatalog.selectAll().where { ExerciseCatalog.id eq saved }.single().toExerciseCatalogItem()
    }

    suspend fun deleteExercise(id: Long) = dbQuery {
        ExerciseCatalog.select(ExerciseCatalog.id).where { ExerciseCatalog.id eq id }.forUpdate().singleOrNull()
            ?: throw NotFoundException("Упражнение не найдено.")
        WorkoutExercises.update({ WorkoutExercises.exerciseCatalogId eq id }) { it[exerciseCatalogId] = null }
        ExerciseCatalog.deleteWhere { ExerciseCatalog.id eq id }
    }

    suspend fun goals(includeHidden: Boolean = false) = dbQuery {
        val rows = Goals.selectAll()
        if (!includeHidden) rows.andWhere { Goals.hidden eq false }
        rows.orderBy(Goals.name.lowerCase(), order = SortOrder.ASC).map { GoalItem(it[Goals.id].value, it[Goals.name], it[Goals.hidden]) }
    }

    suspend fun saveGoal(id: Long?, edit: CatalogEdit) = uniqueName {
        val name = validName(edit.name, 128)
        val saved = if (id == null) Goals.insertAndGetId {
            it[Goals.name] = name
            it[hidden] = edit.hidden
        }.value else {
            if (Goals.update({ Goals.id eq id }) { it[Goals.name] = name; it[hidden] = edit.hidden } == 0)
                throw NotFoundException("Цель не найдена.")
            Clients.update({ Clients.goalId eq id }) { it[goal] = name }
            id
        }
        GoalItem(saved, name, edit.hidden)
    }

    suspend fun deleteGoal(id: Long) = dbQuery {
        if (Goals.deleteWhere { Goals.id eq id } == 0) throw NotFoundException("Цель не найдена.")
    }

    private fun validName(value: String, limit: Int) = value.trim().also {
        if (it.length !in 1..limit) throw ValidationException("Название должно содержать от 1 до $limit символов.")
    }

    private suspend fun <T> uniqueName(block: suspend Transaction.() -> T): T = try {
        dbQuery(block)
    } catch (e: ExposedSQLException) {
        if (e.sqlState == "23505") throw ConflictException("Такое название уже используется.")
        throw e
    }
}
