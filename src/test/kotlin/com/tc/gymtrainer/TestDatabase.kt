package com.tc.gymtrainer

import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.db.DatabaseFactory
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Единый Postgres-контейнер на все интеграционные тесты. Поднимается один раз
 * (lazy), Flyway накатывает реальные миграции, Exposed подключается глобально.
 */
object TestDatabase {
    val config: DatabaseConfig by lazy {
        val pg = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, maxPoolSize = 4).also { DatabaseFactory.init(it) }
    }

    fun ensureStarted() {
        config
    }
}

/** База для интеграционных тестов: общий контейнер + очистка данных между тестами. */
abstract class AbstractDbTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun startDatabase() = TestDatabase.ensureStarted()
    }

    @BeforeEach
    fun cleanTransactionalData() {
        // Оставляем общие группы мышц.
        transaction {
            exec(
                """
                TRUNCATE TABLE exercise_set, workout_exercise, workout_session,
                               body_measurement, exercise_catalog, client, trainer, telegram_bot_identity, goal_catalog, admin_session
                RESTART IDENTITY CASCADE
                """.trimIndent(),
            )
        }
    }
}
