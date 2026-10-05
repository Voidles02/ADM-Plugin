package com.tecnor.adm.settings

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshots may be read from any thread. Publication occurs on the server thread only.
 * The startup snapshot remains fixed so restart notices describe actual running settings.
 */
class ConfigService(private val directory: Path, private val startup: SettingsSnapshot) {
    private val current = AtomicReference(startup)

    fun current(): SettingsSnapshot = current.get()
    fun startup() = startup
    fun readForReload() = SettingsLoader.load(directory)
    fun apply(snapshot: SettingsSnapshot) { current.set(snapshot) }

    fun restartRequired(candidate: SettingsSnapshot): List<String> {
        val changes = mutableListOf<String>()
        val ids = (startup.modules().keys + candidate.modules().keys).toSortedSet()
        for (id in ids) {
            if (startup.modules().getOrDefault(id, false) != candidate.modules().getOrDefault(id, false)) {
                changes.add("modules.$id")
            }
        }
        if (startup.commands().name() != candidate.commands().name()) changes.add("commands.name")
        if (startup.commands().aliases() != candidate.commands().aliases()) changes.add("commands.aliases")
        return java.util.List.copyOf(changes)
    }
}