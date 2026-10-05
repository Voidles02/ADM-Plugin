package com.tecnor.adm.core

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.settings.ConfigService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Cooldown maps are concurrent; permission checks and all mutation are main-thread confined. */
class PermissionService(private val config: ConfigService) {
    private val cooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    fun check(actor: CommandActor, permission: String): ActionResult? =
        if (actor.hasPermission(permission)) null else ActionResult.failure("command.no-permission")

    fun cooldown(actor: CommandActor, command: String): ActionResult? {
        if (actor.isConsole) return null
        val seconds = config.current().integer("cooldowns.$command", 0)
        if (seconds == 0) return null
        val now = System.nanoTime()
        val previous = cooldowns[actor.playerId()]?.get(command) ?: return null
        val remaining = seconds * 1_000_000_000L - (now - previous)
        return if (remaining <= 0) null else ActionResult.failure(
            "command.cooldown", mapOf("seconds" to ceil(remaining / 1_000_000_000.0).toLong().toString())
        )
    }

    fun record(actor: CommandActor, command: String) {
        if (!actor.isConsole) {
            cooldowns.computeIfAbsent(actor.playerId()) { ConcurrentHashMap() }[command] = System.nanoTime()
        }
    }

    fun forget(id: UUID) { cooldowns.remove(id) }
    fun clear() { cooldowns.clear() }
}