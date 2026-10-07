package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class CombatLogService(
    plugin: JavaPlugin,
    modules: ModuleManager,
    permissions: PermissionService,
    private val settings: ConfigService,
    private val messages: MessageService
) : ServiceSupport(plugin, modules, permissions), Listener {
    override val id = "combat-log"
    override val commands = emptyList<CommandSpec>()

    private val taggedUntil = HashMap<UUID, Long>()
    private var enabled = settings.current().value("combat-log.enabled") as? Boolean ?: true
    private var durationSeconds = settings.current().integer("combat-log.duration-seconds", 35)
    private var quitPenalty = settings.current().value("combat-log.quit-penalty") as? Boolean ?: true
    private var active = false

    override fun enable() {
        active = true
    }

    override fun execute(actor: CommandActor, command: String, arguments: String) =
        ActionResult.failure("command.module-unavailable", mapOf("module" to id))

    override fun suggestions(command: String, completed: List<String>) = emptyList<String>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun combat(event: EntityDamageByEntityEvent) {
        if (!active || !enabled || event.finalDamage <= 0.0) return
        val victim = event.entity as? Player ?: return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        if (victim.uniqueId == attacker.uniqueId) return

        val expiry = System.nanoTime() + durationSeconds * 1_000_000_000L
        taggedUntil[victim.uniqueId] = expiry
        taggedUntil[attacker.uniqueId] = expiry
        val result = ActionResult.success("combat-log.tagged", mapOf("seconds" to durationSeconds.toString()))
        val notice = messages.render(result)
        victim.sendActionBar(notice)
        attacker.sendActionBar(notice)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun quit(event: PlayerQuitEvent) {
        val expiry = taggedUntil.remove(event.player.uniqueId) ?: return
        if (!active || !enabled || !quitPenalty || expiry - System.nanoTime() <= 0 ||
            event.player.hasPermission("adm.bypass.combatlog")) return

        val player = event.player
        if (player.isDead) return
        player.health = 0.0
        val result = ActionResult.success("combat-log.quit", mapOf("target" to player.name))
        val notice = messages.render(result)
        Bukkit.getOnlinePlayers().forEach {
            if (it.uniqueId != player.uniqueId) it.sendMessage(notice)
        }
        plugin.logger.info("${player.name} disconnected while combat-tagged and was killed.")
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        enabled = snapshot.value("combat-log.enabled") as? Boolean ?: true
        durationSeconds = snapshot.integer("combat-log.duration-seconds", 35)
        quitPenalty = snapshot.value("combat-log.quit-penalty") as? Boolean ?: true
        if (!enabled) taggedUntil.clear()
    }

    override fun disable() {
        active = false
        taggedUntil.clear()
    }
}