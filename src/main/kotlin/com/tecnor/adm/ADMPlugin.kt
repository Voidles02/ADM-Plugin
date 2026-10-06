package com.tecnor.adm

import com.tecnor.adm.api.AuditSink
import com.tecnor.adm.api.EnderChestProviderRegistry
import com.tecnor.adm.api.Storage
import com.tecnor.adm.audit.AuditLogService
import com.tecnor.adm.audit.DatabaseAuditSink
import com.tecnor.adm.audit.FileAuditSink
import com.tecnor.adm.command.AdminService
import com.tecnor.adm.core.CoreListener
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.hud.HudManager
import com.tecnor.adm.integration.GrimCompatibility
import com.tecnor.adm.integration.LuckPermsIntegration
import com.tecnor.adm.integration.LuckPermsRoleSetup
import com.tecnor.adm.integration.WorldGuardCompatibility
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CoreModule
import com.tecnor.adm.module.FeatureModule
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.module.ModuleStatus
import com.tecnor.adm.punishment.PunishmentEnforcement
import com.tecnor.adm.punishment.PunishmentService
import com.tecnor.adm.punishment.Punishments
import com.tecnor.adm.service.ChatService
import com.tecnor.adm.service.AnticheatService
import com.tecnor.adm.service.InformationService
import com.tecnor.adm.service.InventoryToolsService
import com.tecnor.adm.service.PlayerToolsService
import com.tecnor.adm.service.ReportsService
import com.tecnor.adm.service.StaffCommunicationsService
import com.tecnor.adm.service.TeleportService
import com.tecnor.adm.service.TpaService
import com.tecnor.adm.service.VanishService
import com.tecnor.adm.service.WorldControlService
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsLoader
import com.tecnor.adm.staff.StaffToolsService
import com.tecnor.adm.storage.LoginRecorder
import com.tecnor.adm.storage.EmbeddedStorage
import net.luckperms.api.LuckPermsProvider
import org.bukkit.event.HandlerList
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * ADM entry point. Lifecycle fields are server-thread confined.
 * Workers share only the thread-safe services and immutable snapshots documented in their classes.
 *
 * @author voidles02
 */
class ADMPlugin : JavaPlugin() {
    private lateinit var executor: ExecutorService
    private lateinit var settings: ConfigService
    private lateinit var moduleManager: ModuleManager
    private lateinit var adminService: AdminService
    private lateinit var permissions: PermissionService
    private lateinit var hierarchy: HierarchyService
    private lateinit var enderChestProviders: EnderChestProviderRegistry
    private lateinit var storage: EmbeddedStorage
    private lateinit var audit: DatabaseAuditSink
    private lateinit var grimCompatibility: GrimCompatibility

    override fun onEnable() {
        val directory = dataFolder.toPath()
        val initial = try {
            saveDefaultConfig()
            val configuration = getConfig()
            if (!configuration.contains("vanish.on-join-migration-complete", true)) {
                configuration.set("vanish.on-join", false)
                configuration.set("vanish.on-join-migration-complete", true)
                saveConfig()
            }
            if (!directory.resolve("messages.yml").toFile().exists()) saveResource("messages.yml", false)
            if (!directory.resolve("hud.yml").toFile().exists()) saveResource("hud.yml", false)
            SettingsLoader.load(directory)
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "ADM startup settings are invalid; using built-in defaults. Fix the files and use /adm reload.", exception)
            SettingsLoader.defaults()
        }

        settings = ConfigService(directory, initial)
        moduleManager = ModuleManager(logger)
        val messages = MessageService(settings, logger)
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ADM-io").apply { isDaemon = true } }
        adminService = AdminService(this, pluginMeta.version, settings, moduleManager, messages, executor)
        permissions = PermissionService(settings)
        hierarchy = HierarchyService()
        storage = EmbeddedStorage(this)
        server.servicesManager.register(Storage::class.java, storage, this, ServicePriority.Normal)
        server.pluginManager.registerEvents(LoginRecorder(storage, settings, hierarchy), this)
        adminService.setStorage(storage)
        val fileAudit = FileAuditSink(directory.resolve("audit"), logger, initial)
        audit = DatabaseAuditSink(storage, settings, fileAudit, this)
        server.servicesManager.register(AuditSink::class.java, audit, this, ServicePriority.Normal)
        grimCompatibility = GrimCompatibility(this)
        server.pluginManager.registerEvents(grimCompatibility, this)
        grimCompatibility.enable()
        val scheduler = SchedulerHelper(this)
        if (server.pluginManager.isPluginEnabled("LuckPerms")) {
            try {
                val provider = LuckPermsIntegration(this, scheduler)
                hierarchy.provider = provider
                LuckPermsRoleSetup(LuckPermsProvider.get(), logger).setup()
                for (player in server.onlinePlayers) provider.refresh(player.uniqueId)
            } catch (failure: Throwable) {
                hierarchy.provider?.close()
                hierarchy.provider = null
                logger.log(Level.WARNING, "LuckPerms integration unavailable; using ADM tier markers.", failure)
            }
        }
        val worldGuard = if (server.pluginManager.isPluginEnabled("WorldGuard")) {
            runCatching { WorldGuardCompatibility(this) }.onFailure {
                logger.log(Level.WARNING, "WorldGuard integration unavailable; relying on Bukkit teleport events.", it)
            }.getOrNull()
        } else null
        server.pluginManager.registerEvents(CoreListener(this, permissions, hierarchy), this)
        moduleManager.register(CoreModule(this, initial.commands(), moduleManager, adminService, messages))
        val playerTools = PlayerToolsService(this, moduleManager, permissions, hierarchy)
        moduleManager.register(FeatureModule(this, moduleManager, messages, playerTools))
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            TeleportService(this, moduleManager, permissions, messages, scheduler, worldGuard)))
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            TpaService(this, moduleManager, permissions, settings, messages, scheduler, worldGuard)))
        val information = InformationService(this, moduleManager, permissions, settings)
        moduleManager.register(FeatureModule(this, moduleManager, messages, information))
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            AnticheatService(this, moduleManager, permissions, grimCompatibility)))
        moduleManager.register(FeatureModule(this, moduleManager, messages, ChatService(this, moduleManager, permissions, settings, messages, hierarchy)))
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            WorldControlService(this, moduleManager, permissions, messages)))
        enderChestProviders = EnderChestProviderRegistry()
        val inventories = InventoryToolsService(this, moduleManager, permissions, messages, enderChestProviders)
        moduleManager.register(FeatureModule(this, moduleManager, messages, inventories))
        val vanish = VanishService(this, moduleManager, permissions, settings, messages)
        moduleManager.register(FeatureModule(this, moduleManager, messages, vanish))
        val punishments = Punishments(storage)
        if (initial.modules().getOrDefault("punishments", true)) {
            server.pluginManager.registerEvents(PunishmentEnforcement(punishments, settings, messages), this)
        }
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            PunishmentService(this, moduleManager, permissions, hierarchy, settings, messages, punishments)))
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            StaffCommunicationsService(this, moduleManager, permissions, settings, messages, hierarchy)))
        moduleManager.register(FeatureModule(this, moduleManager, messages,
            StaffToolsService(this, moduleManager, permissions, settings, messages, hierarchy, storage, vanish, inventories, information, playerTools)))
        moduleManager.register(FeatureModule(this, moduleManager, messages, ReportsService(this, moduleManager, permissions, storage, settings, messages)))
        val auditLog = AuditLogService(this, moduleManager, permissions, storage, settings, messages)
        moduleManager.register(FeatureModule(this, moduleManager, messages, auditLog))
        adminService.setAuditLog(auditLog)
        val hud = HudManager(this, moduleManager, messages, storage, adminService, enderChestProviders)
        moduleManager.register(FeatureModule(this, moduleManager, messages, hud))
        moduleManager.enableConfigured(initial)
        logStartupSummary()
        storage.ready.thenAccept { available -> scheduler.main(Runnable {
            if (!available) {
                for (module in listOf("punishments", "staff-tools", "reports", "audit")) {
                    if (moduleManager.isEnabled(module)) moduleManager.fail(module, IllegalStateException("Embedded database connection unavailable: ${storage.health.lastError}"))
                }
            }
        }) }
    }

    private fun logStartupSummary() {
        val statuses = moduleManager.statuses().values
        val enabled = statuses.count { it == ModuleStatus.ENABLED }
        val disabled = statuses.count { it == ModuleStatus.DISABLED }
        val failed = statuses.count { it == ModuleStatus.FAILED }
        val barWidth = 16
        val filled = if (statuses.isEmpty()) 0 else enabled * barWidth / statuses.size
        val moduleBar = "█".repeat(filled) + "░".repeat(barWidth - filled)
        val version = pluginMeta.version.takeUnless { it.isBlank() || it.contains('$') }
            ?: javaClass.getPackage()?.implementationVersion
            ?: "unknown"
        val minecraftVersion = server.minecraftVersion
        val players = "${server.onlinePlayers.size}/${server.maxPlayers}"
        val optionalPlugins = listOf(
            "voicechat" to "Voice Chat",
            "BetterTeams" to "BetterTeams",
            "BetterStasis" to "BetterStasis",
            "FakeSeed" to "FakeSeed"
        ).filter { (pluginName, _) -> server.pluginManager.isPluginEnabled(pluginName) }
            .joinToString("  ·  ") { (_, displayName) -> displayName }
        val bannerRows = listOf(
            "  VERSION   $version",
            "  SERVER    Minecraft $minecraftVersion  ·  Java ${Runtime.version().feature()}",
            "  MODULES   [$moduleBar]  $enabled/${statuses.size} enabled",
            "  HEALTH    $failed failed  ·  $disabled disabled",
            "  PLAYERS   $players online",
            "  OPTIONAL  ${optionalPlugins.ifEmpty { "No optional integrations detected" }}"
        )
        val contentWidth = maxOf(64, bannerRows.maxOf { it.length })
        val border = "─".repeat(contentWidth)
        fun row(text: String) = "│${text.padEnd(contentWidth)}│"
        fun centered(text: String): String {
            val value = text.take(contentWidth)
            val left = (contentWidth - value.length) / 2
            return " ".repeat(left) + value + " ".repeat(contentWidth - left - value.length)
        }

        logger.info("╭$border╮")
        logger.info(row(centered("ADM  ·  ADVANCED ADMIN MANAGEMENT")))
        logger.info("├$border┤")
        bannerRows.forEach { logger.info(row(it)) }
        logger.info("╰$border╯")
    }

    override fun onDisable() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        if (::adminService.isInitialized) adminService.stop()
        if (::moduleManager.isInitialized) moduleManager.disableAll()
        if (::storage.isInitialized) {
            storage.closeWithin(TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0)))
            server.servicesManager.unregister(storage)
        }
        if (::audit.isInitialized) {
            server.servicesManager.unregister(audit)
            audit.closeWithin(TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0)))
        }
        if (::grimCompatibility.isInitialized) grimCompatibility.close()
        HandlerList.unregisterAll(this)
        server.scheduler.cancelTasks(this)
        if (::hierarchy.isInitialized) hierarchy.provider?.close()
        if (::permissions.isInitialized) permissions.clear()
        if (::executor.isInitialized) {
            executor.shutdown()
            try {
                if (!executor.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow()
                    logger.warning("ADM executor did not stop within the five-second shutdown budget.")
                }
            } catch (_: InterruptedException) {
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    fun settings() = settings
    fun modules() = moduleManager
    fun luckPermsHooked() = ::hierarchy.isInitialized && hierarchy.provider != null
}