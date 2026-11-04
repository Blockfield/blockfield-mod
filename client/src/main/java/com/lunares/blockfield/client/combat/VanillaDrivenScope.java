package com.lunares.blockfield.client.combat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Driven-remote vanilla getter scope (U2, ADR4 §§4.2/5.3).
 *
 * <p>Holds the authoritative R velocity pair and its fraction for each driven entity. The {@code
 * CombatPlayerVelocityMixin} override of {@code AbstractClientPlayer.getDeltaMovementLerped}
 * returns the native interpolation of that pair at p_R, irrespective of the renderer's ambient p_A;
 * the global partial replacement alone would be incorrect. U8 installs one frame per main-frame
 * write and clears it on Remove; every other pass reuses the same frozen frame.
 */
public final class VanillaDrivenScope {
    /** Authoritative R velocity endpoints plus the assigned fraction for one driven entity. */
    public record DrivenFrame(Vec3 previous, Vec3 current, float partialR) {
        public DrivenFrame {
            if (previous == null || current == null)
                throw new IllegalArgumentException("Driven velocity endpoints are required");
            if (!(partialR >= 0 && partialR <= 1))
                throw new IllegalArgumentException("Driven p_R outside [0,1]: " + partialR);
        }

        /** Native {@code prev.lerp(cur, (double) p)} order over the R pair. */
        public Vec3 lerped() {
            return previous.lerp(current, (double) partialR);
        }
    }

    private static final Map<Integer, DrivenFrame> FRAMES = new HashMap<>();

    private VanillaDrivenScope() {}

    /** Installs (or replaces) the frozen R frame for a driven entity. */
    public static void install(int entityId, DrivenFrame frame) {
        FRAMES.put(entityId, frame);
    }

    /** Forgets a removed entity; a missing entry is native behavior, never an error. */
    public static void clear(int entityId) {
        FRAMES.remove(entityId);
    }

    /** Forgets every frame on disconnect/scene reset. */
    public static void clearAll() {
        FRAMES.clear();
    }

    /** The driven frame, or null when the entity renders natively. */
    public static DrivenFrame frame(int entityId) {
        return FRAMES.get(entityId);
    }

    /**
     * Driven velocity for the velocity-lerp getter: the R pair at p_R when a frame is installed,
     * else null to run the native path. The ambient render partial is never consulted here.
     */
    public static Vec3 lerpedVelocity(int entityId) {
        DrivenFrame frame = FRAMES.get(entityId);
        return frame == null ? null : frame.lerped();
    }
}
