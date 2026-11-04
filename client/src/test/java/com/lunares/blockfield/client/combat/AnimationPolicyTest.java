package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Policy restore/lock state machine: forced on while connected to a server whose READY armed it,
 * stored values govern after and on every other server.
 */
class AnimationPolicyTest {
    static final com.lunares.blockfield.common.combat.AnimationPolicy SERVER =
            new com.lunares.blockfield.common.combat.AnimationPolicy(
                    0,
                    com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain.A,
                    true,
                    true,
                    true,
                    true,
                    com.lunares.blockfield.common.combat.AnimationPolicy.registered());

    @Test
    void anUnarmedConnectionKeepsTheUserValues() {
        AnimationPolicy.restore();
        AnimationPolicy.install();
        try {
            assertFalse(AnimationPolicy.forcing());
            assertFalse(AnimationPolicy.effectiveParCool("EnableAnimation", false));
            assertFalse(AnimationPolicy.effectiveSmootherCrawl(false));
            assertFalse(AnimationPolicy.lockedSmootherCrawl());
        } finally {
            AnimationPolicy.restore();
        }
    }

    @Test
    void aRefusedConfigurationDisarmsBeforePlay() {
        AnimationPolicy.arm(SERVER);
        AnimationPolicy.disarm();
        AnimationPolicy.install();
        try {
            assertFalse(AnimationPolicy.forcing());
        } finally {
            AnimationPolicy.restore();
        }
    }

    @Test
    void disconnectedLeavesStoredValuesAlone() {
        AnimationPolicy.restore();
        assertFalse(AnimationPolicy.forcing());
        assertFalse(AnimationPolicy.effectiveParCool("EnableAnimation", false));
        assertTrue(AnimationPolicy.effectiveParCool("EnableAnimation", true));
        assertFalse(AnimationPolicy.effectiveParCool("EnableCameraAnimationOfRolling", false));
        assertFalse(AnimationPolicy.effectiveAnimator(false));
        assertFalse(AnimationPolicy.effectiveSmootherCrawl(false));
        assertFalse(AnimationPolicy.lockedParCool("EnableAnimation"));
        assertFalse(AnimationPolicy.lockedSmootherCrawl());
    }

    @Test
    void connectedForcesGeometryEntriesAndLocksThem() {
        AnimationPolicy.arm(SERVER);
        AnimationPolicy.install();
        try {
            assertTrue(AnimationPolicy.forcing());
            for (var name : AnimationPolicy.FORCED_PARCOOL_NAMES)
                assertTrue(AnimationPolicy.effectiveParCool(name, false), name);
            // Personal toggles stay personal even while connected.
            assertFalse(AnimationPolicy.effectiveParCool("EnableCameraAnimationOfRolling", false));
            assertTrue(AnimationPolicy.effectiveAnimator(false));
            assertTrue(AnimationPolicy.effectiveSmootherCrawl(false));
            for (var name : AnimationPolicy.FORCED_PARCOOL_NAMES)
                assertTrue(AnimationPolicy.lockedParCool(name), name);
            assertFalse(AnimationPolicy.lockedParCool("EnableCameraAnimationOfRolling"));
            assertTrue(AnimationPolicy.lockedSmootherCrawl());
        } finally {
            AnimationPolicy.restore();
        }
    }

    @Test
    void restoreIsIdempotentAndRevealsStoredValues() {
        AnimationPolicy.arm(SERVER);
        AnimationPolicy.install();
        AnimationPolicy.restore();
        AnimationPolicy.restore();
        assertFalse(AnimationPolicy.forcing());
        assertFalse(AnimationPolicy.effectiveParCool("EnableFallingAnimation", false));
        assertFalse(AnimationPolicy.lockedParCool("EnableLeanAnimationOfFastRun"));
    }

    @Test
    void registryHasExactlyThe26RegisteredAnimators() {
        assertEquals(26, AnimationPolicy.ANIMATORS.size());
        assertEquals(26, AnimationPolicy.ANIMATORS.stream().distinct().count());
        assertTrue(AnimationPolicy.ANIMATORS.contains("DodgeAnimator"));
        assertTrue(AnimationPolicy.ANIMATORS.contains("CrawlAnimator"));
        assertTrue(AnimationPolicy.ANIMATORS.contains("DiveAnimationHostAnimator"));
    }

    @Test
    void registryIsTheFirst26AnimatorTagsInOrder() {
        var tags = com.lunares.blockfield.common.combat.AnimationTypes.AnimatorTag.values();
        for (int i = 0; i < 26; i++)
            assertEquals(
                    tags[i].name().replace("_", ""),
                    AnimationPolicy.ANIMATORS
                            .get(i)
                            .replace("Animator", "")
                            .replace("DiveAnimationHost", "DiveHost")
                            .toUpperCase(java.util.Locale.ROOT),
                    "tag " + i);
        assertEquals(26, com.lunares.blockfield.common.combat.AnimationPolicy.registered().size());
    }
}
