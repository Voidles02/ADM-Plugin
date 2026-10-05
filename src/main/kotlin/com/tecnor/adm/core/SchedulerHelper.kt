package com.tecnor.adm.core

import org.bukkit.Bukkit
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin

/** Stateless Bukkit scheduler gateway. Callbacks containing gameplay access execute on the main thread. */
class SchedulerHelper(private val plugin: JavaPlugin) {
    fun main(callback: Runnable): Boolean = try {
        val source = com.tecnor.adm.api.ActionOrigin.current()
        val module = com.tecnor.adm.api.StorageTaskScope.current()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            com.tecnor.adm.api.StorageTaskScope.within(module) {
                com.tecnor.adm.api.ActionOrigin.within(source) { callback.run() }
            }
        })
        true
    } catch (_: IllegalPluginAccessException) {
        false
    }
}