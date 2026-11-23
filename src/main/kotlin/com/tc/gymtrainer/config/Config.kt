package com.tc.gymtrainer.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import java.time.Duration

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
    val migrateOnStart: Boolean = true,
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val clientIpHeader: String = "",
)

data class BotAccess(val authKey: String = "", val trainingKey: String = "") {
    init {
        require(authKey.isBlank() || trainingKey.isBlank() || authKey != trainingKey) {
            "AUTH_SERVICE_KEY and TRAINING_SERVICE_KEY must differ"
        }
    }
}

class AdminAccess(val login: String = "", val password: String = "")

data class RateLimit(val requests: Int, val window: Duration)

/** Limits, lifetimes and intervals. The defaults live in application.conf. */
data class Settings(
    val limitsEnabled: Boolean,
    val registerLimit: RateLimit,
    val loginRequestLimit: RateLimit,
    val loginStatusLimit: RateLimit,
    val adminLoginLimit: RateLimit,
    val clientErrorsLimit: RateLimit,
    val apiLimit: RateLimit,
    val maxBodyBytes: Long,
    val loginRequestTtl: Duration,
    val loginDeliveryRetry: Duration,
    val maxPendingLogins: Int,
    val sessionTtl: Duration,
    val signupTtl: Duration,
    val cleanupInterval: Duration,
    val signupLinkTtl: Duration,
    val trainingLinkTtl: Duration,
    val apiKeyTtl: Duration,
    val notificationTtl: Duration,
    val notificationRetryPause: Duration,
    val updateLease: Duration,
    val dialogTtl: Duration,
    val receiptTtl: Duration,
    val auditRetention: Duration,
    val adminSessionTtl: Duration,
) {
    companion object {
        /** The defaults from application.conf, for tests and tools that have no database settings. */
        val defaults: Settings by lazy {
            val options = ConfigResolveOptions.defaults().setAllowUnresolved(true)
            read(ConfigFactory.parseResources("application.conf").resolve(options).getConfig("app"))
        }

        internal fun read(c: Config): Settings {
            fun positive(path: String) = c.getDuration(path).also {
                require(!it.isNegative && !it.isZero) { "app.$path must be positive" }
            }
            fun count(path: String) = c.getInt(path).also { require(it > 0) { "app.$path must be positive" } }
            fun limit(name: String) = RateLimit(count("limits.$name.requests"), positive("limits.$name.window"))
            return Settings(
                limitsEnabled = c.getBoolean("limits.enabled"),
                registerLimit = limit("register"),
                loginRequestLimit = limit("loginRequest"),
                loginStatusLimit = limit("loginStatus"),
                adminLoginLimit = limit("adminLogin"),
                clientErrorsLimit = limit("clientErrors"),
                apiLimit = limit("api"),
                maxBodyBytes = c.getBytes("limits.maxBodyBytes").also { require(it > 0) { "app.limits.maxBodyBytes must be positive" } },
                loginRequestTtl = positive("login.requestTtl"),
                loginDeliveryRetry = positive("login.deliveryRetry"),
                maxPendingLogins = count("login.maxPending"),
                sessionTtl = positive("login.sessionTtl"),
                signupTtl = positive("signup.ttl"),
                cleanupInterval = positive("signup.cleanupInterval"),
                signupLinkTtl = positive("links.signupTtl"),
                trainingLinkTtl = positive("links.trainingTtl"),
                apiKeyTtl = positive("apiKey.ttl"),
                notificationTtl = positive("notifications.ttl"),
                notificationRetryPause = positive("notifications.retryPause"),
                updateLease = positive("telegram.updateLease"),
                dialogTtl = positive("telegram.dialogTtl"),
                receiptTtl = positive("telegram.receiptTtl"),
                auditRetention = positive("audit.retention"),
                adminSessionTtl = positive("admin.sessionTtl"),
            )
        }
    }
}

data class AppConfig(
    val database: DatabaseConfig,
    val server: ServerConfig,
    val bots: BotAccess = BotAccess(),
    val admin: AdminAccess = AdminAccess(),
    val settings: Settings = Settings.defaults,
    val secret: String = "",
) {
    /** One stable seed for token tags. Without APP_SECRET it follows the service's other secrets. */
    val secretSeed: String
        get() = secret.ifBlank {
            listOf("training-collector", database.password, bots.authKey, bots.trainingKey, admin.password).joinToString("\u0000")
        }

    companion object {
        fun load(raw: Config = ConfigFactory.load()): AppConfig {
            val c = raw.getConfig("app")
            val server = ServerConfig(
                host = c.getString("server.host"),
                port = c.getInt("server.port"),
                clientIpHeader = c.getString("server.clientIpHeader").trim(),
            )
            val bots = BotAccess(c.getString("bots.authKey"), c.getString("bots.trainingKey"))
            return AppConfig(
                database = DatabaseConfig(
                    jdbcUrl = c.getString("db.jdbcUrl"),
                    user = c.getString("db.user"),
                    password = c.getString("db.password"),
                    maxPoolSize = c.getInt("db.maxPoolSize").also { require(it > 0) { "DB_MAX_POOL_SIZE must be positive" } },
                    migrateOnStart = c.getBoolean("db.migrateOnStart"),
                ),
                admin = AdminAccess(c.getString("admin.login"), c.getString("admin.password")),
                bots = bots,
                server = server,
                settings = Settings.read(c),
                secret = c.getString("security.secret"),
            )
        }
    }
}
