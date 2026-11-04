package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.lunares.blockfield.common.StanceTransition;
import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationGraphState.Crawl;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes;
import com.lunares.blockfield.common.combat.AnimationTypes.ActionTag;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.LrPose;
import com.lunares.blockfield.common.combat.LrProjection;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.PaProjection;
import com.lunares.blockfield.common.combat.ParCoolFrame;
import com.lunares.blockfield.common.combat.StanceLayers;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer;
import dev.kosmx.playerAnim.core.util.Vec3f;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityDimensions;

/**
 * STANCE-EASE c2 shared scenario driver (owner rule: every layer eases a stance change over {@link
 * StanceTransition#TICKS} ticks; the server six-box pose must equal the remote body the shooter
 * sees, bit-identically, at every tick).
 *
 * <p>Pure common code, so the server and the client oracle fixtures run the same driver: a live
 * {@link StanceTransition} plays each scenario, every tick's snapshot becomes an {@link
 * AnimationState.Stance} exactly as {@code VanillaAnimSync.stance} builds it, the record crosses
 * the {@link AnimationSchema} wire codec, and a driven mirror restores it through {@link
 * StanceTransition#snapshotOf}. The driver asserts the mirror's box and crouch coefficient equal
 * the server's bit for bit at every tick — interrupted transitions reproduce exactly, with no
 * inference from boxes. Bone/offset equality follows from the shared {@link
 * StanceTransition.Crouch#at} path on the proven-identical coefficient; each side's fixture checks
 * its own consumer against that path.
 */
public final class StanceEaseCases {
    public static final EntityDimensions STANDING =
            EntityDimensions.scalable(0.6f, 1.8f).withEyeHeight(1.62f);
    public static final EntityDimensions CROUCHED =
            EntityDimensions.scalable(0.6f, 1.5f).withEyeHeight(1.27f);
    public static final EntityDimensions PRONE =
            EntityDimensions.scalable(0.6f, 0.6f).withEyeHeight(0.4f);

    /** One tick of the script: the pose the server observes; a null target freezes the held box. */
    public record Tick(EntityDimensions target, boolean prone, boolean crouching) {}

    /** One scenario: a full script of observed poses. */
    public record Scenario(String name, List<Tick> ticks) {}

    private static Tick stand() {
        return new Tick(STANDING, false, false);
    }

    private static Tick crouch() {
        return new Tick(CROUCHED, false, true);
    }

    private static Tick prone() {
        return new Tick(PRONE, true, false);
    }

    /** A ceiling-hold tick: the box and coefficient freeze instead of easing toward a target. */
    static Tick hold() {
        return new Tick(null, false, false);
    }

    private static Scenario script(String name, Tick... ticks) {
        return new Scenario(name, List.of(ticks));
    }

    private static List<Tick> repeat(Tick tick, int n) {
        var out = new ArrayList<Tick>();
        for (int i = 0; i < n; i++) out.add(tick);
        return out;
    }

    /**
     * The five c2 scenarios: full swings both ways plus every interruption the triple must carry.
     */
    public static List<Scenario> scenarios() {
        var standToCrouchToStand = new ArrayList<Tick>();
        standToCrouchToStand.addAll(repeat(crouch(), StanceTransition.TICKS + 1));
        standToCrouchToStand.addAll(repeat(stand(), StanceTransition.TICKS + 1));
        var interruptedAt3 = new ArrayList<Tick>();
        interruptedAt3.addAll(repeat(crouch(), 4));
        interruptedAt3.addAll(repeat(stand(), StanceTransition.TICKS + 1));
        var standToProne = new ArrayList<Tick>(repeat(prone(), StanceTransition.TICKS + 1));
        var crouchToProne = new ArrayList<Tick>();
        crouchToProne.addAll(repeat(crouch(), StanceTransition.TICKS + 1));
        crouchToProne.addAll(repeat(prone(), StanceTransition.TICKS + 1));
        var proneInterruptedByCrouch = new ArrayList<Tick>();
        proneInterruptedByCrouch.addAll(repeat(prone(), 4));
        proneInterruptedByCrouch.addAll(repeat(crouch(), StanceTransition.TICKS + 1));
        return List.of(
                script("stand-crouch-stand", standToCrouchToStand.toArray(Tick[]::new)),
                script("crouch-interrupted-at-3", interruptedAt3.toArray(Tick[]::new)),
                script("stand-prone", standToProne.toArray(Tick[]::new)),
                script("crouch-prone", crouchToProne.toArray(Tick[]::new)),
                script(
                        "prone-interrupted-by-crouch",
                        proneInterruptedByCrouch.toArray(Tick[]::new)),
                sneakHeldExit());
    }

    /**
     * STANCE-EASE c3b: the LR hold probe as a scenario. The sneak exit runs two ticks, the ceiling
     * freezes it for four, then it resumes and settles; the sampled LR layer must track the frozen
     * coefficient instead of its own clock.
     */
    public static Scenario sneakHeldExit() {
        var ticks = new ArrayList<Tick>();
        ticks.addAll(repeat(crouch(), StanceTransition.TICKS + 1));
        ticks.addAll(repeat(stand(), 2));
        ticks.addAll(repeat(hold(), 4));
        ticks.addAll(repeat(stand(), StanceTransition.TICKS + 1));
        return script("sneak-held-exit", ticks.toArray(Tick[]::new));
    }

    public static List<Scenario> crawlScenarios() {
        var full = new ArrayList<Tick>(repeat(prone(), StanceTransition.TICKS + 1));
        full.addAll(repeat(stand(), StanceTransition.TICKS + 1));
        var interrupted = new ArrayList<Tick>(repeat(prone(), 4));
        interrupted.addAll(repeat(crouch(), StanceTransition.TICKS + 1));
        return List.of(
                script("crawl-enter-exit", full.toArray(Tick[]::new)),
                script("crawl-interrupted-by-crouch", interrupted.toArray(Tick[]::new)),
                crawlHeldExit(),
                crawlLongHeldExit());
    }

    /**
     * STANCE-EASE c3b: the Crawl held-exit lifetime. The prone exit runs two ticks, the ceiling
     * freezes it for four with the Crawl action already stopped, then it resumes; the node must
     * survive the frozen window and finish the exit.
     */
    public static Scenario crawlHeldExit() {
        var ticks = new ArrayList<Tick>(repeat(prone(), StanceTransition.TICKS + 1));
        ticks.addAll(repeat(stand(), 2));
        ticks.addAll(repeat(hold(), 4));
        ticks.addAll(repeat(stand(), StanceTransition.TICKS + 1));
        return script("crawl-held-exit", ticks.toArray(Tick[]::new));
    }

    /**
     * STANCE-EASE c4: the same held exit frozen far past the old 8-tick freshness bound. The node
     * must survive the whole 30-tick hold and finish the exit after release, every tick
     * bit-identical on both runtimes.
     */
    public static Scenario crawlLongHeldExit() {
        var ticks = new ArrayList<Tick>(repeat(prone(), StanceTransition.TICKS + 1));
        ticks.addAll(repeat(stand(), 2));
        ticks.addAll(repeat(hold(), 30));
        ticks.addAll(repeat(stand(), StanceTransition.TICKS + 1));
        return script("crawl-long-held-exit", ticks.toArray(Tick[]::new));
    }

    public record Composition(
            String scenario,
            int tick,
            float fraction,
            StanceTransition.Snapshot stance,
            float swimO,
            boolean medkit) {
        public ParCoolFrame frame() {
            return ParCoolPresentationCases.frame(new Crawl(), tick, false);
        }

        public float swim() {
            return medkit ? 0 : net.minecraft.util.Mth.lerp(fraction, swimO, stance.flat());
        }
    }

    /** Overlapping coefficients, both directions and an interruption, at fractional A. */
    public static List<Composition> compositions(boolean medkit) {
        var result = new ArrayList<Composition>();
        var scripts =
                medkit
                        ? List.of(scenarios().getFirst())
                        : List.of(scenarios().get(3), scenarios().get(4));
        for (var script : scripts) {
            var stance = new StanceTransition();
            var box = stance.apply(STANDING, STANDING, true, false, false);
            int tick = 0;
            for (var step : script.ticks()) {
                float swimO = stance.flatness();
                box = stance.apply(box, step.target(), true, step.prone(), step.crouching());
                var snap =
                        StanceTransition.snapshotOf(
                                roundTrip(recordOf(stance.snapshot())),
                                stance.snapshot().from(),
                                stance.snapshot().to());
                // HOTFIX-2751 D3: crouch snaps, so mid-flight crouch states no longer exist.
                // The fractional-A composition coverage follows the remaining eased
                // coefficient (prone flat); medkit states sample the settled crouch instead.
                boolean flatMid = snap.flat() > 0 && snap.flat() < 1;
                if (flatMid || (medkit && snap.crouch() == 1 && snap.flat() == 0))
                    for (float fraction : new float[] {.25f, .5f, 65535 / 65536f})
                        result.add(
                                new Composition(
                                        script.name(), tick, fraction, snap, swimO, medkit));
                stance.tick();
                tick++;
            }
        }
        assertFalse(result.isEmpty());
        return result;
    }

    /** Six parts and all 48 inner-skin corners; the client supplies native ModelPart matrices. */
    public static Map<String, Object> compositionGeometry(
            Composition value, float[] params, org.joml.Matrix4f[] matrices) {
        var corners = new ArrayList<Integer>();
        var vectors = new org.joml.Vector3f[8];
        for (int i = 0; i < 8; i++) vectors[i] = new org.joml.Vector3f();
        for (var box : com.lunares.blockfield.common.combat.BodyGeometry.skin()) {
            com.lunares.blockfield.common.combat.BodyRig.corners(
                    matrices[box.bone()], box, vectors);
            for (var v : vectors)
                for (float scalar : new float[] {v.x, v.y, v.z})
                    corners.add(Float.floatToRawIntBits(scalar));
        }
        var bones = new ArrayList<Integer>();
        for (float scalar : params) bones.add(Float.floatToRawIntBits(scalar));
        return Map.of(
                "scenario",
                value.scenario(),
                "tick",
                value.tick(),
                "fractionBits",
                Float.floatToRawIntBits(value.fraction()),
                "bones",
                bones,
                "corners",
                corners);
    }

    public record CrawlSample(int[] bones, boolean removed) {}

    @FunctionalInterface
    public interface CrawlSampler {
        CrawlSample sample(ParCoolFrame frame, AnimationState.Stance stance, float fraction);
    }

    /** The same nonzero baseline on both sides catches rotation replacement vs additive offsets. */
    public static float[][] crawlBase() {
        var base = new float[6][6];
        for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) base[i][j] = (i * 6 + j + 1) / 32f;
        return base;
    }

    /** Actual native/client and evaluator/server samples, compared bitwise by the report gate. */
    public static List<Map<String, Object>> crawlLayers(CrawlSampler sampler) {
        var result = new ArrayList<Map<String, Object>>();
        for (var scenario : crawlScenarios()) {
            run(scenario);
            var stance = new StanceTransition();
            var box = stance.apply(STANDING, STANDING, true, false, false);
            int tick = 0;
            int exitTick = -1;
            for (var step : scenario.ticks()) {
                if (!step.prone() && exitTick < 0) exitTick = tick;
                int notDoingTick = step.prone() ? 0 : tick - exitTick;
                if (step.target() == null) box = stance.hold(box);
                else box = stance.apply(box, step.target(), true, step.prone(), step.crouching());
                var wire = roundTrip(recordOf(stance.snapshot()));
                var frame = ParCoolPresentationCases.frame(new Crawl(), tick, false);
                var pc = frame.state();
                int actionTick = tick;
                var actions =
                        pc.actions().stream()
                                .map(
                                        row ->
                                                row.action() == ActionTag.CRAWL
                                                        ? new AnimationState.ActionState(
                                                                row.action(),
                                                                step.prone(),
                                                                step.prone() ? actionTick : 0,
                                                                notDoingTick,
                                                                actionTick,
                                                                row.inputs())
                                                        : row)
                                .toList();
                frame =
                        new ParCoolFrame(
                                frame.graph(),
                                new AnimationState.ParCool(
                                        pc.animator(),
                                        pc.passiveStart(),
                                        pc.option(),
                                        pc.passive(),
                                        actions,
                                        pc.hangGrid(),
                                        pc.diveGrid()));
                var sample = sampler.sample(frame, wire, 0);
                boolean retain = !step.prone() && wire.flat() > 0;
                assertEquals(
                        !step.prone() && !retain,
                        sample.removed(),
                        scenario.name() + " tick " + tick + ": removal follows flat exit");
                var zero = sampler.sample(frame, flat(wire, 0), 0).bones();
                var full = sampler.sample(frame, flat(wire, 1), 0).bones();
                for (int i = 0; i < sample.bones().length; i++) {
                    float before = Float.intBitsToFloat(zero[i]);
                    float after = Float.intBitsToFloat(full[i]);
                    int expected =
                            Float.floatToRawIntBits(StanceLayers.blend(before, after, wire.flat()));
                    assertEquals(
                            expected,
                            sample.bones()[i],
                            scenario.name() + " tick " + tick + " scalar " + i);
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("scenario", scenario.name());
                row.put("tick", tick++);
                row.put("flatBits", Float.floatToRawIntBits(wire.flat()));
                row.put("removed", sample.removed());
                row.put("bones", java.util.Arrays.stream(sample.bones()).boxed().toList());
                result.add(row);
                stance.tick();
            }
        }
        return result;
    }

    /** Actual Crawl limb samples at every rendered frame, including interrupted and held exits. */
    public static List<Map<String, Object>> crawlFrames(CrawlSampler sampler) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var scenario : crawlScenarios()) {
            for (int fps : new int[] {30, 60, 144, 240}) {
                var stance = new StanceTransition();
                var box = stance.apply(null, STANDING, true, false, false);
                int frameIndex = 0;
                for (int tick = 0; tick < scenario.ticks().size(); tick++) {
                    var step = scenario.ticks().get(tick);
                    box =
                            step.target() == null
                                    ? stance.hold(box)
                                    : stance.apply(
                                            box,
                                            step.target(),
                                            true,
                                            step.prone(),
                                            step.crouching());
                    var wire = roundTrip(recordOf(stance.snapshot()));
                    var frame = ParCoolPresentationCases.frame(new Crawl(), tick, false);
                    while (frameIndex * 20d / fps < tick + 1) {
                        float partial = (float) (frameIndex * 20d / fps - tick);
                        float next =
                                wire.age() >= StanceTransition.TICKS
                                        ? wire.flat()
                                        : net.minecraft.util.Mth.lerp(
                                                Math.min(wire.age() + 1, StanceTransition.TICKS)
                                                        / (float) StanceTransition.TICKS,
                                                wire.fromFlat(),
                                                wire.toFlat());
                        float coefficient = net.minecraft.util.Mth.lerp(partial, wire.flat(), next);
                        var actual = sampler.sample(frame, wire, partial).bones();
                        var zero = sampler.sample(frame, flat(wire, 0), partial).bones();
                        var full = sampler.sample(frame, flat(wire, 1), partial).bones();
                        for (int i = 0; i < actual.length; i++) {
                            float expected =
                                    StanceLayers.blend(
                                            Float.intBitsToFloat(zero[i]),
                                            Float.intBitsToFloat(full[i]),
                                            coefficient);
                            assertEquals(
                                    Float.floatToRawIntBits(expected),
                                    actual[i],
                                    scenario.name()
                                            + " FPS "
                                            + fps
                                            + " tick "
                                            + tick
                                            + " partial "
                                            + partial
                                            + " scalar "
                                            + i);
                        }
                        rows.add(
                                Map.of(
                                        "scenario",
                                        scenario.name(),
                                        "fps",
                                        fps,
                                        "tick",
                                        tick,
                                        "partial",
                                        partial,
                                        "flat",
                                        coefficient,
                                        "bones",
                                        java.util.Arrays.stream(actual).boxed().toList()));
                        frameIndex++;
                    }
                    stance.tick();
                }
            }
        }
        return rows;
    }

    private static AnimationState.Stance flat(AnimationState.Stance s, float value) {
        return new AnimationState.Stance(
                StanceTransition.TICKS,
                s.eased(),
                value,
                value,
                value,
                s.fromCrouch(),
                s.toCrouch(),
                s.crouch(),
                s.from(),
                s.to(),
                s.current());
    }

    public interface PaTimeline {
        void select(String upper, String lower, StanceTransition.Snapshot stance) throws Exception;

        void tick();

        int[] sample(float fraction);
    }

    @FunctionalInterface
    public interface PaFactory {
        PaTimeline create(boolean lr) throws Exception;
    }

    public static Map<String, KeyframeAnimation> gunClips() throws Exception {
        String path =
                "/assets/tacz/custom/tacz_default_gun/assets/tacz/player_animator/"
                        + "rifle_default.player_animation.json";
        var result = new LinkedHashMap<String, KeyframeAnimation>();
        try (var input = StanceEaseCases.class.getResourceAsStream(path)) {
            assertNotNull(input, "pinned held-gun stance clips");
            var json =
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var clip : GeckoLibSerializer.deserialize(json))
                result.put((String) clip.extraData.get("name"), clip);
        }
        return result;
    }

    /** One persistent native/canonical graph, sampled at three render fractions on every tick. */
    public static List<Map<String, Object>> paLayers(boolean lr, PaFactory factory)
            throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        var scripts =
                lr
                        ? List.of(scenarios().get(0), scenarios().get(1), sneakHeldExit())
                        : List.of(crawlScenarios().getFirst(), scenarios().get(3));
        for (var script : scripts) {
            var stance = new StanceTransition();
            var box = stance.apply(STANDING, STANDING, true, false, false);
            var timeline = factory.create(lr);
            timeline.select("hold_upper", "hold_lower", stance.snapshot());
            for (int warmup = 0; warmup < 9; warmup++) timeline.tick();
            int tick = 0;
            for (var step : script.ticks()) {
                timeline.tick();
                if (step.target() == null) box = stance.hold(box);
                else box = stance.apply(box, step.target(), true, step.prone(), step.crouching());
                var snap =
                        StanceTransition.snapshotOf(
                                roundTrip(recordOf(stance.snapshot())),
                                stance.snapshot().from(),
                                stance.snapshot().to());
                String upper =
                        lr && step.crouching()
                                ? "crouch_walk_upper"
                                : !lr && step.prone() ? "lie" : "hold_upper";
                String lower =
                        !lr && step.prone()
                                ? null
                                : step.crouching() ? "crouch_lower" : "hold_lower";
                timeline.select(upper, lower, snap);
                for (float fraction : new float[] {0, .5f, 65535 / 65536f}) {
                    var bits = timeline.sample(fraction);
                    assertArrayEquals(bits, timeline.sample(fraction), "repeat sampling is inert");
                    result.add(
                            Map.of(
                                    "scenario",
                                    script.name(),
                                    "tick",
                                    tick,
                                    "fractionBits",
                                    Float.floatToRawIntBits(fraction),
                                    "bones",
                                    java.util.Arrays.stream(bits).boxed().toList()));
                }
                tick++;
                stance.tick();
            }
        }
        return result;
    }

    public static PaTimeline canonicalPa(boolean lr) throws Exception {
        var clips = gunClips();
        var catalog = new PaGraph.Catalog(1);
        var policy = PaGraphNativeCases.policy(PhaseDomain.A);
        var stack =
                PaGraph.Stack.create(
                        catalog, policy, PaGraphNativeCases.SOURCES, LayerTag.PA_96, 1);
        var sequence = new AtomicLong();
        var assets = new LrAssets(catalog);
        for (boolean crouch : new boolean[] {false, true}) {
            String upper = crouch ? "crouch_walk_upper" : "hold_upper";
            String lower = crouch ? "crouch_lower" : "hold_lower";
            assets.register(
                    "fixture:stance-" + crouch,
                    true,
                    "fixture:stance",
                    Map.of("idle", List.of(upper), "fall", List.of(upper)),
                    Map.of("idle", List.of(lower), "fall", List.of(lower)),
                    clips);
        }
        return new PaTimeline() {
            private long tick;
            private PaProjection.Result observer =
                    new PaProjection.Result(stack.freeze(AnimationGraphState.neutral()), List.of());

            public void select(String upper, String lower, StanceTransition.Snapshot stance) {
                var up = lr ? LayerTag.PA_40 : LayerTag.PA_94;
                var down = lr ? LayerTag.PA_41 : LayerTag.PA_93;
                if (lr) {
                    var display = assets.display("fixture:stance-" + lower.equals("crouch_lower"));
                    var state =
                            new AnimationState.Lr(
                                    display.config().ref(),
                                    display.ref(),
                                    null,
                                    null,
                                    null,
                                    0,
                                    null,
                                    false,
                                    false,
                                    LrPose.ROTATION_FADE,
                                    false,
                                    false,
                                    List.of());
                    var vanilla =
                            PaObserverNativeCases.vanilla(
                                    AnimationTypes.Pose.STANDING, false, false);
                    var placement = LrSelectorNativeCases.placement(0);
                    var input =
                            new AnimationResources.InputRef(
                                    1,
                                    0,
                                    tick,
                                    AnimationResources.Digest.hash(
                                            AnimationSchema.encode(placement)));
                    for (var tag : List.of(up, down)) {
                        long event = sequence.incrementAndGet();
                        var step =
                                LrPose.select(
                                        stack, display, false, "idle", tag, () -> event, stance);
                        var patch =
                                new AnimationEvent.Patch(
                                        tag,
                                        step.kind(),
                                        step.outcome(),
                                        AnimationGraphState.neutral(),
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        state,
                                        null,
                                        null,
                                        vanilla,
                                        recordOf(stance));
                        var decision =
                                new AnimationEvent.Decision(
                                        new AnimationEvent.EventId(
                                                tick,
                                                event,
                                                AnimationTypes.NativeStage.POST_NATIVE_TICK),
                                        tag,
                                        step.kind(),
                                        step.outcome(),
                                        0,
                                        null,
                                        input,
                                        patch,
                                        tag == up ? 0 : 1);
                        observer =
                                LrProjection.decision(
                                        observer,
                                        decision,
                                        placement,
                                        assets,
                                        policy,
                                        PaGraphNativeCases.SOURCES);
                    }
                } else {
                    stack.loop(up, clips.get(upper), sequence::incrementAndGet);
                    if (lower != null)
                        stack.loop(down, clips.get(lower), sequence::incrementAndGet);
                }
            }

            public void tick() {
                stack.tick();
                tick++;
                if (lr)
                    observer =
                            PaProjection.tick(
                                    observer,
                                    PhaseDomain.A,
                                    catalog,
                                    policy,
                                    PaGraphNativeCases.SOURCES);
            }

            public int[] sample(float fraction) {
                var graph = stack.freeze(AnimationGraphState.neutral());
                var scratch =
                        PaGraph.Stack.materialize(
                                graph, catalog, policy, PaGraphNativeCases.SOURCES);
                scratch.sample(fraction);
                var bits = paBits((part, type, base) -> scratch.transform(part, type, base));
                if (lr) {
                    var replay =
                            PaGraph.Stack.materialize(
                                    observer.graph(), catalog, policy, PaGraphNativeCases.SOURCES);
                    replay.sample(fraction);
                    assertArrayEquals(
                            bits,
                            paBits((part, type, base) -> replay.transform(part, type, base)),
                            "stance-bearing LR decisions replay exactly");
                }
                return bits;
            }
        };
    }

    @FunctionalInterface
    public interface PaTransform {
        Vec3f get(String part, TransformType type, Vec3f base);
    }

    public static int[] paBits(PaTransform transform) {
        var bits = new int[36];
        int k = 0;
        for (String part : List.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg"))
            for (var type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
                var value = transform.get(part, type, new Vec3f(0, 0, 0));
                for (float scalar : new float[] {value.getX(), value.getY(), value.getZ()})
                    bits[k++] = Float.floatToRawIntBits(scalar);
            }
        return bits;
    }

    private static final AnimationResources.AttachmentRef ATTACHMENTS =
            new AnimationResources.AttachmentRef(
                    0, 0, 1, AnimationResources.Digest.hash(new byte[] {7}));

    static AnimationState.DimensionsValue dimensions(EntityDimensions value) {
        return new AnimationState.DimensionsValue(
                value.width(), value.height(), value.eyeHeight(), value.fixed(), ATTACHMENTS);
    }

    /** The stance record for a snapshot, exactly as the server capture builds it. */
    public static AnimationState.Stance recordOf(StanceTransition.Snapshot snap) {
        EntityDimensions from = snap.from() == null ? STANDING : snap.from();
        EntityDimensions to = snap.to() == null ? STANDING : snap.to();
        return new AnimationState.Stance(
                snap.age(),
                snap.eased(),
                snap.fromFlat(),
                snap.toFlat(),
                snap.flat(),
                snap.fromCrouch(),
                snap.toCrouch(),
                snap.crouch(),
                dimensions(from),
                dimensions(to),
                dimensions(to));
    }

    private static AnimationState.Stance roundTrip(AnimationState.Stance record) {
        byte[] bytes = AnimationSchema.encode(record);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            AnimationState.Stance decoded =
                    AnimationSchema.readComplete(buf, AnimationState.Stance.class);
            assertArrayEquals(
                    bytes,
                    AnimationSchema.encode(decoded),
                    "the wire must preserve every stance bit");
            return decoded;
        } finally {
            buf.release();
        }
    }

    /**
     * Runs one scenario; returns one raw-bit row per tick. Fails on the first tick where the driven
     * mirror's box or crouch coefficient differs from the server's in any bit.
     */
    public static List<Map<String, Object>> run(Scenario scenario) {
        var rows = new ArrayList<Map<String, Object>>();
        var server = new StanceTransition();
        EntityDimensions box = server.apply(STANDING, STANDING, true, false, false);
        var mirror = new StanceTransition();
        mirror.setDriven(true);
        int tick = 0;
        for (var step : scenario.ticks()) {
            if (step.target() == null) box = server.hold(box);
            else box = server.apply(box, step.target(), true, step.prone(), step.crouching());
            var snap = server.snapshot();
            AnimationState.Stance decoded = roundTrip(recordOf(snap));
            EntityDimensions from = snap.from() == null ? STANDING : snap.from();
            EntityDimensions to = snap.to() == null ? STANDING : snap.to();
            mirror.restore(StanceTransition.snapshotOf(decoded, from, to));
            EntityDimensions remote =
                    step.target() == null
                            ? mirror.hold(box)
                            : mirror.apply(box, step.target(), true, step.prone(), true);
            assertEquals(
                    Float.floatToRawIntBits(box.width()),
                    Float.floatToRawIntBits(remote.width()),
                    scenario.name() + " tick " + tick + ": remote box width");
            assertEquals(
                    Float.floatToRawIntBits(box.height()),
                    Float.floatToRawIntBits(remote.height()),
                    scenario.name() + " tick " + tick + ": remote box height");
            assertEquals(
                    Float.floatToRawIntBits(box.eyeHeight()),
                    Float.floatToRawIntBits(remote.eyeHeight()),
                    scenario.name() + " tick " + tick + ": remote eye height");
            assertEquals(
                    Float.floatToRawIntBits(server.crouchness()),
                    Float.floatToRawIntBits(mirror.crouchness()),
                    scenario.name() + " tick " + tick + ": remote crouch coefficient");
            assertEquals(
                    Float.floatToRawIntBits(server.flatness()),
                    Float.floatToRawIntBits(mirror.flatness()),
                    scenario.name() + " tick " + tick + ": remote flat coefficient");
            assertEquals(snap, mirror.snapshot(), scenario.name() + " tick " + tick);
            var row = new LinkedHashMap<String, Object>();
            row.put("tick", tick);
            row.put("held", step.target() == null);
            row.put("crouchBits", Float.floatToRawIntBits(server.crouchness()));
            row.put("flatBits", Float.floatToRawIntBits(server.flatness()));
            row.put("heightBits", Float.floatToRawIntBits(box.height()));
            row.put("eyeBits", Float.floatToRawIntBits(box.eyeHeight()));
            row.put("wireBytes", AnimationSchema.encode(decoded).length);
            rows.add(row);
            server.tick();
            tick++;
        }
        // Every script ends settled on its final pose.
        assertFalse(server.changing(), scenario.name() + ": script must end settled");
        return rows;
    }
}
