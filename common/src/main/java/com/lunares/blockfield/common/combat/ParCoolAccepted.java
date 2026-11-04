/* Native ParCool bf7 formulas (alRex), LGPL-3.0; see META-INF/licenses/ParCool-LICENSE.txt. */
package com.lunares.blockfield.common.combat;

import com.alrex.parcool.utilities.BufferUtil;
import com.lunares.blockfield.common.combat.AnimationEvent.Patch;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationSchema.Nullable;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepted ParCool start/stop entries as U1 event patches: exactly what bf7 {@code
 * Action.start/finish} and the other-client callbacks install and store for a remote player. The
 * server supplies the world-derived values those callbacks read; no client selector reruns.
 */
public final class ParCoolAccepted {
    private ParCoolAccepted() {}

    // Constructor-initialized bf7 animator fields; the accepted-patch fixture pins them natively.
    static final float CLING_LEAN_ANGLE = 20;
    static final int BACKWARD_WALL_JUMP_TICKS = 12, WALL_JUMP_TICKS = 11;

    /**
     * Server-owned values the callbacks read at acceptance: HangDown.setup bar/orientation, the
     * QuickTurn turn direction after this start's toggle and the Dive stop water-entry outcome.
     */
    public record Context(
            @Nullable BarAxis hangAxis,
            boolean hangOrthogonal,
            boolean quickTurnRightward,
            boolean diveWaterEntry,
            int notLandingTick,
            boolean skyDiveDoing) {}

    public static Patch start(ActionState row, ByteBuffer payload, Context context, boolean busy) {
        var data = payload.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        data.rewind();
        try {
            var inputs = new ArrayList<ActionInput>();
            AnimatorData animator = null;
            AnimatorData child = null;
            for (ActionInput input : row.inputs()) inputs.add(input);
            switch (row.action()) {
                case CAT_LEAP -> animator = new CatLeap();
                case CHARGE_JUMP -> animator = new ChargeJump();
                case CLIMB_UP -> animator = new ClimbUp();
                case CLING_TO_CLIFF -> {
                    replace(
                            inputs,
                            new ClingInput(
                                    0,
                                    FacingDirection.TO_WALL,
                                    new Vec3(data.getDouble(), 0, data.getDouble())));
                    animator = new ClingToCliff(CLING_LEAN_ANGLE);
                }
                case DIVE -> {
                    double speed = data.getDouble();
                    var old = input(row, DiveInput.class);
                    replace(
                            inputs,
                            new DiveInput(
                                    old.justJumped(),
                                    speed,
                                    speed,
                                    speed,
                                    old.fallingTick(),
                                    old.fromSkyDive(),
                                    old.notLandingTick(),
                                    old.waterEntry()));
                    animator =
                            new DiveHost(
                                    new NodeRef(2),
                                    null,
                                    BufferUtil.getBoolean(data),
                                    false,
                                    false,
                                    0);
                    child = new DiveChild(speed, 0, 0);
                }
                case DODGE -> animator = new Dodge(index(DodgeDirection.values(), data.getInt()));
                case FLIPPING -> {
                    data.position(4);
                    animator = new Flipping(index(FlipDirection.values(), data.getInt()));
                }
                case HANG_DOWN -> {
                    replace(
                            inputs,
                            new HangInput(
                                    data.getDouble(),
                                    0,
                                    context.hangOrthogonal(),
                                    context.hangAxis(),
                                    0,
                                    false));
                    animator = new Hang();
                }
                case HIDE_IN_BLOCK -> {
                    boolean stand = BufferUtil.getBoolean(data);
                    boolean diving = BufferUtil.getBoolean(data);
                    BufferUtil.getBlockPos(data);
                    BufferUtil.getBlockPos(data);
                    BufferUtil.getVec3(data);
                    BufferUtil.getVec3(data);
                    replace(inputs, new HideInput(vector(BufferUtil.getVec3(data)), diving));
                    animator = new HideInBlock(stand, diving);
                }
                case HORIZONTAL_WALL_RUN -> {
                    boolean right = BufferUtil.getBoolean(data);
                    var wall = new Vec3(data.getDouble(), 0, data.getDouble());
                    var run = new Vec3(data.getDouble(), 0, data.getDouble());
                    var old = input(row, HorizontalWallRunInput.class);
                    replace(inputs, new HorizontalWallRunInput(old.bodyYaw(), right, wall, run));
                    animator = new HorizontalWallRun(right, 0);
                }
                case JUMP_FROM_BAR -> animator = new JumpFromBar();
                case QUICK_TURN -> {
                    var angle =
                            new net.minecraft.world.phys.Vec3(data.getDouble(), 0, data.getDouble())
                                    .normalize();
                    replace(
                            inputs,
                            new AcceptedStart(
                                    ActionTag.QUICK_TURN,
                                    0,
                                    context.quickTurnRightward(),
                                    vector(angle),
                                    null,
                                    0,
                                    true));
                }
                case RIDE_ZIPLINE -> {
                    var old = input(row, ZiplineInput.class);
                    replace(
                            inputs,
                            new ZiplineInput(
                                    vector(BufferUtil.getVec3(data)),
                                    old.acceleration(),
                                    old.slope(),
                                    old.gravity(),
                                    old.valid(),
                                    old.rope()));
                    animator = new RideZipline(0, 0);
                }
                case ROLL -> animator = new Roll(index(RollDirection.values(), data.getInt()));
                case SLIDE -> {
                    replace(
                            inputs,
                            new SlideInput(new Vec3(data.getDouble(), 0, data.getDouble())));
                    animator = new Sliding();
                }
                case TAP -> animator = new Tap();
                case VAULT -> {
                    int type = data.get();
                    animator =
                            switch (type) {
                                case 0 -> new SpeedVault(VaultType.RIGHT);
                                case 1 -> new SpeedVault(VaultType.LEFT);
                                case 2 -> new KongVault(0, 0);
                                default -> throw new IllegalArgumentException("Unknown vault type");
                            };
                }
                case VERTICAL_WALL_RUN -> {
                    data.position(12);
                    var old = input(row, VerticalWallRunInput.class);
                    replace(
                            inputs,
                            new VerticalWallRunInput(
                                    old.playerYSpeed(),
                                    new Vec3(
                                            data.getDouble(), data.getDouble(), data.getDouble())));
                    animator = new VerticalWallRun();
                }
                case WALL_JUMP -> {
                    data.position(40);
                    animator =
                            switch (data.get()) {
                                case 0 -> new BackwardWallJump(BACKWARD_WALL_JUMP_TICKS);
                                case 1 -> new WallJump(true, WALL_JUMP_TICKS);
                                case 2 -> new WallJump(false, WALL_JUMP_TICKS);
                                default -> throw new IllegalArgumentException("Unknown wall jump");
                            };
                }
                default -> {}
            }
            var started =
                    new ActionState(
                            row.action(), true, row.doingTick(), row.notDoingTick(), 0, inputs);
            return patch(started, animator, child, busy);
        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Malformed accepted ParCool start", e);
        }
    }

    /** bf7 finish: Dive enters water, Slide falls back to Crawl only without an animator. */
    public static Patch finish(ActionState row, Context context, boolean busy) {
        var inputs = new ArrayList<>(row.inputs());
        AnimatorData animator = null;
        switch (row.action()) {
            case DIVE -> {
                var old = input(row, DiveInput.class);
                replace(
                        inputs,
                        new DiveInput(
                                old.justJumped(),
                                old.initialYVelocityOfLastJump(),
                                old.playerYSpeedOld(),
                                old.playerYSpeed(),
                                old.fallingTick(),
                                context.skyDiveDoing(),
                                context.notLandingTick(),
                                context.diveWaterEntry()));
                if (context.diveWaterEntry()) animator = new DiveIntoWater(context.skyDiveDoing());
            }
            case SLIDE -> {
                if (!busy) animator = new Crawl();
            }
            default -> {}
        }
        var stopped =
                new ActionState(
                        row.action(),
                        false,
                        row.doingTick(),
                        row.notDoingTick(),
                        row.tickFromStarted(),
                        inputs);
        return patch(stopped, animator, null, busy);
    }

    private static Patch patch(
            ActionState row, AnimatorData animator, AnimatorData child, boolean busy) {
        AnimationGraphState fragment;
        if (animator == null) fragment = new AnimationGraphState(List.of(), List.of());
        else {
            var nodes = new ArrayList<Node>();
            nodes.add(new Node(1, PhaseDomain.A, null, new Animator(tag(animator), 0, animator)));
            if (child != null)
                nodes.add(
                        new Node(
                                2,
                                PhaseDomain.A,
                                null,
                                new Animator(AnimatorTag.DIVE_CHILD, 0, child)));
            fragment =
                    new AnimationGraphState(
                            List.of(new Root(0, LayerTag.PARCOOL_ACTIVE, new NodeRef(1))), nodes);
        }
        var outcome = animator == null ? Outcome.NO_CHANGE : Outcome.SELECTED;
        var kind =
                animator == null
                        ? StartKind.INPUT_UPDATE
                        : busy ? StartKind.REPLACE : StartKind.NEW;
        AnimationSchema.validateFinite(row);
        ParCoolActions.validateInputs(row);
        return new Patch(
                LayerTag.PARCOOL_ACTIVE,
                kind,
                outcome,
                fragment,
                List.of(row),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    static AnimatorTag tag(AnimatorData data) {
        return switch (data) {
            case BackwardWallJump ignored -> AnimatorTag.BACKWARD_WALL_JUMP;
            case CatLeap ignored -> AnimatorTag.CAT_LEAP;
            case ChargeJump ignored -> AnimatorTag.CHARGE_JUMP;
            case ClimbUp ignored -> AnimatorTag.CLIMB_UP;
            case ClingToCliff ignored -> AnimatorTag.CLING_TO_CLIFF;
            case Crawl ignored -> AnimatorTag.CRAWL;
            case DiveHost ignored -> AnimatorTag.DIVE_HOST;
            case DiveIntoWater ignored -> AnimatorTag.DIVE_INTO_WATER;
            case Dodge ignored -> AnimatorTag.DODGE;
            case Flipping ignored -> AnimatorTag.FLIPPING;
            case Hang ignored -> AnimatorTag.HANG;
            case HideInBlock ignored -> AnimatorTag.HIDE_IN_BLOCK;
            case HorizontalWallRun ignored -> AnimatorTag.HORIZONTAL_WALL_RUN;
            case JumpFromBar ignored -> AnimatorTag.JUMP_FROM_BAR;
            case KongVault ignored -> AnimatorTag.KONG_VAULT;
            case RideZipline ignored -> AnimatorTag.RIDE_ZIPLINE;
            case Roll ignored -> AnimatorTag.ROLL;
            case Sliding ignored -> AnimatorTag.SLIDING;
            case SpeedVault ignored -> AnimatorTag.SPEED_VAULT;
            case Tap ignored -> AnimatorTag.TAP;
            case VerticalWallRun ignored -> AnimatorTag.VERTICAL_WALL_RUN;
            case WallJump ignored -> AnimatorTag.WALL_JUMP;
            default -> throw new IllegalArgumentException("Not an accepted ParCool animator");
        };
    }

    private static <E> E index(E[] values, int index) {
        if (index < 0 || index >= values.length)
            throw new IllegalArgumentException("Unknown ParCool direction");
        return values[index];
    }

    private static Vec3 vector(net.minecraft.world.phys.Vec3 value) {
        return new Vec3(value.x, value.y, value.z);
    }

    private static <T extends ActionInput> T input(ActionState row, Class<T> type) {
        return ParCoolStages.input(row, type);
    }

    private static void replace(List<ActionInput> inputs, ActionInput value) {
        for (int i = 0; i < inputs.size(); i++)
            if (inputs.get(i).getClass() == value.getClass()) {
                inputs.set(i, value);
                return;
            }
        inputs.add(value);
    }
}
