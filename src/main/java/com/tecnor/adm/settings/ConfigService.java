package com.tecnor.adm.settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snapshots may be read from any thread. Publication occurs on the server thread only.
 * The startup snapshot remains fixed so restart notices describe actual running settings.
 */
public final class ConfigService {
    private final Path directory;
    private final SettingsSnapshot startup;
    private final AtomicReference<SettingsSnapshot> current;

    public ConfigService(Path directory, SettingsSnapshot startup) {
        this.directory = directory;
        this.startup = startup;
        this.current = new AtomicReference<>(startup);
    }

    public SettingsSnapshot current() {
        return current.get();
    }

    public SettingsSnapshot startup() {
        return startup;
    }

    public SettingsSnapshot readForReload() throws Exception {
        return SettingsLoader.load(directory);
    }

    public void apply(SettingsSnapshot snapshot) {
        current.set(snapshot);
    }

    public List<String> restartRequired(SettingsSnapshot candidate) {
        List<String> changes = new ArrayList<>();
        Set<String> ids = new TreeSet<>(startup.modules().keySet());
        ids.addAll(candidate.modules().keySet());
        for (String id : ids) {
            boolean before = startup.modules().getOrDefault(id, false);
            boolean after = candidate.modules().getOrDefault(id, false);
            if (before != after) {
                changes.add("modules." + id);
            }
        }
        if (!startup.commands().name().equals(candidate.commands().name())) {
            changes.add("commands.name");
        }
        if (!startup.commands().aliases().equals(candidate.commands().aliases())) {
            changes.add("commands.aliases");
        }
        return List.copyOf(changes);
    }
}