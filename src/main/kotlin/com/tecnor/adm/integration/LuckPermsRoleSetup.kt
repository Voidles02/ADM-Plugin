package com.tecnor.adm.integration

import net.luckperms.api.LuckPerms
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.WeightNode
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

class LuckPermsRoleSetup(
    private val luckPerms: LuckPerms,
    private val logger: Logger
) {
    private data class Role(
        val name: String,
        val weight: Int,
        val permissions: List<String>,
        val parent: String? = null
    )

    private val moderator = Role(
        name = "moderator",
        weight = 10,
        permissions = listOf(
            "adm.tier.mod",
            "adm.mod.*",
            "adm.tpa.use",
            "adm.admin.vanish",
            "adm.admin.vanish.see",
            "adm.vanish.level.1",
            "adm.hud.use",
            "adm.hud.category.moderation",
            "adm.hud.category.reports",
            "adm.hud.category.vanish",
            "minecraft.command.kick",
            "minecraft.command.teleport",
            "minecraft.command.gamemode",
            "minecraft.command.effect",
            "minecraft.command.clear",
            "bukkit.command.kick",
            "bukkit.command.teleport",
            "bukkit.command.gamemode"
        )
    )

    private val admin = Role(
        name = "admin",
        weight = 50,
        parent = moderator.name,
        permissions = listOf(
            "adm.tier.admin",
            "adm.admin.*",
            "adm.admin.tpa.configure",
            "adm.admin.anticheat.*",
            "adm.hud.*",
            "adm.vanish.level.2",
            "minecraft.command.give",
            "minecraft.command.enchant",
            "minecraft.command.experience",
            "minecraft.command.time",
            "minecraft.command.weather",
            "minecraft.command.gamerule",
            "minecraft.command.setblock",
            "minecraft.command.fill",
            "minecraft.command.summon",
            "minecraft.command.kill",
            "minecraft.command.execute",
            "minecraft.command.title",
            "minecraft.command.scoreboard",
            "bukkit.command.give",
            "bukkit.command.enchant",
            "bukkit.command.time",
            "bukkit.command.weather"
        )
    )

    private val owner = Role(
        name = "owner",
        weight = 100,
        parent = admin.name,
        permissions = listOf(
            "adm.*",
            "adm.tier.owner",
            "adm.bypass.*",
            "adm.immune",
            "adm.vanish.level.3",
            "minecraft.command.*",
            "bukkit.command.*"
        )
    )

    fun setup() {
        ensureRole(moderator)
            .thenCompose { ensureRole(admin) }
            .thenCompose { ensureRole(owner) }
            .whenComplete { _, failure ->
                if (failure == null) {
                    logger.info("LuckPerms default groups are ready: owner, admin, moderator. Assign players to groups with LuckPerms.")
                } else {
                    logger.log(Level.WARNING, "Could not create or update ADM's default LuckPerms groups.", failure)
                }
            }
    }

    private fun ensureRole(role: Role): CompletableFuture<Void> {
        val groups = luckPerms.groupManager
        return groups.loadGroup(role.name).thenCompose { loaded ->
            val groupFuture = if (loaded.isPresent) {
                CompletableFuture.completedFuture(loaded.get())
            } else {
                groups.createAndLoadGroup(role.name)
            }
            groupFuture.thenCompose { group ->
                val data = group.data()
                var changed = false

                role.permissions.forEach { permission ->
                    data.toCollection()
                        .filter { it.key == permission && !it.value && it.contexts.isEmpty() && !it.hasExpiry() }
                        .forEach {
                            data.remove(it)
                            changed = true
                        }
                    if (data.add(Node.builder(permission).build()).wasSuccessful()) changed = true
                }

                data.toCollection()
                    .filterIsInstance<WeightNode>()
                    .filter { it.weight != role.weight && it.contexts.isEmpty() && !it.hasExpiry() }
                    .forEach {
                        data.remove(it)
                        changed = true
                    }
                if (data.add(WeightNode.builder(role.weight).build()).wasSuccessful()) changed = true

                role.parent?.let {
                    if (data.add(InheritanceNode.builder(it).build()).wasSuccessful()) changed = true
                }

                if (changed) groups.saveGroup(group) else CompletableFuture.completedFuture<Void>(null)
            }
        }
    }
}