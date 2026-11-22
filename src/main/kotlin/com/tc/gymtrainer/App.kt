package com.tc.gymtrainer

import com.tc.gymtrainer.api.trainingApi
import com.tc.gymtrainer.config.AppConfig
import com.tc.gymtrainer.db.DatabaseFactory
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.MeasurementRepository
import com.tc.gymtrainer.repository.TrainerRepository
import com.tc.gymtrainer.repository.WorkoutRepository
import com.tc.gymtrainer.service.AdminService
import com.tc.gymtrainer.service.CatalogService
import com.tc.gymtrainer.service.ClientService
import com.tc.gymtrainer.service.MeasurementService
import com.tc.gymtrainer.service.TrainerService
import com.tc.gymtrainer.service.LoginCodeService
import com.tc.gymtrainer.service.TelegramLinkService
import com.tc.gymtrainer.service.WorkoutService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

class TrainingServices {
    private val clientRepository = ClientRepository()
    private val workoutRepository = WorkoutRepository()
    private val measurementRepository = MeasurementRepository()

    val admin = AdminService()
    val trainers = TrainerService(TrainerRepository())
    val telegramLinks = TelegramLinkService()
    val loginCodes = LoginCodeService(TrainerRepository())
    val clients = ClientService(clientRepository, workoutRepository, measurementRepository)
    val catalog = CatalogService()
    val workouts = WorkoutService(workoutRepository, clientRepository, catalog)
    val measurements = MeasurementService(measurementRepository, clientRepository)
}

fun withTrainingServices(config: AppConfig, action: (TrainingServices) -> Unit) {
    DatabaseFactory.init(config.database).use { action(TrainingServices()) }
}

fun main() {
    // Set Netty's defaults before it probes native transports and Unsafe on JDK 25.
    System.getProperties().putIfAbsent("io.netty.transport.noNative", "true")
    System.getProperties().putIfAbsent("io.netty.noUnsafe", "true")
    val config = AppConfig.load()
    withTrainingServices(config) { services ->
        val server = embeddedServer(Netty, host = config.server.host, port = config.server.port) {
            trainingApi(services, config.bots, config.admin)
        }
        server.start(wait = true)
    }
}
