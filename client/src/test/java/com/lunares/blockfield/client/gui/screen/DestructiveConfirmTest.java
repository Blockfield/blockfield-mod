package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DestructiveConfirmTest {
    @Test
    void confirmationNeedsTheSameActionWithinTheWindowAndIsConsumed() {
        assertFalse(DestructiveConfirm.confirm("leave", 1000));
        assertTrue(DestructiveConfirm.armed("leave", 1100));
        assertFalse(DestructiveConfirm.confirm("kill", 1100));
        assertFalse(DestructiveConfirm.armed("leave", 1200));
        assertTrue(DestructiveConfirm.confirm("kill", 1200));
        assertFalse(DestructiveConfirm.armed("kill", 1300));
        assertFalse(DestructiveConfirm.confirm("leave", 2000));
        assertFalse(DestructiveConfirm.armed("leave", 5000));
        assertFalse(DestructiveConfirm.confirm("leave", 5000));
        assertTrue(DestructiveConfirm.confirm("leave", 5100));
    }
}
