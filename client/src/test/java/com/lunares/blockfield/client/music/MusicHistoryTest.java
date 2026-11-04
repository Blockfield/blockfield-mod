package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MusicHistoryTest {
    @Test
    void roundSurvivesARestartAndBrokenFilesAreIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("history.json");
        List<String> pool = List.of("a", "b", "c");
        ShuffleBag bag = new ShuffleBag(pool, new Random(1));
        String played = bag.next();
        new MusicHistory(file).save("a,b,c", bag);

        ShuffleBag restored =
                new MusicHistory(file).restore("a,b,c", new ShuffleBag(pool, new Random(2)));
        assertEquals(List.of(played), restored.playedThisRound());
        assertEquals(played, restored.last());

        Files.writeString(file, "{broken");
        ShuffleBag fresh =
                new MusicHistory(file).restore("a,b,c", new ShuffleBag(pool, new Random(3)));
        assertEquals(pool, fresh.playedThisRound());
    }
}
