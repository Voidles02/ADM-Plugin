package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/** Stateless information actions resolve gameplay objects on the main thread only. */
class InformationService(
    plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
    private val config: ConfigService
) : ServiceSupport(plugin, modules, permissions) {
    override val id = "information"
    override val commands = listOf("near", "ping", "list", "whois", "seen").map { CommandSpec(it) }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        val tier = if (command == "whois" || command == "seen") "admin" else "mod"
        check(actor, command, "adm.$tier.$command")?.let { return it }
        val args = words(arguments)
        when (command) {
            "whois", "seen" -> {
                if (args.size != 1) return usage("/$command <player>")
                val input = args.single()
                val target = Bukkit.getPlayerExact(input) ?: Bukkit.getOfflinePlayerIfCached(input)
                    ?: runCatching { Bukkit.getOfflinePlayer(UUID.fromString(input)) }.getOrNull()
                if (target == null) {
                    if (storedInformation(actor, input, command)) return ActionResult.success("storage.working")
                    return ActionResult.failure("command.player-not-found", mapOf("target" to input))
                }
                storedInformation(actor, target.uniqueId.toString())
                val values = mutableMapOf("target" to (target.name ?: target.uniqueId.toString()),
                    "first" to timestamp(target.firstPlayed), "last" to timestamp(target.lastSeen))
                var key = "information.seen"
                if (command == "whois") {
                    val online = target.player
                    key = "information.whois-offline"
                    if (online != null) {
                        val location = online.location
                        val seconds = online.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong() / 20
                        values.putAll(mapOf("ping" to online.ping.toString(), "gamemode" to online.gameMode.name.lowercase(),
                            "location" to "${location.world?.name ?: "unavailable"} ${location.blockX}, ${location.blockY}, ${location.blockZ}",
                            "playtime" to "${seconds / 3600}h ${seconds / 60 % 60}m ${seconds % 60}s"))
                        key = "information.whois-online"
                        if (actor.hasPermission("adm.admin.whois.ip")) {
                            values["ip"] = online.address?.address?.hostAddress ?: "unavailable"
                            key = "information.whois-online-ip"
                        }
                    }
                }
                permissions.record(actor, command)
                plugin.logger.info("${actor.name()} used /$command ${values["target"]}")
                return ActionResult.success(key, values)
            }
            "list" -> {
                if (args.isNotEmpty()) return usage("/list")
                val viewer = actor.playerId()?.let(Bukkit::getPlayer)
                val players = Bukkit.getOnlinePlayers().filter { viewer == null || viewer.canSee(it) }.map { it.name }.sorted()
                return done(actor, command, "information.list", mapOf("count" to players.size.toString(),
                    "players" to players.joinToString(", ")))
            }
            "ping" -> {
                if (args.size > 1) return usage("/ping [player]")
                if (args.isEmpty() && actor.isConsole) return ActionResult.failure("command.player-only")
                val target = if (args.isEmpty()) actor.playerId()?.let(Bukkit::getPlayer) else Bukkit.getPlayerExact(args[0])
                if (target == null) return ActionResult.failure("command.player-not-found", mapOf("target" to (args.firstOrNull() ?: actor.name())))
                return done(actor, command, "information.ping", mapOf("target" to target.name, "ping" to target.ping.toString()))
            }
            "near" -> {
                if (args.size > 1) return usage("/near [radius]")
                if (actor.isConsole) return ActionResult.failure("command.player-only")
                val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
                val snapshot = config.current()
                val max = snapshot.integer("near.max-radius", 1000)
                val radius = if (args.isEmpty()) snapshot.integer("near.default-radius", 100) else
                    args[0].toIntOrNull()?.takeIf { it in 1..max }
                        ?: return ActionResult.failure("information.radius", mapOf("max" to max.toString()))
                val origin = player.location
                val nearby = player.world.players.asSequence().filter { it.uniqueId != player.uniqueId && player.canSee(it) }
                    .map { it.name to it.location.distanceSquared(origin) }
                    .filter { it.second <= radius.toDouble() * radius }
                    .sortedBy { it.second }.toList()
                return done(actor, command, "information.near", mapOf("radius" to radius.toString(),
                    "count" to nearby.size.toString(), "players" to nearby.joinToString(", ") {
                        "${it.first} (${kotlin.math.sqrt(it.second).roundToInt()}m)"
                    }))
            }
        }
        return usage("/$command")
    }

    private fun timestamp(millis: Long): String = if (millis <= 0) "unavailable" else Instant.ofEpochMilli(millis).toString()

    private fun storedInformation(actor: CommandActor, input: String, resolvePaper: String? = null): Boolean {
        val backing = plugin.server.servicesManager.load(com.tecnor.adm.api.Storage::class.java) ?: return false
        val storage = com.tecnor.adm.api.ScopedStorage(backing, id)
        if (storage.health.state != "CONNECTED") return false
        val showIp = actor.hasPermission("adm.admin.whois.ip")
        storage.resolve(input).thenCompose { target -> storage.submit { db ->
            val names = if (target == null) emptyList() else db.prepareStatement("SELECT name FROM names WHERE uuid=? ORDER BY first_seen").use { statement ->
                statement.setString(1, target.id.toString())
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            target to names
        } }.whenComplete { result, error -> com.tecnor.adm.core.SchedulerHelper(plugin).main(Runnable {
            val messages = com.tecnor.adm.message.MessageService(config, plugin.logger)
            if (error != null) {
                messages.send(actor, ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            val target = result.first
            if (target == null) {
                if (resolvePaper != null) messages.send(actor, ActionResult.failure("command.player-not-found", mapOf("target" to input)))
                return@Runnable
            }
            if (resolvePaper != null) {
                val values = mapOf("target" to target.name, "first" to timestamp(target.firstLogin),
                    "last" to timestamp(target.lastLogin))
                val response = if (resolvePaper == "seen") {
                    ActionResult.success("information.seen", values)
                } else if (showIp) {
                    ActionResult.success("information.whois-stored-ip", values + ("ip" to target.ip))
                } else {
                    ActionResult.success("information.whois-offline", values)
                }
                permissions.record(actor, resolvePaper)
                plugin.logger.info("${actor.name()} used /$resolvePaper ${target.name}")
                messages.send(actor, response)
                return@Runnable
            }
            val allowedIp = showIp && actor.hasPermission("adm.admin.whois.ip")
            messages.send(actor, ActionResult.success(
                if (allowedIp) "information.stored-ip" else "information.stored", mapOf("target" to target.name,
                    "first" to timestamp(target.firstLogin), "last" to timestamp(target.lastLogin),
                    "names" to result.second.joinToString(", "), "ip" to if (allowedIp) target.ip else "hidden")))
        }) }
        return true
    }

    override fun suggestions(command: String, completed: List<String>) =
        if (command in listOf("ping", "whois", "seen") && completed.isEmpty()) names() else emptyList()

    override fun disable() = Unit
}