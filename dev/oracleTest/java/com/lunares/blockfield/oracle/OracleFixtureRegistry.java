package com.lunares.blockfield.oracle;

import java.util.Set;

/**
 * Closed animation-family set for oracle fixtures (ADR4 sections 4-5). The runner rejects any other
 * family so a typo or an unreviewed family cannot silently join the exact cohort.
 */
public final class OracleFixtureRegistry {
    public static final Set<String> KNOWN_FAMILIES =
            Set.of(
                    "vanilla",
                    "playerAnimator",
                    "tacz",
                    "lr",
                    "parcool",
                    "create",
                    "sbw",
                    "blockfield");

    private OracleFixtureRegistry() {}

    public static void requireKnownFamily(String family) {
        if (!KNOWN_FAMILIES.contains(family))
            throw new AssertionError(
                    "unknown anim-sync oracle family: "
                            + family
                            + "; known families: "
                            + KNOWN_FAMILIES);
    }

    /** Fragment ids are stable kebab-case so manifests stay machine-mergeable. */
    public static void requireFixtureId(String fixtureId) {
        if (fixtureId == null || !fixtureId.matches("[a-z0-9]+(-[a-z0-9]+)*"))
            throw new AssertionError(
                    "oracle fixture id must be stable kebab-case, got: " + fixtureId);
    }
}
