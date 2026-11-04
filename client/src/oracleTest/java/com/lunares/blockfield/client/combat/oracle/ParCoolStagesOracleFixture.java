package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.alrex.parcool.client.animation.PassiveCustomAnimation;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.attachment.client.Animation;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationEvent.Decision;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.ParCoolStagesCases;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Production ParCool reducer against pinned bf7: every removal predicate, the five packetless
 * selections, counters, passive, animator ticks, and the Dive topology A/B with native renders.
 */
class ParCoolStagesOracleFixture {
    private static final ParCoolGraph NATIVE =
            new ParCoolGraph(
                    ParCoolStagesOracleFixture.class.getClassLoader(),
                    "com.alrex.parcool.client.animation.impl.");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void productionStagesReplayCanonicalAndObserverTimelines() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-stage-replay",
                () -> {
                    var result = ParCoolStagesCases.run();
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return result;
                });
    }

    @Test
    void removalSelectionCountersPassiveAndTicksMatchNative() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-stage-native-primitives",
                () -> {
                    boolean previous =
                            com.lunares.blockfield.client.combat.AnimationPolicy.forcing();
                    {
                        com.lunares.blockfield.client.combat.AnimationPolicy.arm(
                                com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                        com.lunares.blockfield.client.combat.AnimationPolicy.install();
                    }
                    try (var keys = mockStatic(KeyBindings.class);
                            var attachment = mockStatic(Parkourability.class);
                            var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        var player = ParCoolCheckpointOracleFixture.player();
                        var result = new LinkedHashMap<String, Object>();
                        result.put("removalCases", removal(player));
                        result.put("selectionCases", selection(player));
                        result.put("counterCases", counters());
                        result.put("passiveSteps", passive(player, keys));
                        result.put("animatorTicks", ticks(player, keys, attachment));
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        result.put(
                                "scope",
                                "native bf7 shouldRemoved for 26 registered animators, five"
                                        + " hasAnimator-gated selections, Action.tick,"
                                        + " PassiveCustomAnimation.tick and Kong/zipline/Dive host"
                                        + " ticks versus ParCoolStages");
                        return result;
                    } finally {
                        if (previous) {
                            com.lunares.blockfield.client.combat.AnimationPolicy.arm(
                                    com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                            com.lunares.blockfield.client.combat.AnimationPolicy.install();
                        } else com.lunares.blockfield.client.combat.AnimationPolicy.restore();
                    }
                });
    }

    @Test
    void diveTopologyResolvesAtBoundariesWithNativeRenderTopology() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-dive-topology-ab",
                () -> {
                    try (var keys = mockStatic(KeyBindings.class);
                            var attachment = mockStatic(Parkourability.class);
                            var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        var result = dive(ParCoolCheckpointOracleFixture.player(), attachment);
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return result;
                    }
                });
    }

    @Test
    void driveDiveLegUsesRetainedGridIndependentOfRenderHistory() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-dive-leg-fps",
                () -> {
                    try (var keys = mockStatic(KeyBindings.class);
                            var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        // Parkourability.get stays real: the production scope mixin redirects it.
                        var result = diveResult();
                        var player = mock(net.minecraft.client.player.AbstractClientPlayer.class);
                        when(player.getPose()).thenReturn(net.minecraft.world.entity.Pose.STANDING);
                        when(player.getAttachedOrCreate(
                                        com.alrex.parcool.common.attachment.Attachments
                                                .PARKOURABILITY))
                                .thenReturn(new Parkourability());
                        when(player.getAttachedOrCreate(
                                        com.alrex.parcool.common.attachment.ClientAttachments
                                                .ANIMATION))
                                .thenReturn(new Animation());
                        var host =
                                (DiveHost) ((Animator) root(result.frame().graph()).data()).state();
                        int age = child(result.frame().graph(), host.diveAnimator()).tick();
                        var grid = result.frame().state().diveGrid();
                        byte[] retained = AnimationSchema.encode(result.frame().state());
                        var rows = new ArrayList<Map<String, Object>>();
                        boolean replaced = false;
                        for (int alpha : new int[] {0, 1, 16384, 21845, 32768, 43690, 65535}) {
                            int[] reference = null;
                            for (int fps : new int[] {0, 30, 60, 144, 240}) {
                                // Prior frames inside the bucket at this FPS, then the sample.
                                for (int frame = 0; frame < fps / 20; frame++)
                                    leg(player, result, frame * 65536 / Math.max(1, fps / 20));
                                int[] bits = leg(player, result, alpha);
                                if (reference == null) reference = bits;
                                assertArrayEquals(reference, bits, "history " + fps);
                            }
                            float expected =
                                    (float)
                                            Math.toRadians(
                                                    ParCoolReducers.diveLegDegrees(
                                                            grid, new Time(age, alpha)));
                            assertEquals(Float.floatToRawIntBits(expected), reference[0]);
                            assertEquals(Float.floatToRawIntBits(expected), reference[1]);
                            int[] nativeBits = nativeLeg(player, result, alpha);
                            replaced |= !Arrays.equals(nativeBits, reference);
                            rows.add(
                                    Map.of(
                                            "alpha",
                                            alpha,
                                            "deterministicLegBits",
                                            reference[0],
                                            "nativeLastRenderLegBits",
                                            nativeBits[0]));
                        }
                        assertTrue(replaced, "the driven leg term must replace the native one");
                        assertArrayEquals(retained, AnimationSchema.encode(result.frame().state()));
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "childAge",
                                age,
                                "samples",
                                rows,
                                "histories",
                                List.of(0, 30, 60, 144, 240),
                                "retainedStateUnchanged",
                                true);
                    }
                });
    }

    @Test
    void fastRunAndWallRunRootsReadTheCurrentRLimbInput() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-limb-current-r",
                () -> {
                    try (var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        var player = mock(net.minecraft.client.player.AbstractClientPlayer.class);
                        when(player.getPose()).thenReturn(net.minecraft.world.entity.Pose.STANDING);
                        when(player.isAlive()).thenReturn(true);
                        when(player.getLookAngle())
                                .thenReturn(new net.minecraft.world.phys.Vec3(.3, -.2, .9));
                        when(player.getYRot()).thenReturn(23f);
                        when(player.getMainArm())
                                .thenReturn(net.minecraft.world.entity.HumanoidArm.RIGHT);
                        when(player.getAttachedOrCreate(
                                        com.alrex.parcool.common.attachment.Attachments
                                                .PARKOURABILITY))
                                .thenReturn(new Parkourability());
                        when(player.getAttachedOrCreate(
                                        com.alrex.parcool.common.attachment.ClientAttachments
                                                .ANIMATION))
                                .thenReturn(new Animation());
                        var walk = new net.minecraft.world.entity.WalkAnimationState();
                        var walkField =
                                OracleReflection.field(
                                        LivingEntity.class,
                                        "walkAnimation",
                                        "Lnet/minecraft/world/entity/WalkAnimationState;");
                        walkField.setAccessible(true);
                        walkField.set(player, walk);
                        var cases = new ArrayList<Map<String, Object>>();
                        for (Object nativeActor :
                                new Object[] {
                                    new com.alrex.parcool.client.animation.impl
                                            .FastRunningAnimator(),
                                    new com.alrex.parcool.client.animation.impl
                                            .HorizontalWallRunAnimator(true)
                                }) {
                            var rows = rows(true, 0, 0);
                            var graph = withTick(typed(nativeActor), 4);
                            var frame = initial(graph, rows).frame();
                            int[] reference = null;
                            for (float position : new float[] {3.25f, 7.5f}) {
                                walk.update(position, 1);
                                for (int alphaR : new int[] {0, 21845, 65535}) {
                                    float limb =
                                            com.lunares.blockfield.client.animation
                                                    .RemoteParCoolPresentation.limb(
                                                    player, alphaR / 65536f);
                                    int[] driven = root(player, frame, alphaR);
                                    var expected =
                                            (com.alrex.parcool.client.animation.Animator)
                                                    NATIVE.materialize(
                                                            frame.graph(),
                                                            frame.state().animator());
                                    parcool(expected, "limbSwing").setFloat(expected, limb);
                                    var stack = new com.mojang.blaze3d.vertex.PoseStack();
                                    expected.rotatePost(
                                            player,
                                            frame.scratchAttachment(),
                                            new com.alrex.parcool.client.animation
                                                    .PlayerModelRotator(stack, player, .25f, 23));
                                    int[] bits = bits(stack);
                                    assertArrayEquals(bits, driven, "current R limb root");
                                    if (reference != null)
                                        assertFalse(Arrays.equals(reference, driven));
                                    reference = driven;
                                    cases.add(
                                            Map.of(
                                                    "animator",
                                                    nativeActor.getClass().getSimpleName(),
                                                    "limbBits",
                                                    Float.floatToRawIntBits(limb),
                                                    "alphaR",
                                                    alphaR));
                                }
                            }
                        }
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of("cases", cases);
                    }
                });
    }

    private static int[] root(
            net.minecraft.client.player.AbstractClientPlayer player,
            ParCoolFrame frame,
            int alphaR) {
        var presentation = com.lunares.blockfield.client.animation.RemoteParCoolPresentation.class;
        com.lunares.blockfield.client.animation.RemoteParCoolPresentation.install(
                player,
                new com.lunares.blockfield.client.animation.RemoteParCoolPresentation.Frame(
                        frame, ParCoolStagesCases.POLICY, .25f, alphaR / 65536f));
        try (var scope =
                com.lunares.blockfield.client.animation.RemoteParCoolPresentation.scope(player)) {
            var stack = new com.mojang.blaze3d.vertex.PoseStack();
            com.lunares.blockfield.client.animation.RemoteParCoolPresentation.animation(player)
                    .rotatePost(
                            player,
                            new com.alrex.parcool.client.animation.PlayerModelRotator(
                                    stack, player, .25f, 23));
            return bits(stack);
        } finally {
            com.lunares.blockfield.client.animation.RemoteParCoolPresentation.clear(player);
        }
    }

    private static int[] bits(com.mojang.blaze3d.vertex.PoseStack stack) {
        float[] values = stack.last().pose().get(new float[16]);
        int[] bits = new int[16];
        for (int i = 0; i < 16; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return bits;
    }

    @Test
    void zeroFractionSamplesAreFiniteAndRightContinuous() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-zero-fraction-limits",
                () -> {
                    try (var keys = mockStatic(KeyBindings.class);
                            var attachment = mockStatic(Parkourability.class);
                            var singleton = mockStatic(Minecraft.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        var player = ParCoolCheckpointOracleFixture.player();
                        var parkour = parkour(rows(true, 0, 3));
                        attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
                        var rows = new ArrayList<Map<String, Object>>();
                        int cases = 0;
                        for (AnimatorTag tag : AnimationPolicy.registered())
                            for (Object constructor :
                                    ParCoolCheckpointOracleFixture.constructors(NATIVE.type(tag)))
                                for (int age : new int[] {0, 1, 5, 11}) {
                                    var graph = withTick(typed(constructor), age);
                                    float[] delta = new float[2];
                                    int[] zero = sample(graph, player, parkour, 0);
                                    float[] fractions = {0x1p-16f, 0x1p-24f};
                                    for (int i = 0; i < 2; i++) {
                                        int[] near = sample(graph, player, parkour, fractions[i]);
                                        for (int j = 0; j < zero.length; j++)
                                            delta[i] =
                                                    Math.max(
                                                            delta[i],
                                                            Math.abs(
                                                                    Float.intBitsToFloat(near[j])
                                                                            - Float.intBitsToFloat(
                                                                                    zero[j])));
                                    }
                                    assertTrue(
                                            delta[1] <= delta[0] + 1e-6f,
                                            tag + " age " + age + " not right-continuous");
                                    cases++;
                                    if (age == 0)
                                        rows.add(
                                                Map.of(
                                                        "tag",
                                                        tag.name(),
                                                        "maxDelta2m16",
                                                        delta[0],
                                                        "maxDelta2m24",
                                                        delta[1]));
                                }
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "cases",
                                cases,
                                "ageZero",
                                rows,
                                "result",
                                "every registered animator is finite at fraction 0 and converges"
                                        + " from the right; no ParCool limit substitution is needed");
                    }
                });
    }

    private static int[] sample(
            AnimationGraphState graph, Player player, Parkourability parkour, float fraction) {
        var actor =
                (com.alrex.parcool.client.animation.Animator)
                        NATIVE.materialize(graph, new NodeRef(root(graph).id()));
        return ParCoolCheckpointOracleFixture.sample(
                actor, player, parkour, fraction, false, 12.5f);
    }

    private static ParCoolProjection.Result diveResult() throws Exception {
        var base = ParCoolStagesCases.baseState();
        var host =
                new com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator(-.4, false);
        var rows = rows(false, 3, 0);
        var result = initial(typed(host), rows);
        double yOld = -.4;
        var samples = new HashMap<AnimationResources.InputRef, AnimationState>();
        for (long n = 1; n <= 13; n++) {
            double yNow = .3 - .05 * n;
            rows.put(ActionTag.DIVE, diveRow(rows.get(ActionTag.DIVE), true, yOld, yNow));
            yOld = yNow;
            samples.put(
                    ParCoolStagesCases.ref(n),
                    ParCoolStagesCases.state(
                            base, n, rows, new Angles(0, 0, 0, 0, 0, 0, 0, 0), false, true, 0));
            result =
                    new ParCoolStages(
                                    ParCoolStages.Mode.CANONICAL,
                                    samples,
                                    Map.of(),
                                    ParCoolStagesCases.POLICY)
                            .apply(
                                    result,
                                    n,
                                    NativeStage.NATIVE_TICK,
                                    List.of(
                                            ParCoolStagesCases.boundary(
                                                    n, 100 + n, ParCoolStagesCases.ref(n))));
        }
        return result;
    }

    /** Right and left leg xRot bits from one driven invocation scope at A fraction alpha. */
    private static int[] leg(
            net.minecraft.client.player.AbstractClientPlayer player,
            ParCoolProjection.Result result,
            int alpha) {
        com.lunares.blockfield.client.animation.RemoteParCoolPresentation.install(
                player,
                new com.lunares.blockfield.client.animation.RemoteParCoolPresentation.Frame(
                        result.frame(), ParCoolStagesCases.POLICY, alpha / 65536f, 0));
        try (var scope =
                com.lunares.blockfield.client.animation.RemoteParCoolPresentation.scope(player)) {
            var animation =
                    com.lunares.blockfield.client.animation.RemoteParCoolPresentation.animation(
                            player);
            var model = model();
            var transformer =
                    new com.alrex.parcool.client.animation.PlayerModelTransformer(
                            player, model, false, 12.5f, 7.25f, .4f, 13, -17);
            animation.animatePost(player, transformer);
            return new int[] {
                Float.floatToRawIntBits(model.rightLeg.xRot),
                Float.floatToRawIntBits(model.leftLeg.xRot)
            };
        } finally {
            com.lunares.blockfield.client.animation.RemoteParCoolPresentation.clear(player);
        }
    }

    /** The same materialized scratch outside a driven scope: bf7's last-render leg term. */
    private static int[] nativeLeg(Player player, ParCoolProjection.Result result, int alpha) {
        var actor =
                (com.alrex.parcool.client.animation.Animator)
                        NATIVE.materialize(
                                result.frame().graph(), result.frame().state().animator());
        var model = model();
        var transformer =
                new com.alrex.parcool.client.animation.PlayerModelTransformer(
                        player, model, false, 12.5f, 7.25f, .4f, 13, -17) {
                    @Override
                    public float getPartialTick() {
                        return alpha / 65536f;
                    }
                };
        actor.animatePost(player, result.frame().scratchAttachment(), transformer);
        return new int[] {
            Float.floatToRawIntBits(model.rightLeg.xRot),
            Float.floatToRawIntBits(model.leftLeg.xRot)
        };
    }

    private static net.minecraft.client.model.PlayerModel<Player> model() {
        return new net.minecraft.client.model.PlayerModel<>(
                net.minecraft.client.model.geom.builders.LayerDefinition.create(
                                net.minecraft.client.model.PlayerModel.createMesh(
                                        net.minecraft.client.model.geom.builders.CubeDeformation
                                                .NONE,
                                        false),
                                64,
                                64)
                        .bakeRoot(),
                false);
    }

    static EnumMap<ActionTag, ActionState> rows(boolean doing, int notDoing, int charge) {
        var rows = new EnumMap<ActionTag, ActionState>(ActionTag.class);
        for (ActionState row : ParCoolStagesCases.neutral().actions()) {
            var value =
                    new ActionState(row.action(), doing, doing ? 3 : 0, notDoing, 2, row.inputs());
            if (row.action() == ActionTag.CHARGE_JUMP)
                value =
                        ParCoolStagesCases.with(
                                value, new ChargeInput(charge, 0, 0, false, 0, true));
            rows.put(row.action(), value);
        }
        return rows;
    }

    static Parkourability parkour(Map<ActionTag, ActionState> rows) {
        var neutral = ParCoolStagesCases.neutral();
        return new ParCoolFrame(
                        AnimationGraphState.neutral(),
                        new ParCool(
                                null,
                                neutral.passiveStart(),
                                neutral.option(),
                                neutral.passive(),
                                List.copyOf(rows.values()),
                                neutral.hangGrid(),
                                neutral.diveGrid()))
                .scratchAttachment();
    }

    private static AnimationGraphState typed(Object nativeAnimator) {
        var keys = new AtomicInteger(1);
        return NATIVE.freeze(
                AnimationGraphState.neutral(),
                nativeAnimator,
                new AtomicInteger()::incrementAndGet,
                ignored -> new StartKey(7, keys.getAndIncrement()));
    }

    private static Node root(AnimationGraphState graph) {
        int id =
                graph.roots().stream()
                        .filter(r -> r.layer() == LayerTag.PARCOOL_ACTIVE)
                        .findFirst()
                        .orElseThrow()
                        .node()
                        .id();
        return graph.nodes().stream().filter(n -> n.id() == id).findFirst().orElseThrow();
    }

    private static AnimationGraphState withTick(AnimationGraphState graph, int tick) {
        var root = root(graph);
        var actor = (Animator) root.data();
        var nodes = new ArrayList<Node>();
        for (Node node : graph.nodes())
            nodes.add(
                    node.id() == root.id()
                            ? new Node(
                                    node.id(),
                                    node.domain(),
                                    node.start(),
                                    new Animator(actor.tag(), tick, actor.state()))
                            : node);
        return new AnimationGraphState(graph.roots(), nodes, graph.aliases());
    }

    private static int removal(Player player) throws Exception {
        int cases = 0;
        for (AnimatorTag tag : AnimationPolicy.registered())
            for (Object constructor : ParCoolCheckpointOracleFixture.constructors(NATIVE.type(tag)))
                for (boolean doing : new boolean[] {false, true})
                    for (int notDoing : new int[] {0, 4, 5, 6})
                        for (int charge : new int[] {0, 3}) {
                            var rows = rows(doing, notDoing, charge);
                            var parkour = parkour(rows);
                            var graph = typed(constructor);
                            for (int tick = 0; tick <= 24; tick++) {
                                var sampled = withTick(graph, tick);
                                var actor = (Animator) root(sampled).data();
                                var nativeActor =
                                        (com.alrex.parcool.client.animation.Animator)
                                                NATIVE.materialize(
                                                        sampled, new NodeRef(root(sampled).id()));
                                assertEquals(
                                        nativeActor.shouldRemoved(player, parkour),
                                        ParCoolStages.removed(tick, actor.state(), rows),
                                        tag + " tick " + tick + " doing " + doing);
                                cases++;
                            }
                        }
        return cases;
    }

    private static int selection(Player player) throws Exception {
        var candidates =
                List.of(
                        ActionTag.CRAWL,
                        ActionTag.FAST_RUN,
                        ActionTag.FAST_SWIM,
                        ActionTag.WALL_SLIDE);
        int cases = 0;
        for (int mask = 0; mask < 1 << candidates.size(); mask++)
            for (int charge : new int[] {0, 3})
                for (boolean chargeDoing : new boolean[] {false, true}) {
                    var rows = rows(false, 7, charge);
                    for (int i = 0; i < candidates.size(); i++)
                        if ((mask & 1 << i) != 0) {
                            var row = rows.get(candidates.get(i));
                            rows.put(
                                    row.action(),
                                    new ActionState(row.action(), true, 1, 0, 1, row.inputs()));
                        }
                    var charging = rows.get(ActionTag.CHARGE_JUMP);
                    rows.put(
                            ActionTag.CHARGE_JUMP,
                            new ActionState(
                                    charging.action(), chargeDoing, 0, 0, 1, charging.inputs()));
                    var parkour = parkour(rows);
                    var animation = new Animation();
                    doReturn(animation).when(player).getAttachedOrCreate(any());
                    // bf7 registration order: ChargeJump.onClientTick, then working ticks.
                    for (Action action : parkour.getList()) {
                        if (action instanceof com.alrex.parcool.common.action.impl.ChargeJump)
                            action.onClientTick(player, parkour);
                        if (!action.isDoing()) continue;
                        if (action instanceof com.alrex.parcool.common.action.impl.WallSlide)
                            parcool(action, "particleSpawnCoolTime").setByte(action, (byte) 100);
                        if (action instanceof com.alrex.parcool.common.action.impl.Crawl
                                || action instanceof com.alrex.parcool.common.action.impl.FastRun
                                || action instanceof com.alrex.parcool.common.action.impl.FastSwim
                                || action instanceof com.alrex.parcool.common.action.impl.WallSlide)
                            action.onWorkingTickInClient(player, parkour);
                    }
                    var expected = ParCoolStages.selection(rows);
                    var actual = parcool(animation, "animator").get(animation);
                    assertEquals(
                            expected == null ? null : selectionTag(expected),
                            actual == null ? null : NATIVE.tag(actual),
                            "selection mask " + mask + " charge " + charge);
                    cases++;
                }
        return cases;
    }

    private static AnimatorTag selectionTag(AnimatorData data) {
        return switch (data) {
            case JumpCharging ignored -> AnimatorTag.JUMP_CHARGING;
            case Crawl ignored -> AnimatorTag.CRAWL;
            case FastRunning ignored -> AnimatorTag.FAST_RUNNING;
            case FastSwim ignored -> AnimatorTag.FAST_SWIM;
            case WallSlide ignored -> AnimatorTag.WALL_SLIDE;
            default -> throw new AssertionError(data);
        };
    }

    private static int counters() {
        int cases = 0;
        for (ActionState base : ParCoolStagesCases.neutral().actions())
            for (boolean doing : new boolean[] {false, true})
                for (int started : new int[] {-1, 0, 5})
                    for (int ticks : new int[] {0, 1, 9}) {
                        var row =
                                new ActionState(
                                        base.action(),
                                        doing,
                                        ticks,
                                        9 - ticks,
                                        started,
                                        base.inputs());
                        var action = ParCoolActions.materialize(row);
                        action.tick();
                        var next = ParCoolStages.counters(row);
                        assertEquals(action.isDoing(), next.doing());
                        assertEquals(action.getDoingTick(), next.doingTick());
                        assertEquals(action.getNotDoingTick(), next.notDoingTick());
                        assertEquals(action.getTickFromLastStarted(), next.tickFromStarted());
                        cases++;
                    }
        return cases;
    }

    private static int passive(Player player, org.mockito.MockedStatic<KeyBindings> keys)
            throws Exception {
        var nativePassive = new PassiveCustomAnimation();
        var state = new Passive(0, 0, 0);
        var abilities = player.getAbilities();
        int steps = 0;
        for (int i = 0; i < 64; i++) {
            boolean forward = i % 5 < 3;
            boolean ground = i % 23 > 17;
            boolean flying = i >= 30 && i < 45;
            boolean elytra = i % 31 == 7;
            boolean cling = i % 19 == 3;
            float fall = i % 11 == 0 ? 1 : 1.5f;
            keys.when(KeyBindings::isKeyForwardDown).thenReturn(forward);
            when(player.onGround()).thenReturn(ground);
            when(player.isFallFlying()).thenReturn(elytra);
            abilities.flying = flying;
            player.fallDistance = fall;
            var rows = rows(false, 3, 0);
            var clingRow = rows.get(ActionTag.CLING_TO_CLIFF);
            rows.put(
                    ActionTag.CLING_TO_CLIFF,
                    new ActionState(clingRow.action(), cling, 1, 0, 1, clingRow.inputs()));
            nativePassive.tick(player, parkour(rows));
            state =
                    ParCoolReducers.passiveStep(
                            state,
                            new PassiveInput(0, ground, elytra, flying, cling, fall),
                            new IntentInput(forward, false, false, false));
            assertEquals(ParCoolGraph.passive(nativePassive), state, "passive step " + i);
            steps++;
        }
        abilities.flying = false;
        return steps;
    }

    /** One reducer boundary over a single animator, with a sample matching the native inputs. */
    private static ParCoolProjection.Result boundary(
            AnimationGraphState graph,
            Map<ActionTag, ActionState> rows,
            Angles angles,
            AnimationState base) {
        var starts = new ArrayList<Start>();
        for (Node node : graph.nodes())
            if (node.start() != null)
                starts.add(
                        new Start(
                                node.start().eventSequence(),
                                node.start().nodeId(),
                                LayerTag.PARCOOL_ACTIVE,
                                StartKind.NEW,
                                AnchorClass.CANONICAL_TICK,
                                PhaseDomain.A,
                                0,
                                0,
                                0,
                                new CanonicalTick(0, NativeStage.NATIVE_TICK)));
        var neutral = ParCoolStagesCases.neutral();
        var diveGrid = neutral.diveGrid();
        if (((Animator) root(graph).data()).state() instanceof DiveHost host)
            diveGrid =
                    new DiveGrid(
                            new Grid4(
                                    3L * child(graph, host.diveAnimator()).tick(), 0, 0, 0, 0, 0));
        var initial =
                new ParCoolProjection.Result(
                        new ParCoolFrame(
                                graph,
                                new ParCool(
                                        new NodeRef(root(graph).id()),
                                        neutral.passiveStart(),
                                        neutral.option(),
                                        neutral.passive(),
                                        List.copyOf(rows.values()),
                                        neutral.hangGrid(),
                                        diveGrid)),
                        starts);
        var sample = ParCoolStagesCases.state(base, 1, rows, angles, false, true, 0);
        var stages =
                new ParCoolStages(
                        ParCoolStages.Mode.OBSERVER,
                        Map.of(ParCoolStagesCases.ref(1), sample),
                        Map.of(),
                        ParCoolStagesCases.POLICY);
        return stages.apply(
                initial,
                1,
                NativeStage.NATIVE_TICK,
                List.of(ParCoolStagesCases.boundary(1, 9, ParCoolStagesCases.ref(1))));
    }

    private static Map<String, Object> ticks(
            Player player,
            org.mockito.MockedStatic<KeyBindings> keys,
            org.mockito.MockedStatic<Parkourability> attachment)
            throws Exception {
        var base = ParCoolStagesCases.baseState();
        int kong = 0, zipline = 0, dive = 0;
        float[] yaws = {-179.5f, -90, -0f, 0, 17, 89.75f, 90, 179.9f, 180};
        for (float old : yaws)
            for (float now : yaws) {
                for (String name : new String[] {"yBodyRot", "yBodyRotO"}) {
                    var field = OracleReflection.field(LivingEntity.class, name, "F");
                    field.setAccessible(true);
                    field.setFloat(player, name.endsWith("O") ? old : now);
                }
                var rows = rows(true, 0, 0);
                var nativeActor = new com.alrex.parcool.client.animation.impl.KongVaultAnimator();
                var graph = typed(nativeActor);
                nativeActor.tick(player);
                var reduced = boundary(graph, rows, new Angles(0, 0, 0, 0, old, now, 0, 0), base);
                assertEquals(
                        root(typed(nativeActor)).data(),
                        root(reduced.frame().graph()).data(),
                        "Kong " + old + " -> " + now);
                kong++;
            }
        for (boolean doing : new boolean[] {false, true})
            for (double accel : new double[] {-.07, 0, .031})
                for (double slope : new double[] {-.4, -0d, .25}) {
                    var rows = rows(true, 0, 0);
                    var row = rows.get(ActionTag.RIDE_ZIPLINE);
                    var input =
                            new ZiplineInput(new Vec3(1, -.2, .5), accel, slope, .08, true, null);
                    row =
                            ParCoolStagesCases.with(
                                    new ActionState(row.action(), doing, 1, 0, 1, row.inputs()),
                                    input);
                    rows.put(ActionTag.RIDE_ZIPLINE, row);
                    var parkour = parkour(rows);
                    attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
                    var nativeActor =
                            new com.alrex.parcool.client.animation.impl.RideZiplineAnimator();
                    for (int i = 0; i < 3; i++) nativeActor.tick(player);
                    var graph = typed(nativeActor);
                    nativeActor.tick(player);
                    // A stopped zipline is removed by its predicate, so compare the step itself.
                    var expected =
                            (RideZipline) ((Animator) root(typed(nativeActor)).data()).state();
                    assertEquals(
                            expected,
                            ParCoolReducers.ziplineStep(
                                    (RideZipline) ((Animator) root(graph).data()).state(),
                                    input,
                                    doing));
                    if (doing)
                        assertEquals(
                                root(typed(nativeActor)).data(),
                                root(boundary(graph, rows, new Angles(0, 0, 0, 0, 0, 0, 0, 0), base)
                                                .frame()
                                                .graph())
                                        .data());
                    zipline++;
                }
        for (int mask = 0; mask < 16; mask++) {
            boolean f = (mask & 1) != 0,
                    b = (mask & 2) != 0,
                    l = (mask & 4) != 0,
                    r = (mask & 8) != 0;
            keys.when(KeyBindings::isKeyForwardDown).thenReturn(f);
            keys.when(KeyBindings::isKeyBackDown).thenReturn(b);
            keys.when(KeyBindings::isKeyLeftDown).thenReturn(l);
            keys.when(KeyBindings::isKeyRightDown).thenReturn(r);
            var rows = rows(true, 0, 0);
            rows.put(
                    ActionTag.SKY_DIVE,
                    ParCoolStagesCases.with(
                            rows.get(ActionTag.SKY_DIVE), new IntentInput(f, b, l, r)));
            var host =
                    new com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator(
                            -.4, false);
            var sky =
                    new com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator
                            .SkyDiveAnimator(33);
            parcool(host, "skyDiveAnimator").set(host, sky);
            parcool(host, "oldSkyDiveDoing").setBoolean(host, true);
            parcool(host, "transitioning").setBoolean(host, mask % 3 == 0);
            parcool(host, "transitionTick").setInt(host, mask % 6);
            for (int i = 0; i < 12; i++) host.tick(player);
            var graph = typed(host);
            host.tick(player);
            var expected = typed(host);
            var reduced =
                    boundary(graph, rows, new Angles(0, 0, 0, 0, 0, 0, 0, 0), base).frame().graph();
            // Ticks, transition counter and SkyDive counters; the Dive grid/pitch replacements are
            // the separate deterministic outputs, not native render fields.
            var hostState = (DiveHost) ((Animator) root(expected).data()).state();
            var reducedHost = (DiveHost) ((Animator) root(reduced).data()).state();
            assertEquals(
                    ((Animator) root(expected).data()).tick(),
                    ((Animator) root(reduced).data()).tick());
            assertEquals(hostState.transitioning(), reducedHost.transitioning());
            assertEquals(hostState.transitionTick(), reducedHost.transitionTick());
            var nativeSky = (SkyDiveChild) child(expected, hostState.skyDiveAnimator()).state();
            var reducedSky = (SkyDiveChild) child(reduced, reducedHost.skyDiveAnimator()).state();
            assertEquals(nativeSky.forwardAngleCount(), reducedSky.forwardAngleCount());
            assertEquals(nativeSky.forwardAngleCountOld(), reducedSky.forwardAngleCountOld());
            assertEquals(nativeSky.rightAngleCount(), reducedSky.rightAngleCount());
            assertEquals(nativeSky.rightAngleCountOld(), reducedSky.rightAngleCountOld());
            assertEquals(
                    child(expected, hostState.skyDiveAnimator()).tick(),
                    child(reduced, reducedHost.skyDiveAnimator()).tick());
            assertEquals(
                    child(expected, hostState.diveAnimator()).tick(),
                    child(reduced, reducedHost.diveAnimator()).tick());
            dive++;
        }
        return Map.of("kongYawPairs", kong, "ziplineCases", zipline, "diveIntentMasks", dive);
    }

    /** ParCool is not remapped; only vanilla members go through OracleReflection. */
    private static java.lang.reflect.Field parcool(Object owner, String name)
            throws NoSuchFieldException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Animator child(AnimationGraphState graph, NodeRef ref) {
        return graph.nodes().stream()
                .filter(n -> n.id() == ref.id())
                .map(n -> (Animator) n.data())
                .findFirst()
                .orElseThrow();
    }

    /**
     * Native host with one render (fraction 0) after each tick versus integer-boundary topology:
     * transition flags, counters and child presence must agree; start pitch is the A/B item.
     */
    private static Map<String, Object> dive(
            Player player, org.mockito.MockedStatic<Parkourability> attachment) throws Exception {
        var base = ParCoolStagesCases.baseState();
        var trace = new ArrayList<Map<String, Object>>();
        for (boolean fromInAir : new boolean[] {false, true}) {
            var nativeHost =
                    new com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator(
                            -.4, fromInAir);
            var rows = rows(false, 3, 0);
            rows.put(ActionTag.DIVE, diveRow(rows.get(ActionTag.DIVE), true, -.4, -.4));
            var initialGraph = typed(nativeHost);
            var samples = new HashMap<AnimationResources.InputRef, AnimationState>();
            var journal = new HashMap<Long, List<Decision>>();
            var result = initial(initialGraph, rows);
            double yOld = -.4;
            for (long n = 1; n <= 30; n++) {
                double yNow = .3 - .03 * n;
                boolean sky = n >= 4 && n <= 9 || n >= 14 && n <= 15 || n >= 24;
                rows.put(ActionTag.DIVE, diveRow(rows.get(ActionTag.DIVE), true, yOld, yNow));
                rows.put(ActionTag.SKY_DIVE, doing(rows.get(ActionTag.SKY_DIVE), sky));
                yOld = yNow;
                var parkour = parkour(rows);
                attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
                nativeHost.tick(player);
                ParCoolCheckpointOracleFixture.sample(nativeHost, player, parkour, 0, false, n);
                samples.put(
                        ParCoolStagesCases.ref(n),
                        ParCoolStagesCases.state(
                                base, n, rows, new Angles(0, 0, 0, 0, 0, 0, 0, 0), false, true, 0));
                journal.put(
                        n,
                        List.of(
                                ParCoolStagesCases.boundary(
                                        n, 100 + n, ParCoolStagesCases.ref(n))));
                var stages =
                        new ParCoolStages(
                                ParCoolStages.Mode.CANONICAL,
                                samples,
                                Map.of(),
                                ParCoolStagesCases.POLICY);
                result = stages.apply(result, n, NativeStage.NATIVE_TICK, journal.get(n));
                var reduced = (DiveHost) ((Animator) root(result.frame().graph()).data()).state();
                boolean nativeTransitioning =
                        parcool(nativeHost, "transitioning").getBoolean(nativeHost);
                int nativeTransitionTick = parcool(nativeHost, "transitionTick").getInt(nativeHost);
                boolean nativeOld = parcool(nativeHost, "oldSkyDiveDoing").getBoolean(nativeHost);
                var nativeSky = parcool(nativeHost, "skyDiveAnimator").get(nativeHost);
                assertEquals(nativeTransitioning, reduced.transitioning(), "transition " + n);
                assertEquals(
                        nativeTransitionTick, reduced.transitionTick(), "transition tick " + n);
                assertEquals(nativeOld, reduced.oldSkyDiveDoing(), "old SkyDive " + n);
                assertEquals(nativeSky != null, reduced.skyDiveAnimator() != null, "child " + n);
                var row = new LinkedHashMap<String, Object>();
                row.put("fromInAir", fromInAir);
                row.put("tick", n);
                row.put("skyDive", sky);
                if (nativeSky != null) {
                    row.put(
                            "nativeStartPitchBits",
                            Float.floatToRawIntBits(
                                    parcool(nativeSky, "startPitchAngle").getFloat(nativeSky)));
                    var reducedSky =
                            (SkyDiveChild)
                                    child(result.frame().graph(), reduced.skyDiveAnimator())
                                            .state();
                    row.put(
                            "deterministicStartPitchBits",
                            Float.floatToRawIntBits(reducedSky.startPitchAngle()));
                }
                var grid = result.frame().state().diveGrid().factor();
                row.put(
                        "diveGridBits",
                        List.of(
                                Float.floatToRawIntBits(grid.previous()),
                                Float.floatToRawIntBits(grid.start()),
                                Float.floatToRawIntBits(grid.first()),
                                Float.floatToRawIntBits(grid.second()),
                                Float.floatToRawIntBits(grid.third())));
                trace.add(row);
            }
        }
        return Map.of(
                "boundaries",
                trace.size(),
                "trace",
                trace,
                "abItems",
                "SkyDive child start pitch: native last-render Dive pitch versus the Dive root"
                        + " function at the transition boundary; Dive leg term on the 60 Hz grid");
    }

    private static ParCoolProjection.Result initial(
            AnimationGraphState graph, Map<ActionTag, ActionState> rows) {
        var starts = new ArrayList<Start>();
        for (Node node : graph.nodes())
            starts.add(
                    new Start(
                            node.start().eventSequence(),
                            node.start().nodeId(),
                            LayerTag.PARCOOL_ACTIVE,
                            StartKind.NEW,
                            AnchorClass.CANONICAL_TICK,
                            PhaseDomain.A,
                            0,
                            0,
                            0,
                            new CanonicalTick(0, NativeStage.NATIVE_TICK)));
        var neutral = ParCoolStagesCases.neutral();
        return new ParCoolProjection.Result(
                new ParCoolFrame(
                        graph,
                        new ParCool(
                                new NodeRef(root(graph).id()),
                                neutral.passiveStart(),
                                neutral.option(),
                                neutral.passive(),
                                List.copyOf(rows.values()),
                                neutral.hangGrid(),
                                new DiveGrid(new Grid4(0, 0, 0, 0, 0, 0)))),
                starts);
    }

    private static ActionState diveRow(ActionState row, boolean doing, double old, double now) {
        return ParCoolStagesCases.with(
                new ActionState(row.action(), doing, 1, 0, 1, row.inputs()),
                new DiveInput(false, -.4, old, now, 0, false, 9, false));
    }

    private static ActionState doing(ActionState row, boolean doing) {
        return new ActionState(row.action(), doing, 1, 0, 1, row.inputs());
    }
}
