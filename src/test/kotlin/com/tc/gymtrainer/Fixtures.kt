package com.tc.gymtrainer

import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Client
import com.tc.gymtrainer.domain.ClientProfile
import com.tc.gymtrainer.domain.Trainer
import com.tc.gymtrainer.repository.ClientRepository
import com.tc.gymtrainer.repository.MeasurementRepository
import com.tc.gymtrainer.repository.TrainerRepository
import com.tc.gymtrainer.repository.WorkoutRepository
import com.tc.gymtrainer.service.CatalogService
import com.tc.gymtrainer.service.ClientService
import com.tc.gymtrainer.service.MeasurementService
import com.tc.gymtrainer.service.TrainerService
import com.tc.gymtrainer.service.WorkoutService

/** Готовые сервисы поверх stateless-репозиториев (общего подключения Exposed). */
object Services {
    val trainer = TrainerService(TrainerRepository())
    val client = ClientService(ClientRepository(), MeasurementRepository())
    val catalog = CatalogService()
    val workout = WorkoutService(WorkoutRepository(), ClientRepository(), catalog)
    val measurement = MeasurementService(MeasurementRepository(), ClientRepository())
}

suspend fun newTrainer(tgId: Long, name: String = "Тренер $tgId"): Trainer =
    dbQuery { TrainerRepository().insert(name.substringBefore(' '), name.substringAfter(' ', "").ifEmpty { null }, telegramUserId = tgId) }

suspend fun newClient(trainer: Trainer, name: String = "Клиент"): Client =
    Services.client.create(trainer.id, ClientProfile(name))
