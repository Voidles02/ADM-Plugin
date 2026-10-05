package com.tecnor.adm.core

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/** Event callbacks and service references are main-thread confined. */
class CoreListener(
    private val plugin: JavaPlugin,
    private val permissions: PermissionService,
    private val hierarchy: HierarchyService
) : Listener {
    @EventHandler
    fun join(event: PlayerJoinEvent) {
        try {
            hierarchy.provider?.refresh(event.player.uniqueId)
        } catch (failure: Throwable) {
            plugin.logger.log(Level.WARNING, "LuckPerms refresh failed; disabling the integration.", failure)
            hierarchy.provider?.close()
            hierarchy.provider = null
        }
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        permissions.forget(event.player.uniqueId)
        hierarchy.provider?.forget(event.player.uniqueId)
    }
}