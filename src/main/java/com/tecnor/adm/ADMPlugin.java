package com.tecnor.adm;

import com.tecnor.adm.command.AdminService;
import com.tecnor.adm.core.CoreListener;
import com.tecnor.adm.core.HierarchyService;
import com.tecnor.adm.core.PermissionService;
import com.tecnor.adm.core.SchedulerHelper;
import com.tecnor.adm.integration.LuckPermsIntegration;
import com.tecnor.adm.message.MessageService;
import com.tecnor.adm.module.CoreModule;
import com.tecnor.adm.module.ModuleManager;
import com.tecnor.adm.module.FeatureModule;
import com.tecnor.adm.service.PlayerToolsService;
import com.tecnor.adm.service.TeleportService;
import com.tecnor.adm.service.InformationService;
import com.tecnor.adm.service.ChatService;
import com.tecnor.adm.service.InventoryToolsService;
import com.tecnor.adm.service.VanishService;
import com.tecnor.adm.api.EnderChestProviderRegistry;
import com.tecnor.adm.settings.SettingsLoader;
import com.tecnor.adm.settings.ConfigService;
import com.tecnor.adm.settings.SettingsSnapshot;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * ADM entry point. Lifecycle fields are server-thread confined.
 * Workers share only the thread-safe services and immutable snapshots documented in their classes.
 *
 * @author voidles02
 */
public final class ADMPlugin extends JavaPlugin {
    private ExecutorService executor;
    private ConfigService settings;
    private ModuleManager modules;
    private AdminService adminService;
    private PermissionService permissions;
    private HierarchyService hierarchy;
    private EnderChestProviderRegistry enderChestProviders;
    private com.tecnor.adm.storage.SqliteStorage storage;
    private com.tecnor.adm.audit.DatabaseAuditSink audit;

    @Override
    public void onEnable() {
        Path directory = getDataFolder().toPath();
        SettingsSnapshot initial;
        try {
            saveDefaultConfig();
            if (!directory.resolve("messages.yml").toFile().exists()) {
                saveResource("messages.yml", false);
            }
            if (!directory.resolve("hud.yml").toFile().exists()) {
                saveResource("hud.yml", false);
            }
            initial = SettingsLoader.load(directory);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "ADM startup settings are invalid; using built-in defaults. Fix the files and use /adm reload.", exception);
            initial = SettingsLoader.defaults();
        }

        settings = new ConfigService(directory, initial);
        modules = new ModuleManager(getLogger());
        MessageService messages = new MessageService(settings, getLogger());
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ADM-io");
            thread.setDaemon(true);
            return thread;
        });
        adminService = new AdminService(this, getPluginMeta().getVersion(), settings, modules, messages, executor);
        permissions = new PermissionService(settings);
        hierarchy = new HierarchyService();
        storage = new com.tecnor.adm.storage.SqliteStorage(this);
        getServer().getServicesManager().register(com.tecnor.adm.api.Storage.class, storage, this,
                org.bukkit.plugin.ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new com.tecnor.adm.storage.LoginRecorder(storage, settings, hierarchy), this);
        adminService.setStorage(storage);
        var fileAudit = new com.tecnor.adm.audit.FileAuditSink(directory.resolve("audit"), getLogger(), initial);
        audit = new com.tecnor.adm.audit.DatabaseAuditSink(storage, settings, fileAudit, this);
        getServer().getServicesManager().register(com.tecnor.adm.api.AuditSink.class, audit, this,
                org.bukkit.plugin.ServicePriority.Normal);
        SchedulerHelper scheduler = new SchedulerHelper(this);
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                hierarchy.setProvider(new LuckPermsIntegration(this, scheduler));
                for (var player : getServer().getOnlinePlayers()) {
                    hierarchy.getProvider().refresh(player.getUniqueId());
                }
            } catch (Throwable failure) {
                if (hierarchy.getProvider() != null) {
                    hierarchy.getProvider().close();
                }
                hierarchy.setProvider(null);
                getLogger().log(Level.WARNING, "LuckPerms integration unavailable; using ADM tier markers.", failure);
            }
        }
        getServer().getPluginManager().registerEvents(new CoreListener(this, permissions, hierarchy), this);
        modules.register(new CoreModule(this, initial.commands(), modules, adminService, messages));
        var playerTools = new PlayerToolsService(this, modules, permissions, hierarchy);
        modules.register(new FeatureModule(this, modules, messages, playerTools));
        modules.register(new FeatureModule(this, modules, messages,
                new TeleportService(this, modules, permissions, messages, scheduler)));
        var information = new InformationService(this, modules, permissions, settings);
        modules.register(new FeatureModule(this, modules, messages, information));
        modules.register(new FeatureModule(this, modules, messages,
                new ChatService(this, modules, permissions, settings, messages, hierarchy)));
        enderChestProviders = new EnderChestProviderRegistry();
        var inventories = new InventoryToolsService(this, modules, permissions, messages, enderChestProviders);
        modules.register(new FeatureModule(this, modules, messages, inventories));
        var vanish = new VanishService(this, modules, permissions, settings, messages);
        modules.register(new FeatureModule(this, modules, messages, vanish));
        var punishments = new com.tecnor.adm.punishment.Punishments(storage);
        if (initial.modules().getOrDefault("punishments", true)) {
            getServer().getPluginManager().registerEvents(new com.tecnor.adm.punishment.PunishmentEnforcement(punishments, settings, messages), this);
        }
        modules.register(new FeatureModule(this, modules, messages,
                new com.tecnor.adm.punishment.PunishmentService(this, modules, permissions, hierarchy, settings, messages, punishments)));
        modules.register(new FeatureModule(this, modules, messages,
                new com.tecnor.adm.service.StaffCommunicationsService(this, modules, permissions, settings, messages, hierarchy)));
        modules.register(new FeatureModule(this, modules, messages,
                new com.tecnor.adm.staff.StaffToolsService(this, modules, permissions, settings, messages, hierarchy, storage, vanish, inventories, information, playerTools)));
        modules.register(new FeatureModule(this, modules, messages,
                new com.tecnor.adm.service.ReportsService(this, modules, permissions, storage, settings, messages)));
        var auditLog = new com.tecnor.adm.audit.AuditLogService(this, modules, permissions, storage, settings, messages);
        modules.register(new FeatureModule(this, modules, messages, auditLog));
        adminService.setAuditLog(auditLog);
        var hud = new com.tecnor.adm.hud.HudManager(this, modules, messages, storage, adminService, enderChestProviders);
        modules.register(new FeatureModule(this, modules, messages, hud));
        modules.enableConfigured(initial);
        storage.getReady().thenAccept(available -> scheduler.main(() -> {
            if (!available) {
                for (String module : java.util.List.of("punishments", "staff-tools", "reports", "audit")) {
                    if (modules.isEnabled(module)) modules.fail(module, new IllegalStateException("SQLite driver or connection unavailable"));
                }
            }
        }));
    }

    @Override
    public void onDisable() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        if (adminService != null) {
            adminService.stop();
        }
        if (modules != null) {
            modules.disableAll();
        }
        if (storage != null) {
            storage.closeWithin(TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime())));
            getServer().getServicesManager().unregister(storage);
        }
        if (audit != null) {
            getServer().getServicesManager().unregister(audit);
            audit.closeWithin(TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime())));
        }
        HandlerList.unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
        if (hierarchy != null && hierarchy.getProvider() != null) {
            hierarchy.getProvider().close();
        }
        if (permissions != null) {
            permissions.clear();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                    getLogger().warning("ADM executor did not stop within the five-second shutdown budget.");
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public ConfigService settings() {
        return settings;
    }

    public ModuleManager modules() {
        return modules;
    }

    public boolean luckPermsHooked() {
        return hierarchy != null && hierarchy.getProvider() != null;
    }
}