package com.tc.gymtrainer.service

import com.tc.gymtrainer.db.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.util.UUID

internal object TelegramDialogs : Table("telegram_dialog") {
    val role = integer("role")
    val sender = long("sender")
    val last = long("last_update").nullable()
    val pending = long("pending_update").nullable()
    val checkpoint = text("pending_draft").nullable()
    val seen = timestampWithTimeZone("last_seen")
    val lease = uuid("lease").nullable()
    val until = timestampWithTimeZone("lease_until").nullable()
    val draft = text("draft").nullable()
    override val primaryKey = PrimaryKey(role, sender)
}

internal object TelegramReceipts : Table("telegram_write_receipt") {
    val key = uuid("key")
    val result = text("result")
    val created = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(key)
}

internal data class UpdateLease(val status: String, val lease: UUID? = null, val draft: String? = null, val checkpoint: String? = null)
internal data class UpdateResult(val lease: UUID, val draft: String? = null)

internal class WebhookStore(private val role: BotRole) {
    private fun chat(sender: Long) = (TelegramDialogs.role eq role.id) and (TelegramDialogs.sender eq sender)

    suspend fun begin(sender: Long, update: Long) = dbQuery {
        val now = OffsetDateTime.now()
        TelegramDialogs.deleteWhere { seen less now.minusDays(30) }
        TelegramReceipts.deleteWhere { created less now.minusDays(2) }
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
                TelegramDialogs.update({ chat(sender) }) {
                    if (row[TelegramDialogs.pending] != update) it[checkpoint] = null
                    it[pending] = update
                    it[lease] = token
                    it[until] = now.plusSeconds(90)
                }
                UpdateLease("claimed", token, row[TelegramDialogs.draft],
                    row[TelegramDialogs.checkpoint].takeIf { row[TelegramDialogs.pending] == update })
            }
        }
    }

    suspend fun checkpoint(sender: Long, update: Long, result: UpdateResult) = dbQuery {
        val changed = TelegramDialogs.update({ chat(sender) and (TelegramDialogs.lease eq result.lease) and (TelegramDialogs.pending eq update) }) {
            it[checkpoint] = result.draft
            it[until] = OffsetDateTime.now().plusSeconds(90)
        }
        if (changed != 1) throw ConflictException("Истёк срок обработки обновления.")
    }

    suspend fun finish(sender: Long, update: Long, result: UpdateResult) = dbQuery {
        val changed = TelegramDialogs.update({ chat(sender) and (TelegramDialogs.lease eq result.lease) }) {
            it[pending] = null
            it[checkpoint] = null
            it[last] = update
            it[draft] = result.draft
            it[seen] = OffsetDateTime.now()
            it[lease] = null
            it[until] = null
        }
        if (changed != 1) throw ConflictException("Повторите доставку обновления.")
    }
}
