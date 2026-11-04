package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigTest {
    @TempDir Path directory;

    @Test
    void editsAreLivePersistentAndPreserveUnknownAndExternallyChangedKeys() throws Exception {
        Path file = directory.resolve("settings.properties");
        Files.writeString(file, "minimap.size=80\nunknown\\ key=kept\\ value\nminimap.blocks=96\n");
        ClientConfig config = new ClientConfig(file);
        assertEquals(80, config.getInt("minimap.size", 80, 48, 220));
        Files.writeString(
                file, "minimap.size=80\nunknown\\ key=kept\\ value\nminimap.blocks=128\n");
        config.set("minimap.size", "120");
        config.set("minimap.rotate", "false");
        assertEquals(120, config.getInt("minimap.size", 80, 48, 220));
        assertEquals(128, config.getInt("minimap.blocks", 96, 24, 512));
        assertFalse(config.getBoolean("minimap.rotate", true));
        ClientConfig reopened = new ClientConfig(file);
        assertEquals(120, reopened.getInt("minimap.size", 80, 48, 220));
        assertEquals("kept value", reopened.get("unknown key", "missing"));
        config.set("minimap.size", "999");
        assertEquals(220, config.getInt("minimap.size", 80, 48, 220));
        config.set("minimap.size", "invalid");
        assertEquals(80, config.getInt("minimap.size", 80, 48, 220));
    }

    @Test
    void unreadableOrMalformedExistingFileIsNeverOverwritten() throws Exception {
        Path file = directory.resolve("settings.properties");
        Files.writeString(file, "minimap.size=80\n");
        ClientConfig config = new ClientConfig(file);
        assertEquals(80, config.getInt("minimap.size", 80, 48, 220));
        String corrupt = "unknown=\\uNOTHEX\n";
        Files.writeString(file, corrupt);
        assertThrows(IllegalArgumentException.class, () -> config.set("minimap.size", "120"));
        assertEquals(corrupt, Files.readString(file));
        assertEquals(80, config.getInt("minimap.size", 80, 48, 220));
    }
}
