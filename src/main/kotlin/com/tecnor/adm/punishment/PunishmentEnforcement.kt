package com.tecnor.adm.punishment

import com.tecnor.adm.api.ActionResult
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.settings.ConfigService
import com.tecnor.adm.storage.query
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.concurrent.TimeUnit

class PunishmentEnforcement(private val punishments: Punishments, private val settings: ConfigService,
                            private val messages: MessageService) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    fun login(event: AsyncPlayerPreLoginEvent) {
        val id = event.uniqueId
        val ip = event.address.hostAddress
        try {
            val result = punishments.storage.submit { db ->
                val ban = db.query("SELECT * FROM punishments WHERE (target=? AND kind='BAN' OR ip=? AND kind='IPBAN') AND revoked IS NULL AND (expires IS NULL OR expires>?) ORDER BY id DESC LIMIT 1",
                    id.toString(), ip, System.currentTimeMillis()) { it.punishment() }.firstOrNull()
                ban to punishments.active(db, id, "MUTE")
            }.get(8, TimeUnit.SECONDS)
            punishments.mutes.remove(id)
            result.second?.let { punishments.mutes[id] = it }
            result.first?.let {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, messages.render(ActionResult.success("punishment.disconnect",
                    mapOf("action" to it.kind, "reason" to it.reason, "expires" to (it.expires?.let(java.time.Instant::ofEpochMilli)?.toString() ?: "permanent")))))
            }
            if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) punishments.mutes.remove(id)
        } catch (_: Exception) {
            punishments.mutes.remove(id)
            if (settings.current().loginFallback() == "deny") event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                messages.render(ActionResult.failure("storage.login-denied")))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun chat(event: AsyncChatEvent) {
        val mute = punishments.mutes[event.player.uniqueId] ?: return
        if (!mute.active()) {
            punishments.mutes.remove(event.player.uniqueId, mute)
            return
        }
        event.isCancelled = true
        event.player.sendMessage(messages.render(ActionResult.failure("punishment.muted", mapOf("reason" to mute.reason))))
    }

    @EventHandler fun quit(event: PlayerQuitEvent) { punishments.mutes.remove(event.player.uniqueId) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun rejected(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) punishments.mutes.remove(event.uniqueId)
    }
}