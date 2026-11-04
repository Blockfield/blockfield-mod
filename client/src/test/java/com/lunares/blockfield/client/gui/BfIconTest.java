package com.lunares.blockfield.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BfIconTest {
    @Test
    void classesHaveDistinctIconsAndUnknownClassesKeepTheKitFallback() {
        assertEquals(
                Set.of(
                        BfIcon.ASSAULT,
                        BfIcon.MARKSMAN,
                        BfIcon.ENGINEER,
                        BfIcon.SNIPER,
                        BfIcon.DRONE),
                Set.of("assault", "mark", "tech", "sniper", "drone").stream()
                        .map(BfIcon::forClass)
                        .collect(Collectors.toSet()));
        assertEquals(BfIcon.KIT, BfIcon.forClass("unknown"));
        assertEquals(BfIcon.SUN, BfIcon.forAction("vote:time.day"));
    }
}
