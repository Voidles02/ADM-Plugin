package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.AuditEvent
import com.tecnor.adm.api.AuditSink
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.api.EnderChestLease
import com.tecnor.adm.api.EnderChestProvider
import com.tecnor.adm.api.EnderChestProviderRegistry
import com.tecnor.adm.api.ScopedStorage
import com.tecnor.adm.api.Storage
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.inventory.InvseeView
import com.tecnor.adm.inventory.OfflineInventoryStore
import com.tecnor.adm.inventory.VanillaEnderChestProvider
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.SettingsSnapshot
import com.tecnor.adm.staff.StaffToolsService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.time.Instant
import java.util.HexFormat
import java.security.MessageDigest
import java.util.UUID

class InventoryToolsService(
    plugin: JavaPlugin,
    modules: ModuleManager,
    permissions: PermissionService,
    private val messages: MessageService,
    private val providers: EnderChestProviderRegistry
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "inventory"
    override val commands = listOf(CommandSpec("endersee"), CommandSpec("enderedit"), CommandSpec("invsee"))

    private data class Session(
        val staff: Player,
        val target: UUID,
        val targetName: String,
        val inventory: Inventory,
        val editable: Boolean,
        val provider: EnderChestProvider?,
        val lease: EnderChestLease?,
        val kind: String = if (provider != null) "ender-chest" else "inventory",
        val editPermission: String = if (provider != null) "adm.admin.enderedit" else "adm.admin.invsee.edit",
        val selfEdit: Boolean = false,
        val offlineSnapshot: OfflineInventoryStore.Snapshot? = null,
        val invsee: InvseeView? = null,
        var previous: List<String> = emptyList(),
        val source: String = com.tecnor.adm.api.ActionOrigin.current(),
        var flushTask: BukkitTask? = null
    )

    private val sessions = mutableMapOf<UUID, Session>()
    private val locks = mutableMapOf<UUID, UUID>()
    private val offlineInventories = OfflineInventoryStore(plugin)
    private val scheduler = SchedulerHelper(plugin)
    private var refreshTask: BukkitTask? = null

    fun isLocked(target: UUID) = target in locks

    override fun enable() {
        providers.register(plugin, VanillaEnderChestProvider())
        providers.onRemoval { provider ->
            sessions.values.filter { it.provider === provider }.toList().forEach { close(it) }
        }
        plugin.server.servicesManager.register(EnderChestProviderRegistry::class.java, providers, plugin, ServicePriority.Normal)
    }

    private fun syncInvseeRefreshTask() {
        val hasViews = sessions.values.any { it.invsee != null }
        if (hasViews && refreshTask == null) {
            refreshTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                sessions.values.forEach { it.invsee?.refresh() }
            }, 10L, 10L)
        } else if (!hasViews) {
            refreshTask?.cancel()
            refreshTask = null
        }
    }

    override fun disable() {
        try {
            closeAll()
            plugin.server.onlinePlayers.toList().forEach(offlineInventories::capture)
        } finally {
            refreshTask?.cancel()
            providers.unregisterAll(plugin)
            plugin.server.servicesManager.unregister(EnderChestProviderRegistry::class.java, providers)
        }
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        closeAll()
    }

    override fun suggestions(command: String, completed: List<String>) = if (completed.isEmpty()) names() else emptyList()

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, command, "adm.admin.$command")?.let { return it }
        val args = words(arguments)
        if (args.size != 1) return usage("/$command <player>")
        val staff = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        val target = findTarget(args.single()) ?: return if (resolveStoredTarget(actor, command, args.single())) {
            ActionResult.success("storage.working")
        } else ActionResult.failure("command.player-not-found", mapOf("target" to args.single()))
        if (command == "invsee") return openInvsee(actor, staff, target)
        val editable = command == "enderedit"
        close(sessions[staff.uniqueId])
        if (locks[target.uniqueId]?.let { it != staff.uniqueId } == true) return ActionResult.failure("inventory.locked")
        val provider = if (target.isOnline) providers.select(target.uniqueId) else
            providers.providers().firstOrNull { it.supportsOffline && it.supports(target.uniqueId) }
        if (!target.isOnline && provider == null) return openOfflineEnder(actor, staff, target, editable)
        if (provider == null) return ActionResult.failure("inventory.provider-unavailable")
        if (editable) locks[target.uniqueId] = staff.uniqueId
        var lease: EnderChestLease? = null
        try {
            val acquired = provider.open(target.uniqueId, editable)
            lease = acquired
            require(acquired.inventory.size in 9..54 && acquired.inventory.size % 9 == 0) { "Provider inventory must be chest-shaped" }
            val targetName = target.name ?: target.uniqueId.toString()
            val inventory = if (editable) acquired.inventory else Bukkit.createInventory(null, acquired.inventory.size,
                messages.render(ActionResult.success("inventory.title", mapOf("target" to targetName, "type" to "Ender Chest")))).also {
                it.contents = acquired.inventory.contents.map { item -> item?.clone() }.toTypedArray()
            }
            if (!editable) {
                acquired.close()
                lease = null
            } else {
                inventory.viewers.toList().forEach { it.closeInventory() }
                target.player?.let { player ->
                    if (player.openInventory.topInventory == player.enderChest) player.closeInventory()
                }
            }
            staff.closeInventory()
            val session = Session(staff, target.uniqueId, targetName, inventory, editable, provider, lease)
            if (editable) session.previous = contents(session)
            sessions[staff.uniqueId] = session
            staff.openInventory(inventory)
            if (staff.openInventory.topInventory != inventory) {
                close(session)
                return ActionResult.failure("inventory.open-failed")
            }
            audit(session, "access", mapOf("mode" to if (editable) "edit" else "read", "provider" to provider.id))
            return done(actor, command, "inventory.opened", mapOf("target" to targetName, "mode" to if (editable) "editable" else "read-only"))
        } catch (failure: Throwable) {
            try { lease?.flush() } finally {
                try { lease?.close() } finally {
                    sessions.remove(staff.uniqueId)
                    if (locks[target.uniqueId] == staff.uniqueId) locks.remove(target.uniqueId)
                }
            }
            throw failure
        }
    }

    private fun openInvsee(actor: CommandActor, staff: Player, offline: OfflinePlayer): ActionResult {
        val target = offline.player
        if (target == null) {
            val snapshot = offlineInventories.load(offline.uniqueId)
                ?: return ActionResult.failure("inventory.offline-data-unavailable")
            val protection = plugin.server.servicesManager.load(StaffToolsService::class.java)
            val editable = actor.hasPermission("adm.admin.invsee.edit") && protection?.isProtected(staff.uniqueId) != true
            close(sessions[staff.uniqueId])
            val targetName = offline.name ?: offline.uniqueId.toString()
            val inventory = Bukkit.createInventory(null, 45, messages.render(ActionResult.success("inventory.title",
                mapOf("target" to targetName, "type" to "Inventory"))))
            snapshot.inventory.take(41).forEachIndexed { index, item -> inventory.setItem(index, item?.clone()) }
            val session = Session(staff, offline.uniqueId, targetName, inventory, editable, null, null,
                kind = "inventory", offlineSnapshot = snapshot)
            if (editable) session.previous = contents(session)
            sessions[staff.uniqueId] = session
            syncInvseeRefreshTask()
            staff.closeInventory()
            staff.openInventory(inventory)
            if (staff.openInventory.topInventory != inventory) {
                close(session)
                return ActionResult.failure("inventory.open-failed")
            }
            audit(session, "access", mapOf("mode" to if (editable) "edit" else "read-only"))
            return done(actor, "invsee", "inventory.opened", mapOf("target" to targetName,
                "mode" to if (editable) "editable" else "read-only"))
        }
        val protection = plugin.server.servicesManager.load(StaffToolsService::class.java)
        if (protection?.isProtected(target.uniqueId) == true) return ActionResult.failure("staff.inventory-locked")
        val selfEdit = target.uniqueId == staff.uniqueId
        val editable = (selfEdit || actor.hasPermission("adm.admin.invsee.edit")) &&
            protection?.isProtected(staff.uniqueId) != true
        close(sessions[staff.uniqueId])
        val view = InvseeView(staff, target, messages.render(ActionResult.success("inventory.title",
            mapOf("target" to target.name, "type" to "Inventory"))))
        view.refresh()
        staff.closeInventory()
        val session = Session(staff, target.uniqueId, target.name, view.inventory, editable, null, null,
            kind = "inventory", selfEdit = selfEdit, invsee = view)
        sessions[staff.uniqueId] = session
        syncInvseeRefreshTask()
        staff.openInventory(view.inventory)
        if (staff.openInventory.topInventory != view.inventory) {
            close(session)
            return ActionResult.failure("inventory.open-failed")
        }
        audit(session, "access", mapOf("mode" to if (editable) "edit" else "read"))
        return done(actor, "invsee", "inventory.opened", mapOf("target" to target.name,
            "mode" to if (editable) "editable" else "read-only"))
    }

    private fun openOfflineEnder(actor: CommandActor, staff: Player, target: OfflinePlayer, editable: Boolean): ActionResult {
        val snapshot = offlineInventories.load(target.uniqueId)
            ?: return ActionResult.failure("inventory.offline-data-unavailable")
        val targetName = target.name ?: target.uniqueId.toString()
        val inventory = Bukkit.createInventory(null, 27, messages.render(ActionResult.success("inventory.title",
            mapOf("target" to targetName, "type" to "Ender Chest"))))
        snapshot.enderChest.take(27).forEachIndexed { index, item -> inventory.setItem(index, item?.clone()) }
        if (editable) locks[target.uniqueId] = staff.uniqueId
        val session = Session(staff, target.uniqueId, targetName, inventory, editable, null, null,
            kind = "ender-chest", editPermission = "adm.admin.enderedit", offlineSnapshot = snapshot)
        if (editable) session.previous = contents(session)
        sessions[staff.uniqueId] = session
        staff.closeInventory()
        staff.openInventory(inventory)
        if (staff.openInventory.topInventory != inventory) {
            close(session)
            return ActionResult.failure("inventory.open-failed")
        }
        audit(session, "access", mapOf("mode" to if (editable) "edit" else "read-only", "provider" to "offline-cache"))
        return done(actor, if (editable) "enderedit" else "endersee", "inventory.opened", mapOf("target" to targetName,
            "mode" to if (editable) "editable" else "read-only"))
    }

    private fun findTarget(input: String): OfflinePlayer? = Bukkit.getPlayerExact(input)
        ?: Bukkit.getOfflinePlayerIfCached(input)
        ?: runCatching { Bukkit.getOfflinePlayer(UUID.fromString(input)) }.getOrNull()

    private fun resolveStoredTarget(actor: CommandActor, command: String, input: String): Boolean {
        val storage = plugin.server.servicesManager.load(Storage::class.java) ?: return false
        val scoped = ScopedStorage(storage, id)
        if (scoped.health.state != "CONNECTED") return false
        scoped.resolve(input).whenComplete { target, failure ->
            scheduler.main(Runnable {
                if (failure != null) {
                    messages.send(actor, ActionResult.failure("storage.unavailable"))
                } else if (target == null) {
                    messages.send(actor, ActionResult.failure("command.player-not-found", mapOf("target" to input)))
                } else {
                    messages.send(actor, execute(actor, command, target.id.toString()))
                }
            })
        }
        return true
    }

    fun closeTarget(id: UUID) {
        sessions.values.filter { it.target == id || it.staff.uniqueId == id }.toList().forEach { close(it) }
    }

    private fun closeAll() {
        var failure: Throwable? = null
        sessions.values.toList().forEach {
            try { close(it) } catch (error: Throwable) { failure = error; plugin.logger.log(java.util.logging.Level.SEVERE, "Inventory lease cleanup failed", error) }
        }
        failure?.let { throw it }
    }

    private fun close(session: Session?) {
        if (session == null || sessions[session.staff.uniqueId] !== session) return
        if (session.staff.openInventory.topInventory == session.inventory) session.staff.closeInventory()
        release(session)
    }

    private fun release(session: Session) {
        if (sessions.remove(session.staff.uniqueId) !== session) return
        session.flushTask?.cancel()
        session.flushTask = null
        syncInvseeRefreshTask()
        try {
            if (session.editable) recordChanges(session)
            if (session.offlineSnapshot != null) saveOfflineSnapshot(session) else session.lease?.flush()
        } finally {
            try { session.lease?.close() } finally {
                if (locks[session.target] == session.staff.uniqueId) locks.remove(session.target)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val session = sessions[event.player.uniqueId]
        val locked = locks.entries.firstOrNull { (target, _) ->
            sessions[locks[target]]?.inventory == event.inventory ||
                (event.player.uniqueId == target && event.inventory == Bukkit.getPlayer(target)?.enderChest)
        }
        if (locked != null && (session == null || session.inventory != event.inventory || locks[locked.key] != event.player.uniqueId)) {
            event.isCancelled = true
            event.player.sendMessage(messages.render(ActionResult.failure("inventory.locked")))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory != session.inventory) return
        if (!session.editable) event.isCancelled = true
        else if (!canEdit(session, event.whoClicked)) {
            event.isCancelled = true
            plugin.server.scheduler.runTask(plugin, Runnable { close(session) })
        } else if (!event.isCancelled) {
            if (session.invsee != null) {
                session.previous = contents(session)
                session.invsee.click(event)
                recordChanges(session)
            } else scheduleFlush(session)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory != session.inventory) return
        if (!session.editable) event.isCancelled = true
        else if (!canEdit(session, event.whoClicked)) {
            event.isCancelled = true
            plugin.server.scheduler.runTask(plugin, Runnable { close(session) })
        } else if (!event.isCancelled) {
            if (session.invsee != null) {
                val apply = session.invsee.drag(event)
                if (apply != null) plugin.server.scheduler.runTask(plugin, Runnable {
                    if (sessions[session.staff.uniqueId] === session && session.staff.openInventory.topInventory == session.inventory) {
                        session.previous = contents(session)
                        apply.run()
                        recordChanges(session)
                    }
                })
            } else scheduleFlush(session)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun auditClick(event: InventoryClickEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (!session.editable || session.provider == null || event.view.topInventory != session.inventory) return
        if (event.rawSlot in 0 until session.inventory.size || event.action in listOf(
                InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR)) {
            audit(session, "edit-action", mapOf("action" to event.action.name, "click" to event.click.name,
                "raw-slot" to event.rawSlot.toString(), "provider" to session.provider.id))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun auditDrag(event: InventoryDragEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (session.editable && session.provider != null && event.view.topInventory == session.inventory &&
            event.rawSlots.any { it in 0 until session.inventory.size }) {
            audit(session, "edit-action", mapOf("action" to "drag", "raw-slots" to event.rawSlots.joinToString(","),
                "provider" to session.provider.id))
        }
    }

    private fun scheduleFlush(session: Session) {
        if (session.flushTask != null) return
        val flush = Runnable {
            session.flushTask = null
            if (sessions[session.staff.uniqueId] === session) {
                try {
                    recordChanges(session)
                    if (session.offlineSnapshot != null) saveOfflineSnapshot(session) else session.lease?.flush()
                } catch (failure: Throwable) { modules.fail(id, failure) }
            }
        }
        session.flushTask = if (session.offlineSnapshot != null) {
            plugin.server.scheduler.runTaskLater(plugin, flush, 20L)
        } else plugin.server.scheduler.runTask(plugin, flush)
    }

    private fun contents(session: Session): List<String> {
        val source = session.invsee?.target?.inventory ?: session.inventory
        val digest = MessageDigest.getInstance("SHA-256")
        return source.contents.take(if (session.kind == "inventory") 41 else source.size).map { item ->
            if (item == null || item.type.isAir) "empty" else
                "${item.type}:${item.amount}:sha256:${HexFormat.of().formatHex(digest.digest(item.serializeAsBytes()))}"
        }
    }

    private fun recordChanges(session: Session) {
        if (!session.editable) return
        val current = contents(session)
        val changes = current.indices.filter { current[it] != session.previous.getOrNull(it) }
            .associate { "slot-$it" to "${session.previous.getOrNull(it) ?: "empty"} -> ${current[it]}" }
        if (changes.isNotEmpty()) audit(session, "edit", changes)
        session.previous = current
    }

    private fun audit(session: Session, action: String, details: Map<String, String>) {
        plugin.server.servicesManager.load(AuditSink::class.java)?.record(AuditEvent(Instant.now(), session.staff.uniqueId,
            session.staff.name, session.target, session.targetName, session.kind,
            action, details + ("source" to session.source)))
    }

    private fun canEdit(session: Session, player: org.bukkit.entity.HumanEntity) =
        player.hasPermission(session.editPermission) || (session.selfEdit && player.uniqueId == session.staff.uniqueId)

    private fun saveOfflineSnapshot(session: Session) {
        val snapshot = session.offlineSnapshot ?: return
        if (!session.editable) return
        if (session.kind == "ender-chest") {
            snapshot.enderChest = session.inventory.contents.map { it?.clone() }.toMutableList()
        } else {
            val updated = snapshot.inventory.toMutableList()
            while (updated.size < 41) updated.add(null)
            for (slot in 0 until 41) updated[slot] = session.inventory.getItem(slot)?.clone()
            snapshot.inventory = updated
        }
        offlineInventories.saveEdited(session.target, snapshot)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        if (sessions[event.player.uniqueId]?.editable == false) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        sessions[event.player.uniqueId]?.takeIf { it.inventory == event.inventory }?.let { release(it) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        sessions.values.filter { it.target == event.player.uniqueId || it.staff.uniqueId == event.player.uniqueId }
            .toList().forEach { close(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun captureOnQuit(event: PlayerQuitEvent) { offlineInventories.capture(event.player) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        closeTarget(event.player.uniqueId)
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!event.player.isOnline) return@Runnable
            try { offlineInventories.restorePending(event.player) }
            catch (failure: Throwable) {
                plugin.logger.log(java.util.logging.Level.SEVERE,
                    "Could not restore offline inventory edits for ${event.player.uniqueId}", failure)
            }
        })
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        sessions.values.filter { it.invsee != null && (it.target == event.entity.uniqueId || it.staff.uniqueId == event.entity.uniqueId) }
            .toList().forEach { close(it) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onProviderDisable(event: PluginDisableEvent) { providers.unregisterAll(event.plugin) }
}