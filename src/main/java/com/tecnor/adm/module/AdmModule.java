package com.tecnor.adm.module;

import com.tecnor.adm.settings.SettingsSnapshot;

/**
 * All lifecycle methods and status access are server-thread confined.
 * disable() must tolerate partial enablement and release all resources owned by the module.
 */
public interface AdmModule {
    String id();

    void enable() throws Exception;

    void disable() throws Exception;

    ModuleStatus status();

    void markFailed();

    void onReload(SettingsSnapshot snapshot) throws Exception;
}