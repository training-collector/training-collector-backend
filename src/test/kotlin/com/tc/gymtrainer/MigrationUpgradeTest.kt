package com.tc.gymtrainer

import com.tc.gymtrainer.db.DatabaseFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class MigrationUpgradeTest {
    @Test
    fun `a consolidated V1 database adopts the repeatable schema and keeps measurements on subsequent runs`() {
        val db = TestDatabase.config
        val schema = "upgrade_from_consolidated_v1"
        val flyway = DatabaseFactory.migrationConfig().dataSource(db.jdbcUrl, db.user, db.password).schemas(schema).load()
        flyway.migrate()
        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { sql ->
                // Reconstruct the immediately preceding versioned installation in this test schema.
                sql.execute("UPDATE training_schema_history SET version = '1', script = 'V1__training_collector.sql' WHERE type = 'SQL'")
                sql.execute("ALTER TABLE body_measurement DROP COLUMN back_width_cm")
                sql.execute("ALTER TABLE exercise_catalog DROP COLUMN description, ADD COLUMN trainer_id BIGINT REFERENCES trainer(id)")
                sql.execute("ALTER TABLE client ALTER COLUMN goal TYPE varchar(32)")
                sql.execute("ALTER TABLE workout_session DROP COLUMN stage")
                sql.execute("INSERT INTO trainer(full_name) VALUES ('Тренер')")
                sql.execute("INSERT INTO client(trainer_id, full_name, goal) VALUES (1, 'Клиент', 'WEIGHT_LOSS')")
                sql.execute("INSERT INTO exercise_catalog(trainer_id, name) VALUES (1, 'Личное упражнение')")
                sql.execute("INSERT INTO body_measurement(client_id, measured_on, left_forearm_cm) VALUES (1, CURRENT_DATE, 28)")
                sql.execute("INSERT INTO workout_session(client_id, trainer_id, performed_at) VALUES (1, 1, now())")
                assertEquals(1, flyway.migrate().migrationsExecuted)
                sql.execute("INSERT INTO exercise_catalog(name, description) VALUES ( 'Жим', 'Лёжа на скамье')")
                sql.executeQuery("SELECT hidden FROM exercise_catalog WHERE name = 'Личное упражнение'").use {
                    assertTrue(it.next() && it.getBoolean(1))
                }
                sql.executeQuery("SELECT goal FROM client WHERE id = 1").use {
                    assertTrue(it.next())
                    assertEquals("Похудение", it.getString(1))
                }
                sql.executeQuery("SELECT count(*) FROM goal_catalog").use { assertTrue(it.next() && it.getInt(1) == 0) }
                sql.execute("UPDATE body_measurement SET back_width_cm = 39")
                sql.execute("INSERT INTO workout_session(client_id, trainer_id, performed_at, stage) VALUES (1, 1, now(), 'CHOOSE_EXERCISE')")
                // A later edit of the repeatable script must also be safe on a populated database.
                sql.execute("UPDATE training_schema_history SET checksum = 0 WHERE version IS NULL AND type = 'SQL'")
                assertEquals(1, flyway.migrate().migrationsExecuted)
                assertEquals(0, flyway.migrate().migrationsExecuted)
                sql.executeQuery("SELECT stage FROM workout_session ORDER BY id").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("COMPLETED", rows.getString(1))
                    assertTrue(rows.next())
                    assertEquals("CHOOSE_EXERCISE", rows.getString(1))
                }
                sql.executeQuery("SELECT left_forearm_cm, back_width_cm FROM body_measurement").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(28, rows.getInt(1))
                    assertEquals(39, rows.getInt(2))
                }
            }
        }
    }

    @Test
    fun `upgrade preserves existing Telegram profiles, set results and body history`() {
        val db = TestDatabase.config
        val schema = "upgrade_from_v2"
        val flyway = DatabaseFactory.migrationConfig().dataSource(db.jdbcUrl, db.user, db.password).schemas(schema).load()
        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            connection.schema = schema
            connection.createStatement().use { sql ->
                val fixture = checkNotNull(javaClass.getResource("/fixtures/pre_api_schema.sql")).readText()
                sql.execute(fixture)
                sql.executeUpdate("INSERT INTO trainer (telegram_user_id, full_name, phone) VALUES (9123, 'Тренер', '+12025550123')")
                sql.executeUpdate("INSERT INTO client (trainer_id, full_name) VALUES (1, 'Подопечный')")
                sql.executeUpdate("INSERT INTO workout_session (client_id, trainer_id, performed_at) VALUES (1, 1, now())")
                sql.executeUpdate("INSERT INTO workout_exercise (session_id, muscle_group_id, exercise_name, order_index) VALUES (1, 1, 'Жим', 0)")
                sql.executeUpdate("INSERT INTO exercise_set (exercise_id, set_number, reps, weight_kg) VALUES (1, 1, 10, 60)")
                sql.executeUpdate("INSERT INTO body_measurement (client_id, measured_on, waist_cm) VALUES (1, '2026-07-12', 80)")
                sql.executeUpdate("INSERT INTO trainer (telegram_user_id, full_name) VALUES (9124, 'Второй тренер')")
                sql.executeUpdate("INSERT INTO client (trainer_id, full_name) VALUES (2, 'Второй подопечный')")
                sql.executeUpdate("INSERT INTO workout_session (client_id, trainer_id, performed_at) VALUES (2, 2, now())")
                sql.executeUpdate("""
                    INSERT INTO workout_exercise (session_id, muscle_group_id, exercise_catalog_id, exercise_name, order_index)
                    SELECT s.id, c.muscle_group_id, c.id, c.name, 1 FROM workout_session s
                    CROSS JOIN exercise_catalog c WHERE c.name = 'Жим штанги лёжа'
                """.trimIndent())

                assertEquals(1, flyway.migrate().migrationsExecuted)
                assertEquals(0, flyway.migrate().migrationsExecuted)
                sql.executeQuery("SELECT id, approved_at, telegram_linked_at, training_bot_linked_at FROM trainer WHERE username = 'tg:9123'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1L, rows.getLong("id"))
                    assertTrue(rows.getObject("approved_at") != null)
                    assertTrue(rows.getObject("telegram_linked_at") != null)
                    assertNull(rows.getObject("training_bot_linked_at"))
                }
                sql.executeQuery("SELECT count(*) FROM exercise_catalog").use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1))
                }
                sql.executeQuery("""
                    SELECT e.exercise_name, c.name, s.trainer_id, c.hidden, e.muscle_group_id, c.muscle_group_id
                    FROM workout_exercise e JOIN workout_session s ON s.id = e.session_id
                    JOIN exercise_catalog c ON c.id = e.exercise_catalog_id
                """.trimIndent()).use { rows ->
                    var count = 0
                    while (rows.next()) {
                        assertEquals(rows.getString(1), rows.getString(2))
                        assertTrue(rows.getBoolean(4))
                        assertEquals(rows.getLong(5), rows.getLong(6))
                        count++
                    }
                    assertEquals(3, count)
                }
                sql.executeQuery(
                    """
                    SELECT t.telegram_user_id, t.phone, t.phone_verified_at, s.reps, s.weight_kg, s.duration_sec,
                           m.measured_on, m.waist_cm, m.height_cm, m.calf_cm
                    FROM trainer t JOIN client c ON c.trainer_id = t.id
                      JOIN workout_session w ON w.client_id = c.id
                      JOIN workout_exercise e ON e.session_id = w.id
                      JOIN exercise_set s ON s.exercise_id = e.id
                      JOIN body_measurement m ON m.client_id = c.id
                    """.trimIndent(),
                ).use { row ->
                    assertTrue(row.next())
                    assertEquals(9123L, row.getLong("telegram_user_id"))
                    assertEquals("+12025550123", row.getString("phone"))
                    assertNull(row.getObject("phone_verified_at"))
                    assertEquals(10, row.getInt("reps"))
                    assertEquals(60, row.getInt("weight_kg"))
                    assertNull(row.getObject("duration_sec"))
                    assertEquals("2026-07-12", row.getDate("measured_on").toString())
                    assertEquals(80, row.getInt("waist_cm"))
                    assertNull(row.getObject("height_cm"))
                    assertNull(row.getObject("calf_cm"))
                }
            }
        }
    }

    @Test
    fun `a V13 installation keeps its history and gains independent left and right measurements`() {
        val db = TestDatabase.config
        val schema = "upgrade_from_v13"
        val flyway = DatabaseFactory.migrationConfig().dataSource(db.jdbcUrl, db.user, db.password).schemas(schema).load()
        flyway.migrate()
        DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { sql ->
                sql.execute("ALTER TABLE training_schema_history RENAME TO flyway_schema_history")
                sql.execute("ALTER TABLE flyway_schema_history RENAME CONSTRAINT training_schema_history_pk TO flyway_schema_history_pk")
                sql.execute("ALTER INDEX training_schema_history_s_idx RENAME TO flyway_schema_history_s_idx")
                sql.execute("UPDATE flyway_schema_history SET version = '13' WHERE type = 'SQL'")
                sql.execute("""
                    ALTER TABLE body_measurement DROP COLUMN right_biceps_cm, DROP COLUMN left_biceps_cm,
                       DROP COLUMN right_forearm_cm, DROP COLUMN left_forearm_cm,
                       DROP COLUMN right_thigh_cm, DROP COLUMN left_thigh_cm, DROP COLUMN right_calf_cm, DROP COLUMN left_calf_cm
                """.trimIndent())
                sql.execute("INSERT INTO trainer(full_name) VALUES ('Тренер')")
                sql.execute("INSERT INTO client(trainer_id, full_name) VALUES (1, 'Клиент')")
                sql.execute("INSERT INTO body_measurement(client_id, measured_on, biceps_cm) VALUES (1, CURRENT_DATE, 32)")
                assertEquals(1, flyway.migrate().migrationsExecuted)
                assertEquals(0, flyway.migrate().migrationsExecuted)
                sql.executeQuery("SELECT biceps_cm, left_biceps_cm, right_biceps_cm, left_forearm_cm, right_forearm_cm FROM body_measurement").use { r ->
                    assertTrue(r.next())
                    assertEquals(32, r.getInt(1))
                    assertNull(r.getObject(2))
                    assertNull(r.getObject(3))
                    assertNull(r.getObject(4))
                    assertNull(r.getObject(5))
                }
                sql.executeQuery("SELECT version FROM flyway_schema_history WHERE type = 'SQL'").use {
                    assertTrue(it.next())
                    assertEquals("13", it.getString(1))
                }
            }
        }
    }
}
