package com.tecnor.adm.punishment

import com.tecnor.adm.api.*
import com.tecnor.adm.service.ServiceSupport
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.punishment.*
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.storage.*
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PunishmentService(plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
                        private val hierarchy: HierarchyService, private val settings: ConfigService,
                        private val messages: MessageService, private val punishments: Punishments)
    : ServiceSupport(plugin, modules, permissions) {
    override val id = "punishments"
    override val commands = listOf("mute", "tempmute", "unmute", "warn", "warnings", "clearwarnings", "kick",
        "ban", "tempban", "ipban", "unban", "history", "alts").map { CommandSpec(it) }
    private val scheduler = SchedulerHelper(plugin)
    private val storage = ScopedStorage(punishments.storage, id)
    override fun disable() = Unit
    override fun suggestions(command: String, completed: List<String>) = if (completed.isEmpty()) names() else emptyList()

    fun isMuted(target: UUID) = punishments.mutes[target]?.active() == true

    fun page(actor: CommandActor, command: String, input: String, requested: Int, size: Int,
             kind: String = "*"): CompletableFuture<ServicePage<String>> {
        require(command in listOf("warnings", "history", "alts"))
        val node = "adm.${if (command == "warnings") "mod" else "admin"}.$command"
        check(actor, command, node)?.let { return CompletableFuture.failedFuture(ServiceDenied(it)) }
        if (storage.health.state != "CONNECTED") return CompletableFuture.failedFuture(ServiceDenied(ActionResult.failure("storage.unavailable")))
        val output = CompletableFuture<ServicePage<String>>()
        storage.resolve(input).whenComplete { target, error -> scheduler.main(Runnable {
            if (output.isCancelled) return@Runnable
            val denied = when {
                error != null -> ActionResult.failure("storage.unavailable")
                !modules.isEnabled(id) -> ActionResult.failure("command.module-unavailable", mapOf("module" to id))
                !actor.hasPermission(node) -> ActionResult.failure("command.no-permission")
                target == null -> ActionResult.failure("command.player-not-found", mapOf("target" to input))
                else -> {
                    val online = Bukkit.getPlayer(target.id)
                    if (online != null) hierarchy.check(actor, online) else offlineHierarchy(actor, target)
                }
            }
            if (denied != null) { output.completeExceptionally(ServiceDenied(denied)); return@Runnable }
            permissions.record(actor, command)
            queryPage(command, target!!, requested, size, kind).whenComplete { result, failure ->
                if (failure == null) output.complete(result) else output.completeExceptionally(failure)
            }
        }) }
        return output
    }

    private fun queryPage(command: String, target: StoredPlayer, requested: Int, size: Int, kind: String = "*") = storage.submit { db -> db.transaction {
        val limit = size.coerceIn(1, 50)
        val parameters = mutableListOf<Any?>(if (command == "alts") target.ip else target.id.toString())
        val table = if (command == "alts") "players" else "punishments"
        val where = when (command) {
            "alts" -> "ip=?"
            "warnings" -> "target=? AND kind='WARN' AND cleared=FALSE"
            else -> if (kind == "*") "target=?" else { parameters.add(kind.uppercase()); "target=? AND kind=?" }
        }
        val count = db.query("SELECT COUNT(*) FROM $table WHERE $where", *parameters.toTypedArray()) { it.getInt(1) }.first()
        val pages = ((count + limit - 1) / limit).coerceAtLeast(1)
        val page = requested.coerceIn(1, pages)
        val order = if (command == "alts") "last_login DESC,uuid" else "id DESC"
        val rows = db.query("SELECT * FROM $table WHERE $where ORDER BY $order LIMIT ? OFFSET ?", *(parameters + listOf(limit, (page - 1) * limit)).toTypedArray()) {
            if (command == "alts") "${it.getString("name")} (${it.getString("uuid")}) last login ${Instant.ofEpochMilli(it.getLong("last_login"))}"
            else {
                val item = it.punishment()
                "#${item.id} ${item.kind} ${item.reason} | ${item.staff} | ${Instant.ofEpochMilli(item.created)} | expires ${item.expires?.let(Instant::ofEpochMilli) ?: "never"} | ${if (item.active()) "active" else "ended"}"
            }
        }
        ServicePage(page, pages, rows)
    } }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        val tier = if (command in listOf("mute", "tempmute", "unmute", "warn", "warnings", "clearwarnings", "kick")) "mod" else "admin"
        check(actor, command, "adm.$tier.$command")?.let { return it }
        if (storage.health.state != "CONNECTED") return ActionResult.failure("storage.unavailable")
        val args = words(arguments)
        if (args.isEmpty()) return usage("/$command <player>${if (command.startsWith("temp")) " <duration>" else ""} [reason/page]")
        val read = command in listOf("warnings", "history", "alts")
        val page = if (read) args.getOrNull(1)?.toIntOrNull() ?: if (args.size == 1) 1 else 0 else 1
        if (read && (page !in 1..1_000_000 || args.size > 2)) return usage("/$command <player> [page]")
        if (command in listOf("unmute", "unban", "clearwarnings") && args.size != 1) return usage("/$command <player|ip>")
        val duration = if (command.startsWith("temp")) args.getOrNull(1)?.let(::durationMillis)
            ?: return ActionResult.failure("punishment.duration") else null
        val reason = args.drop(if (duration != null) 2 else 1).joinToString(" ").ifBlank {
            settings.current().value("punishments.default-reason") as? String ?: "No reason specified"
        }.take(2000)
        val ip = if (command in listOf("ipban", "unban")) literalIp(args[0]) else null
        val future = if (ip == null) storage.resolve(args[0]).thenCompose { target -> storage.submit { db ->
            TargetLookup(if (target == null) emptyList() else if (command in listOf("ipban", "unban"))
                listOf(target) + db.query("SELECT * FROM players WHERE ip=? AND uuid<>? LIMIT ?", target.ip, target.id.toString(), MAX_IP_TARGETS + 1) { it.storedPlayer() }
            else listOf(target))
        } }
            else storage.submit { db ->
                val count = db.query("SELECT COUNT(*) FROM players WHERE ip=?", ip) { it.getInt(1) }.first()
                if (count > MAX_IP_TARGETS) TargetLookup(emptyList(), true)
                else TargetLookup(db.query("SELECT * FROM players WHERE ip=? LIMIT ?", ip, MAX_IP_TARGETS + 1) { it.storedPlayer() })
            }
        future.whenComplete { lookup, error -> scheduler.main(Runnable {
            if (!modules.isEnabled(id)) return@Runnable
            if (error != null) {
                messages.send(actor, ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            if (lookup.tooMany) {
                messages.send(actor, ActionResult.failure("punishment.ip-target-limit", mapOf("limit" to MAX_IP_TARGETS.toString())))
                return@Runnable
            }
            val targets = lookup.players
            if (ip == null && targets.isEmpty()) {
                messages.send(actor, ActionResult.failure("command.player-not-found", mapOf("target" to args[0])))
                return@Runnable
            }
            val allTargets = targets.toMutableList()
            if (command in listOf("ipban", "unban") && allTargets.size > MAX_IP_TARGETS) {
                messages.send(actor, ActionResult.failure("punishment.ip-target-limit", mapOf("limit" to MAX_IP_TARGETS.toString())))
                return@Runnable
            }
            val affectedIp = ip ?: targets.firstOrNull()?.ip?.takeIf { command in listOf("ipban", "unban") }
            if (affectedIp != null) {
                Bukkit.getOnlinePlayers().filter { it.address?.address?.hostAddress == affectedIp && targets.none { target -> target.id == it.uniqueId } }
                    .forEach { allTargets.add(StoredPlayer(it.uniqueId, it.name, affectedIp, 0, 0, hierarchy.rank(it), it.hasPermission("adm.immune"))) }
            }
            for (target in allTargets) {
                val online = Bukkit.getPlayer(target.id)
                val denied = if (online != null) hierarchy.check(actor, online) else offlineHierarchy(actor, target)
                if (denied != null) {
                    messages.send(actor, denied)
                    return@Runnable
                }
            }
            val target = allTargets.firstOrNull() ?: StoredPlayer(UUID(0, 0), ip!!, ip, 0, 0, 0, false)
            if (read) readPage(actor, command, target, page) else change(actor, command, target, ip, reason, duration)
        }) }
        permissions.record(actor, command)
        return ActionResult.success("storage.working")
    }

    private fun offlineHierarchy(actor: CommandActor, target: StoredPlayer): ActionResult? {
        if (actor.isConsole || actor.playerId() == target.id || actor.hasPermission("adm.bypass.hierarchy")) return null
        val staff = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        val rank = hierarchy.provider?.metadata(target.id)?.weight ?: target.rank
        return if (target.immune || hierarchy.rank(staff) <= rank) ActionResult.failure("command.hierarchy", mapOf("target" to target.name)) else null
    }

    private fun change(actor: CommandActor, command: String, target: StoredPlayer, ip: String?, reason: String, duration: Long?) {
        if (command == "kick" && Bukkit.getPlayer(target.id) == null) {
            messages.send(actor, ActionResult.failure("punishment.online-only"))
            return
        }
        if (command in listOf("unmute", "unban", "clearwarnings")) {
            val now = System.currentTimeMillis()
            val operation = storage.submit { db -> db.transaction {
                when (command) {
                    "clearwarnings" -> db.update("UPDATE punishments SET cleared=TRUE WHERE target=? AND kind='WARN'", target.id.toString())
                    "unmute" -> db.update("UPDATE punishments SET revoked=? WHERE target=? AND kind='MUTE' AND revoked IS NULL", now, target.id.toString())
                    else -> if (ip != null) db.update("UPDATE punishments SET revoked=? WHERE ip=? AND kind='IPBAN' AND revoked IS NULL", now, ip)
                        else db.update("UPDATE punishments SET revoked=? WHERE (target=? AND kind='BAN' OR ip=? AND kind='IPBAN') AND revoked IS NULL", now, target.id.toString(), target.ip)
                }
            } }
            finish(actor, target, command, reason, operation) {
                if (command == "unmute") punishments.mutes.remove(target.id)
            }
            return
        }
        val kind = when (command) {
            "mute", "tempmute" -> "MUTE"
            "ban", "tempban" -> "BAN"
            else -> command.uppercase()
        }
        val escalations = (settings.current().value("punishments.escalation") as? List<*>)?.mapNotNull { value ->
            val rule = value as? Map<*, *> ?: return@mapNotNull null
            val count = (rule["warnings"] as? Number)?.toInt() ?: return@mapNotNull null
            val action = when (rule["action"]) {
                "tempmute" -> "MUTE"
                "tempban" -> "BAN"
                else -> return@mapNotNull null
            }
            Escalation(count, action, durationMillis(rule["duration"] as? String ?: "") ?: return@mapNotNull null)
        } ?: emptyList()
        punishments.apply(target, actor, kind, reason, duration, escalations).whenComplete { actions, error -> scheduler.main(Runnable {
            if (error != null) {
                messages.send(actor, ActionResult.failure("storage.unavailable"))
                return@Runnable
            }
            actions.forEach { action ->
                audit(actor, target, action.kind, action.reason)
                if (action.kind == "MUTE" && Bukkit.getPlayer(target.id) != null) punishments.mutes[target.id] = action
                if (action.kind in listOf("BAN", "KICK")) Bukkit.getPlayer(target.id)?.kick(messages.render(ActionResult.success("punishment.disconnect",
                    mapOf("action" to action.kind, "reason" to action.reason, "expires" to (action.expires?.let(Instant::ofEpochMilli)?.toString() ?: "permanent")))))
                if (action.kind == "IPBAN") Bukkit.getOnlinePlayers().filter { it.address?.address?.hostAddress == target.ip }
                    .toList().forEach { it.kick(messages.render(ActionResult.success("punishment.disconnect", mapOf("action" to "IPBAN", "reason" to reason, "expires" to "permanent")))) }
                if (action.kind == "WARN") Bukkit.getPlayer(target.id)?.sendMessage(messages.render(ActionResult.success("punishment.warned", mapOf("reason" to reason))))
            }
            messages.send(actor, ActionResult.success("punishment.success", mapOf("action" to command, "target" to target.name)))
        }) }
    }

    private fun <T> finish(actor: CommandActor, target: StoredPlayer, action: String, reason: String,
                           future: CompletableFuture<T>, applied: () -> Unit) {
        future.whenComplete { _, error -> scheduler.main(Runnable {
            if (error != null) messages.send(actor, ActionResult.failure("storage.unavailable")) else {
                applied()
                audit(actor, target, action, reason)
                messages.send(actor, ActionResult.success("punishment.success", mapOf("action" to action, "target" to target.name)))
            }
        }) }
    }

    private fun readPage(actor: CommandActor, command: String, target: StoredPlayer, page: Int) {
        val limit = settings.current().integer("punishments.page-size", 10).coerceIn(1, 50)
        queryPage(command, target, page, limit).whenComplete { result, error -> scheduler.main(Runnable {
            messages.send(actor, if (error != null) ActionResult.failure("storage.unavailable") else
                ActionResult.success("storage.page", mapOf("type" to command, "target" to target.name, "page" to result.page.toString(), "pages" to result.pages.toString(), "rows" to result.rows.joinToString("\n").ifBlank { "No entries" })))
        }) }
    }

    private fun audit(actor: CommandActor, target: StoredPlayer, action: String, reason: String) {
        plugin.server.servicesManager.load(AuditSink::class.java)?.record(AuditEvent(Instant.now(), actor.playerId() ?: UUID(0, 0),
            actor.name(), target.id, target.name, "PUNISHMENT", action, mapOf("reason" to reason, "source" to "COMMAND")))
    }

    private fun literalIp(input: String): String? {
        if (!input.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && !(input.contains(':') && input.matches(Regex("[0-9a-fA-F:.]+")))) return null
        return runCatching { InetAddress.getByName(input).hostAddress }.getOrNull()
    }

    private data class TargetLookup(val players: List<StoredPlayer>, val tooMany: Boolean = false)

    private companion object {
        const val MAX_IP_TARGETS = 5000
    }
}