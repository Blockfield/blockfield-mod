package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import com.lunares.blockfield.client.animation.RemoteLrAssets;
import com.lunares.blockfield.client.animation.RemoteLrCompat;
import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.oracle.LrSelectorNativeCases;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.PaGraphNativeCases;
import com.lunares.blockfield.oracle.PaObserverNativeCases;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.*;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * U4 native differential for the LR selector (ADR4 §§3.2/5.1): the pinned {@code
 * IdleAnimationHandler.onClientTick} and {@code MeleeAnimationListener} on factory-registered
 * players with production mixins and the real shared TaCZ clip cache, against {@link LrPose}'s
 * canonical tick with {@link RemoteLrAssets}-retained assets. Native render histories at
 * 0/30/60/144/240 FPS, and the driven-remote native compatibility view against an all-native world,
 * including LR's remote-to-local shared-clip metadata effect and first-person mode.
 */
class LrSelectorOracleFixture {
    private static final String LR = "me.xjqsh.lrtactical.";
    private static final ResourceLocation FILE = ResourceLocation.parse("fixture:lr/melee");
    private static final List<String> PARTS =
            List.of("body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static final float[] FRACTIONS = {1f / 65536, .3f, .85f, 1 - 1f / 65536};
    private static final int[] FPS = {0, 30, 60, 144, 240};
    private static final AnimationPolicy POLICY = PaGraphNativeCases.policy(PhaseDomain.A);

    private static Class<?> weapon, displayType, configType, eventType, actionType;
    private static Method onClientTick, onMelee, attackActive, stackLookup, idLookup, thirdPerson;
    private static LayerTag winner;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class<?> integration =
                Class.forName(LR + "compat.player_animator.PlayerAnimatorIntegration");
        integration.getMethod("init").invoke(null);
        // TaczRemoteOracleFixture registers TaCZ's factories earlier in this JVM; never twice.
        if (!probe(PlayerAnimatorCompat.LOWER_ANIMATION)) PlayerAnimatorCompat.init();
        weapon = Class.forName(LR + "api.item.IMeleeWeapon");
        displayType = Class.forName(LR + "client.resource.display.MeleeDisplayInstance");
        configType = Class.forName(LR + "compat.player_animator.ThirdPersonAnimationConfig");
        eventType = Class.forName(LR + "api.event.MeleePreAttackEvent");
        actionType = Class.forName(LR + "api.melee.MeleeAction");
        var api = Class.forName(LR + "client.api.LrTacticalClientAPI");
        stackLookup = api.getMethod("getMeleeDisplay", ItemStack.class);
        idLookup = api.getMethod("getMeleeDisplay", ResourceLocation.class);
        thirdPerson = displayType.getMethod("getThirdPersonAnimation");
        onClientTick =
                Class.forName(LR + "compat.player_animator.IdleAnimationHandler")
                        .getMethod("onClientTick");
        onMelee =
                Class.forName(LR + "compat.player_animator.MeleeAnimationListener")
                        .getMethod("onMeleeAnimation", eventType);
        attackActive =
                integration.getMethod(
                        "isAttackAnimationActive",
                        AbstractClientPlayer.class,
                        Class.forName(integration.getName() + "$AnimationLayer"));
        var player = player(AbstractClientPlayer.class, 1);
        var stack = ((IAnimatedPlayer) player).getAnimationStack();
        winner = layerTag(stack, layer(player, RemoteLrCompat.ROTATION));
    }

    // -- scenario ------------------------------------------------------------------------------

    /** One player's tick inputs; {@code item} names a stack of the world. */
    private record In(
            String item,
            boolean ground,
            Vec3 delta,
            boolean crouch,
            boolean sprint,
            Pose pose,
            boolean climb,
            boolean swim) {
        static In stand(String item) {
            return new In(item, true, Vec3.ZERO, false, false, Pose.STANDING, false, false);
        }
    }

    private record Attack(int player, String action, int count, String display) {}

    /** Selector scenario: four players (index = list position) covering every native branch. */
    private static In selectorInput(int player, int t) {
        return switch (player) {
            case 0 -> moving(t);
            case 1 -> In.stand(t >= 12 && t < 20 ? "stick" : "bat");
            case 2 -> In.stand("no_config");
            default -> In.stand(t >= 15 ? "no_path" : "bat");
        };
    }

    private static In moving(int t) {
        if (t >= 30 && t < 33)
            return new In("bat", true, Vec3.ZERO, false, false, Pose.SWIMMING, false, true);
        if (t == 33)
            return new In("bat", true, Vec3.ZERO, false, false, Pose.STANDING, true, false);
        if (t == 34 || t == 35)
            return new In("bat", true, Vec3.ZERO, false, false, Pose.SLEEPING, false, false);
        if (t == 36)
            return new In("bat", false, Vec3.ZERO, false, false, Pose.FALL_FLYING, false, false);
        if (t >= 6 && t < 9)
            return new In(
                    "bat",
                    true,
                    new Vec3(t == 6 ? .0316 : .0317, 0, 0),
                    false,
                    false,
                    Pose.STANDING,
                    false,
                    false);
        if (t >= 9 && t < 12)
            return new In(
                    "bat",
                    true,
                    new Vec3(t == 9 ? .2236 : .2237, 0, 0),
                    false,
                    false,
                    Pose.STANDING,
                    false,
                    false);
        if (t == 12)
            return new In(
                    "bat", true, new Vec3(.3, 0, 0), false, true, Pose.STANDING, false, false);
        if (t == 14)
            return new In("bat", true, Vec3.ZERO, true, false, Pose.CROUCHING, false, false);
        if (t == 15)
            return new In(
                    "bat", true, new Vec3(.1, 0, 0), true, false, Pose.CROUCHING, false, false);
        if (t == 17 || t == 18)
            return new In(
                    "bat", false, new Vec3(0, .4, 0), false, false, Pose.STANDING, false, false);
        if (t == 19 || t == 20)
            return new In(
                    "bat", false, new Vec3(0, -.4, 0), false, false, Pose.STANDING, false, false);
        return In.stand("bat");
    }

    private static List<Attack> selectorAttacks(int t) {
        return switch (t) {
            case 3, 5 -> List.of(new Attack(0, "attack_a", 0, "fixture:bat"));
            case 7 -> List.of(new Attack(0, "attack_b", 1, "fixture:bat")); // Gson null slot
            case 21 -> List.of(new Attack(0, "attack_b", 2, "fixture:bat"));
            case 23 -> List.of(new Attack(0, "attack_missing", 0, "fixture:bat"));
            case 24 -> List.of(new Attack(0, "attack_a", 0, "fixture:unknown"));
            case 31 ->
                    List.of(
                            new Attack(0, "attack_a", 0, "fixture:bat"),
                            new Attack(2, "attack_a", 0, "fixture:no_config"));
            case 40 -> List.of(new Attack(3, "attack_a", 0, "fixture:no_path"));
            default -> List.of();
        };
    }

    private static final String BAT =
            "{\"animation\":\"fixture:lr/melee\",\"upper\":{\"idle\":\"u_idle\",\"walk\":\"u_walk\","
                    + "\"run\":\"u_run\",\"sprint\":\"u_run\",\"sneak\":\"u_idle\",\"sneak_walk\":"
                    + "\"u_walk\",\"jump\":\"u_idle\",\"fall\":\"u_walk\",\"attack_a\":[\"atk_a\"],"
                    + "\"attack_b\":[\"atk_a\",5,\"atk_b\"],\"attack_missing\":[\"no_such_clip\"]},"
                    + "\"lower\":{\"idle\":\"l_idle\",\"walk\":\"l_walk\",\"run\":\"l_run\",\"sprint\":"
                    + "\"l_run\",\"sneak\":\"l_idle\",\"sneak_walk\":\"l_walk\",\"jump\":\"l_idle\","
                    + "\"fall\":\"l_idle\"}}";
    private static final String NO_PATH = "{\"upper\":{\"idle\":\"u_walk\"},\"lower\":{}}";

    private static Map<String, KeyframeAnimation> clips() {
        var clips = new LinkedHashMap<String, KeyframeAnimation>();
        for (String name :
                List.of(
                        "u_idle",
                        "u_walk",
                        "u_run",
                        "l_idle",
                        "l_walk",
                        "l_run",
                        "empty",
                        "x_shared")) clips.put(name, PaGraphNativeCases.clip(name, true));
        for (String name : List.of("atk_a", "atk_b"))
            clips.put(name, PaGraphNativeCases.clip(name, false));
        return clips;
    }

    // -- fixtures ------------------------------------------------------------------------------

    @Test
    void nativeSelectorAcrossFrameRates() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-selector-native",
                () -> {
                    var result = new LinkedHashMap<String, Object>();
                    int compared = 0;
                    for (int fps : FPS) {
                        int count = selector(fps);
                        result.put("fps" + fps + "RawBitComparisons", count);
                        compared += count;
                    }
                    result.put("rawBitComparisons", compared);
                    result.put("ticks", 49);
                    result.put("players", 4);
                    result.put(
                            "cases",
                            List.of(
                                    "movement states at .001/.05 neighbours",
                                    "suppressed swim/climb/sleep/fall-fly stopAll",
                                    "non-melee and display-without-config stopAll",
                                    "config without animation path leaves layers",
                                    "attack replace, Gson null slot, missing clip, unknown display",
                                    "attack accepted on a stopAll tick"));
                    result.put(
                            "renderBoundary",
                            "PA setup only; mixed model render characterized separately");
                    result.put("rotationWinner", winner.name());
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    return result;
                });
    }

    @Test
    void drivenRemotesKeepLocalNativeObservations() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-compat-view",
                () -> {
                    var nativeTrace = compat(false);
                    var drivenTrace = compat(true);
                    assertEquals(nativeTrace.size(), drivenTrace.size());
                    for (int i = 0; i < nativeTrace.size(); i++)
                        assertEquals(nativeTrace.get(i), drivenTrace.get(i), "observation " + i);
                    assertTrue(
                            Arrays.stream(Player.class.getDeclaredMethods())
                                    .anyMatch(m -> m.getName().endsWith("blockfield$compatTick")),
                            "compat tick merged into Player.tick");
                    assertTrue(
                            Arrays.stream(
                                            Class.forName(
                                                            LR
                                                                    + "compat.player_animator.PlayerAnimatorIntegration")
                                                    .getDeclaredMethods())
                                    .anyMatch(m -> m.getName().endsWith("blockfield$compatLayer")),
                            "compat layer redirect merged into PlayerAnimatorIntegration");
                    OracleTransformAudit.requireConfig("blockfield-client-lrtactical.mixins.json");
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return Map.of(
                            "observations",
                            nativeTrace.size(),
                            "drivenRemotes",
                            2,
                            "detachContinuationTicks",
                            20,
                            "remoteToLocalMetadata",
                            "a remote attack flags the shared clip the local player idles with",
                            "localFirstPersonMode",
                            "DISABLED");
                });
    }

    @Test
    void observerSelectionProjection() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-selector-projection",
                () -> {
                    var result = LrSelectorNativeCases.projection();
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    return result;
                });
    }

    @Test
    void mixedModelRenderHistoryIsMeasured() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-mixed-render-history",
                () -> {
                    var measurements = new LinkedHashMap<String, Object>();
                    var identity = pinnedEmpty();
                    var noFrames = renderHistory(0, identity, false);
                    var drivenBaseline =
                            renderHistory(0, PaGraphNativeCases.clip("empty", true), true);
                    int changed = 0;
                    for (int fps : FPS) {
                        var nativeIdentity = renderHistory(fps, identity, false);
                        assertArrayEquals(
                                noFrames.parts(),
                                nativeIdentity.parts(),
                                "identity empty at " + fps);
                        assertEquals(
                                fps == 0 ? 1 : 25,
                                nativeIdentity.installs(),
                                "native rotation relatches");
                        var driven =
                                renderHistory(fps, PaGraphNativeCases.clip("empty", true), true);
                        assertEquals(1, driven.installs(), "driven rotation latches once");
                        assertArrayEquals(
                                drivenBaseline.parts(), driven.parts(), "driven model at " + fps);
                        var nonIdentity =
                                renderHistory(fps, PaGraphNativeCases.clip("empty", true), false);
                        if (!Arrays.equals(drivenBaseline.parts(), nonIdentity.parts())) changed++;
                        measurements.put(
                                "fps" + fps,
                                Map.of(
                                        "nativeRotationInstalls",
                                        nativeIdentity.installs(),
                                        "drivenRotationInstalls",
                                        driven.installs(),
                                        "modelCalls",
                                        driven.renders(),
                                        "nativeIdentityPartBits",
                                        nativeIdentity.parts(),
                                        "nativeNonIdentityPartBits",
                                        nonIdentity.parts(),
                                        "drivenNonIdentityPartBits",
                                        driven.parts(),
                                        "nativeRotationAfterRender",
                                        nativeIdentity.rotation(),
                                        "drivenRotationAfterRender",
                                        driven.rotation()));
                    }
                    assertTrue(
                            changed > 0,
                            "non-identity empty exposes the native render-history dependency");
                    measurements.put("nonIdentityNativeGeometryDifferences", changed);
                    measurements.put(
                            "pipeline",
                            "transformed PlayerModel prepare/setup, PA and TaCZ no-gun stopAll; no GL/root/capture");
                    measurements.put("rotationWinner", winner.name());
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    return measurements;
                });
    }

    private record RenderHistory(int installs, int renders, int[] parts, String rotation) {}

    private static KeyframeAnimation pinnedEmpty() throws Exception {
        try (var input =
                LrSelectorOracleFixture.class.getResourceAsStream(
                        "/assets/lrtactical/player_animator/melee/baseball_bat.json")) {
            assertNotNull(input);
            var json =
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(
                                            input, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var empty =
                    TaczAssets.keyed(
                                    dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer
                                            .deserialize(json))
                            .get("empty");
            assertNotNull(empty, "pinned LR empty clip must be present");
            return empty;
        }
    }

    private static RenderHistory renderHistory(int fps, KeyframeAnimation empty, boolean driven)
            throws Exception {
        try (var world = new World(empty)) {
            world.stack("bat", true, world.display("fixture:bat", BAT));
            var player = player(AbstractClientPlayer.class, 400);
            world.add(player);
            world.input(0, In.stand("bat"));
            var canonical = new Canon(world.assets);
            if (driven) RemoteLrCompat.attach(player);
            int installs = 0, renders = 0;
            IAnimation previous = null;
            try {
                for (int t = 0; t <= 24; t++) {
                    if (t > 0) {
                        if (driven) RemoteLrCompat.tick(player);
                        else ((IAnimatedPlayer) player).getAnimationStack().tick();
                        canonical.stack.tick();
                    }
                    canonical.select(world.held("bat"), In.stand("bat"));
                    onClientTick.invoke(null);
                    var actor =
                            driven
                                    ? canonical.stack.layer(winner).getAnimation()
                                    : layer(player, RemoteLrCompat.ROTATION).getAnimation();
                    if (actor != previous) installs++;
                    previous = actor;
                    var frozen = canonical.stack.freeze(AnimationGraphState.neutral());
                    for (float fraction : modelFrames(fps, t)) {
                        if (driven) installLr(player, canonical, frozen, fraction);
                        model(player, fraction);
                        renders++;
                    }
                    assertEquals(
                            frozen,
                            canonical.stack.freeze(AnimationGraphState.neutral()),
                            "render mutated retained state");
                }
                if (driven)
                    installLr(
                            player,
                            canonical,
                            canonical.stack.freeze(AnimationGraphState.neutral()),
                            .5f);
                var parts = model(player, .5f);
                return new RenderHistory(
                        installs,
                        renders + 1,
                        parts,
                        describe(layer(player, RemoteLrCompat.ROTATION)));
            } finally {
                RemotePaPresentation.clear(player);
                if (driven) RemoteLrCompat.detach(player);
            }
        }
    }

    /** A fixed 1/7-frame phase avoids native age-zero/partial-zero singular samples. */
    private static List<Float> modelFrames(int fps, int tick) {
        var result = new ArrayList<Float>();
        if (fps == 0) return result;
        for (long k = Math.max(0, Math.floorDiv(7L * tick * fps - 20 + 139, 140));
                140 * k + 20 < 7L * (tick + 1) * fps;
                k++) result.add((float) (140 * k + 20 - 7L * tick * fps) / (7 * fps));
        return result;
    }

    private static void installLr(
            AbstractClientPlayer player,
            Canon canonical,
            AnimationGraphState graph,
            float fraction) {
        RemoteLrCompat.install(
                player,
                new RemotePaPresentation.Frame(
                        graph,
                        canonical.stack.catalog(),
                        POLICY,
                        fraction,
                        fraction,
                        PaObserverNativeCases.vanilla(AnimationTypes.Pose.STANDING, false, false),
                        null,
                        null));
    }

    private static int[] model(AbstractClientPlayer player, float fraction) {
        var mesh =
                net.minecraft.client.model.PlayerModel.createMesh(
                        net.minecraft.client.model.geom.builders.CubeDeformation.NONE, false);
        var root =
                net.minecraft.client.model.geom.builders.LayerDefinition.create(mesh, 64, 64)
                        .bakeRoot();
        var model = new net.minecraft.client.model.PlayerModel<AbstractClientPlayer>(root, false);
        ((IAnimatedPlayer) player).playerAnimator_getAnimation().setTickDelta(fraction);
        model.prepareMobModel(player, 0, 0, fraction);
        model.setupAnim(player, 0, 0, fraction, 0, 0);
        var parts =
                List.of(
                        model.head,
                        model.body,
                        model.leftArm,
                        model.rightArm,
                        model.leftLeg,
                        model.rightLeg);
        var result = new int[36];
        int i = 0;
        for (var part : parts)
            for (float value :
                    new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot}) {
                assertTrue(Float.isFinite(value), "nonfinite model result");
                result[i++] = Float.floatToRawIntBits(value);
            }
        return result;
    }

    @Test
    void compatibilityHistoryIsRequiredBeforeInstall() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-compat-lifecycle",
                () -> {
                    try (var world = new World()) {
                        world.stack("bat", true, world.display("fixture:bat", BAT));
                        var player = player(AbstractClientPlayer.class, 450);
                        world.add(player);
                        world.input(0, In.stand("bat"));
                        var canon = new Canon(world.assets);
                        canon.select(world.held("bat"), In.stand("bat"));
                        var graph = canon.stack.freeze(AnimationGraphState.neutral());
                        assertThrows(
                                PaGraph.Unsupported.class,
                                () -> installLr(player, canon, graph, .5f));
                        assertFalse(RemotePaPresentation.driven(player));
                        RemoteLrCompat.attach(player);
                        installLr(player, canon, graph, .5f);
                        assertFalse(RemoteLrCompat.unseeded(player));
                        RemoteLrCompat.clearAll();
                        assertTrue(RemoteLrCompat.unseeded(player));
                        assertThrows(
                                PaGraph.Unsupported.class, () -> RemoteLrCompat.attach(player));
                        assertThrows(
                                PaGraph.Unsupported.class,
                                () -> RemoteLrCompat.layer(player, RemoteLrCompat.UPPER));
                        RemotePaPresentation.clear(player);
                    } finally {
                        RemoteLrCompat.clearAll();
                    }
                    OracleTransformAudit.requireConfig("blockfield-client-lrtactical.mixins.json");
                    return Map.of(
                            "missingAttachRefusesInstall",
                            true,
                            "lostViewRefusesNativeCallback",
                            true,
                            "boundary",
                            "LR install adapter; capsule dispatch remains U8");
                });
    }

    @Test
    void nativeOwnAttackHistorySurvivesLeavingTheGraph() {
        AnimSyncOracleRunner.run(
                "lr",
                "lr-own-metadata-history-native",
                () -> {
                    try (var world = new World()) {
                        String config =
                                "{\"animation\":\"fixture:lr/melee\",\"upper\":{\"idle\":\"x_shared\","
                                        + "\"walk\":\"u_walk\",\"hit\":[\"x_shared\"]},\"lower\":{}}";
                        world.stack("bat", true, world.display("fixture:own", config));
                        var player = player(AbstractClientPlayer.class, 475);
                        world.add(player);
                        world.input(0, In.stand("bat"));
                        world.attack(new Attack(0, "hit", 0, "fixture:own"));
                        for (int t = 1; t <= 19; t++) {
                            ((IAnimatedPlayer) player).getAnimationStack().tick();
                            world.input(
                                    0,
                                    new In(
                                            "bat",
                                            true,
                                            new Vec3(.1, 0, 0),
                                            false,
                                            false,
                                            Pose.STANDING,
                                            false,
                                            false));
                            onClientTick.invoke(null);
                        }
                        assertTrue(
                                describe(layer(player, RemoteLrCompat.UPPER))
                                        .startsWith("KAP(u_walk"));
                        world.input(0, In.stand("bat"));
                        onClientTick.invoke(null);
                        assertTrue(
                                (Boolean) attackActive.invoke(null, player, upperLayer()),
                                "own flag survives idle re-entry");
                        world.input(
                                0,
                                new In(
                                        "bat",
                                        true,
                                        new Vec3(.1, 0, 0),
                                        false,
                                        false,
                                        Pose.STANDING,
                                        false,
                                        false));
                        onClientTick.invoke(null);
                        assertTrue(
                                describe(layer(player, RemoteLrCompat.UPPER))
                                        .startsWith("KAP(x_shared"),
                                "own flag blocks walk");
                        OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                        return Map.of(
                                "nativeOwnFlagPersists",
                                true,
                                "blocksIdleAfterClipReentry",
                                true,
                                "evidenceScope",
                                "native characterization only; U4 P2-7 remains unresolved, no equality credit");
                    }
                });
    }

    // -- selector differential -----------------------------------------------------------------

    private static int selector(int fps) throws Exception {
        try (var world = new World()) {
            world.stack("bat", true, world.display("fixture:bat", BAT));
            world.stack("stick", false, null);
            world.stack("no_config", true, world.display("fixture:no_config", null));
            world.stack("no_path", true, world.display("fixture:no_path", NO_PATH));
            for (int i = 0; i < 4; i++) world.add(player(AbstractClientPlayer.class, 100 + i));
            var canon = new ArrayList<Canon>();
            for (int i = 0; i < 4; i++) canon.add(new Canon(world.assets));
            int compared = 0;
            var states = new HashMap<String, String>();
            for (int t = 0; t <= 48; t++) {
                for (int i = 0; i < 4; i++) world.input(i, selectorInput(i, t));
                for (var attack : selectorAttacks(t)) {
                    world.attack(attack);
                    canon.get(attack.player())
                            .attack(
                                    world.assets.display(attack.display()),
                                    attack.action(),
                                    attack.count());
                }
                for (int i = 0; i < 4; i++) {
                    if (t > 0) {
                        ((IAnimatedPlayer) world.players.get(i)).getAnimationStack().tick();
                        canon.get(i).stack.tick();
                    }
                }
                onClientTick.invoke(null);
                for (int i = 0; i < 4; i++) {
                    var in = selectorInput(i, t);
                    canon.get(i).select(world.held(in.item()), in);
                    compared += compare(world.players.get(i), canon.get(i));
                    var player = world.players.get(i);
                    states.put(i + "u" + t, describe(layer(player, RemoteLrCompat.UPPER)));
                    states.put(i + "l" + t, describe(layer(player, RemoteLrCompat.LOWER)));
                }
                // Native renders between ticks; canonical state has no render history.
                for (float fraction : frames(fps, t))
                    for (var player : world.players)
                        ((IAnimatedPlayer) player).getAnimationStack().setupAnim(fraction);
            }
            // The scenario must reach every native branch it claims, not compare empty layers.
            for (var expected :
                    Map.ofEntries(
                                    Map.entry("0u3", "KAP(atk_a"),
                                    Map.entry("0u7", "KAP(atk_a"),
                                    Map.entry("0u21", "KAP(atk_b"),
                                    Map.entry("0u31", "null"),
                                    Map.entry("0l6", "KAP(l_idle"),
                                    Map.entry("0l7", "KAP(l_walk"),
                                    Map.entry("0l9", "KAP(l_walk"),
                                    Map.entry("0l10", "KAP(l_run"),
                                    Map.entry("0l15", "KAP(l_walk"),
                                    Map.entry("1u12", "null"),
                                    Map.entry("1u20", "KAP(u_idle"),
                                    Map.entry("2u10", "null"),
                                    Map.entry("3u40", "KAP(u_idle"),
                                    Map.entry("3l40", "KAP(l_idle"))
                            .entrySet())
                assertTrue(
                        states.get(expected.getKey()).startsWith(expected.getValue()),
                        expected.getKey() + " was " + states.get(expected.getKey()));
            return compared;
        }
    }

    /** Fractions of the frames a native renderer draws between tick t and t+1 at this rate. */
    private static List<Float> frames(int fps, int t) {
        var result = new ArrayList<Float>();
        if (fps == 0) return result;
        // Frame k is at 20k/fps ticks; exact integer arithmetic, float only for the argument.
        for (long k = (long) Math.ceil(t * (double) fps / 20); 20 * k < (t + 1L) * fps; k++)
            result.add((float) (20 * k - (long) t * fps) / fps);
        return result;
    }

    /** Canonical side of one player: the shared seven-root stack and LrPose. */
    private static final class Canon {
        final PaGraph.Stack stack;
        final AtomicLong sequence = new AtomicLong();

        Canon(LrAssets assets) {
            stack =
                    PaGraph.Stack.create(
                            assets.catalog(), POLICY, PaGraphNativeCases.SOURCES, winner, 1);
        }

        void attack(LrAssets.Display display, String action, int count) {
            LrPose.attack(stack, display, action, count, sequence::incrementAndGet);
        }

        void select(LrAssets.Display display, In in) {
            var pose =
                    in.pose() == Pose.SLEEPING
                            ? AnimationTypes.Pose.SLEEPING
                            : in.pose() == Pose.FALL_FLYING
                                    ? AnimationTypes.Pose.FALL_FLYING
                                    : AnimationTypes.Pose.STANDING;
            LrPose.tick(
                    stack,
                    display,
                    LrPose.suppressed(pose, in.climb(), in.swim()),
                    LrPose.movement(
                            in.ground(),
                            in.delta().y,
                            in.delta().horizontalDistanceSqr(),
                            in.crouch(),
                            in.sprint()),
                    sequence::incrementAndGet);
        }
    }

    private static int compare(AbstractClientPlayer player, Canon canon) throws Exception {
        int compared = 0;
        var frozen = canon.stack.freeze(AnimationGraphState.neutral());
        var upper = layer(player, RemoteLrCompat.UPPER);
        var lower = layer(player, RemoteLrCompat.LOWER);
        var rotation = layer(player, RemoteLrCompat.ROTATION);
        var scratch =
                PaGraph.Stack.materialize(
                        frozen, canon.stack.catalog(), POLICY, PaGraphNativeCases.SOURCES);
        assertEquals(describe(upper), describe(scratch.layer(LayerTag.PA_40)), "upper topology");
        assertEquals(describe(lower), describe(scratch.layer(LayerTag.PA_41)), "lower topology");
        assertEquals(describe(rotation), describe(scratch.layer(winner)), "rotation topology");
        for (float fraction : FRACTIONS) {
            var sampled =
                    PaGraph.Stack.materialize(
                            frozen, canon.stack.catalog(), POLICY, PaGraphNativeCases.SOURCES);
            sampled.sample(fraction, fraction);
            for (var pair :
                    List.of(Map.entry(upper, LayerTag.PA_40), Map.entry(lower, LayerTag.PA_41))) {
                pair.getKey().setupAnim(fraction);
                for (String part : PARTS)
                    for (var type : TransformType.values()) {
                        var base = new Vec3f(.3f, -.2f, .5f);
                        PaGraphNativeCases.bits(
                                pair.getKey().get3DTransform(part, type, fraction, base),
                                sampled.layer(pair.getValue())
                                        .get3DTransform(part, type, fraction, base));
                        compared++;
                    }
            }
        }
        return compared;
    }

    /** Native-visible topology: actor clip/name/tick/state and non-adjustment modifiers. */
    private static String describe(ModifierLayer<IAnimation> layer) throws Exception {
        var out = new StringBuilder(actor(layer.getAnimation()));
        for (AbstractModifier modifier : modifiers(layer)) {
            if (modifier instanceof AdjustmentModifier) continue;
            out.append(" | ").append(modifier.getClass().getSimpleName());
            if (modifier
                    instanceof dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier fade)
                out.append(" begin=").append(actor(beginAnimation(fade)));
        }
        return out.toString();
    }

    private static String actor(IAnimation animation) throws Exception {
        if (animation == null) return "null";
        if (animation instanceof KeyframeAnimationPlayer player)
            return "KAP("
                    + player.getData().extraData.get("name")
                    + ",tick="
                    + player.getCurrentTick()
                    + ",active="
                    + player.isActive()
                    + ",fp="
                    + player.getFirstPersonMode(0)
                    + ")";
        String name = animation.getClass().getSimpleName();
        if (name.equals("FrozenAnimationSnapshot")) {
            var source = animation.getClass().getDeclaredField("source");
            source.setAccessible(true);
            return "Frozen(" + actor((IAnimation) source.get(animation)) + ")";
        }
        return name;
    }

    // -- compatibility view --------------------------------------------------------------------

    private static final String REMOTE =
            "{\"animation\":\"fixture:lr/melee\",\"upper\":{\"idle\":\"u_idle\",\"walk\":\"u_walk\","
                    + "\"hit\":[\"x_shared\"]},\"lower\":{\"idle\":\"l_idle\",\"walk\":\"l_walk\"}}";
    private static final String LOCAL =
            "{\"animation\":\"fixture:lr/melee\",\"upper\":{\"idle\":\"x_shared\",\"walk\":\"u_walk\"},"
                    + "\"lower\":{\"idle\":\"l_idle\",\"walk\":\"l_walk\"}}";

    private static In compatInput(int player, int t) {
        String item = player == 0 ? "local" : "remote";
        boolean walk = (t / 7 + player) % 2 == 1;
        return new In(
                item,
                true,
                walk ? new Vec3(.1, 0, 0) : Vec3.ZERO,
                false,
                false,
                Pose.STANDING,
                false,
                false);
    }

    /** Observation trace of world N (all native) or D (remotes 1/2 driven while attached). */
    private static List<String> compat(boolean driven) throws Exception {
        return compat(driven, 0);
    }

    static Map<String, Object> mixedLocalNative() throws Exception {
        if (onClientTick == null) bootstrap();
        var samples = new LinkedHashMap<String, Integer>();
        for (int fps : new int[] {30, 60, 144, 240}) {
            var nativeTrace = compat(false, fps);
            var drivenTrace = compat(true, fps);
            assertEquals(
                    nativeTrace,
                    drivenTrace,
                    "full local native render/metadata at " + fps + " FPS");
            int rendered =
                    (int)
                            nativeTrace.stream()
                                    .filter(row -> row.startsWith("local-render:"))
                                    .count();
            assertTrue(rendered > 0, "required cohort has evaluated evidence");
            samples.put(String.valueOf(fps), rendered);
        }
        return Map.of(
                "samplesPerFps",
                samples,
                "worlds",
                2,
                "ticksPerWorld",
                61,
                "comparison",
                "bit-identical local MAIN root/parts/48 corners and shared metadata; native remotes versus compatibility views");
    }

    private static List<String> compat(boolean driven, int renderFps) throws Exception {
        var trace = new ArrayList<String>();
        try (var world = new World();
                var renderer =
                        renderFps == 0
                                ? null
                                : new MixedRendererOracleFixture.Env(
                                        world.minecraft, world.level)) {
            world.stack("local", true, world.display("fixture:local", LOCAL));
            world.stack("remote", true, world.display("fixture:remote", REMOTE));
            var local =
                    renderFps == 0
                            ? player(LocalPlayer.class, 200)
                            : MixedRendererOracleFixture.player(
                                    LocalPlayer.class, 200, world.level, false);
            world.add(local);
            world.add(player(AbstractClientPlayer.class, 201));
            world.add(player(AbstractClientPlayer.class, 202));
            assertThrows(IllegalArgumentException.class, () -> RemoteLrCompat.attach(local));
            boolean[] attached = new boolean[3];
            for (int t = 0; t <= 60; t++) {
                if (driven && t == 0) attach(world, 1, attached);
                if (driven && t == 10) attach(world, 2, attached);
                if (driven && t == 41)
                    for (int i = 1; i <= 2; i++) {
                        RemoteLrCompat.detach(world.players.get(i));
                        attached[i] = false;
                    }
                for (int i = 0; i < 3; i++) world.input(i, compatInput(i, t));
                if (t == 5 || t == 26) world.attack(new Attack(1, "hit", 0, "fixture:remote"));
                if (t == 16) world.attack(new Attack(2, "hit", 0, "fixture:remote"));
                if (t > 0)
                    for (int i = 0; i < 3; i++) {
                        var player = world.players.get(i);
                        if (attached[i]) RemoteLrCompat.tick(player);
                        else ((IAnimatedPlayer) player).getAnimationStack().tick();
                    }
                onClientTick.invoke(null);
                for (int i = 0; i < 3; i++) {
                    var player = world.players.get(i);
                    if (attached[i]) suppressed(player);
                    trace.add(t + ":" + i + ":" + observe(player, attached[i]));
                }
                trace.add(
                        t
                                + ":local-attack-active="
                                + attackActive.invoke(null, local, upperLayer()));
                trace.add(t + ":shared=" + metadata(world.clips));
                if (renderer != null)
                    for (float fraction : modelFrames(renderFps, t))
                        trace.add(
                                "local-render:"
                                        + t
                                        + ":"
                                        + fraction
                                        + ":"
                                        + renderer.nativeLocal(local, fraction));
                if (t > 0) {
                    var actor = layer(local, RemoteLrCompat.UPPER).getAnimation();
                    if (actor instanceof KeyframeAnimationPlayer player)
                        assertEquals(FirstPersonMode.DISABLED, player.getFirstPersonMode(0));
                }
            }
            assertNull(RemoteLrCompat.layer(local, RemoteLrCompat.UPPER));
        } finally {
            RemoteLrCompat.clearAll();
        }
        // The remote attack flags the clip the local player idles with: native cross-entity state.
        assertTrue(trace.stream().anyMatch(o -> o.endsWith(":local-attack-active=true")));
        return trace;
    }

    private static void attach(World world, int index, boolean[] attached) {
        RemoteLrCompat.attach(world.players.get(index));
        assertFalse(RemoteLrCompat.unseeded(world.players.get(index)));
        attached[index] = true;
    }

    /** A driven remote's real LR layers keep only the native adjustment: no LR geometry. */
    private static void suppressed(AbstractClientPlayer player) {
        for (var id :
                List.of(RemoteLrCompat.UPPER, RemoteLrCompat.LOWER, RemoteLrCompat.ROTATION)) {
            var real = layer(player, id);
            assertNull(real.getAnimation(), "driven real layer " + id);
            for (AbstractModifier modifier : modifiers(real))
                assertInstanceOf(AdjustmentModifier.class, modifier);
        }
    }

    private static String observe(AbstractClientPlayer player, boolean attached) throws Exception {
        var out = new StringBuilder();
        for (var id :
                List.of(RemoteLrCompat.UPPER, RemoteLrCompat.LOWER, RemoteLrCompat.ROTATION)) {
            var layer = attached ? RemoteLrCompat.layer(player, id) : layer(player, id);
            out.append(describe(layer)).append(" ; ");
            if (id.equals(RemoteLrCompat.ROTATION)) continue;
            for (float fraction : FRACTIONS) {
                layer.setupAnim(fraction);
                for (String part : PARTS) {
                    var value =
                            layer.get3DTransform(
                                    part,
                                    TransformType.ROTATION,
                                    fraction,
                                    new Vec3f(.3f, -.2f, .5f));
                    out.append(Integer.toHexString(Float.floatToRawIntBits(value.getX())))
                            .append(',')
                            .append(Integer.toHexString(Float.floatToRawIntBits(value.getY())))
                            .append(',')
                            .append(Integer.toHexString(Float.floatToRawIntBits(value.getZ())))
                            .append(' ');
                }
            }
        }
        return out.toString();
    }

    private static String metadata(Map<String, KeyframeAnimation> clips) {
        var out = new TreeMap<String, String>();
        clips.forEach((name, clip) -> out.put(name, new TreeMap<>(clip.extraData).toString()));
        return out.toString();
    }

    private static Object upperLayer() throws Exception {
        return Class.forName(LR + "compat.player_animator.PlayerAnimatorIntegration$AnimationLayer")
                .getField("UPPER")
                .get(null);
    }

    // -- world ---------------------------------------------------------------------------------

    /** One client world: real factory players, the native handlers and the shared clip cache. */
    private static final class World implements AutoCloseable {
        final MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class);
        final MockedStatic<?> api;
        final ClientLevel level = mock(ClientLevel.class);
        final Minecraft minecraft = mock(Minecraft.class);
        final List<AbstractClientPlayer> players = new ArrayList<>();
        final Map<String, ItemStack> stacks = new HashMap<>();
        final Map<ItemStack, Object> held = new IdentityHashMap<>();
        final Map<ResourceLocation, Object> displays = new HashMap<>();
        final Map<Object, LrAssets.Display> retained = new IdentityHashMap<>();
        final Map<String, KeyframeAnimation> clips = clips();
        final LrAssets assets = new LrAssets(new PaGraph.Catalog(1));
        final HashMap<ResourceLocation, HashMap<String, KeyframeAnimation>> cache;
        final HashMap<String, KeyframeAnimation> previous;

        @SuppressWarnings("unchecked")
        World() throws Exception {
            this(null);
        }

        @SuppressWarnings("unchecked")
        World(KeyframeAnimation empty) throws Exception {
            if (empty != null) clips.put("empty", empty);
            var dispatcher =
                    mock(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class);
            var hands = mock(net.minecraft.client.renderer.ItemInHandRenderer.class);
            doReturn(ItemStack.EMPTY)
                    .when((com.tacz.guns.api.client.other.KeepingItemRenderer) hands)
                    .getCurrentItem();
            doReturn(hands).when(dispatcher).getItemInHandRenderer();
            doReturn(dispatcher).when(minecraft).getEntityRenderDispatcher();
            var timer = mock(net.minecraft.client.DeltaTracker.class);
            doReturn(timer).when(minecraft).getTimer();
            doReturn(.5f).when(timer).getGameTimeDeltaPartialTick(anyBoolean());
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            Field level =
                    OracleReflection.field(
                            Minecraft.class,
                            "level",
                            "Lnet/minecraft/client/multiplayer/ClientLevel;");
            level.setAccessible(true);
            level.set(minecraft, this.level);
            doReturn(true).when(this.level).isClientSide();
            doAnswer(ignored -> List.copyOf(players)).when(this.level).players();
            doAnswer(
                            call ->
                                    players.stream()
                                            .filter(p -> p.getId() == (int) call.getArgument(0))
                                            .findFirst()
                                            .orElse(null))
                    .when(this.level)
                    .getEntity(anyInt());
            api = mockStatic(stackLookup.getDeclaringClass());
            api.when(() -> stackLookup.invoke(null, any(ItemStack.class)))
                    .thenAnswer(call -> Optional.ofNullable(held.get(call.getArgument(0))));
            api.when(() -> idLookup.invoke(null, any(ResourceLocation.class)))
                    .thenAnswer(call -> Optional.ofNullable(displays.get(call.getArgument(0))));
            var field = PlayerAnimatorAssetManager.class.getDeclaredField("animations");
            field.setAccessible(true);
            cache =
                    (HashMap<ResourceLocation, HashMap<String, KeyframeAnimation>>)
                            field.get(PlayerAnimatorAssetManager.get());
            // The native loader keys clips by their lower-cased name in one shared map.
            previous = cache.put(FILE, new HashMap<>(clips));
        }

        Object display(String id, String config) throws Exception {
            var display = mock(displayType);
            Object parsed = config == null ? null : new Gson().fromJson(config, configType);
            thirdPerson.invoke(doReturn(parsed).when(display));
            displays.put(ResourceLocation.parse(id), display);
            retained.put(display, RemoteLrAssets.retain(assets, id, display));
            return display;
        }

        void stack(String name, boolean melee, Object display) {
            var item =
                    melee
                            ? mock(Item.class, withSettings().extraInterfaces(weapon))
                            : mock(Item.class);
            var stack = mock(ItemStack.class);
            doReturn(item).when(stack).getItem();
            stacks.put(name, stack);
            if (display != null) held.put(stack, display);
        }

        LrAssets.Display held(String item) {
            Object display = held.get(stacks.get(item));
            return display == null ? null : retained.get(display);
        }

        void add(AbstractClientPlayer player) {
            doReturn(level).when(player).level();
            players.add(player);
            // A constructor-free render player supplies its own single-player level stub.
            doAnswer(ignored -> List.copyOf(players)).when(level).players();
        }

        void input(int index, In in) {
            var player = players.get(index);
            doReturn(stacks.get(in.item())).when(player).getMainHandItem();
            doReturn(in.ground()).when(player).onGround();
            doReturn(in.delta()).when(player).getDeltaMovement();
            doReturn(in.crouch()).when(player).isCrouching();
            doReturn(in.sprint()).when(player).isSprinting();
            doReturn(in.pose()).when(player).getPose();
            doReturn(in.climb()).when(player).onClimbable();
            doReturn(in.swim()).when(player).isSwimming();
        }

        void attack(Attack attack) throws Exception {
            var event = mock(eventType);
            var action = mock(actionType);
            actionType.getMethod("getId").invoke(doReturn(attack.action()).when(action));
            eventType
                    .getMethod("getPlayerId")
                    .invoke(doReturn(players.get(attack.player()).getId()).when(event));
            eventType.getMethod("getState").invoke(doReturn(action).when(event));
            eventType.getMethod("getActionCount").invoke(doReturn(attack.count()).when(event));
            eventType
                    .getMethod("getAnimationId")
                    .invoke(doReturn(ResourceLocation.parse(attack.display())).when(event));
            onMelee.invoke(null, event);
        }

        @Override
        public void close() {
            if (previous == null) cache.remove(FILE);
            else cache.put(FILE, previous);
            api.close();
            singleton.close();
        }
    }

    // -- players -------------------------------------------------------------------------------

    private static <T extends AbstractClientPlayer> T player(Class<T> type, int id)
            throws Exception {
        var player = mock(type, CALLS_REAL_METHODS);
        var stack = new AnimationStack();
        var data = new HashMap<ResourceLocation, IAnimation>();
        int bound = 0;
        for (Field field : Player.class.getDeclaredFields()) {
            if (field.getType() == AnimationStack.class) {
                field.setAccessible(true);
                field.set(player, stack);
                bound++;
            } else if (field.getType() == AnimationApplier.class) {
                field.setAccessible(true);
                field.set(player, new AnimationApplier(stack));
                bound++;
            } else if (field.getName().equals("modAnimationData")) {
                field.setAccessible(true);
                field.set(player, data);
                bound++;
            }
        }
        assertEquals(3, bound, "PA player mixin fields");
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.prepareAnimations(player, stack, data);
        doReturn(id).when(player).getId();
        doReturn(ItemStack.EMPTY).when(player).getOffhandItem();
        doReturn(ItemStack.EMPTY).when(player).getItemBySlot(any());
        doReturn(net.minecraft.world.entity.HumanoidArm.RIGHT).when(player).getMainArm();
        doReturn(false).when(player).isBaby();
        doReturn(false).when(player).isSpectator();
        doReturn(false).when(player).isUsingItem();
        doReturn(net.minecraft.world.InteractionHand.MAIN_HAND).when(player).getUsedItemHand();
        doReturn(ItemStack.EMPTY).when(player).getUseItem();
        doReturn(false).when(player).isPassenger();
        doReturn(false).when(player).isFallFlying();
        doReturn(false).when(player).isVisuallySwimming();
        doReturn(false).when(player).isAutoSpinAttack();
        doReturn(0).when(player).getFallFlyingTicks();
        doReturn(0f).when(player).getSwimAmount(anyFloat());
        doReturn(0f).when(player).getAttackAnim(anyFloat());
        return player;
    }

    private static boolean probe(ResourceLocation id) throws Exception {
        var player = player(AbstractClientPlayer.class, 1);
        return ((IAnimatedPlayer) player).playerAnimator_getAnimation(id) != null;
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> layer(
            AbstractClientPlayer player, ResourceLocation id) {
        var layer =
                (ModifierLayer<IAnimation>)
                        ((IAnimatedPlayer) player).playerAnimator_getAnimation(id);
        assertNotNull(layer, "registered layer " + id);
        return layer;
    }

    @SuppressWarnings("unchecked")
    private static LayerTag layerTag(AnimationStack stack, IAnimation layer) throws Exception {
        var field = AnimationStack.class.getDeclaredField("layers");
        field.setAccessible(true);
        for (var entry :
                (List<dev.kosmx.playerAnim.core.util.Pair<Integer, IAnimation>>) field.get(stack))
            if (entry.getRight() == layer)
                return entry.getLeft() == 42 ? LayerTag.PA_42 : LayerTag.PA_96;
        throw new AssertionError("rotation layer is not a stack root");
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractModifier> modifiers(ModifierLayer<?> layer) {
        try {
            var field = ModifierLayer.class.getDeclaredField("modifiers");
            field.setAccessible(true);
            return (List<AbstractModifier>) field.get(layer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static IAnimation beginAnimation(
            dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier fade) {
        try {
            var field =
                    dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier.class
                            .getDeclaredField("beginAnimation");
            field.setAccessible(true);
            return (IAnimation) field.get(fade);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
