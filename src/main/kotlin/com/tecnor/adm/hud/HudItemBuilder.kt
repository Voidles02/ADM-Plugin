package com.tecnor.adm.hud

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

class HudItemBuilder(private val manager: HudManager) {
    private val mini = MiniMessage.miniMessage()
    private val templates = linkedMapOf<String, ItemStack>()

    fun text(value: String): Component = mini.deserialize(value).decoration(TextDecoration.ITALIC, false)

    fun build(button: HudButton, viewer: Player): ItemStack {
        val allowed = button.permission.isEmpty() || viewer.hasPermission(button.permission)
        val name = manager.text("buttons.${button.key}.name", button.name)
        val lore = manager.lines("buttons.${button.key}.lore", button.lore).toMutableList()
        if (lore.isEmpty()) lore += manager.text("tooltips.control", "<gray>Inventory navigation or HUD control</gray>")
        lore += manager.text("tooltips.click", "<gray>Left: activate; right/shift/middle: see action details</gray>")
        if (button.permission.isNotEmpty()) {
            lore += mini.serialize(mini.deserialize(manager.text("tooltips.permission", "<gray>Requires <node>: <state></gray>"),
                Placeholder.unparsed("node", button.permission), Placeholder.unparsed("state", if (allowed) "granted" else "missing")))
        }
        if (!allowed) lore += manager.text("tooltips.no-permission", "<red>No permission</red>")
        if (!button.enabled) lore += manager.text("tooltips.unavailable", "<gray>Unavailable</gray>")
        val material = when {
            !allowed -> manager.material("no-permission.material", Material.GRAY_DYE)
            !button.enabled -> manager.material("unavailable.material", Material.BARRIER)
            else -> manager.material("buttons.${button.key}.material", button.material)
        }
        val model = manager.number("buttons.${button.key}.custom-model-data", 0)
        val key = "$material|$name|$lore|$model|${button.head}|$allowed|${button.item?.hashCode()}"
        templates[key]?.let { return it }
        val item = (button.item?.clone() ?: ItemStack(material)).also { it.type = material }
        val meta = item.itemMeta ?: return item
        meta.displayName(text(if (allowed) name else manager.text("no-permission.name", "<red>No permission</red>")))
        meta.lore(lore.map(::text))
        if (model > 0) meta.setCustomModelData(model)
        if (meta is SkullMeta && button.head != null && allowed) meta.owningPlayer = Bukkit.getOfflinePlayer(button.head)
        item.itemMeta = meta
        if (templates.size >= 128) templates.remove(templates.keys.first())
        templates[key] = item.clone()
        return item
    }

    fun clear() = templates.clear()
}