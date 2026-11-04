package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationEvent.Decision;
import com.lunares.blockfield.common.combat.AnimationEvent.EventId;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * U7c ParCool checkpoint values shared by the canonical capture (server) and the observer reducer
 * (both evaluators): the complete neutral family value of a new life, the configured-duration
 * definitions its rows name, and the per-boundary journal marker every S[n] carries.
 */
public final class ParCoolCheckpoints {
    private ParCoolCheckpoints() {}

    /**
     * bf7 durations read by RollAnimator/TapAnimator/Vault ({@code getRollMaxTick} 9, {@code
     * getMaxTappingTick} 8, {@code Vault.MAX_TICK} 11): native constants, not observer config.
     */
    public static int duration(ActionTag tag) {
        // Capture workers must not initialize native actions (their class initializers mint IDs).
        return switch (tag) {
            case ROLL -> 9;
            case TAP -> 8;
            case VAULT -> com.alrex.parcool.common.action.impl.Vault.MAX_TICK;
            default -> throw new IllegalArgumentException("No configured ParCool duration");
        };
    }

    /** The life-scoped definition naming one duration; both peers compute the same bytes. */
    public static Definition durationDefinition(long generation, long targetEpoch, ActionTag tag) {
        byte[] bytes =
                ("parcool.duration:" + tag.name() + "=" + duration(tag))
                        .getBytes(StandardCharsets.UTF_8);
        var digest = Digest.hash(bytes);
        return new Definition(
                new ConfigRef(generation, targetEpoch, digest.a() & Long.MAX_VALUE, digest),
                new Bytes(bytes),
                List.of());
    }

    public static List<Definition> definitions(long generation, long targetEpoch) {
        return List.of(
                durationDefinition(generation, targetEpoch, ActionTag.ROLL),
                durationDefinition(generation, targetEpoch, ActionTag.TAP),
                durationDefinition(generation, targetEpoch, ActionTag.VAULT));
    }

    /**
     * The family value of a life before its first canonical boundary: no animator, no passive pose,
     * every action idle with typed empty inputs. The first boundary takes native counters and
     * synced inputs from its own sample; reducer-owned fields start here.
     */
    public static ParCool neutral(long generation, long targetEpoch) {
        var rows = new ArrayList<ActionState>();
        for (ActionTag tag : ActionTag.values()) {
            List<ActionInput> inputs =
                    switch (tag) {
                        case SLIDE -> List.of(new SlideInput(null));
                        case HORIZONTAL_WALL_RUN ->
                                List.of(new HorizontalWallRunInput(0, false, null, null));
                        case VERTICAL_WALL_RUN -> List.of(new VerticalWallRunInput(0, null));
                        case WALL_SLIDE -> List.of(new WallSlideInput(null));
                        case HANG_DOWN -> List.of(new HangInput(0, 0, false, null, 0, false));
                        case CLING_TO_CLIFF ->
                                List.of(new ClingInput(0, FacingDirection.TO_WALL, null));
                        case DIVE -> List.of(new DiveInput(false, 0, 0, 0, 0, false, 0, false));
                        case CHARGE_JUMP -> List.of(new ChargeInput(0, 0, 0, false, 0, false));
                        case RIDE_ZIPLINE ->
                                List.of(new ZiplineInput(null, 0, 0, 0.08, false, null));
                        case HIDE_IN_BLOCK -> List.of(new HideInput(null, false));
                        case QUICK_TURN ->
                                List.of(new AcceptedStart(tag, 0, false, null, null, 0, false));
                        case SKY_DIVE -> List.of(new IntentInput(false, false, false, false));
                        case ROLL, TAP, VAULT ->
                                List.of(
                                        new DurationInput(
                                                duration(tag),
                                                (ConfigRef)
                                                        durationDefinition(
                                                                        generation,
                                                                        targetEpoch,
                                                                        tag)
                                                                .ref()));
                        default -> List.of(new NoInput());
                    };
            rows.add(new ActionState(tag, false, 0, 0, -1, inputs));
        }
        var grid = new Grid4(0, 0, 0, 0, 0, 0);
        return new ParCool(
                null,
                new AnimationStartReport.StartKey(0, 0),
                new Option(false, 127),
                new Passive(0, 0, 0),
                rows,
                new HangGrid(grid, 0),
                new DiveGrid(grid));
    }

    /**
     * The ParCool boundary marker of S[n]: an inert record naming S[n]'s committed placement. An
     * observer reducer turns it into the family's NATIVE_TICK input record with the same sequence,
     * so derived starts carry one server-allocated identity on every evaluator.
     */
    public static Decision marker(long tick, long sequence, InputRef input) {
        var inert =
                ParCoolStages.input(new EventId(tick, sequence, NativeStage.NATIVE_TICK), input);
        return new Decision(
                new EventId(tick, sequence, NativeStage.POST_NATIVE_TICK),
                inert.layer(),
                inert.startKind(),
                inert.outcome(),
                0,
                null,
                input,
                inert.patch(),
                0);
    }

    /** Whether {@code decision} has the exact marker shape (stage aside). */
    public static boolean marker(Decision decision) {
        var patch = decision.patch();
        return decision.layer() == LayerTag.PARCOOL_PASSIVE
                && decision.startKind() == StartKind.INPUT_UPDATE
                && decision.outcome() == Outcome.NO_CHANGE
                && decision.predecessor() == null
                && patch.layer() == LayerTag.PARCOOL_PASSIVE
                && patch.outcome() == Outcome.NO_CHANGE
                && patch.replacement().nodes().isEmpty()
                && patch.actionInputs().isEmpty()
                && patch.equipment() == null
                && patch.options() == null
                && patch.tacz() == null
                && patch.lr() == null
                && patch.create() == null
                && patch.sbw() == null
                && patch.vanilla() == null
                && patch.stance() == null;
    }

    /** The boundary record the family reducer consumes for a marker. */
    public static Decision boundary(Decision marker) {
        if (!marker(marker) || marker.id().nativeStage() != NativeStage.POST_NATIVE_TICK)
            throw new PaGraph.Unsupported("ParCool boundary marker shape");
        return ParCoolStages.input(
                new EventId(
                        marker.id().serverTick(),
                        marker.id().eventSequence(),
                        NativeStage.NATIVE_TICK),
                marker.input());
    }

    /** The input identity of a decoded checkpoint: its tick and placement content digest. */
    public static InputRef input(AnimationState state) {
        return new InputRef(
                state.identity().resourceGeneration(),
                state.identity().targetEpoch(),
                state.serverTick(),
                Digest.hash(AnimationSchema.encode(state.placement())));
    }

    /**
     * The ParCool family's compatibility identity (U9 {@code compat.family.parcool}): the reducer
     * versions this code replays, the native action registration and the native and mirrored
     * duration constants its removal/selection read, and the forced geometry settings. Both peers
     * compute it from their own loaded classes; the JAR bytes themselves are {@code
     * compat.native.parcool}.
     */
    public static String familyIdentity() {
        var text = new StringBuilder("parcool.family.v1;reducer=u5.stages.v4+u7c.compose.v1");
        text.append(";actions=");
        for (ActionTag tag : ActionTag.values())
            text.append(ParCoolActions.type(tag).getName()).append(',');
        for (ActionTag tag : List.of(ActionTag.ROLL, ActionTag.TAP, ActionTag.VAULT))
            text.append(";duration.").append(tag.name()).append('=').append(duration(tag));
        text.append(";native=")
                .append(com.alrex.parcool.common.action.impl.ChargeJump.JUMP_ANIMATION_TICK)
                .append(',')
                .append(com.alrex.parcool.common.action.impl.ChargeJump.JUMP_MAX_CHARGE_TICK)
                .append(',')
                .append(com.alrex.parcool.common.action.impl.Dodge.MAX_TICK)
                .append(',')
                .append(com.alrex.parcool.common.action.impl.Vault.MAX_TICK);
        text.append(";mirrored=")
                .append(ParCoolStages.CAT_LEAP_TICKS)
                .append(',')
                .append(ParCoolStages.CLIMB_UP_TICKS)
                .append(',')
                .append(ParCoolStages.DIVE_INTO_WATER_TICKS)
                .append(',')
                .append(ParCoolStages.FLIPPING_TICKS)
                .append(',')
                .append(ParCoolStages.JUMP_FROM_BAR_TICKS)
                .append(',')
                .append(ParCoolStages.SLIDING_TRANSITION_TICKS)
                .append(',')
                .append(ParCoolStages.VERTICAL_WALL_RUN_TICKS)
                .append(',')
                .append(ParCoolStages.TAP_TICKS)
                .append(',')
                .append(ParCoolStages.DIVE_IN_AIR_TICKS)
                .append(',')
                .append(ParCoolStages.SKY_DIVE_TRANSITION_TICKS)
                .append(',')
                .append(ParCoolAccepted.CLING_LEAN_ANGLE)
                .append(',')
                .append(ParCoolAccepted.BACKWARD_WALL_JUMP_TICKS)
                .append(',')
                .append(ParCoolAccepted.WALL_JUMP_TICKS);
        text.append(";forced=EnableAnimation,EnableFallingAnimation,EnableLeanAnimationOfFastRun");
        return text.toString();
    }

    public static boolean family(LayerTag layer) {
        return layer == LayerTag.PARCOOL_ACTIVE || layer == LayerTag.PARCOOL_PASSIVE;
    }
}
