package com.tecnor.adm.hud

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

interface HudScreen {
    val id: String
    val title: String
    fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton>
    fun opened(manager: HudManager, session: HudSession, viewer: Player) {}
}

data class HudButton(
    val slot: Int,
    val key: String,
    val name: String,
    val material: Material = Material.PAPER,
    val permission: String = "",
    val lore: List<String> = emptyList(),
    val enabled: Boolean = true,
    val head: java.util.UUID? = null,
    val item: ItemStack? = null,
    val click: (HudManager, HudSession, Player, ClickType) -> Unit
)

abstract class PaginatedHudScreen : HudScreen {
    var page = 0
    var pages = 1

    fun navigation(manager: HudManager): List<HudButton> = listOf(
        HudButton(manager.slot("previous", 48), "previous", "Previous", Material.ARROW, enabled = page > 0) { m, s, p, _ ->
            if (page > 0) { page--; m.refresh(s, p); opened(m, s, p) }
        },
        HudButton(manager.slot("page", 49), "page", "Page ${page + 1} of $pages", Material.MAP) { _, _, _, _ -> },
        HudButton(manager.slot("next", 50), "next", "Next", Material.ARROW, enabled = page + 1 < pages) { m, s, p, _ ->
            if (page + 1 < pages) { page++; m.refresh(s, p); opened(m, s, p) }
        }
    )
}