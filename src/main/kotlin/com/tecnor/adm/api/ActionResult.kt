package com.tecnor.adm.api

/** Immutable service result. Safe to hand between threads; placeholders are plain strings. */
class ActionResult(success: Boolean, messageKey: String, placeholders: Map<String, String>) {
    private val successful = success
    private val key = messageKey
    private val replacements = java.util.Map.copyOf(placeholders)

    fun success() = successful
    fun messageKey() = key
    fun placeholders(): Map<String, String> = replacements

    override fun equals(other: Any?) = other is ActionResult && successful == other.successful &&
        key == other.key && replacements == other.replacements
    override fun hashCode() = 31 * (31 * successful.hashCode() + key.hashCode()) + replacements.hashCode()
    override fun toString() = "ActionResult[success=$successful, messageKey=$key, placeholders=$replacements]"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun success(key: String, placeholders: Map<String, String> = emptyMap()) = ActionResult(true, key, placeholders)

        @JvmStatic
        @JvmOverloads
        fun failure(key: String, placeholders: Map<String, String> = emptyMap()) = ActionResult(false, key, placeholders)
    }
}