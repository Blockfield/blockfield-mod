package com.lunares.blockfield.common.grenade;

/**
 * Two throw intents: OVERHAND (LMB) keeps the mod's native speed, UNDERHAND (RMB) is slowed by the
 * server.
 */
public enum GrenadeThrowMode {
    OVERHAND(0),
    UNDERHAND(1);

    private final int wireId;

    GrenadeThrowMode(int wireId) {
        this.wireId = wireId;
    }

    /** Explicit IDs, never enum ordinals, for a future versioned protocol. */
    public int wireId() {
        return wireId;
    }

    /** An eventual decoder must reject invalid IDs, not silently select a throw mode. */
    public static GrenadeThrowMode fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> OVERHAND;
            case 1 -> UNDERHAND;
            default -> throw new IllegalArgumentException("Unknown grenade throw mode: " + wireId);
        };
    }
}
