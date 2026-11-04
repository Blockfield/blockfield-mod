package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationTypes.Pose;
import net.minecraft.util.Mth;

/** Pinned TaCZ bf8 third-person selection and non-PA formulas; transient evaluation only. */
public final class TaczPose {
    private TaczPose() {}

    /**
     * Native {@code InnerThirdPersonManager.setRotationAnglesHead} stop gate, checked before any
     * evaluator: no TaCZ gun, {@code SLEEPING}, {@code onClimbable()}, {@code isSwimming()} or
     * {@code FALL_FLYING} calls {@code stopAllAnimation} and returns without hold/aim formulas.
     */
    public static boolean stopAll(AnimationState.Tacz tacz, AnimationState.Vanilla vanilla) {
        var flags = vanilla.flags();
        return tacz == null
                || vanilla.pose() == Pose.SLEEPING
                || flags.climbing()
                || flags.swimming()
                || vanilla.pose() == Pose.FALL_FLYING;
    }

    /** Null lower means native prone selection leaves its incumbent untouched. */
    public record Selection(String lower, String upper) {}

    /** Inputs come from U1's vanilla checkpoint and the policy-selected R walk/domain. */
    public static Selection select(
            Pose pose,
            boolean swimming,
            boolean passenger,
            boolean onGround,
            boolean creativeFlying,
            boolean sprinting,
            float walkSpeed,
            float rawAim) {
        if (!Float.isFinite(walkSpeed) || !Float.isFinite(rawAim))
            throw new PaGraph.Unsupported("Nonfinite TaCZ selection input");
        boolean prone = pose == Pose.SWIMMING && !swimming;
        boolean flying = !onGround && creativeFlying;
        boolean moving = !flying && (sprinting || walkSpeed > .05);
        String lower =
                prone
                        ? null
                        : passenger
                                ? "ride_lower"
                                : flying
                                        ? "hold_lower"
                                        : sprinting || walkSpeed > .05
                                                ? pose == Pose.CROUCHING
                                                        ? "crouch_walk_lower"
                                                        : sprinting ? "run_lower" : "walk_lower"
                                                : pose == Pose.CROUCHING
                                                        ? "crouch_lower"
                                                        : "hold_lower";
        String upper =
                prone
                        ? rawAim > 0
                                ? !flying && walkSpeed > .05 ? "lie_move" : "lie_aim"
                                : moving ? "lie_move" : "lie"
                        : rawAim > 0
                                ? "aim_upper"
                                : moving
                                        ? pose == Pose.CROUCHING
                                                ? "crouch_walk_upper"
                                                : sprinting ? "run_upper" : "walk_upper"
                                        : "hold_upper";
        return new Selection(lower, upper);
    }

    /**
     * Mutates a fresh six-part BodyRig parameter array (head, torso, left/right arm, left/right
     * leg; each x,y,z,xRot,yRot,zRot). Never pass retained checkpoint or live model storage.
     * Unknown names follow ThirdPersonManager's default lookup, not the minigun branch.
     */
    public static void fallback(float[] parts, float rawAim, String kind) {
        if (parts.length != 36 || !Float.isFinite(rawAim))
            throw new PaGraph.Unsupported("Invalid TaCZ fallback input");
        if ("minigun".equals(kind)) {
            parts[10] = parts[4] + .8f;
            double cos = Math.cos(-parts[10]), sin = Math.sin(-parts[10]);
            float rightX = parts[18], leftX = parts[12];
            parts[18] = (float) (rightX * cos);
            parts[20] = (float) (rightX * sin);
            parts[22] = -1f + parts[10];
            parts[21] = -.1f + parts[9];
            parts[12] = (float) (leftX * cos);
            parts[14] = (float) (leftX * sin);
            parts[16] = -.1f + parts[10];
            parts[15] = -1f + parts[9];
        } else {
            float yaw = rawAim <= 0 ? .3f : Mth.lerp(rawAim, .3f, .35f);
            float pitch = rawAim <= 0 ? 1.4f : Mth.lerp(rawAim, 1.4f, 1.6f);
            parts[22] = -yaw + parts[4];
            parts[16] = .8f + parts[4];
            parts[21] = -pitch + parts[3];
            parts[15] = -pitch + parts[3];
        }
    }
}
