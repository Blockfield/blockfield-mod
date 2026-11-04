package com.lunares.blockfield.client.combat.oracle;

import org.junit.jupiter.api.Test;

/** Closed-world checks for the fixture registry itself (runs transformed like every fixture). */
class AnimSyncFixtureRegistryTest {
    @Test
    void unknownFamilyIsRejected() {
        try {
            AnimSyncFixtureRegistry.requireKnownFamily("notAFamily");
            throw new AssertionError("unknown family must be rejected");
        } catch (AssertionError expected) {
            if (!expected.getMessage().contains("notAFamily")) throw expected;
        }
    }

    @Test
    void fixtureIdsAreStableKebabCase() {
        AnimSyncFixtureRegistry.requireFixtureId("humanoid-setup-anim-determinism");
        try {
            AnimSyncFixtureRegistry.requireFixtureId("Humanoid_Setup_Anim");
            throw new AssertionError("non-kebab fixture id must be rejected");
        } catch (AssertionError expected) {
            if (!expected.getMessage().contains("Humanoid_Setup_Anim")) throw expected;
        }
    }
}
