package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Concurrent player-bound caches are mutated on the main thread and removed/restored before quit saves. */
class PlayerToolsService(
    plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
    private val hierarchy: HierarchyService
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "player-tools"
    override val commands = listOf(CommandSpec("gamemode", listOf("gm"))) +
        listOf("gmc", "gms", "gma", "gmsp", "fly", "speed", "god", "heal", "feed", "repair", "clear").map { CommandSpec(it) }
    private val gods = ConcurrentHashMap.newKeySet<UUID>()
    private data class FlightState(val allowed: Boolean, val flying: Boolean)
    private data class SpeedState(val walk: Float, val fly: Float)
    private val flights = ConcurrentHashMap<UUID, FlightState>()
    private val speeds = ConcurrentHashMap<UUID, SpeedState>()
    private val modes = mapOf("survival" to GameMode.SURVIVAL, "creative" to GameMode.CREATIVE,
        "adventure" to GameMode.ADVENTURE, "spectator" to GameMode.SPECTATOR)
    private val shortcuts = mapOf("gmc" to "creative", "gms" to "survival", "gma" to "adventure", "gmsp" to "spectator")

    fun releaseSnapshotState(id: UUID) {
        flights.remove(id)
        speeds.remove(id)
    }

    fun isGod(id: UUID) = id in gods

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        val args = words(arguments)
        val canonical = if (command in shortcuts) "gamemode" else command
        val tier = if (canonical in listOf("god", "repair", "clear")) "admin" else "mod"
        check(actor, canonical, "adm.$tier.$canonical")?.let { return it }
        if (canonical == "repair") return repair(actor, args)
        var targetName: String? = null
        var mode: GameMode? = null
        var speed = 0
        var speedType: String? = null
        when (command) {
            "gamemode" -> {
                if (args.size !in 1..2) return usage("/gamemode <survival|creative|adventure|spectator> [player]")
                mode = modes[args[0].lowercase()] ?: return usage("/gamemode <survival|creative|adventure|spectator> [player]")
                targetName = args.getOrNull(1)
            }
            in shortcuts -> {
                if (args.size > 1) return usage("/$command [player]")
                mode = modes.getValue(shortcuts.getValue(command))
                targetName = args.firstOrNull()
            }
            "speed" -> {
                if (args.size !in 1..3) return usage("/speed <0-10> [fly|walk] [player]")
                speed = args[0].toIntOrNull()?.takeIf { it in 0..10 }
                    ?: return usage("/speed <0-10> [fly|walk] [player]")
                if (args.size >= 2) {
                    if (args[1].lowercase() in listOf("fly", "walk")) {
                        speedType = args[1].lowercase()
                        targetName = args.getOrNull(2)
                    } else {
                        if (args.size == 3) return usage("/speed <0-10> [fly|walk] [player]")
                        targetName = args[1]
                    }
                }
            }
            else -> {
                if (args.size > 1) return usage("/$command [player]")
                targetName = args.firstOrNull()
            }
        }
        val target = if (targetName == null) {
            if (actor.isConsole) return ActionResult.failure("command.player-only")
            actor.playerId()?.let(Bukkit::getPlayer)
        } else Bukkit.getPlayerExact(targetName)
        if (target == null) return ActionResult.failure("command.player-not-found", mapOf("target" to (targetName ?: actor.name())))
        if (actor.playerId() != target.uniqueId) {
            permissions.check(actor, "adm.$tier.$canonical.others")?.let { return it }
            if (canonical in listOf("god", "clear", "heal", "feed")) hierarchy.check(actor, target)?.let { return it }
        }
        val values = mutableMapOf("target" to target.name)
        when (canonical) {
            "gamemode" -> {
                target.gameMode = mode!!
                values["mode"] = mode.name.lowercase()
            }
            "fly" -> {
                flights.putIfAbsent(target.uniqueId, FlightState(target.allowFlight, target.isFlying))
                val enabled = !target.allowFlight
                if (!enabled) target.isFlying = false
                target.allowFlight = enabled
                values["state"] = enabled.toString()
            }
            "speed" -> {
                speeds.putIfAbsent(target.uniqueId, SpeedState(target.walkSpeed, target.flySpeed))
                val type = speedType ?: if (target.isFlying) "fly" else "walk"
                if (type == "fly") target.flySpeed = speed / 10f else target.walkSpeed = speed / 10f
                values["type"] = type
                values["speed"] = speed.toString()
            }
            "god" -> values["state"] = (if (gods.remove(target.uniqueId)) false else gods.add(target.uniqueId)).toString()
            "heal" -> {
                if (target.isDead) return ActionResult.failure("tools.dead", mapOf("target" to target.name))
                val attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"))
                    ?: Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"))
                target.health = attribute?.let { target.getAttribute(it)?.value } ?: 20.0
                target.fireTicks = 0
            }
            "feed" -> {
                target.foodLevel = 20
                target.saturation = 20f
                target.exhaustion = 0f
            }
            "clear" -> {
                target.inventory.clear()
                target.inventory.setItemInOffHand(null)
            }
        }
        return done(actor, canonical, "tools.$canonical", values)
    }

    private fun repair(actor: CommandActor, args: List<String>): ActionResult {
        if (args.size > 1 || (args.isNotEmpty() && args[0].lowercase() !in listOf("hand", "all"))) return usage("/repair [hand|all]")
        if (actor.isConsole) return ActionResult.failure("command.player-only")
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        val all = args.firstOrNull()?.equals("all", true) == true
        if (all) permissions.check(actor, "adm.admin.repair.all")?.let { return it }
        val count = if (all) player.inventory.contents.count { repairItem(it) }
            else if (repairItem(player.inventory.itemInMainHand)) 1 else 0
        if (count == 0) return ActionResult.failure("tools.nothing-to-repair")
        return done(actor, "repair", "tools.repair", mapOf("count" to count.toString()))
    }

    private fun repairItem(item: ItemStack?): Boolean {
        val repaired = item ?: return false
        val meta = repaired.itemMeta as? Damageable ?: return false
        if (!meta.hasDamage()) return false
        meta.damage = 0
        repaired.itemMeta = meta
        return true
    }

    override fun suggestions(command: String, completed: List<String>): List<String> = when {
        command == "gamemode" -> when (completed.size) {
            0 -> modes.keys.toList()
            1 -> names()
            else -> emptyList()
        }
        command == "repair" -> if (completed.isEmpty()) listOf("hand", "all") else emptyList()
        command == "speed" -> when (completed.size) {
            0 -> (0..10).map { it.toString() }
            1 -> listOf("fly", "walk") + names()
            2 -> if (completed[1] in listOf("fly", "walk")) names() else emptyList()
            else -> emptyList()
        }
        completed.isEmpty() -> names()
        else -> emptyList()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun damage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!gods.contains(player.uniqueId)) return
        try { event.isCancelled = true } catch (failure: Throwable) { modules.fail(id, failure) }
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        try { restore(event.player) } catch (failure: Throwable) { modules.fail(id, failure) }
    }

    private fun restore(player: Player) {
        gods.remove(player.uniqueId)
        flights.remove(player.uniqueId)?.let {
            val allowed = it.allowed || player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR
            if (!allowed) player.isFlying = false
            player.allowFlight = allowed
            if (allowed) player.isFlying = it.flying
        }
        speeds.remove(player.uniqueId)?.let {
            player.walkSpeed = it.walk
            player.flySpeed = it.fly
        }
    }

    override fun disable() {
        Bukkit.getOnlinePlayers().forEach { restore(it) }
        gods.clear()
        flights.clear()
        speeds.clear()
    }
}