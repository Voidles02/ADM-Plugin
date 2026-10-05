package com.tecnor.adm.module;

import com.tecnor.adm.settings.SettingsSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The registry and module states are confined to the server thread, including command access. */
public final class ModuleManager {
    private final Logger logger;
    private final Map<String, AdmModule> modules = new LinkedHashMap<>();
    public record ModuleError(String module, long timestamp, String message) {}
    private final java.util.ArrayDeque<ModuleError> errors = new java.util.ArrayDeque<>();

    public List<ModuleError> errors() {
        return List.copyOf(errors);
    }

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    public void register(AdmModule module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate ADM module: " + module.id());
        }
    }

    public void enableConfigured(SettingsSnapshot snapshot) {
        for (AdmModule module : modules.values()) {
            if (!module.id().equals("hud") && !snapshot.modules().getOrDefault(module.id(), false)) {
                continue;
            }
            try {
                module.enable();
            } catch (Throwable failure) {
                fail(module.id(), failure);
            }
        }
    }

    public boolean isEnabled(String id) {
        AdmModule module = modules.get(id);
        return module != null && module.status() == ModuleStatus.ENABLED;
    }

    public List<FeatureModule> features() {
        return modules.values().stream().filter(FeatureModule.class::isInstance)
                .map(FeatureModule.class::cast).toList();
    }

    public Map<String, ModuleStatus> statuses() {
        Map<String, ModuleStatus> result = new LinkedHashMap<>();
        modules.forEach((id, module) -> result.put(id, module.status()));
        return Collections.unmodifiableMap(result);
    }

    public List<String> reload(SettingsSnapshot snapshot) {
        List<String> failed = new ArrayList<>();
        for (AdmModule module : modules.values()) {
            if (module.status() != ModuleStatus.ENABLED) {
                continue;
            }
            try {
                module.onReload(snapshot);
            } catch (Throwable failure) {
                fail(module.id(), failure);
                failed.add(module.id());
            }
        }
        return List.copyOf(failed);
    }

    public void fail(String id, Throwable failure) {
        AdmModule module = modules.get(id);
        if (module == null) {
            return;
        }
        logger.log(Level.SEVERE, "ADM module '" + id + "' failed; disabling it.", failure);
        if (errors.size() == 32) errors.removeFirst();
        errors.addLast(new ModuleError(id, System.currentTimeMillis(), failure.toString()));
        try {
            module.disable();
        } catch (Throwable cleanupFailure) {
            logger.log(Level.SEVERE, "Cleanup failed for ADM module '" + id + "'.", cleanupFailure);
        } finally {
            module.markFailed();
        }
    }

    public void disableAll() {
        List<AdmModule> reverse = new ArrayList<>(modules.values());
        Collections.reverse(reverse);
        for (AdmModule module : reverse) {
            if (module.status() != ModuleStatus.ENABLED) {
                continue;
            }
            try {
                module.disable();
            } catch (Throwable failure) {
                logger.log(Level.SEVERE, "Shutdown failed for ADM module '" + module.id() + "'.", failure);
                module.markFailed();
            }
        }
    }
}