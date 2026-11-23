package com.tc.gymtrainer.api

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.service.AdminAuth
import com.tc.gymtrainer.service.CatalogEdit
import com.tc.gymtrainer.service.ExerciseEdit
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

private data class Login(val login: String, val password: String)
private data class GroupInput(val name: String, val description: String? = null)

internal fun Route.adminRoutes(services: TrainingServices, auth: AdminAuth) {
    val settings = services.settings
    route("/admin/v1") {
        post("/login") {
            services.limiter.check("admin-login", call.clientIp, settings.adminLoginLimit)
            val input = call.receive<Login>()
            val token = auth.login(input.login, input.password, call.client)
            services.housekeeping.runIfDue()
            services.audit.record(ADMIN, if (token == null) "admin.login.fail" else "admin.login.ok", ip = call.clientIp)
            if (token == null) call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Неверный логин или пароль."))
            else call.respond(mapOf("token" to token))
        }
        // A page that failed to render reports it here, through the same proxy prefix as the admin API.
        post("/client-errors") { call.acceptClientError(services) }
        authenticate(ADMIN) {
            // Every change an administrator makes is journaled: approvals, deactivations, deletions, catalog edits.
            install(auditChanges(services.audit, setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete)))
            post("/logout") {
                auth.logout(checkNotNull(call.principal<String>()))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/trainers") { call.respond(services.admin.trainers()) }
            // The auth bot picks up the message for the trainer on its next check of the queue.
            post("/trainers/{id}/approve") { services.admin.approve(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            post("/trainers/{id}/deactivate") { services.admin.deactivate(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            post("/trainers/{id}/activate") { services.admin.reactivate(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            delete("/trainers/{id}") { services.admin.deleteTrainer(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            get("/exercises") { call.respond(services.catalog.exercises(includeHidden = true)) }
            get("/muscle-groups") { call.respond(services.catalog.listMuscleGroups()) }
            get("/muscle-groups/{groupId}/exercises") { call.respond(services.catalog.exercises(call.id("groupId"), includeHidden = true)) }
            post("/muscle-groups") {
                val input = call.receive<GroupInput>()
                call.respond(HttpStatusCode.Created, services.catalog.createMuscleGroup(input.name, input.description))
            }
            put("/muscle-groups/{id}") {
                val input = call.receive<GroupInput>()
                call.respond(services.catalog.saveMuscleGroup(call.id("id"), input.name, input.description))
            }
            delete("/muscle-groups/{id}") { services.catalog.deleteMuscleGroup(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            post("/exercises") { call.respond(HttpStatusCode.Created, services.catalog.saveExercise(null, call.receive<ExerciseEdit>())) }
            put("/exercises/{id}") { call.respond(services.catalog.saveExercise(call.id("id"), call.receive<ExerciseEdit>())) }
            delete("/exercises/{id}") { services.catalog.deleteExercise(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            get("/goals") { call.respond(services.catalog.goals(includeHidden = true)) }
            post("/goals") { call.respond(HttpStatusCode.Created, services.catalog.saveGoal(null, call.receive<CatalogEdit>())) }
            put("/goals/{id}") { call.respond(services.catalog.saveGoal(call.id("id"), call.receive<CatalogEdit>())) }
            delete("/goals/{id}") { services.catalog.deleteGoal(call.id("id")); call.respond(HttpStatusCode.NoContent) }
        }
    }
}
