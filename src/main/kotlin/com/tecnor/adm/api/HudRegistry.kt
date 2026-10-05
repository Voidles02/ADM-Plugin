package com.tecnor.adm.api

import com.tecnor.adm.hud.HudButton
import com.tecnor.adm.hud.HudScreen
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class HudRegistry {
    private data class Entry(val owner: Plugin, val factory: () -> HudScreen)
    private val screens = linkedMapOf<String, Entry>()
    private val buttons = linkedMapOf<String, MutableList<Pair<Plugin, HudButton>>>()

    fun register(owner: Plugin, id: String, factory: () -> HudScreen) {
        check(Bukkit.isPrimaryThread())
        require(owner.isEnabled && id.matches(Regex("[a-z][a-z0-9_-]*")))
        require(id !in screens) { "Duplicate HUD screen: $id" }
        screens[id] = Entry(owner, factory)
    }

    fun addButton(owner: Plugin, screen: String, button: HudButton) {
        check(Bukkit.isPrimaryThread())
        require(owner.isEnabled)
        buttons.getOrPut(screen) { mutableListOf() }.add(owner to button)
    }

    fun create(id: String): HudScreen? = screens[id]?.takeIf { it.owner.isEnabled }?.factory?.invoke()
    fun ids(): List<String> = screens.filterValues { it.owner.isEnabled }.keys.toList()
    fun buttons(id: String): List<HudButton> = buttons[id].orEmpty().filter { it.first.isEnabled }.map { it.second }

    fun unregisterAll(owner: Plugin) {
        check(Bukkit.isPrimaryThread())
        screens.entries.removeIf { it.value.owner === owner }
        buttons.values.forEach { it.removeIf { entry -> entry.first === owner } }
    }

    fun clear() {
        screens.clear()
        buttons.clear()
    }
}