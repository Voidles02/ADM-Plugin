package com.tecnor.adm.settings

/**
 * Immutable settings shared through ConfigService's atomic reference.
 * All members contain only immutable plain data; no gameplay objects are retained.
 */
class SettingsSnapshot(
    modules: Map<String, Boolean>,
    private val commands: CommandSettings,
    private val loginFallback: String,
    values: Map<String, Any>,
    messages: Map<String, String>
) {
    private val modules = java.util.Map.copyOf(modules)
    private val values = java.util.Map.copyOf(values)
    private val messages = java.util.Map.copyOf(messages)

    fun modules(): Map<String, Boolean> = modules
    fun commands() = commands
    fun loginFallback() = loginFallback
    fun values(): Map<String, Any> = values
    fun messages(): Map<String, String> = messages

    fun value(path: String): Any? {
        var result: Any? = values
        for (part in path.split('.')) result = (result as? Map<*, *>)?.get(part) ?: return null
        return result
    }

    fun integer(path: String, fallback: Int) = (value(path) as? Number)?.toInt() ?: fallback

    override fun equals(other: Any?) = other is SettingsSnapshot && modules == other.modules &&
        commands == other.commands && loginFallback == other.loginFallback && values == other.values && messages == other.messages
    override fun hashCode() = java.util.Objects.hash(modules, commands, loginFallback, values, messages)
    override fun toString() = "SettingsSnapshot[modules=$modules, commands=$commands, loginFallback=$loginFallback, values=$values, messages=$messages]"

    class CommandSettings(private val name: String, aliases: List<String>) {
        private val aliases = java.util.List.copyOf(aliases)
        fun name() = name
        fun aliases(): List<String> = aliases
        override fun equals(other: Any?) = other is CommandSettings && name == other.name && aliases == other.aliases
        override fun hashCode() = 31 * name.hashCode() + aliases.hashCode()
        override fun toString() = "CommandSettings[name=$name, aliases=$aliases]"
    }
}