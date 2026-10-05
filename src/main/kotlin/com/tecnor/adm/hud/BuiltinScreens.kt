package com.tecnor.adm.hud

import com.tecnor.adm.staff.StaffToolsService
import com.tecnor.adm.service.VanishService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.ceil

class HomeScreen : HudScreen {
    override val id = "home"
    override val title = "ADM Control Panel"
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val slots = manager.contentSlots(session).let { if (it.size < HudActions.categories.size) (0..44).toList() else it }
        val entries = HudActions.categories.mapIndexed { index, category ->
            val built = manager.built(category.id)
            val available = built && manager.categoryAvailable(category)
            HudButton(manager.number("categories.${category.id}.slot", slots[index % slots.size]), category.id,
                manager.text("categories.${category.id}.name", category.name), category.material, "adm.hud.category.${category.id}",
                listOf(if (!built) "<yellow>Coming soon</yellow>" else if (available) "<gray>Open ${category.name}</gray>" else "<gray>Module disabled or failed</gray>"), available) { m, s, p, _ ->
                if (m.categoryAvailable(category)) m.navigate(s, p, m.screen(category.id))
            }
        }.toMutableList()
        val free = slots.filter { slot -> entries.none { it.slot == slot } }
        manager.registry.ids().filter { id -> HudActions.categories.none { it.id == id } }.take(free.size).forEachIndexed { index, id ->
            entries += HudButton(free[index], id, id, Material.BOOK) { m, s, p, _ -> m.registry.create(id)?.let { m.navigate(s, p, it) } }
        }
        return entries
    }
}
class CategoryScreen(private val category: String) : PaginatedHudScreen() {
    override val id = category
    override val title = HudActions.categories.first { it.id == category }.name
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val slots = manager.contentSlots(session)
        val actions = HudActions.actions[category].orEmpty()
        val count = (slots.size - 2).coerceAtLeast(1)
        pages = ceil(actions.size.toDouble() / count).toInt().coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        val buttons = actions.drop(page * count).take(count).mapIndexed { index, action -> manager.actionButton(slots[index], action, session, viewer) }.toMutableList()
        buttons += HudButton(slots.last(), "target", "Target: ${manager.escape(session.target.name)}", Material.PLAYER_HEAD, head = session.target.id,
            lore = listOf("<gray>Left: choose player; right: use yourself</gray>")) { m, s, p, click ->
            if (click.isRightClick) { s.target = HudTarget(p.uniqueId, p.name); m.refresh(s, p) } else m.navigate(s, p, PlayerSelectorScreen())
        }
        if (category == "vanish") buttons += HudButton(slots[slots.size - 2], "staff-kit", "Staff hotbar tools", Material.BLAZE_ROD,
            lore = listOf("<gray>0: Ender pearl — random teleport</gray>", "<gray>1: Blaze rod — freeze stick</gray>",
                "<gray>2: Paper — inspect</gray>", "<gray>3: Chest — read-only invsee</gray>")) { _, _, _, _ -> }
        return buttons + navigation(manager)
    }
}
class PlayerSelectorScreen : PaginatedHudScreen() {
    override val id = "players"
    override val title = "Select Player"
    private var search = ""
    private var cachedSearch: String? = null
    private var cachedViewer: UUID? = null
    private var cachedRoster = emptySet<Pair<UUID, String>>()
    private var cachedOrder = emptyList<UUID>()
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val slots = manager.contentSlots(session).dropLast(2)
        val visiblePlayers = Bukkit.getOnlinePlayers().filter { viewer.canSee(it) && it.name.contains(search, true) }
        val roster = visiblePlayers.mapTo(HashSet()) { it.uniqueId to it.name }
        if (cachedSearch != search || cachedViewer != viewer.uniqueId || cachedRoster != roster) {
            cachedSearch = search
            cachedViewer = viewer.uniqueId
            cachedRoster = roster
            cachedOrder = visiblePlayers.sortedBy { it.name.lowercase() }.map { it.uniqueId }
        }
        pages = ceil(cachedOrder.size.toDouble() / slots.size).toInt().coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        val playerIds = cachedOrder.drop(page * slots.size).take(slots.size)
        val players = playerIds.mapNotNull(Bukkit::getPlayer)
        val buttons = players.mapIndexed { index, player ->
            val vanished = (manager.service("vanish") as? VanishService)?.staffLevel(player) != null
            val frozen = (manager.service("staff-tools") as? StaffToolsService)?.isFrozen(player.uniqueId) == true
            val muted = (manager.service("punishments") as? com.tecnor.adm.punishment.PunishmentService)?.isMuted(player.uniqueId) == true
            HudButton(slots[index], "player-head", manager.escape(player.name), Material.PLAYER_HEAD, head = player.uniqueId,
                lore = listOf("<gray>Ping: ${player.ping}ms; gamemode: ${player.gameMode}</gray>", "<gray>World: ${manager.escape(player.world.name)}</gray>",
                    "<gray>Vanished: $vanished; frozen: $frozen</gray>", "<gray>Muted: $muted</gray>")) { m, s, p, _ ->
                s.target = HudTarget(player.uniqueId, player.name)
                m.navigate(s, p, s.stack.removeLastOrNull() ?: HomeScreen(), false)
            }
        }.toMutableList()
        val allSlots = manager.contentSlots(session)
        buttons += HudButton(allSlots[allSlots.size - 2], "search", "Search: ${manager.escape(search)}", Material.NAME_TAG) { m, s, p, _ -> m.ask(s, p) { search = it.trim().take(32); page = 0 } }
        buttons += HudButton(allSlots.last(), "offline-target", "Stored name or UUID", Material.WRITABLE_BOOK,
            lore = listOf("<gray>For offline-capable actions</gray>")) { m, s, p, _ ->
            m.ask(s, p) { input ->
                val future = m.storage.resolve(input.trim())
                m.watch(s, future) { target, error ->
                    if (error == null && target != null) { s.target = HudTarget(target.id, target.name); m.navigate(s, p, s.stack.removeLastOrNull() ?: HomeScreen(), false) }
                    else m.messages.send(com.tecnor.adm.api.CommandActor.from(p), com.tecnor.adm.api.ActionResult.failure("command.player-not-found", mapOf("target" to input)))
                }
            }
        }
        return buttons + navigation(manager)
    }
}
class ConfirmScreen(private val action: HudAction, private val arguments: String, private val target: HudTarget) : HudScreen {
    override val id = "confirm"
    override val title = "Confirm ${action.command}"
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val parts = arguments.trim().split(Regex("\\s+"))
        val reason = when (action.command) {
            "tempban", "tempmute" -> parts.drop(2).joinToString(" ")
            "ban", "mute", "warn", "kick", "ipban", "sudo" -> parts.drop(1).joinToString(" ")
            else -> "not applicable"
        }
        val duration = when (action.command) {
            "tempban", "tempmute" -> parts.getOrNull(1) ?: "unspecified"
            "ban", "mute", "ipban" -> "permanent"
            else -> "not applicable"
        }
        val targetName = if (action.command == "tpall") "all online players" else if (action.target) target.name else viewer.name
        val health = manager.statusChecker.action(action, viewer)
        return listOf(
        HudButton(13, "confirmation-details", "Confirm ${action.command}", Material.PAPER,
            lore = listOf("<gray>Action: ${action.command}</gray>", "<gray>Target: ${manager.escape(targetName)}</gray>",
                "<gray>Reason / payload: ${manager.escape(reason)}</gray>", "<gray>Duration: ${manager.escape(duration)}</gray>",
                "<gray>Arguments: ${manager.escape(arguments.ifEmpty { "none" })}</gray>", "<gray>Syntax: ${manager.escape(action.syntax)}</gray>",
                manager.text("tooltips.destructive", "<red>This action changes server or player state.</red>"))) { _, _, _, _ -> },
        HudButton(29, "confirm", "<${health.color}>Confirm</${health.color}>", health.material, action.node, health.lore()) { m, s, p, _ -> s.target = target; m.runAction(s, p, action, arguments) },
        HudButton(33, "cancel", "<red>Cancel</red>", Material.RED_CONCRETE) { m, s, p, _ -> m.navigate(s, p, s.stack.removeLastOrNull() ?: HomeScreen(), false) }
        )
    }
}
class SettingsScreen : HudScreen {
    override val id = "settings"
    override val title = "HUD Preferences"
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player) = listOf(
        HudButton(10, "sounds", "Sounds: ${if (session.preferences.sounds) "ON" else "OFF"}", Material.NOTE_BLOCK) { m, s, p, _ -> s.preferences.sounds = !s.preferences.sounds; m.savePreferences(s, p); m.refresh(s, p) },
        HudButton(12, "confirmations", "Optional confirmations: ${if (session.preferences.confirmations) "ON" else "OFF"}", Material.LIME_DYE,
            lore = listOf("<gray>Destructive confirmations cannot be disabled</gray>")) { m, s, p, _ -> s.preferences.confirmations = !s.preferences.confirmations; m.savePreferences(s, p); m.refresh(s, p) },
        HudButton(14, "compact", "Compact: ${if (session.preferences.compact) "ON" else "OFF"}", Material.CHEST) { m, s, p, _ -> s.preferences.compact = !s.preferences.compact; m.savePreferences(s, p); m.refresh(s, p) }
    )
}