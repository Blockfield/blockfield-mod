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
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return;
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
