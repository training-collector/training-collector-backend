package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.ExerciseCatalog
import com.tc.gymtrainer.db.MuscleGroups
import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WorkoutExercises
import com.tc.gymtrainer.domain.ExerciseCatalogItem
import com.tc.gymtrainer.domain.MuscleGroup
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.ForUpdateOption

/** Справочник групп мышц и упражнений (для автоподстановки). Вызывать в транзакции. */
class CatalogRepository {

    // --- Группы мышц ---------------------------------------------------------

    fun listMuscleGroups(): List<MuscleGroup> =
        MuscleGroups.selectAll().orderBy(MuscleGroups.id to SortOrder.ASC).map { it.toMuscleGroup() }

    fun findMuscleGroupById(id: Long): MuscleGroup? =
        MuscleGroups.selectAll()
            .where { MuscleGroups.id eq id }
            .firstOrNull()
            ?.toMuscleGroup()

    // --- Упражнения ----------------------------------------------------------

    // Serialize changes to one trainer's catalog with workout writes that copy its names.
    fun lockCatalog(trainerId: Long) {
        Trainers.select(Trainers.id).where { Trainers.id eq trainerId }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).single()
    }

    private fun exercisesNamed(trainerId: Long, name: String) = ExerciseCatalog.selectAll().where {
        (ExerciseCatalog.trainerId eq trainerId) and (ExerciseCatalog.name.lowerCase() eq stringParam(name).lowerCase())
    }

    fun updateExercise(trainerId: Long, oldName: String, newName: String, description: String?): Boolean {
        // The flat list groups the same name across historical muscle groups.
        val ids = exercisesNamed(trainerId, oldName).map { it[ExerciseCatalog.id] }
        if (exercisesNamed(trainerId, newName).any { it[ExerciseCatalog.id] !in ids }) return false
        ExerciseCatalog.update({ ExerciseCatalog.id inList ids }) {
            it[name] = newName
            it[ExerciseCatalog.description] = description
        }
        if (oldName != newName) {
            WorkoutExercises.update({ WorkoutExercises.exerciseCatalogId inList ids }) { it[exerciseName] = newName }
        }
        return true
    }

    fun listExercises(trainerId: Long, muscleGroupId: Long?, query: String? = null, limit: Int? = null): List<ExerciseCatalogItem> {
        val rows = ExerciseCatalog.selectAll().where { ExerciseCatalog.trainerId eq trainerId }
        if (muscleGroupId != null) {
            rows.andWhere { ExerciseCatalog.muscleGroupId eq muscleGroupId }
        } else {
            // A name could appear in multiple legacy groups. Keep all ids usable in workout history.
            val firstIds = ExerciseCatalog.select(ExerciseCatalog.id.min())
                .where { ExerciseCatalog.trainerId eq trainerId }
                .groupBy(ExerciseCatalog.name.lowerCase())
            rows.andWhere { ExerciseCatalog.id inSubQuery firstIds }
        }
        if (query != null) rows.andWhere { ExerciseCatalog.name.lowerCase() like stringParam("%${query}%").lowerCase() }
        rows.orderBy(ExerciseCatalog.name.lowerCase() to SortOrder.ASC, ExerciseCatalog.id to SortOrder.ASC)
        if (limit != null) rows.limit(limit)
        return rows.map { it.toExerciseCatalogItem() }
    }

    fun findExercise(trainerId: Long, id: Long): ExerciseCatalogItem? =
        ExerciseCatalog.selectAll()
            .where { (ExerciseCatalog.trainerId eq trainerId) and (ExerciseCatalog.id eq id) }
            .firstOrNull()
            ?.toExerciseCatalogItem()

    /** Возвращает существующее упражнение или создаёт новое в справочнике. */
    fun getOrCreateExercise(trainerId: Long, muscleGroupId: Long?, name: String, description: String?): ExerciseCatalogItem {
        val matching = exercisesNamed(trainerId, name).orderBy(ExerciseCatalog.id to SortOrder.ASC).limit(1)
        if (muscleGroupId != null) matching.andWhere { ExerciseCatalog.muscleGroupId eq muscleGroupId }
        matching.firstOrNull()?.let { return it.toExerciseCatalogItem() }
        ExerciseCatalog.insertIgnore {
            it[ExerciseCatalog.trainerId] = trainerId
            it[ExerciseCatalog.muscleGroupId] = muscleGroupId
            it[ExerciseCatalog.name] = name
            it[ExerciseCatalog.description] = description
        }
        return matching.single().toExerciseCatalogItem()
    }
}
