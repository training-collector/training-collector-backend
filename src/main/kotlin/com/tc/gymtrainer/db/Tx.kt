package com.tc.gymtrainer.db

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

internal class SharedTransaction(val tx: Transaction) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SharedTransaction>
}

/**
 * Открывает suspend-транзакцию на IO-диспетчере. Единая точка входа в БД для
 * сервисного слоя, чтобы репозитории всегда работали внутри транзакции.
 */
suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T {
    currentCoroutineContext()[SharedTransaction]?.let { return block(it.tx) }
    return newSuspendedTransaction(Dispatchers.IO, db = TransactionManager.defaultDatabase) { block() }
}
