package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationState.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;
import static com.lunares.blockfield.common.combat.ParCoolGraph.*;

import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.Actions;
import java.util.ArrayList;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/** Native visual-input capture/import; never invokes action physics, selectors, start or finish. */
public final class ParCoolActions {
    private ParCoolActions() {}

    public static Class<? extends Action> type(ActionTag tag) {
        if (Actions.LIST.size() != ActionTag.values().length)
            throw new IllegalArgumentException("ParCool action registry changed");
        Class<? extends Action> type = Actions.LIST.get(tag.id());
        String expected = tag.name().replace("_", "");
        if (!type.getSimpleName().toUpperCase(java.util.Locale.ROOT).equals(expected))
            throw new IllegalArgumentException("ParCool action registration order changed");
        return type;
    }

    /**
     * The journal supplies constructor outcomes, config/resource refs, target intent and grid
     * seeds. They are not reconstructed from an observer's config or a later action state.
     */
    public static ActionState capture(Action action, ActionState authoritative, Player player) {
        requireType(action, authoritative.action());
        validateInputs(authoritative);
        var inputs = new ArrayList<ActionInput>();
        for (ActionInput input : authoritative.inputs()) {
            inputs.add(
                    switch (input) {
                        case SlideInput ignored -> new SlideInput(vector(action, "slidingVec"));
                        case HorizontalWallRunInput ignored ->
                                new HorizontalWallRunInput(
                                        number(action, "bodyYaw"), bool(action, "wallIsRightward"),
                                        vector(action, "runningWallDirection"),
                                                vector(action, "runningDirection"));
                        case VerticalWallRunInput ignored ->
                                new VerticalWallRunInput(
                                        decimal(action, "playerYSpeed"),
                                        vector(action, "wallDirection"));
                        case WallSlideInput ignored ->
                                new WallSlideInput(vector(action, "leanedWallDirection"));
                        case HangInput seed ->
                                new HangInput(
                                        decimal(action, "bodySwingAngleFactor"),
                                        number(action, "armSwingAmount"),
                                        bool(action, "orthogonalToBar"),
                                        bar(get(action, "hangingBarAxis")),
                                        seed.yawSeed(),
                                        seed.valid());
                        case ClingInput ignored ->
                                new ClingInput(
                                        number(action, "armSwingAmount"),
                                        facing(get(action, "facingDirection")),
                                        vector(action, "clingWallDirection"));
                        case DiveInput outcome ->
                                new DiveInput(
                                        bool(action, "justJumped"),
                                        decimal(action, "initialYVelocityOfLastJump"),
                                        decimal(action, "playerYSpeedOld"),
                                        decimal(action, "playerYSpeed"),
                                        integer(action, "fallingTick"),
                                        outcome.fromSkyDive(),
                                        outcome.notLandingTick(),
                                        outcome.waterEntry());
                        case ChargeInput sync ->
                                new ChargeInput(
                                        integer(action, "chargeTick"),
                                        integer(action, "lastChargeTick"),
                                        integer(action, "notChargeTick"),
                                        bool(action, "started"),
                                        sync.syncTick(),
                                        sync.syncValid());
                        case ZiplineInput dependency ->
                                new ZiplineInput(
                                        vector(action, "endOffsetFromStart"),
                                        decimal(action, "acceleration"),
                                        decimal(action, "slope"),
                                        player.getAttributeValue(Attributes.GRAVITY),
                                        get(action, "ridingZipline") != null,
                                        dependency.rope());
                        case HideInput ignored ->
                                new HideInput(
                                        vector(action, "lookDirection"),
                                        bool(action, "startedFromDiving"));
                        default -> input;
                    });
        }
        return new ActionState(
                authoritative.action(),
                action.isDoing(),
                action.getDoingTick(),
                action.getNotDoingTick(),
                action.getTickFromLastStarted(),
                inputs);
    }

    /**
     * Server tick sample: native counters plus the inputs whose native field has a producer on the
     * dedicated server (Normal sync, common tick/start callbacks, the gravity attribute). Start
     * data that only client callbacks store stays the authoritative accepted value.
     */
    public static ActionState synced(Action action, ActionState authoritative, Player player) {
        requireType(action, authoritative.action());
        validateInputs(authoritative);
        var inputs = new ArrayList<ActionInput>();
        for (ActionInput input : authoritative.inputs()) {
            inputs.add(
                    switch (input) {
                        case HorizontalWallRunInput o ->
                                new HorizontalWallRunInput(
                                        number(action, "bodyYaw"),
                                        o.wallIsRightward(),
                                        o.runningWallDirection(),
                                        o.runningDirection());
                        case VerticalWallRunInput o ->
                                new VerticalWallRunInput(
                                        decimal(action, "playerYSpeed"), o.wallDirection());
                        case WallSlideInput ignored ->
                                new WallSlideInput(vector(action, "leanedWallDirection"));
                        case HangInput o ->
                                new HangInput(
                                        o.bodySwingAngleFactor(),
                                        number(action, "armSwingAmount"),
                                        o.orthogonalToBar(),
                                        o.barAxis(),
                                        o.yawSeed(),
                                        o.valid());
                        case ClingInput o ->
                                new ClingInput(
                                        number(action, "armSwingAmount"),
                                        o.facingDirection(),
                                        o.clingWallDirection());
                        case DiveInput o ->
                                new DiveInput(
                                        o.justJumped(),
                                        o.initialYVelocityOfLastJump(),
                                        decimal(action, "playerYSpeedOld"),
                                        decimal(action, "playerYSpeed"),
                                        o.fallingTick(),
                                        o.fromSkyDive(),
                                        o.notLandingTick(),
                                        o.waterEntry());
                        case ChargeInput o -> {
                            int charge = integer(action, "chargeTick");
                            yield new ChargeInput(
                                    charge,
                                    o.lastChargeTick(),
                                    o.notChargeTick(),
                                    o.started(),
                                    charge,
                                    true);
                        }
                        case ZiplineInput o ->
                                new ZiplineInput(
                                        vector(action, "endOffsetFromStart"),
                                        decimal(action, "acceleration"),
                                        decimal(action, "slope"),
                                        player.getAttributeValue(Attributes.GRAVITY),
                                        get(action, "ridingZipline") != null,
                                        o.rope());
                        case HideInput ignored ->
                                new HideInput(
                                        vector(action, "lookDirection"),
                                        bool(action, "startedFromDiving"));
                        default -> input;
                    });
        }
        return new ActionState(
                authoritative.action(),
                action.isDoing(),
                action.getDoingTick(),
                action.getNotDoingTick(),
                action.getTickFromLastStarted(),
                inputs);
    }

    public static ActionTag tag(Action action) {
        for (ActionTag tag : ActionTag.values()) if (type(tag) == action.getClass()) return tag;
        throw new IllegalArgumentException("Unregistered ParCool action");
    }

    /**
     * Restore only into a newly constructed driven scratch action, never a live physics attachment.
     */
    public static Action materialize(ActionState state) {
        AnimationSchema.validateFinite(state);
        validateInputs(state);
        try {
            Action result = type(state.action()).getConstructor().newInstance();
            set(result, "doing", state.doing());
            set(result, "doingTick", state.doingTick());
            set(result, "notDoingTick", state.notDoingTick());
            set(result, "tickFromStarted", state.tickFromStarted());
            for (ActionInput input : state.inputs()) restore(result, state.action(), input);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Pinned ParCool action constructor unavailable", e);
        }
    }

    private static void restore(Action action, ActionTag tag, ActionInput input) {
        switch (input) {
            case SlideInput data -> set(action, "slidingVec", nativeVector(data.slidingVec()));
            case HorizontalWallRunInput data -> {
                set(action, "bodyYaw", data.bodyYaw());
                set(action, "wallIsRightward", data.wallIsRightward());
                set(action, "runningWallDirection", nativeVector(data.runningWallDirection()));
                set(action, "runningDirection", nativeVector(data.runningDirection()));
            }
            case VerticalWallRunInput data -> {
                set(action, "playerYSpeed", data.playerYSpeed());
                set(action, "wallDirection", nativeVector(data.wallDirection()));
            }
            case WallSlideInput data ->
                    set(action, "leanedWallDirection", nativeVector(data.leanedWallDirection()));
            case HangInput data -> {
                set(action, "bodySwingAngleFactor", data.bodySwingAngleFactor());
                set(action, "armSwingAmount", data.armSwingAmount());
                set(action, "orthogonalToBar", data.orthogonalToBar());
                set(
                        action,
                        "hangingBarAxis",
                        data.barAxis() == null
                                ? null
                                : com.alrex.parcool.common.action.impl.HangDown.BarAxis.valueOf(
                                        data.barAxis().name()));
            }
            case ClingInput data -> {
                set(action, "armSwingAmount", data.armSwingAmount());
                set(action, "facingDirection", nativeFacing(data.facingDirection()));
                set(action, "clingWallDirection", nativeVector(data.clingWallDirection()));
            }
            case DiveInput data -> {
                set(action, "justJumped", data.justJumped());
                set(action, "initialYVelocityOfLastJump", data.initialYVelocityOfLastJump());
                set(action, "playerYSpeedOld", data.playerYSpeedOld());
                set(action, "playerYSpeed", data.playerYSpeed());
                set(action, "fallingTick", data.fallingTick());
            }
            case ChargeInput data -> {
                set(action, "chargeTick", data.chargeTick());
                set(action, "lastChargeTick", data.lastChargeTick());
                set(action, "notChargeTick", data.notChargeTick());
                set(action, "started", data.started());
            }
            case ZiplineInput data -> {
                set(action, "endOffsetFromStart", nativeVector(data.endOffsetFromStart()));
                set(action, "acceleration", data.acceleration());
                set(action, "slope", data.slope());
                // The native animator does not read rope/valid; never materialize live rope
                // physics.
            }
            case HideInput data -> {
                set(action, "lookDirection", nativeVector(data.lookDirection()));
                set(action, "startedFromDiving", data.startedFromDiving());
            }
            case AcceptedStart data -> {
                if (data.action() != tag)
                    throw new IllegalArgumentException("Wrong ParCool accepted action");
                if (tag == ActionTag.QUICK_TURN) {
                    set(action, "startAngle", nativeVector(data.direction()));
                    set(action, "turnRightward", data.flag());
                }
            }
            default -> {
                /* Authoritative context is consumed by the reducer, not action physics. */
            }
        }
    }

    private static void requireType(Action action, ActionTag tag) {
        if (action.getClass() != type(tag))
            throw new IllegalArgumentException("Wrong ParCool action type");
    }

    static void validateInputs(ActionState state) {
        var seen = new java.util.HashSet<Class<?>>();
        for (ActionInput input : state.inputs()) {
            if (!seen.add(input.getClass()))
                throw new IllegalArgumentException("Duplicate ParCool action input");
            ActionTag expected =
                    switch (input) {
                        case SlideInput ignored -> ActionTag.SLIDE;
                        case HorizontalWallRunInput ignored -> ActionTag.HORIZONTAL_WALL_RUN;
                        case VerticalWallRunInput ignored -> ActionTag.VERTICAL_WALL_RUN;
                        case WallSlideInput ignored -> ActionTag.WALL_SLIDE;
                        case HangInput ignored -> ActionTag.HANG_DOWN;
                        case ClingInput ignored -> ActionTag.CLING_TO_CLIFF;
                        case DiveInput ignored -> ActionTag.DIVE;
                        case ChargeInput ignored -> ActionTag.CHARGE_JUMP;
                        case ZiplineInput ignored -> ActionTag.RIDE_ZIPLINE;
                        case HideInput ignored -> ActionTag.HIDE_IN_BLOCK;
                        case AcceptedStart start -> start.action();
                        default -> state.action();
                    };
            if (expected != state.action())
                throw new IllegalArgumentException("Wrong ParCool action input family");
        }
        Class<?> required =
                switch (state.action()) {
                    case SLIDE -> SlideInput.class;
                    case HORIZONTAL_WALL_RUN -> HorizontalWallRunInput.class;
                    case VERTICAL_WALL_RUN -> VerticalWallRunInput.class;
                    case WALL_SLIDE -> WallSlideInput.class;
                    case HANG_DOWN -> HangInput.class;
                    case CLING_TO_CLIFF -> ClingInput.class;
                    case DIVE -> DiveInput.class;
                    case CHARGE_JUMP -> ChargeInput.class;
                    case RIDE_ZIPLINE -> ZiplineInput.class;
                    case HIDE_IN_BLOCK -> HideInput.class;
                    case QUICK_TURN -> AcceptedStart.class;
                    case SKY_DIVE -> IntentInput.class;
                    case ROLL, TAP, VAULT -> DurationInput.class;
                    default -> null;
                };
        if (required != null && !seen.contains(required))
            throw new IllegalArgumentException(
                    "Missing ParCool action input: " + required.getSimpleName());
    }

    private static Vec3 vector(Action action, String field) {
        var value = (net.minecraft.world.phys.Vec3) get(action, field);
        return value == null ? null : new Vec3(value.x, value.y, value.z);
    }

    private static net.minecraft.world.phys.Vec3 nativeVector(Vec3 value) {
        return value == null
                ? null
                : new net.minecraft.world.phys.Vec3(value.x(), value.y(), value.z());
    }

    private static BarAxis bar(Object value) {
        return value == null ? null : BarAxis.valueOf(((Enum<?>) value).name());
    }

    private static FacingDirection facing(Object value) {
        if (value == null) return null;
        return switch (((Enum<?>) value).name()) {
            case "ToWall" -> FacingDirection.TO_WALL;
            case "RightAgainstWall" -> FacingDirection.RIGHT_AGAINST_WALL;
            case "LeftAgainstWall" -> FacingDirection.LEFT_AGAINST_WALL;
            default -> throw new IllegalArgumentException("Unknown ParCool facing");
        };
    }

    private static Object nativeFacing(FacingDirection value) {
        if (value == null) return null;
        return switch (value) {
            case TO_WALL ->
                    com.alrex.parcool.common.action.impl.ClingToCliff.FacingDirection.ToWall;
            case RIGHT_AGAINST_WALL ->
                    com.alrex.parcool.common.action.impl.ClingToCliff.FacingDirection
                            .RightAgainstWall;
            case LEFT_AGAINST_WALL ->
                    com.alrex.parcool.common.action.impl.ClingToCliff.FacingDirection
                            .LeftAgainstWall;
        };
    }
}
