package com.tecnor.adm.inventory

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class OfflineInventoryStore(private val plugin: JavaPlugin) {
    data class Snapshot(
        var inventory: MutableList<ItemStack?>,
        var enderChest: MutableList<ItemStack?>,
        var restoreOnJoin: Boolean
    )

    private val directory = File(plugin.dataFolder, "offline-inventories")
    private val ioLock = Any()
    private val captureSequence = AtomicLong()
    private val pendingCaptures = ConcurrentHashMap<UUID, Long>()

    private data class EncodedSnapshot(
        val inventory: List<String>,
        val enderChest: List<String>,
        val restoreOnJoin: Boolean
    )

    fun captureAsync(player: Player, executor: Executor) {
        val playerId = player.uniqueId
        if (hasPendingRestore(playerId)) return
        val encoded = encodeSnapshot(Snapshot(
            player.inventory.contents.map { it?.clone() }.toMutableList(),
            player.enderChest.contents.map { it?.clone() }.toMutableList(),
            false
        ))
        val sequence = captureSequence.incrementAndGet()
        pendingCaptures[playerId] = sequence
        try {
            executor.execute {
                try {
                    synchronized(ioLock) {
                        if (pendingCaptures[playerId] != sequence || hasPendingRestoreLocked(playerId)) return@synchronized
                        write(playerId, encoded)
                    }
                } catch (failure: Exception) {
                    plugin.logger.log(java.util.logging.Level.SEVERE,
                        "Could not capture offline inventory for $playerId", failure)
                } finally {
                    pendingCaptures.remove(playerId, sequence)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingCaptures.remove(playerId, sequence)
            try {
                synchronized(ioLock) { write(playerId, encoded) }
            } catch (failure: Exception) {
                plugin.logger.log(java.util.logging.Level.SEVERE,
                    "Could not capture offline inventory for $playerId", failure)
            }
        }
    }

    fun cancelCapture(playerId: UUID) {
        pendingCaptures.remove(playerId)
    }

    fun load(playerId: UUID): Snapshot? = synchronized(ioLock) {
        loadLocked(playerId)
    }

    private fun loadLocked(playerId: UUID): Snapshot? {
        val file = file(playerId)
        if (!file.isFile) return null
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            if (yaml.getInt("format") != 1 || !yaml.contains("inventory") || !yaml.contains("ender-chest")) return null
            val inventory = decode(yaml.getStringList("inventory")) ?: return null
            val enderChest = decode(yaml.getStringList("ender-chest")) ?: return null
            Snapshot(inventory, enderChest, yaml.getBoolean("restore-on-join"))
        } catch (failure: Exception) {
            plugin.logger.warning("Could not load offline inventory for $playerId: ${failure.message}")
            null
        }
    }

    fun saveEdited(playerId: UUID, snapshot: Snapshot) {
        snapshot.restoreOnJoin = true
        val encoded = encodeSnapshot(snapshot)
        synchronized(ioLock) { write(playerId, encoded) }
    }

    fun restorePending(player: Player): Boolean = synchronized(ioLock) {
        val snapshot = loadLocked(player.uniqueId) ?: return@synchronized false
        if (!snapshot.restoreOnJoin) return@synchronized false
        player.inventory.contents = fit(snapshot.inventory, player.inventory.contents.size)
        player.enderChest.contents = fit(snapshot.enderChest, player.enderChest.contents.size)
        snapshot.restoreOnJoin = false
        write(player.uniqueId, encodeSnapshot(snapshot))
        true
    }

    private fun encodeSnapshot(snapshot: Snapshot) = EncodedSnapshot(
        encode(snapshot.inventory),
        encode(snapshot.enderChest),
        snapshot.restoreOnJoin
    )

    private fun write(playerId: UUID, snapshot: EncodedSnapshot) {
        if (!directory.exists() && !directory.mkdirs()) error("Could not create ${directory.path}")
        val destination = file(playerId)
        val temporary = File(directory, "$playerId.yml.tmp")
        val yaml = YamlConfiguration().apply {
            set("format", 1)
            set("inventory", snapshot.inventory)
            set("ender-chest", snapshot.enderChest)
            set("restore-on-join", snapshot.restoreOnJoin)
        }
        yaml.save(temporary)
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun encode(items: List<ItemStack?>): List<String> = items.map { item ->
        item?.let { Base64.getEncoder().encodeToString(it.serializeAsBytes()) } ?: ""
    }

    private fun decode(items: List<String>): MutableList<ItemStack?>? {
        val decoded = ArrayList<ItemStack?>(items.size)
        for (item in items) {
            if (item.isEmpty()) decoded.add(null)
            else {
                val stack = runCatching { ItemStack.deserializeBytes(Base64.getDecoder().decode(item)) }.getOrNull()
                    ?: return null
                decoded.add(stack)
            }
        }
        return decoded
    }

    private fun fit(items: List<ItemStack?>, size: Int): Array<ItemStack?> = Array(size) { index ->
        items.getOrNull(index)?.clone()
    }

    private fun file(playerId: UUID) = File(directory, "$playerId.yml")

    private fun hasPendingRestore(playerId: UUID): Boolean = synchronized(ioLock) {
        hasPendingRestoreLocked(playerId)
    }

    private fun hasPendingRestoreLocked(playerId: UUID): Boolean {
        val file = file(playerId)
        if (!file.isFile) return false
        return runCatching { YamlConfiguration.loadConfiguration(file).getBoolean("restore-on-join") }
            .getOrElse { failure ->
                plugin.logger.warning("Could not check pending offline inventory edits for $playerId: ${failure.message}")
                false
            }
    }
}