package com.tecnor.adm.module

import com.tecnor.adm.settings.SettingsSnapshot

/**
 * All lifecycle methods and status access are server-thread confined.
 * disable() must tolerate partial enablement and release all resources owned by the module.
 */
interface AdmModule {
    fun id(): String
    fun enable()
    fun disable()
    fun status(): ModuleStatus
    fun markFailed()
    fun onReload(snapshot: SettingsSnapshot)
}