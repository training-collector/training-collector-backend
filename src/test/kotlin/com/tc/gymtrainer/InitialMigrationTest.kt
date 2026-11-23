package com.tc.gymtrainer

import com.tc.gymtrainer.config.DatabaseConfig
import com.tc.gymtrainer.db.DatabaseFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class InitialMigrationTest {
    @Test
    fun `first start creates empty catalogs and restarting keeps the admin's data`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val db = DatabaseConfig(pg.jdbcUrl, pg.username, pg.password, maxPoolSize = 2)
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
                connection.createStatement().use { sql ->
                    DatabaseFactory.init(db).use {
                        for (table in listOf("trainer", "telegram_bot_identity", "telegram_dialog", "telegram_write_receipt", "muscle_group", "exercise_catalog", "goal_catalog")) {
                            sql.executeQuery("SELECT count(*) FROM $table").use { rows ->
                                assertTrue(rows.next())
                                assertEquals(0, rows.getInt(1), table)
                            }
                        }
                        sql.executeUpdate("INSERT INTO goal_catalog (name) VALUES ('Выносливость')")
                    }
                    DatabaseFactory.init(db).use {
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
                }
            }
        }
    }
}
