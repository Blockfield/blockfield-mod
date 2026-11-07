package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** World-free: no Minecraft bootstrap, runs on both sides. */
class HitregTelemetryTest {
    @Test
    void tickQuantilesUseFixedBuckets() {
        var telemetry = new HitregTelemetry();
        for (int i = 0; i < 100; i++) telemetry.recordTick(12_000_000L);
        var report = telemetry.finish();
        assertEquals(15.0, report.tickMsP50());
        assertEquals(15.0, report.tickMsP99());
    }

    @Test
    void emptyMatchReportsZeroWithoutInfinity() {
        var report = new HitregTelemetry().finish();
        assertEquals(0, report.tickMsP50());
        assertEquals(0, report.tickMsP99());
        assertEquals(0, report.fallbackShare());
        assertTrue(report.formatLine("m").contains("shots=0"));
    }

    @Test
    void fallbackShareCountsNonExactTickets() {
        var telemetry = new HitregTelemetry();
        telemetry.noteShot("exact", 0, 0, "hit");
        telemetry.noteShot("stale", 3, 5, "miss");
        telemetry.noteShot("omitted", 1, 0, "miss");
        telemetry.noteShot("fallback", 2, 4, "hit");
        var report = telemetry.finish();
        assertEquals(4, report.shots());
        assertEquals(0.75, report.fallbackShare());
        assertEquals(1L, report.counters().get("shotsTicket:exact"));
        assertEquals(1L, report.counters().get("shotsTicket:stale"));
        assertEquals(1L, report.counters().get("shotsTicket:omitted"));
        assertEquals(1L, report.counters().get("shotsTicket:fallback"));
        assertEquals(2L, report.counters().get("shotsResult:hit"));
        assertEquals(2L, report.counters().get("shotsResult:miss"));
    }

    @Test
    void disputesAreBounded() {
        var telemetry = new HitregTelemetry();
        for (int i = 0; i < HitregTelemetry.MAX_DISPUTES + 3; i++) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("victim", "v" + i);
            telemetry.noteDispute(sample);
        }
        var report = telemetry.finish();
        assertEquals(HitregTelemetry.MAX_DISPUTES, report.disputes().size());
        assertEquals(3L, report.counters().get("disputesDropped"));
    }

    @Test
    void counterRegistryIsCapped() {
        var telemetry = new HitregTelemetry();
        for (int i = 0; i < HitregTelemetry.MAX_COUNTERS + 10; i++) telemetry.count("k" + i);
        var report = telemetry.finish();
        assertTrue(report.counters().size() <= HitregTelemetry.MAX_COUNTERS + 1);
        assertEquals(10L, report.counters().get("telemetryCounterEvictions"));
    }

    @Test
    void overflowQuantilesClampToLastBucket() {
        var telemetry = new HitregTelemetry();
        for (int i = 0; i < 98; i++) telemetry.recordTick(12_000_000L);
        for (int i = 0; i < 2; i++) telemetry.recordTick(300_000_000L);
        var report = telemetry.finish();
        assertEquals(250.0, report.tickMsP99());
        assertTrue(report.tickMsP99Overflow());
        assertTrue((Boolean) report.toMap().get("tickMsP99Overflow"));
        assertTrue(report.formatLine("m").contains("tickMsP99=>250"));
        assertTrue(Double.isFinite((Double) report.toMap().get("tickMsP99")));
    }

    @Test
    void overflowAgeQuantileClampsToLastBucket() {
        var telemetry = new HitregTelemetry();
        telemetry.noteShot("exact", 100, 100, "hit");
        telemetry.noteShot("exact", 0, 0, "hit");
        telemetry.noteShot("exact", 0, 0, "hit");
        telemetry.noteShot("exact", 0, 0, "hit");
        var report = telemetry.finish();
        assertEquals(0, report.ageP50());
        var overflow = new HitregTelemetry();
        for (int i = 0; i < 3; i++) overflow.noteShot("exact", 100, 100, "hit");
        overflow.noteShot("exact", 0, 0, "hit");
        assertEquals(16, overflow.finish().ageP50());
        assertEquals(16, overflow.finish().rewindP50());
        assertTrue(overflow.finish().ageP50Overflow());
        assertTrue(overflow.finish().rewindP50Overflow());
        assertTrue(overflow.finish().formatLine("m").contains("ageP50=>16"));
    }

    @Test
    void nullDisputesAreDroppedNotThrown() {
        var telemetry = new HitregTelemetry();
        assertFalse(telemetry.noteDispute(null));
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("victim", "v");
        partial.put("subcause", null);
        assertTrue(telemetry.noteDispute(partial));
        var report = telemetry.finish();
        assertEquals(1, report.disputes().size());
        assertEquals(1L, report.counters().get("disputesDropped"));
        assertJsonClosed(report.toMap());
    }

    @Test
    void nonJsonDisputeValuesAreDropped() {
        var telemetry = new HitregTelemetry();
        Map<String, Object> nan = new LinkedHashMap<>();
        nan.put("ratio", Double.NaN);
        assertFalse(telemetry.noteDispute(nan));
        Map<String, Object> exotic = new LinkedHashMap<>();
        exotic.put("victim", java.util.UUID.randomUUID());
        exotic.put("kind", java.math.RoundingMode.UP);
        exotic.put("inf", Double.POSITIVE_INFINITY);
        assertFalse(telemetry.noteDispute(exotic));
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("victim", "v");
        mixed.put("age", 3L);
        mixed.put("bad", Float.NaN);
        assertTrue(telemetry.noteDispute(mixed));
        var report = telemetry.finish();
        assertEquals(1, report.disputes().size());
        assertEquals(Map.of("victim", "v", "age", 3L), report.disputes().get(0));
        assertEquals(2L, report.counters().get("disputesDropped"));
        assertJsonClosed(report.toMap());
    }

    @Test
    void reportMapHoldsOnlyJsonClosedValues() {
        var telemetry = new HitregTelemetry();
        telemetry.noteShot("exact", 1, 2, "hit");
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("victim", "v");
        sample.put("age", 1L);
        telemetry.noteDispute(sample);
        Map<String, Object> map = telemetry.finish().toMap();
        assertJsonClosed(map);
    }

    private static void assertJsonClosed(Object value) {
        if (value == null) return;
        if (value instanceof String || value instanceof Boolean) return;
        if (value instanceof Double d) {
            assertTrue(Double.isFinite(d));
            return;
        }
        if (value instanceof Float f) {
            assertTrue(Float.isFinite(f));
            return;
        }
        if (value instanceof Number) return;
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                assertInstanceOf(String.class, entry.getKey());
                assertJsonClosed(entry.getValue());
            }
            return;
        }
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) assertJsonClosed(item);
            return;
        }
        fail("Non-JSON value: " + value.getClass());
    }
}
