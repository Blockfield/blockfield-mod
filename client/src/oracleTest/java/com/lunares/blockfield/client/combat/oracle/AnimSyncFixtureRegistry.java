package com.lunares.blockfield.client.combat.oracle;

/** Stable family-unit API, shared with the server oracle. */
public final class AnimSyncFixtureRegistry {
    private AnimSyncFixtureRegistry() {}

    public static final java.util.Set<String> KNOWN_FAMILIES =
            com.lunares.blockfield.oracle.OracleFixtureRegistry.KNOWN_FAMILIES;

    public static void requireKnownFamily(String family) {
        com.lunares.blockfield.oracle.OracleFixtureRegistry.requireKnownFamily(family);
    }

    public static void requireFixtureId(String id) {
        com.lunares.blockfield.oracle.OracleFixtureRegistry.requireFixtureId(id);
    }
}
