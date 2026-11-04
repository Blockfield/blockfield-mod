package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TabletDeployPutAwayTest {

    @Test
    void deploySkipsTabletPutAwayOnlyInsideTheWindow() {
        Tablet.noteDeployment(1_000_000_000L);
        assertTrue(Tablet.freshDeploy(1_000_000_000L));
        assertTrue(Tablet.freshDeploy(1_000_000_000L + Tablet.DEPLOY_PUT_AWAY_WINDOW_NS));
        assertFalse(Tablet.freshDeploy(1_000_000_000L + Tablet.DEPLOY_PUT_AWAY_WINDOW_NS + 1));
    }

    @Test
    void staleDeployDoesNotLeakIntoLaterTabletCloses() {
        Tablet.noteDeployment(0L);
        assertFalse(Tablet.freshDeploy(Tablet.DEPLOY_PUT_AWAY_WINDOW_NS + 1));
        Tablet.noteDeployment(Long.MIN_VALUE);
        assertFalse(Tablet.freshDeploy(System.nanoTime()));
    }
}
