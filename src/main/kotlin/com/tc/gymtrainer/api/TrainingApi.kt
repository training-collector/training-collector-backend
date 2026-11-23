package com.tc.gymtrainer.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.config.AdminAccess
import com.tc.gymtrainer.config.BotAccess
import com.tc.gymtrainer.domain.ClientProfile
import com.tc.gymtrainer.domain.ExerciseDraft
import com.tc.gymtrainer.domain.MeasurementInput
import com.tc.gymtrainer.domain.SetDraft
import com.tc.gymtrainer.domain.Sex
import com.tc.gymtrainer.domain.WorkoutDraft
import com.tc.gymtrainer.service.Access
import com.tc.gymtrainer.service.AccessDeniedException
import com.tc.gymtrainer.service.AdminAuth
import com.tc.gymtrainer.service.BotUnavailableException
import com.tc.gymtrainer.service.ConflictException
import com.tc.gymtrainer.service.LoginState
import com.tc.gymtrainer.service.NotFoundException
import com.tc.gymtrainer.service.TooManyRequestsException
import com.tc.gymtrainer.service.TrainerPrincipal
import com.tc.gymtrainer.service.ValidationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
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
private data class TimeZoneInput(val timeZone: String)
private data class LoginInput(val username: String)
private data class LoginStatusInput(val secret: String)

/** A client card. On update the fields set only in the bot keep their values unless they are sent. */
private data class ClientInput(
    val firstName: String, val lastName: String? = null, val goal: String? = null, val notes: String? = null,
    val sex: Sex? = null, val age: Int? = null, val telegramUsername: String? = null,
    val measurement: MeasurementInput? = null,
) {
    fun profile(previous: ClientProfile? = null) = ClientProfile(firstName, lastName, sex ?: previous?.sex, age ?: previous?.age,
        telegramUsername ?: previous?.telegramUsername, goal, notes)
}

private data class ExerciseInput(
    val exerciseName: String? = null,
    val exerciseCatalogId: Long? = null,
    val sets: List<SetDraft>,
    val note: String? = null,
) {
    fun toDraft() = ExerciseDraft(exerciseCatalogId, exerciseName, note, sets)
}

private data class SessionInput(val performedAt: OffsetDateTime, val durationMin: Int? = null, val note: String? = null)

private data class WorkoutInput(
    val performedAt: OffsetDateTime,
    val exercises: List<ExerciseInput>,
    val durationMin: Int? = null,
    val note: String? = null,
)

private fun ApplicationCall.instant(name: String): OffsetDateTime? =
    request.queryParameters[name]?.let {
        try {
            OffsetDateTime.parse(it)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Параметр $name должен содержать дату и время с часовым поясом.")
        }
    }

private fun ApplicationCall.requireSession() {
    if (principalOfTrainer.access != Access.SESSION) throw AccessDeniedException("Войдите в кабинет и подтвердите вход в Telegram.")
}

fun Application.trainingApi(services: TrainingServices, keys: BotAccess, admin: AdminAccess = AdminAccess(), clientIpHeader: String = "") {
    val settings = services.settings
    val limiter = services.limiter
    val adminAuth = AdminAuth(admin, settings, services.secrets)
    // Authenticated traffic is limited per trainer; an unfinished registration cannot open the cabinet.
    val trainerLimit = createRouteScopedPlugin("TrainerLimit") {
        on(AuthenticationChecked) { call ->
            val trainer = call.principal<TrainerPrincipal>()?.trainer ?: return@on
            limiter.checkLocal("api", trainer.id, settings.apiLimit)
        }
    }
    val approvedRegistration = createRouteScopedPlugin("ApprovedRegistration") {
        on(AuthenticationChecked) { call ->
            val principal = call.principal<TrainerPrincipal>() ?: return@on
            if (principal.trainer.approvedAt == null || principal.trainer.telegramLinkedAt == null || principal.access == Access.SIGNUP) {
                throw AccessDeniedException("Для доступа привяжите Telegram и подтвердите вход в боте.")
            }
        }
    }
    install(requestLogging(settings, clientIpHeader))
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
        exception<ConflictException> { call, e -> call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message)) }
        exception<BotUnavailableException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message)) }
        exception<RequestRejected> { call, e -> call.respond(e.status, mapOf("error" to e.message)) }
        exception<TooManyRequestsException> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to e.message))
        }
        exception<Throwable> { call, e ->
            logFailure(call, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Внутренняя ошибка сервера."))
        }
    }
    install(Authentication) {
        bearer(ADMIN) { authenticate { if (adminAuth.valid(it.token)) it.token else null } }
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
                limiter.check("register", call.clientIp, settings.registerLimit)
                val input = call.receive<TrainerInput>()
                val registration = services.trainers.registerForWeb(input.username, input.firstName, input.lastName, call.client)
                services.housekeeping.runIfDue()
                call.audit(services.audit, "signup.create", "trainer:${registration.trainer.id}")
                call.respond(HttpStatusCode.Created, registration)
            }
            post("/auth/login") {
                limiter.check("login", call.clientIp, settings.loginRequestLimit)
                val input = call.receive<LoginInput>()
                val start = services.logins.request(input.username, call.client)
                services.housekeeping.runIfDue()
                call.audit(services.audit, "login.request", input.username.singleLine(64))
                call.respond(start)
            }
            // The page asks every few seconds whether the trainer has answered in Telegram.
            post("/auth/login/status") {
                limiter.checkLocal("login-status", call.clientIp, settings.loginStatusLimit)
                val status = services.logins.status(call.receive<LoginStatusInput>().secret, call.client)
                if (status.status == LoginState.APPROVED) call.audit(services.audit, "login.ok", "trainer:${status.trainer?.id}")
                call.respond(status)
            }
            // A page that failed to render reports it here: hosted logs are the only trace of it.
            post("/client-errors") { call.acceptClientError(services) }
            authenticate("trainer") {
                install(trainerLimit)
                get("/trainers/me") { call.respond(call.trainer) }
                post("/auth/logout") {
                    services.trainers.logout(call.principalOfTrainer, call.bearer)
                    call.audit(services.audit, "session.revoke")
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/trainers/me/telegram-link") { call.respond(services.telegramLinks.createSignupLink(call.trainer.id)) }
            }
            authenticate("trainer") {
                install(trainerLimit)
                install(approvedRegistration)
                install(auditChanges(services.audit, setOf(HttpMethod.Delete)))
                trainingRoutes(services)
            }
        }
    }
}

private fun Route.trainingRoutes(services: TrainingServices) {
    put("/trainers/me") {
        val name = call.receive<TrainerNameInput>()
        val saved = services.trainers.updateName(call.trainer.id, name.firstName, name.lastName)
        call.audit(services.audit, "trainer.rename")
        call.respond(saved)
    }
    put("/trainers/me/time-zone") {
        call.respond(services.trainers.updateTimeZone(call.trainer.id, call.receive<TimeZoneInput>().timeZone))
    }
    get("/trainers/me/bot-links") {
        call.respond(services.telegramLinks.profileLinks(call.trainer.trainingBotLinkedAt != null))
    }
    post("/trainers/me/training-bot-link") {
        call.requireSession()
        call.respond(services.telegramLinks.createTrainingLink(call.trainer.id))
    }
    post("/trainers/me/api-key") {
        call.requireSession()
        val key = services.trainers.issueApiToken(call.trainer.id)
        call.audit(services.audit, "apikey.issue")
        call.respond(key)
    }
    post("/trainers/me/token") {
        if (call.principalOfTrainer.access != Access.API_KEY) throw AccessDeniedException("Передайте действующий ключ API.")
        val key = services.trainers.rotateApiToken(call.trainer.id, call.bearer)
        call.audit(services.audit, "apikey.rotate")
        call.respond(key)
    }
    catalogRoutes(services)
    clientRoutes(services)
    workoutRoutes(services)
    measurementRoutes(services)
}

internal fun Route.catalogRoutes(services: TrainingServices) {
    get("/catalog/muscle-groups") { call.respond(services.catalog.listMuscleGroups()) }
    get("/catalog/muscle-groups/{groupId}/exercises") { call.respond(services.catalog.exercises(call.id("groupId"))) }
    get("/catalog/exercises") { call.respond(services.catalog.exercises()) }
    get("/catalog/exercises/{id}") { call.respond(services.catalog.getExercise(call.id("id"))) }
    get("/catalog/goals") { call.respond(services.catalog.goals()) }
}

internal fun Route.clientRoutes(services: TrainingServices) {
    val clients = services.clients
    route("/clients") {
        get {
            val includeArchived = call.request.queryParameters["includeArchived"]?.let {
                it.toBooleanStrictOrNull() ?: throw ValidationException("includeArchived принимает true или false.")
            } ?: false
            call.respond(clients.list(call.trainer.id, includeArchived))
        }
        post {
            val input = call.receive<ClientInput>()
            if (input.lastName.isNullOrBlank()) throw ValidationException("Введите фамилию подопечного.")
            call.writeResult(HttpStatusCode.Created) { clients.create(call.trainer.id, input.profile(), input.measurement) }
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
            if (input.measurement != null) throw ValidationException("Замер добавляется отдельным запросом.")
            call.respond(clients.update(call.trainer.id, call.id("clientId")) { current -> input.profile(current) })
        }
    }
}

internal fun Route.workoutRoutes(services: TrainingServices) {
    val workouts = services.workouts
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
        call.writeResult { workouts.updateSession(call.trainer.id, call.id("workoutId"), input.performedAt, input.durationMin, input.note) }
    }
    delete("/workouts/{workoutId}") {
        call.writeResult(HttpStatusCode.NoContent) { workouts.deleteSession(call.trainer.id, call.id("workoutId")) }
    }
    post("/workouts/{workoutId}/exercises") {
        val input = call.receive<ExerciseInput>()
        if (input.sets.isEmpty()) throw ValidationException("Добавьте хотя бы один подход.")
        call.writeResult(HttpStatusCode.Created) { workouts.addExercise(call.trainer.id, call.id("workoutId"), input.toDraft()) }
    }
    delete("/exercises/{exerciseId}") {
        call.writeResult(HttpStatusCode.NoContent) { workouts.deleteExercise(call.trainer.id, call.id("exerciseId")) }
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
        call.writeResult(HttpStatusCode.NoContent) { workouts.updateSet(call.trainer.id, call.id("setId"), set) }
    }
}

internal fun Route.measurementRoutes(services: TrainingServices) {
    val measurements = services.measurements
    get("/measurements/{measurementId}") { call.respond(measurements.get(call.trainer.id, call.id("measurementId"))) }
    put("/measurements/{measurementId}") {
        val input = call.receive<MeasurementInput>()
        // The bot passes the version it showed, so an edit made elsewhere in the meantime is not overwritten.
        val expected = call.request.queryParameters["expectedVersion"]?.let {
            it.toIntOrNull() ?: throw ValidationException("expectedVersion должен быть целым числом.")
        }
        call.writeResult { measurements.update(call.trainer.id, call.id("measurementId"), input, expected) }
    }
    delete("/measurements/{measurementId}") {
        call.writeResult(HttpStatusCode.NoContent) { measurements.delete(call.trainer.id, call.id("measurementId")) }
    }
    route("/clients/{clientId}/measurements") {
        post {
            val input = call.receive<MeasurementInput>()
            call.writeResult(HttpStatusCode.Created) { measurements.add(call.trainer.id, call.id("clientId"), input) }
        }
        get { call.respond(measurements.history(call.trainer.id, call.id("clientId"))) }
        get("/current") { call.respond(measurements.current(call.trainer.id, call.id("clientId"))) }
    }
}
