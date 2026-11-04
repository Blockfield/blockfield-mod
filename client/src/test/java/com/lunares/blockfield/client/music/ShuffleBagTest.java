package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShuffleBagTest {
    @Test
    void neverRepeatsBackToBackAndPlaysEveryTrackEachRound() {
        ShuffleBag bag = new ShuffleBag(List.of("a", "b", "c"), new Random(1));
        String previous = null;
        for (int round = 0; round < 200; round++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                String next = bag.next();
                assertNotEquals(previous, next);
                seen.add(next);
                previous = next;
            }
            assertEquals(3, seen.size());
        }
    }

    @Test
    void trackPlayedOutsideTheBagIsNotRepeatedNext() {
        ShuffleBag bag = new ShuffleBag(List.of("theme", "b"), new Random(2));
        bag.played("theme");
        assertEquals("b", bag.next());
    }

    @Test
    void singleTrackPoolStillPlays() {
        ShuffleBag bag = new ShuffleBag(List.of("only"), new Random(3));
        assertEquals("only", bag.next());
        assertEquals("only", bag.next());
    }

    @Test
    void restoredRoundSkipsTracksPlayedBeforeTheRestart() {
        ShuffleBag first = new ShuffleBag(List.of("a", "b", "c", "d"), new Random(4));
        first.next();
        String second = first.next();
        ShuffleBag restored = new ShuffleBag(List.of("a", "b", "c", "d"), new Random(5));
        restored.restore(first.playedThisRound(), first.last());
        String third = restored.next();
        assertNotEquals(second, third);
        String fourth = restored.next();
        Set<String> expected = new HashSet<>(List.of("a", "b", "c", "d"));
        expected.removeAll(first.playedThisRound());
        assertEquals(expected, Set.of(third, fourth));
        assertNotEquals(fourth, restored.next());
    }
}
