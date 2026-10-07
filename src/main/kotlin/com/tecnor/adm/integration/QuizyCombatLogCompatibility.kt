package com.tecnor.adm.integration

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Locale
import java.util.logging.Level

class QuizyCombatLogCompatibility(
    private val plugin: JavaPlugin,
    private val admCommandLabels: () -> Collection<String>
) : Listener {
    fun enable() {
        plugin.server.pluginManager.plugins
            .firstOrNull { it.name.equals("QuizyCombatLog", ignoreCase = true) && it.isEnabled }
            ?.let(::allowAdmCommands)
    }

    @EventHandler
    fun pluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name.equals("QuizyCombatLog", ignoreCase = true)) {
            allowAdmCommands(event.plugin)
        }
    }

    private fun allowAdmCommands(detected: Plugin) {
        val quizy = detected as? JavaPlugin ?: return
        try {
            val config = quizy.config
            val commands = config.getStringList("unblocked-commands").toMutableList()
            val admCommands = admCommandLabels().asSequence()
                .map { it.trim().removePrefix("/").lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .flatMap { sequenceOf(it, "adm:$it") }
                .distinct()
                .toList()
            val configured = commands.mapTo(hashSetOf()) {
                it.trim().removePrefix("/").lowercase(Locale.ROOT)
            }
            val missing = admCommands.filter(configured::add)
            if (missing.isEmpty()) return

            config.set("unblocked-commands", commands + missing)
            (config as? YamlConfiguration)?.options()?.indent(2)
            quizy.saveConfig()
            plugin.logger.info("Added ${missing.size} ADM command labels to QuizyCombatLog's unblocked-commands; reload QuizyCombatLog to apply them.")
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "Could not update QuizyCombatLog's unblocked-commands automatically.", failure)
        }
    }
}