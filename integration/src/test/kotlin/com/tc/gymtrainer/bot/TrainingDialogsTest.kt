package com.tc.gymtrainer.bot

import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.config.ServerConfig
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.Sex
import com.tc.gymtrainer.domain.WorkoutDetail
import com.tc.gymtrainer.service.BotRole
import com.tc.gymtrainer.service.CatalogEdit
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
import com.tc.gymtrainer.service.ExerciseEdit
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class TrainingDialogsTest {
    @Test
    fun `coach can skip and edit details, discard drafts and record sets and measurements with buttons`() = runDialogs(false)

    @Test
    fun `webhooks preserve every dialog across cold starts and ignore redelivery`() = runDialogs(true)

    /** [coldStarts]: every update meets a new handler and is delivered twice, as on a hosted webhook. */
    private fun runDialogs(coldStarts: Boolean) {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val config = AppConfig(DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, 4), ServerConfig("127.0.0.1", 0))
            withTestPlatform(config) { services, training, _ ->
                val telegram = TestTelegram(100, "trainer_test_bot")
                val json = Json { ignoreUnknownKeys = true }
                try {
                    runBlocking {
                        // The long-lived handler of a locally polling bot. A restart replaces it.
                        var handler = UpdateHandler(training, telegram, "trainer_test_bot")
                        BotRole.TRAINING.record("trainer_test_bot")
                        BotRole.LOGIN_CODES.record("auth_test_bot")
                        val id = services.trainers.registerForWeb("profile_test", "Test", "Coach").trainer.id
                        services.telegramLinks.confirmSignup(services.telegramLinks.createSignupLink(id).url.substringAfter("?start="), 123)
                        services.admin.approve(id)
                        services.catalog.saveGoal(null, CatalogEdit("Поддержание"))
                        services.telegramLinks.connectTrainingBot(services.telegramLinks.createTrainingLink(id).url.substringAfter("?start="), 123)

                        val user = """{"id":123,"is_bot":false,"first_name":"Test"}"""
                        val chat = """{"id":123,"type":"private","first_name":"Test"}"""
                        var updateId = 0
                        suspend fun update(body: String) {
                            val raw = """{"update_id":${++updateId},$body}"""
                            val event = json.decodeFromString(UpdateDeserializationStrategy, raw)
                            if (coldStarts) {
                                repeat(2) { UpdateHandler(training, telegram, "trainer_test_bot").process(event) }
                            } else handler.process(event)
                        }
                        suspend fun text(value: String) = update(
                            """"message":{"message_id":${++updateId},"date":1700000000,"from":$user,"chat":$chat,"text":${JsonPrimitive(value)}}"""
                        )
                        suspend fun callback(value: String, messageId: Int = telegram.messageId.get(), originalKeyboard: InlineKeyboardMarkup? = null) = update(
                            """"callback_query":{"id":"cb$updateId","from":$user,"chat_instance":"chat","data":${JsonPrimitive(value)},
                            "message":{"message_id":$messageId,"date":1700000000,"chat":$chat,
                            "from":{"id":100,"is_bot":true,"first_name":"Bot"},"text":"Prompt"${originalKeyboard?.let { ",\"reply_markup\":" + json.encodeToString(InlineKeyboardMarkup.serializer(), it) }.orEmpty()}}}"""
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
                        suspend fun pressButton(message: SendTextMessage, label: String, expected: String) =
                            click(inline(message).single { it.text == label }.callbackData, expected)
                        fun assertClientCard(message: SendTextMessage) {
                            assertEquals(listOf("Тренировки", "Замеры"), inline(message).map { it.text })
                        }
                        fun assertActiveWorkoutMenu(message: SendTextMessage, clientId: Long, sessionId: Long) {
                            val actions = inline(message)
                            assertEquals(listOf("Продолжить тренировку", "Завершить тренировку", "Отменить тренировку", "Прошедшие тренировки", "↩️ К подопечному"), actions.map { it.text })
                            assertEquals(listOf("w:resume:$sessionId", "w:finish:$sessionId", "w:cancel:$clientId:$sessionId", "w:hist:$clientId:0", "cl:view:$clientId"), actions.map { it.callbackData })
                        }
                        suspend fun openClient(clientId: Long, next: String): Int {
                            val listing = inline(say("Подопечные", "Ваши подопечные"))
                            assertEquals("➕ Добавить подопечного", listing.last().text)
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
                                assertTrue(buttons(fields).containsAll(listOf("Вес, кг", "Рост, см", "Возраст", "Пол", "Цель", "Заметки", "Ник Telegram")))
                                assertFalse("Телефон" in buttons(fields))
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
                            // The web editor does not show the details set in the bot and must preserve them.
                            services.clients.update(id, ivan.id) { it.copy(notes = "запись с сайта") }
                            assertEquals(edited.profile().copy(notes = "запись с сайта"), services.clients.getOwned(id,ivan.id).profile())
                            return ivan
                        }
                        val ivan = createClientWithMeasurements()

                        val annaCard = openClient(client.id, "Анна Мария")
                        openClient(ivan.id, "Иван Петров")
                        suspend fun pressAnna(action: String, expected: String): SendTextMessage {
                            callback("$action:${client.id}", annaCard)
                            return expect(expected)
                        }
                        assertTrue(services.catalog.exercises().isEmpty())
                        say("Упражнения", "Категорий упражнений пока нет")
                        val chest = services.catalog.createMuscleGroup("Грудные мышцы")
                        val core = services.catalog.createMuscleGroup("Кор")
                        val emptyGroup = inline(click(inline(say("Упражнения", "Выберите группу")).first().callbackData, "пока нет упражнений"))
                        click(emptyGroup.single().callbackData, "Выберите группу")
                        val initialMenu = inline(pressAnna("w:hist", "Тренировки"))
                        assertEquals(listOf("Записать тренировку", "Прошедшие тренировки", "↩️ К подопечному"), initialMenu.map { it.text })
                        val emptyHistory = inline(click(initialMenu[1].callbackData, "Завершённых тренировок пока нет"))
                        assertEquals("↩️ К тренировкам", emptyHistory.single().text)
                        val backToWorkouts = inline(click(emptyHistory.single().callbackData, "Тренировки"))
                        val emptyPicker = inline(click(backToWorkouts.first().callbackData, "ещё не опубликовал упражнения"))
                        assertEquals("↩️ Закрыть тренировку", emptyPicker.single().text)
                        val firstSession = services.workouts.activeForClient(id, client.id)!!.id
                        val emptyCatalogExit = click(emptyPicker.single().callbackData, "Тренировки · ${client.fullName}")
                        assertActiveWorkoutMenu(emptyCatalogExit, client.id, firstSession)
                        suspend fun checkPreviouslySentButtons() {
                            val oldPicker = InlineKeyboardMarkup(listOf(listOf(CallbackDataInlineKeyboardButton("Закрыть тренировку", "cl:view:${client.id}"))))
                            callback("cl:view:${client.id}", originalKeyboard = oldPicker)
                            assertActiveWorkoutMenu(expect("Тренировки · ${client.fullName}"), client.id, firstSession)
                            val oldProfile = InlineKeyboardMarkup(listOf(
                                listOf(CallbackDataInlineKeyboardButton("Продолжить тренировку", "w:resume:$firstSession")),
                                listOf(CallbackDataInlineKeyboardButton("Закрыть тренировку", "w:finish:$firstSession")),
                                listOf(CallbackDataInlineKeyboardButton("Тренировки", "w:hist:${client.id}"), CallbackDataInlineKeyboardButton("Замеры", "m:hist:${client.id}")),
                            ))
                            for (action in listOf("w:resume:$firstSession", "w:finish:$firstSession")) {
                                callback(action, originalKeyboard = oldProfile)
                                assertActiveWorkoutMenu(expect("Тренировки · ${client.fullName}"), client.id, firstSession)
                            }
                        }
                        checkPreviouslySentButtons()
                        val press = services.catalog.saveExercise(null, ExerciseEdit("Жим", chest.id))
                        val workoutGroups = inline(click(inline(emptyCatalogExit).first().callbackData, "Выберите группу мышц"))
                        assertEquals(listOf(chest.name, "↩️ Закрыть тренировку"), workoutGroups.map { it.text })
                        val choices = inline(click(workoutGroups.first().callbackData, "Выберите упражнение"))
                        assertEquals(listOf("Жим", "↩️ Вернуться к категориям"), choices.map { it.text })
                        val setPrompt = click(choices.first().callbackData, "Введите подходы")
                        assertEquals(listOf("Жим", "На повторения", "На время"), setPrompt.textSources.orEmpty().filterIsInstance<BoldTextSource>().map { it.source })
                        assertEquals(listOf("60 10", "0 12", "45с", "16 30с"), setPrompt.textSources.orEmpty().filterIsInstance<CodeTextSource>().map { it.source })
                        say("60 10\nошибка", "строку 2")
                        assertTrue(services.workouts.getDetail(id, firstSession).exercises.single().sets.isEmpty())
                        say("60 10\n62,5 8\n0 12", "Подходов в упражнении: 3")
                        say("Убрать последний подход", "Подходов в упражнении: 2")
                        assertTrue(services.workouts.history(id, client.id).isEmpty())
                        assertEquals(listOf(10, 8), services.workouts.getDetail(id, firstSession).exercises.single().sets.map { it.reps })
                        assertEquals(press.id, services.workouts.getDetail(id, firstSession).exercises.single().exercise.exerciseCatalogId)
                        val plank = services.catalog.saveExercise(null, ExerciseEdit("Планка", core.id))
                        val hold = services.catalog.saveExercise(null, ExerciseEdit("Удержание гири", core.id, "Удерживайте гирю у груди."))
                        val categoryMenu = inline(say("Упражнения", "Выберите группу"))
                        assertEquals(setOf(chest.name, core.name), categoryMenu.map { it.text }.toSet())
                        val catalogMenu = inline(click(categoryMenu.single { it.text == core.name }.callbackData, "Упражнения"))
                        assertEquals(setOf("↩️ Вернуться к категориям", plank.name, hold.name), catalogMenu.map { it.text }.toSet())
                        val catalogCard = click(catalogMenu.single { it.text == plank.name }.callbackData, "Планка")
                        assertEquals("Название: Планка\n\nОписание: не указано", catalogCard.text)
                        val toExercises = inline(catalogCard).single()
                        assertEquals("↩️ К упражнениям", toExercises.text)
                        val savedExercises = inline(click(toExercises.callbackData, "Упражнения"))
                        val holdCard = click(savedExercises.single { it.text == hold.name }.callbackData, hold.name)
                        assertEquals("Название: Удержание гири\n\nОписание: Удерживайте гирю у груди.", holdCard.text)

                        // Restart the bot against the same PostgreSQL: the dialog lives on the platform, not in the process.
                        handler = UpdateHandler(training, telegram, "trainer_test_bot")
                        openClient(client.id, "Идёт тренировка")
                        val currentMenu = click("w:hist:${client.id}", "Тренировки")
                        assertActiveWorkoutMenu(currentMenu, client.id, firstSession)
                        val activeList = inline(currentMenu)
                        val activeCard = telegram.messageId.get()
                        assertEquals("↩️ К тренировкам", inline(click(activeList.single { it.text == "Прошедшие тренировки" }.callbackData, "Завершённых тренировок пока нет")).single().text)
                        openClient(ivan.id, "Иван Петров")
                        callback(activeList.first().callbackData, activeCard)
                        val restored = expect("Подходов в упражнении: 2")
                        assertTrue(restored.text.contains("Жим") && restored.text.contains("62.5 кг × 8"))
                        assertTrue(buttons(restored).containsAll(listOf("Готово", "Убрать последний подход", "↩️ Главное меню")))
                        val review = inline(say("Готово", "Упражнений в тренировке: 1"))
                        assertEquals(listOf("Ещё упражнение", "Завершить тренировку", "Отменить тренировку", "↩️ К тренировкам"), review.map { it.text })
                        val selection = inline(click(review.first().callbackData, "Выберите группу мышц"))
                        assertEquals(setOf(chest.name, core.name, "↩️ Закрыть тренировку"), selection.map { it.text }.toSet())
                        val category = inline(click(selection.single { it.text == chest.name }.callbackData, "Выберите упражнение"))
                        val groupsAgain = inline(click(category.single { it.text == "↩️ Вернуться к категориям" }.callbackData, "Выберите группу мышц"))
                        val activeMenu = click(groupsAgain.single { it.text == "↩️ Закрыть тренировку" }.callbackData, "Тренировки · ${client.fullName}")
                        assertActiveWorkoutMenu(activeMenu, client.id, firstSession)
                        assertEquals(firstSession, services.workouts.activeForClient(id, client.id)?.id)
                        assertTrue(services.workouts.history(id, client.id).isEmpty())
                        assertClientCard(click(inline(activeMenu).single { it.text == "↩️ К подопечному" }.callbackData, "Идёт тренировка"))
                        suspend fun finishWithoutDuration(): WorkoutDetail {
                            val returnMenu = pressAnna("w:hist", "Тренировки")
                            val finishPrompt = pressButton(returnMenu, "Завершить тренировку", "Длительность тренировки")
                            assertEquals(listOf("Указать продолжительность", "Завершить сразу", "↩️ К тренировкам"), inline(finishPrompt).map { it.text })
                            val stillActive = pressButton(finishPrompt, "↩️ К тренировкам", "Тренировки")
                            assertActiveWorkoutMenu(stillActive, client.id, firstSession)
                            assertTrue(services.workouts.history(id, client.id).isEmpty())
                            val reopenedPrompt = pressButton(stillActive, "Завершить тренировку", "Длительность тренировки")
                            pressButton(reopenedPrompt, "Завершить сразу", "Тренировка завершена")
                            assertClientCard(expect("Анна Мария"))
                            assertNull(services.workouts.activeForClient(id, client.id))
                            val workout = services.workouts.getDetail(id, services.workouts.history(id, client.id).first().id)
                            assertNull(workout.session.durationMin)
                            assertEquals(firstSession, workout.session.id)
                            assertEquals(listOf(10,8), workout.exercises.single().sets.map { it.reps })
                            assertEquals(listOf("60","62.5"), workout.exercises.single().sets.map { Format.number(it.weightKg) })
                            return workout
                        }
                        val workout = finishWithoutDuration()

                        val afterFinish = inline(pressAnna("w:hist", "Тренировки"))
                        assertEquals(listOf("Записать тренировку", "Прошедшие тренировки", "↩️ К подопечному"), afterFinish.map { it.text })
                        val firstHistory = inline(click(afterFinish[1].callbackData, "Прошедшие тренировки"))
                        assertEquals(listOf("w:view:$firstSession", "w:hist:${client.id}"), firstHistory.map { it.callbackData })
                        val menu = inline(click(firstHistory.last().callbackData, "Тренировки"))
                        click(menu.first().callbackData, "Выберите группу мышц")
                        val nextId = services.workouts.activeForClient(id, client.id)!!.id
                        assertNotEquals(firstSession, nextId)
                        assertTrue(services.workouts.getDetail(id, nextId).exercises.isEmpty())
                        say("Главное меню", "Выберите действие")
                        callback("w:finish:$firstSession", activeCard)
                        expect("уже завершена")
                        assertEquals(nextId, services.workouts.activeForClient(id, client.id)!!.id)

                        suspend fun finishTimedExercisesWithPreset() {
                            callback("w:resume:$nextId")
                            expect("Выберите группу мышц")
                            click(chest.id.toString(), "Выберите упражнение")
                            click("groups", "Выберите группу мышц")
                            click(core.id.toString(), "Выберите упражнение")
                            click(plank.id.toString(), "45с")
                            say("45с", "45 с")
                            val plankReview = inline(say("Готово", "Упражнений в тренировке: 1"))
                            click(plankReview.single { it.text == "Ещё упражнение" }.callbackData, "Выберите группу мышц")
                            click(core.id.toString(), "Выберите упражнение")
                            click(hold.id.toString(), "16 30с")
                            say("16 30с", "16 кг × 30 с")
                            val lastReview = say("Готово", "Упражнений в тренировке: 2")
                            val finishFromReview = pressButton(lastReview, "Завершить тренировку", "Длительность тренировки")
                            val presets = pressButton(finishFromReview, "Указать продолжительность", "Сколько минут?")
                            assertEquals(listOf("30 мин", "45 мин", "50 мин", "60 мин", "90 мин", "Свой вариант", "Завершить сразу", "↩️ К завершению"), inline(presets).map { it.text })
                            pressButton(presets, "50 мин", "Тренировка завершена")
                            assertClientCard(expect("🕒 Длительность: 50 мин"))
                            pressButton(presets, "30 мин", "уже завершена")
                            assertEquals(50, services.workouts.getDetail(id, nextId).session.durationMin)
                            val timed = services.workouts.getDetail(id, nextId).exercises
                            assertEquals(listOf(plank.id, hold.id), timed.map { it.exercise.exerciseCatalogId })
                            assertEquals(listOf(45, 30), timed.map { it.sets.single().durationSec })
                            assertEquals(listOf(null, "16"), timed.map { Format.number(it.sets.single().weightKg) })
                        }
                        finishTimedExercisesWithPreset()

                        suspend fun cancelWorkoutFromReview() {
                            val menu = inline(click("w:hist:${ivan.id}", "Тренировки"))
                            val groups = inline(click(menu.first().callbackData, "Выберите группу мышц"))
                            val exercises = inline(click(groups.single { it.text == chest.name }.callbackData, "Выберите упражнение"))
                            click(exercises.single { it.text == press.name }.callbackData, "Введите подходы")
                            say("20 5", "Подходов в упражнении: 1")
                            val summary = inline(say("Готово", "Упражнений в тренировке: 1"))
                            val cancel = summary.single { it.text == "Отменить тренировку" }
                            val session = services.workouts.activeForClient(id, ivan.id)!!
                            val detail = services.workouts.getDetail(id, session.id)
                            val choices = inline(click(cancel.callbackData, "Отменить тренировку?"))
                            val keep = choices.single { it.text == "↩️ Оставить тренировку" }
                            val kept = click(keep.callbackData, "Тренировки · ${ivan.fullName}")
                            assertActiveWorkoutMenu(kept, ivan.id, session.id)
                            assertEquals(detail, services.workouts.getDetail(id, session.id))

                            val resumed = inline(click(inline(kept).first().callbackData, "Упражнений в тренировке: 1"))
                            val confirm = inline(click(resumed.single { it.text == cancel.text }.callbackData, "все её подходы будут удалены"))
                                .single { it.text == "Да, отменить" }
                            val cancelled = inline(click(confirm.callbackData, "Тренировка отменена."))
                            assertEquals("Записать тренировку", cancelled.first().text)
                            assertNull(services.workouts.activeForClient(id, ivan.id))
                            assertTrue(services.workouts.history(id, ivan.id).isEmpty())

                            click(cancelled.first().callbackData, "Выберите группу мышц")
                            val next = services.workouts.activeForClient(id, ivan.id)!!
                            assertNotEquals(session.id, next.id)
                            val stale = click(confirm.callbackData, "Эта тренировка уже не активна")
                            assertActiveWorkoutMenu(stale, ivan.id, next.id)
                            val fromMenu = inline(click(inline(stale).single { it.text == cancel.text }.callbackData, "Отменить тренировку?"))
                            click(fromMenu.single { it.text == confirm.text }.callbackData, "Тренировка отменена.")
                            assertEquals(listOf(nextId, firstSession), services.workouts.history(id, client.id).map { it.id })
                        }
                        cancelWorkoutFromReview()

                        suspend fun finishWithCustomMinutes() {
                            val session = services.workouts.startOrResume(id, ivan.id)
                            val menu = click("w:hist:${ivan.id}", "Тренировки")
                            val finish = pressButton(menu, "Завершить тренировку", "Длительность тренировки")
                            val presets = pressButton(finish, "Указать продолжительность", "Сколько минут?")
                            val custom = pressButton(presets, "Свой вариант", "в минутах целым числом")
                            val back = pressButton(custom, "↩️ К выбору длительности", "Сколько минут?")
                            val confirm = pressButton(back, "↩️ К завершению", "Длительность тренировки")
                            val choose = pressButton(confirm, "Указать продолжительность", "Сколько минут?")
                            pressButton(choose, "Свой вариант", "Например, 75")
                            say("0", "положительное целое число минут")
                            say("1:15", "положительное целое число минут")
                            assertEquals(session.id, services.workouts.activeForClient(id, ivan.id)?.id)
                            say("75", "Тренировка завершена")
                            assertClientCard(expect("🕒 Длительность: 75 мин"))
                            assertEquals(75, services.workouts.history(id, ivan.id).single().durationMin)
                            assertNull(services.workouts.activeForClient(id, ivan.id))
                        }
                        finishWithCustomMinutes()

                        suspend fun editWorkoutFromHistory() {
                            val session = workout.session
                            val workoutMenu = inline(pressAnna("w:hist", "Тренировки"))
                            val historyButtons = inline(click(workoutMenu.single { it.text == "Прошедшие тренировки" }.callbackData, "Прошедшие тренировки"))
                            assertEquals(listOf("w:view:$nextId", "w:view:${session.id}", "w:hist:${client.id}"), historyButtons.map { it.callbackData })
                            click(historyButtons.first().callbackData, "Тренировка")
                            val firstEditor = inline(expect("Что изменить?"))
                            val back = firstEditor.single { it.text == "↩️ К прошедшим тренировкам" }
                            val historyAgain = inline(click(back.callbackData, "Прошедшие тренировки"))
                            click(historyAgain[1].callbackData, "Тренировка")
                            val editMenu = expect("Что изменить?").replyMarkup as InlineKeyboardMarkup
                            val rows = editMenu.keyboard.map { row -> row.map { it.text } }
                            for (label in listOf("📅 Дата", "🕒 Длительность", "📝 Заметки")) assertTrue(listOf(label) in rows)

                            click("w:duration", "Введите число минут")
                            services.workouts.updateSession(id, session.id, session.performedAt, null, "Заметка с сайта")
                            say("0", "положительное целое число")
                            say("45", "Тренировка обновлена")
                            expect("Что изменить?")
                            suspend fun savedSession() = services.workouts.getDetail(id, session.id).session
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

                        // The bot keeps the trainer's calendar: the same workout is a day later in a zone far to the east.
                        suspend fun datesFollowTheTrainersTimeZone() {
                            val evening = java.time.OffsetDateTime.parse("2026-04-06T20:00:00Z")
                            services.workouts.updateSession(id, workout.session.id, evening, null, null)
                            services.trainers.updateTimeZone(id, "Pacific/Kiritimati")
                            val history = inline(pressAnna("w:hist", "Тренировки")).single { it.text == "Прошедшие тренировки" }
                            val listed = inline(click(history.callbackData, "Прошедшие тренировки"))
                            assertTrue(listed.any { it.text == "🗓 07.04.2026" }, listed.map { it.text }.toString())
                            click("w:view:${workout.session.id}", "Тренировка 07.04.2026")
                            expect("Что изменить?")
                            click("w:date", "Дата тренировки · 07.04.2026")
                            say("10.04.2026", "Тренировка обновлена")
                            expect("Что изменить?")
                            // 10 April, 10:00 on Kiritimati is still the evening of 9 April in UTC.
                            assertEquals(java.time.OffsetDateTime.parse("2026-04-09T20:00:00Z").toInstant(),
                                services.workouts.getDetail(id, workout.session.id).session.performedAt.toInstant())
                            click("w:done", "Тренировка 10.04.2026")
                            services.trainers.updateTimeZone(id, "UTC")
                        }
                        datesFollowTheTrainersTimeZone()

                        suspend fun recordAndEditMeasurements() {
                            val menuMessageId = annaCard
                            val emptyHistory = pressAnna("m:hist", "замеров пока нет")
                            assertEquals("➕ Добавить новый замер", inline(emptyHistory).first().text)
                            assertEquals("↩️ К подопечному", inline(emptyHistory).last().text)
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

                        repeat(25) { services.catalog.saveExercise(null, ExerciseEdit("Вариант $it", core.id)) }
                        val allGroups = inline(say("Упражнения", "Выберите группу"))
                        val fullCatalog = inline(click(allGroups.single { it.text == core.name }.callbackData, core.name))
                        val secondPage = inline(click(fullCatalog.single { it.text == "Дальше →" }.callbackData, "2 из 2"))
                        assertTrue(secondPage.none { it.callbackData == "ex:add" })
                        assertTrue(secondPage.any { it.callbackData == "ex:view:${hold.id}" })
                        click(secondPage.single { it.text == "↩️ Назад" }.callbackData, "1 из 2")
                    }
                } finally {
                    telegram.close()
                }
            }
        }
    }
}
