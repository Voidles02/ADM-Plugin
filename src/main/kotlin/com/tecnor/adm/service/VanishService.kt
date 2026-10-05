package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import org.bukkit.Bukkit
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class VanishService(
    plugin: JavaPlugin,
    modules: ModuleManager,
    permissions: PermissionService,
    private val config: ConfigService,
    private val messages: MessageService
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "vanish"
    override val commands = listOf(CommandSpec("vanish", listOf("v")))
    private data class State(val level: Int, val pickup: Boolean)
    private val vanished = mutableMapOf<UUID, State>()
    private var refreshTask: BukkitTask? = null

    override fun enable() {
        refreshTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { refresh() }, 20L, 20L)
    }

    override fun disable() {
        refreshTask?.cancel()
        for ((uuid, state) in vanished) {
            val target = Bukkit.getPlayer(uuid) ?: continue
            target.canPickupItems = state.pickup
            Bukkit.getOnlinePlayers().forEach { it.showPlayer(plugin, target) }
        }
        vanished.clear()
    }

    override fun onReload(snapshot: SettingsSnapshot) { refresh() }

    override fun suggestions(command: String, completed: List<String>) = emptyList<String>()

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, "vanish", "adm.admin.vanish")?.let { return it }
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        val args = words(arguments)
        if (args.size > 1) return usage("/vanish [level]")
        if (args.isEmpty() && player.uniqueId in vanished) {
            val state = vanished.remove(player.uniqueId)!!
            player.canPickupItems = state.pickup
            Bukkit.getOnlinePlayers().forEach { it.showPlayer(plugin, player) }
            if (config.current().value("vanish.fake-join") == true) fake(player, "vanish.fake-join")
            return done(actor, "vanish", "vanish.disabled")
        }
        val maximum = level(player)
        val selected = if (args.isEmpty()) maximum else args.single().toIntOrNull()
        if (selected == null || selected < 0 || !player.hasPermission("adm.vanish.level.$selected")) {
            return ActionResult.failure("vanish.invalid-level", mapOf("max" to maximum.toString()))
        }
        val alreadyVanished = player.uniqueId in vanished
        activate(player, selected)
        if (!alreadyVanished && config.current().value("vanish.fake-quit") == true) fake(player, "vanish.fake-quit")
        return done(actor, "vanish", "vanish.enabled", mapOf("level" to selected.toString()))
    }

    private fun level(player: Player): Int = player.effectivePermissions.asSequence()
        .filter { it.value && it.permission.startsWith("adm.vanish.level.") }
        .mapNotNull { it.permission.removePrefix("adm.vanish.level.").toIntOrNull()?.takeIf { value -> value >= 0 } }
        .maxOrNull() ?: 0

    private fun activate(player: Player, selected: Int) {
        vanished[player.uniqueId] = State(selected, vanished[player.uniqueId]?.pickup ?: player.canPickupItems)
        player.canPickupItems = false
        player.world.livingEntities.forEach {
            if (it is Mob && it.target?.uniqueId == player.uniqueId) it.target = null
        }
        refresh()
    }

    fun staffLevel(player: Player): Int? = vanished[player.uniqueId]?.level

    fun staffActivate(player: Player) { activate(player, level(player)) }

    fun staffRestore(player: Player, previous: Int?) {
        if (previous != null) activate(player, previous) else {
            vanished.remove(player.uniqueId)?.let { player.canPickupItems = it.pickup }
            Bukkit.getOnlinePlayers().forEach { it.showPlayer(plugin, player) }
        }
    }

    private fun refresh() {
        if (vanished.isEmpty()) return
        val players = Bukkit.getOnlinePlayers()
        val viewerLevels = players.associate { viewer ->
            viewer.uniqueId to if (viewer.hasPermission("adm.admin.vanish.see")) level(viewer) else -1
        }
        for ((uuid, state) in vanished) {
            val target = Bukkit.getPlayer(uuid) ?: continue
            for (viewer in players) {
                if (viewer.uniqueId == target.uniqueId) continue
                if (viewerLevels.getValue(viewer.uniqueId) >= state.level) viewer.showPlayer(plugin, target)
                else viewer.hidePlayer(plugin, target)
            }
        }
    }

    private fun fake(player: Player, key: String) {
        val message = messages.render(ActionResult.success(key, mapOf("target" to player.name)))
        Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }.forEach { it.sendMessage(message) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (config.current().value("vanish.on-join") != false && player.hasPermission("adm.admin.vanish.join") &&
            player.hasPermission("adm.admin.vanish") && player.hasPermission("adm.vanish.level.${level(player)}")) {
            activate(player, level(player))
            event.joinMessage(null)
        } else {
            Bukkit.getOnlinePlayers().forEach { it.showPlayer(plugin, player) }
            refresh()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        val state = vanished.remove(event.player.uniqueId) ?: return
        event.quitMessage(null)
        event.player.canPickupItems = state.pickup
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        if (event.target?.uniqueId in vanished) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        if (event.entity.uniqueId in vanished) event.isCancelled = true
    }
}