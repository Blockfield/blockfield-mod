package com.lunares.blockfield.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UiMotionTest {
    @Test
    void reversalIsContinuousAndRepeatedTargetsDoNotRestartTheAnimation() {
        UiMotion motion = new UiMotion();
        motion.to(1f, 0);
        float firstFrame = motion.value(4_000_000);
        assertTrue(firstFrame > 0 && firstFrame < motion.value(8_000_000));
        long middle = UiMotion.DURATION_NS / 2;
        float before = motion.value(middle);
        motion.to(0f, middle);
        assertEquals(before, motion.value(middle));
        motion.to(0f, middle + 1);
        assertEquals(0f, motion.value(middle + UiMotion.DURATION_NS));
        motion.snap(1f);
        assertEquals(1f, motion.value(middle));
    }
}
