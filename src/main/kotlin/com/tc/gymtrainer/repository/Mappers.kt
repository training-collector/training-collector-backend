package com.tc.gymtrainer.repository

import com.tc.gymtrainer.db.BodyMeasurements
import com.tc.gymtrainer.db.Clients
import com.tc.gymtrainer.db.ExerciseCatalog
import com.tc.gymtrainer.db.ExerciseSets
import com.tc.gymtrainer.db.MuscleGroups
import com.tc.gymtrainer.db.Trainers
import com.tc.gymtrainer.db.WorkoutExercises
import com.tc.gymtrainer.db.WorkoutSessions
import com.tc.gymtrainer.domain.BodyMeasurement
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.ExerciseCatalogItem
import com.tc.gymtrainer.domain.ExerciseSet
import com.tc.gymtrainer.domain.MuscleGroup
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.domain.WorkoutExercise
import com.tc.gymtrainer.domain.WorkoutSession
import org.jetbrains.exposed.sql.ResultRow

/** Преобразование строк Exposed в доменные модели. */

fun ResultRow.toTrainer() = Trainer(
    id = this[Trainers.id].value,
    telegramUserId = this[Trainers.telegramUserId],
    fullName = this[Trainers.fullName],
    createdAt = this[Trainers.createdAt],
    username = this[Trainers.username],
    firstName = this[Trainers.firstName],
    lastName = this[Trainers.lastName],
    telegramLinkedAt = this[Trainers.telegramLinkedAt],
    approvedAt = this[Trainers.approvedAt],
    trainingBotLinkedAt = this[Trainers.trainingBotLinkedAt],
    signupTelegramInUse = this[Trainers.signupTelegramInUse],
    deactivatedAt = this[Trainers.deactivatedAt],
    timeZone = this[Trainers.timeZone],
)

fun ResultRow.toClient() = Client(
    id = this[Clients.id].value,
    trainerId = this[Clients.trainerId].value,
    fullName = this[Clients.fullName],
    firstName = this[Clients.firstName],
    lastName = this[Clients.lastName],
    sex = this[Clients.sex],
    age = this[Clients.age],
    telegramUsername = this[Clients.telegramUsername],
    goal = this[Clients.goal],
    notes = this[Clients.notes],
    status = this[Clients.status],
    createdAt = this[Clients.createdAt],
    updatedAt = this[Clients.updatedAt],
)

fun ResultRow.toMuscleGroup() = MuscleGroup(
    id = this[MuscleGroups.id].value,
    name = this[MuscleGroups.name],
    description = this[MuscleGroups.description],
)

fun ResultRow.toExerciseCatalogItem() = ExerciseCatalogItem(
    id = this[ExerciseCatalog.id].value,
    muscleGroupId = this[ExerciseCatalog.muscleGroupId].value,
    name = this[ExerciseCatalog.name],
    description = this[ExerciseCatalog.description],
    hidden = this[ExerciseCatalog.hidden],
)

fun ResultRow.toWorkoutSession() = WorkoutSession(
    stage = this[WorkoutSessions.stage],
    id = this[WorkoutSessions.id].value,
    clientId = this[WorkoutSessions.clientId].value,
    trainerId = this[WorkoutSessions.trainerId].value,
    performedAt = this[WorkoutSessions.performedAt],
    durationMin = this[WorkoutSessions.durationMin],
    note = this[WorkoutSessions.note],
    createdAt = this[WorkoutSessions.createdAt],
    updatedAt = this[WorkoutSessions.updatedAt],
)

fun ResultRow.toWorkoutExercise() = WorkoutExercise(
    id = this[WorkoutExercises.id].value,
    sessionId = this[WorkoutExercises.sessionId].value,
    exerciseCatalogId = this[WorkoutExercises.exerciseCatalogId]?.value,
    exerciseName = this[WorkoutExercises.exerciseName],
    orderIndex = this[WorkoutExercises.orderIndex],
    note = this[WorkoutExercises.note],
)

fun ResultRow.toExerciseSet() = ExerciseSet(
    id = this[ExerciseSets.id].value,
    exerciseId = this[ExerciseSets.exerciseId].value,
    setNumber = this[ExerciseSets.setNumber],
    weightKg = this[ExerciseSets.weightKg],
    reps = this[ExerciseSets.reps],
    durationSec = this[ExerciseSets.durationSec],
    restSec = this[ExerciseSets.restSec],
    rpe = this[ExerciseSets.rpe],
)

fun ResultRow.toBodyMeasurement() = BodyMeasurement(
    id = this[BodyMeasurements.id].value,
    clientId = this[BodyMeasurements.clientId].value,
    measuredOn = this[BodyMeasurements.measuredOn],
    weightKg = this[BodyMeasurements.weightKg],
    bodyFatPct = this[BodyMeasurements.bodyFatPct],
    chestCm = this[BodyMeasurements.chestCm],
    waistCm = this[BodyMeasurements.waistCm],
    hipsCm = this[BodyMeasurements.hipsCm],
    bicepsCm = this[BodyMeasurements.bicepsCm],
    thighCm = this[BodyMeasurements.thighCm],
    heightCm = this[BodyMeasurements.heightCm],
    calfCm = this[BodyMeasurements.calfCm],
    rightBicepsCm = this[BodyMeasurements.rightBicepsCm],
    leftBicepsCm = this[BodyMeasurements.leftBicepsCm],
    rightForearmCm = this[BodyMeasurements.rightForearmCm],
    leftForearmCm = this[BodyMeasurements.leftForearmCm],
    backWidthCm = this[BodyMeasurements.backWidthCm],
    rightThighCm = this[BodyMeasurements.rightThighCm],
    leftThighCm = this[BodyMeasurements.leftThighCm],
    rightCalfCm = this[BodyMeasurements.rightCalfCm],
    leftCalfCm = this[BodyMeasurements.leftCalfCm],
    note = this[BodyMeasurements.note],
    createdAt = this[BodyMeasurements.createdAt],
    version = this[BodyMeasurements.version],
)
