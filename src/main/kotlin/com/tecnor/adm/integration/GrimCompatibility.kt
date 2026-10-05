package com.tecnor.adm.integration

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class GrimCompatibility(private val plugin: JavaPlugin) : Listener, AutoCloseable {
    private val attachments = HashMap<UUID, PermissionAttachment>()
    private var refreshTask: BukkitTask? = null

    fun enable() {
        if (grimEnabled()) {
            refreshPlayers()
            startRefreshTask()
        }
    }

    @EventHandler
    fun join(event: PlayerJoinEvent) {
        refresh(event.player)
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        remove(event.player)
    }

    @EventHandler
    fun pluginEnable(event: PluginEnableEvent) {
        if (!event.plugin.name.equals("GrimAC", ignoreCase = true)) return
        refreshPlayers()
        startRefreshTask()
    }

    @EventHandler
    fun pluginDisable(event: PluginDisableEvent) {
        if (!event.plugin.name.equals("GrimAC", ignoreCase = true)) return
        refreshTask?.cancel()
        refreshTask = null
        removeAll()
    }

    private fun startRefreshTask() {
        if (refreshTask == null) {
            refreshTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::refreshPlayers), 20L, 40L)
        }
    }

    private fun grimEnabled() = plugin.server.pluginManager.getPlugin("GrimAC")?.isEnabled == true

    private fun refreshPlayers() {
        plugin.server.onlinePlayers.forEach(::refresh)
    }

    private fun refresh(player: Player) {
        val id = player.uniqueId
        val current = attachments[id]
        val shouldExempt = grimEnabled() && player.hasPermission("adm.grim.exempt")
        if (shouldExempt && current == null) {
            attachments[id] = player.addAttachment(plugin, "grim.exempt", true)
        } else if (!shouldExempt && current != null) {
            player.removeAttachment(current)
            attachments.remove(id)
        }
    }

    private fun remove(player: Player) {
        attachments.remove(player.uniqueId)?.let(player::removeAttachment)
    }

    private fun removeAll() {
        attachments.forEach { (id, attachment) ->
            plugin.server.getPlayer(id)?.removeAttachment(attachment)
        }
        attachments.clear()
    }

    override fun close() {
        refreshTask?.cancel()
        refreshTask = null
        removeAll()
    }
}