/* Native ParCool bf7 formulas (alRex), LGPL-3.0; see META-INF/licenses/ParCool-LICENSE.txt. */
package com.lunares.blockfield.common.combat;

import com.alrex.parcool.utilities.VectorUtil;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;

/** Ordered geometry-only yaw consequences; the caller commits their result into R placement. */
public final class ParCoolYaw {
    private ParCoolYaw() {}

    /**
     * Baseline is pre-ParCool placement. Hang reads its retained R grid, never this function's
     * already-adjusted body yaw. The partial belongs to canonical action time at this R boundary.
     * Removal/selection must have resolved the optional animator before this operation.
     */
    public static Angles apply(
            Angles baseline, ParCoolFrame frame, float actionPartial, Time hangAgeR) {
        AnimationSchema.validateFinite(baseline);
        if (!(actionPartial >= 0 && actionPartial < 1))
            throw new IllegalArgumentException("ParCool yaw partial outside [0,1)");
        float yaw = baseline.yRot();
        float head = baseline.yHeadRot();
        float body = baseline.yBodyRot();
        float oldBody = baseline.yBodyRotO();
        for (var action : ParCoolFrame.actions(frame.state())) {
            if (!action.doing()) continue;
            switch (action.action()) {
                case CLING_TO_CLIFF -> {
                    var input = input(action, ClingInput.class);
                    if (input.clingWallDirection() == null) break;
                    var wall = vector(input.clingWallDirection());
                    if (input.facingDirection() == null)
                        throw new IllegalArgumentException("Missing ParCool cling facing");
                    switch (input.facingDirection()) {
                        case TO_WALL -> body = (float) VectorUtil.toYawDegree(wall);
                        case RIGHT_AGAINST_WALL -> {
                            body =
                                    (float)
                                            VectorUtil.toYawDegree(
                                                    wall.yRot((float) (-Math.PI / 2)));
                            oldBody = body;
                        }
                        case LEFT_AGAINST_WALL -> {
                            body = (float) VectorUtil.toYawDegree(wall.yRot((float) (Math.PI / 2)));
                            oldBody = body;
                        }
                    }
                }
                case HANG_DOWN -> {
                    var input = input(action, HangInput.class);
                    if (input.barAxis() == null) break;
                    if (!input.valid()) throw new IllegalArgumentException("Missing Hang yaw seed");
                    body = ParCoolReducers.hangYaw(frame.state().hangGrid(), hangAgeR);
                }
                case HORIZONTAL_WALL_RUN -> {
                    var input = input(action, HorizontalWallRunInput.class);
                    if (input.runningDirection() == null) break;
                    var run = vector(input.runningDirection());
                    var look = VectorUtil.fromYawDegree(head);
                    double difference =
                            Math.asin(
                                    new net.minecraft.world.phys.Vec3(
                                                    look.x() * run.x() + look.z() * run.z(),
                                                    0,
                                                    -look.x() * run.z() + look.z() * run.x())
                                            .normalize()
                                            .z());
                    if (Math.abs(difference) > Math.PI / 4)
                        yaw =
                                (float)
                                        VectorUtil.toYawDegree(
                                                run.yRot(
                                                        (float)
                                                                (-Math.signum(difference)
                                                                        * Math.PI
                                                                        / 4)));
                    oldBody = body = input.bodyYaw();
                }
                case QUICK_TURN -> {
                    var input = input(action, AcceptedStart.class);
                    if (input.direction() == null) break;
                    if (!input.valid())
                        throw new IllegalArgumentException("Missing accepted QuickTurn outcome");
                    float phase = (action.doingTick() + actionPartial) / 4;
                    yaw =
                            (float)
                                    VectorUtil.toYawDegree(
                                            vector(input.direction())
                                                    .yRot(
                                                            (float)
                                                                    (Math.PI
                                                                            * phase
                                                                            * (input.flag()
                                                                                    ? -1
                                                                                    : 1))));
                }
                case SKY_DIVE -> body = head;
                case VERTICAL_WALL_RUN -> {
                    var input = input(action, VerticalWallRunInput.class);
                    if (input.wallDirection() != null) {
                        head = (float) VectorUtil.toYawDegree(vector(input.wallDirection()));
                        oldBody = body = head;
                    }
                }
                default -> {}
            }
        }
        if (frame.state().animator() != null) {
            var node =
                    frame.graph().nodes().stream()
                            .filter(value -> value.id() == frame.state().animator().id())
                            .findFirst()
                            .orElseThrow();
            if (!(node.data() instanceof Animator animator))
                throw new IllegalArgumentException("Invalid ParCool yaw root");
            switch (animator.state()) {
                case Roll roll -> {
                    if (roll.direction() == RollDirection.LEFT) body = head + 5;
                    else if (roll.direction() == RollDirection.RIGHT) body = head - 5;
                }
                case Dodge dodge -> {
                    if (dodge.direction() == DodgeDirection.LEFT) body = head + 5;
                    else if (dodge.direction() == DodgeDirection.RIGHT) body = head - 5;
                }
                case RideZipline ignored -> {
                    body = yaw;
                    oldBody = baseline.yRotO();
                }
                default -> {}
            }
        }
        var result =
                new Angles(
                        baseline.yRotO(),
                        yaw,
                        baseline.xRotO(),
                        baseline.xRot(),
                        oldBody,
                        body,
                        baseline.yHeadRotO(),
                        head);
        AnimationSchema.validateFinite(result);
        return result;
    }

    private static <T extends ActionInput> T input(ActionState state, Class<T> type) {
        return state.inputs().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing ParCool yaw input"));
    }

    private static net.minecraft.world.phys.Vec3 vector(Vec3 value) {
        return new net.minecraft.world.phys.Vec3(value.x(), value.y(), value.z());
    }
}
