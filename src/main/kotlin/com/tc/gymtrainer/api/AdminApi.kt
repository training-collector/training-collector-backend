package com.tc.gymtrainer.api

import com.tc.gymtrainer.TrainingServices
import com.tc.gymtrainer.service.AdminAuth
import com.tc.gymtrainer.service.CatalogEdit
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

private data class Login(val login: String, val password: String)

internal fun Route.adminRoutes(services: TrainingServices, auth: AdminAuth) {
    route("/admin/v1") {
        post("/login") {
            val input = call.receive<Login>()
            val token = auth.login(input.login, input.password)
            if (token == null) call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Неверный логин или пароль."))
            else call.respond(mapOf("token" to token))
        }
        authenticate("admin") {
            post("/logout") {
                auth.logout(checkNotNull(call.principal<String>()))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/trainers") { call.respond(services.admin.trainers()) }
            post("/trainers/{id}/approve") { services.admin.approve(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            post("/trainers/{id}/deactivate") { services.admin.deactivate(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            delete("/trainers/{id}") { services.admin.deleteTrainer(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            get("/exercises") { call.respond(services.catalog.exercises(includeHidden = true)) }
            post("/exercises") { call.respond(HttpStatusCode.Created, services.catalog.saveExercise(null, call.receive<CatalogEdit>())) }
            put("/exercises/{id}") { call.respond(services.catalog.saveExercise(call.id("id"), call.receive<CatalogEdit>())) }
            delete("/exercises/{id}") { services.catalog.deleteExercise(call.id("id")); call.respond(HttpStatusCode.NoContent) }
            get("/goals") { call.respond(services.catalog.goals(includeHidden = true)) }
            post("/goals") { call.respond(HttpStatusCode.Created, services.catalog.saveGoal(null, call.receive<CatalogEdit>())) }
            put("/goals/{id}") { call.respond(services.catalog.saveGoal(call.id("id"), call.receive<CatalogEdit>())) }
            delete("/goals/{id}") { services.catalog.deleteGoal(call.id("id")); call.respond(HttpStatusCode.NoContent) }
        }
    }
}
