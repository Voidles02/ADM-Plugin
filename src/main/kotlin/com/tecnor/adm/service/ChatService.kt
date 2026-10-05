package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import io.papermc.paper.event.player.ChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Global flags and timestamp fields are main-thread confined. The concurrent map is populated on join, removed on quit.
 * Paper's synchronous ChatEvent deliberately avoids accessing a live Player from an async chat callback.
 * Normal chat checks allocate no ADM objects; denial components are pre-rendered on enable/reload/state changes.
 */
@Suppress("DEPRECATION")
class ChatService(
    plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
    private val config: ConfigService, private val messages: MessageService,
    private val hierarchy: HierarchyService
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "chat"
    override val commands = listOf(CommandSpec("announcement", listOf("annoucement"))) +
        listOf("broadcast", "clearchat", "mutechat", "slowmode", "sudo").map { CommandSpec(it) }
    private class Timestamp(var last: Long = 0)
    private val timestamps = ConcurrentHashMap<UUID, Timestamp>()
    private var muted = false
    private var slowSeconds = 0
    private var sudoExecuting = false
    private var muteNotice: Component = Component.empty()
    private var slowNotice: Component = Component.empty()

    fun isMuted() = muted
    fun slowmodeSeconds() = slowSeconds

    override fun enable() {
        Bukkit.getOnlinePlayers().forEach { timestamps[it.uniqueId] = Timestamp() }
        refreshNotices()
    }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        val tier = if (command in listOf("announcement", "broadcast", "sudo")) "admin" else "mod"
        check(actor, command, "adm.$tier.$command")?.let { return it }
        when (command) {
            "announcement" -> {
                if (arguments.isBlank()) return usage("/announcement <message>")
                if (arguments.length > 256) return ActionResult.failure("chat.announcement-too-long")
                val values = mapOf("message" to arguments.trim())
                val title = messages.render(ActionResult.success("chat.announcement-title"))
                val subtitle = messages.render(ActionResult.success("chat.announcement-subtitle", values))
                val display = Title.title(
                    title,
                    subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofMillis(500))
                )
                val announcement = messages.render(ActionResult.success("chat.announcement", values))
                Bukkit.getOnlinePlayers().forEach {
                    it.showTitle(display)
                    it.sendMessage(announcement)
                }
                Bukkit.getConsoleSender().sendMessage(announcement)
                return done(actor, command, "chat.announcement-sent")
            }
            "broadcast" -> {
                if (arguments.isBlank()) return usage("/broadcast <message>")
                announce("chat.broadcast", mapOf("message" to arguments))
                return done(actor, command, "chat.broadcast-sent")
            }
            "clearchat" -> {
                if (arguments.isNotBlank()) return usage("/clearchat")
                val line = messages.render(ActionResult.success("chat.clear-line"))
                repeat(config.current().integer("clearchat.lines", 100)) {
                    Bukkit.getOnlinePlayers().forEach { it.sendMessage(line) }
                }
                announce("chat.cleared", mapOf("actor" to actor.name()))
                return done(actor, command, "chat.clear-success")
            }
            "mutechat" -> {
                if (arguments.isNotBlank()) return usage("/mutechat")
                muted = !muted
                announce(if (muted) "chat.muted" else "chat.unmuted", mapOf("actor" to actor.name()))
                return done(actor, command, "chat.mute-success", mapOf("state" to muted.toString()))
            }
            "slowmode" -> {
                val args = words(arguments)
                if (args.size != 1) return usage("/slowmode <seconds|off>")
                val snapshot = config.current()
                val min = snapshot.integer("slowmode.min-seconds", 1)
                val max = snapshot.integer("slowmode.max-seconds", 300)
                val seconds = if (args[0].equals("off", true)) 0 else args[0].toIntOrNull()?.takeIf { it in min..max }
                    ?: return ActionResult.failure("chat.slow-range", mapOf("min" to min.toString(), "max" to max.toString()))
                slowSeconds = seconds
                timestamps.values.forEach { it.last = 0 }
                refreshNotices()
                announce(if (seconds == 0) "chat.slow-off" else "chat.slow-on", mapOf("actor" to actor.name(), "seconds" to seconds.toString()))
                return done(actor, command, "chat.slow-success", mapOf("seconds" to seconds.toString()))
            }
            "sudo" -> {
                val parts = arguments.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2 || parts[1].isBlank() || parts[1] == "/") return usage("/sudo <player> <message or /command>")
                if (sudoExecuting) return ActionResult.failure("chat.sudo-nested")
                val target = Bukkit.getPlayerExact(parts[0]) ?: return ActionResult.failure("command.player-not-found", mapOf("target" to parts[0]))
                hierarchy.check(actor, target)?.let { return it }
                sudoExecuting = true
                try {
                    plugin.logger.info("${actor.name()} used /sudo on ${target.name}")
                    permissions.record(actor, command)
                    if (parts[1].startsWith('/')) {
                        if (!Bukkit.dispatchCommand(target, parts[1].substring(1))) return ActionResult.failure("chat.sudo-failed")
                    } else target.chat(parts[1])
                } finally {
                    sudoExecuting = false
                }
                return ActionResult.success("chat.sudo-success", mapOf("target" to target.name))
            }
        }
        return usage("/$command")
    }

    private fun announce(key: String, values: Map<String, String>) {
        val component = messages.render(ActionResult.success(key, values))
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(component) }
        Bukkit.getConsoleSender().sendMessage(component)
    }

    private fun refreshNotices() {
        muteNotice = messages.render(ActionResult.failure("chat.mute-denied"))
        slowNotice = messages.render(ActionResult.failure("chat.slow-denied", mapOf("seconds" to slowSeconds.toString())))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun chat(event: ChatEvent) {
        if (!muted && slowSeconds == 0) return
        try {
            val player = event.player
            if (muted && !player.hasPermission("adm.bypass.mutechat")) {
                event.isCancelled = true
                player.sendMessage(muteNotice)
                return
            }
            if (slowSeconds == 0 || player.hasPermission("adm.bypass.slowmode")) return
            val timestamp = timestamps[player.uniqueId] ?: return
            val now = System.nanoTime()
            if (timestamp.last != 0L && now - timestamp.last < slowSeconds * 1_000_000_000L) {
                event.isCancelled = true
                player.sendMessage(slowNotice)
                return
            }
            timestamp.last = now
        } catch (failure: Throwable) { modules.fail(id, failure) }
    }

    @EventHandler
    fun join(event: PlayerJoinEvent) { timestamps[event.player.uniqueId] = Timestamp() }

    @EventHandler
    fun quit(event: PlayerQuitEvent) { timestamps.remove(event.player.uniqueId) }

    override fun suggestions(command: String, completed: List<String>) = when {
        command == "sudo" && completed.isEmpty() -> names()
        command == "slowmode" && completed.isEmpty() -> listOf("off")
        else -> emptyList()
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        if (slowSeconds > 0) slowSeconds = slowSeconds.coerceIn(snapshot.integer("slowmode.min-seconds", 1), snapshot.integer("slowmode.max-seconds", 300))
        refreshNotices()
    }

    override fun disable() {
        muted = false
        slowSeconds = 0
        sudoExecuting = false
        timestamps.clear()
    }
}