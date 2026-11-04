package com.lunares.blockfield.common.combat;

import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier.PartModifier;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.util.Mth;

/**
 * Reviewed ports of the two native rotation-layer functions (TaCZ bf8 and LR bf2 {@code
 * AdjustmentYRotModifier.apply}), in their bytecode operation order. Natively both read the global
 * DeltaTracker partial and the entity's old/current facing; driven evaluation supplies those as
 * {@link Inputs}. On the client the facing pairs are collapsed to the R-resolved value and the
 * partial is the common A fraction, so {@code rotLerp(p, v, v)} reproduces the native bits.
 */
public final class PaAdjustments {
    private PaAdjustments() {}

    /** Native {@code 0.017453292f} literal used by both functions. */
    private static final float DEG = 0.017453292f;

    /**
     * @param localScreen native early-out for the local player while a screen is open; always false
     *     for remote evaluation
     * @param swimming {@code Entity.isSwimming()}, distinct from the SWIMMING pose
     * @param fixedHand TaCZ display {@code 3rd_fixed_hand}; LR ignores it
     */
    public record Inputs(
            float partial,
            float yBodyRotO,
            float yBodyRot,
            float yHeadRotO,
            float yHeadRot,
            float xRotO,
            float xRot,
            boolean riding,
            boolean swimming,
            boolean swimPose,
            boolean fixedHand,
            boolean localScreen) {}

    public static Function<String, Optional<PartModifier>> tacz(Inputs in) {
        return part -> apply(in, part, false);
    }

    public static Function<String, Optional<PartModifier>> lr(Inputs in) {
        return part -> apply(in, part, true);
    }

    static Optional<PartModifier> apply(Inputs in, String part, boolean lr) {
        if (in.localScreen()) return Optional.empty();
        if (in.riding() && "body".equals(part)) return Optional.empty();
        float body = Mth.rotLerp(in.partial(), in.yBodyRotO(), in.yBodyRot());
        float head = Mth.rotLerp(in.partial(), in.yHeadRotO(), in.yHeadRot());
        float pitchRaw = Mth.lerp(in.partial(), in.xRotO(), in.xRot());
        float yaw = Mth.clamp(Mth.wrapDegrees(head - body), -85.0f, 85.0f);
        float pitch = Mth.wrapDegrees(pitchRaw);
        return switch (part) {
            case "body" ->
                    Optional.of(
                            !in.swimming() && in.swimPose()
                                    ? rotation(0, 0, -yaw * DEG)
                                    : rotation(0, -yaw * DEG, 0));
            case "head" -> Optional.of(rotation(pitch * DEG, 0, 0));
            case "leftArm", "rightArm" -> {
                if (lr) yield Optional.of(rotation(Mth.clamp(pitch, -15.0f, 15.0f) * DEG, 0, 0));
                yield in.fixedHand() ? Optional.empty() : Optional.of(rotation(pitch * DEG, 0, 0));
            }
            default -> Optional.empty();
        };
    }

    private static PartModifier rotation(float x, float y, float z) {
        return new PartModifier(new Vec3f(x, y, z), Vec3f.ZERO);
    }
}
