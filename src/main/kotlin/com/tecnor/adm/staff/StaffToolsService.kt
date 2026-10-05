package com.tecnor.adm.staff

import com.tecnor.adm.api.*
import com.tecnor.adm.service.ServiceSupport
import com.tecnor.adm.service.VanishService
import com.tecnor.adm.service.InventoryToolsService
import com.tecnor.adm.service.InformationService
import com.tecnor.adm.service.PlayerToolsService
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import com.tecnor.adm.staff.StaffSnapshot
import com.tecnor.adm.storage.query
import com.tecnor.adm.storage.update
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class StaffToolsService(plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
                        private val settings: ConfigService, private val messages: MessageService,
                        private val hierarchy: HierarchyService, storage: Storage,
                        private val vanish: VanishService, private val inventories: InventoryToolsService,
                        private val information: InformationService, private val playerTools: PlayerToolsService) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "staff-tools"
    private val storage = ScopedStorage(storage, id)
    override val commands = listOf(CommandSpec("freeze"), CommandSpec("staffmode", listOf("sm")))
    private val frozen = ConcurrentHashMap.newKeySet<UUID>()
    private val recovery = ConcurrentHashMap<UUID, ByteArray>()
    private val sessions = ConcurrentHashMap<UUID, StaffSnapshot>()
    private val pending = mutableSetOf<UUID>()
    private val restoring = mutableSetOf<UUID>()
    private val freezePending = mutableSetOf<UUID>()
    private var actionbar: BukkitTask? = null
    private var stopping = false
    private val scheduler = SchedulerHelper(plugin)
    private val toolKey = NamespacedKey(plugin, "staff_tool")

    fun isProtected(id: UUID) = sessions.containsKey(id) || id in pending || id in restoring

    fun isFrozen(id: UUID) = id in frozen
    fun isStaffMode(id: UUID) = sessions.containsKey(id)
    fun pendingCount() = pending.size + restoring.size + freezePending.size

    override fun enable() {
        stopping = false
        plugin.server.servicesManager.register(StaffToolsService::class.java, this, plugin, org.bukkit.plugin.ServicePriority.Normal)
        storage.submit { db -> db.query("SELECT uuid FROM frozen") { UUID.fromString(it.getString(1)) } }
            .thenAccept { ids -> scheduler.main(Runnable {
                if (!stopping) {
                    frozen.addAll(ids)
                    updateTask()
                }
            }) }
        Bukkit.getOnlinePlayers().forEach { player ->
            val id = player.uniqueId
            pending.add(id)
            storage.submit { db -> db.query("SELECT data FROM staff_snapshots WHERE uuid=?", id.toString()) { it.getBytes(1) }.firstOrNull() }
                .whenComplete { bytes, error -> scheduler.main(Runnable {
                    if (stopping) return@Runnable
                    pending.remove(id)
                    if (error == null && bytes != null && player.isOnline) recover(player, bytes)
                }) }
        }
    }

    override fun disable() {
        stopping = true
        actionbar?.cancel()
        actionbar = null
        sessions.keys.toList().forEach { id -> Bukkit.getPlayer(id)?.let { restore(it, true) } }
        plugin.server.servicesManager.unregister(this)
        recovery.clear()
        pending.clear()
        freezePending.clear()
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        actionbar?.cancel()
        actionbar = null
        updateTask()
    }

    override fun suggestions(command: String, completed: List<String>) = if (command == "freeze" && completed.isEmpty()) names() else emptyList()

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, command, "adm.mod.$command")?.let { return it }
        if (storage.health.state != "CONNECTED") return ActionResult.failure("storage.unavailable")
        if (command == "freeze") {
            val args = words(arguments)
            if (args.size != 1) return usage("/freeze <player>")
            val target = Bukkit.getPlayerExact(args[0]) ?: return ActionResult.failure("command.player-not-found", mapOf("target" to args[0]))
            return freeze(actor, target)
        }
        if (arguments.isNotBlank()) return usage("/staffmode")
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        if (player.uniqueId in pending || player.uniqueId in restoring) return ActionResult.failure("staff.busy")
        if (sessions.containsKey(player.uniqueId)) {
            restore(player, false)
            return ActionResult.success("storage.working")
        }
        if (!modules.isEnabled("vanish")) return ActionResult.failure("command.module-unavailable", mapOf("module" to "vanish"))
        inventories.closeTarget(player.uniqueId)
        player.closeInventory()
        val snapshot = StaffSnapshot.capture(player, vanish.staffLevel(player))
        val bytes = snapshot.bytes()
        val id = player.uniqueId
        pending.add(id)
        storage.submit { db -> db.update("INSERT INTO staff_snapshots(uuid,data,created) VALUES(?,?,?)", id.toString(), bytes, System.currentTimeMillis()) }
            .whenComplete { _, error -> scheduler.main(Runnable {
                if (stopping) return@Runnable
                pending.remove(id)
                if (error != null) {
                    messages.send(actor, ActionResult.failure("storage.unavailable"))
                    return@Runnable
                }
                if (!player.isOnline) return@Runnable
                sessions[id] = snapshot
                player.inventory.clear()
                player.inventory.setItem(0, tool(Material.ENDER_PEARL, "random", "Random teleport"))
                player.inventory.setItem(1, tool(Material.BLAZE_ROD, "freeze", "Freeze stick"))
                player.inventory.setItem(2, tool(Material.PAPER, "inspect", "Inspect"))
                player.inventory.setItem(3, tool(Material.CHEST, "invsee", "Inventory inspect"))
                player.gameMode = GameMode.CREATIVE
                player.allowFlight = true
                player.isFlying = true
                vanish.staffActivate(player)
                audit(actor, player, "STAFFMODE_ENTER")
                messages.send(actor, ActionResult.success("staff.toggle", mapOf("tool" to "staff mode", "state" to "true")))
            }) }
        permissions.record(actor, command)
        return ActionResult.success("storage.working")
    }

    private fun tool(material: Material, action: String, name: String) = ItemStack(material).also { item ->
        item.editMeta { meta ->
            meta.displayName(Component.text(name))
            meta.persistentDataContainer.set(toolKey, PersistentDataType.STRING, action)
        }
    }

    private fun restore(player: Player, quitting: Boolean) {
        val id = player.uniqueId
        val snapshot = sessions[id] ?: return
        if (!restoring.add(id)) return
        player.closeInventory()
        try {
            playerTools.releaseSnapshotState(id)
            if (!snapshot.restore(player)) {
                restoring.remove(id)
                player.sendMessage(messages.render(ActionResult.failure("staff.restore-failed")))
                return
            }
            if (!quitting) vanish.staffRestore(player, snapshot.vanishLevel)
            player.canPickupItems = snapshot.pickup
            player.saveData()
            storage.submit { it.update("DELETE FROM staff_snapshots WHERE uuid=?", id.toString()) }.whenComplete { _, error ->
                scheduler.main(Runnable {
                    if (sessions[id] !== snapshot) return@Runnable
                    if (error == null) {
                        sessions.remove(id)
                        restoring.remove(id)
                        audit(CommandActor.from(player), player, "STAFFMODE_EXIT")
                        if (player.isOnline && !quitting) player.sendMessage(messages.render(ActionResult.success("staff.toggle", mapOf("tool" to "staff mode", "state" to "false"))))
                    } else if (player.isOnline && !quitting) {
                        player.kick(messages.render(ActionResult.failure("staff.restore-failed")))
                    }
                })
            }
        } catch (failure: Throwable) {
            restoring.remove(id)
            plugin.logger.log(java.util.logging.Level.SEVERE, "Staff snapshot restore failed for $id; snapshot retained", failure)
            if (!quitting) player.kick(messages.render(ActionResult.failure("staff.restore-failed")))
        }
    }

    private fun recover(player: Player, bytes: ByteArray) {
        try {
            restoring.remove(player.uniqueId)
            sessions[player.uniqueId] = StaffSnapshot.decode(bytes)
            restore(player, false)
        } catch (failure: Throwable) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "Staff snapshot unreadable; retained for ${player.uniqueId}", failure)
            player.kick(messages.render(ActionResult.failure("staff.restore-failed")))
        }
    }

    private fun freeze(actor: CommandActor, target: Player): ActionResult {
        if (target.hasPermission("adm.bypass.freeze")) return ActionResult.failure("staff.freeze-bypass")
        hierarchy.check(actor, target)?.let { return it }
        val id = target.uniqueId
        if (!freezePending.add(id)) return ActionResult.failure("staff.busy")
        val enabled = id !in frozen
        val staff = actor.playerId()?.toString() ?: "CONSOLE"
        storage.submit { db -> if (enabled) db.update("INSERT OR IGNORE INTO frozen(uuid,staff,created) VALUES(?,?,?)", id.toString(), staff, System.currentTimeMillis())
            else db.update("DELETE FROM frozen WHERE uuid=?", id.toString()) }.whenComplete { _, error -> scheduler.main(Runnable {
            freezePending.remove(id)
            if (error != null) {
                messages.send(actor, ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            if (enabled) frozen.add(id) else frozen.remove(id)
            if (enabled && target.isOnline) announceFreeze(target)
            if (!enabled && target.isOnline) target.sendActionBar(Component.empty())
            updateTask()
            audit(actor, target, if (enabled) "FREEZE" else "UNFREEZE")
            messages.send(actor, ActionResult.success("staff.frozen", mapOf("target" to target.name, "state" to enabled.toString())))
        }) }
        return ActionResult.success("storage.working")
    }

    private fun announceFreeze(player: Player) {
        player.leaveVehicle()
        player.showTitle(Title.title(messages.render(ActionResult.success("staff.freeze-title")), messages.render(ActionResult.success("staff.freeze-subtitle"))))
        player.sendActionBar(messages.render(ActionResult.success("staff.freeze-actionbar")))
    }

    private fun isFrozen(player: Player) = player.uniqueId in frozen && !player.hasPermission("adm.bypass.freeze")
    private fun restricted(player: Player) = player.uniqueId in pending || player.uniqueId in restoring
    private fun staff(player: Player) = sessions.containsKey(player.uniqueId) || restricted(player)

    private fun updateTask() {
        val online = Bukkit.getOnlinePlayers().any(::isFrozen)
        if (!online || stopping) {
            actionbar?.cancel()
            actionbar = null
            return
        }
        if (actionbar != null) return
        val interval = settings.current().integer("freeze.actionbar-seconds", 5).coerceAtLeast(5) * 20L
        actionbar = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val targets = Bukkit.getOnlinePlayers().filter(::isFrozen)
            if (targets.isEmpty()) {
                actionbar?.cancel()
                actionbar = null
            }
            else targets.forEach { it.sendActionBar(messages.render(ActionResult.success("staff.freeze-actionbar"))) }
        }, interval, interval)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun prelogin(event: AsyncPlayerPreLoginEvent) {
        val id = event.uniqueId
        try {
            val data = storage.submit { db ->
                db.query("SELECT uuid FROM frozen WHERE uuid=?", id.toString()) { it.getString(1) }.isNotEmpty() to
                    db.query("SELECT data FROM staff_snapshots WHERE uuid=?", id.toString()) { it.getBytes(1) }.firstOrNull()
            }.get(8, TimeUnit.SECONDS)
            if (data.first) frozen.add(id) else frozen.remove(id)
            recovery.remove(id)
            data.second?.let { recovery[id] = it }
        } catch (_: Exception) {
            if (settings.current().loginFallback() == "deny" || sessions.containsKey(id) || recovery.containsKey(id)) event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.render(ActionResult.failure("storage.login-denied")))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun rejected(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) recovery.remove(event.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun join(event: PlayerJoinEvent) {
        recovery.remove(event.player.uniqueId)?.let { recover(event.player, it) }
        if (isFrozen(event.player)) announceFreeze(event.player)
        updateTask()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun quit(event: PlayerQuitEvent) {
        val player = event.player
        restore(player, true)
        pending.remove(player.uniqueId)
        if (isFrozen(player)) {
            val action = settings.current().value("freeze.quit-action") as? String ?: "notify"
            if (action == "notify") Bukkit.getOnlinePlayers().filter { it.hasPermission("adm.mod.notify") }.forEach {
                it.sendMessage(messages.render(ActionResult.success("staff.freeze-quit", mapOf("target" to player.name))))
            } else if (action.startsWith("command:")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.removePrefix("command:").trim().replace("{player}", player.name))
        }
        if (Bukkit.getOnlinePlayers().none { it.uniqueId != player.uniqueId && isFrozen(it) }) {
            actionbar?.cancel()
            actionbar = null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        if (!isFrozen(event.player) && !restricted(event.player)) return
        val to = event.to ?: return
        if (event.from.world != to.world || event.from.blockX != to.blockX || event.from.blockY != to.blockY || event.from.blockZ != to.blockZ)
            event.to = event.from.clone().also {
                it.yaw = to.yaw
                it.pitch = to.pitch
            }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun teleport(event: PlayerTeleportEvent) {
        if (isFrozen(event.player) && event.player.uniqueId !in restoring) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun command(event: PlayerCommandPreprocessEvent) {
        val label = event.message.substringBefore(' ').removePrefix("/").lowercase()
        val allowed = settings.current().value("freeze.command-whitelist") as? List<*> ?: listOf("msg", "tell", "r")
        if (restricted(event.player) || isFrozen(event.player) && label !in allowed) {
            event.isCancelled = true
            event.player.sendMessage(messages.render(ActionResult.failure("staff.command-blocked")))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
        if (isFrozen(event.player) || restricted(event.player)) {
            event.isCancelled = true
            return
        }
        if (!staff(event.player)) return
        event.isCancelled = true
        if (!event.action.name.startsWith("RIGHT_CLICK") || event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val action = event.item?.itemMeta?.persistentDataContainer?.get(toolKey, PersistentDataType.STRING)
        if (action == "random" && event.player.hasPermission("adm.mod.tp")) {
            val target = Bukkit.getOnlinePlayers().filter { it.uniqueId != event.player.uniqueId && event.player.canSee(it) }.randomOrNull() ?: return
            event.player.teleportAsync(target.location)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun entity(event: PlayerInteractEntityEvent) {
        if (isFrozen(event.player) || restricted(event.player)) {
            event.isCancelled = true
            return
        }
        if (!staff(event.player)) return
        event.isCancelled = true
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val target = event.rightClicked as? Player ?: return
        val actor = CommandActor.from(event.player)
        val action = event.player.inventory.itemInMainHand.itemMeta?.persistentDataContainer?.get(toolKey, PersistentDataType.STRING)
        val result = when (action) {
            "freeze" -> if (actor.hasPermission("adm.mod.freeze")) freeze(actor, target) else ActionResult.failure("command.no-permission")
            "inspect" -> information.execute(actor, "whois", target.name)
            "invsee" -> inventories.execute(actor, "invsee", target.name)
            else -> return
        }
        messages.send(actor, result)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun entityAt(event: PlayerInteractAtEntityEvent) {
        if (isFrozen(event.player) || restricted(event.player) || staff(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST) fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (staff(player) || isFrozen(player)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun drag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (staff(player) || isFrozen(player)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun drop(event: PlayerDropItemEvent) {
        if (staff(event.player) || isFrozen(event.player)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun swap(event: PlayerSwapHandItemsEvent) {
        if (staff(event.player) || isFrozen(event.player)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun pickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (staff(player) || isFrozen(player)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun damage(event: EntityDamageEvent) {
        val victim = event.entity as? Player
        val attacker = (event as? org.bukkit.event.entity.EntityDamageByEntityEvent)?.damager as? Player
        if (victim != null && staff(victim) || attacker != null && (staff(attacker) || isFrozen(attacker))) event.isCancelled = true
    }

    private fun audit(actor: CommandActor, target: Player, action: String) {
        plugin.server.servicesManager.load(AuditSink::class.java)?.record(AuditEvent(Instant.now(), actor.playerId() ?: UUID(0, 0),
            actor.name(), target.uniqueId, target.name, "STAFF", action, mapOf("source" to "COMMAND")))
    }
}