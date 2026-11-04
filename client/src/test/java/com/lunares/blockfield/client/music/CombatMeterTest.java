package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatMeterTest {
    @Test
    void scoreHalvesEveryHalfLife() {
        CombatMeter meter = new CombatMeter(4);
        meter.add(8, 0);
        assertEquals(4, meter.value(4000), 1e-9);
        assertEquals(2, meter.value(8000), 1e-9);
    }

    @Test
    void oneAutomaticBurstIsCappedPerShooter() {
        CombatMeter meter = new CombatMeter(4);
        for (int i = 0; i < 30; i++) meter.shot(1, 0.6, 1.5, i * 30L);
        assertTrue(meter.value(900) <= 1.5, "30 bullets in a second from one gun");
    }

    @Test
    void severalShootersAddUp() {
        CombatMeter meter = new CombatMeter(4);
        for (int second = 0; second < 3; second++) {
            for (int shooter = 0; shooter < 3; shooter++) {
                for (int i = 0; i < 5; i++)
                    meter.shot(shooter, 0.6, 1.5, second * 1000L + i * 100L);
            }
        }
        assertTrue(
                meter.value(3000) >= 6, "three players shooting for three seconds is a firefight");
    }
}
