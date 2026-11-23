package com.tc.gymtrainer

import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.db.DatabaseFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.Statement

class InitialMigrationTest {
    private fun Statement.one(query: String): String? = executeQuery(query).use { rows ->
        assertTrue(rows.next(), query)
        rows.getString(1)
    }

    @Test
    fun `first start creates empty catalogs and restarting keeps the admin's data`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, maxPoolSize = 2)
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                connection.createStatement().use { sql ->
                    DatabaseFactory.init(db).use {
                        for (table in listOf("trainer", "telegram_bot_identity", "telegram_dialog", "telegram_write_receipt", "muscle_group",
                            "exercise_catalog", "goal_catalog", "trainer_notice", "rate_limit", "audit_event")) {
                            assertEquals("0", sql.one("SELECT count(*) FROM $table"), table)
                        }
                        sql.executeUpdate("INSERT INTO goal_catalog (name) VALUES ('Выносливость')")
                    }
                    // With migrations switched off the service starts without connecting, and the schema stays as it is.
                    DatabaseFactory.init(db.copy(migrateOnStart = false)).use {
                        sql.executeQuery("SELECT version, script FROM flyway_schema_history WHERE success AND type = 'SQL' ORDER BY installed_rank").use { rows ->
                            assertTrue(rows.next())
                            assertEquals("1", rows.getString("version"))
                            assertEquals("V1__training_collector.sql", rows.getString("script"))
                            assertFalse(rows.next())
                        }
                        sql.executeQuery("SELECT name, hidden FROM goal_catalog").use { rows ->
                            assertTrue(rows.next())
                            assertEquals("Выносливость", rows.getString("name"))
                            assertFalse(rows.getBoolean("hidden"))
                            assertFalse(rows.next())
                        }
                    }
                    // The deployment step applies nothing when the schema is current.
                    DatabaseFactory.migrate(db)
                    assertEquals("1", sql.one("SELECT count(*) FROM flyway_schema_history WHERE success AND type = 'SQL'"))
                }
            }
        }
    }

    @Test
    fun `the schema computes full names and removes history with its owner`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, maxPoolSize = 2)
            DatabaseFactory.init(db).use {
                DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                    connection.createStatement().use { sql ->
                        sql.executeUpdate("INSERT INTO trainer (id, username, first_name, last_name) VALUES (1, 'coach', 'Анна', 'Тест'), (2, 'solo', 'Иван', NULL)")
                        assertEquals("Анна Тест,Иван", sql.one("SELECT string_agg(full_name, ',' ORDER BY id) FROM trainer"))
                        sql.executeUpdate("INSERT INTO muscle_group (id, name) VALUES (1, 'Ноги')")
                        assertTrue(runCatching { sql.executeUpdate("INSERT INTO muscle_group (name) VALUES ('ноги')") }.isFailure)
                        sql.executeUpdate("INSERT INTO exercise_catalog (id, muscle_group_id, name) VALUES (1, 1, 'Присед')")
                        sql.executeUpdate("INSERT INTO client (id, trainer_id, first_name, last_name) VALUES (1, 1, 'Пётр', 'Петров')")
                        assertEquals("Пётр Петров", sql.one("SELECT full_name FROM client"))
                        sql.executeUpdate("INSERT INTO workout_session (id, client_id, trainer_id, performed_at) VALUES (1, 1, 1, now())")
                        sql.executeUpdate("INSERT INTO workout_exercise (id, session_id, exercise_catalog_id, exercise_name, order_index) VALUES (1, 1, 1, 'Присед', 0)")
                        sql.executeUpdate("INSERT INTO exercise_set (exercise_id, set_number, reps) VALUES (1, 1, 10)")
                        sql.executeUpdate("INSERT INTO body_measurement (client_id, measured_on, weight_kg) VALUES (1, '2026-01-01', 80)")
                        sql.executeUpdate("INSERT INTO login_request (id, trainer_id, secret_hash, number, expires_at) VALUES ('r', 1, 'h', 42, now())")
                        sql.executeUpdate("INSERT INTO trainer_notice (trainer_id, kind, expires_at) VALUES (1, 'ACTIVATED', now())")
                        // A deleted catalog exercise leaves the recorded workout intact.
                        sql.executeUpdate("DELETE FROM exercise_catalog WHERE id = 1")
                        assertEquals("Присед", sql.one("SELECT exercise_name FROM workout_exercise WHERE exercise_catalog_id IS NULL"))
                        // History goes with the trainer.
                        sql.executeUpdate("DELETE FROM trainer WHERE id = 1")
                        for (table in listOf("client", "workout_session", "workout_exercise", "exercise_set", "body_measurement", "trainer_notice", "login_request")) {
                            assertEquals("0", sql.one("SELECT count(*) FROM $table"), table)
                        }
                    }
                }
            }
        }
    }
}
