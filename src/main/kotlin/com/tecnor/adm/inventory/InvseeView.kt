package com.tecnor.adm.inventory

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component

/** The GUI is a projection, never storage. Every edit touches the live target synchronously. */
class InvseeView(private val staff: Player, val target: Player, title: Component) {
    val inventory = Bukkit.createInventory(null, 45, title)

    fun refresh() {
        val live = target.inventory
        for (slot in 0 until 41) {
            val item = live.getItem(slot)?.takeUnless { it.type.isAir }
            val current = inventory.getItem(slot)?.takeUnless { it.type.isAir }
            if (item != current) inventory.setItem(slot, item?.clone())
        }
    }

    fun click(event: InventoryClickEvent) {
        val raw = event.rawSlot
        val top = raw in 0 until inventory.size
        if (top || event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY || event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
        } else return
        if (target.uniqueId == staff.uniqueId && (!top || event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY ||
                event.action == InventoryAction.COLLECT_TO_CURSOR)) return
        if (raw < 0 || (top && raw >= 41)) return
        val slot = if (top) raw else event.view.convertSlot(raw)
        val source = if (top) target.inventory else staff.inventory
        val item = source.getItem(slot)?.clone()?.takeUnless { it.type.isAir }
        if (top && item != inventory.getItem(slot)?.takeUnless { it.type.isAir }) { refresh(); return }
        val cursor = staff.itemOnCursor.clone().takeUnless { it.type.isAir }
        when (event.action) {
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE -> {
                if (item == null || (cursor != null && !cursor.isSimilar(item))) return
                val requested = when (event.action) {
                    InventoryAction.PICKUP_HALF -> (item.amount + 1) / 2
                    InventoryAction.PICKUP_ONE -> 1
                    else -> item.amount
                }
                val count = minOf(requested, item.maxStackSize - (cursor?.amount ?: 0))
                if (count <= 0) return
                source.setItem(slot, remainder(item, item.amount - count))
                staff.setItemOnCursor(item.clone().apply { amount = (cursor?.amount ?: 0) + count })
            }
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE -> {
                if (cursor == null || (item != null && !item.isSimilar(cursor))) return
                val count = minOf(if (event.action == InventoryAction.PLACE_ONE) 1 else cursor.amount,
                    minOf(cursor.maxStackSize, source.maxStackSize) - (item?.amount ?: 0))
                if (count <= 0) return
                source.setItem(slot, cursor.clone().apply { amount = (item?.amount ?: 0) + count })
                staff.setItemOnCursor(remainder(cursor, cursor.amount - count))
            }
            InventoryAction.SWAP_WITH_CURSOR -> {
                if (cursor != null && cursor.amount > minOf(cursor.maxStackSize, source.maxStackSize)) return
                source.setItem(slot, cursor)
                staff.setItemOnCursor(item)
            }
            InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                if (item == null) return
                val destination = if (top) staff.inventory else target.inventory
                val leftover = destination.addItem(item.clone()).values.sumOf { it.amount }
                source.setItem(slot, remainder(item, leftover))
            }
            InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD -> {
                val hotbar = if (event.hotbarButton in 0..8) event.hotbarButton else 40
                val replacement = staff.inventory.getItem(hotbar)?.clone()
                source.setItem(slot, replacement)
                staff.inventory.setItem(hotbar, item)
            }
            InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT -> {
                if (item == null) return
                val count = if (event.action == InventoryAction.DROP_ONE_SLOT) 1 else item.amount
                source.setItem(slot, remainder(item, item.amount - count))
                staff.world.dropItemNaturally(staff.location, item.clone().apply { amount = count })
            }
            InventoryAction.COLLECT_TO_CURSOR -> {
                if (cursor == null) return
                var count = cursor.amount
                for (bag in listOf(target.inventory, staff.inventory)) {
                    val slots = if (bag == target.inventory) 41 else 36
                    for (index in 0 until slots) {
                        val stack = bag.getItem(index) ?: continue
                        if (!cursor.isSimilar(stack)) continue
                        val taken = minOf(stack.amount, cursor.maxStackSize - count)
                        if (taken <= 0) break
                        bag.setItem(index, remainder(stack, stack.amount - taken))
                        count += taken
                    }
                }
                staff.setItemOnCursor(cursor.apply { amount = count })
            }
            else -> Unit
        }
        refresh()
        staff.updateInventory()
        target.updateInventory()
    }

    fun drag(event: InventoryDragEvent): Runnable? {
        if (event.rawSlots.none { it < inventory.size }) return null
        event.isCancelled = true
        val expected = mutableMapOf<Int, ItemStack?>()
        for (raw in event.rawSlots) {
            if (raw < inventory.size && (raw !in 0 until 41 || inventory.getItem(raw) != target.inventory.getItem(raw))) {
                refresh()
                return null
            }
            expected[raw] = if (raw < inventory.size) target.inventory.getItem(raw)?.clone()
                else staff.inventory.getItem(event.view.convertSlot(raw))?.clone()
        }
        val slots = event.rawSlots.associateWith { if (it < inventory.size) it else event.view.convertSlot(it) }
        val replacements = event.newItems.mapValues { it.value.clone() }
        val oldCursor = event.oldCursor.clone()
        val newCursor = event.cursor?.clone()
        // A cancelled drag restores the old cursor after callbacks; apply only after that restoration.
        return Runnable {
            if (!target.isOnline || target.isDead || staff.itemOnCursor != oldCursor) return@Runnable
            for ((raw, previous) in expected) {
                val current = if (raw < inventory.size) target.inventory.getItem(slots.getValue(raw))
                    else staff.inventory.getItem(slots.getValue(raw))
                if (current != previous) { refresh(); return@Runnable }
            }
            for ((raw, item) in replacements) {
                if (raw < inventory.size) target.inventory.setItem(slots.getValue(raw), item.clone())
                else staff.inventory.setItem(slots.getValue(raw), item.clone())
            }
            staff.setItemOnCursor(newCursor)
            refresh()
            staff.updateInventory()
            target.updateInventory()
        }
    }

    private fun remainder(item: ItemStack, amount: Int) = if (amount <= 0) null else item.clone().apply { this.amount = amount }
}