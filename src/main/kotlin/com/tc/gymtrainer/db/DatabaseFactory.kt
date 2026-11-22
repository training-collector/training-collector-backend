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
 * Схему всегда накатывает Flyway (единственный источник правды по DDL).
 */
object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: DatabaseConfig): AutoCloseable {
        log.info("Инициализация БД: {}", config.jdbcUrl)
        val dataSource = hikari(config)
        val database = try {
            migrate(dataSource)
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

    private fun hikari(config: DatabaseConfig): HikariDataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.maxPoolSize
            // IDEA starts the PostgreSQL container alongside the API and the two bots.
            initializationFailTimeout = 120_000
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "gym-trainer-pool"
        }
        return HikariDataSource(hikari)
    }

    // The original V1–V13 ledger stays available when adopting an existing installation.
    internal fun migrationConfig() = Flyway.configure()
        .locations("classpath:db/migration")
        .table("training_schema_history")
        .baselineOnMigrate(true)
        .baselineVersion("0")

    private fun migrate(dataSource: DataSource) {
        val result = migrationConfig().dataSource(dataSource).load().migrate()
        log.info("Flyway: применено миграций {}", result.migrationsExecuted)
    }
}
