package com.lunares.blockfield.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Properties-файл клиента в {@code config/}, читается один раз при первом обращении. {@link #PACK}
 * раздаётся модпаком (packwiz его перезаписывает), {@link #USER} пак не трогает: туда игрок кладёт
 * свои настройки через обычное меню Minecraft.
 */
public final class ClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfig.class);

    public static final ClientConfig PACK = new ClientConfig("blockfield-client.properties");
    public static final ClientConfig USER = new ClientConfig("blockfield-settings.properties");

    private final String fileName;
    private Properties properties;
    private final Path explicitPath;

    private ClientConfig(String fileName) {
        this.fileName = fileName;
        this.explicitPath = null;
    }

    ClientConfig(Path path) {
        this.fileName = path.getFileName().toString();
        this.explicitPath = path;
    }

    private Path path() {
        return explicitPath != null
                ? explicitPath
                : FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }

    private synchronized Properties properties() {
        if (properties != null) return properties;
        properties = new Properties();
        Path file = path();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException | IllegalArgumentException e) {
                properties.clear();
                LOGGER.warn("Cannot read {}, using defaults", file, e);
            }
        }
        return properties;
    }

    public synchronized String get(String key, String fallback) {
        String value = properties().getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** Кривое число в конфиге не должно ронять HUD: берём дефолт и зажимаем в разумные пределы. */
    public int getInt(String key, int fallback, int min, int max) {
        try {
            String value = get(key, null);
            return value == null ? fallback : Math.min(max, Math.max(min, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Re-read before writing so unknown keys and external edits survive; publish only after save.
     */
    public synchronized void set(String key, String value) throws IOException {
        Path file = path();
        Properties updated = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                updated.load(in);
            }
        }
        updated.setProperty(key, value);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), fileName, ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                updated.store(out, "Blockfield player settings");
            }
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            properties = updated;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
