package com.tecnor.adm.settings;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless loader. Each invocation owns its YAML parsers and returns immutable plain data.
 * Used synchronously only during startup, and on the ADM executor during reload.
 */
public final class SettingsLoader {
    private SettingsLoader() {
    }

    public static SettingsSnapshot load(Path directory) throws Exception {
        YamlConfiguration config = read(directory.resolve("config.yml"));
        YamlConfiguration messages = read(directory.resolve("messages.yml"));
        Map<String, Object> values = new LinkedHashMap<>(immutableSection(config));
        if (Files.isRegularFile(directory.resolve("hud.yml"))) {
            YamlConfiguration hud = read(directory.resolve("hud.yml"));
            boundedInteger(hud, "input-timeout-seconds", 60, 5, 600);
            boundedInteger(hud, "status.ttl-seconds", 5, 1, 60);
            boundedInteger(hud, "status.live-refresh-seconds", 0, 0, 60);
            boundedInteger(hud, "status.slow-storage-ms", 100, 1, 60000);
            ConfigurationSection slots = hud.getConfigurationSection("slots");
            if (slots != null) {
                Set<Integer> used = new HashSet<>();
                for (String key : slots.getKeys(false)) {
                    int slot = boundedInteger(slots, key, 45, 45, 53);
                    if (!used.add(slot)) throw new IllegalArgumentException("Duplicate HUD navigation slot: " + slot);
                }
            }
            Object content = hud.get("layout.content-slots");
            if (content != null) {
                if (!(content instanceof List<?> list) || list.size() < 9 || list.stream().anyMatch(item ->
                        !(item instanceof Number n) || n.intValue() != n.doubleValue() || n.intValue() < 0 || n.intValue() > 44)
                        || new HashSet<>(list).size() != list.size()) throw new IllegalArgumentException("HUD content slots must contain at least nine unique slots from 0 to 44");
            }
            ConfigurationSection categories = hud.getConfigurationSection("categories");
            if (categories != null) for (String id : categories.getKeys(false)) {
                boundedInteger(hud, "categories." + id + ".slot", 10, 0, 44);
                if (hud.contains("categories." + id + ".enabled") && !(hud.get("categories." + id + ".enabled") instanceof Boolean))
                    throw new IllegalArgumentException("HUD category enabled values must be booleans");
            }
            MiniMessage validator = MiniMessage.builder().strict(true).build();
            for (Object value : hud.getValues(true).values()) {
                if (value instanceof String text) validator.deserialize(text);
            }
            values.put("hud", immutableSection(hud));
        }

        Map<String, Boolean> modules = new LinkedHashMap<>();
        for (String id : List.of("core", "player-tools", "teleport", "information", "chat", "inventory", "vanish", "punishments", "staff-chat", "staff-tools", "reports", "audit")) {
            modules.put(id, true);
        }
        Object moduleSection = config.get("modules");
        if (moduleSection != null && !(moduleSection instanceof ConfigurationSection)) {
            throw new IllegalArgumentException("modules must be a YAML section");
        }
        if (moduleSection instanceof ConfigurationSection section) {
            for (String id : section.getKeys(false)) {
                if (!id.matches("[a-z][a-z0-9_-]*")) {
                    throw new IllegalArgumentException("Invalid module identifier: " + id);
                }
                Object enabled = section.get(id);
                if (!(enabled instanceof Boolean flag)) {
                    throw new IllegalArgumentException("modules." + id + " must be true or false");
                }
                modules.put(id, flag);
            }
        }

        Object commandSection = config.get("commands");
        if (commandSection != null && !(commandSection instanceof ConfigurationSection)) {
            throw new IllegalArgumentException("commands must be a YAML section");
        }
        String name = string(config, "commands.name", "adm");
        validateCommandName(name);
        List<String> aliases = new ArrayList<>();
        Object aliasValue = config.get("commands.aliases");
        if (aliasValue == null) {
            aliases.add("advancedadminmanagement");
        } else if (aliasValue instanceof List<?> list) {
            for (Object alias : list) {
                if (!(alias instanceof String text)) {
                    throw new IllegalArgumentException("commands.aliases must contain only strings");
                }
                validateCommandName(text);
                aliases.add(text);
            }
        } else {
            throw new IllegalArgumentException("commands.aliases must be a list");
        }
        Set<String> usedNames = new HashSet<>();
        usedNames.add(name);
        for (String alias : aliases) {
            if (!usedNames.add(alias)) {
                throw new IllegalArgumentException("Duplicate command name or alias: " + alias);
            }
        }

        String fallback = string(config, "login-fallback", "allow");
        if (!fallback.equals("allow") && !fallback.equals("deny")) {
            throw new IllegalArgumentException("login-fallback must be allow or deny");
        }

        Object cooldowns = config.get("cooldowns");
        if (cooldowns != null && !(cooldowns instanceof ConfigurationSection)) {
            throw new IllegalArgumentException("cooldowns must be a YAML section");
        }
        if (cooldowns instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) {
                boundedInteger(config, "cooldowns." + key, 0, 0, 86400);
            }
        }
        int slowMin = boundedInteger(config, "slowmode.min-seconds", 1, 1, 86400);
        int slowMax = boundedInteger(config, "slowmode.max-seconds", 300, 1, 86400);
        if (slowMin > slowMax) {
            throw new IllegalArgumentException("slowmode.min-seconds must not exceed max-seconds");
        }
        int nearDefault = boundedInteger(config, "near.default-radius", 100, 1, 10000);
        int nearMax = boundedInteger(config, "near.max-radius", 1000, 1, 10000);
        if (nearDefault > nearMax) {
            throw new IllegalArgumentException("near.default-radius must not exceed max-radius");
        }
        boundedInteger(config, "clearchat.lines", 100, 1, 500);
        boundedInteger(config, "audit.max-file-bytes", 10485760, 1024, Integer.MAX_VALUE);
        boundedInteger(config, "punishments.page-size", 10, 1, 50);
        boundedInteger(config, "freeze.actionbar-seconds", 5, 5, 86400);
        boundedInteger(config, "audit.retention-days", 90, 1, 36500);
        boundedInteger(config, "reports.retention-days", 90, 1, 36500);
        boundedInteger(config, "reports.cooldown-seconds", 60, 0, 86400);
        for (String key : List.of("audit.enabled", "audit.file-secondary", "audit.prune-on-start", "audit.rotate-daily", "vanish.on-join", "vanish.fake-join", "vanish.fake-quit")) {
            if (config.contains(key) && !(config.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException(key + " must be true or false");
            }
        }
        for (String key : List.of("freeze.command-whitelist", "spy.ignored-commands", "spy.social-commands")) {
            Object list = config.get(key);
            if (list != null && (!(list instanceof List<?> entries) || entries.stream().anyMatch(entry -> !(entry instanceof String)))) {
                throw new IllegalArgumentException(key + " must be a list of command labels");
            }
        }
        string(config, "punishments.default-reason", "No reason specified");
        String quitAction = string(config, "freeze.quit-action", "notify");
        if (!quitAction.equals("notify") && !quitAction.equals("none") && !quitAction.startsWith("command:")) {
            throw new IllegalArgumentException("freeze.quit-action must be notify, none, or command:<console command>");
        }
        Object escalation = config.get("punishments.escalation");
        if (escalation != null) {
            if (!(escalation instanceof List<?> entries)) throw new IllegalArgumentException("punishments.escalation must be a list");
            Set<Integer> counts = new HashSet<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> rule) || !(rule.get("warnings") instanceof Number count)
                        || count.intValue() < 1 || count.doubleValue() != count.intValue() || !counts.add(count.intValue())
                        || !List.of("tempmute", "tempban").contains(rule.get("action"))
                        || !(rule.get("duration") instanceof String duration) || !duration.matches("[1-9][0-9]{0,8}[mhd]")) {
                    throw new IllegalArgumentException("Invalid or duplicate warning escalation rule");
                }
            }
        }
        MiniMessage.builder().strict(true).build().deserialize(string(config, "staffchat.prefix", "<gray>[Staff]</gray> "));

        Map<String, String> templates = new LinkedHashMap<>();
        MiniMessage validator = MiniMessage.builder().strict(true).build();
        for (Map.Entry<String, Object> entry : messages.getValues(true).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection) {
                continue;
            }
            if (!(entry.getValue() instanceof String template)) {
                throw new IllegalArgumentException("messages.yml: " + entry.getKey() + " must be a string");
            }
            try {
                validator.deserialize(template);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid MiniMessage at messages.yml: " + entry.getKey(), exception);
            }
            templates.put(entry.getKey(), template);
        }

        return new SettingsSnapshot(modules, new SettingsSnapshot.CommandSettings(name, aliases),
                fallback, values, templates);
    }

    public static SettingsSnapshot defaults() {
        return new SettingsSnapshot(
                Map.ofEntries(Map.entry("core", true), Map.entry("player-tools", true), Map.entry("teleport", true),
                        Map.entry("information", true), Map.entry("chat", true), Map.entry("inventory", true),
                        Map.entry("vanish", true), Map.entry("punishments", true), Map.entry("staff-chat", true), Map.entry("staff-tools", true),
                        Map.entry("reports", true), Map.entry("audit", true)),
                new SettingsSnapshot.CommandSettings("adm", List.of("advancedadminmanagement")),
                "allow",
                Map.of("modules", Map.of("core", true),
                        "commands", Map.of("name", "adm", "aliases", List.of("advancedadminmanagement")),
                        "login-fallback", "allow"),
                Map.of()
        );
    }

    private static YamlConfiguration read(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing settings file: " + path.getFileName());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    private static int boundedInteger(ConfigurationSection section, String key, int fallback, int min, int max) {
        Object value = section.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max) {
            throw new IllegalArgumentException(key + " must be an integer from " + min + " to " + max);
        }
        return number.intValue();
    }

    private static String string(ConfigurationSection section, String key, String fallback) {
        Object value = section.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    private static void validateCommandName(String name) {
        if (!name.matches("[a-z][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid command name or alias: " + name);
        }
    }

    private static Map<String, Object> immutableSection(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            result.put(entry.getKey(), immutableValue(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof ConfigurationSection section) {
            return immutableSection(section);
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(immutableValue(element));
            }
            return List.copyOf(result);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Configuration map keys must be strings");
                }
                result.put(key, immutableValue(entry.getValue()));
            }
            return Map.copyOf(result);
        }
        throw new IllegalArgumentException("Configuration values must contain only plain YAML data");
    }
}