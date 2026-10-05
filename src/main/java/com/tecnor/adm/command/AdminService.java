package com.tecnor.adm.command;

import com.tecnor.adm.api.ActionResult;
import com.tecnor.adm.api.CommandActor;
import com.tecnor.adm.Stage0Identity;
import com.tecnor.adm.core.PermissionService;
import com.tecnor.adm.core.SchedulerHelper;
import com.tecnor.adm.message.MessageService;
import com.tecnor.adm.module.ModuleManager;
import com.tecnor.adm.settings.ConfigService;
import com.tecnor.adm.settings.SettingsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Public actions run on the server thread and return plain ActionResults.
 * The reload worker captures only immutable identity and settings data. It never resolves players.
 * Atomic flags synchronize shutdown and reload admission with the executor thread.
 */
public final class AdminService {
    private final JavaPlugin plugin;
    private final String version;
    private final ConfigService settings;
    private final ModuleManager modules;
    private final MessageService messages;
    private final ExecutorService executor;
    private final PermissionService permissions;
    private final SchedulerHelper scheduler;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean reloading = new AtomicBoolean(false);
    private com.tecnor.adm.api.Storage storage;
    private com.tecnor.adm.audit.AuditLogService auditLog;
    private boolean debugging;
    private final Level originalLogLevel;

    public boolean isDebugging() {
        return debugging;
    }

    public void setAuditLog(com.tecnor.adm.audit.AuditLogService auditLog) {
        this.auditLog = auditLog;
    }

    public ActionResult log(CommandActor actor, String arguments) {
        return auditLog.execute(actor, "log", arguments);
    }

    public ActionResult cleanup(CommandActor actor) {
        return auditLog.execute(actor, "cleanup", "");
    }

    public void setStorage(com.tecnor.adm.api.Storage storage) {
        this.storage = storage;
    }

    public ActionResult storageInfo(CommandActor actor) {
        ActionResult denied = check(actor, "adm.admin.storage-info");
        if (denied != null) return denied;
        var health = storage.getHealth();
        return ActionResult.success("storage.info", Map.of("state", health.getState(),
                "success", Long.toString(health.getLastSuccess()), "latency", Long.toString(health.getLatencyMs()),
                "error", health.getLastError()));
    }

    public AdminService(JavaPlugin plugin, String version, ConfigService settings,
                        ModuleManager modules, MessageService messages, ExecutorService executor) {
        this.plugin = plugin;
        this.version = version;
        this.settings = settings;
        this.modules = modules;
        this.messages = messages;
        this.executor = executor;
        this.permissions = new PermissionService(settings);
        this.scheduler = new SchedulerHelper(plugin);
        this.originalLogLevel = plugin.getLogger().getLevel();
    }

    public ActionResult help(CommandActor actor) {
        ActionResult unavailable = unavailable();
        if (unavailable != null) {
            return unavailable;
        }
        if (!actor.hasPermission("adm.admin.version") && !actor.hasPermission("adm.admin.reload")
                && !actor.hasPermission("adm.admin.debug") && !actor.hasPermission("adm.admin.storage-info")
                && !actor.hasPermission("adm.admin.log") && !actor.hasPermission("adm.admin.cleanup")) {
            return ActionResult.failure("command.no-permission");
        }
        return ActionResult.success("command.help", Map.of("command", settings.startup().commands().name()));
    }

    public ActionResult version(CommandActor actor) {
        ActionResult denied = check(actor, "adm.admin.version");
        if (denied != null) {
            return denied;
        }
        permissions.record(actor, "version");
        return ActionResult.success("command.version", Map.of("version", version, "author", Stage0Identity.AUTHOR));
    }

    public ActionResult debug(CommandActor actor) {
        ActionResult denied = check(actor, "adm.admin.debug");
        if (denied != null) {
            return denied;
        }
        debugging = !debugging;
        plugin.getLogger().setLevel(debugging ? Level.FINE : originalLogLevel);
        StringBuilder details = new StringBuilder("Debug: " + (debugging ? "ON" : "OFF"));
        modules.statuses().forEach((id, status) -> {
            if (!details.isEmpty()) {
                details.append('\n');
            }
            details.append(id).append(": ").append(status.name());
        });
        permissions.record(actor, "debug");
        return ActionResult.success("command.debug", Map.of("details", details.toString()));
    }

    public ActionResult reload(CommandActor actor) {
        ActionResult denied = check(actor, "adm.admin.reload");
        if (denied != null) {
            return denied;
        }
        if (!accepting.get()) {
            return ActionResult.failure("reload.unavailable");
        }
        if (!reloading.compareAndSet(false, true)) {
            return ActionResult.failure("reload.busy");
        }
        try {
            executor.execute(() -> readReload(actor));
        } catch (RejectedExecutionException exception) {
            reloading.set(false);
            return ActionResult.failure("reload.unavailable");
        }
        permissions.record(actor, "reload");
        return ActionResult.success("reload.started");
    }

    public void stop() {
        accepting.set(false);
        permissions.clear();
        plugin.getLogger().setLevel(originalLogLevel);
    }

    public void forget(java.util.UUID id) {
        permissions.forget(id);
    }

    private void readReload(CommandActor actor) {
        SettingsSnapshot candidate;
        try {
            candidate = settings.readForReload();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "ADM settings reload rejected.", exception);
            String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            dispatch(() -> {
                try {
                    if (accepting.get()) {
                        messages.send(actor, ActionResult.failure("reload.invalid", Map.of("error", error)));
                    }
                } finally {
                    reloading.set(false);
                }
            });
            return;
        }
        dispatch(() -> finishReload(actor, candidate));
    }

    private void finishReload(CommandActor actor, SettingsSnapshot candidate) {
        try {
            if (!accepting.get()) {
                return;
            }
            List<String> restarts = settings.restartRequired(candidate);
            settings.apply(candidate);
            List<String> failed = modules.reload(candidate);
            messages.send(actor, ActionResult.success("reload.success"));
            for (String setting : restarts) {
                messages.send(actor, ActionResult.success("reload.restart-required", Map.of("setting", setting)));
            }
            for (String id : failed) {
                messages.send(actor, ActionResult.failure("reload.module-failed", Map.of("module", id)));
            }
        } finally {
            reloading.set(false);
        }
    }

    private void dispatch(Runnable callback) {
        if (!accepting.get()) {
            reloading.set(false);
            return;
        }
        if (!scheduler.main(callback)) {
            reloading.set(false);
        }
    }

    private ActionResult check(CommandActor actor, String permission) {
        ActionResult unavailable = unavailable();
        if (unavailable != null) {
            return unavailable;
        }
        ActionResult denied = permissions.check(actor, permission);
        return denied != null ? denied : permissions.cooldown(actor, permission.substring(permission.lastIndexOf('.') + 1));
    }

    private ActionResult unavailable() {
        if (!modules.isEnabled("core")) {
            return ActionResult.failure("command.module-unavailable", Map.of("module", "core"));
        }
        return null;
    }
}