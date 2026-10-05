package com.tecnor.adm.settings

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stateless loader. Each invocation owns its YAML parsers and returns immutable plain data.
 * Used synchronously only during startup, and on the ADM executor during reload.
 */
object SettingsLoader {
    private val moduleIds = listOf("core", "player-tools", "teleport", "tpa", "information", "chat", "anticheat", "inventory", "vanish",
        "punishments", "staff-chat", "staff-tools", "reports", "audit")
    private val featureCommandNames = setOf(
        "adm-connect", "anticheat", "announcement", "annoucement", "broadcast", "clearchat", "mutechat", "slowmode", "sudo",
        "near", "ping", "list", "whois", "seen", "endersee", "enderedit", "invsee", "gamemode", "gm", "gmc", "gms",
        "gma", "gmsp", "fly", "speed", "god", "heal", "feed", "repair", "clear", "report", "reports", "staffchat", "sc",
        "spy", "tp", "tphere", "tpall", "tppos", "back", "top", "tpa", "tpaccept", "tpdeny",
        "admtpa", "admtpaccept", "admtpdeny", "vanish", "v"
    )

    @JvmStatic
    fun load(directory: Path): SettingsSnapshot {
        val config = read(directory.resolve("config.yml"))
        val messages = read(directory.resolve("messages.yml"))
        val values = LinkedHashMap(immutableSection(config))
        if (Files.isRegularFile(directory.resolve("hud.yml"))) {
            val hud = read(directory.resolve("hud.yml"))
            boundedInteger(hud, "input-timeout-seconds", 60, 5, 600)
            boundedInteger(hud, "status.ttl-seconds", 5, 1, 60)
            boundedInteger(hud, "status.live-refresh-seconds", 0, 0, 60)
            boundedInteger(hud, "status.slow-storage-ms", 100, 1, 60000)
            hud.getConfigurationSection("slots")?.let { slots ->
                val used = hashSetOf<Int>()
                for (key in slots.getKeys(false)) {
                    val slot = boundedInteger(slots, key, 45, 45, 53)
                    require(used.add(slot)) { "Duplicate HUD navigation slot: $slot" }
                }
            }
            val content = hud.get("layout.content-slots")
            if (content != null) {
                require(content is List<*> && content.size >= 9 && content.all {
                    it is Number && it.toInt().toDouble() == it.toDouble() && it.toInt() in 0..44
                } && content.toSet().size == content.size) {
                    "HUD content slots must contain at least nine unique slots from 0 to 44"
                }
            }
            hud.getConfigurationSection("categories")?.let { categories ->
                for (id in categories.getKeys(false)) {
                    boundedInteger(hud, "categories.$id.slot", 10, 0, 44)
                    require(!hud.contains("categories.$id.enabled") || hud.get("categories.$id.enabled") is Boolean) {
                        "HUD category enabled values must be booleans"
                    }
                }
            }
            val validator = MiniMessage.builder().strict(true).build()
            hud.getValues(true).values.filterIsInstance<String>().forEach { validator.deserialize(it) }
            values["hud"] = immutableSection(hud)
        }

        val modules = moduleIds.associateWith { true }.toMutableMap()
        val moduleSection = config.get("modules")
        require(moduleSection == null || moduleSection is ConfigurationSection) { "modules must be a YAML section" }
        if (moduleSection is ConfigurationSection) {
            for (id in moduleSection.getKeys(false)) {
                require(id.matches(Regex("[a-z][a-z0-9_-]*"))) { "Invalid module identifier: $id" }
                val enabled = moduleSection.get(id)
                require(enabled is Boolean) { "modules.$id must be true or false" }
                modules[id] = enabled
            }
        }

        val commandSection = config.get("commands")
        require(commandSection == null || commandSection is ConfigurationSection) { "commands must be a YAML section" }
        val name = string(config, "commands.name", "adm")
        validateCommandName(name)
        val aliases = mutableListOf<String>()
        when (val aliasValue = config.get("commands.aliases")) {
            null -> aliases.add("advancedadminmanagement")
            is List<*> -> for (alias in aliasValue) {
                require(alias is String) { "commands.aliases must contain only strings" }
                validateCommandName(alias)
                aliases.add(alias)
            }
            else -> throw IllegalArgumentException("commands.aliases must be a list")
        }
        val usedNames = hashSetOf(name)
        for (alias in aliases) require(usedNames.add(alias)) { "Duplicate command name or alias: $alias" }
        require(usedNames.none { it in featureCommandNames }) {
            "commands.name and commands.aliases cannot use labels reserved by ADM feature commands"
        }

        val fallback = string(config, "login-fallback", "allow")
        require(fallback == "allow" || fallback == "deny") { "login-fallback must be allow or deny" }
        val cooldowns = config.get("cooldowns")
        require(cooldowns == null || cooldowns is ConfigurationSection) { "cooldowns must be a YAML section" }
        if (cooldowns is ConfigurationSection) {
            for (key in cooldowns.getKeys(false)) boundedInteger(config, "cooldowns.$key", 0, 0, 86400)
        }
        val tpa = config.get("tpa")
        require(tpa == null || tpa is ConfigurationSection) { "tpa must be a YAML section" }
        boundedInteger(config, "tpa.cooldown-seconds", 5, 0, 86400)
        boundedInteger(config, "tpa.protection-seconds", 15, 0, 86400)
        val slowMin = boundedInteger(config, "slowmode.min-seconds", 1, 1, 86400)
        val slowMax = boundedInteger(config, "slowmode.max-seconds", 300, 1, 86400)
        require(slowMin <= slowMax) { "slowmode.min-seconds must not exceed max-seconds" }
        val nearDefault = boundedInteger(config, "near.default-radius", 100, 1, 10000)
        val nearMax = boundedInteger(config, "near.max-radius", 1000, 1, 10000)
        require(nearDefault <= nearMax) { "near.default-radius must not exceed max-radius" }
        boundedInteger(config, "clearchat.lines", 100, 1, 500)
        boundedInteger(config, "audit.max-file-bytes", 10485760, 1024, Int.MAX_VALUE)
        boundedInteger(config, "punishments.page-size", 10, 1, 50)
        boundedInteger(config, "freeze.actionbar-seconds", 5, 5, 86400)
        boundedInteger(config, "audit.retention-days", 90, 1, 36500)
        boundedInteger(config, "reports.retention-days", 90, 1, 36500)
        boundedInteger(config, "reports.cooldown-seconds", 60, 0, 86400)
        for (key in listOf("audit.enabled", "audit.file-secondary", "audit.prune-on-start", "audit.rotate-daily", "vanish.on-join", "vanish.fake-join", "vanish.fake-quit")) {
            require(!config.contains(key) || config.get(key) is Boolean) { "$key must be true or false" }
        }
        for (key in listOf("freeze.command-whitelist", "spy.ignored-commands", "spy.social-commands")) {
            val list = config.get(key)
            require(list == null || list is List<*> && list.all { it is String }) { "$key must be a list of command labels" }
        }
        string(config, "punishments.default-reason", "No reason specified")
        val quitAction = string(config, "freeze.quit-action", "notify")
        require(quitAction == "notify" || quitAction == "none" || quitAction.startsWith("command:")) {
            "freeze.quit-action must be notify, none, or command:<console command>"
        }
        val escalation = config.get("punishments.escalation")
        if (escalation != null) {
            require(escalation is List<*>) { "punishments.escalation must be a list" }
            val counts = hashSetOf<Int>()
            for (entry in escalation) {
                val rule = entry as? Map<*, *>
                val count = rule?.get("warnings") as? Number
                val duration = rule?.get("duration") as? String
                require(rule != null && count != null && count.toInt() >= 1 && count.toDouble() == count.toInt().toDouble() &&
                    counts.add(count.toInt()) && rule["action"] in listOf("tempmute", "tempban") &&
                    duration != null && duration.matches(Regex("[1-9][0-9]{0,8}[mhd]"))) {
                    "Invalid or duplicate warning escalation rule"
                }
            }
        }
        MiniMessage.builder().strict(true).build().deserialize(string(config, "staffchat.prefix", "<gray>[Staff]</gray> "))

        val templates = linkedMapOf<String, String>()
        val validator = MiniMessage.builder().strict(true).build()
        for ((key, value) in messages.getValues(true)) {
            if (value is ConfigurationSection) continue
            require(value is String) { "messages.yml: $key must be a string" }
            try {
                validator.deserialize(value)
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException("Invalid MiniMessage at messages.yml: $key", exception)
            }
            templates[key] = value
        }
        return SettingsSnapshot(modules, SettingsSnapshot.CommandSettings(name, aliases), fallback, values, templates)
    }

    @JvmStatic
    fun defaults() = SettingsSnapshot(
        moduleIds.associateWith { true },
        SettingsSnapshot.CommandSettings("adm", listOf("advancedadminmanagement")),
        "allow",
        mapOf("modules" to mapOf("core" to true),
            "commands" to mapOf("name" to "adm", "aliases" to listOf("advancedadminmanagement")),
            "tpa" to mapOf("cooldown-seconds" to 5, "protection-seconds" to 15),
            "login-fallback" to "allow"),
        emptyMap()
    )

    private fun read(path: Path): YamlConfiguration {
        if (!Files.isRegularFile(path)) throw IOException("Missing settings file: ${path.fileName}")
        return YamlConfiguration().also { yaml -> Files.newBufferedReader(path, StandardCharsets.UTF_8).use { yaml.load(it) } }
    }

    private fun boundedInteger(section: ConfigurationSection, key: String, fallback: Int, min: Int, max: Int): Int {
        val value = section.get(key) ?: return fallback
        require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in min..max) {
            "$key must be an integer from $min to $max"
        }
        return value.toInt()
    }

    private fun string(section: ConfigurationSection, key: String, fallback: String): String {
        val value = section.get(key) ?: return fallback
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun validateCommandName(name: String) {
        require(name.matches(Regex("[a-z][a-z0-9_-]*"))) { "Invalid command name or alias: $name" }
    }

    private fun immutableSection(section: ConfigurationSection): Map<String, Any> =
        java.util.Map.copyOf(section.getValues(false).mapValues { immutableValue(it.value) })

    private fun immutableValue(value: Any?): Any = when (value) {
        is ConfigurationSection -> immutableSection(value)
        is String, is Boolean, is Number -> value
        is List<*> -> java.util.List.copyOf(value.map { immutableValue(it) })
        is Map<*, *> -> {
            val result = linkedMapOf<String, Any>()
            for ((key, entry) in value) {
                require(key is String) { "Configuration map keys must be strings" }
                result[key] = immutableValue(entry)
            }
            java.util.Map.copyOf(result)
        }
        else -> throw IllegalArgumentException("Configuration values must contain only plain YAML data")
    }
}