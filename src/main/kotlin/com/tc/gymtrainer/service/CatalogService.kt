package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.ExerciseCatalogItem
import com.tc.gymtrainer.domain.MuscleGroup
import com.tc.gymtrainer.repository.CatalogRepository

/** Личный список упражнений тренера. */
class CatalogService(private val catalog: CatalogRepository) {

    suspend fun listMuscleGroups(): List<MuscleGroup> =
        dbQuery { catalog.listMuscleGroups() }

    suspend fun exercisesFor(trainerId: Long, muscleGroupId: Long? = null): List<ExerciseCatalogItem> = dbQuery {
        if (muscleGroupId != null && catalog.findMuscleGroupById(muscleGroupId) == null) {
            throw NotFoundException("Группа мышц не найдена.")
        }
        catalog.listExercises(trainerId, muscleGroupId)
    }

    /** Подсказки упражнений по вводимой подстроке (для автоподстановки). */
    suspend fun suggest(trainerId: Long, query: String, muscleGroupId: Long? = null, limit: Int = 10): List<ExerciseCatalogItem> = dbQuery {
        if (query.isBlank() && muscleGroupId == null) emptyList()
        else catalog.listExercises(trainerId, muscleGroupId, query.trim(), limit)
    }

    suspend fun getExercise(trainerId: Long, id: Long): ExerciseCatalogItem = dbQuery {
        catalog.findExercise(trainerId, id) ?: throw NotFoundException("Упражнение не найдено в вашем списке.")
    }

    suspend fun addExercise(trainerId: Long, muscleGroupId: Long? = null, name: String, description: String? = null): ExerciseCatalogItem = dbQuery {
        resolveExercise(trainerId, null, muscleGroupId, name, description)
    }

    suspend fun updateExercise(trainerId: Long, id: Long, name: String, description: String?): ExerciseCatalogItem = dbQuery {
        val trimmed = exerciseName(name)
        val details = description?.trim()?.ifEmpty { null }
        catalog.lockCatalog(trainerId)
        val item = catalog.findExercise(trainerId, id) ?: throw NotFoundException("Упражнение не найдено в вашем списке.")
        if (!catalog.updateExercise(trainerId, item.name, trimmed, details)) {
            throw ConflictException("Упражнение с таким названием уже есть в вашем списке.")
        }
        item.copy(name = trimmed, description = details)
    }

    private fun exerciseName(name: String?): String = name.orEmpty().trim().also {
        if (it.length !in 1..255) throw ValidationException("Название упражнения должно содержать от 1 до 255 символов.")
    }

    /** Called within the catalog or workout write transaction. */
    internal fun resolveExercise(trainerId: Long, id: Long?, muscleGroupId: Long?, name: String?, description: String? = null): ExerciseCatalogItem {
        catalog.lockCatalog(trainerId)
        if (id != null) return catalog.findExercise(trainerId, id)
            ?: throw NotFoundException("Упражнение не найдено в вашем списке.")
        val trimmed = exerciseName(name)
        if (muscleGroupId != null && catalog.findMuscleGroupById(muscleGroupId) == null) {
            throw ValidationException("Неизвестная группа мышц.")
        }
        return catalog.getOrCreateExercise(trainerId, muscleGroupId, trimmed, description?.trim()?.ifEmpty { null })
    }
}
