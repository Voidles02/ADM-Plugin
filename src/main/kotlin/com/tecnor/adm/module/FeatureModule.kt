package com.tecnor.adm.module

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestions
import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.settings.SettingsSnapshot
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture

data class CommandSpec(val name: String, val aliases: List<String> = emptyList())

/** Services and all command/lifecycle calls are main-thread confined. */
interface FeatureService {
    val id: String
    val commands: List<CommandSpec>
    fun execute(actor: CommandActor, command: String, arguments: String): ActionResult
    fun suggestions(command: String, completed: List<String>): List<String>
    fun enable()
    fun disable()
    fun onReload(snapshot: SettingsSnapshot)
}

/** Startup-only registration; module failures disable listeners and reject further service execution. */
class FeatureModule(
    private val plugin: JavaPlugin,
    private val modules: ModuleManager,
    private val messages: MessageService,
    val service: FeatureService
) : AdmModule {
    private var state = ModuleStatus.DISABLED
    private var registered = false
    private val scheduler = SchedulerHelper(plugin)

    override fun id() = service.id
    override fun status() = state
    override fun markFailed() { state = ModuleStatus.FAILED }

    fun commandsRegistered() = registered
    fun listenersRegistered() = state == ModuleStatus.ENABLED && service is Listener

    override fun enable() {
        state = ModuleStatus.ENABLED
        service.enable()
        if (service is Listener) plugin.server.pluginManager.registerEvents(service, plugin)
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            if (!registered && state == ModuleStatus.ENABLED) {
                try {
                    for (spec in service.commands) {
                        val root = Commands.literal(spec.name)
                            .requires { it.sender is Player || it.sender is ConsoleCommandSender }
                            .executes { run(CommandActor.from(it.source.sender), spec.name, "") }
                            .then(Commands.argument("arguments", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    val future = CompletableFuture<Suggestions>()
                                    val complete = Runnable {
                                        try {
                                            val input = builder.remaining
                                            val lastSpace = input.lastIndexOf(' ')
                                            val completed = if (lastSpace < 0) emptyList() else
                                                input.substring(0, lastSpace).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                            val prefix = input.substring(lastSpace + 1)
                                            val output = builder.createOffset(builder.start + lastSpace + 1)
                                            if (state == ModuleStatus.ENABLED) {
                                                service.suggestions(spec.name, completed)
                                                    .filter { it.startsWith(prefix, ignoreCase = true) }
                                                    .distinct().forEach { output.suggest(it) }
                                            }
                                            future.complete(output.build())
                                        } catch (failure: Throwable) {
                                            modules.fail(id(), failure)
                                            future.complete(builder.build())
                                        }
                                    }
                                    if (Bukkit.isPrimaryThread()) complete.run()
                                    else if (!scheduler.main(complete)) future.complete(builder.build())
                                    future
                                }
                                .executes { run(CommandActor.from(it.source.sender), spec.name,
                                    StringArgumentType.getString(it, "arguments")) })
                        event.registrar().register(root.build(), "ADM ${spec.name}", spec.aliases)
                    }
                    registered = true
                } catch (failure: Throwable) {
                    modules.fail(id(), failure)
                }
            }
        }
    }

    private fun run(actor: CommandActor, command: String, arguments: String): Int = try {
        val result = service.execute(actor, command, arguments)
        messages.send(actor, result)
        if (result.success()) 1 else 0
    } catch (failure: Throwable) {
        modules.fail(id(), failure)
        messages.send(actor, ActionResult.failure("command.module-unavailable", mapOf("module" to id())))
        0
    }

    override fun disable() {
        state = ModuleStatus.DISABLED
        if (service is Listener) HandlerList.unregisterAll(service)
        service.disable()
    }

    override fun onReload(snapshot: SettingsSnapshot) { service.onReload(snapshot) }
}