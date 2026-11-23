package com.tc.gymtrainer.db

import com.tc.gymtrainer.domain.ClientStatus
import com.tc.gymtrainer.domain.Sex
import com.tc.gymtrainer.domain.WorkoutStage
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed-описание таблиц. Соответствует Flyway-миграции V1. Схему в рантайме
 * создаёт Flyway, а не Exposed; эти объекты нужны только для типобезопасных запросов.
 * Enum-поля мапятся на varchar по имени константы (enumerationByName).
 */

object Trainers : LongIdTable("trainer") {
    val telegramUserId = long("telegram_user_id").nullable().uniqueIndex("uq_trainer_telegram_user_id")
    /** Вычисляется базой из имени и фамилии. */
    val fullName = varchar("full_name", 511).databaseGenerated()
    val apiTokenHash = varchar("api_token_hash", 64).nullable().uniqueIndex()
    val apiTokenExpiresAt = timestampWithTimeZone("api_token_expires_at").nullable()
    val username = varchar("username", 254).nullable()
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255).nullable()
    val timeZone = varchar("time_zone", 64).nullable()
    val telegramLinkedAt = timestampWithTimeZone("telegram_linked_at").nullable()
    val trainingBotLinkedAt = timestampWithTimeZone("training_bot_linked_at").nullable()
    val approvedAt = timestampWithTimeZone("approved_at").nullable()
    val signupExpiresAt = timestampWithTimeZone("signup_expires_at").nullable()
    val signupTelegramInUse = bool("signup_telegram_in_use").default(false)
    val deactivatedAt = timestampWithTimeZone("deactivated_at").nullable()
    val codeChatStarted = bool("code_chat_started").default(false)
    val createdAt = timestampWithTimeZone("created_at")
}

object WebSessions : Table("web_session") {
    val onboarding = bool("onboarding").default(false)
    val tokenHash = varchar("token_hash", 64)
    val trainerId = reference("trainer_id", Trainers)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    val ip = varchar("ip", 64).nullable()
    val userAgent = varchar("user_agent", 255).nullable()
    override val primaryKey = PrimaryKey(tokenHash)
}

object TelegramLinks : Table("telegram_link") {
    val tokenHash = varchar("token_hash", 64)
    val trainerId = reference("trainer_id", Trainers)
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

/** Запрос входа. [id] стоит в кнопках бота, секрет запроса знает только браузер, который его начал. */
object LoginRequests : Table("login_request") {
    val id = varchar("id", 32)
    val trainerId = reference("trainer_id", Trainers)
    val secretHash = varchar("secret_hash", 64)
    val number = short("number")
    val status = varchar("status", 16)
    val ip = varchar("ip", 64).nullable()
    val userAgent = varchar("user_agent", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val deliveredAt = timestampWithTimeZone("delivered_at").nullable()
    /** Compared with the database clock, so it is set by the database too. */
    val nextDeliveryAt = timestampWithTimeZone("next_delivery_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

object TelegramBotIdentity : Table("telegram_bot_identity") {
    val id = integer("id")
    val username = varchar("username", 32)
    override val primaryKey = PrimaryKey(id)
}

object Clients : LongIdTable("client") {
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255).nullable()
    val sex = enumerationByName<Sex>("sex", 8).nullable()
    val age = integer("age").nullable()
    val telegramUsername = varchar("telegram_username", 32).nullable()
    val trainerId = reference("trainer_id", Trainers)
    /** Вычисляется базой из имени и фамилии. */
    val fullName = varchar("full_name", 511).databaseGenerated()
    val goal = varchar("goal", 128).nullable()
    val goalId = optReference("goal_id", Goals)
    val notes = text("notes").nullable()
    val status = enumerationByName<ClientStatus>("status", 16)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object MuscleGroups : LongIdTable("muscle_group") {
    val name = varchar("name", 128)
    val description = text("description").nullable()
}

object Goals : LongIdTable("goal_catalog") {
    val name = varchar("name", 128)
    val hidden = bool("hidden").default(false)
}

object AdminSessions : Table("admin_session") {
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    val ip = varchar("ip", 64).nullable()
    val userAgent = varchar("user_agent", 255).nullable()
    override val primaryKey = PrimaryKey(tokenHash)
}

/** Одно ожидающее сообщение о доступе на тренера: значение имеет только последнее состояние. */
object TrainerNotices : LongIdTable("trainer_notice") {
    val trainerId = reference("trainer_id", Trainers)
    val kind = varchar("kind", 16)
    /** Compared with the database clock, so it is set by the database too. */
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").defaultExpression(CurrentTimestampWithTimeZone)
    val expiresAt = timestampWithTimeZone("expires_at")
}

object ExerciseCatalog : LongIdTable("exercise_catalog") {
    val hidden = bool("hidden").default(false)
    val muscleGroupId = reference("muscle_group_id", MuscleGroups)
    val name = varchar("name", 255)
    val description = text("description").nullable()
}

object WorkoutSessions : LongIdTable("workout_session") {
    val stage = enumerationByName<WorkoutStage>("stage", 20).default(WorkoutStage.COMPLETED)
    val clientId = reference("client_id", Clients)
    val trainerId = reference("trainer_id", Trainers)
    val performedAt = timestampWithTimeZone("performed_at")
    val durationMin = integer("duration_min").nullable()
    val note = text("note").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object WorkoutExercises : LongIdTable("workout_exercise") {
    val sessionId = reference("session_id", WorkoutSessions)
    val exerciseCatalogId = reference("exercise_catalog_id", ExerciseCatalog).nullable()
    val exerciseName = varchar("exercise_name", 255)
    val orderIndex = integer("order_index")
    val note = text("note").nullable()
}

object ExerciseSets : LongIdTable("exercise_set") {
    val exerciseId = reference("exercise_id", WorkoutExercises)
    val setNumber = integer("set_number")
    val weightKg = decimal("weight_kg", 6, 2).nullable()
    val reps = integer("reps").nullable()
    val durationSec = integer("duration_sec").nullable()
    val restSec = integer("rest_sec").nullable()
    val rpe = decimal("rpe", 3, 1).nullable()
}

object BodyMeasurements : LongIdTable("body_measurement") {
    val clientId = reference("client_id", Clients)
    val measuredOn = date("measured_on")
    val weightKg = decimal("weight_kg", 5, 2).nullable()
    val bodyFatPct = decimal("body_fat_pct", 4, 1).nullable()
    val chestCm = decimal("chest_cm", 5, 1).nullable()
    val waistCm = decimal("waist_cm", 5, 1).nullable()
    val hipsCm = decimal("hips_cm", 5, 1).nullable()
    val bicepsCm = decimal("biceps_cm", 5, 1).nullable()
    val heightCm = decimal("height_cm", 5, 1).nullable()
    val calfCm = decimal("calf_cm", 5, 1).nullable()
    val rightBicepsCm = decimal("right_biceps_cm", 5, 1).nullable()
    val leftBicepsCm = decimal("left_biceps_cm", 5, 1).nullable()
    val rightForearmCm = decimal("right_forearm_cm", 5, 1).nullable()
    val leftForearmCm = decimal("left_forearm_cm", 5, 1).nullable()
    val backWidthCm = decimal("back_width_cm", 5, 1).nullable()
    val rightThighCm = decimal("right_thigh_cm", 5, 1).nullable()
    val leftThighCm = decimal("left_thigh_cm", 5, 1).nullable()
    val rightCalfCm = decimal("right_calf_cm", 5, 1).nullable()
    val leftCalfCm = decimal("left_calf_cm", 5, 1).nullable()
    val thighCm = decimal("thigh_cm", 5, 1).nullable()
    val note = text("note").nullable()
    /** Растёт при каждой правке; защищает от одновременного редактирования. */
    val version = integer("version").default(0)
    val createdAt = timestampWithTimeZone("created_at")
}

object RateLimits : Table("rate_limit") {
    val bucket = varchar("bucket", 96)
    val windowStart = timestampWithTimeZone("window_start")
    val hits = integer("hits")
    override val primaryKey = PrimaryKey(bucket)
}

object AuditEvents : LongIdTable("audit_event") {
    val at = timestampWithTimeZone("at")
    val actor = varchar("actor", 64)
    val action = varchar("action", 64)
    val subject = varchar("subject", 160).nullable()
    val ip = varchar("ip", 64).nullable()
    val detail = varchar("detail", 255).nullable()
}
