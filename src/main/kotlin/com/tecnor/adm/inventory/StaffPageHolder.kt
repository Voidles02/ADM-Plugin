package com.tecnor.adm.inventory

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.UUID

class StaffPageHolder(val kind: String, val viewer: UUID, val page: Int, val pages: Int,
                      val ids: List<Long>, val filters: List<String> = emptyList()) : InventoryHolder {
    private lateinit var backing: Inventory
    fun create(title: Component): Inventory {
        backing = Bukkit.createInventory(this, 54, title)
        return backing
    }
    override fun getInventory(): Inventory = backing
}