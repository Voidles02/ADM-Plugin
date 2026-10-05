package com.tecnor.adm.hud

import org.bukkit.Material
import org.bukkit.entity.Player

class InputScreen(private val action: HudAction) : HudScreen {
    override val id = "input"
    override val title = "Options: ${action.command}"
    private var payload = ""
    private var duration = "1h"
    private var reason = "No reason specified"
    private var amount = 1
    private var type = "walk"
    private val coordinates = doubleArrayOf(0.0, 64.0, 0.0)
    private var initialized = false
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        if (!initialized) {
            val location = viewer.location
            coordinates[0] = location.x
            coordinates[1] = location.y
            coordinates[2] = location.z
            reason = manager.text("default-reason", "No reason specified")
            initialized = true
        }
        val buttons = mutableListOf<HudButton>()
        fun preset(slot: Int, text: String, permission: String = "", apply: () -> Unit) {
            buttons += HudButton(slot, "preset", manager.escape(text), Material.PAPER, permission) { m, s, p, _ -> apply(); m.refresh(s, p) }
        }
        when (action.command) {
            "gamemode" -> listOf("survival", "creative", "adventure", "spectator").forEachIndexed { i, mode -> preset(10 + i, mode) { payload = mode } }
            "repair" -> listOf("hand", "all").forEachIndexed { i, mode -> preset(10 + i, mode, if (mode == "all") "adm.admin.repair.all" else action.node) { payload = mode } }
            "speed", "slowmode", "vanish" -> {
                buttons += HudButton(10, "decrease", "Decrease: $amount", Material.RED_DYE) { m, s, p, click -> amount = (amount - if (click.isShiftClick) 5 else 1).coerceAtLeast(0); m.refresh(s, p) }
                buttons += HudButton(12, "increase", "Increase: $amount", Material.LIME_DYE) { m, s, p, click -> amount = (amount + if (click.isShiftClick) 5 else 1).coerceAtMost(if (action.command == "speed") 10 else if (action.command == "vanish") 100 else 86400); m.refresh(s, p) }
                if (action.command == "speed") preset(14, "Type: $type") { type = if (type == "walk") "fly" else "walk" }
                if (action.command == "slowmode") preset(14, "Off") { amount = 0 }
                if (action.command == "vanish") (0..3).forEach { level -> preset(18 + level, "Level $level", "adm.vanish.level.$level") { amount = level; payload = "" } }
            }
            "tppos" -> for (axis in 0..2) {
                val letter = listOf("X", "Y", "Z")[axis]
                buttons += HudButton(10 + axis * 3, "coordinate-minus", "$letter - (${coordinates[axis]})", Material.RED_DYE) { m, s, p, click -> coordinates[axis] -= if (click.isShiftClick) 10 else 1; m.refresh(s, p) }
                buttons += HudButton(11 + axis * 3, "coordinate-plus", "$letter + (${coordinates[axis]})", Material.LIME_DYE) { m, s, p, click -> coordinates[axis] += if (click.isShiftClick) 10 else 1; m.refresh(s, p) }
            }
            else -> {
                manager.lines("presets.durations", listOf("5m", "1h", "1d", "7d", "30d", "permanent")).take(6).forEachIndexed { i, text -> preset(9 + i, text) { duration = text } }
                manager.lines("presets.reasons", listOf("Rule violation", "Spam", "Inappropriate behaviour")).take(6).forEachIndexed { i, text -> preset(18 + i, text) { reason = text } }
                buttons += HudButton(25, "duration-less", "Duration -", Material.RED_DYE) { m, s, p, _ -> adjustDuration(-5); m.refresh(s, p) }
                buttons += HudButton(26, "duration-more", "Duration +", Material.LIME_DYE) { m, s, p, _ -> adjustDuration(5); m.refresh(s, p) }
            }
        }
        buttons += HudButton(31, "chat-input", "Enter free text", Material.WRITABLE_BOOK,
            lore = listOf("<gray>Type cancel to cancel; timeout applies.</gray>", "<gray>Message, reason, or exact numeric arguments</gray>")) { m, s, p, _ ->
            m.ask(s, p) { text -> if (action.command in listOf("mute", "tempmute", "warn", "kick", "ban", "tempban", "ipban")) reason = text else payload = text }
        }
        if (action.command in listOf("tempban", "tempmute")) buttons += HudButton(32, "custom-duration", "Enter duration", Material.CLOCK,
            lore = listOf("<gray>Positive minutes/hours/days, or permanent</gray>")) { m, s, p, _ ->
            m.ask(s, p) { text ->
                val entered = text.trim().lowercase()
                if (entered == "permanent" || com.tecnor.adm.punishment.durationMillis(entered) != null) duration = entered
                else m.messages.send(com.tecnor.adm.api.CommandActor.from(p), com.tecnor.adm.api.ActionResult.failure("punishment.duration"))
            }
        }
        val input = when (action.command) {
            "gamemode" -> payload.ifEmpty { "survival" }
            "repair" -> payload.ifEmpty { "hand" }
            "speed" -> payload.ifEmpty { "$amount $type" }
            "slowmode" -> payload.ifEmpty { if (amount == 0) "off" else "$amount" }
            "vanish" -> payload.ifEmpty { "$amount" }
            "tppos" -> payload.ifEmpty { coordinates.joinToString(" ") }
            "tempmute", "tempban" -> "$duration $reason"
            "mute", "warn", "kick", "ban", "ipban" -> reason
            else -> payload
        }
        val actual = if (duration == "permanent" && action.command in listOf("tempmute", "tempban")) action.copy(command = if (action.command == "tempmute") "mute" else "ban", node = if (action.command == "tempmute") "adm.mod.mute" else "adm.admin.ban") else action
        val health = manager.statusChecker.action(actual, viewer)
        buttons += HudButton(40, "apply", "<${health.color}>Apply ${actual.command}</${health.color}>", health.material, actual.node,
            lore = listOf("<gray>Arguments: ${manager.escape(input)}</gray>", "<gray>Duration: ${manager.escape(duration)}; reason: ${manager.escape(reason)}</gray>") + health.lore(), enabled = input.isNotBlank()) { m, s, p, _ ->
            m.submitAction(s, p, actual, if (actual.command != action.command) reason else input)
        }
        return buttons
    }
    private fun adjustDuration(delta: Int) {
        val unit = duration.lastOrNull()
        val amount = duration.dropLast(1).toIntOrNull() ?: 60
        val minutes = when (unit) { 'h' -> amount.toLong() * 60; 'd' -> amount.toLong() * 1440; else -> amount.toLong() }
        duration = "${(minutes + delta).coerceIn(1, 52_560_000)}m"
    }
}