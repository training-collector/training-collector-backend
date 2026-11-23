package com.tc.gymtrainer.api

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.config.BotAccess
import com.tc.gymtrainer.domain.*
import com.tc.gymtrainer.service.WebhookStore
import com.tc.gymtrainer.service.UpdateResult
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.time.LocalDate

private data class Identity(val username: String)
private data class Link(val token: String, val sender: Long)
private data class Challenge(val challenge: String)
private data class Delivery(val challenge: String, val code: String)
private data class NewClient(val profile: ClientProfile, val measurement: MeasurementInput? = null)
private data class MeasurementEdit(val input: MeasurementInput, val expected: BodyMeasurement)

private fun matches(expected: String, supplied: String): Boolean = expected.isNotBlank() &&
    MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())

private suspend fun TrainingServices.linkedTrainer(sender: Long) =
    trainers.findByTelegramUserId(sender)?.takeIf {
        it.approvedAt != null && it.telegramLinkedAt != null && it.trainingBotLinkedAt != null
    }

internal fun AuthenticationConfig.botAuthentication(keys: BotAccess, services: TrainingServices) {
    bearer("auth-bot") { authenticate { if (matches(keys.authKey, it.token)) BotRole.LOGIN_CODES else null } }
    bearer("training-bot") { authenticate { if (matches(keys.trainingKey, it.token)) BotRole.TRAINING else null } }
    bearer("training-chat") {
        authenticate { credentials ->
            if (matches(keys.trainingKey, credentials.token)) services.linkedTrainer(id("sender")) else null
        }
    }
}

private suspend fun ApplicationCall.identity(role: BotRole) {
    val name = receive<Identity>().username
    if (!name.matches(Regex("[A-Za-z0-9_]{1,32}"))) throw ValidationException("Некорректное имя бота.")
    role.record(name)
    respond(HttpStatusCode.NoContent)
}

private suspend inline fun <reified T : Any> ApplicationCall.optional(value: T?) {
    if (value == null) respond(HttpStatusCode.NoContent) else respond(value)
}

private fun Route.updateRoutes(role: BotRole) {
    val store = WebhookStore(role)
    route("/updates/{sender}/{update}") {
        fun ApplicationCall.updateId() = parameters["update"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw ValidationException("Некорректный update_id.")
        post("/begin") { call.respond(store.begin(call.id("sender"), call.updateId())) }
        post("/checkpoint") {
            store.checkpoint(call.id("sender"), call.updateId(), call.receive<UpdateResult>())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/finish") {
            store.finish(call.id("sender"), call.updateId(), call.receive<UpdateResult>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

internal fun Route.botRoutes(services: TrainingServices) {
    route("/internal/v1/auth") {
        authenticate("auth-bot") {
            updateRoutes(BotRole.LOGIN_CODES)
            post("/identity") { call.identity(BotRole.LOGIN_CODES) }
            post("/signup") {
                val input = call.receive<Link>()
                call.respond(mapOf("status" to services.telegramLinks.confirmSignup(input.token, input.sender)))
            }
            post("/chats/{sender}/activate") {
                val sender = call.id("sender")
                val trainer = services.trainers.findByTelegramUserId(sender)
                val pending = trainer?.telegramLinkedAt != null && trainer.approvedAt == null && trainer.deactivatedAt == null
                call.respond(mapOf("active" to services.dispatch.change { services.loginCodes.activateChat(sender) }, "pending" to pending,
                    "deactivated" to (trainer?.deactivatedAt != null), "username" to trainer?.username))
            }
            post("/deliveries/delay") { call.respond(mapOf("seconds" to services.dispatch.nextDelay())) }
            post("/deliveries/next") { call.optional(services.loginCodes.nextDelivery()) }
            post("/notifications/next") { call.optional(services.admin.nextNotification()) }
            post("/notifications/{id}/complete") {
                services.admin.acknowledgeNotification(call.id("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            post("/deliveries/prepare") {
                val input = call.receive<Delivery>()
                call.respond(mapOf("ready" to services.loginCodes.prepareDelivery(input.challenge, input.code)))
            }
            post("/deliveries/heading") {
                services.loginCodes.markHeadingSent(call.receive<Challenge>().challenge)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/deliveries/complete") {
                services.loginCodes.delivered(call.receive<Challenge>().challenge)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    route("/internal/v1/training") {
        authenticate("training-bot") {
            updateRoutes(BotRole.TRAINING)
            post("/identity") { call.identity(BotRole.TRAINING) }
            post("/connect") {
                val input = call.receive<Link>()
                call.writeResult { services.telegramLinks.connectTrainingBot(input.token, input.sender) }
            }
            get("/linked/{sender}") { call.optional(services.linkedTrainer(call.id("sender"))) }
        }
        route("/chats/{sender}") {
            authenticate("training-chat") {
                catalogRoutes(services)
                clientRoutes(services.clients)
                workoutRoutes(services.workouts)
                measurementRoutes(services.measurements)
                get("/catalog/exercises/{id}") { call.respond(services.catalog.getExercise(call.id("id"))) }
                post("/clients/profile") {
                    val input = call.receive<NewClient>()
                    call.writeResult { services.clients.create(call.trainer.id, input.profile, input.measurement) }
                }
                get("/measurements/{id}") { call.respond(services.measurements.get(call.trainer.id, call.id("id"))) }
                put("/measurements/{id}/revision") {
                    val edit = call.receive<MeasurementEdit>()
                    call.writeResult { services.measurements.update(call.trainer.id, call.id("id"), edit.input, edit.expected) }
                }
                workoutDialogRoutes(services)
            }
        }
    }
}

private fun Route.workoutDialogRoutes(services: TrainingServices) {
    val workouts = services.workouts
    get("/clients/{id}/workouts/active") { call.optional(workouts.activeForClient(call.trainer.id, call.id("id"))) }
    delete("/clients/{id}/workouts/active/{sessionId}") {
        call.writeResult { workouts.cancelActiveWorkout(call.trainer.id, call.id("id"), call.id("sessionId")) }
    }
    post("/clients/{id}/workouts/start") { call.writeResult { workouts.startOrResume(call.trainer.id, call.id("id")) } }
    route("/workouts/{id}") {
        post("/skip-template") {
            call.writeResult(HttpStatusCode.NoContent) { workouts.skipLegacyTemplate(call.trainer.id, call.id("id")) }
        }
        post("/select/{catalog}") {
            call.writeResult(HttpStatusCode.NoContent) { workouts.selectExercise(call.trainer.id, call.id("id"), call.id("catalog")) }
        }
        post("/sets/{exercise}/append") {
            call.writeResult(HttpStatusCode.NoContent) { workouts.appendActiveSets(call.trainer.id, call.id("id"), call.id("exercise"), call.receive<List<SetDraft>>()) }
        }
        post("/sets/{exercise}/undo") {
            call.writeResult(HttpStatusCode.NoContent) { workouts.undoActiveSet(call.trainer.id, call.id("id"), call.id("exercise")) }
        }
        post("/sets/{exercise}/finish") {
            call.writeResult(HttpStatusCode.NoContent) { workouts.finishExercise(call.trainer.id, call.id("id"), call.id("exercise")) }
        }
        post("/another") {
            call.writeResult(HttpStatusCode.NoContent) { workouts.chooseAnotherExercise(call.trainer.id, call.id("id")) }
        }
        post("/finish") {
            val minutes = call.request.queryParameters["durationMin"]?.let {
                it.toIntOrNull() ?: throw ValidationException("Введите продолжительность в минутах целым числом.")
            }
            call.writeResult { workouts.finishWorkout(call.trainer.id, call.id("id"), minutes) }
        }
        put("/date") {
            val date = call.receive<DateEdit>().date
            call.writeResult { workouts.updateSession(call.trainer.id, call.id("id")) { it.copy(performedAt = it.performedAt.with(date)) } }
        }
        put("/duration") {
            val duration = call.receive<DurationEdit>()
            call.writeResult { workouts.updateSession(call.trainer.id, call.id("id")) { it.copy(durationMin = duration.minutes) } }
        }
        put("/note") {
            val note = call.receive<NoteEdit>()
            call.writeResult { workouts.updateSession(call.trainer.id, call.id("id")) { it.copy(note = note.text) } }
        }
    }
}

private data class DateEdit(val date: LocalDate)
private data class DurationEdit(val minutes: Int?)
private data class NoteEdit(val text: String?)
