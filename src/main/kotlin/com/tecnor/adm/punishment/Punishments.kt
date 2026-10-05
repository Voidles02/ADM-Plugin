package com.tecnor.adm.punishment

import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.api.Storage
import com.tecnor.adm.api.StoredPlayer
import com.tecnor.adm.storage.query
import com.tecnor.adm.storage.transaction
import com.tecnor.adm.storage.update
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Punishment(val id: Long, val target: UUID, val kind: String, val reason: String,
                      val created: Long, val expires: Long?, val revoked: Long?, val staff: String) {
    fun active(now: Long = System.currentTimeMillis()) = revoked == null && (expires == null || expires > now)
}

fun ResultSet.punishment() = Punishment(getLong("id"), UUID.fromString(getString("target")), getString("kind"),
    getString("reason"), getLong("created"), getObject("expires")?.let { getLong("expires") },
    getObject("revoked")?.let { getLong("revoked") }, getString("staff_name"))

fun durationMillis(input: String): Long? {
    val match = Regex("([1-9][0-9]{0,8})([mhd])").matchEntire(input) ?: return null
    val unit = when (match.groupValues[2]) {
        "m" -> 60_000L
        "h" -> 3_600_000L
        else -> 86_400_000L
    }
    return match.groupValues[1].toLongOrNull()?.let { runCatching { Math.multiplyExact(it, unit) }.getOrNull() }
}

data class Escalation(val count: Int, val kind: String, val duration: Long)

class Punishments(val storage: Storage) {
    val mutes = ConcurrentHashMap<UUID, Punishment>()

    fun active(db: Connection, id: UUID, kind: String): Punishment? = db.query(
        "SELECT * FROM punishments WHERE target=? AND kind=? AND revoked IS NULL AND (expires IS NULL OR expires>?) ORDER BY id DESC LIMIT 1",
        id.toString(), kind, System.currentTimeMillis()) { it.punishment() }.firstOrNull()

    fun apply(target: StoredPlayer, actor: CommandActor, kind: String, reason: String, duration: Long?,
              escalations: List<Escalation>) = storage.submit { db -> db.transaction {
        val actions = mutableListOf<Punishment>()
        fun add(type: String, time: Long?) {
            val now = System.currentTimeMillis()
            val expires = time?.let { Math.addExact(now, it) }
            if (type in listOf("MUTE", "BAN", "IPBAN")) {
                db.update("UPDATE punishments SET revoked=? WHERE target=? AND kind=? AND revoked IS NULL", now, target.id.toString(), type)
            }
            db.update("INSERT INTO punishments(target,target_name,ip,kind,reason,staff,staff_name,created,expires) VALUES(?,?,?,?,?,?,?,?,?)",
                target.id.toString(), target.name, if (type == "IPBAN") target.ip else null, type, reason,
                actor.playerId()?.toString() ?: "CONSOLE", actor.name(), now, expires)
            actions.add(db.query("SELECT * FROM punishments WHERE id=last_insert_rowid()") { it.punishment() }.first())
        }
        add(kind, duration)
        if (kind == "WARN") {
            val count = db.query("SELECT COUNT(*) FROM punishments WHERE target=? AND kind='WARN' AND cleared=0", target.id.toString()) { it.getInt(1) }.first()
            escalations.firstOrNull { it.count == count }?.let { add(it.kind, it.duration) }
        }
        actions
    } }
}