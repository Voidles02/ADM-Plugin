package com.tecnor.adm.api;

import java.util.Map;

/** Immutable service result. Safe to hand between threads; placeholders are plain strings. */
public record ActionResult(boolean success, String messageKey, Map<String, String> placeholders) {
    public ActionResult {
        placeholders = Map.copyOf(placeholders);
    }

    public static ActionResult success(String key) {
        return new ActionResult(true, key, Map.of());
    }

    public static ActionResult success(String key, Map<String, String> placeholders) {
        return new ActionResult(true, key, placeholders);
    }

    public static ActionResult failure(String key) {
        return new ActionResult(false, key, Map.of());
    }

    public static ActionResult failure(String key, Map<String, String> placeholders) {
        return new ActionResult(false, key, placeholders);
    }
}