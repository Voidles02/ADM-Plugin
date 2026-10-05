package com.tecnor.adm.inventory

import com.tecnor.adm.api.EnderChestLease
import com.tecnor.adm.api.EnderChestProvider
import org.bukkit.Bukkit
import java.util.UUID

class VanillaEnderChestProvider : EnderChestProvider {
    override val id = "vanilla"
    override val priority = Int.MIN_VALUE
    override val supportsOffline = false
    override fun supports(target: UUID) = true

    override fun open(target: UUID, editable: Boolean): EnderChestLease {
        val player = requireNotNull(Bukkit.getPlayer(target)) { "Offline access unavailable" }
        return object : EnderChestLease {
            override val inventory = player.enderChest
            override fun flush() = Unit
            override fun close() = Unit
        }
    }
}