package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EquipmentInspectTest {
    private static final EquipmentInspect.Pose REST = EquipmentInspect.Pose.IDENTITY;

    @Test
    void gestureIsBoundedAndInterruptedByActionsOrOwnerSlotAndStackChanges() {
        var owner = new Object();
        var stack = new Object();
        var state = new EquipmentInspect.State();
        var sequence = EquipmentInspect.Sequence.RPG;
        assertFalse(state.start(owner, 2, stack, sequence, false, 100));
        assertFalse(state.start(owner, 2, stack, null, true, 100));
        assertTrue(state.start(owner, 2, stack, sequence, true, 100));
        assertEquals(REST, state.sample(owner, 2, stack, true, 100));
        assertFalse(state.start(owner, 2, stack, sequence, true, 200));
        assertNotEquals(REST, state.sample(owner, 2, stack, true, 700));
        assertEquals(REST, state.sample(owner, 2, stack, true, 100 + sequence.duration));
        assertEquals(REST, state.sample(owner, 2, stack, true, 200 + sequence.duration));
        for (int cause = 0; cause < 4; cause++) {
            assertTrue(state.start(owner, 2, stack, sequence, true, 4000));
            assertEquals(
                    REST,
                    state.sample(
                            cause == 0 ? new Object() : owner,
                            cause == 1 ? 3 : 2,
                            cause == 2 ? new Object() : stack,
                            cause != 3,
                            4400));
            assertEquals(REST, state.sample(owner, 2, stack, true, 4600));
        }
        assertTrue(state.start(owner, 2, stack, sequence, true, 5000));
        assertEquals(REST, state.sample(owner, 2, stack, true, 4999));
        assertEquals(REST, state.sample(owner, 2, stack, true, 5400));
        assertTrue(state.start(owner, 2, stack, sequence, true, 5500));
        assertEquals(sequence.sample(500), state.sample(owner, 2, stack, true, 6000));
    }

    @Test
    void authoredSequencesSettleWithoutFreezingAndKeepTurnsWithinWristRange() {
        var rpg = EquipmentInspect.Sequence.RPG;
        assertTrue(rpg.sample(1000).yaw() > 20);
        assertTrue(rpg.sample(2150).yaw() < -15);
        assertTrue(rpg.sample(2900).pitch() < rpg.sample(1000).pitch());
        assertNotEquals(rpg.sample(1000), rpg.sample(1300));
        assertTrue(rpg.sample(150).y() < 0);
    }

    @Test
    void passingKeyframesCarriesVelocityInsteadOfStoppingAtEveryTarget() {
        var sequence = EquipmentInspect.Sequence.RPG;
        float incoming = sequence.sample(470).yaw() - sequence.sample(469).yaw();
        float outgoing = sequence.sample(471).yaw() - sequence.sample(470).yaw();
        assertTrue(incoming > .025f);
        assertEquals(incoming, outgoing, .001f);
    }

    @Test
    void everySequenceHasSmoothBoundedEntryTurnsAndReturn() {
        for (var sequence : new EquipmentInspect.Sequence[] {EquipmentInspect.Sequence.RPG}) {
            assertEquals(REST, sequence.sample(-1));
            assertEquals(REST, sequence.sample(0));
            assertEquals(REST, sequence.sample(sequence.duration));
            assertEquals(REST, sequence.sample(sequence.duration + 1));
            var previous = REST;
            for (long time = 1; time <= sequence.duration; time++) {
                var pose = sequence.sample(time);
                assertTrue(Math.abs(pose.x()) <= .25f);
                assertTrue(Math.abs(pose.y()) <= .25f);
                assertTrue(Math.abs(pose.z()) <= .10f);
                assertTrue(Math.abs(pose.pitch()) <= 30);
                assertTrue(Math.abs(pose.yaw()) <= 40);
                assertTrue(Math.abs(pose.roll()) <= 15);
                assertTrue(Math.abs(pose.x() - previous.x()) < .003f);
                assertTrue(Math.abs(pose.y() - previous.y()) < .003f);
                assertTrue(Math.abs(pose.z() - previous.z()) < .003f);
                assertTrue(Math.abs(pose.pitch() - previous.pitch()) < .24f);
                assertTrue(Math.abs(pose.yaw() - previous.yaw()) < .24f);
                assertTrue(Math.abs(pose.roll() - previous.roll()) < .24f);
                previous = pose;
            }
            assertTrue(Math.abs(sequence.sample(1).yaw()) < .001f);
            assertTrue(Math.abs(sequence.sample(sequence.duration - 1).yaw()) < .001f);
        }
    }

    @Test
    void nativeGunAndKnifeAnimationsKeepTheirExistingHandlers() {
        assertTrue(EquipmentInspect.supports("superbwarfare:rpg"));
        // Class equipment inspects through its own animated model (EquipmentAnimations).
        assertFalse(EquipmentInspect.supports("superbwarfare:medical_kit"));
        assertFalse(EquipmentInspect.supports("superbwarfare:scout_drone"));
        assertFalse(EquipmentInspect.supports("tacz:modern_kinetic_gun"));
        assertFalse(EquipmentInspect.supports("lrtactical:dagger"));
        assertFalse(EquipmentInspect.supports("blockfield:command_tablet"));
    }
}
