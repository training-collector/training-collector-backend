package com.tc.gymtrainer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.TelegramLinkService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

class TrainingApiTest : AbstractDbTest() {
    @TempDir lateinit var logs: Path
    private var server: Process? = null
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val json = ObjectMapper()
    private val adminPassword = java.util.UUID.randomUUID().toString()
    private val authKey = java.util.UUID.randomUUID().toString()
    private val trainingKey = java.util.UUID.randomUUID().toString()
    private lateinit var base: String

    @BeforeEach
    fun startHttp() = startServer("SERVER_PORT")

    private fun startServer(portVariable: String) {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val host = "http://127.0.0.1:$port"
        base = "$host/api/v1"
        val log = logs.resolve("api.log")
        val db = TestDatabase.config
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(java, "-cp", System.getProperty("training.dist.lib") + "/*", System.getProperty("training.dist.main"))
            .directory(logs.toFile())
            .apply {
                environment().apply {
                    put("ADMIN_LOGIN", "admin-test")
                    put("ADMIN_PASSWORD", adminPassword)
                    put("AUTH_SERVICE_KEY", authKey)
                    put("TRAINING_SERVICE_KEY", trainingKey)
                    put("DB_JDBC_URL", db.jdbcUrl)
                    put("DB_USER", db.user)
                    put("DB_PASSWORD", db.password)
                    put("SERVER_HOST", "127.0.0.1")
                    remove("PORT")
                    remove("SERVER_PORT")
                    put(portVariable, port.toString())
                }
            }
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        server = process
        val health = HttpRequest.newBuilder(URI.create("$host/health")).timeout(Duration.ofSeconds(2)).build()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (process.isAlive && System.nanoTime() < deadline) {
            try {
                val response = http.send(health, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200 && response.body() == "OK") return
            } catch (_: IOException) {
                // Netty opens the port after database migration.
            }
            Thread.sleep(100)
        }
        error("Ktor startup failed: \n${log.readText()}")
    }

    private fun stopServer() {
        server?.let {
            it.destroy()
            if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly().waitFor()
        }
        server = null
    }

    @AfterEach
    fun stopHttp() {
        stopServer()
        http.close()
    }

    private fun request(method: String, path: String, status: Int, token: String? = null, body: String? = null): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create(if (path.startsWith("/admin/") || path.startsWith("/internal/")) base.removeSuffix("/api/v1") + path else base + path)).timeout(Duration.ofSeconds(10))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(status, response.statusCode(), "$method $path")
        assertEquals(listOf("no-store"), response.headers().allValues("Cache-Control"), "$method $path")
        return if (response.body().isBlank()) json.nullNode() else json.readTree(response.body())
    }

    private val links = TelegramLinkService()
    private val codes = TrainingServices().loginCodes
    private var nextSender = 100_000_000L

    private fun admin() = request("POST", "/admin/v1/login", 200, body = json.writeValueAsString(mapOf("login" to "admin-test", "password" to adminPassword)))["token"].asText()

    private fun linkTelegram(token: String, sender: Long): String {
        runBlocking { BotRole.LOGIN_CODES.record("training_codes_bot") }
        val link = request("POST", "/trainers/me/telegram-link", 200, token)
        val url = link["url"].asText()
        assertTrue(url.startsWith("https://t.me/training_codes_bot?start="))
        runBlocking { links.confirmSignup(url.substringAfter("?start="), sender) }
        val profile = request("GET", "/trainers/me", 200, token)
        assertTrue(profile["approvedAt"].isNull)
        request("POST", "/admin/v1/trainers/${profile["id"]}/approve", 204, admin())
        return runBlocking { codes.request(profile["username"].asText()).challenge }
    }

    private fun register(): String {
        val sender = ++nextSender
        val name = "coach$sender"
        val signup = request("POST", "/trainers", 201, body =
            """{"firstName":"Анна","lastName":"Тест","username":"$name"}""")["token"].asText()
        val id = linkTelegram(signup, sender)
        runBlocking { codes.prepareDelivery(id, "012345") }
        return request("POST", "/auth/login/verify", 200, body = """{"challenge":"$id","code":"012345"}""")["token"].asText()
    }

    private fun client(token: String) = request("POST", "/clients", 201, token, """{"fullName":"Подопечный"}""")["id"].asLong()

    @Test
    fun `approval code works from a login form opened before approval after leaving signup`() {
        runBlocking { BotRole.LOGIN_CODES.record("training_codes_bot") }
        val signup = request("POST", "/trainers", 201, body = """{"username":"мой.логин","firstName":"Имя","lastName":"Тест"}""")
        val onboarding = signup["token"].asText()
        val link = request("POST", "/trainers/me/telegram-link", 200, onboarding)["url"].asText().substringAfter("?start=")
        request("POST", "/internal/v1/auth/signup", 200, authKey, """{"token":"$link","sender":999}""")
        val chat = request("POST", "/internal/v1/auth/chats/999/activate", 200, authKey)
        assertTrue(chat["pending"].asBoolean())
        assertEquals("мой.логин", chat["username"].asText())
        request("POST", "/auth/logout", 204, onboarding)
        val login = """{"username":"МОЙ.ЛОГИН"}"""
        val early = request("POST", "/auth/login", 200, body = login)["challenge"].asText()
        request("POST", "/admin/v1/trainers/${signup["trainer"]["id"]}/approve", 204, admin())
        val job = request("POST", "/internal/v1/auth/deliveries/next", 200, authKey)["challenge"].asText()
        assertNotEquals(early, job)
        request("POST", "/internal/v1/auth/deliveries/prepare", 200, authKey, """{"challenge":"$job","code":"012345"}""")
        request("POST", "/internal/v1/auth/deliveries/complete", 204, authKey, """{"challenge":"$job"}""")
        assertEquals(job, request("POST", "/auth/login", 200, body = login)["challenge"].asText())
        val verify = """{"username":"МОЙ.ЛОГИН","code":"012345"}"""
        request("POST", "/auth/login/verify", 401, body = """{"username":"other","code":"012345"}""")
        val token = request("POST", "/auth/login/verify", 200, body = verify)["token"].asText()
        assertEquals("мой.логин", request("GET", "/trainers/me", 200, token)["username"].asText())
        request("POST", "/auth/login/verify", 401, body = verify)
    }

    @Test
    fun `registration telegram approval and OTP are distinct steps across restarts`() {
        val signup = """{"firstName":"Анна","lastName":"Тест","username":"мой.тренер"}"""
        request("POST", "/trainers", 400, body = signup.replace("мой.тренер", "coach@example.test"))
        request("POST", "/trainers", 400, body = signup.replace("lastName", "password"))
        val registered = request("POST", "/trainers", 201, body = signup)
        val onboarding = registered["token"].asText()
        assertTrue(onboarding.startsWith("signup."))
        assertEquals("Анна Тест", registered["trainer"]["fullName"].asText())
        assertTrue(registered["trainer"]["approvedAt"].isNull)
        request("POST", "/trainers", 409, body = signup.replace("мой.тренер", "МОЙ.ТРЕНЕР"))
        request("GET", "/clients", 403, onboarding)
        request("POST", "/trainers/me/telegram-link", 503, onboarding)
        request("POST", "/auth/login", 400, body = """{"username":"мой.тренер","password":"old-password"}""")
        request("POST", "/auth/login", 503, body = """{"username":"мой.тренер"}""")
        runBlocking { BotRole.LOGIN_CODES.record("training_codes_bot") }
        fun login() = request("POST", "/auth/login", 200, body = """{"username":"МОЙ.ТРЕНЕР"}""")
        fun verify(id: String, code: String, status: Int) =
            request("POST", "/auth/login/verify", status, body = """{"challenge":"$id","code":"$code"}""")
        verify(login()["challenge"].asText(), "012345", 401)
        val firstChallenge = linkTelegram(onboarding, 999)
        val approved = request("GET", "/trainers/me", 200, onboarding)
        assertTrue(approved["approvedAt"].isTextual)
        assertTrue(approved["phone"].isNull)
        request("GET", "/clients", 403, onboarding)
        request("POST", "/trainers/me/api-key", 403, onboarding)
        request("POST", "/trainers/me/training-bot-link", 403, onboarding)
        val challenge = login()
        assertEquals("https://t.me/training_codes_bot?start=login", challenge["botUrl"].asText())
        val id = challenge["challenge"].asText()
        assertEquals(firstChallenge, id)
        assertEquals(id, login()["challenge"].asText())
        runBlocking {
            assertTrue(!codes.activateChat(998))
            assertEquals(999L, codes.nextDelivery()!!.telegramId)
            codes.prepareDelivery(id, "012345")
            codes.delivered(id)
        }
        verify(id, "010101", 401)
        stopServer()
        startServer("PORT")
        val session = verify(id, "012345", 200)["token"].asText()
        verify(id, "012345", 401)
        request("GET", "/trainers/me", 401, onboarding)
        request("GET", "/clients", 200, session)
        assertTrue(request("GET", "/trainers/me", 200, session)["trainingBotLinkedAt"].isNull)
        request("POST", "/trainers/me/training-bot-link", 503, session)
        runBlocking { BotRole.TRAINING.record("training_test_bot") }
        val botLinks = "/trainers/me/bot-links"
        request("GET", botLinks, 401)
        val beforeLink = request("GET", botLinks, 200, session)
        assertEquals("https://t.me/training_codes_bot", beforeLink["authUrl"].asText())
        assertTrue(beforeLink["trainingUrl"].isNull)
        val url = request("POST", "/trainers/me/training-bot-link", 200, session)["url"].asText()
        assertTrue(url.startsWith("https://t.me/training_test_bot?start="))
        val payload = url.substringAfter("?start=")
        runBlocking { links.connectTrainingBot(payload, 999) }
        assertTrue(request("GET", "/trainers/me", 200, session)["trainingBotLinkedAt"].isTextual)
        assertEquals("https://t.me/training_test_bot", request("GET", botLinks, 200, session)["trainingUrl"].asText())
        request("POST", "/trainers/me/training-bot-link", 409, session)
        request("POST", "/auth/logout", 204, session)
        request("GET", "/clients", 401, session)
    }

    @Test
    fun `trainer can replace an API key without changing the profile or clients`() {
        fun apiKey(): String {
            val session = register()
            return request("POST", "/trainers/me/api-key", 200, session)["token"].asText()
        }
        val old = apiKey()
        val other = apiKey()
        val clientId = client(old)
        request("POST", "/trainers/me/training-bot-link", 403, old)
        val profile = request("GET", "/trainers/me", 200, old)
        val replacement = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        val route = "/trainers/me/token"
        fun body(token: String) = """{"newToken":"$token"}"""
        request("POST", route, 401, body = body(replacement))
        request("POST", route, 400, old, body(other))
        request("POST", route, 400, old, body("short"))
        request("POST", route, 204, old, body(replacement))
        request("GET", "/trainers/me", 401, old)
        assertEquals(profile, request("GET", "/trainers/me", 200, replacement))
        assertEquals(clientId, request("GET", "/clients", 200, replacement).single()["id"].asLong())
    }

    @Test
    fun `approved trainers can rename only their own profile`() {
        val token = register()
        val other = register()
        val path = "/trainers/me"
        val before = request("GET", path, 200, token)
        val otherProfile = request("GET", path, 200, other)
        val input = """{"firstName":" Анна Мария ","lastName":" де Соуза "}"""
        request("PUT", path, 401, body = input)
        val signup = request("POST", "/trainers", 201, body = """{"username":"awaiting","firstName":"Имя","lastName":"Тест"}""")
        request("PUT", path, 403, signup["token"].asText(), input)
        val updated = request("PUT", path, 200, token, input)
        val expected = before.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            put("firstName", "Анна Мария"); put("lastName", "де Соуза"); put("fullName", "Анна Мария де Соуза")
        }
        assertEquals(expected, updated)
        request("PUT", path, 400, token, """{"firstName":" ","lastName":"Тест"}""")
        request("PUT", path, 400, token, """{"firstName":"Имя","lastName":"${"а".repeat(255)}"}""")
        assertEquals(updated, request("GET", path, 200, token))
        assertEquals(otherProfile, request("GET", path, 200, other))
        assertEquals(updated, request("GET", "/admin/v1/trainers", 200, admin()).single { it["id"] == updated["id"] })
    }

    @Test
    fun `web client names preserve their parts and Telegram profile details`() {
        val token = register()
        request("POST", "/clients", 400, token, """{"firstName":"Анна"}""")
        val client = request("POST", "/clients", 201, token, """{"firstName":"Анна Мария","lastName":"де Соуза"}""")
        val id = client["id"].asLong()
        assertEquals("Анна Мария де Соуза", client["fullName"].asText())
        runBlocking { Services.client.updateProfile(client["trainerId"].asLong(), id) { it.copy(age = 26, telegramUsername = "coach_client", phone = "+123456") } }
        val updated = request("PUT", "/clients/$id", 200, token, """{"firstName":"Анна Мария","lastName":"Иванова"}""")
        assertEquals("Анна Мария", updated["firstName"].asText())
        assertEquals("Иванова", updated["lastName"].asText())
        assertEquals(26, updated["age"].asInt())
        assertEquals("coach_client", updated["telegramUsername"].asText())
        assertEquals("+123456", updated["phone"].asText())
        val cleared = request("PUT", "/clients/$id", 200, token, """{"firstName":"Анна Мария","lastName":"Иванова","phone":null}""")
        assertTrue(cleared["phone"].isNull)
    }

    @Test
    fun `trainer can assign replace and clear a client's goal from the web form`() {
        val admin = admin()
        val trainer = register()
        for (name in listOf("Сила", "Выносливость")) {
            request("POST", "/admin/v1/goals", 201, admin, """{"name":"$name"}""")
        }
        val client = request("POST", "/clients", 201, trainer, """{"firstName":"Иван","lastName":"Петров"}""")
        val path = "/clients/${client["id"]}"
        for (goal in listOf("Сила", "Выносливость", null)) {
            val input = json.createObjectNode().put("firstName", "Иван").put("lastName", "Петров")
                .put("goal", goal).put("notes", "Заметка тренера")
            val saved = request("PUT", path, 200, trainer, input.toString())
            assertEquals(input["goal"], saved["goal"])
            assertEquals(input["notes"], saved["notes"])
            assertEquals(saved, request("GET", path, 200, trainer))
        }
    }

    @Test
    fun `editing a category preserves its exercises and allows clearing its description`() {
        val admin = admin()
        val owner = register()
        val groups = "/admin/v1/muscle-groups"
        val group = request("POST", groups, 201, admin, """{"name":"Грудь","description":" Первая строка "}""")
        assertEquals("Первая строка", group["description"].asText())
        val id = group["id"].asLong()
        val exercise = request("POST", "/admin/v1/exercises", 201, admin, """{"name":"Жим","muscleGroupId":$id}""")
        request("POST", groups, 201, admin, """{"name":"Спина"}""")
        val edit = """{"name":" Грудные мышцы ","description":"Первая строка\nВторая строка"}"""
        request("PUT", "$groups/$id", 401, owner, edit)
        request("PUT", "$groups/999999", 404, admin, edit)
        val updated = request("PUT", "$groups/$id", 200, admin, edit)
        assertEquals(id, updated["id"].asLong())
        assertEquals("Грудные мышцы", updated["name"].asText())
        assertEquals("Первая строка\nВторая строка", updated["description"].asText())
        request("PUT", "$groups/$id", 400, admin, """{"name":"  "}""")
        request("PUT", "$groups/$id", 409, admin, """{"name":"Спина"}""")
        assertEquals(updated, request("GET", "/catalog/muscle-groups", 200, owner).single { it["id"].asLong() == id })
        assertEquals(exercise, request("GET", "/catalog/muscle-groups/$id/exercises", 200, owner).single())
        val cleared = request("PUT", "$groups/$id", 200, admin, """{"name":"Грудные мышцы","description":"  "}""")
        assertTrue(cleared.path("description").isNull)
        assertEquals(cleared, request("GET", groups, 200, admin).single { it["id"].asLong() == id })
    }

    @Test
    fun `admin controls shared catalogs and historical workouts survive catalog deletion`() {
        val owner = register()
        val other = register()
        val admin = admin()
        val catalog = "/catalog/exercises"
        val manage = "/admin/v1/exercises"
        val groups = "/admin/v1/muscle-groups"
        assertTrue(request("GET", "/catalog/muscle-groups", 200, owner).isEmpty)
        request("POST", groups, 401, owner, """{"name":"Кор"}""")
        val group = request("POST", groups, 201, admin, """{"name":"Кор"}""")["id"].asLong()
        val otherGroup = request("POST", groups, 201, admin, """{"name":"Спина"}""")["id"].asLong()
        request("POST", groups, 409, admin, """{"name":"Кор"}""")
        request("POST", manage, 400, admin, """{"name":"Планка"}""")
        request("POST", manage, 400, admin, """{"name":"Планка","muscleGroupId":999999}""")
        val input = """{"name":"Планка","muscleGroupId":$group,"description":"Локти под плечами"}"""
        request("GET", manage, 401, owner)
        request("POST", catalog, 405, owner, input)
        val item = request("POST", manage, 201, admin, input)
        assertEquals(item, request("GET", catalog, 200, owner).single())
        assertEquals(item, request("GET", catalog, 200, other).single())
        assertEquals(item, request("GET", "/catalog/muscle-groups/$group/exercises", 200, owner).single())
        assertTrue(request("GET", "/catalog/muscle-groups/$otherGroup/exercises", 200, owner).isEmpty)
        val path = "/clients/${client(owner)}/workouts"
        val workout = """{"performedAt":"2026-11-21T09:00:00Z","exercises":[{"exerciseCatalogId":${item["id"]},"sets":[{"durationSec":45}]}]}"""
        val session = request("POST", path, 201, owner, workout)["session"]["id"]
        request("POST", path, 404, owner, workout.replace(""""exerciseCatalogId":${item["id"]}""", """"exerciseName":"Нет в каталоге"""".trim()))
        val edit = "$manage/${item["id"]}"
        request("PUT", edit, 200, admin, """{"name":"Планка","muscleGroupId":$otherGroup,"hidden":true}""")
        assertEquals(item["id"], request("GET", "$groups/$otherGroup/exercises", 200, admin).single()["id"])
        assertTrue(request("GET", catalog, 200, other).isEmpty)
        request("POST", path, 404, owner, workout)
        request("DELETE", edit, 204, admin)
        val history = request("GET", "/workouts/$session", 200, owner)["exercises"].single()
        assertEquals(group, history["exercise"]["muscleGroupId"].asLong())
        assertEquals("Планка", history["exercise"]["exerciseName"].asText())
        assertEquals(45, history["sets"].single()["durationSec"].asInt())
        val goals = "/admin/v1/goals"
        assertTrue(request("GET", "/catalog/goals", 200, owner).isEmpty)
        val goal = request("POST", goals, 201, admin, """{"name":"Сила"}""")
        assertEquals(goal, request("GET", "/catalog/goals", 200, other).single())
        request("PUT", "$goals/${goal["id"]}", 200, admin, """{"name":"Осанка","hidden":true}""")
        assertTrue(request("GET", "/catalog/goals", 200, other).isEmpty)
        request("DELETE", "$goals/${goal["id"]}", 204, admin)
    }

    @Test
    fun `only linked signups can be approved and deactivation revokes every credential`() {
        val admin = admin()
        val path = "/admin/v1/trainers"
        request("GET", path, 401)
        request("POST", "/admin/v1/login", 401, body = """{"login":"admin-test","password":"wrong"}""")
        val signup = request("POST", "/trainers", 201, body = """{"username":"pending","firstName":"Имя","lastName":"Тест"}""")
        val unlinked = signup["trainer"]["id"]
        assertTrue(request("GET", path, 200, admin).isEmpty)
        request("POST", "$path/$unlinked/approve", 409, admin)
        request("POST", "$path/$unlinked/activate", 409, admin)
        val session = register()
        val active = request("GET", path, 200, admin).single()
        val id = active["id"].asLong()
        val sender = active["telegramUserId"].asLong()
        val key = request("POST", "/trainers/me/api-key", 200, session)["token"].asText()
        val record = client(session)
        request("POST", "/clients/$record/measurements", 201, session, """{"measuredOn":"2026-11-01","weightKg":75}""")
        runBlocking { BotRole.TRAINING.record("training_bot") }
        val link = request("POST", "/trainers/me/training-bot-link", 200, session)["url"].asText()
        runBlocking { links.connectTrainingBot(link.substringAfter("?start="), sender) }
        val oldCode = runBlocking { codes.request(active["username"].asText()).challenge }
        runBlocking { codes.prepareDelivery(oldCode, "123456") }
        request("POST", "$path/$id/deactivate", 204, admin)
        request("GET", "/clients", 401, session)
        request("GET", "/clients", 401, key)
        request("GET", "/internal/v1/training/chats/$sender/clients", 401, trainingKey)
        request("POST", "/auth/login/verify", 401, body = """{"challenge":"$oldCode","code":"123456"}""")
        assertTrue(request("GET", path, 200, admin).single()["deactivatedAt"].isTextual)
        assertTrue(runBlocking { codes.nextDelivery() } == null)
        request("POST", "$path/$id/approve", 409, admin)
        request("POST", "$path/$id/activate", 401, session)
        request("POST", "$path/$id/activate", 204, admin)
        assertTrue(request("GET", path, 200, admin).single()["approvedAt"].isTextual)
        request("GET", "/clients", 401, session)
        request("GET", "/clients", 401, key)
        request("GET", "/internal/v1/training/chats/$sender/clients", 401, trainingKey)
        request("DELETE", "$path/$id", 204, admin)
        assertTrue(request("GET", path, 200, admin).isEmpty)
        request("POST", "/admin/v1/logout", 204, admin)
        request("GET", path, 401, admin)
    }

    @Test
    fun `workspace edits preserve ordering and enforce ownership`() {
        val owner = register()
        val other = register()
        val clientId = client(owner)
        val path = "/clients/$clientId"
        val group = runBlocking { Services.catalog.createMuscleGroup("Ноги").id }
        runBlocking { Services.catalog.saveExercise(null, com.tc.gymtrainer.service.ExerciseEdit("Жим", group)) }
        val exercise = """{"muscleGroupId":$group,"exerciseName":"Жим","sets":[{"reps":8},{"durationSec":20}]}"""
        val workout = request("POST", "$path/workouts", 201, owner,
            """{"performedAt":"2026-11-21T09:00:00Z","exercises":[$exercise]}""")
        val workoutPath = "/workouts/" + workout["session"]["id"]
        val original = workout["exercises"].first()
        val exercisePath = "/exercises/" + original["exercise"]["id"]
        val setPath = "/sets/" + original["sets"].first()["id"]

        request("DELETE", setPath, 403, other)
        request("POST", "$exercisePath/sets", 403, other, """{"reps":2}""")
        request("DELETE", setPath, 204, owner)
        val appended = request("POST", "$exercisePath/sets", 201, owner, """{"reps":5}""")
        assertEquals(3, appended["setNumber"].asInt())
        val meta = """{"performedAt":"2026-11-22T09:00:00Z","durationMin":55,"note":"Новая дата"}"""
        request("PUT", workoutPath, 403, other, meta)
        assertEquals(55, request("PUT", workoutPath, 200, owner, meta)["session"]["durationMin"].asInt())
        request("POST", "$workoutPath/exercises", 403, other, exercise)
        request("POST", "$workoutPath/exercises", 201, owner, exercise)
        request("DELETE", exercisePath, 403, other)
        request("DELETE", exercisePath, 204, owner)
        assertEquals(2, request("POST", "$workoutPath/exercises", 201, owner, exercise)["orderIndex"].asInt())
        request("DELETE", workoutPath, 403, other)
        request("DELETE", workoutPath, 204, owner)

        val input = """{"measuredOn":"2026-11-21","waistCm":80}"""
        val m = request("POST", "$path/measurements", 201, owner, input)
        val measurement = "/measurements/" + m["id"]
        request("PUT", measurement, 403, other, input)
        request("PUT", measurement, 200, owner, """{"measuredOn":"2026-11-21","calfCm":35}""")
        assertEquals(35, request("GET", "$path/measurements/current", 200, owner)["calfCm"]["value"].asInt())
        request("DELETE", measurement, 403, other)
        request("DELETE", measurement, 204, owner)
        request("POST", "$path/archive", 403, other)
        assertEquals("ARCHIVED", request("POST", "$path/archive", 200, owner)["status"].asText())
        assertTrue(request("GET", "/clients", 200, owner).isEmpty)
        assertEquals("ACTIVE", request("POST", "$path/restore", 200, owner)["status"].asText())
        request("DELETE", path, 403, other)
        request("DELETE", path, 204, owner)
    }

    @Test
    fun `internal routes require the matching bot and an activated training chat`() {
        val internal = base.removeSuffix("/api/v1") + "/internal/v1"
        fun status(path: String, token: String?, post: Boolean = false): Int {
            val request = HttpRequest.newBuilder(URI.create(internal + path))
            if (token != null) request.header("Authorization", "Bearer $token")
            if (post) request.POST(HttpRequest.BodyPublishers.noBody())
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        }
        assertEquals(401, status("/auth/deliveries/next", null, true))
        assertEquals(401, status("/auth/deliveries/next", trainingKey, true))
        assertEquals(204, status("/auth/deliveries/next", authKey, true))
        assertEquals(401, status("/training/linked/123", authKey))
        assertEquals(204, status("/training/linked/123", trainingKey))
        assertEquals(401, status("/training/chats/123/clients", trainingKey))
    }

    @Test
    fun `existing Telegram trainer can read their clients and rotate their API token`() {
        val trainer = runBlocking { newTrainer(8345) }
        val client = runBlocking { newClient(trainer) }
        val first = runBlocking { Services.trainer.issueApiToken(trainer.id) }
        request("GET", "/clients", 403, first)
        linkTelegram(first, 8345)
        assertEquals("tg:8345", request("GET", "/trainers/me", 200, first)["username"].asText())
        assertEquals(client.id, request("GET", "/clients", 200, first).single()["id"].asLong())
        val replacement = runBlocking { Services.trainer.issueApiToken(trainer.id) }
        request("GET", "/trainers/me", 401, first)
        assertEquals(trainer.id, request("GET", "/trainers/me", 200, replacement)["id"].asLong())
    }

    @Test
    fun `workout round trip, correction, ownership and atomic validation`() {
        val token = register()
        val trainerId = request("GET", "/trainers/me", 200, token)["id"].asLong()
        val clientId = client(token)
        val path = "/clients/$clientId/workouts"
        val group = runBlocking { Services.catalog.createMuscleGroup("Ноги").id }
        runBlocking { Services.catalog.saveExercise(null, com.tc.gymtrainer.service.ExerciseEdit("Тестовое упражнение", group)) }
        fun workout(sets: String) = """
            {"performedAt":"2026-11-20T12:00:00+04:00","exercises":[
              {"muscleGroupId":$group,"exerciseName":"Тестовое упражнение","sets":$sets}
            ]}
        """.trimIndent()
        val created = request("POST", path, 201, token, workout("""[{"weightKg":12.5,"reps":8},{"durationSec":45}]"""))
        val id = created["session"]["id"].asLong()
        val setId = created["exercises"][0]["sets"][1]["id"].asLong()
        assertEquals(trainerId, created["session"]["trainerId"].asLong())
        assertEquals(clientId, created["session"]["clientId"].asLong())
        assertEquals(created, request("GET", "/workouts/$id", 200, token))
        assertEquals(8, created["exercises"][0]["sets"][0]["reps"].asInt())
        assertEquals(45, created["exercises"][0]["sets"][1]["durationSec"].asInt())

        request("PUT", "/sets/$setId", 204, token, """{"durationSec":50,"weightKg":2.5}""")
        val corrected = request("GET", "/workouts/$id", 200, token)["exercises"][0]["sets"][1]
        assertEquals(50, corrected["durationSec"].asInt())
        assertEquals(2.5, corrected["weightKg"].asDouble())
        assertTrue(corrected["reps"].isNull)

        for (invalid in listOf("""{"durationSec":0}""", """{"reps":2,"durationSec":30}""", """{"weightKg":4}""")) {
            request("POST", path, 400, token, workout("""[{"reps":3},$invalid]"""))
        }
        request("POST", path, 400, token, workout("[null]"))
        request("POST", path, 400, token, """{"performedAt":"2026-11-20T08:00:00Z","exercises":[null]}""")
        assertEquals(listOf(id), request("GET", path, 200, token).map { it["id"].asLong() })
        request("POST", path, 400, token, """{"performedAt":"вчера","exercises":[]}""")
        request("GET", "/clients", 401)
        val other = register()
        assertTrue(request("GET", "/clients", 200, other).isEmpty)
        request("GET", "/workouts/$id", 403, other)
        request("POST", path, 403, other, workout("""[{"reps":8}]"""))
        request("PUT", "/sets/$setId", 403, other, """{"reps":10}""")
    }

    @Test
    fun `partial measurements keep dated history and latest value for each metric`() {
        val token = register()
        val path = "/clients/${client(token)}/measurements"
        assertTrue(request("GET", "$path/current", 200, token).isEmpty)
        val baseline = request("POST", path, 201, token, """
            {"measuredOn":"2026-10-01","heightCm":180,"weightKg":82.5,"bodyFatPct":18,"bicepsCm":33,
             "waistCm":85,"chestCm":100,"hipsCm":99,"thighCm":56,"calfCm":38}
        """.trimIndent())
        val later = request("POST", path, 201, token, """{"measuredOn":"2026-11-01","weightKg":80}""")
        val sameDay = request("POST", path, 201, token, """{"measuredOn":"2026-11-01","weightKg":80.2}""")
        request("POST", path, 201, token, """{"measuredOn":"2026-09-01","weightKg":84}""")
        request("POST", path, 400, token, """{"measuredOn":"2026-11-02","calfCm":-1}""")
        request("POST", path, 400, token, """{"measuredOn":"2026-11-02"}""")
        stopServer()
        startHttp()
        val history = request("GET", path, 200, token)
        assertEquals(4, history.size())
        assertEquals(listOf(sameDay, later, baseline), history.take(3))
        assertTrue(history.first()["heightCm"].isNull)
        val current = request("GET", "$path/current", 200, token)
        assertEquals(9, current.size())
        assertEquals(80.2, current["weightKg"]["value"].asDouble())
        assertEquals("2026-11-01", current["weightKg"]["measuredOn"].asText())
        assertEquals(180, current["heightCm"]["value"].asInt())
        assertEquals("2026-10-01", current["calfCm"]["measuredOn"].asText())
        val paired = request("POST", path, 201, token, """
            {"measuredOn":"2026-11-03","rightBicepsCm":32.1,"leftBicepsCm":30.5,"rightThighCm":54,"leftThighCm":52,
             "rightCalfCm":36.2,"leftCalfCm":35.4,"rightForearmCm":27.4,"leftForearmCm":26.3,"backWidthCm":39.1}
        """.trimIndent())
        val newer = request("POST", path, 201, token, """{"measuredOn":"2026-11-04","leftBicepsCm":31,"rightForearmCm":28}""")
        assertEquals(31, request("GET", "$path/current", 200, token)["leftBicepsCm"]["value"].asInt())
        assertEquals(32.1, request("GET", "$path/current", 200, token)["rightBicepsCm"]["value"].asDouble())
        val edited = request("PUT", "/measurements/${newer["id"]}", 200, token,
            """{"measuredOn":"2026-11-04","leftBicepsCm":31.2,"rightForearmCm":28.2}""")
        assertEquals(28.2, edited["rightForearmCm"].asDouble())
        val currentSides = request("GET", "$path/current", 200, token)
        assertEquals(39.1, currentSides["backWidthCm"]["value"].asDouble())
        assertEquals(28.2, currentSides["rightForearmCm"]["value"].asDouble())
        assertEquals(26.3, currentSides["leftForearmCm"]["value"].asDouble())
        val savedPair = request("GET", path, 200, token).single { it["id"] == paired["id"] }
        assertEquals(paired, savedPair)
        assertEquals(36.2, savedPair["rightCalfCm"].asDouble())
        assertEquals(52, savedPair["leftThighCm"].asInt())
        assertEquals(27.4, savedPair["rightForearmCm"].asDouble())
        assertEquals(39.1, savedPair["backWidthCm"].asDouble())
        request("POST", path, 400, token, """{"measuredOn":"2026-11-04","backWidthCm":-1}""")
        request("POST", path, 400, token, """{"measuredOn":"2026-11-04","leftForearmCm":0}""")
        request("POST", path, 400, token, """{"measuredOn":"2026-11-04","leftCalfCm":-1}""")
        request("POST", path, 400, token, """{"measuredOn":"2026-11-04","rightThighCm":55.55}""")
        val other = register()
        request("GET", path, 403, other)
        request("POST", path, 403, other, """{"measuredOn":"2026-11-02","weightKg":60}""")
    }
}
