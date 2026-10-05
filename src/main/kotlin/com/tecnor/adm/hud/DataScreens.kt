package com.tecnor.adm.hud

import com.tecnor.adm.api.*
import com.tecnor.adm.audit.AuditLogService
import com.tecnor.adm.punishment.PunishmentService
import com.tecnor.adm.service.ReportsService
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.Instant
import java.util.concurrent.CompletableFuture

abstract class DataScreen<T> : PaginatedHudScreen() {
    protected var rows = emptyList<T>()
    protected var loading = true
    protected var error = ""
    private var request = 0L
    abstract fun fetch(manager: HudManager, session: HudSession, viewer: Player, size: Int): CompletableFuture<ServicePage<T>>
    abstract fun entry(manager: HudManager, session: HudSession, viewer: Player, slot: Int, row: T): HudButton
    open fun controls(manager: HudManager, session: HudSession, viewer: Player) = emptyList<HudButton>()
    open fun allowed(viewer: Player) = true

    override fun opened(manager: HudManager, session: HudSession, viewer: Player) {
        if (!allowed(viewer)) { manager.close(session.viewer); return }
        val ticket = ++request
        loading = true
        error = ""
        manager.refresh(session, viewer)
        val future = fetch(manager, session, viewer, (manager.contentSlots(session).size - 3).coerceAtLeast(1))
        manager.watch(session, future) { result, failure ->
            if (ticket != request || session.screen !== this || !allowed(viewer)) return@watch
            loading = false
            if (failure != null) {
                val cause = generateSequence(failure) { it.cause }.filterIsInstance<ServiceDenied>().firstOrNull()
                error = cause?.result?.messageKey() ?: "storage.unavailable"
                manager.messages.send(CommandActor.from(viewer), cause?.result ?: ActionResult.failure(error))
                rows = emptyList()
            } else if (result != null) { page = result.page - 1; pages = result.pages; rows = result.rows }
            manager.refresh(session, viewer)
        }
    }
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val slots = manager.contentSlots(session).dropLast(3)
        val buttons = when {
            !allowed(viewer) -> listOf(HudButton(slots.first(), "no-permission", "No permission", Material.GRAY_DYE, enabled = false) { _, _, _, _ -> })
            loading -> listOf(HudButton(slots.first(), "loading", "Loading…", Material.CLOCK, enabled = false) { _, _, _, _ -> })
            error.isNotEmpty() -> listOf(HudButton(slots.first(), "error", "${manager.escape(error)}", Material.BARRIER, enabled = false) { _, _, _, _ -> })
            rows.isEmpty() -> listOf(HudButton(slots.first(), "empty", "No entries", Material.PAPER) { _, _, _, _ -> })
            else -> rows.take(slots.size).mapIndexed { index, row -> entry(manager, session, viewer, slots[index], row) }
        }
        return buttons + controls(manager, session, viewer) + navigation(manager) +
            HudButton(manager.slot("refresh", 51), "refresh", "Refresh", Material.SUNFLOWER) { m, s, p, _ -> opened(m, s, p) }
    }
}

class PunishmentPageScreen(private val action: HudAction, private val target: HudTarget) : DataScreen<String>() {
    override val id = "${action.command}-viewer"
    override val title = "${action.command}: ${target.name}"
    private var kind = "*"
    override fun allowed(viewer: Player) = viewer.hasPermission(action.node)
    override fun fetch(manager: HudManager, session: HudSession, viewer: Player, size: Int): CompletableFuture<ServicePage<String>> {
        val service = manager.service("punishments") as? PunishmentService
            ?: return CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("storage.unavailable")))
        return service.page(CommandActor.from(viewer), action.command, target.id.toString(), page + 1, size, kind)
    }
    override fun entry(manager: HudManager, session: HudSession, viewer: Player, slot: Int, row: String) =
        HudButton(slot, "punishment-row", manager.escape(row.substringBefore(" | ").take(80)), Material.PAPER,
            action.node, row.split(" | ").map { "<gray>${manager.escape(it)}</gray>" }) { _, _, _, _ -> }
    override fun controls(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> = if (action.command != "history") emptyList() else listOf(
        HudButton(manager.contentSlots(session).last(), "history-filter", "Type: $kind", Material.HOPPER,
            lore = listOf("<gray>Left: cycle; right: enter type (* = all)</gray>")) { m, s, p, click ->
            if (click.isRightClick) m.ask(s, p) { kind = it.trim().uppercase().take(32); page = 0 }
            else {
                val types = listOf("*", "BAN", "IPBAN", "MUTE", "WARN", "KICK")
                kind = types[(types.indexOf(kind) + 1) % types.size]; page = 0; opened(m, s, p)
            }
        }
    )
}

class ReportsScreen : DataScreen<ReportsService.Report>() {
    override val id = "reports"
    override val title = "Reports"
    override fun allowed(viewer: Player) = viewer.hasPermission("adm.mod.reports")
    override fun fetch(manager: HudManager, session: HudSession, viewer: Player, size: Int) =
        (manager.service("reports") as? ReportsService)?.page(CommandActor.from(viewer), page + 1, size)
            ?: CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("storage.unavailable")))
    override fun entry(manager: HudManager, session: HudSession, viewer: Player, slot: Int, row: ReportsService.Report) =
        HudButton(slot, "report-row", "#${row.id} ${manager.escape(row.name)} [${row.status}]", when (row.status) {
            "OPEN" -> Material.PAPER; "CLAIMED" -> Material.YELLOW_WOOL; else -> Material.GRAY_WOOL
        }, "adm.mod.reports", listOf("<gray>Reporter: ${manager.escape(row.reporter)}</gray>", "<gray>Reason: ${manager.escape(row.reason)}</gray>",
            "<gray>Claimant: ${manager.escape(row.claimant ?: "none")}</gray>", "<gray>${Instant.ofEpochMilli(row.created)}</gray>",
            "<gray>Left: claim (adm.mod.reports.claim)</gray>", "<gray>Right: close (adm.mod.reports.close)</gray>",
            "<gray>Shift-left: teleport (adm.mod.tp); middle: details</gray>")) { m, s, p, click ->
            if (click == org.bukkit.event.inventory.ClickType.MIDDLE) m.navigate(s, p, ReportDetailsScreen(row))
            else {
                val action = when { click.isShiftClick && click.isLeftClick -> "TP"; click.isRightClick -> "CLOSE"; else -> "CLAIM" }
                act(m, s, p, row, action)
            }
        }

    companion object {
        fun act(manager: HudManager, session: HudSession, viewer: Player, report: ReportsService.Report, action: String) {
            val service = manager.service("reports") as? ReportsService ?: return
            manager.watch(session, service.act(CommandActor.from(viewer), report.id, action, "HUD")) { result, error ->
                manager.messages.send(CommandActor.from(viewer), result ?: ActionResult.failure("storage.unavailable"))
                if (error == null) session.screen.opened(manager, session, viewer)
            }
        }
    }
}
class ReportDetailsScreen(private var report: ReportsService.Report) : HudScreen {
    override val id = "report-details"
    override val title = "Report #${report.id}"
    override fun opened(manager: HudManager, session: HudSession, viewer: Player) {
        val service = manager.service("reports") as? ReportsService ?: return
        manager.watch(session, service.details(CommandActor.from(viewer), report.id)) { current, error ->
            if (session.screen !== this || !viewer.hasPermission("adm.mod.reports")) return@watch
            if (error == null && current != null) { report = current; manager.refresh(session, viewer) }
        }
    }
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player) = listOf(
        HudButton(13, "report-details", "${manager.escape(report.name)} [${report.status}]", Material.PAPER, "adm.mod.reports",
            listOf("<gray>${manager.escape(report.reason)}</gray>", "<gray>Reporter: ${manager.escape(report.reporter)}</gray>",
                "<gray>Claimant: ${manager.escape(report.claimant ?: "none")}</gray>", "<gray>${Instant.ofEpochMilli(report.created)}</gray>")) { _, _, _, _ -> },
        HudButton(29, "report-claim", "Claim", Material.YELLOW_WOOL, "adm.mod.reports.claim") { m, s, p, _ -> ReportsScreen.act(m, s, p, report, "CLAIM") },
        HudButton(31, "report-tp", "Teleport to reported player", Material.ENDER_PEARL, "adm.mod.tp") { m, s, p, _ -> ReportsScreen.act(m, s, p, report, "TP") },
        HudButton(33, "report-close", "Close report", Material.GRAY_WOOL, "adm.mod.reports.close") { m, s, p, _ -> ReportsScreen.act(m, s, p, report, "CLOSE") }
    )
}

class LogsScreen : DataScreen<AuditLogService.Entry>() {
    override val id = "logs"
    override val title = "Logs and Audit"
    private val filters = mutableListOf("*", "*", "*")
    override fun allowed(viewer: Player) = viewer.hasPermission("adm.admin.log")
    override fun fetch(manager: HudManager, session: HudSession, viewer: Player, size: Int) =
        (manager.service("audit") as? AuditLogService)?.page(CommandActor.from(viewer), filters.toList(), page + 1, size)
            ?: CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("storage.unavailable")))
    override fun entry(manager: HudManager, session: HudSession, viewer: Player, slot: Int, row: AuditLogService.Entry) =
        HudButton(slot, "audit-row", "#${row.id} ${manager.escape(row.action)}", Material.PAPER, "adm.admin.log",
            listOf("<gray>${manager.escape(row.staff)} → ${manager.escape(row.target)}</gray>", "<gray>${Instant.ofEpochMilli(row.time)} [${row.source}]</gray>") +
                row.details.split('\n').map { "<gray>${manager.escape(it)}</gray>" }) { _, _, _, _ -> }
    override fun controls(manager: HudManager, session: HudSession, viewer: Player) = List(3) { index ->
        HudButton(manager.contentSlots(session).takeLast(3)[index], "audit-filter-$index", "${listOf("Staff", "Target", "Action")[index]}: ${manager.escape(filters[index])}",
            Material.HOPPER, "adm.admin.log", listOf("<gray>Enter exact value; * removes filter</gray>")) { m, s, p, _ ->
            m.ask(s, p) { filters[index] = it.trim().take(64).ifEmpty { "*" }; page = 0 }
        }
    }
}

class IntegrationsScreen : PaginatedHudScreen() {
    override val id = "integrations"
    override val title = "Integrations"
    override fun buttons(manager: HudManager, session: HudSession, viewer: Player): List<HudButton> {
        val lp = manager.plugin.server.pluginManager.getPlugin("LuckPerms")
        val integrations = listOf(HudButton(0, "luckperms", "LuckPerms: ${if (manager.plugin.luckPermsHooked()) "hooked" else "fallback tiers"}", Material.COMPARATOR,
            lore = listOf("<gray>Version: ${manager.escape(lp?.pluginMeta?.version ?: "absent")}</gray>")) { _, _, _, _ -> }) + manager.providers.providers().map { provider ->
            HudButton(0, "provider", manager.escape(provider.id), Material.ENDER_CHEST,
                lore = listOf("<gray>Priority: ${provider.priority}</gray>", "<gray>Offline: ${provider.supportsOffline}</gray>",
                    "<gray>Read and exclusive editable leases</gray>", "<gray>Accepts target: ${provider.supports(session.target.id)}</gray>")) { _, _, _, _ -> }
        }
        val slots = manager.contentSlots(session)
        pages = ((integrations.size + slots.size - 1) / slots.size).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        return integrations.drop(page * slots.size).take(slots.size).mapIndexed { index, button -> button.copy(slot = slots[index]) } + navigation(manager)
    }
}