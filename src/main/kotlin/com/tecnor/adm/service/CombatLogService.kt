package com.tecnor.adm.service

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.api.CommandActor
import com.tecnor.adm.core.PermissionService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.CommandSpec
import com.tecnor.adm.module.ModuleManager
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.settings.SettingsSnapshot
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
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
    private val pendingDeathKey = NamespacedKey(plugin, "combat-log-pending-death")
    private var countdownTask: BukkitTask? = null

    override fun enable() {
        active = true
        countdownTask?.cancel()
        countdownTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { updateCountdowns() }, 0L, 20L)
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
        sendCountdown(victim, durationSeconds.toLong())
        sendCountdown(attacker, durationSeconds.toLong())
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun quit(event: PlayerQuitEvent) {
        val expiry = taggedUntil.remove(event.player.uniqueId) ?: return
        if (!active || !enabled || !quitPenalty || expiry - System.nanoTime() <= 0 ||
            event.player.hasPermission("adm.bypass.combatlog")) return

        val player = event.player
        if (player.isDead) return
        val data = player.persistentDataContainer
        data.set(pendingDeathKey, PersistentDataType.BYTE, 1.toByte())
        player.health = 0.0
        if (player.isDead) data.remove(pendingDeathKey)
        val result = ActionResult.success("combat-log.quit", mapOf("target" to player.name))
        val notice = messages.render(result)
        Bukkit.getOnlinePlayers().forEach {
            if (it.uniqueId != player.uniqueId) it.sendMessage(notice)
        }
        plugin.logger.info("${player.name} disconnected while combat-tagged and was killed.")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun join(event: PlayerJoinEvent) {
        val player = event.player
        if (player.persistentDataContainer.get(pendingDeathKey, PersistentDataType.BYTE) != 1.toByte()) return

        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            if (!player.isDead && player.health > 0.0) player.health = 0.0
            player.persistentDataContainer.remove(pendingDeathKey)
        })
    }

    private fun updateCountdowns() {
        if (!active || !enabled) return

        val now = System.nanoTime()
        val notices = HashMap<Long, Component>()
        val iterator = taggedUntil.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, expiry) = iterator.next()
            val player = Bukkit.getPlayer(uuid)
            if (expiry <= now) {
                player?.sendActionBar(Component.empty())
                iterator.remove()
                continue
            }

            if (player != null) {
                val remainingSeconds = (expiry - now + 999_999_999L) / 1_000_000_000L
                val notice = notices.getOrPut(remainingSeconds) {
                    messages.render(ActionResult.success("combat-log.timer", mapOf("seconds" to remainingSeconds.toString())))
                }
                player.sendActionBar(notice)
            }
        }
    }

    private fun sendCountdown(player: Player, seconds: Long) {
        player.sendActionBar(messages.render(ActionResult.success("combat-log.timer", mapOf("seconds" to seconds.toString()))))
    }

    private fun clearCountdowns() {
        taggedUntil.keys.forEach { Bukkit.getPlayer(it)?.sendActionBar(Component.empty()) }
        taggedUntil.clear()
    }

    override fun onReload(snapshot: SettingsSnapshot) {
        enabled = snapshot.value("combat-log.enabled") as? Boolean ?: true
        durationSeconds = snapshot.integer("combat-log.duration-seconds", 35)
        quitPenalty = snapshot.value("combat-log.quit-penalty") as? Boolean ?: true
        if (!enabled) clearCountdowns()
    }

    override fun disable() {
        active = false
        countdownTask?.cancel()
        countdownTask = null
        clearCountdowns()
    }
}