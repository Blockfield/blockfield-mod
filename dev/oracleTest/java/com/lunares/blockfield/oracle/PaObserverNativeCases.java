package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.*;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.util.*;

/** Pure observer schedule against independently replayed real PA layers, in production Knot. */
public final class PaObserverNativeCases {
    private PaObserverNativeCases() {}

    private static final PaGraph.Sources NONE = PaGraphNativeCases.SOURCES;

    private static Patch patch(
            KeyframeAnimation clip,
            LayerTag layer,
            PaGraph.Catalog catalog,
            AnimationPolicy policy) {
        var author = PaGraph.Stack.create(catalog, policy, NONE, LayerTag.PA_96, 1);
        author.once(layer, clip, false, 1);
        return new Patch(
                layer,
                StartKind.REPLACE,
                Outcome.SELECTED,
                author.fragment(layer),
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

    private static Start start(
            long sequence, LayerTag layer, long r, long a, int alpha, AnimationPolicy policy) {
        return new Start(
                sequence,
                100,
                layer,
                StartKind.REPLACE,
                AnchorClass.OBSERVER_APPLY,
                policy.domain(layer),
                policy.domain(layer) == PhaseDomain.R ? r : a,
                0,
                0,
                new ObserverApply(
                        sequence,
                        sequence,
                        1,
                        new Time(103, alpha),
                        new Time(r, alpha),
                        new Time(a, alpha),
                        105,
                        sequence));
    }

    private static void nativeInstall(ModifierLayer<IAnimation> layer, KeyframeAnimation clip) {
        layer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(
                        PaGraph.TACZ_FADE, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE),
                new KeyframeAnimationPlayer(clip.copy()),
                false);
    }

    private static void bits(
            PaProjection.Result result,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            int ticks,
            KeyframeAnimation first,
            KeyframeAnimation second) {
        for (float fraction : new float[] {0, 1f / 65536, .30f, .85f, 1 - 1f / 65536}) {
            var nativeLayer = new ModifierLayer<IAnimation>();
            nativeInstall(nativeLayer, first);
            nativeInstall(
                    nativeLayer, second); // two applications in one bucket; no tick between them
            for (int t = 0; t < ticks; t++) if (nativeLayer.isActive()) nativeLayer.tick();
            nativeLayer.setupAnim(fraction);
            var scratch = PaGraph.Stack.materialize(result.graph(), catalog, policy, NONE);
            scratch.sample(fraction, fraction);
            for (String part : List.of("head", "body", "leftArm", "rightLeg"))
                for (var type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
                    var base = new Vec3f(.25f, -.5f, 1.25f);
                    PaGraphNativeCases.bits(
                            nativeLayer.get3DTransform(part, type, fraction, base),
                            scratch.transform(part, type, base));
                }
        }
    }

    private static Object value(java.lang.reflect.Type type) throws ReflectiveOperationException {
        if (type instanceof java.lang.reflect.ParameterizedType) return List.of();
        Class<?> c = (Class<?>) type;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == float.class) return 0f;
        if (c == double.class) return 0d;
        if (c == boolean.class) return false;
        if (c == String.class) return "fixture";
        if (c == AnimationGraphState.class) return AnimationGraphState.neutral();
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

    /** A neutral authoritative vanilla checkpoint carrying the TaCZ stop-gate inputs. */
    public static AnimationState.Vanilla vanilla(
            AnimationTypes.Pose pose, boolean climbing, boolean swimming) {
        try {
            var base = (AnimationState.Vanilla) value(AnimationState.Vanilla.class);
            var flags = with(with(base.flags(), "climbing", climbing), "swimming", swimming);
            return with(with(base, "pose", pose), "flags", flags);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T with(T original, String field, Object replacement) {
        try {
            var components = original.getClass().getRecordComponents();
            var args = new Object[components.length];
            var types = new Class<?>[components.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = components[i].getType();
                args[i] =
                        components[i].getName().equals(field)
                                ? replacement
                                : components[i].getAccessor().invoke(original);
            }
            return (T) original.getClass().getConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int successors(PhaseDomain domain) throws Exception {
        var policy = PaGraphNativeCases.policy(domain);
        var catalog = new PaGraph.Catalog(1);
        var assets = new TaczAssets(catalog, Map.of("fixture:gun", "fixture:display"));
        var finite = PaGraphNativeCases.clip("hold_lower", false);
        var display =
                assets.register(
                        "fixture:display",
                        "fixture:animation",
                        false,
                        "default",
                        true,
                        Map.of("hold_lower", finite));
        var tacz =
                new AnimationState.Tacz(
                        EvaluatorKind.PA,
                        catalog.named("tacz.gun", "fixture:gun"),
                        null,
                        display.ref(),
                        false,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        Outcome.NO_CHANGE);
        var vanilla = (AnimationState.Vanilla) value(AnimationState.Vanilla.class);
        var placement = (AnimationState.Placement) value(AnimationState.Placement.class);
        var empty =
                new PaProjection.Result(
                        PaGraph.Stack.create(catalog, policy, NONE, LayerTag.PA_96, 1)
                                .freeze(AnimationGraphState.neutral()),
                        List.of());
        var result =
                PaProjection.apply(
                        empty,
                        patch(finite, LayerTag.PA_93, catalog, policy),
                        start(1, LayerTag.PA_93, 98, 100, 19660, policy),
                        catalog,
                        policy,
                        NONE);
        long base = domain == PhaseDomain.R ? 98 : 100;
        int successors = 0;
        for (long n = base + 1; n <= base + 64; n++) {
            result = PaProjection.tick(result, domain, catalog, policy, NONE);
            var patch =
                    new Patch(
                            LayerTag.PA_93,
                            StartKind.NEW,
                            Outcome.NO_CHANGE,
                            new AnimationGraphState(
                                    List.of(new AnimationGraphState.Root(93, LayerTag.PA_93, null)),
                                    List.of()),
                            List.of(),
                            null,
                            null,
                            tacz,
                            null,
                            null,
                            null,
                            vanilla,
                            null);
            var input = new InputRef(1, 7, n, Digest.hash(new byte[] {1}));
            var decision =
                    new Decision(
                            new EventId(n, n + 100, NativeStage.POST_NATIVE_TICK),
                            LayerTag.PA_93,
                            StartKind.NEW,
                            Outcome.NO_CHANGE,
                            0,
                            null,
                            input,
                            patch,
                            0);
            var before = result;
            result = PaProjection.taczDecision(result, decision, placement, assets, policy, NONE);
            if (!Arrays.equals(
                    AnimationSchema.encode(before.graph()),
                    AnimationSchema.encode(result.graph()))) {
                var newest =
                        result.starts().stream()
                                .max(Comparator.comparingLong(Start::eventSequence))
                                .orElseThrow();
                assertEquals(AnchorClass.PROJECTED_SUCCESSOR, newest.anchorClass());
                assertEquals(n, newest.phaseAnchor());
                assertEquals(domain, newest.phaseDomain());
                assertEquals(n, ((ProjectedSuccessor) newest.proof()).inputRef());
                successors++;
            }
        }
        assertTrue(successors >= 2);
        var context = (AnimationState) value(AnimationState.class);
        var id =
                new AnimationState.Identity(
                        1,
                        1,
                        1,
                        2,
                        0,
                        1,
                        7,
                        0,
                        0,
                        1,
                        1,
                        new Digest(1, 2, 3, 4),
                        new Digest(4, 3, 2, 1));
        context = with(with(context, "identity", id), "graph", result.graph());
        var digest =
                Digest.hash("pa-observer-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var seed =
                new ObserverSeed(
                        id,
                        new SeedRef(1, 7, 1, digest),
                        null,
                        context,
                        result.graph(),
                        base + 64,
                        base + 164,
                        base + 164,
                        result.starts(),
                        catalog.definitions(),
                        digest);
        var activation =
                new ObserverApply(
                        200,
                        1,
                        1,
                        new Time(base + 64, 0),
                        new Time(base + 64, 0),
                        new Time(base + 64, 0),
                        base + 64,
                        1000);
        var continuation = PaProjection.seedContinue(result, 1000, activation, policy);
        for (int i = 0; i < result.starts().size(); i++) {
            var old = result.starts().get(i);
            var continued =
                    continuation.starts().stream()
                            .filter(s -> s.nodeId() == old.nodeId())
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    AnimationStartReport.age(old, activation.applyR(), activation.applyA(), policy),
                    AnimationStartReport.age(
                            continued, activation.applyR(), activation.applyA(), policy));
            assertEquals(StartKind.SEED_CONTINUE, continued.startKind());
        }
        result = continuation;
        var folded =
                PaProjection.fold(
                        seed,
                        new SeedRef(1, 7, 2, digest),
                        context,
                        result,
                        seed.inputPrefix(),
                        1000,
                        seed.decisionPrefix(),
                        catalog.definitions(),
                        digest);
        assertEquals(seed.ref(), folded.predecessor());
        var wire =
                new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(AnimationSchema.encode(folded)));
        try {
            var decoded = AnimationSchema.readComplete(wire, ObserverSeed.class);
            assertArrayEquals(
                    AnimationSchema.encode(result.graph()),
                    AnimationSchema.encode(decoded.graph()));
            assertEquals(result.starts(), decoded.starts());
        } finally {
            wire.release();
        }
        return successors;
    }

    public static Map<String, Object> run() throws Exception {
        var first = PaGraphNativeCases.clip("observer-first", true);
        var second = PaGraphNativeCases.clip("observer-second", true);
        int comparisons = 0, folds = 0;
        for (PhaseDomain lower : PhaseDomain.values()) {
            var policy = PaGraphNativeCases.policy(lower);
            var catalog = new PaGraph.Catalog(1);
            var assets = new TaczAssets(catalog, Map.of());
            var empty =
                    new PaProjection.Result(
                            PaGraph.Stack.create(catalog, policy, NONE, LayerTag.PA_96, 1)
                                    .freeze(AnimationGraphState.neutral()),
                            List.of());
            LayerTag layer = LayerTag.PA_93;
            var one =
                    PaProjection.apply(
                            empty,
                            patch(first, layer, catalog, policy),
                            start(1, layer, 98, 100, 19660, policy),
                            catalog,
                            policy,
                            NONE);
            var two =
                    PaProjection.apply(
                            one,
                            patch(second, layer, catalog, policy),
                            start(2, layer, 98, 100, 55705, policy),
                            catalog,
                            policy,
                            NONE);
            assertEquals(2, two.starts().size(), "live source retains the earlier start");
            bits(two, catalog, policy, 0, first, second);
            byte[] retained = AnimationSchema.encode(two.graph());
            bits(two, catalog, policy, 0, first, second);
            assertArrayEquals(retained, AnimationSchema.encode(two.graph()));
            var journal = new HashMap<Long, List<Decision>>();
            long base = lower == PhaseDomain.R ? 98 : 100;
            for (long n = base + 1; n <= base + 120; n++) journal.put(n, List.of());
            var unfurled = two;
            var folded = two;
            for (int segment = 0; segment < 4; segment++) {
                long from = base + segment * 30;
                for (long n = from; n < from + 30; n++)
                    unfurled =
                            PaProjection.project(
                                    unfurled,
                                    policy.domain(layer),
                                    n,
                                    n + 1,
                                    journal,
                                    Map.of(),
                                    assets,
                                    policy,
                                    NONE);
                folded =
                        PaProjection.project(
                                folded,
                                policy.domain(layer),
                                from,
                                from + 30,
                                journal,
                                Map.of(),
                                assets,
                                policy,
                                NONE);
                var wire =
                        new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(
                                        AnimationSchema.encode(folded.graph())));
                try {
                    folded =
                            PaProjection.retain(
                                    AnimationSchema.readComplete(wire, AnimationGraphState.class),
                                    folded.starts());
                } finally {
                    wire.release();
                }
                assertArrayEquals(
                        AnimationSchema.encode(unfurled.graph()),
                        AnimationSchema.encode(folded.graph()));
                bits(folded, catalog, policy, (segment + 1) * 30, first, second);
                comparisons += 40;
                folds++;
            }
            assertThrows(
                    PaGraph.Unsupported.class,
                    () ->
                            PaProjection.project(
                                    two, lower, base, base + 41, journal, Map.of(), assets, policy,
                                    NONE));
            assertThrows(
                    PaGraph.Unsupported.class,
                    () ->
                            PaProjection.project(
                                    two, lower, base, base + 1, Map.of(), Map.of(), assets, policy,
                                    NONE));
            // Equal final H does not admit an event missing from the historical applied prefix.
            var earlierPrefix =
                    PaProjection.project(
                            one,
                            policy.domain(layer),
                            base,
                            base + 1,
                            journal,
                            Map.of(),
                            assets,
                            policy,
                            NONE);
            var laterPrefix =
                    PaProjection.project(
                            two,
                            policy.domain(layer),
                            base,
                            base + 1,
                            journal,
                            Map.of(),
                            assets,
                            policy,
                            NONE);
            assertNotEquals(
                    Digest.hash(AnimationSchema.encode(earlierPrefix.graph())),
                    Digest.hash(AnimationSchema.encode(laterPrefix.graph())));
            assertEquals(1, earlierPrefix.starts().size());
        }
        return Map.of(
                "projectedSuccessors",
                successors(PhaseDomain.A) + successors(PhaseDomain.R),
                "nativeBitComparisons",
                comparisons,
                "foldedSegments",
                folds,
                "longLivedTicks",
                120,
                "sameBucketStarts",
                List.of(.30, .85),
                "lowerPolicies",
                List.of("A", "R"),
                "equalFinalHDifferentPrefixes",
                true,
                "missingBoundaryUnavailable",
                true,
                "sixBoxEquality",
                false);
    }
}
