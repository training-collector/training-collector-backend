package com.tc.gymtrainer.db

import com.tc.gymtrainer.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Инициализация подключения к БД: пул Hikari → миграции Flyway → Exposed.
 * Схему всегда накатывает Flyway (единственный источник правды по DDL): при старте
 * сервиса или отдельным шагом развёртывания, если migrateOnStart выключен.
 */
object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: DatabaseConfig): AutoCloseable {
        val dataSource = hikari(config, connectNow = config.migrateOnStart)
        val database = try {
            if (config.migrateOnStart) migrate(dataSource)
            Database.connect(dataSource)
        } catch (e: Exception) {
            dataSource.close()
            throw e
        }
        return AutoCloseable {
            TransactionManager.closeAndUnregister(database)
            dataSource.close()
        }
    }

    /** Applies pending migrations and releases the connection. */
    fun migrate(config: DatabaseConfig) = hikari(config, connectNow = true).use(::migrate)

    private fun hikari(config: DatabaseConfig, connectNow: Boolean): HikariDataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.maxPoolSize
            minimumIdle = 0
            idleTimeout = 60_000
            keepaliveTime = 0
            // IDEA starts the PostgreSQL container alongside the API and the two bots, so the first
            // connection may have to wait. Without migrations the pool connects on the first query:
            // a cold start then does not wake a sleeping database.
            initializationFailTimeout = if (connectNow) 120_000 else -1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "gym-trainer-pool"
        }
        return HikariDataSource(hikari)
    }

    private fun migrate(dataSource: DataSource) {
        val result = Flyway.configure().dataSource(dataSource).load().migrate()
        log.info("Flyway: применено миграций {}", result.migrationsExecuted)
    }
}
