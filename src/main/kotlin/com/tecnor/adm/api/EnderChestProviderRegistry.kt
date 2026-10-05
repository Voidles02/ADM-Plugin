package com.tecnor.adm.api

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Register through Bukkit's ServicesManager. Higher priorities win; vanilla is the fallback. */
class EnderChestProviderRegistry {
    private data class Entry(val owner: Plugin, val provider: EnderChestProvider)
    private val entries = linkedMapOf<String, Entry>()
    private val removalListeners = mutableListOf<(EnderChestProvider) -> Unit>()

    fun register(owner: Plugin, provider: EnderChestProvider) {
        check(Bukkit.isPrimaryThread())
        require(owner.isEnabled && provider.id.matches(Regex("[a-z][a-z0-9_-]*")))
        require(provider.id !in entries) { "Duplicate Ender Chest provider: ${provider.id}" }
        entries[provider.id] = Entry(owner, provider)
    }

    fun unregister(owner: Plugin, id: String) {
        check(Bukkit.isPrimaryThread())
        val entry = entries[id] ?: return
        require(entry.owner === owner)
        removalListeners.toList().forEach { it(entry.provider) }
        entries.remove(id)
    }

    fun unregisterAll(owner: Plugin) {
        entries.filterValues { it.owner === owner }.keys.toList().forEach { unregister(owner, it) }
    }

    fun providers(): List<EnderChestProvider> {
        check(Bukkit.isPrimaryThread())
        return entries.values.map { it.provider }.sortedByDescending { it.priority }
    }

    fun select(target: UUID): EnderChestProvider? = providers().firstOrNull { it.supports(target) }

    fun onRemoval(listener: (EnderChestProvider) -> Unit) {
        check(Bukkit.isPrimaryThread())
        removalListeners.add(listener)
    }
}