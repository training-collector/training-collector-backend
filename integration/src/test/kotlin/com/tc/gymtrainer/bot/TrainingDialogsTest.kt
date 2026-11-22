package com.tc.gymtrainer.bot

import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.config.ServerConfig
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.Sex
import com.tc.gymtrainer.service.BotRole
import dev.inmo.tgbotapi.extensions.behaviour_builder.DefaultBehaviourContext
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import dev.inmo.tgbotapi.types.message.textsources.CodeTextSource
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class TrainingDialogsTest {
    @Test
    fun `coach can skip and edit details, discard drafts and record sets and measurements with buttons`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val config = AppConfig(DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, 4), ServerConfig("127.0.0.1", 0))
            withTestPlatform(config) { services, training, _ ->
                var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val telegram = TestTelegram(100, "trainer_test_bot")
                var context = DefaultBehaviourContext(telegram, scope)
                val json = Json { ignoreUnknownKeys = true }
                try {
                    runBlocking {
                        suspend fun installBot() {
                            GymTrainerBot(training).install(context)
                        }
                        installBot()
                        BotRole.LOGIN_CODES.record("auth_test_bot")
                        val id = services.trainers.registerForWeb("profile_test", "Test", "Coach").trainer.id
                        services.telegramLinks.confirmSignup(services.telegramLinks.createSignupLink(id).url.substringAfter("?start="), 123)
                        services.telegramLinks.connectTrainingBot(services.telegramLinks.createTrainingLink(id).url.substringAfter("?start="), 123)

                        val user = """{"id":123,"is_bot":false,"first_name":"Test"}"""
                        val chat = """{"id":123,"type":"private","first_name":"Test"}"""
                        var updateId = 0
                        suspend fun update(body: String) {
                            val raw = """{"update_id":${++updateId},$body}"""
                            context.asUpdateReceiver(json.decodeFromString(UpdateDeserializationStrategy, raw))
                        }
                        suspend fun text(value: String) = update(
                            """"message":{"message_id":${++updateId},"date":1700000000,"from":$user,"chat":$chat,"text":${JsonPrimitive(value)}}"""
                        )
                        suspend fun callback(value: String, messageId: Int = telegram.messageId.get()) = update(
                            """"callback_query":{"id":"cb$updateId","from":$user,"chat_instance":"chat","data":${JsonPrimitive(value)},
                            "message":{"message_id":$messageId,"date":1700000000,"chat":$chat,
                            "from":{"id":100,"is_bot":true,"first_name":"Bot"},"text":"Prompt"}}"""
                        )
                        suspend fun expect(fragment: String): SendTextMessage {
                            val response = withTimeoutOrNull(5000) { telegram.sent.receive() } ?: error("Waiting for «$fragment»")
                            assertTrue(response.text.contains(fragment), "Expected «$fragment», received «${response.text}»")
                            return response
                        }
                        suspend fun say(value: String, expected: String) = text(value).let { expect(expected) }
                        suspend fun click(value: String, expected: String) = callback(value).let { expect(expected) }
                        fun buttons(message: SendTextMessage) = (message.replyMarkup as ReplyKeyboardMarkup).keyboard.flatten().map { it.text }
                        val home = listOf("Подопечные", "Упражнения")
                        fun inline(message: SendTextMessage) = (message.replyMarkup as InlineKeyboardMarkup).keyboard.flatten()
                            .map { it as CallbackDataInlineKeyboardButton }
                        fun assertClientCard(message: SendTextMessage) {
                            assertEquals(listOf("Тренировки", "Замеры"), inline(message).map { it.text })
                        }
                        suspend fun openClient(clientId: Long, next: String): Int {
                            val listing = inline(say("Подопечные", "Ваши подопечные"))
                            assertEquals("➕ Добавить подопечного", listing.first().text)
                            assertClientCard(click(listing.single { it.callbackData == "cl:view:$clientId" }.callbackData, next))
                            return telegram.messageId.get()
                        }

                        suspend fun createFirstClient(): Client {
                            val emptyClients = inline(say("Подопечные", "нет подопечных"))
                            assertEquals(listOf("➕ Добавить подопечного"), emptyClients.map { it.text })
                            assertEquals(listOf("Отмена"), buttons(click(emptyClients.first().callbackData, "Имя нового")))
                            say("Анна", "Фамилия")
                            say("Отмена", "Отменено")
                            assertTrue(services.clients.list(id).isEmpty())
                            say("Добавить подопечного", "Имя нового")
                            say("Анна Мария", "Фамилия")
                            val menu = say("де Вега", "Анна Мария де Вега")
                            assertEquals(listOf("Сохранить", "Редактировать", "Отмена"), buttons(menu))
                            assertEquals(home, buttons(say("Сохранить", "Подопечный добавлен")))
                            assertClientCard(expect("Анна Мария де Вега"))
                            val client = services.clients.list(id).single()
                            assertEquals("Анна Мария", client.firstName)
                            assertEquals("де Вега", client.lastName)
                            assertNull(client.age)
                            return client
                        }
                        val client = createFirstClient()

                        suspend fun createClientWithMeasurements(): Client {
                            suspend fun editNewProfile() {
                                say("Добавить подопечного", "Имя нового")
                                say("Иван", "Фамилия")
                                say("Петров", "Сохранить")
                                val fields = say("Редактировать", "Выберите поле")
                                assertTrue(buttons(fields).containsAll(listOf("Вес, кг", "Рост, см", "Возраст", "Пол", "Цель", "Заметки", "Телефон", "Ник Telegram")))
                                say("Вес, кг", "замера за сегодня")
                                say("0", "Недопустимый вес")
                                say("80,5", "Вес: 80.5 кг")
                            }
                            editNewProfile()
                            say("Отмена", "Отменено")
                            assertEquals(listOf(client.id), services.clients.list(id).map { it.id })
                            editNewProfile()
                            say("Рост, см", "замера за сегодня")
                            say("181", "Рост: 181 см")
                            val goals = say("Цель", "Цель")
                            assertFalse("Реабилитация" in buttons(goals))
                            say("Реабилитация", "Выберите цель кнопкой")
                            say("Поддержание", "Цель: Поддержание")
                            say("Возраст", "Сколько")
                            say("abc", "целым")
                            say("28", "Возраст: 28")
                            say("Пол", "Пол")
                            say("Женский", "Пол: Женский")
                            say("Ник Telegram", "@username")
                            say("@coach_test", "@coach_test")
                            say("Телефон", "Телефон")
                            say("+7 555 123", "Телефон: +7")
                            say("Сохранить", "Подопечный добавлен")
                            assertClientCard(expect("Иван Петров"))
                            val ivan = services.clients.list(id).single { it.firstName == "Иван" }
                            val baseline = services.measurements.history(id, ivan.id).single()
                            assertEquals("80.5", Format.number(baseline.weightKg))
                            assertEquals("181", Format.number(baseline.heightCm))
                            openClient(ivan.id, "Вес: 80.5 кг")
                            val ivanMeasurements = inline(click("m:hist:${ivan.id}", "История замеров"))
                            assertEquals(listOf("m:add:${ivan.id}", "m:view:${baseline.id}", "cl:view:${ivan.id}"), ivanMeasurements.map { it.callbackData })
                            val fieldMenu = click(ivanMeasurements.first().callbackData, "Иван Петров").replyMarkup as ReplyKeyboardMarkup
                            assertEquals(listOf(listOf("Сохранить"), listOf("Отмена"), listOf("Вес, кг", "Рост, см")),
                                fieldMenu.keyboard.take(3).map { row -> row.map { it.text } })
                            say("Вес, кг", "Введите значение")
                            say("79", "Вес, кг: 79")
                            say("Сохранить", "Замер сохранён")
                            val history = services.measurements.history(id, ivan.id)
                            assertEquals(listOf("79", "80.5"), history.map { Format.number(it.weightKg) })
                            assertEquals(listOf(null, "181"), history.map { Format.number(it.heightCm) })

                            val edited = services.clients.getOwned(id, ivan.id)
                            assertEquals(Sex.FEMALE, edited.sex)
                            assertEquals("coach_test", edited.telegramUsername)
                            assertEquals(28, edited.age)
                            assertEquals("+7 555 123", edited.phone)
                            // The legacy web editor sends a fullName and must preserve the structured profile.
                            services.clients.update(id, ivan.id, edited.fullName, edited.phone, edited.goal, "запись с сайта")
                            assertEquals(edited.profile().copy(notes = "запись с сайта"), services.clients.getOwned(id,ivan.id).profile())
                            return ivan
                        }
                        val ivan = createClientWithMeasurements()

                        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
                            connection.createStatement().use { it.execute("DELETE FROM muscle_group") }
                        }
                        val annaCard = openClient(client.id, "Анна Мария")
                        openClient(ivan.id, "Иван Петров")
                        suspend fun pressAnna(action: String, expected: String): SendTextMessage {
                            callback("$action:${client.id}", annaCard)
                            return expect(expected)
                        }
                        val emptyCatalog = inline(say("Упражнения", "Список упражнений пока пуст"))
                        assertEquals(listOf("➕ Добавить упражнение"), emptyCatalog.map { it.text })
                        click(emptyCatalog.first().callbackData, "Введите название")
                        say("Отмена", "Отменено")
                        val emptyWorkouts = inline(pressAnna("w:hist", "Завершённых тренировок пока нет"))
                        assertEquals(listOf("Записать тренировку", "К подопечному"), emptyWorkouts.map { it.text })
                        click(emptyWorkouts.first().callbackData, "Список упражнений пока пуст")
                        val firstSession = services.workouts.activeForClient(id, client.id)!!.id
                        click("ex:new", "Введите название")
                        val draftActions = say("Черновик", "Сохранить").replyMarkup as ReplyKeyboardMarkup
                        assertEquals(listOf(listOf("Сохранить"), listOf("Отмена"), listOf("Добавить описание")),
                            draftActions.keyboard.map { row -> row.map { it.text } })
                        say("Добавить описание", "Введите описание")
                        say("Не сохранять это упражнение", "Сохранить упражнение?")
                        say("Отмена", "Отменено")
                        assertTrue(services.catalog.exercisesFor(id).isEmpty())
                        pressAnna("w:add", "Список упражнений пока пуст")
                        assertEquals(firstSession, services.workouts.activeForClient(id, client.id)!!.id)
                        click("ex:new", "Введите название")
                        say("Жим", "Сохранить")
                        val setPrompt = say("Сохранить", "Введите подходы")
                        assertEquals(listOf("Жим", "На повторения", "На время"), setPrompt.textSources.orEmpty().filterIsInstance<BoldTextSource>().map { it.source })
                        assertEquals(listOf("60 10", "0 12", "45с", "16 30с"), setPrompt.textSources.orEmpty().filterIsInstance<CodeTextSource>().map { it.source })
                        say("60 10\nошибка", "строку 2")
                        assertTrue(services.workouts.getDetail(id, firstSession).exercises.single().sets.isEmpty())
                        say("60 10\n62,5 8\n0 12", "Подходов в упражнении: 3")
                        say("Убрать последний подход", "Подходов в упражнении: 2")
                        assertTrue(services.workouts.history(id, client.id).isEmpty())
                        assertEquals(listOf(10, 8), services.workouts.getDetail(id, firstSession).exercises.single().sets.map { it.reps })
                        val press = services.catalog.exercisesFor(id).single()
                        assertNull(press.muscleGroupId)
                        val catalogMenu = inline(say("Упражнения", "Мои упражнения"))
                        assertEquals(listOf("➕ Добавить упражнение", "Жим"), catalogMenu.map { it.text })
                        click(catalogMenu.first().callbackData, "Введите название")
                        say("Планка", "Сохранить")
                        say("Сохранить", "«Планка» в вашем списке")
                        val updatedCatalog = inline(expect("Мои упражнения"))
                        val plank = services.catalog.exercisesFor(id).single { it.name == "Планка" }
                        assertNull(plank.description)
                        val catalogCard = click(updatedCatalog.single { it.text == "Планка" }.callbackData, "Планка")
                        assertEquals("Название: Планка\n\nОписание: не указано", catalogCard.text)
                        val back = inline(catalogCard).single()
                        assertEquals("К упражнениям", back.text)
                        val allExercises = inline(click(back.callbackData, "Мои упражнения"))
                        click(allExercises.first().callbackData, "Введите название")
                        say("Удержание гири", "Сохранить")
                        say("Добавить описание", "Введите описание")
                        val preview = say("Удерживайте гирю у груди.", "Сохранить упражнение?")
                        assertTrue(preview.text.contains("Название: Удержание гири\n\nОписание: Удерживайте гирю у груди."))
                        assertTrue(services.catalog.exercisesFor(id).none { it.name == "Удержание гири" })
                        say("Сохранить", "«Удержание гири» в вашем списке")
                        val savedExercises = inline(expect("Мои упражнения"))
                        val hold = services.catalog.exercisesFor(id).single { it.name == "Удержание гири" }
                        assertEquals("Удерживайте гирю у груди.", hold.description)
                        val holdCard = click(savedExercises.single { it.text == hold.name }.callbackData, hold.name)
                        assertEquals("Название: Удержание гири\n\nОписание: Удерживайте гирю у груди.", holdCard.text)

                        // Lose the entire Telegram dialog and reinstall the bot against the same PostgreSQL.
                        scope.coroutineContext.job.cancelAndJoin()
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                        context = DefaultBehaviourContext(telegram, scope)
                        installBot()
                        openClient(client.id, "Идёт тренировка")
                        val activeList = inline(click("w:hist:${client.id}", "Тренировки"))
                        assertEquals(listOf("Продолжить тренировку", "Завершить тренировку", "К подопечному"), activeList.map { it.text })
                        val activeCard = telegram.messageId.get()
                        openClient(ivan.id, "Иван Петров")
                        callback(activeList.first().callbackData, activeCard)
                        val restored = expect("Подходов в упражнении: 2")
                        assertTrue(restored.text.contains("Жим") && restored.text.contains("62.5 кг × 8"))
                        assertTrue(buttons(restored).containsAll(listOf("Готово", "Убрать последний подход", "Главное меню")))
                        say("Готово", "Упражнений в тренировке: 1")
                        val selection = say("Ещё упражнение", "Мои упражнения").replyMarkup as InlineKeyboardMarkup
                        assertEquals(setOf("Жим", "Планка", "Удержание гири", "➕ Добавить упражнение", "Отмена"), selection.keyboard.flatten().map { it.text }.toSet())
                        click("cancel", "Тренировка сохранена")
                        callback("w:resume:$firstSession", activeCard)
                        expect("Мои упражнения")
                        callback("w:finish:$firstSession", activeCard)
                        expect("Тренировка завершена")
                        assertClientCard(expect("Анна Мария"))
                        assertNull(services.workouts.activeForClient(id, client.id))
                        val workout = services.workouts.lastWorkout(id, client.id)!!
                        assertEquals(firstSession, workout.session.id)
                        assertEquals(listOf(10,8), workout.exercises.single().sets.map { it.reps })
                        assertEquals(listOf("60","62.5"), workout.exercises.single().sets.map { Format.number(it.weightKg) })

                        val afterFinish = inline(pressAnna("w:hist", "Тренировки"))
                        assertEquals(listOf("w:add:${client.id}", "w:view:$firstSession", "cl:view:${client.id}"), afterFinish.map { it.callbackData })
                        click(afterFinish.first().callbackData, "Мои упражнения")
                        val nextId = services.workouts.activeForClient(id, client.id)!!.id
                        assertNotEquals(firstSession, nextId)
                        assertTrue(services.workouts.getDetail(id, nextId).exercises.isEmpty())
                        say("Главное меню", "Выберите действие")
                        callback("w:finish:$firstSession", activeCard)
                        expect("уже завершена")
                        assertEquals(nextId, services.workouts.activeForClient(id, client.id)!!.id)

                        // A session left at the old template prompt also resumes directly at exercise selection.
                        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
                            connection.prepareStatement("UPDATE workout_session SET stage = 'CHOOSE_TEMPLATE' WHERE id = ?").use {
                                it.setLong(1, nextId)
                                assertEquals(1, it.executeUpdate())
                            }
                        }
                        callback("w:resume:$nextId")
                        expect("Мои упражнения")
                        click(plank.id.toString(), "45с")
                        say("45с", "45 с")
                        say("Готово", "Упражнений в тренировке: 1")
                        say("Ещё упражнение", "Мои упражнения")
                        click(hold.id.toString(), "16 30с")
                        say("16 30с", "16 кг × 30 с")
                        say("Готово", "Упражнений в тренировке: 2")
                        say("Завершить тренировку", "Тренировка завершена")
                        assertClientCard(expect("Анна Мария"))
                        val timed = services.workouts.lastWorkout(id, client.id)!!.exercises
                        assertEquals(listOf(plank.id, hold.id), timed.map { it.exercise.exerciseCatalogId })
                        assertEquals(listOf(45, 30), timed.map { it.sets.single().durationSec })
                        assertEquals(listOf(null, "16"), timed.map { Format.number(it.sets.single().weightKg) })
                        assertTrue(services.catalog.listMuscleGroups().isEmpty())

                        suspend fun editWorkoutFromHistory() {
                            val session = workout.session
                            val historyButtons = inline(pressAnna("w:hist", "Тренировки"))
                            assertEquals(listOf("w:add:${client.id}", "w:view:$nextId", "w:view:${session.id}", "cl:view:${client.id}"), historyButtons.map { it.callbackData })
                            click(historyButtons[2].callbackData, "Тренировка")
                            val editMenu = expect("Что изменить?").replyMarkup as InlineKeyboardMarkup
                            val rows = editMenu.keyboard.map { row -> row.map { it.text } }
                            for (label in listOf("📅 Дата", "⏱ Длительность", "📝 Заметки")) assertTrue(listOf(label) in rows)

                            click("w:duration", "Введите число минут")
                            services.workouts.updateSession(id, session.id, session.performedAt, null, "Заметка с сайта")
                            say("0", "положительное целое число")
                            say("45", "Тренировка обновлена")
                            expect("Что изменить?")
                            suspend fun savedSession() = services.workouts.getSession(id, session.id)
                            assertEquals("Заметка с сайта", savedSession().note)
                            assertEquals(session.performedAt, savedSession().performedAt)

                            click("w:note", "Заметка с сайта")
                            say("Работа над техникой", "Тренировка обновлена")
                            expect("Что изменить?")
                            click("w:date", "Дата тренировки")
                            say("31.02.2026", "существующую дату")
                            say("06.04.2026", "Тренировка обновлена")
                            expect("Что изменить?")
                            assertEquals(session.performedAt.with(java.time.LocalDate.of(2026, 4, 6)), savedSession().performedAt)
                            assertEquals(45, savedSession().durationMin)
                            assertEquals("Работа над техникой", savedSession().note)

                            click("w:duration", "45 мин")
                            say("Очистить поле", "Тренировка обновлена")
                            expect("Что изменить?")
                            assertNull(savedSession().durationMin)
                            click("w:note", "Работа над техникой")
                            say("Оставить", "Текущее значение")
                            expect("Что изменить?")
                            click("w:note", "Работа над техникой")
                            say("Очистить поле", "Тренировка обновлена")
                            expect("Что изменить?")
                            assertNull(savedSession().note)
                            click("w:done", "06.04.2026")
                        }
                        editWorkoutFromHistory()

                        suspend fun recordAndEditMeasurements() {
                            val menuMessageId = annaCard
                            val emptyHistory = pressAnna("m:hist", "замеров пока нет")
                            assertEquals("Добавить новый замер", inline(emptyHistory).first().text)
                            val draftFields = click(inline(emptyHistory).first().callbackData, "Выберите показатель")
                            assertEquals(listOf("Сохранить", "Отмена"), buttons(draftFields).take(2))
                            say("Обхват талии, см","Введите значение")
                            say("75","Обхват талии, см: 75")
                            say("Отмена","Отменено")
                            assertTrue(services.measurements.history(id,client.id).isEmpty())
                            // Reopen the original menu from the empty-history screen and save the first measurement.
                            callback("m:hist:${client.id}", menuMessageId)
                            expect("замеров пока нет")
                            click("m:add:${client.id}", "Выберите показатель")
                            say("Вес, кг","Введите значение")
                            say("70,5","Вес, кг: 70.5")
                            say("Обхват правого предплечья, см","Введите значение")
                            say("27,8","Обхват правого предплечья, см: 27.8")
                            say("Ширина спины, см","Введите ширину спины")
                            say("39,1","Ширина спины, см: 39.1")
                            say("Сохранить","Замер сохранён")
                            val measurement = services.measurements.history(id,client.id).single()
                            val savedMeasurements = inline(pressAnna("m:hist", "История замеров"))
                            assertEquals(listOf("m:add:${client.id}", "m:view:${measurement.id}", "cl:view:${client.id}"), savedMeasurements.map { it.callbackData })
                            click(savedMeasurements[1].callbackData, "70.5")
                            click("me:edit","Вес, кг: 70.5")
                            say("Обхват талии, см","Введите значение")
                            say("74","Обхват талии, см: 74")
                            say("Обхват левого предплечья, см","Введите значение")
                            say("26,4","Обхват левого предплечья, см: 26.4")
                            say("Сохранить","Обновлено")
                            val updated = services.measurements.history(id,client.id).single()
                            assertEquals("70.5",Format.number(updated.weightKg))
                            assertEquals("74",Format.number(updated.waistCm))
                            assertEquals("27.8",Format.number(updated.rightForearmCm))
                            assertEquals("26.4",Format.number(updated.leftForearmCm))
                            assertEquals("39.1",Format.number(updated.backWidthCm))
                            callback("m:add", menuMessageId)
                            expect("Выберите подопечного для замеров")
                            expect("Подопечные")
                            click(ivan.id.toString(), "Иван Петров")
                            say("Отмена", "Отменено")
                        }
                        recordAndEditMeasurements()

                        repeat(25) { services.catalog.addExercise(id, name = "Вариант $it") }
                        val fullCatalog = inline(say("Упражнения", "Мои упражнения"))
                        val secondPage = inline(click(fullCatalog.single { it.text == "Дальше →" }.callbackData, "2 из 2"))
                        assertEquals("➕ Добавить упражнение", secondPage.first().text)
                        assertTrue(secondPage.any { it.callbackData == "ex:view:${hold.id}" })
                    }
                } finally {
                    runBlocking { scope.coroutineContext.job.cancelAndJoin() }
                    telegram.close()
                }
            }
        }
    }
}
