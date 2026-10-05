package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Position caches contain plain immutable data. All world access is main-thread only; chunks load via teleportAsync. */
class TeleportService(
    plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
    private val messages: MessageService, private val scheduler: SchedulerHelper
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "teleport"
    override val commands = listOf("tp", "tphere", "tpall", "tppos", "back", "top").map { CommandSpec(it) }
    private data class Position(val world: UUID, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
        fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }
    }
    private val back = ConcurrentHashMap<UUID, Position>()
    private val pending = ConcurrentHashMap<UUID, UUID>()
    fun pendingCount() = pending.size
    private val unsafe = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.FIRE, Material.SOUL_FIRE, Material.LAVA, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH)

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        val node = "adm.${if (command in listOf("tpall", "tppos")) "admin" else "mod"}.$command"
        check(actor, command, node)?.let { return it }
        val args = words(arguments)
        if (actor.isConsole) return ActionResult.failure("command.player-only")
        val player = Bukkit.getPlayer(actor.playerId()) ?: return ActionResult.failure("command.player-only")
        when (command) {
            "tp", "tphere" -> {
                if (args.size != 1) return usage("/$command <player>")
                val target = Bukkit.getPlayerExact(args[0]) ?: return ActionResult.failure("command.player-not-found", mapOf("target" to args[0]))
                if (target.uniqueId == player.uniqueId) return ActionResult.failure("teleport.same-player")
                return if (command == "tp") request(actor, command, player, target.location, true)
                    else request(actor, command, target, player.location, true)
            }
            "tpall" -> {
                if (args.isNotEmpty()) return usage("/tpall")
                val destination = player.location
                var count = 0
                for (target in Bukkit.getOnlinePlayers()) {
                    if (target.uniqueId != player.uniqueId && !pending.containsKey(target.uniqueId)) {
                        val result = request(actor, command, target, destination, false)
                        if (result.success()) count++
                    }
                }
                return done(actor, command, "teleport.all", mapOf("count" to count.toString()))
            }
            "tppos" -> {
                if (args.size !in 3..4) return usage("/tppos <x> <y> <z> [world]")
                val coords = args.take(3).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
                if (coords.any { it == null }) return usage("/tppos <x> <y> <z> [world]")
                val world = if (args.size == 4) Bukkit.getWorld(args[3]) else player.world
                if (world == null) return ActionResult.failure("teleport.world-not-found", mapOf("world" to args[3]))
                val location = Location(world, coords[0]!!, coords[1]!!, coords[2]!!, player.location.yaw, player.location.pitch)
                if (location.y < world.minHeight || location.y >= world.maxHeight ||
                    kotlin.math.abs(location.x) > 29_999_984 || kotlin.math.abs(location.z) > 29_999_984 ||
                    !world.worldBorder.isInside(location)) return ActionResult.failure("teleport.out-of-bounds")
                return request(actor, command, player, location, true)
            }
            "back" -> {
                if (args.isNotEmpty()) return usage("/back")
                val destination = back[player.uniqueId]?.location() ?: return ActionResult.failure("teleport.no-back")
                return request(actor, command, player, destination, true)
            }
            "top" -> {
                if (args.isNotEmpty()) return usage("/top")
                val location = player.location
                val world = player.world
                for (y in world.getHighestBlockYAt(location.blockX, location.blockZ) downTo world.minHeight) {
                    if (y + 2 >= world.maxHeight) continue
                    val floor = world.getBlockAt(location.blockX, y, location.blockZ)
                    if (!floor.type.isSolid || floor.isLiquid || floor.type in unsafe) continue
                    if (floor.boundingBox.maxY > y + 1.0) continue
                    val feet = world.getBlockAt(location.blockX, y + 1, location.blockZ)
                    val head = world.getBlockAt(location.blockX, y + 2, location.blockZ)
                    if (!feet.isPassable || !head.isPassable || feet.isLiquid || head.isLiquid || feet.type in unsafe || head.type in unsafe) continue
                    return request(actor, command, player, Location(world, location.blockX + 0.5, y + 1.0,
                        location.blockZ + 0.5, location.yaw, location.pitch), true)
                }
                return ActionResult.failure("teleport.no-safe-top")
            }
        }
        return usage("/$command")
    }

    private fun position(location: Location) = Position(location.world!!.uid, location.x, location.y, location.z, location.yaw, location.pitch)

    private fun request(actor: CommandActor, command: String, player: Player, destination: Location, notify: Boolean): ActionResult {
        val id = player.uniqueId
        val token = UUID.randomUUID()
        if (pending.putIfAbsent(id, token) != null) return ActionResult.failure("teleport.busy")
        val original = position(player.location)
        val targetName = player.name
        try {
            player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete { success, failure ->
                scheduler.main(Runnable {
                    if (pending.remove(id, token) && modules.isEnabled(this.id) && Bukkit.getPlayer(id) != null) {
                        try {
                            if (failure == null && success == true) back[id] = original
                            if (notify) messages.send(actor, if (failure == null && success == true)
                                ActionResult.success("teleport.success", mapOf("target" to targetName)) else ActionResult.failure("teleport.failed"))
                        } catch (error: Throwable) { modules.fail(this.id, error) }
                    }
                })
            }
        } catch (failure: Throwable) {
            pending.remove(id, token)
            throw failure
        }
        return done(actor, command, "teleport.started", mapOf("target" to targetName))
    }

    override fun suggestions(command: String, completed: List<String>) =
        if (command in listOf("tp", "tphere") && completed.isEmpty()) names() else emptyList()

    @EventHandler
    fun death(event: PlayerDeathEvent) {
        try { back[event.entity.uniqueId] = position(event.entity.location) }
        catch (failure: Throwable) { modules.fail(id, failure) }
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        back.remove(event.player.uniqueId)
        pending.remove(event.player.uniqueId)
    }

    override fun disable() {
        back.clear()
        pending.clear()
    }
}