package com.tecnor.adm.core

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

data class StaffMetadata(val prefix: String, val suffix: String, val weight: Int)

/** Implementations maintain thread-safe plain metadata; lifecycle calls occur on the main thread. */
interface RankProvider : AutoCloseable {
    fun metadata(id: UUID): StaffMetadata?
    fun refresh(id: UUID)
    fun forget(id: UUID)
    override fun close()
}

/** Provider assignment and checks are main-thread confined. No LuckPerms types leak into this service. */
class HierarchyService {
    var provider: RankProvider? = null

    fun rank(player: Player): Int = provider?.let { it.metadata(player.uniqueId)?.weight ?: 0 }
        ?: when {
            player.hasPermission("adm.tier.owner") -> 3
            player.hasPermission("adm.tier.admin") -> 2
            player.hasPermission("adm.tier.mod") -> 1
            else -> 0
        }

    fun check(actor: CommandActor, target: Player): ActionResult? {
        if (actor.isConsole || actor.playerId() == target.uniqueId || actor.hasPermission("adm.bypass.hierarchy")) return null
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        return if (target.hasPermission("adm.immune") || rank(player) <= rank(target)) {
            ActionResult.failure("command.hierarchy", mapOf("target" to target.name))
        } else null
    }
}