package com.tc.gymtrainer.api

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.config.BotAccess
import com.tc.gymtrainer.domain.SetDraft
import com.tc.gymtrainer.service.Access
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.TrainerPrincipal
import com.tc.gymtrainer.service.UpdateResult
import com.tc.gymtrainer.service.ValidationException
import com.tc.gymtrainer.service.WebhookStore
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest

private data class Identity(val username: String)
private data class Link(val token: String, val sender: Long)
private data class Delivered(val id: String)
private data class Decision(val sender: Long)

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
            if (!matches(keys.trainingKey, credentials.token)) null
            else services.linkedTrainer(id("sender"))?.let { TrainerPrincipal(it, Access.BOT) }
        }
    }
}

private suspend fun ApplicationCall.identity(role: BotRole) {
    role.record(receive<Identity>().username)
    respond(HttpStatusCode.NoContent)
}

private suspend inline fun <reified T : Any> ApplicationCall.optional(value: T?) {
    if (value == null) respond(HttpStatusCode.NoContent) else respond(value)
}

private fun Route.updateRoutes(role: BotRole, services: TrainingServices) {
    val store = WebhookStore(role, services.settings)
    route("/updates/{sender}/{update}") {
        fun ApplicationCall.updateId() = parameters["update"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw ValidationException("Некорректный update_id.")
        post("/begin") {
            val sender = call.id("sender")
            val lease = store.begin(sender, call.updateId())
            services.housekeeping.runIfDue()
            // The training bot needs the trainer for every update: one call instead of two.
            call.respond(if (role == BotRole.TRAINING && lease.lease != null) lease.copy(trainer = services.linkedTrainer(sender)) else lease)
        }
        post("/checkpoint") {
            store.checkpoint(call.id("sender"), call.updateId(), call.receive<UpdateResult>())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/release") {
            store.release(call.id("sender"), call.receive<UpdateResult>())
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
            updateRoutes(BotRole.LOGIN_CODES, services)
            post("/identity") { call.identity(BotRole.LOGIN_CODES) }
            post("/signup") {
                val input = call.receive<Link>()
                val status = services.telegramLinks.confirmSignup(input.token, input.sender)
                call.audit(services.audit, "telegram.link", "telegram:${input.sender}", status.name)
                call.respond(mapOf("status" to status))
            }
            post("/chats/{sender}/activate") {
                val sender = call.id("sender")
                val trainer = services.trainers.findByTelegramUserId(sender)
                val pending = trainer?.telegramLinkedAt != null && trainer.approvedAt == null && trainer.deactivatedAt == null
                // A request made before the chat was opened is delivered from now on.
                val active = services.logins.activateChat(sender)
                call.respond(mapOf("active" to active, "pending" to pending,
                    "deactivated" to (trainer?.deactivatedAt != null), "username" to trainer?.username))
            }
            post("/deliveries/next") { call.optional(services.logins.nextDelivery()) }
            post("/deliveries/complete") {
                services.logins.delivered(call.receive<Delivered>().id)
                call.respond(HttpStatusCode.NoContent)
            }
            // The trainer pressed "Разрешить вход" or "Отклонить вход".
            post("/logins/{id}/{decision}") {
                val approve = when (call.parameters["decision"]) {
                    "approve" -> true
                    "deny" -> false
                    else -> throw ValidationException("Некорректное решение.")
                }
                val sender = call.receive<Decision>().sender
                val result = services.logins.decide(call.parameters["id"].orEmpty(), sender, approve)
                call.audit(services.audit, if (approve) "login.approve" else "login.deny", "telegram:$sender", result.name)
                call.respond(mapOf("decision" to result))
            }
            post("/notifications/next") { call.optional(services.admin.nextNotification()) }
            post("/notifications/{id}/complete") {
                services.admin.acknowledgeNotification(call.id("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    route("/internal/v1/training") {
        authenticate("training-bot") {
            updateRoutes(BotRole.TRAINING, services)
            post("/identity") { call.identity(BotRole.TRAINING) }
            post("/connect") {
                val input = call.receive<Link>()
                call.writeResult { services.telegramLinks.connectTrainingBot(input.token, input.sender) }
                call.audit(services.audit, "trainingbot.connect", "telegram:${input.sender}")
            }
        }
        route("/chats/{sender}") {
            authenticate("training-chat") {
                install(auditChanges(services.audit, setOf(HttpMethod.Delete)))
                catalogRoutes(services)
                clientRoutes(services)
                workoutRoutes(services)
                measurementRoutes(services)
                workoutDialogRoutes(services)
            }
        }
    }
}

/** Step-by-step recording of a workout from the bot. Every write is replay-safe. */
private fun Route.workoutDialogRoutes(services: TrainingServices) {
    val workouts = services.workouts
    get("/clients/{id}/workouts/active") { call.optional(workouts.activeForClient(call.trainer.id, call.id("id"))) }
    delete("/clients/{id}/workouts/active/{sessionId}") {
        call.writeResult { workouts.cancelActiveWorkout(call.trainer.id, call.id("id"), call.id("sessionId")) }
    }
    post("/clients/{id}/workouts/start") { call.writeResult { workouts.startOrResume(call.trainer.id, call.id("id")) } }
    route("/workouts/{id}") {
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
    }
}
