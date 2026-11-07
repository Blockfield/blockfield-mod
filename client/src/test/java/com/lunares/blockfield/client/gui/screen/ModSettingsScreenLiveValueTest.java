package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Fail-before cover for the 2.79.3 crash: a locked numeric option (boolean default)
// read its live Double, and YACL's BooleanController threw ClassCastException.
class ModSettingsScreenLiveValueTest {
    @Test
    void liveValueOfAnotherTypeFallsBackToTheOptionDefault() {
        assertEquals(false, ModSettingsScreen.sameType(0.5, false));
    }

    @Test
    void liveValueOfTheSameTypeIsShown() {
        assertEquals(0.75, ModSettingsScreen.sameType(0.75, 0.5));
        assertEquals(true, ModSettingsScreen.sameType(true, false));
        assertEquals(
                java.util.concurrent.TimeUnit.SECONDS,
                ModSettingsScreen.sameType(
                        java.util.concurrent.TimeUnit.SECONDS,
                        java.util.concurrent.TimeUnit.MINUTES));
    }
}
