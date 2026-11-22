package com.tc.gymtrainer.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.domain.ExerciseDraft
import com.tc.gymtrainer.domain.Goal
import com.tc.gymtrainer.domain.MeasurementInput
import com.tc.gymtrainer.domain.SetDraft
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.domain.WorkoutDraft
import com.tc.gymtrainer.service.ClientService
import com.tc.gymtrainer.service.WorkoutService
import com.tc.gymtrainer.service.MeasurementService
import com.tc.gymtrainer.service.AccessDeniedException
import com.tc.gymtrainer.service.BotUnavailableException
import com.tc.gymtrainer.service.ConflictException
import com.tc.gymtrainer.service.LoginException
import com.tc.gymtrainer.service.NotFoundException
import com.tc.gymtrainer.service.ValidationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.createRouteScopedPlugin
import com.tc.gymtrainer.config.BotAccess
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private data class TrainerInput(val username: String, val firstName: String, val lastName: String)
private data class LoginInput(val username: String)
private data class VerifyCodeInput(val challenge: String, val code: String)
private data class ReplaceTokenInput(val newToken: String)
private data class ClientInput(val fullName: String, val phone: String? = null, val goal: Goal? = null, val notes: String? = null)
private data class CatalogExerciseInput(val name: String, val muscleGroupId: Long? = null, val description: String? = null)
private data class EditCatalogExerciseInput(val name: String, val description: String? = null)
private data class ExerciseInput(
    val muscleGroupId: Long? = null,
    val exerciseName: String? = null,
    val exerciseCatalogId: Long? = null,
    val sets: List<SetDraft>,
    val note: String? = null,
) {
    fun toDraft() = ExerciseDraft(muscleGroupId, exerciseCatalogId, exerciseName, note, sets)
}

private data class SessionInput(val performedAt: OffsetDateTime, val durationMin: Int? = null, val note: String? = null)

private data class WorkoutInput(
    val performedAt: OffsetDateTime,
    val exercises: List<ExerciseInput>,
    val durationMin: Int? = null,
    val note: String? = null,
)

internal val ApplicationCall.trainer: Trainer get() = checkNotNull(principal<Trainer>())
private val ApplicationCall.bearer: String get() = request.headers[HttpHeaders.Authorization]!!.substringAfter(' ').trim()

private val approvedRegistration = createRouteScopedPlugin("ApprovedRegistration") {
    on(AuthenticationChecked) { call ->
        val trainer = call.principal<Trainer>() ?: return@on
        if (trainer.approvedAt == null || trainer.telegramLinkedAt == null || call.bearer.startsWith("signup.")) {
            throw AccessDeniedException("Для доступа привяжите Telegram и войдите по одноразовому коду.")
        }
    }
}

internal fun ApplicationCall.id(name: String): Long =
    parameters[name]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ValidationException("Некорректный идентификатор: $name.")

private fun ApplicationCall.instant(name: String): OffsetDateTime? =
    request.queryParameters[name]?.let {
        try {
            OffsetDateTime.parse(it)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Параметр $name должен содержать дату и время с часовым поясом.")
        }
    }

fun Application.trainingApi(services: TrainingServices, keys: BotAccess) {
    install(createApplicationPlugin("PrivateApiResponses") {
        onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-store") }
    })
    install(ContentNegotiation) {
        jackson {
            registerModule(KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        }
    }
    install(StatusPages) {
        exception<ValidationException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный запрос или JSON.")) }
        exception<UnsupportedMediaTypeException> { call, _ -> call.respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to "Ожидается application/json.")) }
        exception<NotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message)) }
        exception<AccessDeniedException> { call, e -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message)) }
        exception<LoginException> { call, e -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message)) }
        exception<ConflictException> { call, e -> call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message)) }
        exception<BotUnavailableException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message)) }
    }
    install(Authentication) {
        botAuthentication(keys, services)
        bearer("trainer") {
            realm = "Training API"
            authenticate { services.trainers.authenticate(it.token) }
        }
    }
    routing {
        botRoutes(services)
        get("/health") { call.respondText("OK") }
        route("/api/v1") {
            post("/trainers") {
                val input = call.receive<TrainerInput>()
                val registration = services.trainers.registerForWeb(input.username, input.firstName, input.lastName)
                call.respond(HttpStatusCode.Created, registration)
            }
            post("/auth/login") {
                val input = call.receive<LoginInput>()
                call.respond(services.loginCodes.request(input.username))
            }
            post("/auth/login/verify") {
                val input = call.receive<VerifyCodeInput>()
                call.respond(services.loginCodes.verify(input.challenge, input.code))
            }
            authenticate("trainer") {
                get("/trainers/me") { call.respond(call.trainer) }
                post("/auth/logout") {
                    services.trainers.logout(call.bearer)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/trainers/me/telegram-link") {
                    if (!call.bearer.startsWith("signup.") && call.trainer.telegramLinkedAt != null) {
                        throw AccessDeniedException("Эта ссылка предназначена для регистрации.")
                    }
                    call.respond(services.telegramLinks.createSignupLink(call.trainer.id))
                }
            }
            authenticate("trainer") {
                install(approvedRegistration)
                trainingRoutes(services)
            }
        }
    }
}

private fun Route.trainingRoutes(services: TrainingServices) {
    get("/trainers/me/bot-links") {
        call.respond(services.telegramLinks.profileLinks(call.trainer.trainingBotLinkedAt != null))
    }
    post("/trainers/me/training-bot-link") {
        if (!call.bearer.startsWith("session.")) throw AccessDeniedException("Войдите в кабинет по одноразовому коду.")
        call.respond(services.telegramLinks.createTrainingLink(call.trainer.id))
    }
    post("/trainers/me/api-key") {
        if (!call.bearer.startsWith("session.")) throw AccessDeniedException("Войдите в кабинет по одноразовому коду.")
        call.respond(mapOf("token" to services.trainers.issueApiToken(call.trainer.id)))
    }
    post("/trainers/me/token") {
        val replacement = call.receive<ReplaceTokenInput>().newToken
        services.trainers.replaceApiToken(call.trainer.id, call.bearer, replacement)
        call.respond(HttpStatusCode.NoContent)
    }
    catalogRoutes(services)
    clientRoutes(services.clients)
    workoutRoutes(services.workouts)
    measurementRoutes(services.measurements)
}

internal fun Route.catalogRoutes(services: TrainingServices) {
    get("/catalog/muscle-groups") { call.respond(services.catalog.listMuscleGroups()) }
    get("/catalog/muscle-groups/{groupId}/exercises") {
        call.respond(services.catalog.exercisesFor(call.trainer.id, call.id("groupId")))
    }
    get("/catalog/exercises") { call.respond(services.catalog.exercisesFor(call.trainer.id)) }
    post("/catalog/exercises") {
        val input = call.receive<CatalogExerciseInput>()
        call.respond(services.catalog.addExercise(call.trainer.id, input.muscleGroupId, input.name, input.description))
    }
    put("/catalog/exercises/{id}") {
        val input = call.receive<EditCatalogExerciseInput>()
        call.respond(services.catalog.updateExercise(call.trainer.id, call.id("id"), input.name, input.description))
    }
}

internal fun Route.clientRoutes(clients: ClientService) {
    route("/clients") {
        get {
            val includeArchived = call.request.queryParameters["includeArchived"]?.let {
                it.toBooleanStrictOrNull() ?: throw ValidationException("includeArchived принимает true или false.")
            } ?: false
            call.respond(clients.list(call.trainer.id, includeArchived))
        }
        post {
            val input = call.receive<ClientInput>()
            val client = clients.create(call.trainer.id, input.fullName, input.phone, input.goal, input.notes)
            call.respond(HttpStatusCode.Created, client)
        }
        get("/{clientId}") { call.respond(clients.getOwned(call.trainer.id, call.id("clientId"))) }
        post("/{clientId}/archive") { call.respond(clients.archive(call.trainer.id, call.id("clientId"))) }
        post("/{clientId}/restore") { call.respond(clients.unarchive(call.trainer.id, call.id("clientId"))) }
        delete("/{clientId}") {
            clients.delete(call.trainer.id, call.id("clientId"))
            call.respond(HttpStatusCode.NoContent)
        }
        put("/{clientId}") {
            val input = call.receive<ClientInput>()
            val client = clients.update(call.trainer.id, call.id("clientId"), input.fullName, input.phone, input.goal, input.notes)
            call.respond(client)
        }
    }
}

internal fun Route.workoutRoutes(workouts: WorkoutService) {
    route("/clients/{clientId}/workouts") {
        post {
            val input = call.receive<WorkoutInput>()
            if (input.exercises.isEmpty() || input.exercises.any { it.sets.isEmpty() }) {
                throw ValidationException("Укажите хотя бы одно упражнение и хотя бы один подход для каждого упражнения.")
            }
            val draft = WorkoutDraft(
                clientId = call.id("clientId"),
                performedAt = input.performedAt,
                durationMin = input.durationMin,
                note = input.note,
                exercises = input.exercises.map { it.toDraft() },
            )
            call.respond(HttpStatusCode.Created, workouts.create(call.trainer.id, draft))
        }
        get {
            val from = call.instant("from")
            val to = call.instant("to")
            if (from != null && to != null && !from.isBefore(to)) throw ValidationException("from должен быть раньше to.")
            val exercise = call.request.queryParameters["exercise"]
            call.respond(workouts.history(call.trainer.id, call.id("clientId"), from, to, exercise))
        }
    }
    get("/workouts/{workoutId}") { call.respond(workouts.getDetail(call.trainer.id, call.id("workoutId"))) }
    put("/workouts/{workoutId}") {
        val input = call.receive<SessionInput>()
        call.respond(workouts.updateSession(call.trainer.id, call.id("workoutId"), input.performedAt, input.durationMin, input.note))
    }
    delete("/workouts/{workoutId}") {
        workouts.deleteSession(call.trainer.id, call.id("workoutId"))
        call.respond(HttpStatusCode.NoContent)
    }
    post("/workouts/{workoutId}/exercises") {
        val input = call.receive<ExerciseInput>()
        if (input.sets.isEmpty()) throw ValidationException("Добавьте хотя бы один подход.")
        call.respond(HttpStatusCode.Created, workouts.addExercise(call.trainer.id, call.id("workoutId"), input.toDraft()))
    }
    delete("/exercises/{exerciseId}") {
        workouts.deleteExercise(call.trainer.id, call.id("exerciseId"))
        call.respond(HttpStatusCode.NoContent)
    }
    post("/exercises/{exerciseId}/sets") {
        call.respond(HttpStatusCode.Created, workouts.addSet(call.trainer.id, call.id("exerciseId"), call.receive<SetDraft>()))
    }
    delete("/sets/{setId}") {
        workouts.deleteSet(call.trainer.id, call.id("setId"))
        call.respond(HttpStatusCode.NoContent)
    }
    put("/sets/{setId}") {
        val set = call.receive<SetDraft>()
        workouts.updateSet(call.trainer.id, call.id("setId"), set.weightKg, set.reps, set.restSec, set.rpe, set.durationSec)
        call.respond(HttpStatusCode.NoContent)
    }
}

internal fun Route.measurementRoutes(measurements: MeasurementService) {
    put("/measurements/{measurementId}") {
        call.respond(measurements.update(call.trainer.id, call.id("measurementId"), call.receive<MeasurementInput>()))
    }
    delete("/measurements/{measurementId}") {
        measurements.delete(call.trainer.id, call.id("measurementId"))
        call.respond(HttpStatusCode.NoContent)
    }
    route("/clients/{clientId}/measurements") {
        post {
            val input = call.receive<MeasurementInput>()
            call.respond(HttpStatusCode.Created, measurements.add(call.trainer.id, call.id("clientId"), input))
        }
        get { call.respond(measurements.history(call.trainer.id, call.id("clientId"))) }
        get("/current") { call.respond(measurements.current(call.trainer.id, call.id("clientId"))) }
    }
}
