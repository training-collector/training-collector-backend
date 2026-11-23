package com.tc.gymtrainer

import com.tc.gymtrainer.api.trainingApi
import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.DatabaseFactory
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.MeasurementRepository
import com.tc.gymtrainer.repository.TrainerRepository
import com.tc.gymtrainer.repository.WorkoutRepository
import com.tc.gymtrainer.service.AdminService
import com.tc.gymtrainer.service.Audit
import com.tc.gymtrainer.service.CatalogService
import com.tc.gymtrainer.service.ClientService
import com.tc.gymtrainer.service.Housekeeping
import com.tc.gymtrainer.service.LoginService
import com.tc.gymtrainer.service.MeasurementService
import com.tc.gymtrainer.service.RateLimiter
import com.tc.gymtrainer.service.Secrets
import com.tc.gymtrainer.service.TelegramLinkService
import com.tc.gymtrainer.service.TrainerService
import com.tc.gymtrainer.service.WorkoutService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

class TrainingServices(
    val settings: Settings = Settings.defaults,
    val secrets: Secrets = Secrets.ephemeral,
) {
    private val clientRepository = ClientRepository()
    private val workoutRepository = WorkoutRepository()
    private val measurementRepository = MeasurementRepository()
    private val trainerRepository = TrainerRepository()

    val limiter = RateLimiter(settings.limitsEnabled)
    val audit = Audit()
    val housekeeping = Housekeeping(settings)
    val admin = AdminService(settings)
    val trainers = TrainerService(trainerRepository, settings, secrets)
    val telegramLinks = TelegramLinkService(settings)
    val logins = LoginService(trainerRepository, settings, secrets)
    val clients = ClientService(clientRepository, measurementRepository)
    val catalog = CatalogService()
    val workouts = WorkoutService(workoutRepository, clientRepository, catalog)
    val measurements = MeasurementService(measurementRepository, clientRepository)
}

fun withTrainingServices(config: AppConfig, action: (TrainingServices) -> Unit) {
    DatabaseFactory.init(config.database).use {
        action(TrainingServices(config.settings, Secrets(config.secretSeed)))
    }
}

fun main(args: Array<String>) {
    val config = AppConfig.load()
    // A deployment step: apply migrations and exit. The server can then start without touching the database.
    if (args.firstOrNull() == "migrate") return DatabaseFactory.migrate(config.database)
    // Set Netty's defaults before it probes native transports and Unsafe on JDK 25.
    System.getProperties().putIfAbsent("io.netty.transport.noNative", "true")
    System.getProperties().putIfAbsent("io.netty.noUnsafe", "true")
    withTrainingServices(config) { services ->
        val server = embeddedServer(Netty, host = config.server.host, port = config.server.port) {
            trainingApi(services, config.bots, config.admin, config.server.clientIpHeader)
        }
        server.start(wait = true)
    }
}
