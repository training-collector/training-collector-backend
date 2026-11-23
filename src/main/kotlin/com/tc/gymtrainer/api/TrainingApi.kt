package com.tc.gymtrainer.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.config.AdminAccess
import com.tc.gymtrainer.service.AdminAuth
import com.tc.gymtrainer.domain.ClientProfile
import com.tc.gymtrainer.domain.ExerciseDraft
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
private data class TrainerNameInput(val firstName: String, val lastName: String)
private data class LoginInput(val username: String, val resend: Boolean = false)
private data class VerifyCodeInput(val code: String, val challenge: String? = null, val username: String? = null)
private data class ReplaceTokenInput(val newToken: String)
private data class ClientInput(
    val firstName: String? = null, val lastName: String? = null, val fullName: String? = null,
    val goal: String? = null, val notes: String? = null,
) {
    private var phoneIncluded = false
    var phone: String? = null
        set(value) {
            field = value
            phoneIncluded = true
        }

    fun namedProfile(previous: ClientProfile? = null): ClientProfile {
        if (firstName.isNullOrBlank()) throw ValidationException("Введите имя подопечного.")
        return (previous ?: ClientProfile(firstName)).copy(firstName = firstName, lastName = lastName,
            phone = if (phoneIncluded) phone else previous?.phone, goal = goal, notes = notes)
    }
    fun legacyName(): String? {
        if (fullName != null && (firstName != null || lastName != null)) throw ValidationException("Передайте имя и фамилию отдельно.")
        return fullName
    }
}
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

fun Application.trainingApi(services: TrainingServices, keys: BotAccess, admin: AdminAccess = AdminAccess()) {
    val adminAuth = AdminAuth(admin)
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
        bearer("admin") { authenticate { if (adminAuth.valid(it.token)) it.token else null } }
        botAuthentication(keys, services)
        bearer("trainer") {
            realm = "Training API"
            authenticate { services.trainers.authenticate(it.token) }
        }
    }
    routing {
        adminRoutes(services, adminAuth)
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
                call.respond(services.loginCodes.request(input.username, input.resend))
            }
            post("/auth/login/verify") {
                val input = call.receive<VerifyCodeInput>()
                call.respond(services.loginCodes.verify(input.challenge, input.code, input.username))
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
    put("/trainers/me") {
        val name = call.receive<TrainerNameInput>()
        call.respond(services.trainers.updateName(call.trainer.id, name.firstName, name.lastName))
    }
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
    get("/catalog/muscle-groups/{groupId}/exercises") { call.respond(services.catalog.exercises(call.id("groupId"))) }
    get("/catalog/exercises") { call.respond(services.catalog.exercises()) }
    get("/catalog/goals") { call.respond(services.catalog.goals()) }
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
            val legacy = input.legacyName()
            val client = if (legacy != null) clients.create(call.trainer.id, legacy, input.phone, input.goal, input.notes)
                else {
                    if (input.lastName.isNullOrBlank()) throw ValidationException("Введите фамилию подопечного.")
                    clients.create(call.trainer.id, input.namedProfile())
                }
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
            val id = call.id("clientId")
            val legacy = input.legacyName()
            val client = if (legacy != null) clients.update(call.trainer.id, id, legacy, input.phone, input.goal, input.notes)
                else clients.updateProfile(call.trainer.id, id) { current -> input.namedProfile(current) }
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
