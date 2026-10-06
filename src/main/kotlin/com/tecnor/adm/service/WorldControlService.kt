package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.plugin.java.JavaPlugin

class WorldControlService(
    plugin: JavaPlugin,
    modules: ModuleManager,
    permissions: PermissionService,
    private val messages: MessageService
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "world-control"
    override val commands = listOf(CommandSpec("day", listOf("dat")), CommandSpec("noon"), CommandSpec("night"))

    override fun suggestions(command: String, completed: List<String>) =
        if (completed.size == 1) Bukkit.getWorlds().map(World::getName) else emptyList()

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, command, "adm.admin.worldcontrol")?.let { return it }
        val args = words(arguments)
        if (command == "weather") {
            if (args.firstOrNull()?.equals("stop", ignoreCase = true) != true || args.size > 2) return usage("/w stop [world]")
            val world = resolveWorld(actor, args.drop(1)) ?: return worldFailure(actor, args.drop(1))
            world.setStorm(false)
            world.setThundering(false)
            world.setWeatherDuration(12000)
            world.setThunderDuration(12000)
            world.setClearWeatherDuration(12000)
            return done(actor, command, "world.weather-cleared", mapOf("world" to world.name))
        }

        if (command !in setOf("day", "noon", "night") || args.size > 1) return usage("/$command [world]")
        val world = resolveWorld(actor, args) ?: return worldFailure(actor, args)
        val time = when (command) {
            "day" -> 1000L
            "noon" -> 6000L
            else -> 13000L
        }
        world.setTime(time)
        return done(actor, command, "world.time-set", mapOf("world" to world.name, "time" to time.toString()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val args = words(event.message.removePrefix("/"))
        if (!isWeatherStop(args)) return
        event.isCancelled = true
        val actor = CommandActor.from(event.player)
        messages.send(actor, execute(actor, "weather", args.drop(1).joinToString(" ")))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        val args = words(event.command)
        if (!isWeatherStop(args)) return
        event.isCancelled = true
        val actor = CommandActor.from(event.sender)
        messages.send(actor, execute(actor, "weather", args.drop(1).joinToString(" ")))
    }

    private fun isWeatherStop(args: List<String>) = args.size in 2..3 &&
        args[0].equals("w", ignoreCase = true) && args[1].equals("stop", ignoreCase = true)

    private fun resolveWorld(actor: CommandActor, arguments: List<String>): World? {
        if (arguments.size > 1) return null
        return arguments.firstOrNull()?.let(Bukkit::getWorld)
            ?: if (arguments.isEmpty()) actor.playerId()?.let(Bukkit::getPlayer)?.world else null
    }

    private fun worldFailure(actor: CommandActor, arguments: List<String>): ActionResult = when {
        arguments.size > 1 -> usage("/${if (arguments.firstOrNull() == "stop") "w stop" else "day"} [world]")
        arguments.isNotEmpty() -> ActionResult.failure("world.not-found", mapOf("world" to arguments.first()))
        actor.isConsole -> ActionResult.failure("world.required")
        else -> ActionResult.failure("world.not-found", mapOf("world" to "current"))
    }

    override fun disable() = Unit
}