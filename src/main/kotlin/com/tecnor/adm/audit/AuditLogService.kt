package com.tecnor.adm.audit

import com.tecnor.adm.api.*
import com.tecnor.adm.service.ServiceSupport
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
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID

class AuditLogService(plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
                      storage: Storage, private val settings: ConfigService,
                      private val messages: MessageService) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "audit"
    private val storage = ScopedStorage(storage, id)
    override val commands = emptyList<CommandSpec>()
    private val scheduler = SchedulerHelper(plugin)
    private val requests = mutableMapOf<UUID, UUID>()
    data class Entry(val id: Long, val staff: String, val target: String, val action: String,
                             val details: String, val time: Long, val source: String)
    override fun suggestions(command: String, completed: List<String>) = emptyList<String>()
    override fun enable() { if (settings.current().value("audit.prune-on-start") != false) prune() }
    override fun disable() {
        Bukkit.getOnlinePlayers().filter { (it.openInventory.topInventory.holder as? StaffPageHolder)?.kind == "audit" }.forEach { it.closeInventory() }
        requests.clear()
    }

    private fun prune() = storage.submit { db -> db.transaction {
        val now = System.currentTimeMillis()
        val auditBefore = now - settings.current().integer("audit.retention-days", 90).coerceAtLeast(1) * 86_400_000L
        val reportsBefore = now - settings.current().integer("reports.retention-days", 90).coerceAtLeast(1) * 86_400_000L
        val count = db.update("DELETE FROM audit WHERE created<?", auditBefore)
        db.update("DELETE FROM reports WHERE status='CLOSED' AND created<?", reportsBefore)
        count
    } }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        check(actor, command, "adm.admin.$command")?.let { return it }
        if (storage.health.state != "CONNECTED") return ActionResult.failure("storage.unavailable")
        if (command == "cleanup") {
            if (arguments.isNotBlank()) return usage("/adm cleanup")
            prune().whenComplete { count, error -> scheduler.main(Runnable {
                messages.send(actor, if (error != null) ActionResult.failure("storage.unavailable") else ActionResult.success("audit.cleaned", mapOf("count" to count.toString())))
            }) }
            return ActionResult.success("storage.working")
        }
        val args = words(arguments).toMutableList()
        val page = if (args.lastOrNull()?.toIntOrNull() != null) args.removeLast().toInt() else 1
        if (args.size > 3 || page !in 1..1_000_000) return usage("/adm log [staff|*] [target|*] [action|*] [page]")
        val filters = List(3) { args.getOrNull(it) ?: "*" }
        open(actor, filters, page)
        return ActionResult.success("storage.working")
    }

    private fun open(actor: CommandActor, filters: List<String>, requested: Int) {
        val ticket = UUID.randomUUID()
        actor.playerId()?.let { requests[it] = ticket }
        page(actor, filters, requested, 45).whenComplete { result, error -> scheduler.main(Runnable {
            if (!modules.isEnabled(id)) return@Runnable
            if (error != null) {
                messages.send(actor, ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            if (!actor.hasPermission("adm.admin.log")) return@Runnable
            if (actor.isConsole) {
                messages.send(actor, ActionResult.success("storage.page", mapOf("type" to "audit", "target" to filters.joinToString("/"),
                    "page" to result.page.toString(), "pages" to result.pages.toString(), "rows" to result.rows.joinToString("\n") {
                        "#${it.id} ${it.staff} -> ${it.target} ${it.action} ${Instant.ofEpochMilli(it.time)} ${it.source} ${it.details}"
                    }.ifBlank { "No entries" })))
                return@Runnable
            }
            val id = actor.playerId() ?: return@Runnable
            if (requests[id] != ticket) return@Runnable
            val player = Bukkit.getPlayer(id) ?: return@Runnable
            val holder = StaffPageHolder("audit", player.uniqueId, result.page, result.pages, result.rows.map { it.id }, filters)
            val inventory = holder.create(messages.render(ActionResult.success("audit.title", mapOf("page" to result.page.toString(), "pages" to result.pages.toString()))))
            result.rows.forEachIndexed { index, entry -> inventory.setItem(index, ItemStack(Material.PAPER).also { item -> item.editMeta { meta ->
                meta.displayName(messages.render(ActionResult.success("audit.entry", mapOf("id" to entry.id.toString(), "action" to entry.action))))
                meta.lore(listOf(messages.render(ActionResult.success("audit.participants", mapOf("staff" to entry.staff, "target" to entry.target))),
                    messages.render(ActionResult.success("audit.time", mapOf("time" to Instant.ofEpochMilli(entry.time).toString(), "source" to entry.source)))) +
                    entry.details.split('\n').map { net.kyori.adventure.text.Component.text(it) })
            } }) }
            inventory.setItem(45, ItemStack(Material.ARROW).also { item -> item.editMeta { it.displayName(messages.render(ActionResult.success("gui.previous"))) } })
            inventory.setItem(53, ItemStack(Material.ARROW).also { item -> item.editMeta { it.displayName(messages.render(ActionResult.success("gui.next"))) } })
            player.openInventory(inventory)
        }) }
    }

    fun page(actor: CommandActor, filters: List<String>, requested: Int, size: Int): java.util.concurrent.CompletableFuture<ServicePage<Entry>> {
        if (!modules.isEnabled(id) || !actor.hasPermission("adm.admin.log"))
            return java.util.concurrent.CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("command.no-permission")))
        require(filters.size == 3)
        val limit = size.coerceIn(1, 45)
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any?>()
        listOf("staff_name", "target_name", "action").forEachIndexed { index, column ->
            if (filters[index] != "*") {
                conditions.add("$column=? COLLATE NOCASE")
                parameters.add(filters[index])
            }
        }
        val where = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")
        return storage.submit { db -> db.transaction {
            val count = db.query("SELECT COUNT(*) FROM audit WHERE $where", *parameters.toTypedArray()) { it.getInt(1) }.first()
            val pages = ((count + limit - 1) / limit).coerceAtLeast(1)
            val page = requested.coerceIn(1, pages)
            val rows = db.query("SELECT * FROM audit WHERE $where ORDER BY created DESC,id DESC LIMIT ? OFFSET ?", *(parameters + listOf(limit, (page - 1) * limit)).toTypedArray()) {
                Entry(it.getLong("id"), it.getString("staff_name"), it.getString("target_name"), it.getString("action"),
                    it.getString("details"), it.getLong("created"), it.getString("source"))
            }
            ServicePage(page, pages, rows)
        } }
    }

    @EventHandler fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? StaffPageHolder ?: return
        if (holder.kind != "audit") return
        event.isCancelled = true
        if (event.whoClicked.uniqueId != holder.viewer) return
        val actor = CommandActor.from(event.whoClicked)
        if (!actor.hasPermission("adm.admin.log")) return
        if (event.rawSlot == 45) open(actor, holder.filters, (holder.page - 1).coerceAtLeast(1))
        if (event.rawSlot == 53) open(actor, holder.filters, (holder.page + 1).coerceAtMost(holder.pages))
    }
    @EventHandler fun drag(event: InventoryDragEvent) {
        if ((event.view.topInventory.holder as? StaffPageHolder)?.kind == "audit") event.isCancelled = true
    }
    @EventHandler fun quit(event: PlayerQuitEvent) { requests.remove(event.player.uniqueId) }
}