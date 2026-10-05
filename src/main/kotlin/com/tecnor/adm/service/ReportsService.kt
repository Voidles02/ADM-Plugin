package com.tecnor.adm.service

import com.tecnor.adm.api.*
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.inventory.StaffPageHolder
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.storage.query
import com.tecnor.adm.storage.transaction
import com.tecnor.adm.storage.update
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

class ReportsService(plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
                     storage: Storage, private val settings: ConfigService,
                     private val messages: MessageService) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "reports"
    private val storage = ScopedStorage(storage, id)
    override val commands = listOf(CommandSpec("report"), CommandSpec("reports"))
    data class Report(val id: Long, val reporter: String, val target: UUID, val name: String,
                              val reason: String, val created: Long, val status: String, val claimant: String?)
    private val cooldowns = mutableMapOf<UUID, Long>()
    private val pendingReports = mutableSetOf<UUID>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val scheduler = SchedulerHelper(plugin)
    fun details(actor: CommandActor, report: Long): java.util.concurrent.CompletableFuture<Report?> {
        if (!modules.isEnabled(id) || !actor.hasPermission("adm.mod.reports"))
            return java.util.concurrent.CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("command.no-permission")))
        return storage.submit { db -> db.query("SELECT * FROM reports WHERE id=?", report) {
            Report(it.getLong("id"), it.getString("reporter_name"), UUID.fromString(it.getString("target")), it.getString("target_name"),
                it.getString("reason"), it.getLong("created"), it.getString("status"), it.getString("claimed_by"))
        }.firstOrNull() }
    }
    override fun suggestions(command: String, completed: List<String>) = if (command == "report" && completed.isEmpty()) names() else emptyList()
    override fun disable() {
        Bukkit.getOnlinePlayers().filter { (it.openInventory.topInventory.holder as? StaffPageHolder)?.kind == "reports" }.forEach { it.closeInventory() }
        requests.clear()
        cooldowns.clear()
    }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, command, if (command == "report") "adm.report" else "adm.mod.reports")?.let { return it }
        if (storage.health.state != "CONNECTED") return ActionResult.failure("storage.unavailable")
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        val args = words(arguments)
        if (command == "reports") {
            val page = args.firstOrNull()?.toIntOrNull() ?: if (args.isEmpty()) 1 else 0
            if (args.size > 1 || page !in 1..1_000_000) return usage("/reports [page]")
            open(player, page)
            return ActionResult.success("storage.working")
        }
        if (args.size < 2) return usage("/report <player> <reason>")
        val id = player.uniqueId
        val now = System.currentTimeMillis()
        cooldowns.entries.removeIf { it.value <= now }
        val remaining = (cooldowns[id] ?: 0) - now
        if (remaining > 0) return ActionResult.failure("command.cooldown", mapOf("seconds" to ceil(remaining / 1000.0).toLong().toString()))
        if (!pendingReports.add(id)) return ActionResult.failure("staff.busy")
        val reason = args.drop(1).joinToString(" ").take(2000)
        storage.resolve(args[0]).thenCompose { target -> storage.submit { db ->
            if (target != null) db.update("INSERT INTO reports(reporter,reporter_name,target,target_name,reason,created) VALUES(?,?,?,?,?,?)",
                id.toString(), actor.name(), target.id.toString(), target.name, reason, System.currentTimeMillis())
            target
        } }.whenComplete { target, error -> scheduler.main(Runnable {
            pendingReports.remove(id)
            if (error != null) {
                messages.send(actor, ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            if (target == null) {
                messages.send(actor, ActionResult.failure("command.player-not-found", mapOf("target" to args[0])))
                return@Runnable
            }
            cooldowns[id] = System.currentTimeMillis() + settings.current().integer("reports.cooldown-seconds", 60).coerceAtLeast(0) * 1000L
            messages.send(actor, ActionResult.success("reports.submitted", mapOf("target" to target.name)))
            Bukkit.getOnlinePlayers().filter { it.hasPermission("adm.mod.notify") }.forEach {
                it.sendMessage(messages.render(ActionResult.success("reports.notify", mapOf("staff" to actor.name(), "target" to target.name, "reason" to reason))))
            }
            audit(actor, target.id, target.name, "REPORT", reason)
        }) }
        return ActionResult.success("storage.working")
    }

    private fun open(player: Player, requested: Int) {
        val id = player.uniqueId
        val ticket = UUID.randomUUID()
        requests[id] = ticket
        page(CommandActor.from(player), requested, 45).whenComplete { result, error -> scheduler.main(Runnable {
            if (requests[id] != ticket || !player.isOnline || !modules.isEnabled(this.id)) return@Runnable
            if (error != null) {
                messages.send(CommandActor.from(player), ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            if (!player.hasPermission("adm.mod.reports")) return@Runnable
            val holder = StaffPageHolder("reports", id, result.page, result.pages, result.rows.map { it.id })
            val inventory = holder.create(messages.render(ActionResult.success("reports.title", mapOf("page" to result.page.toString(), "pages" to result.pages.toString()))))
            result.rows.forEachIndexed { index, report ->
                inventory.setItem(index, ItemStack(when (report.status) {
                    "OPEN" -> Material.PAPER
                    "CLAIMED" -> Material.YELLOW_WOOL
                    else -> Material.GRAY_WOOL
                }).also { item ->
                    item.editMeta { meta ->
                        meta.displayName(messages.render(ActionResult.success("reports.entry", mapOf("id" to report.id.toString(), "target" to report.name, "status" to report.status))))
                        meta.lore(listOf("reports.reason", "reports.reporter", "reports.claimant", "reports.time", "reports.controls").map { key ->
                            messages.render(ActionResult.success(key, mapOf("reason" to report.reason, "staff" to report.reporter,
                                "claimant" to (report.claimant ?: "none"), "time" to Instant.ofEpochMilli(report.created).toString())))
                        })
                    }
                })
            }
            inventory.setItem(45, navigation("gui.previous"))
            inventory.setItem(53, navigation("gui.next"))
            player.openInventory(inventory)
        }) }
    }

    fun page(actor: CommandActor, requested: Int, size: Int): java.util.concurrent.CompletableFuture<ServicePage<Report>> {
        if (!modules.isEnabled(id) || !actor.hasPermission("adm.mod.reports"))
            return java.util.concurrent.CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("command.no-permission")))
        val limit = size.coerceIn(1, 45)
        return storage.submit { db -> db.transaction {
            val count = db.query("SELECT COUNT(*) FROM reports") { it.getInt(1) }.first()
            val pages = ((count + limit - 1) / limit).coerceAtLeast(1)
            val page = requested.coerceIn(1, pages)
            val rows = db.query("SELECT * FROM reports ORDER BY created DESC,id DESC LIMIT ? OFFSET ?", limit, (page - 1) * limit) {
                Report(it.getLong("id"), it.getString("reporter_name"), UUID.fromString(it.getString("target")), it.getString("target_name"),
                    it.getString("reason"), it.getLong("created"), it.getString("status"), it.getString("claimed_by"))
            }
            ServicePage(page, pages, rows)
        } }
    }

    private fun navigation(key: String) = ItemStack(Material.ARROW).also { item -> item.editMeta { it.displayName(messages.render(ActionResult.success(key))) } }

    @EventHandler fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? StaffPageHolder ?: return
        if (holder.kind != "reports") return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.viewer != player.uniqueId || !player.hasPermission("adm.mod.reports")) return
        if (event.rawSlot == 45) {
            open(player, (holder.page - 1).coerceAtLeast(1))
            return
        }
        if (event.rawSlot == 53) {
            open(player, (holder.page + 1).coerceAtMost(holder.pages))
            return
        }
        val report = holder.ids.getOrNull(event.rawSlot) ?: return
        val action = when {
            event.isShiftClick && event.isLeftClick -> "TP"
            event.isLeftClick -> "CLAIM"
            event.isRightClick -> "CLOSE"
            else -> return
        }
        val node = when (action) {
            "TP" -> "adm.mod.tp"
            "CLAIM" -> "adm.mod.reports.claim"
            else -> "adm.mod.reports.close"
        }
        if (!player.hasPermission(node)) {
            messages.send(CommandActor.from(player), ActionResult.failure("command.no-permission"))
            return
        }
        val actor = CommandActor.from(player)
        act(actor, report, action).whenComplete { result, error -> scheduler.main(Runnable {
            if (error == null) messages.send(actor, result)
            if (player.isOnline && player.openInventory.topInventory.holder === holder && action != "TP") open(player, holder.page)
        }) }
    }

    fun act(actor: CommandActor, report: Long, action: String, source: String = "COMMAND"): java.util.concurrent.CompletableFuture<ActionResult> {
        require(action in listOf("CLAIM", "CLOSE", "TP"))
        val node = when (action) { "CLAIM" -> "adm.mod.reports.claim"; "CLOSE" -> "adm.mod.reports.close"; else -> "adm.mod.tp" }
        val denied = check(actor, "reports", "adm.mod.reports") ?: permissions.check(actor, node)
        if (denied != null) return java.util.concurrent.CompletableFuture.completedFuture(denied)
        val output = java.util.concurrent.CompletableFuture<ActionResult>()
        storage.submit { db -> db.transaction {
            val target = db.query("SELECT target,target_name FROM reports WHERE id=?", report) { UUID.fromString(it.getString(1)) to it.getString(2) }.firstOrNull()
            val changed = when (action) {
                "CLAIM" -> db.update("UPDATE reports SET status='CLAIMED',claimed_by=? WHERE id=? AND status='OPEN'", actor.name(), report)
                "CLOSE" -> db.update("UPDATE reports SET status='CLOSED',closed_by=? WHERE id=? AND status<>'CLOSED'", actor.name(), report)
                else -> 1
            }
            target to changed
        } }.whenComplete { result, error -> scheduler.main(Runnable {
            if (error != null) {
                output.complete(ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            val target = result.first
            if (target == null) { output.complete(ActionResult.failure("hud.report-missing")); return@Runnable }
            if (action == "TP") {
                val online = Bukkit.getPlayer(target.first)
                val teleport = modules.features().firstOrNull { it.id() == "teleport" }?.service
                val response = if (online == null) ActionResult.failure("punishment.online-only") else
                    teleport?.execute(actor, "tp", online.name) ?: ActionResult.failure("command.module-unavailable", mapOf("module" to "teleport"))
                if (response.success()) audit(actor, target.first, target.second, "REPORT_TP", "report=$report", source)
                output.complete(response)
            } else {
                if (result.second > 0) audit(actor, target.first, target.second, "REPORT_$action", "report=$report", source)
                output.complete(ActionResult.success("hud.report-result", mapOf("action" to action, "count" to result.second.toString())))
            }
        }) }
        return output
    }

    @EventHandler fun drag(event: InventoryDragEvent) {
        if ((event.view.topInventory.holder as? StaffPageHolder)?.kind == "reports") event.isCancelled = true
    }
    @EventHandler fun quit(event: PlayerQuitEvent) { requests.remove(event.player.uniqueId) }

    private fun audit(actor: CommandActor, target: UUID, name: String, action: String, details: String, source: String = "COMMAND") {
        plugin.server.servicesManager.load(AuditSink::class.java)?.record(AuditEvent(Instant.now(), actor.playerId() ?: UUID(0, 0),
            actor.name(), target, name, "REPORT", action, mapOf("details" to details, "source" to source)))
    }
}