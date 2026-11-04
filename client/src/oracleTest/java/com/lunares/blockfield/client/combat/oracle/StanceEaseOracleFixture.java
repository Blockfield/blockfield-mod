package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.api.unstable.animation.AnimationOption;
import com.alrex.parcool.client.animation.PlayerModelTransformer;
import com.alrex.parcool.client.animation.impl.CrawlAnimator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.StanceTransition;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.ParCoolFrame;
import com.lunares.blockfield.common.combat.ParCoolGraph;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.ParCoolPresentationCases;
import com.lunares.blockfield.oracle.StanceEaseCases;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.guns.compat.playeranimator.animation.AnimationManager;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * STANCE-EASE c2 client half: the driven remote's crouch bones blend by the replicated triple
 * through the shared {@link StanceTransition.Crouch#at} path the server uses. Every scenario tick
 * crosses the wire codec, restores into a driven mirror and runs the real transformed {@link
 * HumanoidModel#setupAnim} (production {@code StanceCrouchMixin}); the overwritten bone fields
 * equal the shared path exactly, and the additive arm term equals its delta against a
 * zero-coefficient run with identical inputs.
 */
class StanceEaseOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void remoteBodyBlendsByTheReplicatedTriple() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "stance-ease-remote-body",
                () -> {
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var scenarios = new ArrayList<Map<String, Object>>();
                    for (var scenario : StanceEaseCases.scenarios()) {
                        var rows = StanceEaseCases.run(scenario);
                        checkRemoteBones(scenario);
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("scenario", scenario.name());
                        entry.put("ticks", rows);
                        scenarios.add(entry);
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("scenarios", scenarios);
                    result.put(
                            "crawl", StanceEaseCases.crawlLayers(StanceEaseOracleFixture::crawl));
                    TaczRemoteOracleFixture.bootstrap();
                    result.put(
                            "lr",
                            StanceEaseCases.paLayers(true, StanceEaseOracleFixture::nativePa));
                    result.put(
                            "tacz",
                            StanceEaseCases.paLayers(false, StanceEaseOracleFixture::nativePa));
                    OracleTransformAudit.requireConfig("blockfield-client-lrtactical.mixins.json");
                    result.put("firstPerson", firstPerson());
                    result.put("cameraFrames", cameraFrames());
                    result.put("swimFrames", swimFrames());
                    result.put(
                            "crawlFrames",
                            StanceEaseCases.crawlFrames(StanceEaseOracleFixture::crawl));
                    // A restored cold checkpoint has no easing; the renderer's crouch flag wins.
                    var cold =
                            drivenPlayer(
                                    new StanceEaseCases.Tick(StanceEaseCases.CROUCHED, false, true),
                                    new StanceTransition.Snapshot(
                                            0, false, 0, 0, 0, 0, 0, 0, null, null));
                    var parts = snapshot(runNative(cold));
                    assertFalse(cold.mirror().eases());
                    assertEquals(0f, cold.mirror().crouchness());
                    assertEquals(.5f, parts.get("body.xRot"), 0);
                    assertEquals(4.2f, parts.get("head.y"), 0);
                    assertEquals(4f, parts.get("rightLeg.z"), 0);
                    result.put("uneasedCrouchZeroTriple", parts);
                    return result;
                });
    }

    @Test
    void easedCrouchComposesBeforeSwim() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "stance-ease-crouch-before-swim",
                () -> Map.of("samples", compositions(false)));
    }

    @Test
    void medkitForcesFullCrouchThroughEasing() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "stance-ease-medkit-full-crouch",
                () -> Map.of("samples", compositions(true)));
    }

    private static List<Map<String, Object>> compositions(boolean medkit) {
        var result = new ArrayList<Map<String, Object>>();
        for (var value : StanceEaseCases.compositions(medkit)) {
            var driven = swimmingDriven(value.stance(), value.swim());
            when(driven.player().getItemBySlot(any())).thenReturn(ItemStack.EMPTY);
            try (var singleton = SceneTimingOracleFixture.minecraft();
                    var retained = mockStatic(DrivenPresentation.class)) {
                when(net.minecraft.client.Minecraft.getInstance()
                                .getTimer()
                                .getGameTimeDeltaPartialTick(anyBoolean()))
                        .thenReturn(value.fraction());
                retained.when(() -> DrivenPresentation.medkit(driven.player())).thenReturn(medkit);
                var model = new PlayerModel<Player>(mesh().bakeRoot(), false);
                model.prepareMobModel(driven.player(), 0, 0, value.fraction());
                model.setupAnim(driven.player(), 0, 0, value.fraction(), 0, 0);
                if (medkit) {
                    assertTrue(model.crouching, "retained medkit must keep full crouch");
                    assertEquals(4.2f, model.head.y, 0);
                    assertEquals(.5f, model.body.xRot, 0);
                    assertEquals(12.2f, model.rightLeg.y, 0);
                    assertEquals(-.35f, model.rightArm.xRot, 0);
                } else {
                    float crouch = value.stance().crouch();
                    float beforeSwim = .4f * crouch + Mth.sin(value.fraction() * .067f) * .05f;
                    assertEquals(
                            Mth.lerp(value.swim(), beforeSwim, 0),
                            model.rightArm.xRot,
                            1e-6f,
                            "crouch must precede swim");
                    var frame = value.frame();
                    var transformer =
                            new PlayerModelTransformer(
                                    driven.player(), model, false, value.fraction(), 0, 0, 0, 0);
                    transformer.setOption(
                            (AnimationOption)
                                    ParCoolGraph.option(
                                            AnimationOption.class, frame.state().option()));
                    new CrawlAnimator()
                            .animatePost(driven.player(), frame.scratchAttachment(), transformer);
                }
                var parts =
                        List.of(
                                model.head,
                                model.body,
                                model.leftArm,
                                model.rightArm,
                                model.leftLeg,
                                model.rightLeg);
                var params = new float[36];
                var matrices = new org.joml.Matrix4f[6];
                int k = 0;
                for (int i = 0; i < 6; i++) {
                    var part = parts.get(i);
                    for (float scalar :
                            new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot})
                        params[k++] = scalar;
                    var stack = new com.mojang.blaze3d.vertex.PoseStack();
                    part.translateAndRotate(stack);
                    matrices[i] = new org.joml.Matrix4f(stack.last().pose());
                }
                result.add(StanceEaseCases.compositionGeometry(value, params, matrices));
            }
        }
        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
        return result;
    }

    private static StanceEaseCases.PaTimeline nativePa(boolean lr) throws Exception {
        var player = TaczRemoteOracleFixture.player();
        if (!lr) {
            var gunItem =
                    mock(
                            net.minecraft.world.item.Item.class,
                            withSettings().extraInterfaces(com.tacz.guns.api.item.IGun.class));
            var gun = mock(ItemStack.class);
            when(gun.getItem()).thenReturn(gunItem);
            doReturn(gun).when(player).getMainHandItem();
            assertTrue(player.getMainHandItem().getItem() instanceof com.tacz.guns.api.item.IGun);
        }
        var stance = new StanceTransition();
        when(((StanceTransition.Access) player).blockfield$stance()).thenReturn(stance);
        var stack = ((IAnimatedPlayer) player).getAnimationStack();
        var rifle = ResourceLocation.parse("tacz:rifle_default.player_animation");
        var display = mock(GunDisplayInstance.class);
        when(display.getPlayerAnimator3rd()).thenReturn(rifle);
        var integration =
                Class.forName(
                        "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration");
        var layerClass = Class.forName(integration.getName() + "$AnimationLayer");
        var idle =
                integration.getMethod(
                        "playIdleAnimation",
                        AbstractClientPlayer.class,
                        ResourceLocation.class,
                        String.class,
                        layerClass,
                        int.class);
        var upperLayer = layerClass.getField("UPPER").get(null);
        var lowerLayer = layerClass.getField("LOWER").get(null);
        return new StanceEaseCases.PaTimeline() {
            public void select(String upper, String lower, StanceTransition.Snapshot snap)
                    throws Exception {
                stance.restore(snap);
                doReturn(
                                snap.toFlat() == 1
                                        ? net.minecraft.world.entity.Pose.SWIMMING
                                        : snap.toCrouch() == 1
                                                ? net.minecraft.world.entity.Pose.CROUCHING
                                                : net.minecraft.world.entity.Pose.STANDING)
                        .when(player)
                        .getPose();
                doReturn(snap.toCrouch() == 1).when(player).isCrouching();
                if (lr) {
                    idle.invoke(null, player, rifle, upper, upperLayer, 4);
                    idle.invoke(null, player, rifle, lower, lowerLayer, 8);
                } else {
                    AnimationManager.playLoopAnimation(
                            player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, upper);
                    if (lower != null)
                        AnimationManager.playLoopAnimation(
                                player, display, PlayerAnimatorCompat.LOWER_ANIMATION, lower);
                }
            }

            public void tick() {
                stack.tick();
            }

            public int[] sample(float fraction) {
                stack.setupAnim(fraction);
                return StanceEaseCases.paBits(
                        (part, type, base) -> stack.get3DTransform(part, type, fraction, base));
            }
        };
    }

    private static List<Map<String, Object>> firstPerson() throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        var progress = ObjectAnimationRunner.class.getDeclaredMethod("updateProgress", long.class);
        progress.setAccessible(true);
        for (String gun : List.of("rpk", "fn_evolys")) {
            String path =
                    "/assets/tacz/custom/tacz_default_gun/assets/tacz/animations/"
                            + gun
                            + ".animation.json";
            JsonObject animations;
            try (var input = StanceEaseOracleFixture.class.getResourceAsStream(path)) {
                assertNotNull(input, "pinned crawl assets");
                animations =
                        JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                                .getAsJsonObject()
                                .getAsJsonObject("animations");
            }
            for (String name : List.of("crawl_start", "crawl_end")) {
                float seconds =
                        animations.getAsJsonObject(name).get("animation_length").getAsFloat();
                var animation =
                        mock(
                                ObjectAnimation.class,
                                withSettings()
                                        .useConstructor(name)
                                        .defaultAnswer(CALLS_REAL_METHODS));
                doReturn(seconds).when(animation).getMaxEndTimeS();
                var runner = new ObjectAnimationRunner(animation);
                runner.run();
                for (int tick = 0; tick < StanceTransition.TICKS; tick++)
                    progress.invoke(runner, 50_000_000L);
                long elapsed = runner.getProgressNs();
                long expected = gun.equals("rpk") ? 400_000_000L : 600_000_000L;
                assertEquals(expected, elapsed, gun + " " + name);
                result.add(
                        Map.of(
                                "gun",
                                gun,
                                "clip",
                                name,
                                "authoredTicks",
                                seconds * 20,
                                "effectiveTicks",
                                gun.equals("rpk") ? seconds * 20 : 8,
                                "progressNsAt8",
                                elapsed));
            }
        }
        OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
        return result;
    }

    @Test
    void proneCrawlUsesRenderFractions() {
        StanceEaseCases.crawlFrames(StanceEaseOracleFixture::crawl);
    }

    @Test
    void proneSwimUsesRenderFractions() throws Exception {
        swimFrames();
    }

    private static List<Map<String, Object>> swimFrames() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        var update = OracleReflection.method(LivingEntity.class, "updateSwimAmount", "()V");
        update.setAccessible(true);
        for (var scenario : StanceEaseCases.crawlScenarios()) {
            var stance = new StanceTransition();
            var box = stance.apply(null, StanceEaseCases.STANDING, true, false, false);
            var player =
                    mock(
                            Player.class,
                            withSettings().extraInterfaces(StanceTransition.Access.class));
            when(((StanceTransition.Access) player).blockfield$stance()).thenReturn(stance);
            doCallRealMethod().when(player).getSwimAmount(anyFloat());
            int tick = 0;
            for (var step : scenario.ticks()) {
                box =
                        step.target() == null
                                ? stance.hold(box)
                                : stance.apply(
                                        box, step.target(), true, step.prone(), step.crouching());
                update.invoke(player);
                var snap = stance.snapshot();
                float next =
                        snap.age() >= StanceTransition.TICKS
                                ? snap.flat()
                                : Mth.lerp(
                                        Math.min(snap.age() + 1, StanceTransition.TICKS)
                                                / (float) StanceTransition.TICKS,
                                        snap.fromFlat(),
                                        snap.toFlat());
                for (float partial : new float[] {0, .25f, .5f, .75f, 65535 / 65536f}) {
                    float actual = player.getSwimAmount(partial);
                    assertEquals(
                            Mth.lerp(partial, snap.flat(), next),
                            actual,
                            1e-6f,
                            scenario.name() + " swim tick " + tick + " partial " + partial);
                    rows.add(
                            Map.of(
                                    "scenario",
                                    scenario.name(),
                                    "tick",
                                    tick,
                                    "partial",
                                    partial,
                                    "swim",
                                    actual));
                }
                stance.tick();
                tick++;
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> cameraFrames() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        var entity =
                OracleReflection.field(
                        net.minecraft.client.Camera.class,
                        "entity",
                        "Lnet/minecraft/world/entity/Entity;");
        var old = OracleReflection.field(net.minecraft.client.Camera.class, "eyeHeightOld", "F");
        var end = OracleReflection.field(net.minecraft.client.Camera.class, "eyeHeight", "F");
        entity.setAccessible(true);
        old.setAccessible(true);
        end.setAccessible(true);
        for (var scenario : StanceEaseCases.scenarios()) {
            var stance = new StanceTransition();
            var box = stance.apply(null, StanceEaseCases.STANDING, true, false, false);
            var player =
                    mock(
                            Player.class,
                            withSettings().extraInterfaces(StanceTransition.Access.class));
            when(((StanceTransition.Access) player).blockfield$stance()).thenReturn(stance);
            var camera = new net.minecraft.client.Camera();
            entity.set(camera, player);
            old.setFloat(camera, box.eyeHeight());
            end.setFloat(camera, box.eyeHeight());
            // Eye changes outside crouch/prone keep vanilla's half-per-tick camera filter.
            stance.apply(box, box.withEyeHeight(1f), true, false, false);
            when(player.getEyeHeight()).thenReturn(1f);
            camera.tick();
            assertEquals(
                    (box.eyeHeight() + 1f) / 2,
                    end.getFloat(camera),
                    1e-6f,
                    "ordinary eye changes keep vanilla smoothing");
            stance.apply(box.withEyeHeight(1f), box, true, false, false);
            old.setFloat(camera, box.eyeHeight());
            end.setFloat(camera, box.eyeHeight());
            int tick = 0;
            for (var step : scenario.ticks()) {
                box =
                        step.target() == null
                                ? stance.hold(box)
                                : stance.apply(
                                        box, step.target(), true, step.prone(), step.crouching());
                when(player.getEyeHeight()).thenReturn(box.eyeHeight());
                camera.tick();
                var snap = stance.snapshot();
                float nextEye =
                        snap.age() >= StanceTransition.TICKS
                                ? box.eyeHeight()
                                : Mth.lerp(
                                        Math.min(snap.age() + 1, StanceTransition.TICKS)
                                                / (float) StanceTransition.TICKS,
                                        snap.from().eyeHeight(),
                                        snap.to().eyeHeight());
                for (float fraction : new float[] {0, .25f, .5f, .75f, 65535 / 65536f}) {
                    float actual = Mth.lerp(fraction, old.getFloat(camera), end.getFloat(camera));
                    assertEquals(
                            Mth.lerp(fraction, box.eyeHeight(), nextEye),
                            actual,
                            1e-6f,
                            scenario.name() + " camera tick " + tick + " partial " + fraction);
                    rows.add(
                            Map.of(
                                    "scenario",
                                    scenario.name(),
                                    "tick",
                                    tick,
                                    "partial",
                                    fraction,
                                    "eye",
                                    actual));
                }
                stance.tick();
                tick++;
            }
        }
        return rows;
    }

    private static StanceEaseCases.CrawlSample crawl(
            ParCoolFrame frame, AnimationState.Stance stance, float fraction) {
        var snap =
                StanceTransition.snapshotOf(
                        stance, StanceEaseCases.STANDING, StanceEaseCases.PRONE);
        var driven =
                drivenPlayer(new StanceEaseCases.Tick(StanceEaseCases.PRONE, true, false), snap);
        try (var singleton = SceneTimingOracleFixture.minecraft()) {
            when(net.minecraft.client.Minecraft.getInstance()
                            .getTimer()
                            .getGameTimeDeltaPartialTick(anyBoolean()))
                    .thenReturn(fraction);
            var model =
                    new PlayerModel<Player>(
                            LayerDefinition.create(
                                            PlayerModel.createMesh(CubeDeformation.NONE, false),
                                            64,
                                            64)
                                    .bakeRoot(),
                            false);
            var parts =
                    List.of(
                            model.head,
                            model.body,
                            model.leftArm,
                            model.rightArm,
                            model.leftLeg,
                            model.rightLeg);
            var base = StanceEaseCases.crawlBase();
            for (int i = 0; i < parts.size(); i++) {
                var p = parts.get(i);
                p.x = base[i][0];
                p.y = base[i][1];
                p.z = base[i][2];
                p.xRot = base[i][3];
                p.yRot = base[i][4];
                p.zRot = base[i][5];
            }
            var transformer =
                    new PlayerModelTransformer(
                            driven.player(),
                            model,
                            false,
                            fraction,
                            ParCoolPresentationCases.limb(fraction),
                            0,
                            0,
                            0);
            transformer.setOption(
                    (AnimationOption)
                            ParCoolGraph.option(AnimationOption.class, frame.state().option()));
            var actor = new CrawlAnimator();
            var attachment = frame.scratchAttachment();
            boolean removed = actor.shouldRemoved(driven.player(), attachment);
            actor.animatePost(driven.player(), attachment, transformer);
            var bits = new int[36];
            int k = 0;
            for (var p : parts)
                for (float value : new float[] {p.x, p.y, p.z, p.xRot, p.yRot, p.zRot})
                    bits[k++] = Float.floatToRawIntBits(value);
            OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
            return new StanceEaseCases.CrawlSample(bits, removed);
        }
    }

    /** Replays every tick's wire triple through the real transformed model and checks the bones. */
    private static void checkRemoteBones(StanceEaseCases.Scenario scenario) {
        var server = new StanceTransition();
        var box =
                server.apply(
                        StanceEaseCases.STANDING, StanceEaseCases.STANDING, true, false, false);
        // Settled-standing arm baseline: identical inputs, so any delta is the crouch term alone.
        var settled = new StanceTransition();
        settled.apply(StanceEaseCases.STANDING, StanceEaseCases.STANDING, true, false, false);
        float standingArmX =
                snapshot(
                                runNative(
                                        drivenPlayer(
                                                new StanceEaseCases.Tick(
                                                        StanceEaseCases.STANDING, false, false),
                                                settled.snapshot())))
                        .get("rightArm.xRot");
        int tick = 0;
        for (var step : scenario.ticks()) {
            if (step.target() == null) box = server.hold(box);
            else box = server.apply(box, step.target(), true, step.prone(), step.crouching());
            var snap = server.snapshot();
            float c = snap.crouch();
            var k = StanceTransition.Crouch.at(c);
            var driven = drivenPlayer(step, snap);
            var parts = snapshot(runNative(driven));
            // The TAIL overwrites these fields while easing, vanilla native while settled:
            // both equal the shared path bit for bit.
            assertEquals(k.torsoXRot(), parts.get("body.xRot"), 0, at(scenario, tick, "body.xRot"));
            assertEquals(k.legZ(), parts.get("rightLeg.z"), 0, at(scenario, tick, "leg.z"));
            assertEquals(k.legY(), parts.get("rightLeg.y"), 0, at(scenario, tick, "leg.y"));
            assertEquals(k.headY(), parts.get("head.y"), 0, at(scenario, tick, "head.y"));
            assertEquals(k.torsoY(), parts.get("body.y"), 0, at(scenario, tick, "body.y"));
            assertEquals(k.armY(), parts.get("rightArm.y"), 0, at(scenario, tick, "arm.y"));
            if (driven.mirror().changing()) {
                // The arms are additive while easing: the delta against an identical
                // zero-coefficient run is the shared term exactly.
                var base = snapshot(runNative(zeroed(driven)));
                assertEquals(
                        k.armXRotAdd(),
                        parts.get("rightArm.xRot") - base.get("rightArm.xRot"),
                        1e-6f,
                        at(scenario, tick, "rightArm.xRot delta"));
                assertEquals(
                        k.armXRotAdd(),
                        parts.get("leftArm.xRot") - base.get("leftArm.xRot"),
                        1e-6f,
                        at(scenario, tick, "leftArm.xRot delta"));
            } else if (step.crouching()) {
                // Settled crouch is the vanilla native branch: its arm constant is the shared one.
                assertEquals(
                        StanceTransition.Crouch.at(1).armXRotAdd(),
                        parts.get("rightArm.xRot") - standingArmX,
                        1e-6f,
                        at(scenario, tick, "settled rightArm.xRot delta"));
            } else {
                assertEquals(
                        standingArmX,
                        parts.get("rightArm.xRot"),
                        0,
                        at(scenario, tick, "arm rest"));
            }
            server.tick();
            tick++;
        }
    }

    private static String at(StanceEaseCases.Scenario scenario, int tick, String field) {
        return scenario.name() + " tick " + tick + ": remote " + field;
    }

    /** A driven mirror restored from this tick's wire triple, with a nonzero swim amount. */
    private static Driven swimmingDriven(StanceTransition.Snapshot snap, float swim) {
        var player =
                mock(Player.class, withSettings().extraInterfaces(StanceTransition.Access.class));
        var mirror = new StanceTransition();
        mirror.setDriven(true);
        mirror.restore(
                StanceTransition.snapshotOf(
                        StanceEaseCases.recordOf(snap),
                        snap.from() == null ? StanceEaseCases.STANDING : snap.from(),
                        snap.to() == null ? StanceEaseCases.STANDING : snap.to()));
        when(((StanceTransition.Access) player).blockfield$stance()).thenReturn(mirror);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        when(player.getSwimAmount(anyFloat())).thenReturn(swim);
        when(player.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(player.getFallFlyingTicks()).thenReturn(0);
        when(player.getAttackAnim(anyFloat())).thenReturn(0f);
        when(player.isVisuallySwimming()).thenReturn(false);
        when(player.isFallFlying()).thenReturn(false);
        when(player.isCrouching()).thenReturn(false);
        when(player.isUsingItem()).thenReturn(false);
        return new Driven(player, mirror, false);
    }

    /** Fresh parts per run so no retained model state leaks between samples. */
    private static LayerDefinition mesh() {
        return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
    }

    /**
     * A driven mock player whose transition is the mirror restored from this tick's wire triple —
     * the same endpoint mapping {@code DrivenPresentation} installs (see {@link
     * StanceTransition#snapshotOf}).
     */
    private static Driven drivenPlayer(StanceEaseCases.Tick step, StanceTransition.Snapshot snap) {
        var player =
                mock(Player.class, withSettings().extraInterfaces(StanceTransition.Access.class));
        var mirror = new StanceTransition();
        mirror.setDriven(true);
        mirror.restore(
                StanceTransition.snapshotOf(
                        StanceEaseCases.recordOf(snap),
                        snap.from() == null ? StanceEaseCases.STANDING : snap.from(),
                        snap.to() == null ? StanceEaseCases.STANDING : snap.to()));
        when(((StanceTransition.Access) player).blockfield$stance()).thenReturn(mirror);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        when(player.getSwimAmount(anyFloat())).thenReturn(0f);
        when(player.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(player.getFallFlyingTicks()).thenReturn(0);
        when(player.getAttackAnim(anyFloat())).thenReturn(0f);
        when(player.isVisuallySwimming()).thenReturn(false);
        when(player.isFallFlying()).thenReturn(false);
        when(player.isCrouching()).thenReturn(false);
        when(player.isUsingItem()).thenReturn(false);
        // The renderer sets the flag from the pose; only the production mixin may suppress it.
        boolean nativeCrouch = step.crouching();
        return new Driven(player, mirror, nativeCrouch);
    }

    private record Driven(Player player, StanceTransition mirror, boolean nativeCrouch) {}

    /** The same mirror with a zeroed triple, still easing: the arm baseline for this tick. */
    private static Driven zeroed(Driven driven) {
        var snap = driven.mirror().snapshot();
        var zero = new StanceTransition();
        zero.setDriven(true);
        zero.restore(
                new StanceTransition.Snapshot(
                        snap.age(), snap.eased(), 0, 0, 0, 0, 0, 0, snap.from(), snap.to()));
        when(((StanceTransition.Access) driven.player()).blockfield$stance()).thenReturn(zero);
        return new Driven(driven.player(), zero, driven.nativeCrouch());
    }

    private static HumanoidModel<LivingEntity> runNative(Driven driven) {
        // Accessories' constructor hook needs a (null-loader) Minecraft singleton, like the
        // vanilla setup-anim fixture; no GL service runs.
        try (var singleton = mockStatic(net.minecraft.client.Minecraft.class)) {
            singleton
                    .when(net.minecraft.client.Minecraft::getInstance)
                    .thenReturn(mock(net.minecraft.client.Minecraft.class));
            var model = new HumanoidModel<LivingEntity>(mesh().bakeRoot());
            model.crouching = driven.nativeCrouch();
            model.prepareMobModel(driven.player(), 0, 0, 1);
            model.attackTime = 0;
            model.setupAnim(driven.player(), 0, 0, 0, 0, 0);
            return model;
        }
    }

    private static Map<String, Float> snapshot(HumanoidModel<LivingEntity> model) {
        var out = new LinkedHashMap<String, Float>();
        out.put("body.xRot", model.body.xRot);
        out.put("body.y", model.body.y);
        out.put("head.y", model.head.y);
        out.put("rightArm.xRot", model.rightArm.xRot);
        out.put("rightArm.y", model.rightArm.y);
        out.put("leftArm.xRot", model.leftArm.xRot);
        out.put("leftArm.y", model.leftArm.y);
        out.put("rightLeg.z", model.rightLeg.z);
        out.put("rightLeg.y", model.rightLeg.y);
        out.put("leftLeg.z", model.leftLeg.z);
        out.put("leftLeg.y", model.leftLeg.y);
        return out;
    }
}
