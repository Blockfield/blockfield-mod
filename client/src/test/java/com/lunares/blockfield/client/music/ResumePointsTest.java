package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResumePointsTest {
    private static double length(String track) {
        return 180;
    }

    @Test
    void quickReturnResumesTheLatestInterruptedTrackOnce() {
        ResumePoints points = new ResumePoints();
        points.interrupted("a", 40, 1_000);
        points.interrupted("b", 70, 5_000);
        ResumePoints.Point p =
                points.take(List.of("a", "b"), 20_000, 45, 30, ResumePointsTest::length);
        assertEquals("b", p.track());
        assertEquals(70, p.position());
        assertEquals(
                "a",
                points.take(List.of("a", "b"), 20_000, 45, 30, ResumePointsTest::length).track());
        assertNull(points.take(List.of("a", "b"), 20_000, 45, 30, ResumePointsTest::length));
    }

    @Test
    void staleNearTheEndOrForeignTracksStartFresh() {
        ResumePoints points = new ResumePoints();
        points.interrupted("old", 40, 0);
        points.interrupted("ending", 160, 50_000);
        points.interrupted("tactical", 30, 50_000);
        assertNull(points.take(List.of("old", "ending"), 60_000, 45, 30, ResumePointsTest::length));
    }
}
