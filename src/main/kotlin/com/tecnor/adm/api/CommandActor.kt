package com.tecnor.adm.api

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Immutable identity safe to pass to workers. from() and hasPermission() are main-thread only;
 * workers must use only the plain identity accessors, never resolve a live sender.
 */
data class CommandActor(private val playerId: UUID?, private val name: String) {
    val isConsole: Boolean get() = playerId == null
    fun playerId(): UUID? = playerId
    fun name() = name
    fun hasPermission(permission: String) = playerId?.let { Bukkit.getPlayer(it)?.hasPermission(permission) == true } ?: true

    companion object {
        @JvmStatic
        fun from(sender: CommandSender) = if (sender is Player) CommandActor(sender.uniqueId, sender.name)
            else CommandActor(null, "Console")
    }
}