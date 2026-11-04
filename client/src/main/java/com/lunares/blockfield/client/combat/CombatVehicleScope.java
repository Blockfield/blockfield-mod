package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Driven-vehicle R scope (U6, ADR4 §§4.5/5.3).
 *
 * <p>Holds the authoritative R endpoint pairs for each driven vehicle. Kind 1 (boat/minecart)
 * carries origin plus yaw/pitch pairs; kind 2 (SBW) additionally carries roll, turret and gun
 * pairs. Every sample uses the frame's frozen p_R; the renderer's ambient p_A is never consulted.
 * U8 installs one frame per driven entity at main-frame entry from the decoded checkpoint and
 * clears it on Remove; every pass (main, shadow, other) reuses the same frozen frame, so no pass
 * advances clocks, creates starts or persists mutations.
 */
public final class CombatVehicleScope {
    /** Authoritative R endpoints plus the assigned fraction for one driven vehicle. */
    public record VehicleFrame(
            Vec3 previousOrigin,
            Vec3 origin,
            float yawO,
            float yaw,
            float pitchO,
            float pitch,
            float prevRoll,
            float roll,
            float turretYRotO,
            float turretYRot,
            float turretXRotO,
            float turretXRot,
            float gunYRotO,
            float gunYRot,
            float gunXRotO,
            float gunXRot,
            float partialR,
            long revision) {
        public VehicleFrame {
            if (previousOrigin == null || origin == null)
                throw new IllegalArgumentException("Driven vehicle origins are required");
            if (!(partialR >= 0 && partialR <= 1))
                throw new IllegalArgumentException("Driven p_R outside [0,1]: " + partialR);
        }

        /** Native double interpolation order over the R origin pair. */
        public Vec3 originAtR() {
            return new Vec3(
                    Mth.lerp((double) partialR, previousOrigin.x, origin.x),
                    Mth.lerp((double) partialR, previousOrigin.y, origin.y),
                    Mth.lerp((double) partialR, previousOrigin.z, origin.z));
        }

        /** Native angle wrap/clamp order is the caller's; sampling here is a plain rotLerp. */
        public float yawAtR() {
            return Mth.rotLerp(partialR, yawO, yaw);
        }

        /** Plain rotLerp over the R pitch pair. */
        public float pitchAtR() {
            return Mth.rotLerp(partialR, pitchO, pitch);
        }
    }

    private static final Map<Integer, VehicleFrame> FRAMES = new HashMap<>();
    // The decoded SBW layout reads of the record each driven vehicle was last written from.
    private static final Map<Integer, List<AnimationState.VehicleRead>> READS = new HashMap<>();

    private CombatVehicleScope() {}

    /** Installs (or replaces) the frozen R frame for a driven vehicle. */
    public static void install(int entityId, VehicleFrame frame) {
        FRAMES.put(entityId, frame);
    }

    /** Forgets a removed entity; a missing entry is native behavior, never an error. */
    public static void clear(int entityId) {
        FRAMES.remove(entityId);
        READS.remove(entityId);
    }

    /** Forgets every frame on disconnect/scene reset. */
    public static void clearAll() {
        FRAMES.clear();
        READS.clear();
    }

    /** Retains the decoded layout reads of the exact record a vehicle was written from. */
    public static void retainReads(int entityId, List<AnimationState.VehicleRead> reads) {
        READS.put(entityId, reads);
    }

    /** The retained layout reads of a driven vehicle, or null when it has none. */
    public static List<AnimationState.VehicleRead> reads(int entityId) {
        return READS.get(entityId);
    }

    /** The driven frame, or null when the entity renders natively. */
    public static VehicleFrame frame(int entityId) {
        return FRAMES.get(entityId);
    }

    /**
     * Native {@code Boat.getPassengerAttachmentPoint} over a driven R yaw (pinned vanilla 1.21.1
     * bytecode: local offset {@code (0, heightFactor, xOffset)} rotated by {@code -yaw *
     * DEG_TO_RAD} through {@code Vec3.yRot}). The caller supplies the already-resolved offset:
     * single passenger {@code getSinglePassengerXOffset()}, first of several {@code 0.2}, later
     * {@code -0.6} ({@code +0.2} for animals), height {@code dimensions.height() * (8/9)} for
     * bamboo else {@code / 3}.
     */
    public static Vec3 boatAttachment(double xOffset, double height, float yawR) {
        return new Vec3(0, height, xOffset).yRot(-yawR * Mth.DEG_TO_RAD);
    }
}
