package com.tecnor.adm.module;

import com.tecnor.adm.api.ActionResult;
import com.tecnor.adm.api.CommandActor;
import com.tecnor.adm.command.AdminService;
import com.tecnor.adm.message.MessageService;
import com.tecnor.adm.settings.SettingsSnapshot;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Function;

/**
 * Lifecycle, registration guard, and status are server-thread confined.
 * Commands are registered once at startup; disabled or failed module services reject execution.
 */
public final class CoreModule implements AdmModule, Listener {
    private final JavaPlugin plugin;
    private final SettingsSnapshot.CommandSettings commands;
    private final ModuleManager modules;
    private final AdminService service;
    private final MessageService messages;
    private ModuleStatus status = ModuleStatus.DISABLED;
    private boolean handlerRegistered;
    private boolean commandsRegistered;

    public CoreModule(JavaPlugin plugin, SettingsSnapshot.CommandSettings commands,
                      ModuleManager modules, AdminService service, MessageService messages) {
        this.plugin = plugin;
        this.commands = commands;
        this.modules = modules;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public String id() {
        return "core";
    }

    @Override
    public void enable() {
        if (handlerRegistered) {
            throw new IllegalStateException("Core command lifecycle handler was already registered");
        }
        status = ModuleStatus.ENABLED;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            if (commandsRegistered || status != ModuleStatus.ENABLED) {
                return;
            }
            try {
                var root = Commands.literal(commands.name())
                        .requires(source -> supported(source.getSender()))
                        .executes(context -> execute(context.getSource(), service::help))
                        .then(Commands.literal("version")
                                .executes(context -> execute(context.getSource(), service::version)))
                        .then(Commands.literal("reload")
                                .executes(context -> execute(context.getSource(), service::reload)))
                        .then(Commands.literal("storage-info")
                                .executes(context -> execute(context.getSource(), service::storageInfo)))
                        .then(Commands.literal("cleanup")
                                .executes(context -> execute(context.getSource(), service::cleanup)))
                        .then(Commands.literal("log")
                                .executes(context -> execute(context.getSource(), actor -> service.log(actor, "")))
                                .then(Commands.argument("filters", StringArgumentType.greedyString())
                                        .executes(context -> execute(context.getSource(), actor -> service.log(actor,
                                                StringArgumentType.getString(context, "filters"))))))
                        .then(Commands.literal("debug")
                                .executes(context -> execute(context.getSource(), service::debug)));
                event.registrar().register(root.build(), "ADM administration commands", commands.aliases());
                commandsRegistered = true;
            } catch (Throwable failure) {
                modules.fail(id(), failure);
            }
        });
        handlerRegistered = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        status = ModuleStatus.DISABLED;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public ModuleStatus status() {
        return status;
    }

    @Override
    public void markFailed() {
        status = ModuleStatus.FAILED;
    }

    @Override
    public void onReload(SettingsSnapshot snapshot) {
        // Commands and module enablement remain bound to the startup snapshot.
    }

    private int execute(CommandSourceStack source, Function<CommandActor, ActionResult> action) {
        CommandActor actor = CommandActor.from(source.getSender());
        try {
            ActionResult result = action.apply(actor);
            messages.send(actor, result);
            return result.success() ? 1 : 0;
        } catch (Throwable failure) {
            modules.fail(id(), failure);
            messages.send(actor, ActionResult.failure("command.module-unavailable", java.util.Map.of("module", id())));
            return 0;
        }
    }

    private static boolean supported(CommandSender sender) {
        return sender instanceof Player || sender instanceof ConsoleCommandSender;
    }
}