package com.tecnor.adm.audit

import com.tecnor.adm.api.AuditEvent
import com.tecnor.adm.api.AuditSink
import com.tecnor.adm.api.Storage
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.storage.update
import org.bukkit.plugin.java.JavaPlugin

class DatabaseAuditSink(storage: Storage, private val settings: ConfigService,
                        private val file: FileAuditSink, private val plugin: JavaPlugin) : AuditSink {
    private val storage = com.tecnor.adm.api.ScopedStorage(storage, "audit")
    override fun record(event: AuditEvent) {
        val config = settings.current()
        if (config.value("audit.enabled") == false) return
        file.configure(config)
        val secondary = config.value("audit.file-secondary") == true
        val origin = com.tecnor.adm.api.ActionOrigin.current()
        val source = if (origin == "HUD") origin else event.details["source"] ?: origin
        val record = event.copy(details = event.details + ("source" to source))
        if (secondary) file.record(record)
        storage.submit { db ->
            val details = (record.details + ("inventory" to event.inventory)).entries.joinToString("\n") { "${it.key}=${it.value}" }
            db.update("INSERT INTO audit(staff,staff_name,target,target_name,action,details,created,source) VALUES(?,?,?,?,?,?,?,?)",
                event.actor.toString(), event.actorName, event.target.toString(), event.targetName, event.action,
                details, event.timestamp.toEpochMilli(), source)
        }.whenComplete { _, error ->
            if (error != null && !secondary) {
                file.record(record)
                plugin.logger.warning("Database audit failed; record sent to fallback file sink")
            }
        }
    }

    override fun close() { file.close() }

    fun closeWithin(milliseconds: Long) { file.closeWithin(milliseconds) }
}