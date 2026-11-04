/* Native ParCool bf7 formulas (alRex), LGPL-3.0; see META-INF/licenses/ParCool-LICENSE.txt. */
package com.lunares.blockfield.common.combat;

import com.alrex.parcool.utilities.MathUtil;
import com.alrex.parcool.utilities.VectorUtil;
import com.lunares.blockfield.common.combat.AnimationGraphState.KongVault;
import com.lunares.blockfield.common.combat.AnimationGraphState.RideZipline;
import com.lunares.blockfield.common.combat.AnimationGraphState.SkyDiveChild;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import net.minecraft.util.Mth;

/**
 * ADR4 §5.2 primitives. Inputs are decoded values; no entity, key binding or render is retained.
 */
public final class ParCoolReducers {
    private ParCoolReducers() {}

    public static long gridIndex(Time age) {
        return Math.addExact(Math.multiplyExact(age.tick(), 3), (age.alpha() * 3) / 65536);
    }

    public static float gridFraction(Time age) {
        return ((age.alpha() * 3) % 65536) / 65536f;
    }

    /** One native HangDown render recurrence, scheduled by the caller on the rational R grid. */
    public static float hangStep(float previousYaw, Vec3 look, BarAxis axis) {
        if (axis == null) return previousYaw;
        var body = VectorUtil.fromYawDegree(previousYaw).normalize();
        var ideal =
                Math.abs(look.x()) > Math.abs(look.z())
                        ? new net.minecraft.world.phys.Vec3(look.x() > 0 ? 1 : -1, 0, 0)
                        : new net.minecraft.world.phys.Vec3(0, 0, look.z() > 0 ? 1 : -1);
        double difference = Math.acos(body.dot(ideal));
        difference /= 4;
        return (float) VectorUtil.toYawDegree(ideal.yRot((float) difference));
    }

    public static float hangYaw(HangGrid grid, Time age) {
        var values = grid.yaw();
        int index = intervalIndex(values, age);
        float from = endpoint(values, index);
        float fraction = gridFraction(age);
        return fraction == 0 ? from : Mth.rotLerp(fraction, from, endpoint(values, index + 1));
    }

    /**
     * Preserve native singular/nonfinite outcomes; admission decides availability, not this
     * formula.
     */
    public static float diveFactor(double initialYSpeed, double ySpeed) {
        return (float) Math.max(0, 2 / (1 + Math.exp(ySpeed / initialYSpeed - 1)) - 0.9621);
    }

    public static float diveLegDegrees(DiveGrid grid, Time age) {
        var values = grid.factor();
        int index = intervalIndex(values, age);
        float current = endpoint(values, index);
        float previous = index == 0 ? values.previous() : endpoint(values, index - 1);
        float delta = current - previous;
        float fraction = gridFraction(age);
        return -180f
                * (fraction == 0
                        ? delta
                        : MathUtil.lerp(delta, endpoint(values, index + 1) - current, fraction));
    }

    private static int intervalIndex(Grid4 grid, Time age) {
        long index = Math.subtractExact(gridIndex(age), grid.baseIndex());
        if (index < 0 || index > 3 || index == 3 && gridFraction(age) != 0)
            throw new IllegalArgumentException("ParCool grid interval unavailable");
        return (int) index;
    }

    private static float endpoint(Grid4 grid, int index) {
        return switch (index) {
            case 0 -> grid.start();
            case 1 -> grid.first();
            case 2 -> grid.second();
            case 3 -> grid.third();
            default -> throw new IllegalArgumentException("ParCool grid endpoint unavailable");
        };
    }

    /**
     * One integer native oscillator step; rope/valid are not reads of the native remote animator.
     */
    public static RideZipline ziplineStep(RideZipline state, ZiplineInput input, boolean doing) {
        if (!doing) return state;
        double old = state.currentAngleRadian();
        double invSqrt = Mth.invSqrt(input.slope() * input.slope() + 1);
        double xz = -input.acceleration() * invSqrt;
        double y = input.gravity() + input.acceleration() * input.slope() * invSqrt;
        return new RideZipline(old, Mth.lerp(0.1, old, Math.atan2(xz, y)));
    }

    public static Passive passiveStep(Passive state, PassiveInput input, IntentInput targetIntent) {
        int flying = state.flyingAnimationLevel();
        if (targetIntent.forward() && input.flying()) {
            flying++;
            if (flying > 20) flying = 20;
        } else {
            flying--;
            if (flying < 0) flying = 0;
        }
        int falling =
                !input.ground()
                                && input.fallDistance() > 1
                                && !input.flying()
                                && !input.fall()
                                && !input.cling()
                        ? state.fallingAnimationTick() + 1
                        : 0;
        return new Passive(falling, state.flyingAnimationLevel(), flying);
    }

    public static SkyDiveChild skyDiveStep(SkyDiveChild state, IntentInput targetIntent) {
        return new SkyDiveChild(
                leanStep(state.forwardAngleCount(), targetIntent.forward(), targetIntent.back()),
                state.forwardAngleCount(),
                leanStep(state.rightAngleCount(), targetIntent.right(), targetIntent.left()),
                state.rightAngleCount(),
                state.startPitchAngle(),
                state.pitchAngle());
    }

    private static int leanStep(int count, boolean positive, boolean negative) {
        if (positive && !negative) return count < 8 ? count + 1 : count;
        if (negative && !positive) return count > -8 ? count - 1 : count;
        if (count > 0) count--;
        if (count < 0) count++;
        return count;
    }

    /** Consecutive authoritative R endpoints, never a yaw left by an actual renderer. */
    public static KongVault kongStep(KongVault state, float previousBodyYaw, float bodyYaw) {
        var current = VectorUtil.fromYawDegree(bodyYaw);
        var previous = VectorUtil.fromYawDegree(previousBodyYaw);
        float difference =
                (float)
                        Math.atan(
                                (previous.x() * current.z() - current.x() * previous.z())
                                        / (current.x() * previous.x()
                                                + current.z() * previous.z()));
        return new KongVault(difference, state.yRotDifference());
    }

    /**
     * Production bf7 listener: extern/tacz/TaCZAnimationCompat, including null/passive and
     * ROTATION.
     */
    public static Option option(AnimatorTag animator, boolean gun) {
        if (animator == AnimatorTag.DIVE_CHILD || animator == AnimatorTag.SKY_DIVE_CHILD)
            throw new IllegalArgumentException("Dive children are not registered roots");
        if (!gun) return new Option(false, 127);
        if (animator == AnimatorTag.CRAWL) return new Option(true, 0);
        int allowed = 127;
        if (animator == AnimatorTag.FAST_RUNNING) {
            allowed &= ~(1 << AnimationPart.LEFT_LEG.id() | 1 << AnimationPart.RIGHT_LEG.id());
            allowed &= ~(1 << AnimationPart.LEFT_ARM.id() | 1 << AnimationPart.RIGHT_ARM.id());
        } else if (animator == null
                || switch (animator) {
                    case CAT_LEAP,
                            HIDE_IN_BLOCK,
                            HORIZONTAL_WALL_RUN,
                            RIDE_ZIPLINE,
                            ROLL,
                            SLIDING ->
                            true;
                    default -> false;
                }) {
            allowed &= ~(1 << AnimationPart.LEFT_ARM.id() | 1 << AnimationPart.RIGHT_ARM.id());
        }
        return new Option(false, allowed);
    }
}
