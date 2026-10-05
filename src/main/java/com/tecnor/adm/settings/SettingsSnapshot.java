package com.tecnor.adm.settings;

import java.util.List;
import java.util.Map;

/**
 * Immutable settings shared through ConfigService's atomic reference.
 * All members contain only immutable plain data; no gameplay objects are retained.
 */
public record SettingsSnapshot(
        Map<String, Boolean> modules,
        CommandSettings commands,
        String loginFallback,
        Map<String, Object> values,
        Map<String, String> messages
) {
    public SettingsSnapshot {
        modules = Map.copyOf(modules);
        values = Map.copyOf(values);
        messages = Map.copyOf(messages);
    }

    public Object value(String path) {
        Object result = values;
        for (String part : path.split("\\.")) {
            if (!(result instanceof Map<?, ?> section)) {
                return null;
            }
            result = section.get(part);
        }
        return result;
    }

    public int integer(String path, int fallback) {
        Object result = value(path);
        return result instanceof Number number ? number.intValue() : fallback;
    }

    public record CommandSettings(String name, List<String> aliases) {
        public CommandSettings {
            aliases = List.copyOf(aliases);
        }
    }
}