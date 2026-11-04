package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;

/**
 * The production ParCool reducer over one scripted canonical timeline and its observer projection,
 * identical in both transformed runtimes; encoded results feed the cross-JVM comparator.
 */
public final class ParCoolStagesCases {
    private ParCoolStagesCases() {}

    public static final AnimationPolicy POLICY = ParCoolProjectionCases.POLICY;
    static final Digest DIGEST = new Digest(5, 6, 7, 8);
    static final long FIRST = 100, LAST = 195;

    /** One accepted ParCool packet entry; a fragment is the native other-client constructor. */
    record Accept(long tick, ActionTag action, boolean start, AnimationGraphState fragment) {}

    static final List<Accept> ACCEPTED =
            List.of(
                    new Accept(103, ActionTag.ROLL, true, single(new Roll(RollDirection.LEFT))),
                    new Accept(104, ActionTag.FAST_RUN, true, null),
                    new Accept(110, ActionTag.ROLL, false, null),
                    new Accept(118, ActionTag.VAULT, true, single(new KongVault(0, 0))),
                    new Accept(119, ActionTag.VAULT, false, null),
                    new Accept(131, ActionTag.FAST_RUN, false, null),
                    new Accept(140, ActionTag.DIVE, true, dive(-.4)),
                    new Accept(143, ActionTag.SKY_DIVE, true, null),
                    new Accept(148, ActionTag.SKY_DIVE, false, null),
                    new Accept(156, ActionTag.DIVE, false, null),
                    new Accept(178, ActionTag.HANG_DOWN, true, single(new Hang())),
                    new Accept(188, ActionTag.HANG_DOWN, false, null));

    public static AnimationGraphState single(AnimatorData data) {
        return new AnimationGraphState(
                List.of(new Root(0, LayerTag.PARCOOL_ACTIVE, new NodeRef(1))),
                List.of(new Node(1, PhaseDomain.A, null, new Animator(tag(data), 0, data))));
    }

    public static AnimationGraphState dive(double initialYSpeed) {
        return new AnimationGraphState(
                List.of(new Root(0, LayerTag.PARCOOL_ACTIVE, new NodeRef(1))),
                List.of(
                        new Node(
                                1,
                                PhaseDomain.A,
                                null,
                                new Animator(
                                        AnimatorTag.DIVE_HOST,
                                        0,
                                        new DiveHost(
                                                new NodeRef(2), null, false, false, false, 0))),
                        new Node(
                                2,
                                PhaseDomain.A,
                                null,
                                new Animator(
                                        AnimatorTag.DIVE_CHILD,
                                        0,
                                        new DiveChild(initialYSpeed, 0, 0)))));
    }

    static AnimatorTag tag(AnimatorData data) {
        return switch (data) {
            case Roll ignored -> AnimatorTag.ROLL;
            case KongVault ignored -> AnimatorTag.KONG_VAULT;
            case Hang ignored -> AnimatorTag.HANG;
            default -> throw new IllegalArgumentException();
        };
    }

    /**
     * Canonical native mirror per tick: accepted doing changes, synced inputs, then Action.tick.
     */
    static final class Timeline {
        final Map<Long, AnimationState> samples = new TreeMap<>();
        final Map<Long, ActionState> acceptedRows = new HashMap<>();
        final Map<Accept, ActionState> rows = new HashMap<>();
        final AnimationState base;

        Timeline() throws Exception {
            base = baseState();
            var current = new EnumMap<ActionTag, ActionState>(ActionTag.class);
            for (var row : neutral().actions()) current.put(row.action(), row);
            samples.put(FIRST, sample(FIRST, current));
            for (long t = FIRST + 1; t <= LAST; t++) {
                for (Accept accept : ACCEPTED)
                    if (accept.tick() == t) {
                        var old = current.get(accept.action());
                        var changed =
                                new ActionState(
                                        old.action(),
                                        accept.start(),
                                        old.doingTick(),
                                        old.notDoingTick(),
                                        accept.start() ? 0 : old.tickFromStarted(),
                                        startInputs(accept, old.inputs()));
                        current.put(accept.action(), changed);
                        rows.put(accept, changed);
                    }
                synced(t, current);
                for (ActionTag tag : ActionTag.values())
                    current.put(tag, counters(current.get(tag)));
                samples.put(t, sample(t, current));
            }
        }

        static List<ActionInput> startInputs(Accept accept, List<ActionInput> inputs) {
            if (!accept.start()) return inputs;
            return inputs.stream()
                    .map(
                            input ->
                                    switch (input) {
                                        case DiveInput d ->
                                                (ActionInput)
                                                        new DiveInput(
                                                                false, -.4, -.4, -.4, 0, false, 9,
                                                                false);
                                        case HangInput h ->
                                                new HangInput(.6, 0, false, BarAxis.X, 0, false);
                                        default -> input;
                                    })
                    .toList();
        }

        /** Synced Normal values, world reads and target intent at canonical tick {@code t}. */
        static void synced(long t, Map<ActionTag, ActionState> rows) {
            var dive = rows.get(ActionTag.DIVE);
            if (dive.doing()) {
                var d = input(dive, DiveInput.class);
                double now = -.4 - .05 * (t - 140);
                rows.put(
                        ActionTag.DIVE,
                        with(
                                dive,
                                new DiveInput(
                                        d.justJumped(),
                                        d.initialYVelocityOfLastJump(),
                                        d.playerYSpeed(),
                                        now,
                                        d.fallingTick(),
                                        d.fromSkyDive(),
                                        d.notLandingTick(),
                                        d.waterEntry())));
            }
            var charge = rows.get(ActionTag.CHARGE_JUMP);
            var c = input(charge, ChargeInput.class);
            int chargeTick = t >= 133 && t <= 135 ? 3 : 0;
            rows.put(
                    ActionTag.CHARGE_JUMP,
                    with(
                            charge,
                            new ChargeInput(
                                    chargeTick,
                                    c.lastChargeTick(),
                                    c.notChargeTick(),
                                    c.started(),
                                    chargeTick,
                                    true)));
            rows.put(
                    ActionTag.SKY_DIVE,
                    with(
                            rows.get(ActionTag.SKY_DIVE),
                            new IntentInput(t >= 144 && t <= 146, false, t == 145, false)));
            var hang = rows.get(ActionTag.HANG_DOWN);
            if (hang.doing()) {
                var h = input(hang, HangInput.class);
                rows.put(
                        ActionTag.HANG_DOWN,
                        with(
                                hang,
                                new HangInput(
                                        h.bodySwingAngleFactor(),
                                        .25f + (t - 178) * .125f,
                                        t >= 183,
                                        t == 185 ? null : BarAxis.X,
                                        h.yawSeed(),
                                        h.valid())));
            }
        }

        AnimationState sample(long t, Map<ActionTag, ActionState> rows) {
            float body = t >= 118 && t <= 130 ? 10 + 7 * (t - 118) : 10;
            float bodyOld = t >= 119 && t <= 131 ? 10 + 7 * (t - 119) : 10;
            if (t >= 178) {
                body = 50;
                bodyOld = 50;
            }
            boolean falling = t >= 157 && t <= 175;
            return state(
                    base,
                    t,
                    rows,
                    new Angles(30, 30, 10, 10, bodyOld, body, 30, 30),
                    t >= 115 && t <= 125,
                    !falling,
                    falling ? 2 : 0);
        }
    }

    /** A complete decoded sample: the given canonical rows, R angles, flags and gun presence. */
    public static AnimationState state(
            AnimationState base,
            long t,
            Map<ActionTag, ActionState> rows,
            Angles angles,
            boolean gun,
            boolean ground,
            float fallDistance) {
        var parcool = neutral();
        var state =
                new ParCool(
                        null,
                        parcool.passiveStart(),
                        parcool.option(),
                        parcool.passive(),
                        List.copyOf(new EnumMap<>(rows).values()),
                        parcool.hangGrid(),
                        parcool.diveGrid());
        var p = base.placement();
        var placement =
                new Placement(
                        p.previousOrigin(),
                        p.origin(),
                        angles,
                        p.walk(),
                        p.deltaMovementOnPreviousTick(),
                        p.deltaMovement(),
                        p.vehicleAngles(),
                        p.reads());
        var v = base.vanilla();
        var f = v.flags();
        var flags =
                new Flags(
                        true,
                        f.spectator(),
                        f.baby(),
                        f.crouching(),
                        f.visuallySwimming(),
                        f.swimming(),
                        f.fallFlying(),
                        f.autoSpinAttack(),
                        ground,
                        f.inWater(),
                        f.inBubbleColumn(),
                        f.fullyFrozen(),
                        f.passenger(),
                        f.sprinting(),
                        f.creativeFlying(),
                        f.climbing(),
                        f.upsideDown());
        var vanilla =
                new Vanilla(
                        v.attack(),
                        v.swimAmountO(),
                        v.swimAmount(),
                        v.oBob(),
                        v.bob(),
                        v.vital(),
                        v.pose(),
                        flags,
                        fallDistance,
                        v.mainArm(),
                        v.bedOrientation(),
                        v.size(),
                        .08,
                        v.itemUse(),
                        v.medkitActive(),
                        v.medkitHand());
        Tacz tacz;
        try {
            tacz = gun ? (Tacz) ParCoolProjectionCases.value(Tacz.class) : null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return new AnimationState(
                base.identity(),
                t,
                base.sampleRevision(),
                base.eventWatermark(),
                placement,
                vanilla,
                base.stance(),
                base.equipment(),
                tacz,
                base.lr(),
                AnimationGraphState.neutral(),
                state,
                base.create(),
                base.sbw(),
                List.of());
    }

    public static AnimationState baseState() throws Exception {
        return (AnimationState) ParCoolProjectionCases.value(AnimationState.class);
    }

    public static ParCool neutral() {
        var context = ParCoolNativeValues.context();
        var rows = new ArrayList<ActionState>();
        for (var row : context.actions())
            rows.add(
                    new ActionState(
                            row.action(),
                            false,
                            0,
                            17,
                            -1,
                            row.action() == ActionTag.HANG_DOWN
                                    ? List.of(new HangInput(0, 0, false, null, 0, false))
                                    : row.inputs()));
        return new ParCool(
                null,
                new StartKey(1, 1),
                new Option(false, 127),
                new Passive(0, 0, 0),
                rows,
                context.hangGrid(),
                context.diveGrid());
    }

    public static InputRef ref(long tick) {
        return new InputRef(0, 0, tick, DIGEST);
    }

    public static ActionState counters(ActionState row) {
        boolean doing = row.doing();
        return new ActionState(
                row.action(),
                doing,
                doing ? row.doingTick() + 1 : 0,
                doing ? 0 : row.notDoingTick() + 1,
                row.tickFromStarted() >= 0 ? row.tickFromStarted() + 1 : -1,
                row.inputs());
    }

    public static <T extends ActionInput> T input(ActionState row, Class<T> type) {
        return row.inputs().stream().filter(type::isInstance).map(type::cast).findFirst().get();
    }

    public static ActionState with(ActionState row, ActionInput value) {
        return new ActionState(
                row.action(),
                row.doing(),
                row.doingTick(),
                row.notDoingTick(),
                row.tickFromStarted(),
                row.inputs().stream()
                        .map(i -> i.getClass() == value.getClass() ? value : i)
                        .toList());
    }

    static Patch inert() {
        return new Patch(
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
                null);
    }

    public static Decision boundary(long tick, long sequence, InputRef input) {
        return new Decision(
                new EventId(tick, sequence, NativeStage.NATIVE_TICK),
                LayerTag.PARCOOL_PASSIVE,
                StartKind.INPUT_UPDATE,
                Outcome.NO_CHANGE,
                0,
                null,
                input,
                inert(),
                0);
    }

    public static Decision event(
            long tick,
            long sequence,
            NativeStage stage,
            Accept accept,
            ActionState row,
            long input) {
        var outcome = accept.fragment() == null ? Outcome.NO_CHANGE : Outcome.SELECTED;
        var kind = accept.fragment() == null ? StartKind.INPUT_UPDATE : StartKind.NEW;
        var patch =
                new Patch(
                        LayerTag.PARCOOL_ACTIVE,
                        kind,
                        outcome,
                        accept.fragment() == null
                                ? new AnimationGraphState(List.of(), List.of())
                                : accept.fragment(),
                        List.of(row),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        return new Decision(
                new EventId(tick, sequence, stage),
                LayerTag.PARCOOL_ACTIVE,
                kind,
                outcome,
                0,
                null,
                ref(input),
                patch,
                0);
    }

    static Map<InputRef, AnimationState> samples(Timeline timeline) {
        var map = new HashMap<InputRef, AnimationState>();
        timeline.samples.forEach((tick, state) -> map.put(ref(tick), state));
        return map;
    }

    static ParCoolProjection.Result initial(Timeline timeline) {
        return new ParCoolProjection.Result(
                new ParCoolFrame(AnimationGraphState.neutral(), neutral()), List.of());
    }

    /** Canonical journal: acceptances at INTER_TICK_ACCEPT, one boundary record per tick. */
    record Journal(Map<Long, List<Decision>> decisions, Map<Long, List<Start>> starts) {}

    static Journal canonicalJournal(Timeline timeline) {
        var decisions = new HashMap<Long, List<Decision>>();
        var starts = new HashMap<Long, List<Start>>();
        long sequence = 1000;
        for (long t = FIRST + 1; t <= LAST; t++) {
            var list = new ArrayList<Decision>();
            for (Accept accept : ACCEPTED)
                if (accept.tick() == t) {
                    sequence++;
                    list.add(
                            event(
                                    t,
                                    sequence,
                                    NativeStage.INTER_TICK_ACCEPT,
                                    accept,
                                    timeline.rows.get(accept),
                                    t - 1));
                    if (accept.fragment() != null) {
                        var templates = new ArrayList<Start>();
                        for (int i = 0; i < accept.fragment().nodes().size(); i++)
                            templates.add(
                                    new Start(
                                            sequence,
                                            1,
                                            LayerTag.PARCOOL_ACTIVE,
                                            StartKind.NEW,
                                            AnchorClass.CANONICAL_TICK,
                                            PhaseDomain.A,
                                            t,
                                            1,
                                            0,
                                            new CanonicalTick(t, NativeStage.INTER_TICK_ACCEPT)));
                        starts.put(sequence, templates);
                    }
                }
            sequence++;
            list.add(boundary(t, sequence, ref(t)));
            decisions.put(t, list);
        }
        return new Journal(decisions, starts);
    }

    /**
     * Observer timeline: each acceptance is applied {@code lead} buckets before its server tick (sA
     * below E), at END_COMMIT of that bucket; boundary inputs name S[n] unless held.
     */
    static Journal observerJournal(Timeline timeline, int lead, Map<Long, Long> holds) {
        var decisions = new HashMap<Long, List<Decision>>();
        var starts = new HashMap<Long, List<Start>>();
        for (long n = FIRST + 1; n <= LAST; n++) decisions.put(n, new ArrayList<>());
        long sequence = 1000;
        for (long n = FIRST + 1; n <= LAST; n++) {
            sequence++;
            decisions.get(n).add(boundary(n, sequence, ref(holds.getOrDefault(n, n))));
            for (Accept accept : ACCEPTED)
                if (accept.tick() - lead == n) {
                    sequence++;
                    decisions
                            .get(n)
                            .add(
                                    event(
                                            n,
                                            sequence,
                                            NativeStage.END_COMMIT,
                                            accept,
                                            timeline.rows.get(accept),
                                            holds.getOrDefault(n, n)));
                    if (accept.fragment() != null) {
                        var apply =
                                new ObserverApply(
                                        n,
                                        0,
                                        1,
                                        new Time(accept.tick(), 0),
                                        new Time(n - 1, 30000),
                                        new Time(n, 1000),
                                        n + 1,
                                        sequence);
                        var templates = new ArrayList<Start>();
                        for (int i = 0; i < accept.fragment().nodes().size(); i++)
                            templates.add(
                                    new Start(
                                            sequence,
                                            1,
                                            LayerTag.PARCOOL_ACTIVE,
                                            StartKind.NEW,
                                            AnchorClass.OBSERVER_APPLY,
                                            PhaseDomain.A,
                                            n,
                                            0,
                                            0,
                                            apply));
                        starts.put(sequence, templates);
                    }
                }
        }
        return new Journal(decisions, starts);
    }

    /** Unfurled schedule: the stage callbacks in resolver order, one boundary at a time. */
    static ParCoolProjection.Result unfurled(
            ParCoolProjection.Result base, long from, long to, Journal journal, ParCoolStages s) {
        var result = base;
        for (long n = from + 1; n <= to; n++)
            for (NativeStage stage : NativeStage.values()) {
                long tick = n;
                result =
                        s.apply(
                                result,
                                tick,
                                stage,
                                journal.decisions().get(n).stream()
                                        .filter(d -> d.id().nativeStage() == stage)
                                        .toList());
            }
        return result;
    }

    static Animator active(ParCoolProjection.Result result) {
        var ref = result.frame().state().animator();
        if (ref == null) return null;
        return result.frame().graph().nodes().stream()
                .filter(n -> n.id() == ref.id())
                .map(n -> (Animator) n.data())
                .findFirst()
                .orElseThrow();
    }

    static Start start(ParCoolProjection.Result result, NodeRef ref) {
        var key =
                result.frame().graph().nodes().stream()
                        .filter(n -> n.id() == ref.id())
                        .findFirst()
                        .orElseThrow()
                        .start();
        return result.starts().stream()
                .filter(s -> s.eventSequence() == key.eventSequence() && s.nodeId() == key.nodeId())
                .findFirst()
                .orElseThrow();
    }

    public static Map<String, Object> run() throws Exception {
        var timeline = new Timeline();
        var samples = samples(timeline);
        var canonicalJournal = canonicalJournal(timeline);
        var canonical =
                new ParCoolStages(
                        ParCoolStages.Mode.CANONICAL, samples, canonicalJournal.starts(), POLICY);
        var trace = new ArrayList<String>();
        var checkpoints = new TreeMap<Long, ParCoolProjection.Result>();
        var result = initial(timeline);
        checkpoints.put(FIRST, result);
        for (long n = FIRST + 1; n <= LAST; n++) {
            result = unfurled(result, n - 1, n, canonicalJournal, canonical);
            checkpoints.put(n, result);
            var actor = active(result);
            trace.add(
                    n
                            + ":"
                            + (actor == null ? "-" : actor.tag() + "@" + actor.tick())
                            + ":"
                            + result.frame().state().passive().fallingAnimationTick()
                            + ":"
                            + result.frame().state().option().allowedParts());
        }
        // Topology checkpoints: removal precedes selection at one boundary, then options.
        assertEquals(AnimatorTag.ROLL, active(checkpoints.get(109L)).tag());
        assertEquals(AnimatorTag.FAST_RUNNING, active(checkpoints.get(110L)).tag());
        var fastRun = checkpoints.get(110L);
        var selected = start(fastRun, fastRun.frame().state().animator());
        assertEquals(AnchorClass.CANONICAL_TICK, selected.anchorClass());
        assertEquals(110, selected.phaseAnchor());
        assertNotEquals(0, selected.predecessorNodeId());
        assertEquals(AnimatorTag.KONG_VAULT, active(checkpoints.get(118L)).tag());
        assertEquals(10, active(checkpoints.get(127L)).tick());
        assertEquals(AnimatorTag.FAST_RUNNING, active(checkpoints.get(128L)).tag());
        // TaCZ held 115..125: Kong is exempt; FastRunning with a gun loses arms and legs.
        assertEquals(127, checkpoints.get(120L).frame().state().option().allowedParts());
        assertEquals(97, checkpoints.get(115L).frame().state().option().allowedParts());
        assertEquals(127, checkpoints.get(126L).frame().state().option().allowedParts());
        assertNull(active(checkpoints.get(131L)));
        assertEquals(AnimatorTag.JUMP_CHARGING, active(checkpoints.get(133L)).tag());
        assertNull(active(checkpoints.get(136L)));
        var kong = (KongVault) active(checkpoints.get(120L)).state();
        assertEquals(
                ParCoolReducers.kongStep(
                        (KongVault) active(checkpoints.get(119L)).state(),
                        10 + 7 * (120 - 119),
                        10 + 7 * (120 - 118)),
                kong);
        // Dive: grid per child age, SkyDive child created on entry and kept for the exit fade.
        var host = (DiveHost) active(checkpoints.get(142L)).state();
        assertNull(host.skyDiveAnimator());
        var entry = (DiveHost) active(checkpoints.get(143L)).state();
        assertNotNull(entry.skyDiveAnimator());
        assertTrue(entry.transitioning());
        var exit = (DiveHost) active(checkpoints.get(148L)).state();
        assertNotNull(exit.skyDiveAnimator());
        assertTrue(exit.transitioning() && !exit.oldSkyDiveDoing());
        var dropped = (DiveHost) active(checkpoints.get(154L)).state();
        assertNull(dropped.skyDiveAnimator());
        assertFalse(dropped.transitioning());
        var grid = checkpoints.get(150L).frame().state().diveGrid().factor();
        assertEquals(33, grid.baseIndex());
        assertEquals(
                checkpoints.get(149L).frame().state().diveGrid().factor().second(),
                grid.previous());
        assertNull(active(checkpoints.get(156L)));
        // Passive falling reaches the native start line at 14 counted ticks.
        assertEquals(13, checkpoints.get(169L).frame().state().passive().fallingAnimationTick());
        var passive = checkpoints.get(170L);
        assertEquals(14, passive.frame().state().passive().fallingAnimationTick());
        assertTrue(
                passive.starts().stream()
                        .anyMatch(
                                s ->
                                        s.layerTag() == LayerTag.PARCOOL_PASSIVE
                                                && s.phaseAnchor() == 170));
        assertTrue(
                checkpoints.get(176L).starts().stream()
                        .noneMatch(s -> s.layerTag() == LayerTag.PARCOOL_PASSIVE));
        // Hang: canonical R grid seeded from the previous body yaw, held while no bar is found.
        var hang = checkpoints.get(178L).frame().state();
        assertEquals(0, hang.hangGrid().yaw().baseIndex());
        assertEquals(50f, hang.hangGrid().constructorYaw());
        assertEquals(9, checkpoints.get(184L).frame().state().hangGrid().yaw().baseIndex() / 2);
        var noBar = checkpoints.get(185L).frame().state().hangGrid();
        assertEquals(checkpoints.get(184L).frame().state().hangGrid(), noBar);
        assertEquals(0, checkpoints.get(186L).frame().state().hangGrid().yaw().baseIndex());

        // Canonical production: ordered yaw fold at zero partial, then Kong over the foldedAngles
        // pair.
        var skyRows = new EnumMap<ActionTag, ActionState>(ActionTag.class);
        for (var row : timeline.samples.get(120L).parcool().actions())
            skyRows.put(row.action(), row);
        var sky = skyRows.get(ActionTag.SKY_DIVE);
        skyRows.put(ActionTag.SKY_DIVE, new ActionState(sky.action(), true, 1, 0, 1, sky.inputs()));
        var writer =
                state(
                        timeline.base,
                        120,
                        skyRows,
                        timeline.samples.get(120L).placement().angles(),
                        true,
                        true,
                        0);
        var producerSamples = new HashMap<>(samples);
        producerSamples.put(ref(120), writer);
        var producer =
                new ParCoolStages(
                        ParCoolStages.Mode.CANONICAL,
                        producerSamples,
                        canonicalJournal.starts(),
                        POLICY);
        var produced =
                producer.canonical(
                        checkpoints.get(119L), 120, canonicalJournal.decisions().get(120L));
        var foldedAngles =
                ParCoolYaw.apply(
                        writer.placement().angles(), produced.result().frame(), 0, Time.whole(0));
        assertEquals(foldedAngles, produced.angles());
        assertEquals(
                foldedAngles.yHeadRot(), foldedAngles.yBodyRot(), "SkyDive writes body = head");
        assertNotEquals(writer.placement().angles().yBodyRot(), foldedAngles.yBodyRot());
        assertEquals(
                ParCoolReducers.kongStep(
                        (KongVault) active(checkpoints.get(119L)).state(),
                        foldedAngles.yBodyRotO(),
                        foldedAngles.yBodyRot()),
                active(produced.result()).state());
        var hangProduced =
                canonical.canonical(
                        checkpoints.get(179L), 180, canonicalJournal.decisions().get(180L));
        var hangGrid = hangProduced.result().frame().state().hangGrid().yaw();
        assertEquals(
                hangGrid.third(), hangProduced.angles().yBodyRot(), "Hang commits its endpoint");
        assertEquals(checkpoints.get(180L), hangProduced.result());

        // Importing a complete checkpoint replaces derivation without reticking it.
        var imported =
                ParCoolProjection.canonical(
                        checkpoints.get(150L),
                        150,
                        180,
                        Map.of(165L, checkpoints.get(165L)),
                        canonicalJournal.decisions(),
                        POLICY,
                        canonical);
        assertEquals(checkpoints.get(180L), imported);
        assertEquals(
                checkpoints.get(140L),
                ParCoolProjection.canonical(
                        checkpoints.get(101L),
                        101,
                        140,
                        Map.of(),
                        canonicalJournal.decisions(),
                        POLICY,
                        canonical));

        // Observer projection: every acceptance applied two buckets early (sA < E).
        var observerJournal = observerJournal(timeline, 2, Map.of());
        var observer =
                new ParCoolStages(
                        ParCoolStages.Mode.OBSERVER, samples, observerJournal.starts(), POLICY);
        var direct = unfurled(initial(timeline), FIRST, LAST, observerJournal, observer);
        var seed = seed(timeline, initial(timeline), FIRST);
        long bucket = FIRST;
        ParCoolProjection.Result folded = null;
        int folds = 0;
        while (bucket < LAST) {
            long to = Math.min(LAST, bucket + 20);
            folded =
                    ParCoolProjection.observer(
                            seed, bucket, to, observerJournal.decisions(), POLICY, observer);
            seed = fold(timeline, seed, folded, to, ++folds);
            bucket = to;
        }
        assertEquals(direct, folded);
        var beforeStop = unfurled(initial(timeline), FIRST, 108, observerJournal, observer);
        assertEquals(AnimatorTag.ROLL, active(beforeStop).tag());
        var observerRoll = unfurled(beforeStop, 108, 109, observerJournal, observer);
        assertEquals(AnimatorTag.FAST_RUNNING, active(observerRoll).tag());
        var projected = start(observerRoll, observerRoll.frame().state().animator());
        assertEquals(AnchorClass.PROJECTED_SUCCESSOR, projected.anchorClass());
        assertEquals(109, projected.phaseAnchor());
        // Held input prefix: a recorded older sample is deterministic and differs downstream.
        var heldJournal = observerJournal(timeline, 2, Map.of(149L, 148L, 150L, 148L));
        var held =
                new ParCoolStages(
                        ParCoolStages.Mode.OBSERVER, samples, heldJournal.starts(), POLICY);
        var heldA = unfurled(initial(timeline), FIRST, 150, heldJournal, held);
        var heldB = unfurled(initial(timeline), FIRST, 150, heldJournal, held);
        assertEquals(heldA, heldB);
        assertNotEquals(unfurled(initial(timeline), FIRST, 150, observerJournal, observer), heldA);

        // Fail closed: missing sample, later sample, missing boundary record.
        var missing = new HashMap<>(samples);
        missing.remove(ref(120));
        var noSample =
                new ParCoolStages(
                        ParCoolStages.Mode.CANONICAL, missing, canonicalJournal.starts(), POLICY);
        assertThrows(
                IllegalArgumentException.class,
                () -> unfurled(checkpoints.get(119L), 119, 120, canonicalJournal, noSample));
        var later = observerJournal(timeline, 2, Map.of(130L, 131L));
        var laterStages =
                new ParCoolStages(ParCoolStages.Mode.OBSERVER, samples, later.starts(), POLICY);
        assertThrows(
                IllegalArgumentException.class,
                () -> unfurled(initial(timeline), FIRST, 131, later, laterStages));
        var gap = new HashMap<>(observerJournal.decisions());
        gap.put(
                111L,
                gap.get(111L).stream()
                        .filter(d -> d.id().nativeStage() != NativeStage.NATIVE_TICK)
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.observer(
                                seed(timeline, initial(timeline), FIRST),
                                FIRST,
                                120,
                                gap,
                                POLICY,
                                observer));
        var canonicalHex =
                HexFormat.of()
                        .formatHex(AnimationSchema.encode(checkpoints.get(LAST).frame().state()));
        return Map.of(
                "boundaries",
                LAST - FIRST,
                "accepted",
                ACCEPTED.size(),
                "folds",
                folds,
                "canonicalTrace",
                trace,
                "canonicalFinalHex",
                canonicalHex,
                "canonicalGraphHex",
                HexFormat.of()
                        .formatHex(AnimationSchema.encode(checkpoints.get(LAST).frame().graph())),
                "observerFinalHex",
                HexFormat.of().formatHex(AnimationSchema.encode(direct.frame().state())),
                "observerGraphHex",
                HexFormat.of().formatHex(AnimationSchema.encode(direct.frame().graph())),
                "heldFinalHex",
                HexFormat.of().formatHex(AnimationSchema.encode(heldA.frame().state())),
                "scope",
                "production deterministic ParCool stages: inputs/holds, tick order, removal, Dive"
                        + " topology/grid, Hang grid, five selections, passive, options; native"
                        + " equality of primitives is the client stages fixture");
    }

    static ObserverSeed seed(Timeline timeline, ParCoolProjection.Result result, long bucket) {
        var context = ParCoolProjection.withFrame(timeline.samples.get(bucket), result.frame());
        return new ObserverSeed(
                context.identity(),
                new SeedRef(0, 0, 1, DIGEST),
                null,
                context,
                result.frame().graph(),
                0,
                eventPrefix(result),
                0,
                result.starts(),
                List.of(),
                DIGEST);
    }

    static ObserverSeed fold(
            Timeline timeline,
            ObserverSeed previous,
            ParCoolProjection.Result result,
            long bucket,
            int index) {
        return ParCoolProjectionCases.roundTrip(
                ParCoolProjection.fold(
                        previous,
                        new SeedRef(0, 0, index + 1, DIGEST),
                        timeline.samples.get(bucket),
                        result,
                        bucket,
                        Math.max(previous.eventPrefix(), eventPrefix(result)),
                        0,
                        List.of(),
                        DIGEST,
                        POLICY));
    }

    static long eventPrefix(ParCoolProjection.Result result) {
        return result.starts().stream().mapToLong(Start::eventSequence).max().orElse(0);
    }
}
