package com.lunares.blockfield.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class KeyDefaultHistory {
    private final Properties applied = new Properties();

    static KeyDefaultHistory load(Path path) throws IOException {
        KeyDefaultHistory history = new KeyDefaultHistory();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                history.applied.load(reader);
            }
        }
        return history;
    }

    boolean shouldUpdate(String name, String bound, String original, String legacy) {
        String previous = applied.getProperty(name);
        return previous != null
                ? bound.equals(previous)
                : bound.equals(original) || bound.equals(legacy);
    }

    void record(String name, String key) {
        applied.setProperty(name, key);
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (var writer = Files.newBufferedWriter(path)) {
            applied.store(
                    writer,
                    "Last applied pack defaults, used to preserve custom key bindings on upgrade.");
        }
    }
}
