package com.tecnor.adm.message;

import com.tecnor.adm.api.ActionResult;
import com.tecnor.adm.api.CommandActor;
import com.tecnor.adm.settings.ConfigService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Rendering uses one atomic immutable snapshot per message and is thread-safe.
 * Missing-key warnings use a concurrent set for a once-per-key warning per plugin instance.
 * Sending resolves live audiences and must occur on the server thread.
 */
public final class MessageService {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("prefix", "<dark_gray>[<aqua>ADM</aqua>]</dark_gray> "),
            Map.entry("command.help", "<prefix><gray>Commands: /<command> version, /<command> reload, /<command> debug</gray>"),
            Map.entry("command.no-permission", "<prefix><red>You do not have permission to do that.</red>"),
            Map.entry("command.module-unavailable", "<prefix><red>The <module> module is unavailable.</red>"),
            Map.entry("command.version", "<prefix><gray>ADM <aqua><version></aqua> by <aqua><author></aqua>.</gray>"),
            Map.entry("command.debug", "<prefix><gray>Module status:</gray><newline><details>"),
            Map.entry("command.module-status", "<gray><module>: <status></gray>"),
            Map.entry("reload.started", "<prefix><gray>Reading settings asynchronously...</gray>"),
            Map.entry("reload.busy", "<prefix><yellow>A reload is already running.</yellow>"),
            Map.entry("reload.success", "<prefix><green>Settings and messages reloaded.</green>"),
            Map.entry("reload.invalid", "<prefix><red>Reload rejected; the previous settings are still active. <error></red>"),
            Map.entry("reload.unavailable", "<prefix><red>ADM is shutting down; reload is unavailable.</red>"),
            Map.entry("reload.restart-required", "<prefix><yellow>Restart required to apply: <setting>.</yellow>"),
            Map.entry("reload.module-failed", "<prefix><red>Module <module> failed during reload and was disabled. Check the server log.</red>"),
            Map.entry("command.player-only", "<prefix><red>This command needs a player; specify a target where supported.</red>"),
            Map.entry("command.player-not-found", "<prefix><red>Online player <target> was not found.</red>"),
            Map.entry("command.hierarchy", "<prefix><red>You cannot target <target>: they are immune or not below your rank.</red>"),
            Map.entry("command.cooldown", "<prefix><yellow>Wait <seconds> seconds before using this command again.</yellow>"),
            Map.entry("command.usage", "<prefix><yellow>Usage: <usage></yellow>"),
            Map.entry("tools.gamemode", "<prefix><green>Set <target>'s game mode to <mode>.</green>"),
            Map.entry("tools.fly", "<prefix><green>Flight for <target>: <state>.</green>"),
            Map.entry("tools.speed", "<prefix><green>Set <target>'s <type> speed to <speed>.</green>"),
            Map.entry("tools.god", "<prefix><green>God mode for <target>: <state>.</green>"),
            Map.entry("tools.heal", "<prefix><green>Healed <target>.</green>"),
            Map.entry("tools.feed", "<prefix><green>Fed <target>.</green>"),
            Map.entry("tools.clear", "<prefix><green>Cleared <target>'s inventory.</green>"),
            Map.entry("tools.repair", "<prefix><green>Repaired <count> item(s).</green>"),
            Map.entry("tools.nothing-to-repair", "<prefix><yellow>No damaged repairable items were found.</yellow>"),
            Map.entry("tools.dead", "<prefix><red><target> is dead and cannot be healed yet.</red>"),
            Map.entry("teleport.started", "<prefix><gray>Teleport requested for <target>.</gray>"),
            Map.entry("teleport.success", "<prefix><green>Teleported <target>.</green>"),
            Map.entry("teleport.failed", "<prefix><red>Teleport failed or was cancelled.</red>"),
            Map.entry("teleport.busy", "<prefix><yellow>A teleport is already pending for that player.</yellow>"),
            Map.entry("teleport.all", "<prefix><green>Requested teleports for <count> player(s).</green>"),
            Map.entry("teleport.same-player", "<prefix><yellow>You are already at your own location.</yellow>"),
            Map.entry("teleport.world-not-found", "<prefix><red>World <world> was not found.</red>"),
            Map.entry("teleport.out-of-bounds", "<prefix><red>Coordinates are outside the world bounds or border.</red>"),
            Map.entry("teleport.no-back", "<prefix><yellow>No previous location is available in this session.</yellow>"),
            Map.entry("teleport.no-safe-top", "<prefix><red>No safe top location was found at this X/Z.</red>"),
            Map.entry("information.list", "<prefix><gray>Online (<count>): <players></gray>"),
            Map.entry("information.ping", "<prefix><gray><target>'s ping: <ping>ms.</gray>"),
            Map.entry("information.near", "<prefix><gray>Nearby within <radius>m (<count>): <players></gray>"),
            Map.entry("information.radius", "<prefix><red>Radius must be an integer from 1 to <max>.</red>"),
            Map.entry("information.seen", "<prefix><gray><target>: first played <first>; last seen <last> (UTC).</gray>"),
            Map.entry("information.whois-online", "<prefix><gray><target><newline>Ping: <ping>ms<newline>Game mode: <gamemode><newline>Location: <location><newline>Playtime: <playtime><newline>First played: <first><newline>Last seen: <last> (UTC)</gray>"),
            Map.entry("information.whois-online-ip", "<prefix><gray><target><newline>IP: <ip><newline>Ping: <ping>ms<newline>Game mode: <gamemode><newline>Location: <location><newline>Playtime: <playtime><newline>First played: <first><newline>Last seen: <last> (UTC)</gray>"),
            Map.entry("information.whois-offline", "<prefix><gray><target> (offline)<newline>First played: <first><newline>Last seen: <last> (UTC)<newline>IP: unavailable<newline>Ping: unavailable<newline>Game mode: unavailable<newline>Location: unavailable<newline>Playtime: unavailable</gray>"),
            Map.entry("inventory.title", "<type>: <target>"),
            Map.entry("inventory.opened", "<prefix><green>Opened <target>: <mode>.</green>"),
            Map.entry("inventory.locked", "<prefix><red>This Ender Chest is locked by a staff editor.</red>"),
            Map.entry("inventory.provider-unavailable", "<prefix><red>No Ender Chest provider is available.</red>"),
            Map.entry("inventory.offline-ender", "<prefix><red>Offline access unavailable.</red>"),
            Map.entry("inventory.offline-invsee", "<prefix><red>Offline inventory access unavailable in this stage.</red>"),
            Map.entry("inventory.open-failed", "<prefix><red>The inventory could not be opened.</red>"),
            Map.entry("inventory.self-edit", "<prefix><red>Use your own inventory directly; invsee self-editing is unavailable.</red>"),
            Map.entry("vanish.enabled", "<prefix><green>Vanish enabled at level <level>.</green>"),
            Map.entry("vanish.disabled", "<prefix><green>Vanish disabled.</green>"),
            Map.entry("vanish.invalid-level", "<prefix><red>You need an explicit vanish level permission. Your highest level is <max>.</red>"),
            Map.entry("vanish.fake-join", "<yellow><target> joined the game</yellow>"),
            Map.entry("vanish.fake-quit", "<yellow><target> left the game</yellow>"),
            Map.entry("chat.broadcast", "<prefix><gold><message></gold>"),
            Map.entry("chat.broadcast-sent", "<prefix><green>Broadcast sent.</green>"),
            Map.entry("chat.clear-line", " "),
            Map.entry("chat.cleared", "<prefix><yellow>Chat was cleared by <actor>.</yellow>"),
            Map.entry("chat.clear-success", "<prefix><green>Chat cleared.</green>"),
            Map.entry("chat.muted", "<prefix><yellow>Chat was muted by <actor>.</yellow>"),
            Map.entry("chat.unmuted", "<prefix><green>Chat was unmuted by <actor>.</green>"),
            Map.entry("chat.mute-success", "<prefix><green>Global chat mute: <state>.</green>"),
            Map.entry("chat.mute-denied", "<prefix><red>Chat is currently muted.</red>"),
            Map.entry("chat.slow-on", "<prefix><yellow><actor> set slowmode to <seconds> seconds.</yellow>"),
            Map.entry("chat.slow-off", "<prefix><green><actor> disabled slowmode.</green>"),
            Map.entry("chat.slow-success", "<prefix><green>Slowmode interval: <seconds> seconds (0 = off).</green>"),
            Map.entry("chat.slow-range", "<prefix><red>Slowmode must be off or an integer from <min> to <max>.</red>"),
            Map.entry("chat.slow-denied", "<prefix><yellow>Slowmode allows one message every <seconds> seconds.</yellow>"),
            Map.entry("chat.sudo-success", "<prefix><green>Executed as <target>.</green>"),
            Map.entry("chat.sudo-failed", "<prefix><red>The target command could not be dispatched.</red>"),
            Map.entry("chat.sudo-nested", "<prefix><red>Nested sudo is not allowed.</red>")
    );

    private static final Map<String, String> BUNDLED_DEFAULTS = bundledDefaults();

    private static Map<String, String> bundledDefaults() {
        try (var stream = MessageService.class.getResourceAsStream("/messages.yml")) {
            if (stream == null) return DEFAULTS;
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            var defaults = new java.util.LinkedHashMap<String, String>(DEFAULTS);
            yaml.getValues(true).forEach((key, value) -> {
                if (value instanceof String text) defaults.put(key, text);
            });
            return Map.copyOf(defaults);
        } catch (Exception failure) {
            return DEFAULTS;
        }
    }

    private final ConfigService settings;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    public MessageService(ConfigService settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    public Component render(ActionResult result) {
        Map<String, String> templates = settings.current().messages();
        TagResolver.Builder tags = TagResolver.builder();
        result.placeholders().forEach((key, value) -> tags.resolver(Placeholder.unparsed(key, value)));
        tags.resolver(Placeholder.component("prefix", miniMessage.deserialize(template(templates, "prefix"))));
        return miniMessage.deserialize(template(templates, result.messageKey()), tags.build());
    }

    public void send(CommandActor actor, ActionResult result) {
        CommandSender sender = actor.isConsole() ? Bukkit.getConsoleSender() : Bukkit.getPlayer(actor.playerId());
        if (sender != null) {
            sender.sendMessage(render(result));
        }
    }

    private String template(Map<String, String> configured, String key) {
        String value = configured.get(key);
        if (value != null) {
            return value;
        }
        if (warnedKeys.add(key)) {
            logger.warning("Missing messages.yml key '" + key + "'; using its built-in default.");
        }
        return BUNDLED_DEFAULTS.getOrDefault(key, "<prefix><red>Missing message: <key></red>")
                .replace("<key>", key);
    }
}