package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.lang.reflect.*;
import java.util.*;

/** Replay/seed machinery, with real native Action.tick calls in both transformed runtimes. */
public final class ParCoolProjectionCases {
    private ParCoolProjectionCases() {}

    public static final AnimationPolicy POLICY =
            new AnimationPolicy(
                    0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    private static final Digest DIGEST = new Digest(1, 2, 3, 4);

    public static Start start(int node, LayerTag layer, long bucket, int age) {
        return new Start(
                7,
                node,
                layer,
                StartKind.NEW,
                AnchorClass.CANONICAL_TICK,
                PhaseDomain.A,
                bucket,
                age,
                0,
                new CanonicalTick(bucket, NativeStage.NATIVE_TICK));
    }

    public static ParCoolProjection.Result initial(long bucket) {
        var context = ParCoolNativeValues.context();
        var roots = new ArrayList<>(AnimationGraphState.neutral().roots());
        roots.replaceAll(
                root ->
                        root.layer() == LayerTag.PARCOOL_ACTIVE
                                ? new Root(root.priority(), root.layer(), new NodeRef(1))
                                : root);
        var graph =
                new AnimationGraphState(
                        roots,
                        List.of(
                                new Node(
                                        1,
                                        PhaseDomain.A,
                                        new StartKey(7, 1),
                                        new Animator(AnimatorTag.CLIMB_UP, 0, new ClimbUp()))));
        var state =
                new ParCool(
                        new NodeRef(1),
                        new StartKey(7, 100),
                        context.option(),
                        context.passive(),
                        context.actions(),
                        context.hangGrid(),
                        context.diveGrid());
        return new ParCoolProjection.Result(
                new ParCoolFrame(graph, state),
                List.of(
                        start(1, LayerTag.PARCOOL_ACTIVE, bucket, 0),
                        start(100, LayerTag.PARCOOL_PASSIVE, bucket, 0)));
    }

    public static Map<Long, List<Decision>> journal(long from, long to) {
        var journal = new HashMap<Long, List<Decision>>();
        for (long tick = from; tick < to; ) journal.put(++tick, List.of());
        return journal;
    }

    /** Test stage: native counter advancement only; not the unfinished geometry reducer. */
    public static ParCoolProjection.Result counters(
            ParCoolProjection.Result base, long tick, NativeStage stage, List<Decision> decisions) {
        assertTrue(decisions.isEmpty());
        if (stage != NativeStage.NATIVE_TICK) return base;
        var nodes = new ArrayList<Node>();
        for (Node node : base.frame().graph().nodes()) {
            var actor = (Animator) node.data();
            nodes.add(
                    new Node(
                            node.id(),
                            node.domain(),
                            node.start(),
                            new Animator(
                                    actor.tag(), Math.addExact(actor.tick(), 1), actor.state())));
        }
        var actions = new ArrayList<ActionState>();
        for (ActionState old : base.frame().state().actions()) {
            var nativeAction = ParCoolActions.materialize(old);
            nativeAction.tick();
            actions.add(
                    new ActionState(
                            old.action(),
                            nativeAction.isDoing(),
                            nativeAction.getDoingTick(),
                            nativeAction.getNotDoingTick(),
                            nativeAction.getTickFromLastStarted(),
                            old.inputs()));
        }
        var old = base.frame().state();
        var state =
                new ParCool(
                        old.animator(),
                        old.passiveStart(),
                        old.option(),
                        old.passive(),
                        actions,
                        old.hangGrid(),
                        old.diveGrid());
        return new ParCoolProjection.Result(
                new ParCoolFrame(
                        new AnimationGraphState(
                                base.frame().graph().roots(),
                                nodes,
                                base.frame().graph().aliases()),
                        state),
                base.starts());
    }

    public static AnimationState context(ParCoolProjection.Result result) throws Exception {
        return ParCoolProjection.withFrame(
                (AnimationState) value(AnimationState.class), result.frame());
    }

    public static ObserverSeed seed(ParCoolProjection.Result result) throws Exception {
        var context = context(result);
        return new ObserverSeed(
                context.identity(),
                new SeedRef(0, 0, 1, DIGEST),
                null,
                context,
                result.frame().graph(),
                0,
                7,
                7,
                result.starts(),
                List.of(),
                DIGEST);
    }

    public static Map<String, Object> run() throws Exception {
        var initial = initial(100);
        var initialBytes = AnimationSchema.encode(initial.frame().state());
        var order = new ArrayList<NativeStage>();
        var advanced =
                ParCoolProjection.canonical(
                        initial,
                        100,
                        103,
                        Map.of(),
                        journal(100, 103),
                        POLICY,
                        (base, tick, stage, decisions) -> {
                            order.add(stage);
                            return counters(base, tick, stage, decisions);
                        });
        assertEquals(3, age(advanced));
        for (int i = 0; i < 3; i++)
            assertEquals(List.of(NativeStage.values()), order.subList(i * 5, (i + 1) * 5));
        var checkpoints = new LinkedHashMap<Long, ParCoolProjection.Result>();
        checkpoints.put(102L, advanced);
        checkpoints.put(101L, initial);
        var replacement =
                ParCoolProjection.canonical(
                        initial,
                        100,
                        104,
                        checkpoints,
                        journal(102, 104),
                        POLICY,
                        ParCoolProjectionCases::counters);
        assertEquals(5, age(replacement));
        assertEquals(
                22,
                replacement
                        .frame()
                        .scratchAttachment()
                        .get(com.alrex.parcool.common.action.impl.Dive.class)
                        .getNotDoingTick());
        assertEquals(
                advanced,
                ParCoolProjection.canonical(
                        initial,
                        100,
                        102,
                        checkpoints,
                        Map.of(),
                        POLICY,
                        (a, b, c, d) -> {
                            throw new AssertionError("Restored tick repeated");
                        }));

        var mutableJournal = journal(100, 103);
        assertEquals(
                advanced,
                ParCoolProjection.canonical(
                        initial,
                        100,
                        103,
                        Map.of(),
                        mutableJournal,
                        POLICY,
                        (base, tick, stage, decisions) -> {
                            mutableJournal.remove(102L);
                            return counters(base, tick, stage, decisions);
                        }));
        var seed = seed(initial);
        var seedBytes = AnimationSchema.encode(seed);
        var continued = initial;
        long bucket = 100;
        // Eight bounded folds keep a 160-tick actor without replaying its ancient constructor.
        for (int segment = 0; segment < 8; segment++) {
            continued =
                    ParCoolProjection.observer(
                            seed,
                            bucket,
                            bucket + 20,
                            journal(bucket, bucket + 20),
                            POLICY,
                            ParCoolProjectionCases::counters);
            seed =
                    ParCoolProjection.fold(
                            seed,
                            new SeedRef(0, 0, segment + 2, DIGEST),
                            context(continued),
                            continued,
                            bucket + 20,
                            7,
                            7,
                            List.of(),
                            DIGEST,
                            POLICY);
            seed = roundTrip(seed);
            assertEquals(continued.frame().state(), seed.checkpoint().parcool());
            assertEquals(continued.frame().graph(), seed.checkpoint().graph());
            bucket += 20;
        }
        var direct = initial;
        for (long tick = 101; tick <= 260; tick++)
            direct = counters(direct, tick, NativeStage.NATIVE_TICK, List.of());
        assertEquals(direct, continued);
        assertEquals(160, age(continued));
        assertEquals(
                177,
                continued
                        .frame()
                        .scratchAttachment()
                        .get(com.alrex.parcool.common.action.impl.Dive.class)
                        .getNotDoingTick());
        var activation =
                new ObserverApply(
                        900,
                        2,
                        3,
                        new Time(270, 20000),
                        new Time(258, 4000),
                        new Time(260, 20000),
                        270,
                        1000);
        var rebased = ParCoolProjection.seedContinue(continued, 1000, activation, POLICY);
        assertEquals(
                continued.frame().graph().nodes().getFirst().data(),
                rebased.frame().graph().nodes().getFirst().data());
        assertEquals(continued.frame().state().passive(), rebased.frame().state().passive());
        assertEquals(new StartKey(1000, 100), rebased.frame().state().passiveStart());
        for (int i = 0; i < continued.starts().size(); i++)
            assertEquals(
                    AnimationStartReport.age(
                            continued.starts().get(i),
                            activation.applyR(),
                            activation.applyA(),
                            POLICY),
                    AnimationStartReport.age(
                            rebased.starts().get(i),
                            activation.applyR(),
                            activation.applyA(),
                            POLICY));

        var mixedRoots = new ArrayList<>(initial.frame().graph().roots());
        mixedRoots.replaceAll(
                root ->
                        root.layer() == LayerTag.PA_93
                                ? new Root(root.priority(), root.layer(), new NodeRef(200))
                                : root);
        var mixedNodes = new ArrayList<>(initial.frame().graph().nodes());
        mixedNodes.add(new Node(200, PhaseDomain.A, new StartKey(7, 200), new Container(null)));
        var mixedStarts = new ArrayList<>(initial.starts());
        mixedStarts.add(start(200, LayerTag.PA_93, 100, 0));
        var mixed =
                new ParCoolProjection.Result(
                        new ParCoolFrame(
                                new AnimationGraphState(mixedRoots, mixedNodes),
                                initial.frame().state()),
                        mixedStarts);
        var continuedMixed = ParCoolProjection.seedContinue(mixed, 1000, activation, POLICY);
        assertEquals(mixedNodes.getLast(), continuedMixed.frame().graph().nodes().getLast());
        assertTrue(continuedMixed.starts().contains(mixedStarts.getLast()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.canonical(
                                mixed,
                                100,
                                101,
                                Map.of(),
                                journal(100, 101),
                                POLICY,
                                (a, b, c, d) -> initial));
        var before = seed(initial);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.observer(
                                before,
                                99,
                                99,
                                Map.of(),
                                POLICY,
                                ParCoolProjectionCases::counters));
        var futurePrefix =
                new ObserverApply(
                        activation.applyFrameId(),
                        activation.applyRevision(),
                        activation.mappingId(),
                        activation.sW(),
                        activation.applyR(),
                        activation.applyA(),
                        activation.applyH(),
                        1001);
        var outside = ParCoolProjection.seedContinue(continued, 1000, futurePrefix, POLICY);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.fold(
                                before,
                                new SeedRef(0, 0, 98, DIGEST),
                                contextUnchecked(outside),
                                outside,
                                0,
                                1000,
                                7,
                                List.of(),
                                DIGEST,
                                POLICY));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.fold(
                                before,
                                before.ref(),
                                contextUnchecked(initial),
                                initial,
                                0,
                                7,
                                7,
                                List.of(),
                                DIGEST,
                                POLICY));
        var future =
                new ObserverSeed(
                        before.identity(),
                        before.ref(),
                        null,
                        before.checkpoint(),
                        before.graph(),
                        0,
                        6,
                        7,
                        before.starts(),
                        List.of(),
                        DIGEST);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.observer(
                                future,
                                100,
                                100,
                                Map.of(),
                                POLICY,
                                ParCoolProjectionCases::counters));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.observer(
                                before,
                                100,
                                141,
                                journal(100, 141),
                                POLICY,
                                ParCoolProjectionCases::counters));
        var missing = journal(100, 105);
        missing.remove(103L);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.observer(
                                before,
                                100,
                                105,
                                missing,
                                POLICY,
                                ParCoolProjectionCases::counters));
        var finalResult = continued;
        var finalSeed = seed;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.fold(
                                finalSeed,
                                new SeedRef(0, 0, 99, DIGEST),
                                contextUnchecked(finalResult),
                                finalResult,
                                1,
                                7,
                                7,
                                List.of(),
                                DIGEST,
                                POLICY));
        var passiveState = initial.frame().state();
        var passiveOnly =
                new ParCoolFrame(
                        AnimationGraphState.neutral(),
                        new ParCool(
                                null,
                                passiveState.passiveStart(),
                                passiveState.option(),
                                passiveState.passive(),
                                passiveState.actions(),
                                passiveState.hangGrid(),
                                passiveState.diveGrid()));
        var missingPassive = new ParCoolProjection.Result(passiveOnly, List.of());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ParCoolProjection.canonical(
                                missingPassive,
                                100,
                                100,
                                Map.of(),
                                Map.of(),
                                POLICY,
                                ParCoolProjectionCases::counters));
        var neutral =
                new ParCoolProjection.Result(
                        new ParCoolFrame(
                                AnimationGraphState.neutral(),
                                new ParCool(
                                        null,
                                        passiveState.passiveStart(),
                                        passiveState.option(),
                                        new Passive(0, 2, 3),
                                        passiveState.actions(),
                                        passiveState.hangGrid(),
                                        passiveState.diveGrid())),
                        List.of());
        assertEquals(
                neutral,
                ParCoolProjection.observer(
                        seed(neutral),
                        100,
                        100,
                        Map.of(),
                        POLICY,
                        ParCoolProjectionCases::counters));
        var neutralContinuation = ParCoolProjection.seedContinue(neutral, 1000, activation, POLICY);
        assertEquals(neutral, neutralContinuation);
        var neutralSeed = seed(neutral);
        assertTrue(
                ParCoolProjection.fold(
                                neutralSeed,
                                new SeedRef(0, 0, 99, DIGEST),
                                context(neutral),
                                neutral,
                                0,
                                7,
                                7,
                                List.of(),
                                DIGEST,
                                POLICY)
                        .starts()
                        .isEmpty());
        assertArrayEquals(initialBytes, AnimationSchema.encode(initial.frame().state()));
        assertArrayEquals(seedBytes, AnimationSchema.encode(before));
        assertNull(seed.checkpoint().tacz()); // U1 manifest 3, ParCool-only checkpoint
        return Map.of(
                "nativeActions",
                27,
                "folds",
                8,
                "wholeTicks",
                160,
                "restoredCheckpointNotReticked",
                true,
                "missingStepAndSeedRefused",
                true,
                "passiveAndForeignAnchorsPreserved",
                true,
                "nullableTacz",
                true,
                "retainedStateUnchanged",
                true,
                "finalU1SeedHex",
                HexFormat.of().formatHex(AnimationSchema.encode(seed)),
                "scope",
                "bounded replay and folding with native action counters; geometry stage production remains separate");
    }

    private static int age(ParCoolProjection.Result result) {
        return ((Animator) result.frame().graph().nodes().getFirst().data()).tick();
    }

    private static AnimationState contextUnchecked(ParCoolProjection.Result result) {
        try {
            return context(result);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static <T extends Record> T roundTrip(T value) {
        var buffer =
                new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(AnimationSchema.encode(value)));
        try {
            @SuppressWarnings("unchecked")
            T result = (T) AnimationSchema.readComplete(buffer, value.getClass());
            assertEquals(value, result);
            return result;
        } finally {
            buffer.release();
        }
    }

    static Object value(Type type) throws Exception {
        if (type instanceof ParameterizedType) return List.of();
        Class<?> c = (Class<?>) type;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == float.class) return -0f;
        if (c == double.class) return -0d;
        if (c == boolean.class) return false;
        if (c == String.class) return "fixture";
        if (c == AnimationGraphState.class) return AnimationGraphState.neutral();
        if (c == ParCool.class) return ParCoolNativeValues.context();
        if (c.isEnum()) return c.getEnumConstants()[0];
        var fields = c.getRecordComponents();
        var args = new Object[fields.length];
        var types = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType();
            args[i] =
                    fields[i].isAnnotationPresent(AnimationSchema.Nullable.class)
                            ? null
                            : value(fields[i].getGenericType());
        }
        return c.getConstructor(types).newInstance(args);
    }
}
