package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SprintControlTest {
    @Test
    void releasingSprintKeyStopsActiveSprint() {
        assertTrue(SprintControl.shouldStopSprint(false, true));
    }

    @Test
    void heldSprintKeyDoesNotOverrideGameRestrictions() {
        assertFalse(SprintControl.shouldStopSprint(true, true));
        assertFalse(SprintControl.shouldStopSprint(true, false));
    }

    @Test
    void idlePlayerNeedsNoSprintMutation() {
        assertFalse(SprintControl.shouldStopSprint(false, false));
    }
}
