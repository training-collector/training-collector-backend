package com.tc.gymtrainer.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

data class ServerConfig(
    val host: String,
    val port: Int,
)

data class BotAccess(val authKey: String = "", val trainingKey: String = "") {
    init {
        require(authKey.isBlank() || trainingKey.isBlank() || authKey != trainingKey) {
            "AUTH_SERVICE_KEY and TRAINING_SERVICE_KEY must differ"
        }
    }
}

data class AppConfig(
    val database: DatabaseConfig,
    val server: ServerConfig,
    val bots: BotAccess = BotAccess(),
) {
    companion object {
        fun load(raw: Config = ConfigFactory.load()): AppConfig {
            val c = raw.getConfig("app")
            return AppConfig(
                database = DatabaseConfig(
                    jdbcUrl = c.getString("db.jdbcUrl"),
                    user = c.getString("db.user"),
                    password = c.getString("db.password"),
                    maxPoolSize = c.getInt("db.maxPoolSize"),
                ),
                bots = BotAccess(c.getString("bots.authKey"), c.getString("bots.trainingKey")),
                server = ServerConfig(
                    host = c.getString("server.host"),
                    port = c.getInt("server.port"),
                ),
            )
        }
    }
}
