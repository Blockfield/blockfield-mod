package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.PoseDebugPacket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoseDebugOverlayTest {
    private static final String PARTS = "[[0,0,0,1,1,1]]";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() throws Exception {
        PoseDebugOverlay.reset();
    }

    private static void tick(double live) {
        PoseDebugOverlay.onPacket(
                new PoseDebugPacket(
                        "{\"kind\":\"tick\",\"tick\":100,\"rows\":[{\"id\":7,\"live\":["
                                + live
                                + ",0,0],\"feet\":["
                                + live
                                + ",0,0],\"frameTick\":100,\"teleports\":2,\"parts\":"
                                + PARTS
                                + "}]}"));
    }

    private static void rewind(long seq, double start, double impact) {
        PoseDebugOverlay.onPacket(
                new PoseDebugPacket(
                        "{\"kind\":\"rewind\",\"seq\":"
                                + seq
                                + ",\"tick\":101,\"rows\":[{\"id\":7,\"flight\":2,\"distance\":55,"
                                + "\"label\":\"view:rewound:incomplete\","
                                + "\"labelImpact\":\"view:rewound:incomplete\",\"start\":["
                                + start
                                + ",0,0],\"impact\":["
                                + impact
                                + ",0,0],\"parts\":"
                                + PARTS
                                + "}]}"));
    }

    /** The reply arrives a round trip later, when the model has moved on. */
    @Test
    void aRewindIsMeasuredFromTheFrameItAnswersNotTheLatest() {
        long seq = PoseDebugOverlay.noteQuery(Map.of(7, new double[] {10, 0, 0}));
        tick(11.1);
        rewind(seq, 10, 10.56);
        var row = PoseDebugOverlay.targetRow(7, new double[] {10.5, 0, 0});
        assertEquals(10.5, row.getAsJsonArray("drawn").get(0).getAsDouble(), 1e-9);
        assertEquals(11.1, row.getAsJsonArray("live").get(0).getAsDouble(), 1e-9);
        assertEquals(0, row.getAsJsonArray("offStart").get(0).getAsDouble(), 1e-9);
        assertEquals(0.56, row.getAsJsonArray("offImpact").get(0).getAsDouble(), 1e-9);
        assertEquals(2, row.get("flight").getAsInt());
        assertEquals(2, row.get("teleports").getAsInt());
    }

    @Test
    void aMalformedPacketIsDroppedAndKeepsTheLastFrame() {
        tick(11.1);
        assertDoesNotThrow(() -> PoseDebugOverlay.onPacket(new PoseDebugPacket("not json{")));
        assertDoesNotThrow(
                () -> PoseDebugOverlay.onPacket(new PoseDebugPacket("{\"kind\":\"tick\"}")));
        var row = PoseDebugOverlay.targetRow(7, new double[] {10.5, 0, 0});
        assertEquals(11.1, row.getAsJsonArray("live").get(0).getAsDouble(), 1e-9);
    }

    @Test
    void theFirstQueryIsDueAtOnceThenOncePerInterval() {
        assertTrue(PoseDebugOverlay.queryDue(5_000_000_000L));
        assertFalse(PoseDebugOverlay.queryDue(5_049_000_000L));
        assertTrue(PoseDebugOverlay.queryDue(5_050_000_000L));
    }

    @Test
    void aReplyToAForgottenQueryIsDropped() {
        long seq = PoseDebugOverlay.noteQuery(Map.of(7, new double[] {10, 0, 0}));
        for (int i = 0; i < 64; i++) PoseDebugOverlay.noteQuery(Map.of(7, new double[] {0, 0, 0}));
        rewind(seq, 10, 10.56);
        assertFalse(PoseDebugOverlay.targetRow(7, new double[] {10, 0, 0}).has("offImpact"));
    }

    @Test
    void aTargetWithoutServerDataHasOnlyItsDrawnPosition() {
        var row = PoseDebugOverlay.targetRow(9, new double[] {1, 2, 3});
        assertTrue(row.has("drawn"));
        assertFalse(row.has("live"));
        assertFalse(row.has("offStart"));
    }

    @Test
    void dumpWritesTheFrameLogAndEmptiesIt(@TempDir Path dir) throws Exception {
        PoseDebugOverlay.record("{\"ns\":1}");
        PoseDebugOverlay.record("{\"ns\":2}");
        var file = dir.resolve("nested/frames.ndjson");
        assertEquals(2, PoseDebugOverlay.dump(file.toString()));
        assertEquals(java.util.List.of("{\"ns\":1}", "{\"ns\":2}"), Files.readAllLines(file));
        assertEquals(0, PoseDebugOverlay.dump(file.toString()));
    }
}
