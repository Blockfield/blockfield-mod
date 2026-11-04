/* Native ParCool bf7 formulas (alRex), LGPL-3.0; see META-INF/licenses/ParCool-LICENSE.txt. */
package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationEvent.Decision;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationResources.InputRef;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.common.combat.ParCoolProjection.Result;
import java.util.*;
import net.minecraft.util.Mth;

/**
 * ADR4 §5.2 production reducer behind {@link ParCoolProjection}: the same immutable function
 * advances canonical checkpoints and observer seeds in both runtimes. It never reads an entity, key
 * binding, configuration, render or later sample.
 *
 * <p>Journal contract per family boundary {@code n}:
 *
 * <ul>
 *   <li>{@code NATIVE_TICK}: exactly one inert input record ({@code INPUT_UPDATE}/{@code
 *       NO_CHANGE}, empty patch) whose {@link Decision#input} names the retained canonical sample
 *       for {@code n}, or an older sample as an explicitly recorded hold. The boundary performs the
 *       bf7 tick order (passive, animator, actions), then removal, Dive topology, the five
 *       packetless selections in registration order and finally options.
 *   <li>{@code INTER_TICK_ACCEPT}/{@code PRE_NATIVE_TICK}/{@code END_COMMIT}: accepted ParCool
 *       start/stop patches in sequence order ({@link #event}). Their {@code input} names the sample
 *       supplying equipment for the option recomputation. An observer application in bucket {@code
 *       n} is an {@code END_COMMIT} record of {@code n}.
 *   <li>{@code POST_NATIVE_TICK}: optional recorded packetless outcomes, verified against the
 *       derivation, never applied as topology.
 * </ul>
 */
public final class ParCoolStages implements ParCoolProjection.Stages {
    /** Canonical takes counters from its own tick sample; observers advance their own mirror. */
    public enum Mode {
        CANONICAL,
        OBSERVER
    }

    // Private bf7 animator constants (manifest values); the removal fixture pins each natively.
    static final int CAT_LEAP_TICKS = 20;
    static final int CLIMB_UP_TICKS = 10;
    static final int DIVE_INTO_WATER_TICKS = 9;
    static final int FLIPPING_TICKS = 12;
    static final int JUMP_FROM_BAR_TICKS = 8;
    static final int SLIDING_TRANSITION_TICKS = 5;
    static final int VERTICAL_WALL_RUN_TICKS = 10;
    static final int TAP_TICKS = 8;
    static final int DIVE_IN_AIR_TICKS = 10;
    static final int SKY_DIVE_TRANSITION_TICKS = 6;

    /** Stable transition ordinals of reducer-derived starts within one boundary. */
    public static final int SELECTION = 0, SKY_DIVE_CHILD = 1, PASSIVE = 2;

    private final Mode mode;
    private final Map<InputRef, AnimationState> samples;
    private final Map<Long, List<Start>> eventStarts;
    private final AnimationPolicy policy;

    /**
     * @param samples complete decoded canonical states by their retained input identity
     * @param eventStarts start templates of accepted events by event sequence, in fragment order
     *     (host, Dive child, SkyDive child); node and predecessor IDs are bound by this reducer
     */
    public ParCoolStages(
            Mode mode,
            Map<InputRef, AnimationState> samples,
            Map<Long, List<Start>> eventStarts,
            AnimationPolicy policy) {
        this.mode = Objects.requireNonNull(mode);
        this.samples = Map.copyOf(samples);
        var starts = new HashMap<Long, List<Start>>();
        eventStarts.forEach((sequence, list) -> starts.put(sequence, List.copyOf(list)));
        this.eventStarts = Map.copyOf(starts);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public Result apply(Result base, long tick, NativeStage stage, List<Decision> decisions) {
        // Other families compose separately; their records may share the journal.
        var family = decisions.stream().filter(d -> family(d.layer())).toList();
        return switch (stage) {
            case NATIVE_TICK -> {
                if (family.size() != 1)
                    throw new IllegalArgumentException("ParCool boundary needs one input record");
                yield boundary(base, tick, family.getFirst());
            }
            case POST_NATIVE_TICK -> {
                for (Decision decision : family) verify(base, tick, decision);
                yield base;
            }
            default -> {
                Result result = base;
                for (Decision decision : family) result = event(result, decision);
                yield result;
            }
        };
    }

    /**
     * One accepted start/stop patch, exactly as the native other-client callback installs it:
     * {@code SELECTED} replaces the incumbent animator with the fragment (setAnimator ignores
     * hasAnimator), {@code NO_CHANGE} updates only action rows. Options follow in the same bucket;
     * removal waits for the next integer boundary.
     */
    public Result event(Result base, Decision decision) {
        var patch = decision.patch();
        if (!family(decision.layer())
                || patch.layer() != decision.layer()
                || patch.outcome() != decision.outcome()
                || patch.startKind() != decision.startKind()
                || decision.id().nativeStage() == NativeStage.NATIVE_TICK
                || decision.id().nativeStage() == NativeStage.POST_NATIVE_TICK)
            throw new IllegalArgumentException("ParCool event patch mismatch");
        if (patch.equipment() != null
                || patch.options() != null
                || patch.tacz() != null
                || patch.lr() != null
                || patch.create() != null
                || patch.sbw() != null
                || patch.vanilla() != null
                || patch.stance() != null)
            throw new IllegalArgumentException("Foreign or derived value in a ParCool patch");
        var sample = sample(decision.input(), decision.id().serverTick());
        Work w = new Work(base);
        for (ActionState row : patch.actionInputs()) {
            AnimationSchema.validateFinite(row);
            ParCoolActions.validateInputs(row);
            w.actions.put(row.action(), row);
        }
        long sequence = decision.id().eventSequence();
        var templates = eventStarts.getOrDefault(sequence, List.of());
        switch (decision.outcome()) {
            case SELECTED -> install(w, patch.replacement(), sequence, templates);
            case NO_CHANGE -> {
                if (!patch.replacement().nodes().isEmpty())
                    throw new IllegalArgumentException("NO_CHANGE ParCool event with topology");
                // Input anchors of an action-only event keep their proof; IDs stay disjoint.
                for (Start template : templates)
                    w.starts.add(bind(template, sequence, w.allocate(), 0));
            }
            default -> throw new IllegalArgumentException("Unsupported ParCool event outcome");
        }
        w.option = ParCoolReducers.option(w.tag(), sample.tacz() != null);
        passive(w, decision.id().serverTick(), decision.id().nativeStage(), decision);
        return w.result();
    }

    /** Server END-tick product: the ParCool result and the committed R placement angles. */
    public record Canonical(Result result, Angles angles) {}

    /**
     * Server production of tick {@code n} over its pre-ParCool capture: accepted events, the
     * boundary, the ordered yaw fold at zero partial (ADR4 §5.2), then Kong over those folded
     * endpoints, which observers read from the committed sample. Canonical history is imported as
     * complete checkpoints, never re-derived from folded samples.
     */
    public Canonical canonical(Result previous, long n, List<Decision> decisions) {
        if (mode != Mode.CANONICAL)
            throw new IllegalArgumentException("Canonical production needs canonical mode");
        Result result = previous;
        Result events = previous;
        Decision input = null;
        for (NativeStage stage : NativeStage.values()) {
            var selected = decisions.stream().filter(d -> d.id().nativeStage() == stage).toList();
            if (stage == NativeStage.NATIVE_TICK) {
                events = result;
                for (Decision decision : selected) if (family(decision.layer())) input = decision;
            }
            result = apply(result, n, stage, selected);
        }
        var sample = sample(input.input(), n);
        var state = result.frame().state();
        var hang = ParCoolFrame.actions(state).get(ActionTag.HANG_DOWN.id());
        var hangInput = input(hang, HangInput.class);
        Time hangAge =
                hang.doing() && hangInput.barAxis() != null && hangInput.valid()
                        ? hangAge(state.hangGrid(), Time.whole(1))
                        : Time.whole(0);
        var folded = ParCoolYaw.apply(sample.placement().angles(), result.frame(), 0, hangAge);
        return new Canonical(kong(events, result, folded), folded);
    }

    private Result kong(Result before, Result after, Angles folded) {
        var ref = after.frame().state().animator();
        if (ref == null) return after;
        Work w = new Work(after);
        if (!(w.actor(ref).state() instanceof KongVault)) return after;
        var old =
                before.frame().graph().nodes().stream()
                        .filter(node -> node.id() == ref.id())
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("Kong without predecessor"));
        w.set(
                ref,
                w.actor(ref).tick(),
                ParCoolReducers.kongStep(
                        (KongVault) ((Animator) old.data()).state(),
                        folded.yBodyRotO(),
                        folded.yBodyRot()));
        return w.result();
    }

    /** The inert per-boundary journal record naming the retained sample (or a recorded hold). */
    public static Decision input(AnimationEvent.EventId id, InputRef sample) {
        if (id.nativeStage() != NativeStage.NATIVE_TICK)
            throw new IllegalArgumentException("ParCool input records belong to NATIVE_TICK");
        return new Decision(
                id,
                LayerTag.PARCOOL_PASSIVE,
                StartKind.INPUT_UPDATE,
                Outcome.NO_CHANGE,
                0,
                null,
                sample,
                new AnimationEvent.Patch(
                        LayerTag.PARCOOL_PASSIVE,
                        StartKind.INPUT_UPDATE,
                        Outcome.NO_CHANGE,
                        new AnimationGraphState(List.of(), List.of()),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                0);
    }

    private Result boundary(Result base, long n, Decision input) {
        var patch = input.patch();
        if (input.startKind() != StartKind.INPUT_UPDATE
                || input.outcome() != Outcome.NO_CHANGE
                || patch.outcome() != Outcome.NO_CHANGE
                || !patch.replacement().nodes().isEmpty()
                || !patch.actionInputs().isEmpty()
                || patch.options() != null)
            throw new IllegalArgumentException("ParCool boundary record is not an inert input");
        var sample = sample(input.input(), n);
        if (mode == Mode.CANONICAL && sample.serverTick() != n)
            throw new IllegalArgumentException("Canonical ParCool boundary needs its own sample");
        var canonical = new EnumMap<ActionTag, ActionState>(ActionTag.class);
        for (ActionState row : ParCoolFrame.actions(sample.parcool()))
            canonical.put(row.action(), row);
        Work w = new Work(base);
        for (ActionTag tag : ActionTag.values())
            w.actions.put(
                    tag,
                    mode == Mode.CANONICAL
                            ? canonicalRow(w.action(tag), canonical.get(tag))
                            : observerRow(w.action(tag), canonical.get(tag)));

        // bf7 ActionProcessor.onTick: Animation.tick (passive, then animator) precedes actions.
        var intent = w.input(ActionTag.SKY_DIVE, IntentInput.class);
        var vanilla = sample.vanilla();
        w.passive =
                ParCoolReducers.passiveStep(
                        w.passive,
                        new PassiveInput(
                                0,
                                vanilla.flags().onGround(),
                                vanilla.flags().fallFlying(),
                                vanilla.flags().creativeFlying(),
                                w.doing(ActionTag.CLING_TO_CLIFF),
                                vanilla.fallDistance()),
                        intent);
        if (w.animator != null) tickAnimator(w, sample.placement().angles(), intent);
        for (ActionTag tag : ActionTag.values()) {
            var row = mode == Mode.OBSERVER ? counters(w.action(tag)) : w.action(tag);
            w.actions.put(tag, local(row));
        }
        // Hang yaw belongs to the canonical R placement producer; observers carry that grid.
        if (mode == Mode.CANONICAL) hang(w, sample.placement().angles());
        else w.hangGrid = sample.parcool().hangGrid();

        int removed = w.animator != null && removed(w, sample.stance()) ? w.removeActive() : 0;
        if (w.animator != null && w.actor(w.animator).state() instanceof DiveHost) {
            diveGrid(w, false);
            diveTopology(w, n, input);
        }
        if (w.animator == null) select(w, n, input, removed);
        w.option = ParCoolReducers.option(w.tag(), sample.tacz() != null);
        passive(w, n, NativeStage.NATIVE_TICK, input);
        return w.result();
    }

    private AnimationState sample(InputRef ref, long tick) {
        var sample = samples.get(ref);
        if (sample == null) throw new IllegalArgumentException("ParCool input sample unavailable");
        if (sample.serverTick() > tick)
            throw new IllegalArgumentException("ParCool input sample from a later boundary");
        if (sample.identity().policyGeneration() != policy.generation())
            throw new IllegalArgumentException("ParCool input sample policy generation");
        return sample;
    }

    /** Server tick sample: native counters/inputs, reducer-owned fields kept from the frame. */
    private static ActionState canonicalRow(ActionState own, ActionState sample) {
        boolean fresh = !own.doing() || sample.tickFromStarted() == 1;
        var inputs = new ArrayList<ActionInput>();
        for (ActionInput input : sample.inputs())
            inputs.add(
                    switch (input) {
                        case HangInput s -> {
                            var o = input(own, HangInput.class);
                            yield fresh && sample.doing()
                                    ? new HangInput(
                                            s.bodySwingAngleFactor(),
                                            s.armSwingAmount(),
                                            s.orthogonalToBar(),
                                            s.barAxis(),
                                            o.yawSeed(),
                                            false)
                                    : new HangInput(
                                            o.bodySwingAngleFactor(),
                                            s.armSwingAmount(),
                                            s.orthogonalToBar(),
                                            s.barAxis(),
                                            o.yawSeed(),
                                            o.valid());
                        }
                        case DiveInput s ->
                                new DiveInput(
                                        s.justJumped(),
                                        s.initialYVelocityOfLastJump(),
                                        s.playerYSpeedOld(),
                                        s.playerYSpeed(),
                                        input(own, DiveInput.class).fallingTick(),
                                        s.fromSkyDive(),
                                        s.notLandingTick(),
                                        s.waterEntry());
                        default -> input;
                    });
        return new ActionState(
                sample.action(),
                sample.doing(),
                sample.doingTick(),
                sample.notDoingTick(),
                sample.tickFromStarted(),
                inputs);
    }

    /**
     * Observer mirror: counters and start data come from applied events. Synced/world values come
     * from the named sample only while its canonical action is active, otherwise the last values
     * are held, never a later sample. Charge sync, intent and gravity are not action-gated.
     */
    private static ActionState observerRow(ActionState own, ActionState sample) {
        boolean live = sample.doing();
        var inputs = new ArrayList<ActionInput>();
        for (ActionInput input : own.inputs())
            inputs.add(
                    switch (input) {
                        case HorizontalWallRunInput o -> {
                            var c = optional(sample, HorizontalWallRunInput.class);
                            yield live && c != null
                                    ? new HorizontalWallRunInput(
                                            c.bodyYaw(),
                                            o.wallIsRightward(),
                                            o.runningWallDirection(),
                                            o.runningDirection())
                                    : o;
                        }
                        case VerticalWallRunInput o -> {
                            var c = optional(sample, VerticalWallRunInput.class);
                            yield live && c != null
                                    ? new VerticalWallRunInput(c.playerYSpeed(), o.wallDirection())
                                    : o;
                        }
                        case WallSlideInput o -> held(live, o, sample, WallSlideInput.class);
                        case ClingInput o -> held(live, o, sample, ClingInput.class);
                        case HangInput o -> {
                            var c = optional(sample, HangInput.class);
                            yield live && c != null
                                    ? new HangInput(
                                            o.bodySwingAngleFactor(),
                                            c.armSwingAmount(),
                                            c.orthogonalToBar(),
                                            c.barAxis(),
                                            o.yawSeed(),
                                            o.valid())
                                    : o;
                        }
                        case DiveInput o -> {
                            var c = optional(sample, DiveInput.class);
                            yield live && c != null
                                    ? new DiveInput(
                                            o.justJumped(),
                                            o.initialYVelocityOfLastJump(),
                                            c.playerYSpeedOld(),
                                            c.playerYSpeed(),
                                            o.fallingTick(),
                                            o.fromSkyDive(),
                                            o.notLandingTick(),
                                            o.waterEntry())
                                    : o;
                        }
                        case ChargeInput o -> {
                            var c = optional(sample, ChargeInput.class);
                            yield c == null
                                    ? o
                                    : new ChargeInput(
                                            c.chargeTick(),
                                            o.lastChargeTick(),
                                            o.notChargeTick(),
                                            o.started(),
                                            c.syncTick(),
                                            c.syncValid());
                        }
                        case ZiplineInput o -> {
                            var c = optional(sample, ZiplineInput.class);
                            yield c == null
                                    ? o
                                    : new ZiplineInput(
                                            o.endOffsetFromStart(),
                                            live ? c.acceleration() : o.acceleration(),
                                            live ? c.slope() : o.slope(),
                                            c.gravity(),
                                            live ? c.valid() : o.valid(),
                                            live ? c.rope() : o.rope());
                        }
                        case IntentInput o -> {
                            var c = optional(sample, IntentInput.class);
                            yield c == null ? o : c;
                        }
                        default -> input;
                    });
        return new ActionState(
                own.action(),
                own.doing(),
                own.doingTick(),
                own.notDoingTick(),
                own.tickFromStarted(),
                inputs);
    }

    private static <T extends ActionInput> T held(
            boolean live, T own, ActionState sample, Class<T> type) {
        var canonical = optional(sample, type);
        return live && canonical != null ? canonical : own;
    }

    /** bf7 Action.tick. */
    public static ActionState counters(ActionState row) {
        boolean doing = row.doing();
        int doingTick = doing ? row.doingTick() + 1 : 0;
        int notDoingTick = doing ? 0 : row.notDoingTick() + 1;
        int started =
                row.tickFromStarted() >= 0 ? row.tickFromStarted() + 1 : row.tickFromStarted();
        return new ActionState(row.action(), doing, doingTick, notDoingTick, started, row.inputs());
    }

    /** Other-client reducers after the counter tick: Dive.onClientTick, HangDown damping. */
    private static ActionState local(ActionState row) {
        return switch (row.action()) {
            case DIVE -> {
                var d = input(row, DiveInput.class);
                int falling =
                        row.doing() && (d.playerYSpeed() < 0 || d.fallingTick() > 0)
                                ? d.fallingTick() + 1
                                : 0;
                yield replace(
                        row,
                        new DiveInput(
                                d.justJumped(),
                                d.initialYVelocityOfLastJump(),
                                d.playerYSpeedOld(),
                                d.playerYSpeed(),
                                falling,
                                d.fromSkyDive(),
                                d.notLandingTick(),
                                d.waterEntry()));
            }
            case HANG_DOWN -> {
                if (!row.doing()) yield row;
                var h = input(row, HangInput.class);
                double factor = h.bodySwingAngleFactor();
                if (h.orthogonalToBar()) factor /= 1.05;
                else factor /= 1.5;
                yield replace(
                        row,
                        new HangInput(
                                factor,
                                h.armSwingAmount(),
                                h.orthogonalToBar(),
                                h.barAxis(),
                                h.yawSeed(),
                                h.valid()));
            }
            default -> row;
        };
    }

    private void tickAnimator(Work w, Angles angles, IntentInput intent) {
        var ref = w.animator;
        var actor = w.actor(ref);
        AnimatorData data =
                switch (actor.state()) {
                    case KongVault kong ->
                            ParCoolReducers.kongStep(kong, angles.yBodyRotO(), angles.yBodyRot());
                    case RideZipline zip ->
                            ParCoolReducers.ziplineStep(
                                    zip,
                                    w.input(ActionTag.RIDE_ZIPLINE, ZiplineInput.class),
                                    w.doing(ActionTag.RIDE_ZIPLINE));
                    case DiveHost host -> {
                        var dive = w.actor(host.diveAnimator());
                        w.set(host.diveAnimator(), Math.addExact(dive.tick(), 1), dive.state());
                        if (host.skyDiveAnimator() != null) {
                            var sky = w.actor(host.skyDiveAnimator());
                            w.set(
                                    host.skyDiveAnimator(),
                                    Math.addExact(sky.tick(), 1),
                                    ParCoolReducers.skyDiveStep(
                                            (SkyDiveChild) sky.state(), intent));
                        }
                        boolean transitioning = host.transitioning();
                        int transitionTick = host.transitionTick();
                        if (transitioning) {
                            transitionTick++;
                            if (transitionTick >= SKY_DIVE_TRANSITION_TICKS) {
                                transitionTick = 0;
                                transitioning = false;
                            }
                        }
                        yield new DiveHost(
                                host.diveAnimator(),
                                host.skyDiveAnimator(),
                                host.fromInAir(),
                                host.oldSkyDiveDoing(),
                                transitioning,
                                transitionTick);
                    }
                    default -> actor.state();
                };
        w.set(ref, Math.addExact(actor.tick(), 1), data);
    }

    /** All 26 bf7 {@code shouldRemoved} predicates over the integer mirror. */
    public static boolean removed(
            int tick, AnimatorData data, Map<ActionTag, ActionState> actions) {
        return switch (data) {
            case BackwardWallJump s -> tick >= s.maxTick();
            case CatLeap ignored -> !doing(actions, ActionTag.CAT_LEAP) || tick > CAT_LEAP_TICKS;
            case ChargeJump ignored ->
                    tick >= com.alrex.parcool.common.action.impl.ChargeJump.JUMP_ANIMATION_TICK;
            case ClimbUp ignored -> CLIMB_UP_TICKS <= tick;
            case ClingToCliff ignored -> !doing(actions, ActionTag.CLING_TO_CLIFF);
            case Crawl ignored -> !doing(actions, ActionTag.CRAWL);
            case DiveHost ignored -> !doing(actions, ActionTag.DIVE);
            case DiveIntoWater ignored -> tick >= DIVE_INTO_WATER_TICKS;
            case Dodge ignored -> tick >= com.alrex.parcool.common.action.impl.Dodge.MAX_TICK;
            case FastRunning ignored -> !doing(actions, ActionTag.FAST_RUN);
            case FastSwim ignored -> !doing(actions, ActionTag.FAST_SWIM);
            case Flipping ignored -> !doing(actions, ActionTag.FLIPPING) || tick >= FLIPPING_TICKS;
            case Hang ignored -> !doing(actions, ActionTag.HANG_DOWN);
            case HideInBlock ignored -> !doing(actions, ActionTag.HIDE_IN_BLOCK);
            case HorizontalWallRun ignored -> !doing(actions, ActionTag.HORIZONTAL_WALL_RUN);
            case JumpCharging ignored -> !charging(actions);
            case JumpFromBar ignored -> tick >= JUMP_FROM_BAR_TICKS;
            case KongVault ignored -> tick >= com.alrex.parcool.common.action.impl.Vault.MAX_TICK;
            case RideZipline ignored -> !doing(actions, ActionTag.RIDE_ZIPLINE);
            case Roll ignored -> !doing(actions, ActionTag.ROLL);
            case Sliding ignored -> {
                var slide = actions.get(ActionTag.SLIDE);
                yield !slide.doing() && slide.notDoingTick() >= SLIDING_TRANSITION_TICKS;
            }
            case SpeedVault ignored -> tick >= com.alrex.parcool.common.action.impl.Vault.MAX_TICK;
            case Tap ignored -> tick >= TAP_TICKS;
            case VerticalWallRun ignored -> tick >= VERTICAL_WALL_RUN_TICKS;
            case WallJump s -> tick >= s.maxTick();
            case WallSlide ignored -> !doing(actions, ActionTag.WALL_SLIDE);
            case DiveChild ignored ->
                    throw new IllegalArgumentException("Dive child is not a root");
            case SkyDiveChild ignored ->
                    throw new IllegalArgumentException("Dive child is not a root");
        };
    }

    /** A crawl keeps its limb overlay until the shared flat coefficient finishes the exit. */
    public static boolean removed(
            int tick,
            AnimatorData data,
            Map<ActionTag, ActionState> actions,
            AnimationState.Stance stance) {
        if (data instanceof Crawl && stance != null) {
            var crawl = actions.get(ActionTag.CRAWL);
            int notDoing = crawl == null ? Integer.MAX_VALUE : crawl.notDoingTick();
            if (StanceLayers.retainCrawl(
                    stance.eased(),
                    stance.flat(),
                    stance.toFlat(),
                    stance.fromFlat(),
                    notDoing,
                    stance.age())) return false;
        }
        return removed(tick, data, actions);
    }

    private static boolean removed(Work w, AnimationState.Stance stance) {
        var actor = w.actor(w.animator);
        return removed(actor.tick(), actor.state(), w.actions, stance);
    }

    /** bf7 ChargeJump.isCharging over the synced counter. */
    public static boolean charging(Map<ActionTag, ActionState> actions) {
        var row = actions.get(ActionTag.CHARGE_JUMP);
        return input(row, ChargeInput.class).chargeTick() > 0 && !row.doing();
    }

    private static boolean doing(Map<ActionTag, ActionState> actions, ActionTag tag) {
        return actions.get(tag).doing();
    }

    /**
     * The five tick-installed animators in action registration order; the first eligible one wins,
     * as bf7's hasAnimator gate makes every later candidate a no-op.
     */
    public static AnimatorData selection(Map<ActionTag, ActionState> actions) {
        if (charging(actions)) return new JumpCharging();
        if (doing(actions, ActionTag.CRAWL)) return new Crawl();
        if (doing(actions, ActionTag.FAST_RUN)) return new FastRunning(0);
        if (doing(actions, ActionTag.FAST_SWIM)) return new FastSwim();
        if (doing(actions, ActionTag.WALL_SLIDE)) return new WallSlide();
        return null;
    }

    private void select(Work w, long n, Decision input, int predecessor) {
        var data = selection(w.actions);
        if (data == null) return;
        int id = w.allocate();
        var start = derived(n, NativeStage.POST_NATIVE_TICK, input, id, predecessor, SELECTION);
        w.nodes.put(
                id,
                new Node(
                        id,
                        policy.domain(LayerTag.PARCOOL_ACTIVE),
                        new StartKey(start.eventSequence(), id),
                        new Animator(tag(data), 0, data)));
        w.starts.add(start);
        w.root(new NodeRef(id));
    }

    /**
     * Render-time bf7 checkTransition and SkyDive child creation/drop, resolved once per integer
     * boundary after the tick. A new child's start pitch is the current Dive root function at this
     * boundary (fraction 0), never a last-render field.
     */
    private void diveTopology(Work w, long n, Decision input) {
        var hostRef = w.animator;
        var hostActor = w.actor(hostRef);
        var host = (DiveHost) hostActor.state();
        if (host.fromInAir() && hostActor.tick() < DIVE_IN_AIR_TICKS) return;
        boolean doing = w.doing(ActionTag.SKY_DIVE);
        boolean toggled = doing != host.oldSkyDiveDoing();
        boolean transitioning = host.transitioning() || toggled;
        int transitionTick = toggled ? 0 : host.transitionTick();
        NodeRef sky = host.skyDiveAnimator();
        if (doing) {
            if (sky == null) {
                var dive = (DiveChild) w.actor(host.diveAnimator()).state();
                int id = w.allocate();
                var start =
                        derived(
                                n,
                                NativeStage.POST_NATIVE_TICK,
                                input,
                                id,
                                hostRef.id(),
                                SKY_DIVE_CHILD);
                w.nodes.put(
                        id,
                        new Node(
                                id,
                                policy.domain(LayerTag.PARCOOL_ACTIVE),
                                new StartKey(start.eventSequence(), id),
                                new Animator(
                                        AnimatorTag.SKY_DIVE_CHILD,
                                        0,
                                        new SkyDiveChild(
                                                0,
                                                0,
                                                0,
                                                0,
                                                dive.pitchAngle(),
                                                dive.pitchAngle()))));
                w.starts.add(start);
                sky = new NodeRef(id);
            }
            skyPitch(w, sky);
        } else if (sky != null) {
            if (transitioning) {
                // Exit captures the pitch the transition starts from, then it stays retained.
                if (toggled) skyPitch(w, sky);
            } else {
                w.drop(sky);
                sky = null;
            }
        }
        w.set(
                hostRef,
                hostActor.tick(),
                new DiveHost(
                        host.diveAnimator(),
                        sky,
                        host.fromInAir(),
                        doing,
                        transitioning,
                        transitionTick));
    }

    /** bf7 SkyDiveAnimator.rotatePost pitch at fraction zero of the current integer age. */
    public static float skyPitch(int tick, SkyDiveChild sky) {
        float basePitch =
                tick >= SKY_DIVE_TRANSITION_TICKS
                        ? 90
                        : com.alrex.parcool.utilities.MathUtil.lerp(
                                sky.startPitchAngle(), 90, (tick + 0f) / SKY_DIVE_TRANSITION_TICKS);
        return basePitch + 24 * forwardFactor(sky);
    }

    private static float forwardFactor(SkyDiveChild sky) {
        float phase;
        if (sky.forwardAngleCount() != 0)
            phase =
                    com.alrex.parcool.utilities.MathUtil.lerp(
                                    sky.forwardAngleCountOld(), sky.forwardAngleCount(), 0f)
                            / 8;
        else phase = 0;
        if (phase > 1) phase = 1;
        if (phase < -1) phase = -1;
        if (phase > 0) return com.alrex.parcool.utilities.EasingFunctions.CubicInOut(phase);
        if (phase < 0) return -com.alrex.parcool.utilities.EasingFunctions.CubicInOut(-phase);
        return 0;
    }

    private void skyPitch(Work w, NodeRef ref) {
        var actor = w.actor(ref);
        var sky = (SkyDiveChild) actor.state();
        w.set(
                ref,
                actor.tick(),
                new SkyDiveChild(
                        sky.forwardAngleCount(),
                        sky.forwardAngleCountOld(),
                        sky.rightAngleCount(),
                        sky.rightAngleCountOld(),
                        sky.startPitchAngle(),
                        skyPitch(actor.tick(), sky)));
    }

    /**
     * ADR4 §5.2 Dive grid for the child's current integer age: F at the four 60 Hz points of the
     * known y-speed interval; the preceding point is the previous interval's or the constructor's.
     */
    private static void diveGrid(Work w, boolean fresh) {
        var host = (DiveHost) w.actor(w.animator).state();
        var childActor = w.actor(host.diveAnimator());
        var child = (DiveChild) childActor.state();
        int age = childActor.tick();
        var dive = w.input(ActionTag.DIVE, DiveInput.class);
        float[] f = new float[4];
        for (int j = 0; j < 4; j++)
            f[j] =
                    ParCoolReducers.diveFactor(
                            child.initialYSpeed(),
                            Mth.lerp(
                                    (double) (float) (j / 3.0),
                                    dive.playerYSpeedOld(),
                                    dive.playerYSpeed()));
        long base = Math.multiplyExact((long) age, 3);
        float previous;
        if (fresh) {
            if (age != 0) throw new IllegalArgumentException("New Dive child is not at age zero");
            previous = child.oldFactor();
        } else {
            var old = w.diveGrid.factor();
            if (old.baseIndex() != base - 3)
                throw new IllegalArgumentException("ParCool Dive grid interval unavailable");
            previous = old.second();
        }
        w.diveGrid = new DiveGrid(new Grid4(base, previous, f[0], f[1], f[2], f[3]));
        w.set(
                host.diveAnimator(),
                childActor.tick(),
                new DiveChild(child.initialYSpeed(), 180 * f[0], f[0]));
    }

    /**
     * Canonical 60 Hz Hang recurrence for the R interval ending at this boundary (ADR4 §5.2). The
     * first interval of a hang seeds from the sample's previous committed body yaw.
     */
    private static void hang(Work w, Angles angles) {
        var row = w.action(ActionTag.HANG_DOWN);
        var in = input(row, HangInput.class);
        if (!row.doing() || in.barAxis() == null) {
            if (in.valid()) w.actions.put(ActionTag.HANG_DOWN, replace(row, seed(in, 0, false)));
            return;
        }
        var look = look(angles.xRot(), angles.yRot());
        if (!in.valid()) {
            float seed = angles.yBodyRotO();
            float y1 = ParCoolReducers.hangStep(seed, look, in.barAxis());
            float y2 = ParCoolReducers.hangStep(y1, look, in.barAxis());
            float y3 = ParCoolReducers.hangStep(y2, look, in.barAxis());
            w.hangGrid = new HangGrid(new Grid4(0, seed, seed, y1, y2, y3), seed);
            w.actions.put(ActionTag.HANG_DOWN, replace(row, seed(in, seed, true)));
            return;
        }
        var old = w.hangGrid.yaw();
        float y1 = ParCoolReducers.hangStep(old.third(), look, in.barAxis());
        float y2 = ParCoolReducers.hangStep(y1, look, in.barAxis());
        float y3 = ParCoolReducers.hangStep(y2, look, in.barAxis());
        w.hangGrid =
                new HangGrid(
                        new Grid4(
                                Math.addExact(old.baseIndex(), 3),
                                old.second(),
                                old.third(),
                                y1,
                                y2,
                                y3),
                        w.hangGrid.constructorYaw());
    }

    private static HangInput seed(HangInput in, float yaw, boolean valid) {
        return new HangInput(
                in.bodySwingAngleFactor(),
                in.armSwingAmount(),
                in.orthogonalToBar(),
                in.barAxis(),
                yaw,
                valid);
    }

    /** R-domain Hang grid coordinate of {@code p_R} inside the retained interval. */
    public static Time hangAge(HangGrid grid, Time partial) {
        if (partial.tick() > 1 || partial.tick() == 1 && partial.alpha() != 0)
            throw new IllegalArgumentException("Hang R partial outside (0,1]");
        if (grid.yaw().baseIndex() % 3 != 0)
            throw new IllegalArgumentException("Hang grid is not tick aligned");
        return new Time(Math.addExact(grid.yaw().baseIndex() / 3, partial.tick()), partial.alpha());
    }

    /** bf7 Entity.calculateViewVector(xRot, yRot), the Hang look read, with Mth's tables. */
    public static Vec3 look(float xRot, float yRot) {
        float pitch = xRot * ((float) Math.PI / 180F);
        float yaw = -yRot * ((float) Math.PI / 180F);
        float yawCos = Mth.cos(yaw);
        float yawSin = Mth.sin(yaw);
        float pitchCos = Mth.cos(pitch);
        float pitchSin = Mth.sin(pitch);
        return new Vec3(yawSin * pitchCos, -pitchSin, yawCos * pitchCos);
    }

    private void passive(Work w, long tick, NativeStage stage, Decision input) {
        var state = w.state();
        boolean contributes =
                state.animator() == null
                        && !state.option().animationCanceled()
                        && state.passive().fallingAnimationTick() >= 14
                        && !w.doing(ActionTag.DIVE);
        boolean present = w.starts.stream().anyMatch(s -> key(s).equals(w.passiveStart));
        if (contributes && !present) {
            int id = w.allocate();
            var start = derived(tick, stage, input, id, 0, PASSIVE);
            w.starts.add(
                    new Start(
                            start.eventSequence(),
                            id,
                            LayerTag.PARCOOL_PASSIVE,
                            start.startKind(),
                            start.anchorClass(),
                            policy.domain(LayerTag.PARCOOL_PASSIVE),
                            start.phaseAnchor(),
                            0,
                            0,
                            start.proof()));
            w.passiveStart = new StartKey(start.eventSequence(), id);
        } else if (!contributes && present) {
            // A reducer-minted phase key leaves with its pose; event proofs are never pruned here.
            w.starts.removeIf(
                    s ->
                            key(s).equals(w.passiveStart)
                                    && s.anchorClass() != AnchorClass.OBSERVER_APPLY);
        }
    }

    /**
     * Observer transitions after a removed or hosting node are PROJECTED_SUCCESSORs the server
     * re-derives from the same seed, journal and sample; unblocked ones (no predecessor node) are
     * CANONICAL_TICK, as on the server's own timeline.
     */
    private Start derived(
            long tick, NativeStage stage, Decision input, int node, int predecessor, int ordinal) {
        long sequence = input.id().eventSequence();
        boolean projected = mode == Mode.OBSERVER && predecessor != 0;
        Proof proof =
                projected
                        ? new ProjectedSuccessor(tick, stage, input.input().contentId(), ordinal)
                        : new CanonicalTick(tick, stage);
        return new Start(
                sequence,
                node,
                LayerTag.PARCOOL_ACTIVE,
                StartKind.NEW,
                projected ? AnchorClass.PROJECTED_SUCCESSOR : AnchorClass.CANONICAL_TICK,
                policy.domain(LayerTag.PARCOOL_ACTIVE),
                tick,
                0,
                predecessor,
                proof);
    }

    /** A recorded packetless outcome must equal the derivation at this boundary. */
    private void verify(Result base, long tick, Decision decision) {
        var state = base.frame().state();
        Start derived =
                state.animator() == null
                        ? null
                        : base.starts().stream()
                                .filter(
                                        s ->
                                                s.layerTag() == LayerTag.PARCOOL_ACTIVE
                                                        && s.phaseAnchor() == tick
                                                        && s.anchorClass()
                                                                != AnchorClass.OBSERVER_APPLY
                                                        && s.nodeId() == state.animator().id())
                                .findFirst()
                                .orElse(null);
        var fragment = decision.patch().replacement();
        boolean ok =
                switch (decision.outcome()) {
                    case SELECTED ->
                            derived != null
                                    && fragment.nodes().size() == 1
                                    && fragment.nodes().getFirst().data() instanceof Animator a
                                    && a.tag() == tag(base, state.animator());
                    case NO_CHANGE -> derived == null;
                    default -> false;
                };
        if (!ok) throw new IllegalArgumentException("Recorded ParCool selection differs");
    }

    private void install(
            Work w, AnimationGraphState fragment, long sequence, List<Start> templates) {
        if (fragment.roots().size() != 1
                || fragment.roots().getFirst().layer() != LayerTag.PARCOOL_ACTIVE
                || fragment.roots().getFirst().node() == null
                || !fragment.aliases().isEmpty())
            throw new IllegalArgumentException("ParCool replacement fragment shape");
        var byId = new HashMap<Integer, Node>();
        for (Node node : fragment.nodes())
            if (node.start() != null
                    || !(node.data() instanceof Animator)
                    || byId.put(node.id(), node) != null)
                throw new IllegalArgumentException("Invalid ParCool fragment node");
        var order = new ArrayList<Node>();
        var host = byId.get(fragment.roots().getFirst().node().id());
        if (host == null) throw new IllegalArgumentException("Missing ParCool fragment root");
        order.add(host);
        var hostData = (Animator) host.data();
        if (hostData.tag() == AnimatorTag.DIVE_CHILD
                || hostData.tag() == AnimatorTag.SKY_DIVE_CHILD)
            throw new IllegalArgumentException("Dive child is not a root");
        if (hostData.state() instanceof DiveHost dive) {
            order.add(child(byId, dive.diveAnimator(), AnimatorTag.DIVE_CHILD));
            if (dive.skyDiveAnimator() != null)
                order.add(child(byId, dive.skyDiveAnimator(), AnimatorTag.SKY_DIVE_CHILD));
        }
        if (order.size() != byId.size())
            throw new IllegalArgumentException("Unreachable ParCool fragment node");
        if (templates.size() != order.size())
            throw new IllegalArgumentException("Missing ParCool event start");
        int predecessor = w.removeActive();
        var ids = new HashMap<Integer, Integer>();
        for (Node node : order) ids.put(node.id(), w.allocate());
        int hostId = ids.get(host.id());
        for (int i = 0; i < order.size(); i++) {
            Node node = order.get(i);
            int id = ids.get(node.id());
            var actor = (Animator) node.data();
            AnimatorData data =
                    actor.state() instanceof DiveHost dive
                            ? new DiveHost(
                                    new NodeRef(ids.get(dive.diveAnimator().id())),
                                    dive.skyDiveAnimator() == null
                                            ? null
                                            : new NodeRef(ids.get(dive.skyDiveAnimator().id())),
                                    dive.fromInAir(),
                                    dive.oldSkyDiveDoing(),
                                    dive.transitioning(),
                                    dive.transitionTick())
                            : actor.state();
            var start = bind(templates.get(i), sequence, id, i == 0 ? predecessor : hostId);
            if (start.layerTag() != LayerTag.PARCOOL_ACTIVE)
                throw new IllegalArgumentException("ParCool event start layer");
            w.starts.add(start);
            w.nodes.put(
                    id,
                    new Node(
                            id,
                            policy.domain(LayerTag.PARCOOL_ACTIVE),
                            key(start),
                            new Animator(actor.tag(), actor.tick(), data)));
        }
        w.root(new NodeRef(hostId));
        if (hostData.state() instanceof DiveHost) diveGrid(w, true);
    }

    private static Node child(Map<Integer, Node> nodes, NodeRef ref, AnimatorTag tag) {
        var node = nodes.get(ref.id());
        if (node == null || !(node.data() instanceof Animator a) || a.tag() != tag)
            throw new IllegalArgumentException("Invalid ParCool Dive child");
        return node;
    }

    private static Start bind(Start template, long sequence, int node, int predecessor) {
        if (template.eventSequence() != sequence)
            throw new IllegalArgumentException("ParCool event start sequence");
        return new Start(
                sequence,
                node,
                template.layerTag(),
                template.startKind(),
                template.anchorClass(),
                template.phaseDomain(),
                template.phaseAnchor(),
                template.u0(),
                predecessor,
                template.proof());
    }

    private static StartKey key(Start start) {
        return new StartKey(start.eventSequence(), start.nodeId());
    }

    private static AnimatorTag tag(AnimatorData data) {
        return switch (data) {
            case JumpCharging ignored -> AnimatorTag.JUMP_CHARGING;
            case Crawl ignored -> AnimatorTag.CRAWL;
            case FastRunning ignored -> AnimatorTag.FAST_RUNNING;
            case FastSwim ignored -> AnimatorTag.FAST_SWIM;
            case WallSlide ignored -> AnimatorTag.WALL_SLIDE;
            default -> throw new IllegalArgumentException("Not a packetless ParCool selection");
        };
    }

    private static AnimatorTag tag(Result base, NodeRef ref) {
        return base.frame().graph().nodes().stream()
                .filter(node -> node.id() == ref.id())
                .map(node -> ((Animator) node.data()).tag())
                .findFirst()
                .orElseThrow();
    }

    private static boolean family(LayerTag layer) {
        return layer == LayerTag.PARCOOL_ACTIVE || layer == LayerTag.PARCOOL_PASSIVE;
    }

    static <T extends ActionInput> T input(ActionState row, Class<T> type) {
        var found = optional(row, type);
        if (found == null)
            throw new IllegalArgumentException("Missing ParCool input " + type.getSimpleName());
        return found;
    }

    private static <T extends ActionInput> T optional(ActionState row, Class<T> type) {
        for (ActionInput input : row.inputs()) if (type.isInstance(input)) return type.cast(input);
        return null;
    }

    private static ActionState replace(ActionState row, ActionInput value) {
        var inputs = new ArrayList<ActionInput>();
        for (ActionInput input : row.inputs())
            inputs.add(input.getClass() == value.getClass() ? value : input);
        return new ActionState(
                row.action(),
                row.doing(),
                row.doingTick(),
                row.notDoingTick(),
                row.tickFromStarted(),
                inputs);
    }

    /** Mutable working copy of one immutable result; rebuilt and revalidated on exit. */
    private final class Work {
        private final AnimationGraphState base;
        final List<Root> roots;
        final LinkedHashMap<Integer, Node> nodes = new LinkedHashMap<>();
        final List<Alias> aliases;
        final EnumMap<ActionTag, ActionState> actions = new EnumMap<>(ActionTag.class);
        final List<Start> starts;
        NodeRef animator;
        StartKey passiveStart;
        Option option;
        Passive passive;
        HangGrid hangGrid;
        DiveGrid diveGrid;
        private int next;

        Work(Result result) {
            var graph = result.frame().graph();
            base = graph;
            roots = new ArrayList<>(graph.roots());
            for (Node node : graph.nodes()) nodes.put(node.id(), node);
            aliases = graph.aliases();
            var state = result.frame().state();
            for (ActionState row : ParCoolFrame.actions(state)) actions.put(row.action(), row);
            starts = new ArrayList<>(result.starts());
            animator = state.animator();
            passiveStart = state.passiveStart();
            option = state.option();
            passive = state.passive();
            hangGrid = state.hangGrid();
            diveGrid = state.diveGrid();
            int max = passiveStart.nodeId();
            for (int id : nodes.keySet()) max = Math.max(max, id);
            for (Start start : starts) max = Math.max(max, start.nodeId());
            next = Math.addExact(max, 1);
        }

        /** Above every live node and phase key, as PA materialization allocates. */
        int allocate() {
            int id = next;
            next = Math.addExact(next, 1);
            return id;
        }

        ParCool state() {
            return new ParCool(
                    animator,
                    passiveStart,
                    option,
                    passive,
                    List.copyOf(actions.values()),
                    hangGrid,
                    diveGrid);
        }

        Result result() {
            var list = List.copyOf(nodes.values());
            // Untouched topology (the same root and node objects) is the input graph itself:
            // equal by construction, and its validations stay known.
            var graph =
                    same(roots, base.roots()) && same(list, base.nodes())
                            ? base
                            : new AnimationGraphState(roots, list, aliases);
            return new Result(new ParCoolFrame(graph, state()), starts);
        }

        private static boolean same(List<?> a, List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
            return true;
        }

        ActionState action(ActionTag tag) {
            return actions.get(tag);
        }

        boolean doing(ActionTag tag) {
            return actions.get(tag).doing();
        }

        <T extends ActionInput> T input(ActionTag tag, Class<T> type) {
            return ParCoolStages.input(actions.get(tag), type);
        }

        Animator actor(NodeRef ref) {
            return (Animator) nodes.get(ref.id()).data();
        }

        AnimatorTag tag() {
            return animator == null ? null : actor(animator).tag();
        }

        void set(NodeRef ref, int tick, AnimatorData data) {
            var old = nodes.get(ref.id());
            nodes.put(
                    old.id(),
                    new Node(
                            old.id(),
                            old.domain(),
                            old.start(),
                            new Animator(((Animator) old.data()).tag(), tick, data)));
        }

        void root(NodeRef ref) {
            roots.replaceAll(
                    root ->
                            root.layer() == LayerTag.PARCOOL_ACTIVE
                                    ? new Root(root.priority(), root.layer(), ref)
                                    : root);
            animator = ref;
        }

        void drop(NodeRef ref) {
            var node = nodes.remove(ref.id());
            if (node.start() != null) starts.removeIf(start -> key(start).equals(node.start()));
        }

        /** Removes the active subtree with its phase keys; returns the replaced root ID or 0. */
        int removeActive() {
            if (animator == null) return 0;
            int id = animator.id();
            if (actor(animator).state() instanceof DiveHost host) {
                drop(host.diveAnimator());
                if (host.skyDiveAnimator() != null) drop(host.skyDiveAnimator());
            }
            drop(animator);
            root(null);
            return id;
        }
    }
}
