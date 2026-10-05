package com.tecnor.adm.message

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.settings.ConfigService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Rendering uses one atomic immutable snapshot per message and is thread-safe.
 * Missing-key warnings use a concurrent set for a once-per-key warning per plugin instance.
 * Sending resolves live audiences and must occur on the server thread.
 */
class MessageService(private val settings: ConfigService, private val logger: Logger) {
    private val miniMessage = MiniMessage.miniMessage()
    private val warnedKeys = ConcurrentHashMap.newKeySet<String>()

    fun render(result: ActionResult): Component {
        val templates = settings.current().messages()
        val tags = TagResolver.builder()
        result.placeholders().forEach { (key, value) -> tags.resolver(Placeholder.unparsed(key, value)) }
        tags.resolver(Placeholder.component("prefix", miniMessage.deserialize(template(templates, "prefix"))))
        return miniMessage.deserialize(template(templates, result.messageKey()), tags.build())
    }

    fun send(actor: CommandActor, result: ActionResult) {
        val sender: CommandSender? = if (actor.isConsole) Bukkit.getConsoleSender()
            else actor.playerId()?.let { Bukkit.getPlayer(it) }
        sender?.sendMessage(render(result))
    }

    private fun template(configured: Map<String, String>, key: String): String {
        configured[key]?.let { return it }
        if (warnedKeys.add(key)) logger.warning("Missing messages.yml key '$key'; using its built-in default.")
        return bundledDefaults.getOrDefault(key, "<prefix><red>Missing message: <key></red>").replace("<key>", key)
    }

    companion object {
        private val defaults = mapOf(
            "prefix" to "<dark_gray>[<aqua>ADM</aqua>]</dark_gray> ",
            "command.help" to "<prefix><gray>Commands: /<command> version, /<command> reload, /<command> debug</gray>",
            "command.no-permission" to "<prefix><red>You do not have permission to do that.</red>",
            "command.module-unavailable" to "<prefix><red>The <module> module is unavailable.</red>",
            "command.version" to "<prefix><gray>ADM <aqua><version></aqua> by <aqua><author></aqua>.</gray>",
            "command.debug" to "<prefix><gray>Module status:</gray><newline><details>",
            "command.module-status" to "<gray><module>: <status></gray>",
            "reload.started" to "<prefix><gray>Reading settings asynchronously...</gray>",
            "reload.busy" to "<prefix><yellow>A reload is already running.</yellow>",
            "reload.success" to "<prefix><green>Settings and messages reloaded.</green>",
            "reload.invalid" to "<prefix><red>Reload rejected; the previous settings are still active. <error></red>",
            "reload.unavailable" to "<prefix><red>ADM is shutting down; reload is unavailable.</red>",
            "reload.restart-required" to "<prefix><yellow>Restart required to apply: <setting>.</yellow>",
            "reload.module-failed" to "<prefix><red>Module <module> failed during reload and was disabled. Check the server log.</red>",
            "command.player-only" to "<prefix><red>This command needs a player; specify a target where supported.</red>",
            "command.player-not-found" to "<prefix><red>Online player <target> was not found.</red>",
            "command.hierarchy" to "<prefix><red>You cannot target <target>: they are immune or not below your rank.</red>",
            "command.cooldown" to "<prefix><yellow>Wait <seconds> seconds before using this command again.</yellow>",
            "command.usage" to "<prefix><yellow>Usage: <usage></yellow>",
            "tools.gamemode" to "<prefix><green>Set <target>'s game mode to <mode>.</green>",
            "tools.fly" to "<prefix><green>Flight for <target>: <state>.</green>",
            "tools.speed" to "<prefix><green>Set <target>'s <type> speed to <speed>.</green>",
            "tools.god" to "<prefix><green>God mode for <target>: <state>.</green>",
            "tools.heal" to "<prefix><green>Healed <target>.</green>",
            "tools.feed" to "<prefix><green>Fed <target>.</green>",
            "tools.clear" to "<prefix><green>Cleared <target>'s inventory.</green>",
            "tools.repair" to "<prefix><green>Repaired <count> item(s).</green>",
            "tools.nothing-to-repair" to "<prefix><yellow>No damaged repairable items were found.</yellow>",
            "tools.dead" to "<prefix><red><target> is dead and cannot be healed yet.</red>",
            "teleport.started" to "<prefix><gray>Teleport requested for <target>.</gray>",
            "teleport.success" to "<prefix><green>Teleported <target>.</green>",
            "teleport.failed" to "<prefix><red>Teleport failed or was cancelled.</red>",
            "teleport.busy" to "<prefix><yellow>A teleport is already pending for that player.</yellow>",
            "teleport.all" to "<prefix><green>Requested teleports for <count> player(s).</green>",
            "teleport.same-player" to "<prefix><yellow>You are already at your own location.</yellow>",
            "teleport.world-not-found" to "<prefix><red>World <world> was not found.</red>",
            "teleport.out-of-bounds" to "<prefix><red>Coordinates are outside the world bounds or border.</red>",
            "teleport.no-back" to "<prefix><yellow>No previous location is available in this session.</yellow>",
            "teleport.no-safe-top" to "<prefix><red>No safe top location was found at this X/Z.</red>",
            "information.list" to "<prefix><gray>Online (<count>): <players></gray>",
            "information.ping" to "<prefix><gray><target>'s ping: <ping>ms.</gray>",
            "information.near" to "<prefix><gray>Nearby within <radius>m (<count>): <players></gray>",
            "information.radius" to "<prefix><red>Radius must be an integer from 1 to <max>.</red>",
            "information.seen" to "<prefix><gray><target>: first played <first>; last seen <last> (UTC).</gray>",
            "information.whois-online" to "<prefix><gray><target><newline>Ping: <ping>ms<newline>Game mode: <gamemode><newline>Location: <location><newline>Playtime: <playtime><newline>First played: <first><newline>Last seen: <last> (UTC)</gray>",
            "information.whois-online-ip" to "<prefix><gray><target><newline>IP: <ip><newline>Ping: <ping>ms<newline>Game mode: <gamemode><newline>Location: <location><newline>Playtime: <playtime><newline>First played: <first><newline>Last seen: <last> (UTC)</gray>",
            "information.whois-offline" to "<prefix><gray><target> (offline)<newline>First played: <first><newline>Last seen: <last> (UTC)<newline>IP: unavailable<newline>Ping: unavailable<newline>Game mode: unavailable<newline>Location: unavailable<newline>Playtime: unavailable</gray>",
            "inventory.title" to "<type>: <target>",
            "inventory.opened" to "<prefix><green>Opened <target>: <mode>.</green>",
            "inventory.locked" to "<prefix><red>This Ender Chest is locked by a staff editor.</red>",
            "inventory.provider-unavailable" to "<prefix><red>No Ender Chest provider is available.</red>",
            "inventory.offline-ender" to "<prefix><red>Offline access unavailable.</red>",
            "inventory.offline-invsee" to "<prefix><red>Offline inventory access unavailable in this stage.</red>",
            "inventory.open-failed" to "<prefix><red>The inventory could not be opened.</red>",
            "inventory.self-edit" to "<prefix><red>Use your own inventory directly; invsee self-editing is unavailable.</red>",
            "vanish.help" to "<prefix><yellow>Vanish: /vanish on uses your highest allowed level; /vanish off restores visibility; /vanish set LVL selects a level. Bare /vanish toggles. Higher levels are hidden from more staff; viewers need a level at least as high as yours to see you. Your highest permitted level is <max>.</yellow>",
            "vanish.enabled" to "<prefix><green>Vanish enabled at level <level>. Use /vanish off to return or /vanish set LVL to change levels.</green>",
            "vanish.disabled" to "<prefix><green>Vanish disabled.</green>",
            "vanish.invalid-level" to "<prefix><red>That level is not enabled for your role. Your highest permitted level is <max>; use /vanish set LVL with a level you are permitted to use.</red>",
            "vanish.fake-join" to "<yellow><target> joined the game</yellow>",
            "vanish.fake-quit" to "<yellow><target> left the game</yellow>",
            "chat.broadcast" to "<prefix><gold><message></gold>",
            "chat.broadcast-sent" to "<prefix><green>Broadcast sent.</green>",
            "chat.announcement-title" to "<dark_gray>[<aqua><bold>ANNOUNCEMENT</bold></aqua>]</dark_gray>",
            "chat.announcement-subtitle" to "<white><message></white>",
            "chat.announcement" to "<newline><dark_gray><bold>✦</bold></dark_gray> <aqua><bold>ANNOUNCEMENT</bold></aqua><newline><gold><message></gold><newline>",
            "chat.announcement-sent" to "<prefix><green>Announcement sent.</green>",
            "chat.announcement-too-long" to "<prefix><red>Announcement text must be 256 characters or fewer.</red>",
            "chat.clear-line" to " ",
            "chat.cleared" to "<prefix><yellow>Chat was cleared by <actor>.</yellow>",
            "chat.clear-success" to "<prefix><green>Chat cleared.</green>",
            "chat.muted" to "<prefix><yellow>Chat was muted by <actor>.</yellow>",
            "chat.unmuted" to "<prefix><green>Chat was unmuted by <actor>.</green>",
            "chat.mute-success" to "<prefix><green>Global chat mute: <state>.</green>",
            "chat.mute-denied" to "<prefix><red>Chat is currently muted.</red>",
            "chat.slow-on" to "<prefix><yellow><actor> set slowmode to <seconds> seconds.</yellow>",
            "chat.slow-off" to "<prefix><green><actor> disabled slowmode.</green>",
            "chat.slow-success" to "<prefix><green>Slowmode interval: <seconds> seconds (0 = off).</green>",
            "chat.slow-range" to "<prefix><red>Slowmode must be off or an integer from <min> to <max>.</red>",
            "chat.slow-denied" to "<prefix><yellow>Slowmode allows one message every <seconds> seconds.</yellow>",
            "chat.sudo-success" to "<prefix><green>Executed as <target>.</green>",
            "chat.sudo-failed" to "<prefix><red>The target command could not be dispatched.</red>",
            "chat.sudo-nested" to "<prefix><red>Nested sudo is not allowed.</red>"
        )

        private val bundledDefaults: Map<String, String> = try {
            MessageService::class.java.getResourceAsStream("/messages.yml")?.use { stream ->
                val yaml = YamlConfiguration.loadConfiguration(stream.reader(StandardCharsets.UTF_8))
                java.util.Map.copyOf(defaults + yaml.getValues(true).filterValues { it is String }.mapValues { it.value as String })
            } ?: defaults
        } catch (_: Exception) {
            defaults
        }
    }
}