package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyDefaultHistoryTest {
    @TempDir Path directory;

    @Test
    void firstUpgradeMigratesStockAndPreviousPackDefaultsOnly() throws Exception {
        var history = KeyDefaultHistory.load(directory.resolve("keys.properties"));
        assertTrue(history.shouldUpdate("sprint", "ctrl", "ctrl", null));
        assertTrue(history.shouldUpdate("radio", "mouse5", "r", "mouse5"));
        assertFalse(history.shouldUpdate("radio", "mouse4", "r", "mouse5"));
        assertFalse(history.shouldUpdate("sprint", "unbound", "ctrl", null));
    }

    @Test
    void restartPreservesRebindingBackToOldDefaults() throws Exception {
        Path path = directory.resolve("keys.properties");
        var history = KeyDefaultHistory.load(path);
        history.record("radio", "capslock");
        history.record("sprint", "shift");
        history.save(path);
        var loaded = KeyDefaultHistory.load(path);
        assertFalse(loaded.shouldUpdate("radio", "mouse5", "r", "mouse5"));
        assertFalse(loaded.shouldUpdate("radio", "r", "r", "mouse5"));
        assertFalse(loaded.shouldUpdate("sprint", "ctrl", "ctrl", null));
        assertFalse(loaded.shouldUpdate("sprint", "unbound", "ctrl", null));
        assertTrue(loaded.shouldUpdate("sprint", "shift", "ctrl", null));
    }
}
