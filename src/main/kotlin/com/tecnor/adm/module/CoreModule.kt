package com.tecnor.adm.module

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.command.AdminService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.settings.SettingsSnapshot
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Lifecycle, registration guard, and status are server-thread confined.
 * Commands are registered once at startup; disabled or failed module services reject execution.
 */
class CoreModule(
    private val plugin: JavaPlugin,
    private val commands: SettingsSnapshot.CommandSettings,
    private val modules: ModuleManager,
    private val service: AdminService,
    private val messages: MessageService
) : AdmModule, Listener {
    private var state = ModuleStatus.DISABLED
    private var handlerRegistered = false
    private var commandsRegistered = false
    private val commandLabels by lazy {
        buildSet {
            add(commands.name())
            addAll(commands.aliases())
            add("ADM-Console")
            modules.features().forEach { feature ->
                feature.service.commands.forEach { command ->
                    add(command.name)
                    addAll(command.aliases)
                }
            }
        }.flatMapTo(HashSet()) { label ->
            listOf(label.lowercase(), "adm:${label.lowercase()}")
        }
    }

    override fun id() = "core"
    override fun status() = state
    override fun markFailed() { state = ModuleStatus.FAILED }

    override fun enable() {
        check(!handlerRegistered) { "Core command lifecycle handler was already registered" }
        state = ModuleStatus.ENABLED
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            if (!commandsRegistered && state == ModuleStatus.ENABLED) {
                try {
                    val root = Commands.literal(commands.name())
                        .requires { supported(it.sender) }
                        .executes { execute(it.source, service::help) }
                        .then(Commands.literal("version").executes { execute(it.source, service::version) })
                        .then(Commands.literal("reload").executes { execute(it.source, service::reload) })
                        .then(Commands.literal("storage-info").executes { execute(it.source, service::storageInfo) })
                        .then(Commands.literal("database").executes { execute(it.source, service::database) })
                        .then(Commands.literal("cleanup").executes { execute(it.source, service::cleanup) })
                        .then(Commands.literal("log")
                            .executes { execute(it.source) { actor -> service.log(actor, "") } }
                            .then(Commands.argument("filters", StringArgumentType.greedyString())
                                .executes { context -> execute(context.source) { actor ->
                                    service.log(actor, StringArgumentType.getString(context, "filters"))
                                } }))
                        .then(Commands.literal("debug").executes { execute(it.source, service::debug) })
                    event.registrar().register(root.build(), "ADM administration commands", commands.aliases())
                    val consoleStatus = Commands.literal("ADM-Console")
                        .requires { it.sender is ConsoleCommandSender }
                        .executes { context ->
                            val sender = context.source.sender
                            val online = plugin.server.onlinePlayers
                            val enabled = modules.statuses().count { it.value == ModuleStatus.ENABLED }
                            sender.sendMessage("ADM console status: ${online.size}/${plugin.server.maxPlayers} players online")
                            sender.sendMessage("Online players: ${online.joinToString(", ") { it.name }.ifEmpty { "none" }}")
                            sender.sendMessage("Modules enabled: $enabled/${modules.statuses().size}")
                            1
                        }
                    event.registrar().register(consoleStatus.build(), "Live ADM status for console only")
                    commandsRegistered = true
                    plugin.logger.info("Registered /${commands.name()} and aliases ${commands.aliases().joinToString()}; database subcommand is available.")
                } catch (failure: Throwable) {
                    modules.fail(id(), failure)
                }
            }
        }
        handlerRegistered = true
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun disable() {
        HandlerList.unregisterAll(this)
        state = ModuleStatus.DISABLED
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { service.forget(event.player.uniqueId) }

    @EventHandler
    fun onCommandSend(event: PlayerCommandSendEvent) {
        val player = event.player
        if (player.hasPermission("adm.tier.mod") || player.hasPermission("adm.tier.admin") ||
            player.hasPermission("adm.tier.owner")) return
        event.commands.removeIf { it.lowercase() in commandLabels }
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        // Commands and module enablement remain bound to the startup snapshot.
    }

    private fun execute(source: CommandSourceStack, action: (CommandActor) -> ActionResult): Int {
        val actor = CommandActor.from(source.sender)
        return try {
            val result = action(actor)
            messages.send(actor, result)
            if (result.success()) 1 else 0
        } catch (failure: Throwable) {
            modules.fail(id(), failure)
            messages.send(actor, ActionResult.failure("command.module-unavailable", mapOf("module" to id())))
            0
        }
    }

    private fun supported(sender: CommandSender) = sender is Player || sender is ConsoleCommandSender
}