package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeldEquipmentMovementTest {
    @Test
    void movementRequiresGroundedActualSpeedAndHasBoundedAmplitude() {
        assertEquals(
                EquipmentInspect.Pose.IDENTITY, HeldEquipmentMovement.sample(1, 0, true, true, 1));
        assertEquals(
                EquipmentInspect.Pose.IDENTITY, HeldEquipmentMovement.sample(1, 1, false, true, 1));
        var run = HeldEquipmentMovement.sample(.5f, 1, true, true, 1);
        var walk = HeldEquipmentMovement.sample(.5f, 1, true, false, 1);
        assertTrue(Math.abs(run.x()) > Math.abs(walk.x()));
        assertEquals(run, HeldEquipmentMovement.sample(.5f, 10, true, true, 1));
        var opposite = HeldEquipmentMovement.sample(.5f, 1, true, true, -1);
        assertEquals(-run.x(), opposite.x());
        assertEquals(-run.roll(), opposite.roll());
        assertEquals(run.y(), opposite.y());
        for (int i = 0; i <= 200; i++) {
            var movement = HeldEquipmentMovement.sample(i / 100f, 1, true, true, 1);
            assertTrue(Math.abs(movement.x()) <= .035f);
            assertTrue(Math.abs(movement.y()) <= .018f);
            assertTrue(Math.abs(movement.z()) <= .006f);
            assertTrue(Math.abs(movement.pitch()) <= .8f);
            assertTrue(Math.abs(movement.roll()) <= 1.5f);
        }
    }
}
