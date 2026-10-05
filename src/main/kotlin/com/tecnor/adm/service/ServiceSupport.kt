package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.module.FeatureService
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.SettingsSnapshot
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/** Common service guards are main-thread only; no command adapter can bypass these checks. */
abstract class ServiceSupport(
    protected val plugin: JavaPlugin,
    protected val modules: ModuleManager,
    protected val permissions: PermissionService
) : FeatureService {
    protected fun check(actor: CommandActor, command: String, node: String): ActionResult? {
        if (!modules.isEnabled(id)) return ActionResult.failure("command.module-unavailable", mapOf("module" to id))
        return permissions.check(actor, node) ?: permissions.cooldown(actor, command)
    }

    protected fun done(actor: CommandActor, command: String, key: String, values: Map<String, String> = emptyMap()): ActionResult {
        permissions.record(actor, command)
        plugin.logger.info("${actor.name()} used /$command ${values.entries.joinToString { "${it.key}=${it.value}" }}")
        return ActionResult.success(key, values)
    }

    protected fun usage(syntax: String) = ActionResult.failure("command.usage", mapOf("usage" to syntax))
    protected fun words(text: String) = if (text.isBlank()) emptyList() else text.trim().split(Regex("\\s+"))
    protected fun names() = Bukkit.getOnlinePlayers().map { it.name }
    override fun enable() = Unit
    override fun onReload(snapshot: SettingsSnapshot) = Unit
}