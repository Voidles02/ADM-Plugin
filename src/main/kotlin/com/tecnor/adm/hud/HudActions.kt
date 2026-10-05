package com.tecnor.adm.hud

import org.bukkit.Material

data class HudAction(
    val command: String,
    val module: String,
    val node: String,
    val syntax: String,
    val target: Boolean = false,
    val input: Boolean = false,
    val destructive: Boolean = false,
    val argument: String = "",
    val aliases: String = "none"
)

data class HudCategory(val id: String, val name: String, val material: Material, val modules: List<String>)

object HudActions {
    val categories = listOf(
        HudCategory("player", "Player Management", Material.PLAYER_HEAD, listOf("player-tools", "information")),
        HudCategory("teleport", "Teleportation", Material.ENDER_PEARL, listOf("teleport")),
        HudCategory("inventory", "Inventory Tools", Material.CHEST, listOf("inventory")),
        HudCategory("chat", "Chat Tools", Material.WRITABLE_BOOK, listOf("chat", "staff-chat")),
        HudCategory("vanish", "Vanish and Staff Mode", Material.GLASS, listOf("vanish", "staff-tools")),
        HudCategory("server", "Server Tools", Material.COMMAND_BLOCK, listOf("core", "audit")),
        HudCategory("settings", "Settings", Material.COMPARATOR, listOf("core")),
        HudCategory("moderation", "Moderation", Material.IRON_BARS, listOf("staff-tools", "punishments")),
        HudCategory("punishments", "Punishments", Material.IRON_AXE, listOf("punishments")),
        HudCategory("reports", "Reports", Material.PAPER, listOf("reports")),
        HudCategory("logs", "Logs and Audit", Material.BOOK, listOf("audit")),
        HudCategory("integrations", "Integrations", Material.HOPPER, listOf("inventory", "core")),
        HudCategory("status", "System Status", Material.REDSTONE, listOf("core"))
    )
    val actions = linkedMapOf(
        "player" to listOf(
            HudAction("gamemode", "player-tools", "adm.mod.gamemode", "/gamemode <mode> [player]", true, true, aliases = "gm, gmc, gms, gma, gmsp"),
            HudAction("fly", "player-tools", "adm.mod.fly", "/fly [player]", true),
            HudAction("speed", "player-tools", "adm.mod.speed", "/speed <0-10> [fly|walk] [player]", true, true),
            HudAction("god", "player-tools", "adm.admin.god", "/god [player]", true),
            HudAction("heal", "player-tools", "adm.mod.heal", "/heal [player]", true),
            HudAction("feed", "player-tools", "adm.mod.feed", "/feed [player]", true),
            HudAction("repair", "player-tools", "adm.admin.repair", "/repair [hand|all]", input = true),
            HudAction("clear", "player-tools", "adm.admin.clear", "/clear [player]", true, destructive = true),
            HudAction("whois", "information", "adm.admin.whois", "/whois <player>", true),
            HudAction("seen", "information", "adm.admin.seen", "/seen <player>", true),
            HudAction("near", "information", "adm.mod.near", "/near [radius]"),
            HudAction("ping", "information", "adm.mod.ping", "/ping [player]", true)
        ),
        "teleport" to listOf(
            HudAction("tp", "teleport", "adm.mod.tp", "/tp <player>", true),
            HudAction("tphere", "teleport", "adm.mod.tphere", "/tphere <player>", true),
            HudAction("tpall", "teleport", "adm.admin.tpall", "/tpall", destructive = true),
            HudAction("tppos", "teleport", "adm.admin.tppos", "/tppos <x> <y> <z> [world]", input = true),
            HudAction("back", "teleport", "adm.mod.back", "/back"),
            HudAction("top", "teleport", "adm.mod.top", "/top"),
            HudAction("tp", "teleport", "adm.mod.tp", "/tp <random online player>", argument = "random")
        ),
        "inventory" to listOf("invsee", "endersee", "enderedit").map { HudAction(it, "inventory", "adm.admin.$it", "/$it <player>", true) },
        "chat" to listOf(
            HudAction("broadcast", "chat", "adm.admin.broadcast", "/broadcast <message>", input = true),
            HudAction("clearchat", "chat", "adm.mod.clearchat", "/clearchat"),
            HudAction("mutechat", "chat", "adm.mod.mutechat", "/mutechat"),
            HudAction("slowmode", "chat", "adm.mod.slowmode", "/slowmode <seconds|off>", input = true),
            HudAction("staffchat", "staff-chat", "adm.mod.staffchat", "/staffchat [message]", aliases = "sc"),
            HudAction("spy", "staff-chat", "adm.admin.spy.commands", "/spy commands", argument = "commands"),
            HudAction("spy", "staff-chat", "adm.admin.spy.social", "/spy social", argument = "social"),
            HudAction("sudo", "chat", "adm.admin.sudo", "/sudo <player> <message or /command>", true, true, true)
        ),
        "vanish" to listOf(
            HudAction("vanish", "vanish", "adm.admin.vanish", "/vanish [level]", aliases = "v"),
            HudAction("vanish", "vanish", "adm.admin.vanish", "/vanish <level>", input = true, argument = "level", aliases = "v"),
            HudAction("staffmode", "staff-tools", "adm.mod.staffmode", "/staffmode", aliases = "sm")
        ),
        "moderation" to listOf(
            HudAction("freeze", "staff-tools", "adm.mod.freeze", "/freeze <player>", true),
            HudAction("mute", "punishments", "adm.mod.mute", "/mute <player> [reason]", true, true),
            HudAction("tempmute", "punishments", "adm.mod.tempmute", "/tempmute <player> <duration> [reason]", true, true),
            HudAction("unmute", "punishments", "adm.mod.unmute", "/unmute <player>", true),
            HudAction("warn", "punishments", "adm.mod.warn", "/warn <player> [reason]", true, true),
            HudAction("warnings", "punishments", "adm.mod.warnings", "/warnings <player> [page]", true),
            HudAction("clearwarnings", "punishments", "adm.mod.clearwarnings", "/clearwarnings <player>", true, destructive = true),
            HudAction("kick", "punishments", "adm.mod.kick", "/kick <player> [reason]", true, true, true)
        ),
        "punishments" to listOf(
            HudAction("ban", "punishments", "adm.admin.ban", "/ban <player> [reason]", true, true, true),
            HudAction("tempban", "punishments", "adm.admin.tempban", "/tempban <player> <duration> [reason]", true, true, true),
            HudAction("ipban", "punishments", "adm.admin.ipban", "/ipban <player|ip> [reason]", true, true, true),
            HudAction("unban", "punishments", "adm.admin.unban", "/unban <player|ip>", true, destructive = true),
            HudAction("history", "punishments", "adm.admin.history", "/history <player> [page]", true),
            HudAction("alts", "punishments", "adm.admin.alts", "/alts <player> [page]", true)
        ),
        "server" to listOf(
            HudAction("reload", "core", "adm.admin.reload", "/adm reload", destructive = true),
            HudAction("cleanup", "audit", "adm.admin.cleanup", "/adm cleanup", destructive = true),
            HudAction("storage-info", "core", "adm.admin.storage-info", "/adm storage-info"),
            HudAction("debug", "core", "adm.admin.debug", "/adm debug"),
            HudAction("version", "core", "adm.admin.version", "/adm version")
        )
    )
}