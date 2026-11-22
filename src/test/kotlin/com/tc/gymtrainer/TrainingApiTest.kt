package com.tc.gymtrainer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.TelegramLinkService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
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

    private fun linkTelegram(token: String, sender: Long): String {
        runBlocking { BotRole.LOGIN_CODES.record("training_codes_bot") }
        val link = request("POST", "/trainers/me/telegram-link", 200, token)
        val url = link["url"].asText()
        assertTrue(url.startsWith("https://t.me/training_codes_bot?start="))
        runBlocking { links.confirmSignup(url.substringAfter("?start="), sender) }
        return link["challenge"].asText()
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
    fun `trainers build their own exercise lists and reuse them for multiple clients`() {
        val owner = register()
        val other = register()
        val catalog = "/catalog/exercises"
        val group = request("GET", "/catalog/muscle-groups", 200, owner).first()["id"].asLong()
        val groupPath = "/catalog/muscle-groups/$group/exercises"
        val input = """{"muscleGroupId":$group,"name":"Жим с паузой"}"""
        request("GET", catalog, 401)
        assertTrue(request("GET", catalog, 200, owner).isEmpty)
        val item = request("POST", catalog, 200, owner, input)
        assertEquals(item, request("POST", catalog, 200, owner, input.replace("Жим", "  жим")))
        assertEquals(item, request("GET", groupPath, 200, owner).single())
        assertTrue(request("GET", groupPath, 200, other).isEmpty)
        assertTrue(request("POST", catalog, 200, other, input)["id"] != item["id"])
        request("POST", catalog, 400, owner, """{"muscleGroupId":$group,"name":"  "}""")
        request("POST", catalog, 400, owner, """{"muscleGroupId":999999,"name":"Жим"}""")

        val exercise = """{"exerciseCatalogId":${item["id"]},"sets":[{"reps":8}]}"""
        val workout = """{"performedAt":"2026-11-21T09:00:00Z","exercises":[$exercise]}"""
        repeat(2) {
            val saved = request("POST", "/clients/${client(owner)}/workouts", 201, owner, workout)
            val exerciseRow = saved["exercises"].single()["exercise"]
            assertEquals(item["id"], exerciseRow["exerciseCatalogId"])
            assertEquals(item["name"], exerciseRow["exerciseName"])
        }
        val otherPath = "/clients/${client(other)}/workouts"
        request("POST", otherPath, 404, other, workout)
        assertTrue(request("GET", otherPath, 200, other).isEmpty)
        val inline = """{"muscleGroupId":$group,"exerciseName":"Тяга","sets":[{"reps":6}]}"""
        val session = request("POST", otherPath, 201, other, workout.replace(exercise, inline))["session"]
        request("POST", "/workouts/${session["id"]}/exercises", 404, other, exercise)
        assertEquals(setOf("Жим с паузой", "Тяга"), request("GET", catalog, 200, other).map { it["name"].asText() }.toSet())
        assertEquals(item, request("GET", catalog, 200, owner).single())
        val plank = request("POST", catalog, 200, owner, """{"name":"Планка","description":" Удерживайте корпус прямо. "}""")
        assertEquals("Удерживайте корпус прямо.", plank["description"].asText())
        assertEquals(plank, request("POST", catalog, 200, owner, """{"name":" планка "}"""))
        val timed = """{"exerciseCatalogId":${plank["id"]},"sets":[{"durationSec":45}]}"""
        val timedWorkout = request("POST", "/clients/${client(owner)}/workouts", 201, owner, workout.replace(exercise, timed))
        assertEquals(plank["id"], timedWorkout["exercises"].single()["exercise"]["exerciseCatalogId"])
        request("POST", otherPath, 404, other, workout.replace(exercise, timed))
        val edit = "$catalog/${plank["id"]}"
        request("PUT", edit, 401, body = """{"name":"Обновление"}""")
        request("PUT", edit, 404, other, """{"name":"Обновление"}""")
        request("PUT", edit, 400, owner, """{"name":"  "}""")
        request("PUT", edit, 409, owner, """{"name":"  жим с паузой "}""")
        val renamed = request("PUT", edit, 200, owner, """{"name":"  Боковая планка ","description":"Опора на предплечье."}""")
        assertEquals(plank["id"], renamed["id"])
        assertEquals("Боковая планка", renamed["name"].asText())
        assertEquals("Опора на предплечье.", renamed["description"].asText())
        assertTrue(request("GET", catalog, 200, owner).any { it == renamed })
        val before = timedWorkout["exercises"].single()
        val after = request("GET", "/workouts/${timedWorkout["session"]["id"]}", 200, owner)["exercises"].single()
        assertEquals("Боковая планка", after["exercise"]["exerciseName"].asText())
        assertEquals(before["exercise"]["id"], after["exercise"]["id"])
        assertEquals(before["sets"], after["sets"])
        val cleared = request("PUT", edit, 200, owner, """{"name":"Боковая планка","description":""}""")
        assertTrue(cleared["description"].isNull)
    }

    @Test
    fun `workspace edits preserve ordering and enforce ownership`() {
        val owner = register()
        val other = register()
        val clientId = client(owner)
        val path = "/clients/$clientId"
        val group = request("GET", "/catalog/muscle-groups", 200, owner).first()["id"].asLong()
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
        val group = request("GET", "/catalog/muscle-groups", 200, token).first()["id"].asLong()
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
