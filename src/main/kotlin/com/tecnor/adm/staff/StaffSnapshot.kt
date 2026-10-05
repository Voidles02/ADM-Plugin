package com.tecnor.adm.staff

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class StaffSnapshot(val items: Array<ItemStack?>, val mode: GameMode, val allowFlight: Boolean,
                         val flying: Boolean, val flySpeed: Float, val walkSpeed: Float,
                         val world: UUID, val x: Double, val y: Double, val z: Double,
                         val yaw: Float, val pitch: Float, val vanishLevel: Int?, val pickup: Boolean) {
    fun bytes(): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.writeInt(1)
        out.writeInt(items.size)
        items.forEach { item ->
            val bytes = item?.serializeAsBytes()
            out.writeInt(bytes?.size ?: -1)
            if (bytes != null) out.write(bytes)
        }
        out.writeUTF(mode.name)
        out.writeBoolean(allowFlight)
        out.writeBoolean(flying)
        out.writeFloat(flySpeed)
        out.writeFloat(walkSpeed)
        out.writeUTF(world.toString())
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(z)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        out.writeInt(vanishLevel ?: -1)
        out.writeBoolean(pickup)
    } }.toByteArray()

    fun restore(player: Player): Boolean {
        val destination = Bukkit.getWorld(world) ?: return false
        if (!player.teleport(Location(destination, x, y, z, yaw, pitch))) return false
        player.inventory.contents = items.map { it?.clone() }.toTypedArray()
        player.gameMode = mode
        player.allowFlight = allowFlight
        player.isFlying = flying && allowFlight
        player.flySpeed = flySpeed
        player.walkSpeed = walkSpeed
        player.canPickupItems = pickup
        player.fallDistance = 0f
        return true
    }

    companion object {
        fun capture(player: Player, vanishLevel: Int?): StaffSnapshot {
            val location = player.location
            return StaffSnapshot(player.inventory.contents.map { it?.clone() }.toTypedArray(), player.gameMode,
                player.allowFlight, player.isFlying, player.flySpeed, player.walkSpeed, player.world.uid,
                location.x, location.y, location.z, location.yaw, location.pitch, vanishLevel, player.canPickupItems)
        }

        fun decode(bytes: ByteArray): StaffSnapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1) { "Unsupported staff snapshot version" }
            val size = input.readInt()
            require(size in 1..100)
            val items = Array<ItemStack?>(size) {
                val length = input.readInt()
                if (length == -1) null else {
                    require(length in 1..16_777_216)
                    ItemStack.deserializeBytes(input.readNBytes(length).also { require(it.size == length) })
                }
            }
            StaffSnapshot(items, GameMode.valueOf(input.readUTF()), input.readBoolean(), input.readBoolean(),
                input.readFloat(), input.readFloat(), UUID.fromString(input.readUTF()), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat(),
                input.readInt().takeIf { it >= 0 }, input.readBoolean())
        }
    }
}