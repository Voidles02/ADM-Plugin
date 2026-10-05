package com.tecnor.adm.api

import org.bukkit.inventory.Inventory
import java.util.UUID

/** All calls run on the server thread. Editable leases must exclude access outside ADM too. */
interface EnderChestProvider {
    val id: String
    val priority: Int get() = 0
    val supportsOffline: Boolean
    fun supports(target: UUID): Boolean
    fun open(target: UUID, editable: Boolean): EnderChestLease
}

/** A lease owns a live inventory or a write-through buffer. flush() must be synchronous and atomic. */
interface EnderChestLease : AutoCloseable {
    val inventory: Inventory
    fun flush()
    override fun close()
}