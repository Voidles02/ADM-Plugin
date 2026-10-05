package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.integration.GrimCompatibility
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

class AnticheatService(
    plugin: JavaPlugin,
    modules: ModuleManager,
    permissions: PermissionService,
    private val grimCompatibility: GrimCompatibility
) : ServiceSupport(plugin, modules, permissions) {
    override val id = "anticheat"
    override val commands = listOf(CommandSpec("adm-connect"), CommandSpec("anticheat"))

    private val supportedPlugins = mapOf(
        "grimac" to listOf("GrimAC"),
        "vulcan" to listOf("Vulcan"),
        "matrix" to listOf("Matrix"),
        "spartan" to listOf("Spartan"),
        "nocheatplus" to listOf("NoCheatPlus"),
        "themis" to listOf("Themis"),
        "anticheatreloaded" to listOf("AntiCheatReloaded")
    )
    private var connectedPluginName: String? = plugin.server.pluginManager.plugins
        .firstOrNull { candidate -> supportedPlugins.values.any { names -> names.any { it.equals(candidate.name, true) } } && candidate.isEnabled }
        ?.name

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, command, "adm.admin.anticheat.manage")?.let { return it }
        val args = words(arguments)
        return when (command) {
            "adm-connect" -> connect(actor, args)
            "anticheat" -> control(actor, args)
            else -> usage("/adm:anticheat <status|on|off|refresh|exemptions>")
        }
    }

    private fun connect(actor: CommandActor, args: List<String>): ActionResult {
        if (args.size != 1) return usage("/adm-connect <GrimAC|Vulcan|Matrix|Spartan|NoCheatPlus|Themis|AntiCheatReloaded>")
        val aliases = supportedPlugins[args[0].lowercase()] ?: return ActionResult.failure("anticheat.unsupported")
        val target = plugin.server.pluginManager.plugins.firstOrNull { candidate ->
            aliases.any { it.equals(candidate.name, true) }
        } ?: return ActionResult.failure("anticheat.not-found", mapOf("name" to args[0]))
        connectedPluginName = target.name
        if (isGrim(target)) grimCompatibility.refreshOnlinePlayers()
        return done(actor, "adm-connect", "anticheat.connected", mapOf("name" to target.name, "state" to state(target)))
    }

    private fun control(actor: CommandActor, args: List<String>): ActionResult {
        if (args.size != 1) return usage("/adm:anticheat <status|on|off|refresh|exemptions>")
        val action = args[0].lowercase()
        val selectedName = connectedPluginName
        val target = selectedName?.let(plugin.server.pluginManager::getPlugin)
        return when (action) {
            "status" -> ActionResult.success(
                "anticheat.status",
                mapOf("name" to (target?.name ?: "none"), "state" to (target?.let(::state) ?: "not connected"))
            )
            "on", "off" -> toggle(actor, action, target)
            "refresh" -> {
                if (target == null || !isGrim(target)) return ActionResult.failure("anticheat.grim-required")
                grimCompatibility.refreshOnlinePlayers()
                done(actor, "anticheat", "anticheat.refreshed", mapOf("name" to target.name))
            }
            "exemptions" -> {
                if (target == null || !isGrim(target)) return ActionResult.failure("anticheat.grim-required")
                val exempt = plugin.server.onlinePlayers.asSequence()
                    .filter { it.hasPermission("adm.grim.exempt") }
                    .map { it.name }
                    .toList()
                ActionResult.success("anticheat.exemptions", mapOf("count" to exempt.size.toString(), "players" to exempt.joinToString(", ").ifEmpty { "none" }))
            }
            else -> usage("/adm:anticheat <status|on|off|refresh|exemptions>")
        }
    }

    private fun toggle(actor: CommandActor, action: String, target: Plugin?): ActionResult {
        if (target == null) return ActionResult.failure("anticheat.not-connected")
        val enable = action == "on"
        if (target.isEnabled == enable) {
            return ActionResult.success("anticheat.already-state", mapOf("name" to target.name, "state" to state(target)))
        }
        return try {
            if (enable) plugin.server.pluginManager.enablePlugin(target)
            else plugin.server.pluginManager.disablePlugin(target)
            if (isGrim(target) && enable) grimCompatibility.refreshOnlinePlayers()
            done(actor, "anticheat", "anticheat.toggled", mapOf("name" to target.name, "state" to state(target)))
        } catch (failure: Exception) {
            plugin.logger.warning("Could not turn ${target.name} ${if (enable) "on" else "off"}: ${failure.message}")
            ActionResult.failure("anticheat.toggle-failed", mapOf("name" to target.name))
        }
    }

    private fun isGrim(plugin: Plugin) = plugin.name.equals("GrimAC", ignoreCase = true)
    private fun state(plugin: Plugin) = if (plugin.isEnabled) "on" else "off"

    override fun suggestions(command: String, completed: List<String>) = when {
        command == "adm-connect" && completed.isEmpty() -> supportedPlugins.keys.toList()
        command == "anticheat" && completed.isEmpty() -> listOf("status", "on", "off", "refresh", "exemptions")
        else -> emptyList()
    }

    override fun disable() {
        connectedPluginName = null
    }
}