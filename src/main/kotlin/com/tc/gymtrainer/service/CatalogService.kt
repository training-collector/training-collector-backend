package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.*
import com.tc.gymtrainer.domain.ExerciseCatalogItem
import com.tc.gymtrainer.domain.MuscleGroup
import com.tc.gymtrainer.repository.toExerciseCatalogItem
import com.tc.gymtrainer.repository.toMuscleGroup
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.vendors.ForUpdateOption

data class GoalItem(val id: Long, val name: String, val hidden: Boolean)
data class CatalogEdit(val name: String, val description: String? = null, val hidden: Boolean = false)
data class ExerciseEdit(val name: String, val muscleGroupId: Long, val description: String? = null, val hidden: Boolean = false)

class CatalogService {
    suspend fun listMuscleGroups() = dbQuery { MuscleGroups.selectAll().orderBy(MuscleGroups.name.lowerCase()).map { it.toMuscleGroup() } }

    suspend fun createMuscleGroup(value: String, description: String? = null) = saveMuscleGroup(null, value, description)

    suspend fun saveMuscleGroup(id: Long?, value: String, description: String? = null) = uniqueName {
        val name = validName(value, 128)
        val details = description?.trim()?.ifEmpty { null }
        val savedId = if (id == null) MuscleGroups.insertAndGetId {
            it[MuscleGroups.name] = name
            it[MuscleGroups.description] = details
        }.value else {
            val updated = MuscleGroups.update({ MuscleGroups.id eq id }) {
                it[MuscleGroups.name] = name
                it[MuscleGroups.description] = details
            }
            if (updated == 0) throw NotFoundException("Категория не найдена.")
            id
        }
        MuscleGroup(savedId, name, details)
    }

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
        // A shared lock keeps the exercise from changing until the workout is saved without
        // making trainers who record the same exercise wait for each other.
        return rows.forUpdate(ForUpdateOption.PostgreSQL.ForShare()).singleOrNull()?.toExerciseCatalogItem()
            ?: throw NotFoundException("Выберите доступное упражнение из справочника.")
    }

    suspend fun saveExercise(id: Long?, edit: ExerciseEdit) = uniqueName {
        val name = validName(edit.name, 255)
        val description = edit.description?.trim()?.ifEmpty { null }
        val group = MuscleGroups.select(MuscleGroups.id).where { MuscleGroups.id eq edit.muscleGroupId }.singleOrNull()
            ?: throw ValidationException("Выберите существующую группу мышц.")
        val saved = if (id == null) ExerciseCatalog.insertAndGetId {
            it[muscleGroupId] = group[MuscleGroups.id]
            it[ExerciseCatalog.name] = name
            it[ExerciseCatalog.description] = description
            it[hidden] = edit.hidden
        }.value else {
            if (ExerciseCatalog.update({ ExerciseCatalog.id eq id }) {
                it[muscleGroupId] = group[MuscleGroups.id]
                it[ExerciseCatalog.name] = name
                it[ExerciseCatalog.description] = description
                it[hidden] = edit.hidden
            } == 0) throw NotFoundException("Упражнение не найдено.")
            id
        }
        ExerciseCatalogItem(saved, group[MuscleGroups.id].value, name, description, edit.hidden)
    }

    /** Записанные тренировки сохраняют название упражнения, ссылка на справочник обнуляется (ON DELETE SET NULL). */
    suspend fun deleteExercise(id: Long) = dbQuery {
        if (ExerciseCatalog.deleteWhere { ExerciseCatalog.id eq id } == 0) throw NotFoundException("Упражнение не найдено.")
    }

    suspend fun deleteMuscleGroup(id: Long) = dbQuery {
        MuscleGroups.select(MuscleGroups.id).where { MuscleGroups.id eq id }.forUpdate().singleOrNull()
            ?: throw NotFoundException("Категория не найдена.")
        if (ExerciseCatalog.select(ExerciseCatalog.id).where { ExerciseCatalog.muscleGroupId eq id }.limit(1).any()) {
            throw ConflictException("В категории есть упражнения. Сначала перенесите или удалите их.")
        }
        MuscleGroups.deleteWhere { MuscleGroups.id eq id }
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
            val previous = Goals.select(Goals.name).where { Goals.id eq id }.forUpdate().singleOrNull()?.get(Goals.name)
                ?: throw NotFoundException("Цель не найдена.")
            Goals.update({ Goals.id eq id }) { it[Goals.name] = name; it[hidden] = edit.hidden }
            // Client cards keep a copy of the name; it changes only on a rename, not on hide or show.
            if (previous != name) Clients.update({ Clients.goalId eq id }) { it[goal] = name }
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
