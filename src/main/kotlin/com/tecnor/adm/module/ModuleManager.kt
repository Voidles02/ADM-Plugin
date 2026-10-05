package com.tecnor.adm.module

import com.tecnor.adm.settings.SettingsSnapshot
import java.util.ArrayDeque
import java.util.logging.Level
import java.util.logging.Logger

/** The registry and module states are confined to the server thread, including command access. */
class ModuleManager(private val logger: Logger) {
    private val modules = linkedMapOf<String, AdmModule>()
    private val errors = ArrayDeque<ModuleError>()

    data class ModuleError(private val module: String, private val timestamp: Long, private val message: String) {
        fun module() = module
        fun timestamp() = timestamp
        fun message() = message
    }

    fun errors(): List<ModuleError> = java.util.List.copyOf(errors)

    fun register(module: AdmModule) {
        require(modules.putIfAbsent(module.id(), module) == null) { "Duplicate ADM module: ${module.id()}" }
    }

    fun enableConfigured(snapshot: SettingsSnapshot) {
        for (module in modules.values) {
            if (module.id() != "hud" && !snapshot.modules().getOrDefault(module.id(), false)) continue
            try {
                module.enable()
            } catch (failure: Throwable) {
                fail(module.id(), failure)
            }
        }
    }

    fun isEnabled(id: String) = modules[id]?.status() == ModuleStatus.ENABLED
    fun features(): List<FeatureModule> = modules.values.filterIsInstance<FeatureModule>()
    fun statuses(): Map<String, ModuleStatus> = java.util.Collections.unmodifiableMap(modules.mapValues { it.value.status() })

    fun reload(snapshot: SettingsSnapshot): List<String> {
        val failed = mutableListOf<String>()
        for (module in modules.values) {
            if (module.status() != ModuleStatus.ENABLED) continue
            try {
                module.onReload(snapshot)
            } catch (failure: Throwable) {
                fail(module.id(), failure)
                failed.add(module.id())
            }
        }
        return java.util.List.copyOf(failed)
    }

    fun fail(id: String, failure: Throwable) {
        val module = modules[id] ?: return
        logger.log(Level.SEVERE, "ADM module '$id' failed; disabling it.", failure)
        if (errors.size == 32) errors.removeFirst()
        errors.addLast(ModuleError(id, System.currentTimeMillis(), failure.toString()))
        try {
            module.disable()
        } catch (cleanupFailure: Throwable) {
            logger.log(Level.SEVERE, "Cleanup failed for ADM module '$id'.", cleanupFailure)
        } finally {
            module.markFailed()
        }
    }

    fun disableAll() {
        for (module in modules.values.toList().asReversed()) {
            if (module.status() != ModuleStatus.ENABLED) continue
            try {
                module.disable()
            } catch (failure: Throwable) {
                logger.log(Level.SEVERE, "Shutdown failed for ADM module '${module.id()}'.", failure)
                module.markFailed()
            }
        }
    }
}