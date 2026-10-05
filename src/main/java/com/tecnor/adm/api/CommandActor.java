package com.tecnor.adm.api;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Immutable identity safe to pass to workers. from() and hasPermission() are main-thread only;
 * workers must use only the plain identity accessors, never resolve a live sender.
 */
public record CommandActor(UUID playerId, String name) {
    public static CommandActor from(CommandSender sender) {
        if (sender instanceof Player player) {
            return new CommandActor(player.getUniqueId(), player.getName());
        }
        return new CommandActor(null, "Console");
    }

    public boolean isConsole() {
        return playerId == null;
    }

    public boolean hasPermission(String permission) {
        if (isConsole()) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.hasPermission(permission);
    }
}