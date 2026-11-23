package com.tc.gymtrainer.service

import com.tc.gymtrainer.config.Settings
import com.tc.gymtrainer.db.dbQuery
import com.tc.gymtrainer.domain.Trainer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.util.UUID

internal object TelegramDialogs : Table("telegram_dialog") {
    val role = integer("role")
    val sender = long("sender")
    val last = long("last_update").nullable()
    val pending = long("pending_update").nullable()
    val checkpoint = text("pending_draft").nullable()
    val attempts = integer("attempts").default(0)
    val seen = timestampWithTimeZone("last_seen")
    val lease = uuid("lease").nullable()
    val until = timestampWithTimeZone("lease_until").nullable()
    val draft = text("draft").nullable()
    override val primaryKey = PrimaryKey(role, sender)
}

internal object TelegramReceipts : Table("telegram_write_receipt") {
    val key = uuid("key")
    val result = text("result")
    val sender = long("sender").nullable()
    val created = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(key)
}

/** [attempt] counts deliveries of the same update, so a bot can give up on one that keeps failing. */
internal data class UpdateLease(
    val status: String, val lease: UUID? = null, val draft: String? = null, val checkpoint: String? = null,
    val attempt: Int = 0, val trainer: Trainer? = null,
)
internal data class UpdateResult(val lease: UUID, val draft: String? = null)

/** Removes copies of a trainer's data kept for dialogs. Call inside a transaction. */
internal fun forgetDialogData(sender: Long, keepProgress: Boolean) {
    if (keepProgress) {
        // The row also remembers the last processed update, which a repeated delivery needs.
        TelegramDialogs.update({ TelegramDialogs.sender eq sender }) {
            it[draft] = null
            it[checkpoint] = null
        }
    } else TelegramDialogs.deleteWhere { TelegramDialogs.sender eq sender }
    TelegramReceipts.deleteWhere { TelegramReceipts.sender eq sender }
}

internal class WebhookStore(private val role: BotRole, private val settings: Settings) {
    private fun chat(sender: Long) = (TelegramDialogs.role eq role.id) and (TelegramDialogs.sender eq sender)

    suspend fun begin(sender: Long, update: Long) = dbQuery {
        val now = OffsetDateTime.now()
        TelegramDialogs.insertIgnore {
            it[TelegramDialogs.role] = this@WebhookStore.role.id
            it[TelegramDialogs.sender] = sender
            it[seen] = now
        }
        val row = TelegramDialogs.selectAll().where { chat(sender) }.forUpdate().single()
        // After a week without events Telegram can start a fresh sequence of update IDs.
        when {
            row[TelegramDialogs.until]?.isAfter(now) == true -> UpdateLease("busy")
            row[TelegramDialogs.seen] > now.minusDays(2) && (row[TelegramDialogs.last] ?: -1) >= update -> UpdateLease("done")
            else -> {
                val token = UUID.randomUUID()
                val repeated = row[TelegramDialogs.pending] == update
                val attempt = if (repeated) row[TelegramDialogs.attempts] + 1 else 1
                TelegramDialogs.update({ chat(sender) }) {
                    if (!repeated) it[checkpoint] = null
                    it[pending] = update
                    it[attempts] = attempt
                    it[lease] = token
                    it[until] = now.plus(settings.updateLease)
                }
                UpdateLease("claimed", token, row[TelegramDialogs.draft],
                    row[TelegramDialogs.checkpoint].takeIf { repeated }, attempt)
            }
        }
    }

    suspend fun checkpoint(sender: Long, update: Long, result: UpdateResult) = dbQuery {
        val changed = TelegramDialogs.update({ chat(sender) and (TelegramDialogs.lease eq result.lease) and (TelegramDialogs.pending eq update) }) {
            it[checkpoint] = result.draft
            it[until] = OffsetDateTime.now().plus(settings.updateLease)
        }
        if (changed != 1) throw ConflictException("Истёк срок обработки обновления.")
    }

    /** A failed attempt frees the chat at once: the retry does not wait for the lease to expire. */
    suspend fun release(sender: Long, result: UpdateResult) = dbQuery {
        TelegramDialogs.update({ chat(sender) and (TelegramDialogs.lease eq result.lease) }) {
            it[lease] = null
            it[until] = null
        }
        Unit
    }

    suspend fun finish(sender: Long, update: Long, result: UpdateResult) = dbQuery {
        val changed = TelegramDialogs.update({ chat(sender) and (TelegramDialogs.lease eq result.lease) }) {
            it[pending] = null
            it[checkpoint] = null
            it[attempts] = 0
            it[last] = update
            it[draft] = result.draft
            it[seen] = OffsetDateTime.now()
            it[lease] = null
            it[until] = null
        }
        if (changed != 1) throw ConflictException("Повторите доставку обновления.")
    }
}
