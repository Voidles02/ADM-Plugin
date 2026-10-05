package com.tecnor.adm.integration

import com.tecnor.adm.core.RankProvider
import com.tecnor.adm.core.SchedulerHelper
import com.tecnor.adm.core.StaffMetadata
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.user.UserDataRecalculateEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Loaded only with LuckPerms present. Cache is concurrent; live player access stays on the main thread. */
class LuckPermsIntegration(plugin: JavaPlugin, scheduler: SchedulerHelper) : RankProvider {
    private val api = LuckPermsProvider.get()
    private val cache = ConcurrentHashMap<UUID, StaffMetadata>()
    @Volatile private var closed = false
    private val subscription = api.eventBus.subscribe(plugin, UserDataRecalculateEvent::class.java) { event ->
        val id = event.user.uniqueId
        if (!closed) scheduler.main(Runnable { if (!closed) refresh(id) })
    }

    override fun metadata(id: UUID) = cache[id]

    override fun refresh(id: UUID) {
        val player = Bukkit.getPlayer(id) ?: return
        val adapter = api.getPlayerAdapter(Player::class.java)
        val user = adapter.getUser(player)
        val meta = adapter.getMetaData(player)
        val weight = api.groupManager.getGroup(user.primaryGroup)?.weight?.orElse(0) ?: 0
        cache[id] = StaffMetadata(meta.prefix.orEmpty(), meta.suffix.orEmpty(), weight)
    }

    override fun forget(id: UUID) { cache.remove(id) }

    override fun close() {
        closed = true
        subscription.close()
        cache.clear()
    }
}