package com.tecnor.adm.storage

import com.tecnor.adm.api.Storage
import com.tecnor.adm.core.HierarchyService
import com.tecnor.adm.settings.ConfigService
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.util.concurrent.TimeUnit

class LoginRecorder(private val storage: Storage, private val settings: ConfigService,
                    private val hierarchy: HierarchyService) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun record(event: AsyncPlayerPreLoginEvent) {
        val id = event.uniqueId.toString()
        val name = event.name
        val ip = event.address.hostAddress
        val now = System.currentTimeMillis()
        try {
            storage.submit { db -> db.transaction {
                if (db.update("UPDATE players SET name=?,ip=?,last_login=? WHERE uuid=?", name, ip, now, id) == 0)
                    db.update("INSERT INTO players(uuid,name,ip,first_login,last_login) VALUES(?,?,?,?,?)", id, name, ip, now, now)
                if (db.update("UPDATE names SET last_seen=? WHERE name=? AND uuid=?", now, name, id) == 0)
                    db.update("INSERT INTO names(name,uuid,first_seen,last_seen) VALUES(?,?,?,?)", name, id, now, now)
            } }.get(8, TimeUnit.SECONDS)
        } catch (_: Exception) {
            if (settings.current().loginFallback() == "deny") event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Login unavailable: administration storage is offline."))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun metadata(event: PlayerJoinEvent) {
        val player = event.player
        val id = player.uniqueId.toString()
        val rank = hierarchy.rank(player)
        val immune = player.hasPermission("adm.immune")
        storage.submit { it.update("UPDATE players SET player_rank=?,immune=? WHERE uuid=?", rank, immune, id) }
    }
}