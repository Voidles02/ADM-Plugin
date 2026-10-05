package com.tecnor.adm.integration

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class WorldGuardCompatibility(private val plugin: JavaPlugin) {
    private val warned = AtomicBoolean(false)

    fun allowsTeleport(player: Player, destination: Location): Boolean {
        if (!plugin.server.pluginManager.isPluginEnabled("WorldGuard")) return true
        val destinationWorld = destination.world ?: return false
        return try {
            val worldGuard = WorldGuard.getInstance()
            val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
            val sessions = worldGuard.platform.sessionManager
            val query = worldGuard.platform.regionContainer.createQuery()
            val canExit = sessions.hasBypass(localPlayer, BukkitAdapter.adapt(player.world)) ||
                query.testState(BukkitAdapter.adapt(player.location), localPlayer, Flags.EXIT_VIA_TELEPORT)
            val canEnter = sessions.hasBypass(localPlayer, BukkitAdapter.adapt(destinationWorld)) ||
                query.testState(BukkitAdapter.adapt(destination), localPlayer, Flags.ENTRY)
            canExit && canEnter
        } catch (failure: Throwable) {
            if (warned.compareAndSet(false, true)) {
                plugin.logger.log(Level.WARNING, "WorldGuard teleport check failed; Bukkit teleport-event protection remains active.", failure)
            }
            true
        }
    }
}