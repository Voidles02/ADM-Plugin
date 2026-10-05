package com.tecnor.adm.hud

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class HudTarget(val id: UUID, val name: String)
data class HudPreferences(var sounds: Boolean = true, var confirmations: Boolean = true, var compact: Boolean = false)

class HudSession(val viewer: UUID, var screen: HudScreen, var target: HudTarget) {
    val token: UUID = UUID.randomUUID()
    val stack = ArrayDeque<HudScreen>()
    var inventory: Inventory? = null
    var buttons = emptyMap<Int, HudButton>()
    var preferences = HudPreferences()
    var switching = false
    @Volatile var awaitingChat = false
    var timeout: BukkitTask? = null
    var input: ((String) -> Unit)? = null
    var revision = 0L
    var preferenceRevision = 0L
    val tasks = mutableSetOf<BukkitTask>()
    val futures = mutableSetOf<CompletableFuture<*>>()
}

class HudHolder(val viewer: UUID, val token: UUID) : InventoryHolder {
    lateinit var contents: Inventory
    override fun getInventory(): Inventory = contents
}