package com.tecnor.adm.api

import java.time.Instant
import java.util.UUID

/** Plain data only. No Bukkit objects may cross the writer boundary. */
data class AuditEvent(
    val timestamp: Instant,
    val actor: UUID,
    val actorName: String,
    val target: UUID,
    val targetName: String,
    val inventory: String,
    val action: String,
    val details: Map<String, String>
)

interface AuditSink : AutoCloseable {
    fun record(event: AuditEvent)
    override fun close()
}