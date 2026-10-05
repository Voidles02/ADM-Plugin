package com.tecnor.adm.command

import com.tecnor.adm.Stage0Identity
import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.api.Storage
import com.tecnor.adm.audit.AuditLogService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Public actions run on the server thread and return plain ActionResults.
 * The reload worker captures only immutable identity and settings data. It never resolves players.
 * Atomic flags synchronize shutdown and reload admission with the executor thread.
 */
class AdminService(
    private val plugin: JavaPlugin,
    private val version: String,
    private val settings: ConfigService,
    private val modules: ModuleManager,
    private val messages: MessageService,
    private val executor: ExecutorService
) {
    private val permissions = PermissionService(settings)
    private val scheduler = SchedulerHelper(plugin)
    private val accepting = AtomicBoolean(true)
    private val reloading = AtomicBoolean(false)
    private lateinit var storage: Storage
    private lateinit var auditLog: AuditLogService
    private var debugging = false
    private val originalLogLevel = plugin.logger.level

    val isDebugging: Boolean get() = debugging
    fun setAuditLog(auditLog: AuditLogService) { this.auditLog = auditLog }
    fun setStorage(storage: Storage) { this.storage = storage }
    fun log(actor: CommandActor, arguments: String) = auditLog.execute(actor, "log", arguments)
    fun cleanup(actor: CommandActor) = auditLog.execute(actor, "cleanup", "")

    fun storageInfo(actor: CommandActor): ActionResult {
        check(actor, "adm.admin.storage-info")?.let { return it }
        val health = storage.health
        return ActionResult.success("storage.info", mapOf("state" to health.state,
            "success" to health.lastSuccess.toString(), "latency" to health.latencyMs.toString(), "error" to health.lastError))
    }

    fun database(actor: CommandActor): ActionResult {
        check(actor, "adm.admin.database")?.let { return it }
        val path = plugin.dataFolder.toPath().resolve("adm.mv.db").toAbsolutePath().normalize()
        permissions.record(actor, "database")
        return ActionResult.success("storage.database", mapOf("path" to path.toString()))
    }

    fun help(actor: CommandActor): ActionResult {
        unavailable()?.let { return it }
        if (listOf("version", "reload", "debug", "storage-info", "database", "log", "cleanup").none { actor.hasPermission("adm.admin.$it") }) {
            return ActionResult.failure("command.no-permission")
        }
        return ActionResult.success("command.help", mapOf("command" to settings.startup().commands().name()))
    }

    fun version(actor: CommandActor): ActionResult {
        check(actor, "adm.admin.version")?.let { return it }
        permissions.record(actor, "version")
        return ActionResult.success("command.version", mapOf("version" to version, "author" to Stage0Identity.AUTHOR))
    }

    fun debug(actor: CommandActor): ActionResult {
        check(actor, "adm.admin.debug")?.let { return it }
        debugging = !debugging
        plugin.logger.level = if (debugging) Level.FINE else originalLogLevel
        val details = buildString {
            append("Debug: ${if (debugging) "ON" else "OFF"}")
            for ((id, status) in modules.statuses()) append('\n').append(id).append(": ").append(status.name)
        }
        permissions.record(actor, "debug")
        return ActionResult.success("command.debug", mapOf("details" to details))
    }

    fun reload(actor: CommandActor): ActionResult {
        check(actor, "adm.admin.reload")?.let { return it }
        if (!accepting.get()) return ActionResult.failure("reload.unavailable")
        if (!reloading.compareAndSet(false, true)) return ActionResult.failure("reload.busy")
        try {
            executor.execute { readReload(actor) }
        } catch (_: RejectedExecutionException) {
            reloading.set(false)
            return ActionResult.failure("reload.unavailable")
        }
        permissions.record(actor, "reload")
        return ActionResult.success("reload.started")
    }

    fun stop() {
        accepting.set(false)
        permissions.clear()
        plugin.logger.level = originalLogLevel
    }

    fun forget(id: UUID) { permissions.forget(id) }

    private fun readReload(actor: CommandActor) {
        val candidate = try {
            settings.readForReload()
        } catch (exception: Exception) {
            plugin.logger.log(Level.WARNING, "ADM settings reload rejected.", exception)
            val error = exception.message ?: exception.javaClass.simpleName
            dispatch(Runnable {
                try {
                    if (accepting.get()) messages.send(actor, ActionResult.failure("reload.invalid", mapOf("error" to error)))
                } finally {
                    reloading.set(false)
                }
            })
            return
        }
        dispatch(Runnable { finishReload(actor, candidate) })
    }

    private fun finishReload(actor: CommandActor, candidate: SettingsSnapshot) {
        try {
            if (!accepting.get()) return
            val restarts = settings.restartRequired(candidate)
            settings.apply(candidate)
            val failed = modules.reload(candidate)
            messages.send(actor, ActionResult.success("reload.success"))
            for (setting in restarts) messages.send(actor, ActionResult.success("reload.restart-required", mapOf("setting" to setting)))
            for (id in failed) messages.send(actor, ActionResult.failure("reload.module-failed", mapOf("module" to id)))
        } finally {
            reloading.set(false)
        }
    }

    private fun dispatch(callback: Runnable) {
        if (!accepting.get() || !scheduler.main(callback)) reloading.set(false)
    }

    private fun check(actor: CommandActor, permission: String): ActionResult? = unavailable()
        ?: permissions.check(actor, permission) ?: permissions.cooldown(actor, permission.substringAfterLast('.'))

    private fun unavailable(): ActionResult? = if (modules.isEnabled("core")) null
        else ActionResult.failure("command.module-unavailable", mapOf("module" to "core"))
}