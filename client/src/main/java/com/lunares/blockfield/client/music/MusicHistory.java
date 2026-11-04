package com.lunares.blockfield.client.music;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Круги мешков треков между запусками игры: после перезапуска мешок продолжает прерванный круг, а
 * не начинает новый с тех же треков. Ключ -- набор треков; новый набор (другая версия музыки)
 * начинает с чистого круга.
 */
final class MusicHistory {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlockField-Music");
    private static final Gson GSON = new Gson();

    record Round(List<String> played, String last) {}

    private final Path file;
    private final Map<String, Round> rounds = new HashMap<>();

    MusicHistory(Path file) {
        this.file = file;
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Map<String, Round> loaded =
                    GSON.fromJson(
                            Files.readString(file),
                            new TypeToken<Map<String, Round>>() {}.getType());
            if (loaded != null) rounds.putAll(loaded);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("music history {} ignored: {}", file, e.toString());
        }
    }

    ShuffleBag restore(String key, ShuffleBag bag) {
        Round round = rounds.get(key);
        if (round != null && round.played() != null) bag.restore(round.played(), round.last());
        return bag;
    }

    void save(String key, ShuffleBag bag) {
        rounds.put(key, new Round(bag.playedThisRound(), bag.last()));
        if (file == null) return;
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(rounds));
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("music history {} not saved: {}", file, e.toString());
        }
    }
}
