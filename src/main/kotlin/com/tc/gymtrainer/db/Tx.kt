package com.tc.gymtrainer.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Открывает suspend-транзакцию на IO-диспетчере. Единая точка входа в БД для
 * сервисного слоя, чтобы репозитории всегда работали внутри транзакции.
 */
suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, db = TransactionManager.defaultDatabase) { block() }
