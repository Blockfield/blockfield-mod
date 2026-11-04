package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.Speed;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.layered.*;
import dev.kosmx.playerAnim.api.layered.modifier.*;
import dev.kosmx.playerAnim.core.data.AnimationFormat;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.lang.reflect.Field;
import java.util.*;

/** Shared client/server native cases; both callers execute inside production Knot. */
public final class PaGraphNativeCases {
    private PaGraphNativeCases() {}

    public static final PaGraph.Sources SOURCES = id -> part -> Optional.empty();

    public static AnimationPolicy policy(PhaseDomain lower) {
        return new AnimationPolicy(1, lower, true, true, true, true, AnimationPolicy.registered());
    }

    public static KeyframeAnimation clip(String name, boolean loop) {
        var builder = new KeyframeAnimation.AnimationBuilder(AnimationFormat.UNKNOWN);
        builder.beginTick = 2;
        builder.endTick = 18;
        builder.stopTick = 23;
        builder.isLooped = loop;
        builder.returnTick = 3;
        builder.setName(name);
        for (String part : List.of("body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg")) {
            var tracks = builder.getOrCreatePart(part);
            tracks.pitch.setEnabled(true);
            tracks.pitch.addKeyFrame(2, -0.2f, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
            tracks.pitch.addKeyFrame(9, 0.7f, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
            tracks.pitch.addKeyFrame(18, -0.1f, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
            tracks.x.setEnabled(true);
            tracks.x.addKeyFrame(2, 0.5f, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
            tracks.x.addKeyFrame(18, 2.0f, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
        }
        return builder.build();
    }

    private record Native(
            AnimationStack stack,
            Map<LayerTag, ModifierLayer<IAnimation>> layers,
            Map<AdjustmentModifier, AdjustmentSource> sources) {}

    private static Native fresh(KeyframeAnimation first, KeyframeAnimation next, int ticks) {
        var nativeStack = new AnimationStack();
        var layers = new EnumMap<LayerTag, ModifierLayer<IAnimation>>(LayerTag.class);
        var sources = new IdentityHashMap<AdjustmentModifier, AdjustmentSource>();
        for (LayerTag tag : PaGraph.PA_ROOTS) {
            var layer = new ModifierLayer<IAnimation>();
            layers.put(tag, layer);
            nativeStack.addAnimLayer(AnimationGraphState.priority(tag), layer);
            if (tag == LayerTag.PA_42 || tag == LayerTag.PA_96) {
                var adjustment = new AdjustmentModifier(part -> Optional.empty());
                layer.addModifierLast(adjustment);
                sources.put(
                        adjustment,
                        tag == LayerTag.PA_42
                                ? AdjustmentSource.LR_BODY_POSE
                                : AdjustmentSource.HELD_WEAPON_POSE);
            }
        }
        var actor = new KeyframeAnimationPlayer(first, 4);
        actor.perspective = 2;
        actor.setFirstPersonConfiguration(new FirstPersonConfiguration(true, false, false, true));
        actor.setFirstPersonMode(dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.DISABLED);
        var wrapper = new ModifierLayer<IAnimation>();
        wrapper.setAnimation(actor);
        wrapper.addModifierLast(new SpeedModifier(2f / 3));
        layers.get(LayerTag.PA_93).setAnimation(wrapper);
        var action = layers.get(LayerTag.PA_95);
        action.setAnimation(new KeyframeAnimationPlayer(first, 3));
        action.replaceAnimationWithFade(fade(8), new KeyframeAnimationPlayer(next), false);
        nativeStack.tick();
        nativeStack.tick();
        action.replaceAnimationWithFade(fade(5), new KeyframeAnimationPlayer(first, 1), false);
        for (int i = 0; i < ticks; i++) nativeStack.tick();
        return new Native(nativeStack, layers, sources);
    }

    private static AbstractFadeModifier fade(int length) {
        return AbstractFadeModifier.standardFadeIn(
                length, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
    }

    private static AnimationGraphState freeze(
            Native n, PaGraph.Catalog catalog, AnimationPolicy policy) {
        return PaGraph.freezeNative(
                n.stack(),
                catalog,
                policy,
                Map.of(),
                n.sources(),
                LayerTag.PA_96,
                1,
                AnimationGraphState.neutral());
    }

    public static Map<String, Object> checkpoint() {
        var first = clip("loop", true);
        var next = clip("once", false);
        var catalog = new PaGraph.Catalog(1);
        catalog.register(first);
        catalog.register(next);
        var policy = policy(PhaseDomain.A);
        var nativeState = fresh(first, next, 0);
        var graph = freeze(nativeState, catalog, policy);
        assertEquals(
                graph, PaGraph.Stack.materialize(graph, catalog, policy, SOURCES).freeze(graph));
        var leaseStore = new AnimationResources.Store();
        leaseStore.installAll(catalog.definitions());
        try (var lease = leaseStore.pin(PaGraph.references(graph))) {
            assertNotNull(lease);
        }
        assertEquals(17, graph.roots().size());
        assertEquals(1, graph.aliases().size());
        var canonical = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
        var initialActors =
                graph.nodes().stream()
                        .filter(n -> n.data() instanceof AnimationGraphState.KeyframePlayer)
                        .map(
                                n -> {
                                    var actorState = (AnimationGraphState.KeyframePlayer) n.data();
                                    return Map.of(
                                            "nodeId",
                                            n.id(),
                                            "tick",
                                            actorState.currentTick(),
                                            "running",
                                            actorState.isRunning(),
                                            "loopStarted",
                                            actorState.isLoopStarted(),
                                            "tickDeltaBits",
                                            Float.floatToRawIntBits(actorState.tickDelta()),
                                            "perspective",
                                            actorState.perspective(),
                                            "firstPersonMode",
                                            actorState.firstPersonMode().name());
                                })
                        .toList();
        int comparisons = 0;
        for (int tick = 0; tick < 32; tick++) {
            for (float p : new float[] {0, 1f / 65536, 0.3f, 0.5f, 0.85f, 1 - 1f / 65536, 1}) {
                var reference = fresh(first, next, tick);
                reference.stack().setupAnim(p);
                var scratch = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
                scratch.sample(p);
                compare(reference.stack(), scratch, p);
                comparisons++;
                assertThrows(IllegalStateException.class, scratch::tick);
                assertThrows(IllegalStateException.class, () -> scratch.freeze(graphForFailure()));
                assertEquals(graph, canonical.freeze(graph));
            }
            canonical.tick();
            graph = canonical.freeze(graph);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("initialActorBits", initialActors);
        result.put("sampleComparisons", comparisons);
        result.put("subsequentIntegerTicks", 32);
        result.put("nodeCount", graph.nodes().size());
        result.put("sharedRotationAlias", true);
        result.put("nestedLiveFades", true);
        return result;
    }

    private static AnimationGraphState wire(AnimationGraphState graph) {
        var bytes = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            AnimationSchema.write(bytes, graph);
            var decoded = AnimationSchema.readComplete(bytes, AnimationGraphState.class);
            assertEquals(graph, decoded);
            return decoded;
        } finally {
            bytes.release();
        }
    }

    private static Native stopFresh(KeyframeAnimation clip, int tick) {
        var n = fresh(clip, clip, 0);
        for (int step = 0; step <= tick; step++) {
            if (step == 3)
                n.layers().get(LayerTag.PA_95).replaceAnimationWithFade(fade(8), null, false);
            if (step == 6)
                n.layers()
                        .get(LayerTag.PA_95)
                        .replaceAnimationWithFade(
                                fade(8), new KeyframeAnimationPlayer(clip), false);
            if (step < tick) n.stack().tick();
        }
        return n;
    }

    public static Map<String, Object> stopsAndMissing() {
        var clip = clip("finite", false);
        var catalog = new PaGraph.Catalog(1);
        catalog.register(clip);
        var policy = policy(PhaseDomain.A);
        var graph = wire(freeze(stopFresh(clip, 0), catalog, policy));
        var canonical = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
        for (int tick = 0; tick <= 40; tick++) {
            if (tick == 3) assertEquals(Outcome.STOPPED, canonical.stop(LayerTag.PA_95, 8, 1));
            if (tick == 6) assertEquals(Outcome.SELECTED, canonical.loop(LayerTag.PA_95, clip, 2));
            graph = wire(canonical.freeze(graph));
            assertEquals(Outcome.MISSING_CLIP, canonical.loop(LayerTag.PA_95, null, 3));
            assertEquals(
                    graph, canonical.freeze(graph), "missing clip must retain the incumbent graph");
            for (float p : new float[] {0, 1f / 65536, 0.3f, 0.85f, 1}) {
                var nativeState = stopFresh(clip, tick);
                nativeState.stack().setupAnim(p);
                var scratch = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
                scratch.sample(p);
                compare(nativeState.stack(), scratch, p);
            }
            canonical.tick();
        }
        assertFalse(canonical.active(), "finite actors must reach their native stop tick");
        return Map.of(
                "nativeFiniteStopComparisons",
                205,
                "stopFadeTick",
                3,
                "interruptFadeTick",
                6,
                "missingClipRetainsIncumbent",
                true,
                "graphWireRoundTrips",
                42);
    }

    /**
     * ADR4.1 errata (U3 P1-5): native PA's zero-age fade-in is {@code 0f/0f} at fraction 0. Both
     * evaluators use its right-hand limit; the native outputs at fractions 2^-16 and 2^-24 converge
     * to it, and the client and server sessions reproduce the same pinned bits.
     */
    public static Map<String, Object> nativeZeroAgeSingularity() {
        var builder = clip("zero", true).mutableCopy();
        builder.beginTick = 0;
        var clip = builder.build();
        var catalog = new PaGraph.Catalog(1);
        catalog.register(clip);
        var n = fresh(clip, clip, 0);
        var layer = n.layers().get(LayerTag.PA_96);
        layer.removeModifier(0);
        var source =
                new AdjustmentModifier(
                        part ->
                                Optional.of(
                                        new AdjustmentModifier.PartModifier(
                                                new Vec3f(0.25f, 0.5f, 0), Vec3f.ZERO)));
        layer.addModifierLast(source);
        layer.setAnimation(new KeyframeAnimationPlayer(clip));
        n.sources().put(source, AdjustmentSource.HELD_WEAPON_POSE);
        var policy = policy(PhaseDomain.A);
        var graph = freeze(n, catalog, policy);
        PaGraph.Sources sources =
                id ->
                        part ->
                                Optional.of(
                                        new AdjustmentModifier.PartModifier(
                                                new Vec3f(0.25f, 0.5f, 0), Vec3f.ZERO));
        var result = new LinkedHashMap<String, Object>();
        var limits = new ArrayList<Integer>();
        int singular = 0;
        for (String part : PaGraph.RIG_PARTS) {
            n.stack().setupAnim(0);
            Vec3f nativeZero =
                    n.stack().get3DTransform(part, TransformType.ROTATION, 0, Vec3f.ZERO);
            var scratch = PaGraph.Stack.materialize(graph, catalog, policy, sources);
            scratch.sample(0);
            Vec3f limit = scratch.transform(part, TransformType.ROTATION, Vec3f.ZERO);
            scratch.requireFinite();
            float[] near = new float[2];
            Vec3f[] neighbours = new Vec3f[2];
            float[] fractions = {1f / 65536, 1f / (1 << 24)};
            for (int k = 0; k < 2; k++) {
                n.stack().setupAnim(fractions[k]);
                neighbours[k] =
                        n.stack()
                                .get3DTransform(
                                        part, TransformType.ROTATION, fractions[k], Vec3f.ZERO);
                near[k] = distance(neighbours[k], limit);
            }
            assertTrue(near[1] <= near[0], part + " converges toward the limit");
            assertTrue(near[1] <= 1e-6f, part + " 2^-24 neighbour within 1e-6 of the limit");
            if (!finite(nativeZero)) singular++;
            else bits(nativeZero, limit);
            for (float value : new float[] {limit.getX(), limit.getY(), limit.getZ()})
                limits.add(Float.floatToRawIntBits(value));
            result.put(part + ".distance2^-16", near[0]);
            result.put(part + ".distance2^-24", near[1]);
        }
        assertTrue(singular > 0, "the native zero-age sample must be singular");
        // Both sessions run this case: equal pinned bits prove client and server agree.
        assertEquals(LIMIT_BITS, limits, "pinned right-limit bits");
        result.put("nativeSingularParts", singular);
        result.put("rightLimitBits", limits);
        result.put(
                "rule", "native 0f/0f fade-in at fraction 0 replaced by its right-hand limit 1f");
        return result;
    }

    /** Body..rightLeg rotation x,y,z raw bits of the right-limit sample; pinned on both sides. */
    private static final List<Integer> LIMIT_BITS = limitBits();

    private static List<Integer> limitBits() {
        // Adjustment (0.25f, 0.5f, 0) at fade 1 over the zero-age clip, for each of seven parts.
        var bits = new ArrayList<Integer>();
        for (int part = 0; part < 7; part++) bits.addAll(List.of(0x3e800000, 0x3f000000, 0));
        return List.copyOf(bits);
    }

    private static float distance(Vec3f a, Vec3f b) {
        return Math.max(
                Math.abs(a.getX() - b.getX()),
                Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    private static boolean finite(Vec3f value) {
        return Float.isFinite(value.getX())
                && Float.isFinite(value.getY())
                && Float.isFinite(value.getZ());
    }

    private static AnimationGraphState graphForFailure() {
        return AnimationGraphState.neutral();
    }

    private static void compare(AnimationStack nativeStack, PaGraph.Stack scratch, float p) {
        assertEquals(nativeStack.isActive(), scratch.active());
        for (String part : List.of("body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg"))
            for (TransformType type : TransformType.values()) {
                Vec3f base =
                        type == TransformType.SCALE
                                ? new Vec3f(1, 1, 1)
                                : new Vec3f(0.3f, -0.2f, 0.5f);
                bits(
                        nativeStack.get3DTransform(part, type, p, base),
                        scratch.transform(part, type, base));
            }
    }

    public static void bits(Vec3f expected, Vec3f actual) {
        assertArrayEquals(
                new int[] {
                    Float.floatToRawIntBits(expected.getX()),
                    Float.floatToRawIntBits(expected.getY()),
                    Float.floatToRawIntBits(expected.getZ())
                },
                new int[] {
                    Float.floatToRawIntBits(actual.getX()),
                    Float.floatToRawIntBits(actual.getY()),
                    Float.floatToRawIntBits(actual.getZ())
                });
    }

    public static Map<String, Object> speedBoundaries() throws Exception {
        var clip = clip("speed", false);
        var catalog = new PaGraph.Catalog(1);
        catalog.register(clip);
        var policy = policy(PhaseDomain.A);
        var rows = new ArrayList<Map<String, Object>>();
        for (float shifted : new float[] {Math.nextDown(1f), 1, Math.nextUp(1f)}) {
            for (float p : new float[] {0, 1f / 65536, 0.5f, 1 - 1f / 65536, 1}) {
                var nativeState = fresh(clip, clip, 0);
                var wrapper =
                        (ModifierLayer<?>) nativeState.layers().get(LayerTag.PA_93).getAnimation();
                var speed = (SpeedModifier) modifiers(wrapper).getFirst();
                field(SpeedModifier.class, "shiftedDelta").setFloat(speed, shifted);
                field(SpeedModifier.class, "delta").setFloat(speed, 0);
                var graph = freeze(nativeState, catalog, policy);
                var scratch = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
                nativeState.stack().setupAnim(p);
                scratch.sample(p);
                compare(nativeState.stack(), scratch, p);
                var actor = (KeyframeAnimationPlayer) wrapper.getAnimation();
                rows.add(
                        Map.of(
                                "shiftedBeforeBits",
                                Float.floatToRawIntBits(shifted),
                                "fractionBits",
                                Float.floatToRawIntBits(p),
                                "childTick",
                                actor.getTick(),
                                "shiftedAfterBits",
                                Float.floatToRawIntBits(
                                        field(SpeedModifier.class, "shiftedDelta")
                                                .getFloat(speed))));
            }
        }
        var speed = new SpeedModifier(1);
        var child = new KeyframeAnimationPlayer(clip);
        speed.setAnim(child);
        speed.setupAnim(1);
        assertEquals(0, child.getTick(), "native strict >1 does not tick at exactly 1");
        speed.setupAnim(Math.nextUp(1f));
        assertEquals(1, child.getTick());
        return Map.of("strictGreaterThanOne", true, "cases", rows);
    }

    public static Map<String, Object> history() throws Exception {
        var clip = clip("history", true);
        var catalog = new PaGraph.Catalog(1);
        catalog.register(clip);
        var policy = policy(PhaseDomain.A);
        var rows = new ArrayList<Map<String, Object>>();
        AnimationGraphState expected = null;
        for (int fps : new int[] {30, 60, 144, 240}) {
            var nativeState = fresh(clip, clip, 0);
            var canonical =
                    PaGraph.Stack.materialize(
                            freeze(nativeState, catalog, policy), catalog, policy, SOURCES);
            for (int tick = 0; tick < 24; tick++) {
                int first = (tick * fps + 19) / 20;
                int last = ((tick + 1) * fps + 19) / 20;
                var retained = canonical.freeze(AnimationGraphState.neutral());
                for (int frame = first; frame < last; frame++) {
                    float p = (float) ((double) frame * 20 / fps - tick);
                    nativeState.stack().setupAnim(p);
                    var scratch = PaGraph.Stack.materialize(retained, catalog, policy, SOURCES);
                    scratch.sample(p);
                    scratch.transform("leftArm", TransformType.ROTATION, Vec3f.ZERO);
                }
                assertEquals(retained, canonical.freeze(retained));
                nativeState.stack().tick();
                canonical.tick();
            }
            var graph = canonical.freeze(AnimationGraphState.neutral());
            if (expected == null) expected = graph;
            else assertEquals(expected, graph, "canonical graph cannot depend on prior FPS");
            var wrapper =
                    (ModifierLayer<?>) nativeState.layers().get(LayerTag.PA_93).getAnimation();
            var speed = (SpeedModifier) modifiers(wrapper).getFirst();
            var actor = (KeyframeAnimationPlayer) wrapper.getAnimation();
            rows.add(
                    Map.of(
                            "fps",
                            fps,
                            "nativeChildTick",
                            actor.getTick(),
                            "nativeShiftedBits",
                            Float.floatToRawIntBits(
                                    field(SpeedModifier.class, "shiftedDelta").getFloat(speed)),
                            "canonicalSpeed",
                            graph.nodes().stream()
                                    .filter(n -> n.data() instanceof Speed)
                                    .map(n -> n.data().toString())
                                    .toList()));
        }
        return Map.of("trajectories", rows, "canonicalRetainedHistoryIndependent", true);
    }

    public static Map<String, Object> lowerDomainAndAssets() {
        var clip = clip("lower", true);
        var catalog = new PaGraph.Catalog(1);
        var ref = catalog.register(clip);
        var policy = policy(PhaseDomain.R);
        var graph = freeze(fresh(clip, clip, 0), catalog, policy);
        var scratch = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
        assertThrows(PaGraph.Unsupported.class, () -> scratch.sample(0.8f));
        scratch.sample(0.8f, 0.2f);
        var reference = fresh(clip, clip, 0);
        var direct = new AnimationStack();
        for (LayerTag tag : PaGraph.PA_ROOTS) {
            var layer = reference.layers().get(tag);
            float fraction = policy.domain(tag) == PhaseDomain.R ? 0.2f : 0.8f;
            layer.setupAnim(fraction);
            direct.addAnimLayer(
                    AnimationGraphState.priority(tag),
                    new IAnimation() {
                        @Override
                        public boolean isActive() {
                            return layer.isActive();
                        }

                        @Override
                        public void setupAnim(float ignored) {}

                        @Override
                        public Vec3f get3DTransform(
                                String part, TransformType type, float ignored, Vec3f base) {
                            return layer.get3DTransform(part, type, fraction, base);
                        }
                    });
        }
        compare(direct, scratch, 0.8f);
        var invalid = fresh(clip, clip, 0);
        var invalidWrapper = (ModifierLayer<?>) invalid.layers().get(LayerTag.PA_93).getAnimation();
        ((KeyframeAnimationPlayer) invalidWrapper.getAnimation()).bodyParts.remove("head");
        assertThrows(PaGraph.Unsupported.class, () -> freeze(invalid, catalog, policy));
        var firstPerson = PaGraph.Stack.create(catalog, policy, SOURCES, LayerTag.PA_96, 1);
        firstPerson.loop(LayerTag.PA_93, clip, 1);
        var lowerActor = (KeyframeAnimationPlayer) firstPerson.layer(LayerTag.PA_93).getAnimation();
        var configuration = new FirstPersonConfiguration(true, false, false, true);
        lowerActor.setFirstPersonConfiguration(configuration);
        lowerActor.setFirstPersonMode(
                dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.DISABLED);
        assertEquals(
                lowerActor.getFirstPersonMode(.2f),
                firstPerson.nativeStack().getFirstPersonMode(.8f));
        assertEquals(configuration, firstPerson.nativeStack().getFirstPersonConfiguration(.8f));
        var starts = PaGraph.Stack.create(catalog, policy, SOURCES, LayerTag.PA_96, 1);
        var actor = new KeyframeAnimationPlayer(clip);
        assertThrows(PaGraph.Unsupported.class, () -> starts.started(actor, 1, new Object()));
        starts.started(actor, 1, null);
        var container = new AnimationContainer<IAnimation>(actor);
        starts.started(container, 2, actor);
        starts.layer(LayerTag.PA_95).setAnimation(container);
        var startKeys =
                starts.freeze(AnimationGraphState.neutral()).nodes().stream()
                        .map(AnimationGraphState.Node::start)
                        .filter(Objects::nonNull)
                        .toList();
        assertEquals(2, startKeys.size());
        assertEquals(
                startKeys.getFirst(), startKeys.getLast(), "retained source anchor is inherited");
        var unchanged = catalog.clip(ref);
        assertThrows(
                AssertionError.class,
                () -> clip.getBodyParts().get("body").pitch.setEnabled(false));
        clip.extraData.put("name", "changed-source");
        assertEquals("lower", unchanged.extraData.get("name"));
        assertTrue(catalog.clip(ref).getBodyParts().get("body").pitch.isEnabled());
        var privateView = catalog.clip(ref);
        privateView.extraData.put("lr_attack_action", true);
        assertFalse(catalog.clip(ref).extraData.containsKey("lr_attack_action"));
        return Map.of("rLowerFraction", 0.2f, "aUpperFraction", 0.8f, "catalogIsolated", true);
    }

    private static Native lrFresh(KeyframeAnimation first, KeyframeAnimation next, int ticks)
            throws Exception {
        var n = fresh(first, next, 0);
        var source = new KeyframeAnimationPlayer(first, 9);
        source.stop();
        var frozenCtor =
                Class.forName(
                                "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration$FrozenAnimationSnapshot")
                        .getDeclaredConstructor(KeyframeAnimationPlayer.class);
        frozenCtor.setAccessible(true);
        var frozen = (IAnimation) frozenCtor.newInstance(source);
        var forcedCtor =
                Class.forName(
                                "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration$ForcedFadeModifier")
                        .getDeclaredConstructor(
                                int.class, dev.kosmx.playerAnim.core.util.Ease.class);
        forcedCtor.setAccessible(true);
        var forced =
                (AbstractFadeModifier)
                        forcedCtor.newInstance(5, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
        forced.setBeginAnimation(frozen);
        var layer = n.layers().get(LayerTag.PA_40);
        layer.setAnimation(new KeyframeAnimationPlayer(next));
        layer.addModifierLast(forced);
        for (int i = 0; i < ticks; i++) n.stack().tick();
        return n;
    }

    public static Map<String, Object> frozenForced() throws Exception {
        var first = clip("frozen", true);
        var next = clip("replacement", false);
        var catalog = new PaGraph.Catalog(1);
        catalog.register(first);
        catalog.register(next);
        var policy = policy(PhaseDomain.A);
        var graph = freeze(lrFresh(first, next, 0), catalog, policy);
        assertTrue(
                graph.nodes().stream()
                        .anyMatch(n -> n.data() instanceof AnimationGraphState.LrFrozen));
        assertTrue(
                graph.nodes().stream()
                        .anyMatch(n -> n.data() instanceof AnimationGraphState.LrForcedFade));
        var canonical = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
        for (int tick = 0; tick < 12; tick++) {
            for (float p : new float[] {0, 1f / 65536, 0.3f, 0.85f, 1}) {
                var nativeState = lrFresh(first, next, tick);
                nativeState.stack().setupAnim(p);
                var scratch = PaGraph.Stack.materialize(graph, catalog, policy, SOURCES);
                scratch.sample(p);
                compare(nativeState.stack(), scratch, p);
            }
            canonical.tick();
            graph = canonical.freeze(graph);
        }
        return Map.of(
                "nativeLrFrozenForcedComparisons",
                60,
                "subsequentIntegerTicks",
                12,
                "sourceStoppedRunningBitRetained",
                true,
                "sourceAlwaysFractionZero",
                true);
    }

    public static Map<String, Object> easeAndRefusals() throws Exception {
        var clip = clip("guards", true);
        var catalog = new PaGraph.Catalog(1);
        catalog.register(clip);
        for (var nativeEase : dev.kosmx.playerAnim.core.util.Ease.values()) {
            var wire =
                    Arrays.stream(Ease.values())
                            .filter(e -> e.id() == nativeEase.getId())
                            .findFirst()
                            .orElseThrow();
            assertEquals(nativeEase.name(), wire.name());
        }
        for (String name :
                List.of(
                        "dev.kosmx.playerAnim.api.layered.modifier.MirrorModifier",
                        "dev.kosmx.playerAnim.api.layered.modifier.FirstPersonModifier",
                        "dev.kosmx.playerAnim.minecraftApi.layers.LeftHandedHelperModifier")) {
            var n = fresh(clip, clip, 0);
            var modifier =
                    (AbstractModifier)
                            Class.forName("org.objenesis.ObjenesisHelper")
                                    .getMethod("newInstance", Class.class)
                                    .invoke(null, Class.forName(name));
            n.layers().get(LayerTag.PA_94).addModifierLast(modifier);
            var failure =
                    assertThrows(
                            PaGraph.Unsupported.class,
                            () -> freeze(n, catalog, policy(PhaseDomain.A)));
            assertTrue(failure.getMessage().contains("First-person-only"));
        }
        var n = fresh(clip, clip, 0);
        var unlinked =
                new ModifierLayer<IAnimation>(
                        new KeyframeAnimationPlayer(clip), new SpeedModifier(2f / 3));
        assertFalse(unlinked.isActive());
        n.layers().get(LayerTag.PA_95).setAnimation(unlinked);
        var failure =
                assertThrows(
                        PaGraph.Unsupported.class, () -> freeze(n, catalog, policy(PhaseDomain.A)));
        assertEquals("Unhosted PA modifier", failure.getMessage());
        return Map.of(
                "easeIds",
                Arrays.stream(dev.kosmx.playerAnim.core.util.Ease.values())
                        .map(e -> Map.of("name", e.name(), "id", (int) e.getId()))
                        .toList(),
                "refusedModifierClasses",
                3,
                "unhostedNativeWrapper",
                "rejected-invalid-topology");
    }

    public static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    public static List<AbstractModifier> modifiers(ModifierLayer<?> layer) throws Exception {
        return (List<AbstractModifier>) field(ModifierLayer.class, "modifiers").get(layer);
    }
}
