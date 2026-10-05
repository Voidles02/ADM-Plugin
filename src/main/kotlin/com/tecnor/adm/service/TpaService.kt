package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.integration.WorldGuardCompatibility
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import kotlin.math.ceil

class TpaService(
    plugin: JavaPlugin,
    modules: ModuleManager,
    permissions: PermissionService,
    private val settings: ConfigService,
    private val messages: MessageService,
    private val scheduler: SchedulerHelper,
    private val worldGuard: WorldGuardCompatibility? = null
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "tpa"
    override val commands = listOf(
        CommandSpec("tpa", listOf("admtpa")),
        CommandSpec("tpaccept", listOf("admtpaccept")),
        CommandSpec("tpdeny", listOf("admtpdeny"))
    )

    private data class Request(val requester: UUID, val requesterName: String, val expiresAt: Long)

    private val requestsByTarget = mutableMapOf<UUID, Request>()
    private val targetByRequester = mutableMapOf<UUID, UUID>()
    private val cooldowns = mutableMapOf<UUID, Long>()
    private val pendingTeleports = mutableSetOf<UUID>()
    private val protectedUntil = mutableMapOf<UUID, Long>()
    private var cooldownSeconds = settings.current().integer("tpa.cooldown-seconds", 5)
    private var protectionSeconds = settings.current().integer("tpa.protection-seconds", 15)
    private var active = false

    override fun enable() {
        active = true
    }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        val args = words(arguments)
        if (command == "tpa" && args.firstOrNull()?.lowercase() in listOf("cooldown", "protection")) {
            return setDuration(actor, args)
        }
        check(actor, command, "adm.tpa.use")?.let { return it }
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        return when (command) {
            "tpa" -> sendRequest(actor, player, args)
            "tpaccept" -> accept(actor, player, args)
            "tpdeny" -> deny(actor, player, args)
            else -> usage("/tpa <player> | /tpaccept [player] | /tpdeny [player]")
        }
    }

    private fun sendRequest(actor: CommandActor, requester: Player, args: List<String>): ActionResult {
        if (args.size != 1) return usage("/tpa <player>")
        val target = Bukkit.getPlayerExact(args.single())
            ?: return ActionResult.failure("command.player-not-found", mapOf("target" to args.single()))
        if (target.uniqueId == requester.uniqueId) return ActionResult.failure("tpa.self")
        if (requester.uniqueId in pendingTeleports) return ActionResult.failure("teleport.busy")

        val now = System.nanoTime()
        val remaining = cooldownSeconds * 1_000_000_000L - (now - (cooldowns[requester.uniqueId] ?: 0L))
        if (cooldownSeconds > 0 && cooldowns.containsKey(requester.uniqueId) && remaining > 0) {
            return ActionResult.failure("tpa.cooldown", mapOf("seconds" to ceil(remaining / 1_000_000_000.0).toLong().toString()))
        }

        targetByRequester[requester.uniqueId]?.let { previousTarget ->
            val previous = requestsByTarget[previousTarget]
            if (previous != null && previous.expiresAt - now > 0) return ActionResult.failure("tpa.request-pending")
            removeRequest(requester.uniqueId, previousTarget)
        }
        val existing = requestsByTarget[target.uniqueId]
        if (existing != null && existing.expiresAt - now > 0) {
            return ActionResult.failure("tpa.target-pending", mapOf("target" to target.name))
        }
        existing?.let { removeRequest(it.requester, target.uniqueId) }

        requestsByTarget[target.uniqueId] = Request(requester.uniqueId, requester.name, now + REQUEST_TIMEOUT_NANOS)
        targetByRequester[requester.uniqueId] = target.uniqueId
        if (cooldownSeconds > 0) cooldowns[requester.uniqueId] = now
        messages.send(CommandActor.from(target), ActionResult.success("tpa.request-received", mapOf("requester" to requester.name)))
        return done(actor, "tpa", "tpa.request-sent", mapOf("target" to target.name))
    }

    private fun accept(actor: CommandActor, target: Player, args: List<String>): ActionResult {
        if (args.size > 1) return usage("/tpaccept [player]")
        val request = currentRequest(target.uniqueId)
            ?: return ActionResult.failure("tpa.no-request")
        if (args.isNotEmpty() && !request.requesterName.equals(args.single(), ignoreCase = true)) {
            return ActionResult.failure("tpa.request-not-found", mapOf("requester" to args.single()))
        }
        val requester = Bukkit.getPlayer(request.requester)
        removeRequest(request.requester, target.uniqueId)
        if (requester == null) return ActionResult.failure("tpa.requester-offline")
        if (!pendingTeleports.add(requester.uniqueId)) return ActionResult.failure("teleport.busy")

        val destination = target.location.clone()
        if (worldGuard?.allowsTeleport(requester, destination) == false) {
            messages.send(CommandActor.from(requester), ActionResult.failure("tpa.region-denied"))
            return ActionResult.failure("tpa.region-denied")
        }
        val requesterId = requester.uniqueId
        val requesterName = requester.name
        val targetName = target.name
        messages.send(CommandActor.from(requester), ActionResult.success("tpa.teleport-started", mapOf("target" to targetName)))
        try {
            requester.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete { success, failure ->
                scheduler.main(Runnable {
                    pendingTeleports.remove(requesterId)
                    if (!active) return@Runnable
                    val onlineRequester = Bukkit.getPlayer(requesterId) ?: return@Runnable
                    val completed = failure == null && success == true
                    if (completed && protectionSeconds > 0) {
                        protectedUntil[requesterId] = System.nanoTime() + protectionSeconds * 1_000_000_000L
                    }
                    val result = if (completed) ActionResult.success("tpa.teleport-success", mapOf("target" to targetName))
                        else ActionResult.failure("tpa.teleport-failed", mapOf("target" to targetName))
                    messages.send(CommandActor.from(onlineRequester), result)
                    Bukkit.getPlayer(target.uniqueId)?.let {
                        messages.send(CommandActor.from(it), ActionResult.success(
                            if (completed) "tpa.teleport-complete" else "tpa.teleport-failed-target",
                            mapOf("requester" to requesterName)
                        ))
                    }
                })
            }
        } catch (failure: Throwable) {
            pendingTeleports.remove(requesterId)
            throw failure
        }
        return done(actor, "tpaccept", "tpa.request-accepted", mapOf("requester" to requesterName))
    }

    private fun deny(actor: CommandActor, target: Player, args: List<String>): ActionResult {
        if (args.size > 1) return usage("/tpdeny [player]")
        val request = currentRequest(target.uniqueId) ?: return ActionResult.failure("tpa.no-request")
        if (args.isNotEmpty() && !request.requesterName.equals(args.single(), ignoreCase = true)) {
            return ActionResult.failure("tpa.request-not-found", mapOf("requester" to args.single()))
        }
        removeRequest(request.requester, target.uniqueId)
        Bukkit.getPlayer(request.requester)?.let {
            messages.send(CommandActor.from(it), ActionResult.failure("tpa.request-denied", mapOf("target" to target.name)))
        }
        return done(actor, "tpdeny", "tpa.request-denied-target", mapOf("requester" to request.requesterName))
    }

    private fun currentRequest(target: UUID): Request? {
        val request = requestsByTarget[target] ?: return null
        if (request.expiresAt - System.nanoTime() > 0 && Bukkit.getPlayer(request.requester) != null) return request
        removeRequest(request.requester, target)
        return null
    }

    private fun removeRequest(requester: UUID, target: UUID) {
        if (requestsByTarget[target]?.requester == requester) requestsByTarget.remove(target)
        if (targetByRequester[requester] == target) targetByRequester.remove(requester)
    }

    private fun setDuration(actor: CommandActor, args: List<String>): ActionResult {
        val shorthandProtection = args.size == 2 && args[0].equals("protection", true)
        if (!shorthandProtection && (args.size != 3 || args[1].lowercase() != "set")) {
            return usage("/tpa cooldown set <time> | /tpa protection <time>")
        }
        permissions.check(actor, "adm.admin.tpa.configure")?.let { return it }
        val seconds = parseDuration(args.last()) ?: return ActionResult.failure("tpa.invalid-duration")
        val path = if (args[0].equals("cooldown", true)) "tpa.cooldown-seconds" else "tpa.protection-seconds"
        val file = File(plugin.dataFolder, "config.yml")
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            yaml.set(path, seconds)
            yaml.save(file)
            if (path.endsWith("cooldown-seconds")) cooldownSeconds = seconds else protectionSeconds = seconds
            done(actor, "tpa", "tpa.setting-updated", mapOf("setting" to args[0].lowercase(), "seconds" to seconds.toString()))
        } catch (failure: Exception) {
            plugin.logger.warning("Could not save TPA setting '$path': ${failure.message}")
            ActionResult.failure("tpa.setting-save-failed")
        }
    }

    private fun parseDuration(value: String): Int? {
        val match = DURATION.matchEntire(value.lowercase()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "m" -> 60L
            "h" -> 3600L
            else -> 1L
        }
        return (amount * multiplier).takeIf { it in 0..MAX_DURATION_SECONDS }?.toInt()
    }

    override fun suggestions(command: String, completed: List<String>): List<String> = when {
        command == "tpa" && completed.isEmpty() -> names()
        command == "tpa" && completed.size == 1 -> listOf("cooldown", "protection")
        command in listOf("tpaccept", "tpdeny") && completed.isEmpty() -> requestsByTarget.values.map { it.requesterName }.distinct()
        else -> emptyList()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protect(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val expiry = protectedUntil[player.uniqueId] ?: return
        if (expiry - System.nanoTime() > 0) event.isCancelled = true else protectedUntil.remove(player.uniqueId)
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        cooldowns.remove(id)
        protectedUntil.remove(id)
        targetByRequester.remove(id)?.let { target ->
            if (requestsByTarget[target]?.requester == id) {
                requestsByTarget.remove(target)
                Bukkit.getPlayer(target)?.let {
                    messages.send(CommandActor.from(it), ActionResult.failure("tpa.requester-left", mapOf("requester" to event.player.name)))
                }
            }
        }
        requestsByTarget.remove(id)?.let { request ->
            targetByRequester.remove(request.requester, id)
            Bukkit.getPlayer(request.requester)?.let {
                messages.send(CommandActor.from(it), ActionResult.failure("tpa.target-left", mapOf("target" to event.player.name)))
            }
        }
        pendingTeleports.remove(id)
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        cooldownSeconds = snapshot.integer("tpa.cooldown-seconds", 5)
        protectionSeconds = snapshot.integer("tpa.protection-seconds", 15)
    }

    override fun disable() {
        active = false
        requestsByTarget.clear()
        targetByRequester.clear()
        cooldowns.clear()
        pendingTeleports.clear()
        protectedUntil.clear()
    }

    private companion object {
        const val MAX_DURATION_SECONDS = 86400L
        const val REQUEST_TIMEOUT_NANOS = 60_000_000_000L
        val DURATION = Regex("([0-9]{1,6})([smh]?)")
    }
}