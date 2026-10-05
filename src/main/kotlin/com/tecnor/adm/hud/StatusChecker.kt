package com.tecnor.adm.hud

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.module.ModuleStatus
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitTask
import java.time.Instant

private val statusMiniMessage = MiniMessage.miniMessage()

data class ModuleHealth(val module: String, val color: String, val material: Material, val details: List<String>) {
    fun lore() = listOf("<$color>Health: ${when (color) { "green" -> "working"; "yellow" -> "degraded"; "red" -> "disabled / failed"; else -> "no permission" }}</$color>") +
        details.map { "<gray>${statusMiniMessage.escapeTags(it)}</gray>" }
}

class StatusChecker(private val manager: HudManager) : AutoCloseable {
    private var cachedAt = 0L
    private var cached = emptyMap<String, ModuleHealth>()
    private var nextPing = 0L
    private var liveTask: BukkitTask? = null
    private var lastStorageError = ""
    private val storageErrors = ArrayDeque<Pair<Long, String>>()
    private val storageModules = setOf("punishments", "staff-tools", "reports", "audit")
    private val storedTargetModules = setOf("punishments", "reports")

    fun clearCache() { cached = emptyMap(); cachedAt = 0; nextPing = 0 }

    fun snapshot(force: Boolean = false): Map<String, ModuleHealth> {
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && now - cachedAt < manager.number("status.ttl-seconds", 5) * 1000L) return cached
        val health = manager.storage.health
        if (health.lastError.isNotEmpty() && health.lastError != lastStorageError) {
            lastStorageError = health.lastError
            if (storageErrors.size == 16) storageErrors.removeFirst()
            storageErrors.addLast(now to health.lastError.take(256))
        }
        val pending = manager.storage.pendingByModule()
        val registeredListeners = org.bukkit.event.HandlerList.getRegisteredListeners(manager.plugin)
        cached = manager.modules.statuses().mapValues { (module, status) ->
            val feature = manager.modules.features().firstOrNull { it.id() == module }
            val configured = module == "hud" || manager.plugin.settings().current().modules().getOrDefault(module, true)
            val registered = if (module == "core") Bukkit.getCommandMap().getCommand(manager.plugin.settings().startup().commands().name()) != null else
                feature?.commandsRegistered() == true || feature?.service?.commands?.isEmpty() == true
            val needsListener = feature?.service is Listener || module in setOf("core", "punishments")
            val listeners = when (module) {
                "core" -> registeredListeners.any { it.listener.javaClass.name == "com.tecnor.adm.core.CoreListener" }
                "punishments" -> registeredListeners.any { it.listener.javaClass.name == "com.tecnor.adm.punishment.PunishmentEnforcement" }
                else -> !needsListener || registeredListeners.any { it.listener === feature?.service }
            }
            val lp = manager.plugin.luckPermsHooked()
            val providers = manager.providers.providers()
            var color = when {
                !configured || status != ModuleStatus.ENABLED || !registered || !listeners -> "red"
                module in storageModules && health.state == "FAILED" -> "red"
                module in storageModules && (health.state != "CONNECTED" || health.latencyMs > manager.number("status.slow-storage-ms", 100)) -> "yellow"
                module in setOf("punishments", "staff-tools", "player-tools") && !lp -> "yellow"
                module == "inventory" && providers.isEmpty() -> "red"
                else -> "green"
            }
            val errors = manager.modules.errors().filter { it.module() == module }.takeLast(3)
            if (status == ModuleStatus.FAILED) color = "red"
            val servicePending = when (val service = feature?.service) {
                is com.tecnor.adm.service.TeleportService -> service.pendingCount()
                is com.tecnor.adm.staff.StaffToolsService -> service.pendingCount()
                is HudManager -> service.sessions.values.sumOf { session -> synchronized(session) { session.futures.size + session.tasks.size } }
                else -> 0
            }
            val details = mutableListOf("Config enabled: $configured; state: $status", "Commands registered: $registered; listeners: ${if (needsListener) listeners else "not required"}",
                "Storage: ${health.state}; last latency: ${health.latencyMs}ms", "Pending async: ${maxOf(pending[module] ?: 0, servicePending)}; database total: ${manager.storage.pendingTasks}",
                "LuckPerms: ${if (lp) "hooked" else "absent / fallback tiers"}")
            if (module == "inventory") details += providers.map { "Provider ${it.id}: offline=${it.supportsOffline}, read/edit leases, priority=${it.priority}" }
            details += errors.map { "Error ${Instant.ofEpochMilli(it.timestamp())}: ${it.message()}" }
            if (module in storageModules) details += storageErrors.takeLast(1).map { "Storage error ${Instant.ofEpochMilli(it.first)}: ${it.second}" }
            ModuleHealth(module, color, material(color), details)
        }
        cachedAt = now
        return cached
    }

    private fun material(color: String) = when (color) { "green" -> Material.LIME_DYE; "yellow" -> Material.YELLOW_DYE; "red" -> Material.RED_DYE; else -> Material.GRAY_DYE }

    fun module(module: String, viewer: Player, node: String = ""): ModuleHealth {
        val status = snapshot()[module] ?: ModuleHealth(module, "red", Material.RED_DYE, listOf("Unknown module"))
        if (node.isNotEmpty() && !viewer.hasPermission(node)) return status.copy(color = "gray", material = Material.GRAY_DYE, details = status.details + "Required node: $node (missing)")
        if (!manager.moduleAvailable(module) && module != "hud") return status.copy(color = "red", material = Material.RED_DYE)
        if (module in storageModules && manager.storage.health.state == "FAILED") return status.copy(color = "red", material = Material.RED_DYE)
        return status.copy(details = status.details + if (node.isEmpty()) "Viewer permission: see command-specific items" else "Required node: $node (granted)")
    }

    fun action(action: HudAction, viewer: Player): ModuleHealth {
        val status = module(action.module, viewer, action.node)
        if (status.color == "gray" || status.color == "red") return status
        val root = if (action.module in setOf("core", "audit")) manager.plugin.settings().startup().commands().name() else action.command
        if (Bukkit.getCommandMap().getCommand(root) == null) return status.copy(color = "red", material = Material.RED_DYE, details = status.details + "Command unresolved: $root")
        return status
    }

    fun refresh(session: HudSession, viewer: Player, force: Boolean = false) {
        snapshot(force)
        val now = System.currentTimeMillis()
        if (now < nextPing && !force) { manager.refresh(session, viewer); return }
        nextPing = now + manager.number("status.ttl-seconds", 5).coerceIn(1, 60) * 1000L
        manager.load(session, { db -> db.createStatement().use { it.executeQuery("SELECT 1").use { rows -> rows.next() && rows.getInt(1) == 1 } } }) { _, _ ->
            snapshot(true)
            if (session.screen is StatusScreen) manager.refresh(session, viewer)
        }
    }

    fun test(session: HudSession, viewer: Player, module: String, done: (String) -> Unit) {
        if (!viewer.hasPermission("adm.hud.status.test")) return
        val actions = HudActions.actions.values.flatten().filter { it.module == module }
        val nodes = (actions.map { it.node } + when (module) { "reports" -> listOf("adm.mod.reports"); "audit" -> listOf("adm.admin.log"); "hud" -> listOf("adm.hud.use"); else -> emptyList() }).distinct()
        val permitted = nodes.isEmpty() || nodes.any(viewer::hasPermission)
        val featureCommands = manager.service(module)?.commands.orEmpty().map { it.name }
        val registered = featureCommands.all { Bukkit.getCommandMap().getCommand(it) != null } && actions.all { action ->
            val root = if (action.module in setOf("core", "audit")) manager.plugin.settings().startup().commands().name() else action.command
            Bukkit.getCommandMap().getCommand(root) != null
        }
        val target = session.target.id.toString()
        val requiresStoredTarget = module in storedTargetModules
        val checks = listOf("module" to (module == "hud" || manager.moduleAvailable(module)), "commands" to registered, "permission" to permitted)
        manager.load(session, { db ->
            val ping = db.createStatement().use { it.executeQuery("SELECT 1").use { rows -> rows.next() && rows.getInt(1) == 1 } }
            val resolved = !requiresStoredTarget || db.prepareStatement("SELECT uuid FROM players WHERE uuid=?").use { statement -> statement.setString(1, target); statement.executeQuery().use { it.next() } }
            ping to resolved
        }) { result, error ->
            val results = checks + listOf("storage ping" to (error == null && result?.first == true)) +
                if (requiresStoredTarget) listOf("stored target" to (error == null && result?.second == true)) else emptyList()
            val pass = results.all { it.second }
            val details = results.joinToString(", ") { "${it.first}=${if (it.second) "PASS" else "FAIL"}" }
            val text = "${if (pass) "PASS" else "FAIL"}: $details"
            done(text)
            manager.messages.send(CommandActor.from(viewer), ActionResult.success("hud.status-test", mapOf("module" to module, "result" to text)))
            manager.audit(viewer, session.target, "STATUS_TEST", module, mapOf("result" to text))
            snapshot(true)
            manager.refresh(session, viewer)
        }
    }

    fun updateLiveTask() {
        val seconds = manager.number("status.live-refresh-seconds", 0)
        val visible = manager.sessions.values.any { it.screen is StatusScreen && !it.awaitingChat }
        if (seconds <= 0 || !visible) { liveTask?.cancel(); liveTask = null; return }
        if (liveTask != null) return
        liveTask = manager.plugin.server.scheduler.runTaskTimer(manager.plugin, Runnable {
            val sessions = manager.sessions.values.filter { it.screen is StatusScreen && !it.awaitingChat }
            if (sessions.isEmpty()) { updateLiveTask(); return@Runnable }
            snapshot(true)
            sessions.forEach { session -> Bukkit.getPlayer(session.viewer)?.let { viewer -> refresh(session, viewer) } }
        }, seconds.coerceIn(1, 60) * 20L, seconds.coerceIn(1, 60) * 20L)
    }
    override fun close() { liveTask?.cancel(); liveTask = null; clearCache(); storageErrors.clear() }
}

class StatusScreen : PaginatedHudScreen() {
    override val id = "status"
    override val title = "System Status"
    private val tests = mutableMapOf<String, String>()
    private var selected = "core"
    override fun opened(manager: HudManager, session: HudSession, viewer: Player) = manager.statusChecker.refresh(session, viewer)
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val statuses = manager.statusChecker.snapshot()
        val slots = manager.contentSlots(session)
        val count = slots.size - 1
        val entries = statuses.keys.toList()
        pages = ((entries.size + count - 1) / count).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        val good = statuses.values.count { it.color == "green" }
        val failing = statuses.values.filter { it.color != "green" }.joinToString(", ") { it.module }
        val buttons = entries.drop(page * count).take(count).mapIndexed { index, module ->
            val node = HudActions.actions.values.flatten().firstOrNull { it.module == module }?.node.orEmpty()
            val health = manager.statusChecker.module(module, viewer, node)
            HudButton(slots[index], "module-$module", "<${health.color}>$module</${health.color}>", health.material,
                lore = health.lore() + listOf("<gray>Left: select for Test</gray>", "<gray>Last Test: ${manager.escape(tests[module] ?: "not tested")}</gray>")) { m, s, p, _ ->
                selected = module
                m.refresh(s, p)
            }
        }.toMutableList()
        buttons += HudButton(slots.last(), "status-summary", "$good/${statuses.size} modules healthy", Material.BEACON,
            lore = listOf("<gray>Not healthy: ${manager.escape(failing.ifEmpty { "none" })}</gray>")) { _, _, _, _ -> }
        buttons += HudButton(manager.slot("refresh", 51), "refresh", "Refresh Status", Material.SUNFLOWER) { m, s, p, _ -> m.statusChecker.refresh(s, p, true) }
        buttons += HudButton(manager.slot("test", 52), "test", "Test: $selected", Material.TRIPWIRE_HOOK, "adm.hud.status.test",
            lore = listOf("<gray>Safe, non-destructive dry run</gray>", "<gray>${manager.escape(tests[selected] ?: "not tested")}</gray>")) { m, s, p, _ ->
            val module = selected
            m.statusChecker.test(s, p, module) { tests[module] = it }
        }
        return buttons + navigation(manager)
    }
}