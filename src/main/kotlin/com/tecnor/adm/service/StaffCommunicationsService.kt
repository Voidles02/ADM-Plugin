package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StaffCommunicationsService(plugin: JavaPlugin, modules: ModuleManager, permissions: PermissionService,
                                 private val config: ConfigService, private val messages: MessageService,
                                 private val hierarchy: HierarchyService) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "staff-chat"
    override val commands = listOf(CommandSpec("staffchat", listOf("sc")), CommandSpec("spy"))
    private val toggled = ConcurrentHashMap.newKeySet<UUID>()
    private val commandSpies = mutableSetOf<UUID>()
    private val socialSpies = mutableSetOf<UUID>()
    private val scheduler = SchedulerHelper(plugin)

    fun isStaffChat(id: UUID) = id in toggled
    fun isSpy(id: UUID, type: String) = id in if (type == "social") socialSpies else commandSpies

    override fun suggestions(command: String, completed: List<String>) =
        if (command == "spy" && completed.isEmpty()) listOf("commands", "social") else emptyList()
    override fun disable() {
        toggled.clear()
        commandSpies.clear()
        socialSpies.clear()
    }

    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        if (command == "staffchat") {
            check(actor, command, "adm.mod.staffchat")?.let { return it }
            if (arguments.isNotBlank()) {
                send(actor, arguments.take(2000))
                return done(actor, command, "staff.sent")
            }
            val id = actor.playerId() ?: return ActionResult.failure("command.player-only")
            val enabled = if (toggled.remove(id)) false else {
                toggled.add(id)
                true
            }
            return done(actor, command, "staff.toggle", mapOf("tool" to "staff chat", "state" to enabled.toString()))
        }
        val args = words(arguments)
        if (args.size > 1 || args.firstOrNull()?.let { it !in listOf("commands", "social") } == true) return usage("/spy [commands|social]")
        val type = args.firstOrNull() ?: "commands"
        check(actor, command, "adm.admin.spy.$type")?.let { return it }
        val id = actor.playerId() ?: return ActionResult.failure("command.player-only")
        val set = if (type == "commands") commandSpies else socialSpies
        val enabled = if (set.remove(id)) false else {
            set.add(id)
            true
        }
        return done(actor, command, "staff.toggle", mapOf("tool" to "$type spy", "state" to enabled.toString()))
    }

    private fun send(actor: CommandActor, text: String) {
        val metadata = actor.playerId()?.let { hierarchy.provider?.metadata(it) }
        val prefix = config.current().value("staffchat.prefix") as? String ?: "<dark_gray>[<aqua>Staff</aqua>]</dark_gray> "
        val legacy = LegacyComponentSerializer.legacyAmpersand()
        val output = MiniMessage.miniMessage().deserialize(prefix)
            .append(legacy.deserialize(metadata?.prefix ?: ""))
            .append(Component.text(actor.name()))
            .append(legacy.deserialize(metadata?.suffix ?: ""))
            .append(Component.text(": $text"))
        Bukkit.getOnlinePlayers().filter { it.hasPermission("adm.mod.staffchat") }.forEach { it.sendMessage(output) }
        Bukkit.getConsoleSender().sendMessage(output)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun chat(event: AsyncChatEvent) {
        val id = event.player.uniqueId
        if (id !in toggled) return
        event.isCancelled = true
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        scheduler.main(Runnable {
            val player = Bukkit.getPlayer(id) ?: return@Runnable
            if (!player.hasPermission("adm.mod.staffchat")) {
                toggled.remove(id)
                return@Runnable
            }
            send(CommandActor.from(player), text)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun commands(event: PlayerCommandPreprocessEvent) {
        val label = event.message.substringBefore(' ').removePrefix("/").substringAfter(':').lowercase()
        val ignored = config.current().value("spy.ignored-commands") as? List<*> ?: listOf("login", "register")
        if (label in ignored) return
        val social = label in (config.current().value("spy.social-commands") as? List<*> ?: listOf("msg", "tell", "w", "reply", "r"))
        val viewers = commandSpies + if (social) socialSpies else emptySet()
        viewers.filter { it != event.player.uniqueId }.forEach { id ->
            val viewer = Bukkit.getPlayer(id) ?: return@forEach
            if (id in commandSpies && viewer.hasPermission("adm.admin.spy.commands") || social && id in socialSpies && viewer.hasPermission("adm.admin.spy.social"))
                viewer.sendMessage(messages.render(ActionResult.success("staff.spy", mapOf("staff" to event.player.name, "message" to event.message))))
        }
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        toggled.remove(event.player.uniqueId)
        commandSpies.remove(event.player.uniqueId)
        socialSpies.remove(event.player.uniqueId)
    }
}