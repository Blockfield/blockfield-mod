package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.AnimationTypes.AdjustmentSource;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.TaczPose;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.guns.compat.playeranimator.animation.AnimationManager;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U3 native differential: the pinned TaCZ {@code AnimationManager} public play/stop methods on a
 * factory-registered player (production mixins applied) against the shared canonical reducer
 * ({@link PaGraph.Stack}), sampled raw-bitwise after every integer tick and after zero-tick event
 * application; then the driven presentation transplant into a second player's own native layers.
 */
class TaczRemoteOracleFixture {
    private static final AnimationPolicy POLICY =
            new AnimationPolicy(
                    0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    private static final ResourceLocation RIFLE =
            ResourceLocation.parse("tacz:rifle_default.player_animation");
    private static final List<String> PARTS =
            List.of("head", "torso", "body", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static final float[] FRACTIONS = {0, 1f / 65536, 0.25f, 0.5f, 1 - 1f / 65536};
    private static boolean registered;
    private static Map<String, KeyframeAnimation> clips;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        synchronized (TaczRemoteOracleFixture.class) {
            if (!registered) {
                // Production mod order registers LR first, so TaCZ's rotation factory wins.
                Class.forName(
                                "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration")
                        .getMethod("init")
                        .invoke(null);
                PlayerAnimatorCompat.init();
                registered = true;
            }
        }
        clips = new LinkedHashMap<>();
        String path =
                "/assets/tacz/custom/tacz_default_gun/assets/tacz/player_animator/"
                        + "rifle_default.player_animation.json";
        try (var input = TaczRemoteOracleFixture.class.getResourceAsStream(path)) {
            assertNotNull(input, "pinned TaCZ default pack clips");
            JsonObject json =
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var clip :
                    dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer.deserialize(json))
                clips.put((String) clip.extraData.get("name"), clip);
        }
        Field animations = PlayerAnimatorAssetManager.class.getDeclaredField("animations");
        animations.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map =
                (HashMap<ResourceLocation, HashMap<String, KeyframeAnimation>>)
                        animations.get(PlayerAnimatorAssetManager.get());
        map.put(RIFLE, new HashMap<>(clips));
    }

    static AbstractClientPlayer player() throws Exception {
        var player = mock(AbstractClientPlayer.class, CALLS_REAL_METHODS);
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
        doReturn(false).when(player).isSwimming();
        doReturn(Pose.STANDING).when(player).getPose();
        doReturn(ItemStack.EMPTY).when(player).getMainHandItem();
        doReturn(null).when(player).getVehicle();
        doReturn(12f).when(player).getXRot();
        // R-facing is resolved before the scope; old and current collapse to it.
        set(player, LivingEntity.class, "yBodyRotO", 31);
        set(player, LivingEntity.class, "yBodyRot", 31);
        set(player, LivingEntity.class, "yHeadRotO", 70);
        set(player, LivingEntity.class, "yHeadRot", 70);
        set(player, Entity.class, "xRotO", 12);
        return player;
    }

    private static void set(Player player, Class<?> owner, String name, float value)
            throws Exception {
        var field = OracleReflection.field(owner, name, "F");
        field.setAccessible(true);
        field.setFloat(player, value);
    }

    /** The player's own native rotation functions (what {@link RemotePaPresentation} binds). */
    @SuppressWarnings("unchecked")
    private static PaGraph.Sources nativeSources(AbstractClientPlayer player) throws Exception {
        Field layers = AnimationStack.class.getDeclaredField("layers");
        layers.setAccessible(true);
        Field modifiers = ModifierLayer.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        Field source = AdjustmentModifier.class.getDeclaredField("source");
        source.setAccessible(true);
        var functions = new HashMap<Integer, Object>();
        for (var entry :
                (List<Pair<Integer, IAnimation>>)
                        layers.get(((IAnimatedPlayer) player).getAnimationStack())) {
            if (entry.getLeft() != 42 && entry.getLeft() != 96) continue;
            var first = ((List<AbstractModifier>) modifiers.get(entry.getRight())).getFirst();
            functions.put(entry.getLeft(), source.get(first));
        }
        return id ->
                (Function<String, Optional<AdjustmentModifier.PartModifier>>)
                        functions.get(id == AdjustmentSource.LR_BODY_POSE ? 42 : 96);
    }

    static RemotePaPresentation.Frame frame(
            AnimationGraphState graph,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            float a,
            float r,
            float aim,
            boolean fixedHand,
            String fallback,
            boolean pa) {
        return frame(
                graph,
                catalog,
                policy,
                a,
                r,
                aim,
                fixedHand,
                fallback,
                pa,
                com.lunares.blockfield.oracle.PaObserverNativeCases.vanilla(
                        com.lunares.blockfield.common.combat.AnimationTypes.Pose.STANDING,
                        false,
                        false));
    }

    static RemotePaPresentation.Frame frame(
            AnimationGraphState graph,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            float a,
            float r,
            float aim,
            boolean fixedHand,
            String fallback,
            boolean pa,
            com.lunares.blockfield.common.combat.AnimationState.Vanilla vanilla) {
        var assets =
                new com.lunares.blockfield.common.combat.TaczAssets(
                        catalog, Map.of("fixture:gun", "fixture:" + fallback + fixedHand));
        var display =
                assets.register(
                        "fixture:" + fallback + fixedHand,
                        RIFLE.toString(),
                        fixedHand,
                        fallback,
                        pa,
                        pa ? clips : Map.of());
        var tacz =
                new com.lunares.blockfield.common.combat.AnimationState.Tacz(
                        pa
                                ? com.lunares.blockfield.common.combat.AnimationTypes.EvaluatorKind
                                        .PA
                                : com.lunares.blockfield.common.combat.AnimationTypes.EvaluatorKind
                                        .NATIVE_FALLBACK,
                        catalog.named("tacz.gun", "fixture:gun"),
                        null,
                        display.ref(),
                        fixedHand,
                        aim,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        com.lunares.blockfield.common.combat.AnimationTypes.Outcome.NO_CHANGE);
        return new RemotePaPresentation.Frame(graph, catalog, policy, a, r, vanilla, tacz, assets);
    }

    private interface Script {
        void tick(int t) throws Exception;
    }

    private static void scenario(int ticks, Script nativeTick, Script canonicalTick)
            throws Exception {
        for (int t = 0; t < ticks; t++) {
            nativeTick.tick(t);
            canonicalTick.tick(t);
        }
    }

    @Test
    void nativePublicPlayMethodsMatchTheCanonicalReducer() {
        AnimSyncOracleRunner.run(
                "tacz",
                "public-play-methods-canonical",
                () -> {
                    var result = new LinkedHashMap<String, Object>();
                    try (var singleton = mockStatic(Minecraft.class);
                            var displayApi = mockStatic(TimelessAPI.class);
                            var staminaApi = mockStatic(com.alrex.parcool.api.Stamina.class)) {
                        // Production ExhaustedPlayerPoseMixin reads the shooter's ParCool stamina.
                        var stamina = mock(com.alrex.parcool.api.Stamina.class);
                        staminaApi
                                .when(() -> com.alrex.parcool.api.Stamina.get(any(Player.class)))
                                .thenReturn(stamina);
                        var minecraft = mock(Minecraft.class);
                        var timer = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(timer);
                        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                        var display = mock(GunDisplayInstance.class);
                        when(display.getPlayerAnimator3rd()).thenReturn(RIFLE);
                        displayApi
                                .when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                .thenReturn(Optional.of(display));
                        var player = player();
                        var sources = nativeSources(player);
                        var catalog = new PaGraph.Catalog(0);
                        var canonical =
                                PaGraph.Stack.create(catalog, POLICY, sources, LayerTag.PA_96, 1);
                        int[] counts = new int[3]; // compared, singular limits, events
                        AnimationGraphState[] graph = new AnimationGraphState[1];
                        var nativeStack = ((IAnimatedPlayer) player).getAnimationStack();
                        Script nativeTick =
                                t -> {
                                    if (t > 0) nativeStack.tick();
                                    nativeOps(player, display, t);
                                };
                        Script canonicalTick =
                                t -> {
                                    if (t > 0) canonical.tick();
                                    canonicalOps(canonical, t);
                                    graph[0] = canonical.freeze(AnimationGraphState.neutral());
                                    compare(player, graph[0], catalog, sources, timer, counts);
                                    if (t == 5 || t == 12 || t == 30 || t == 42) counts[2]++;
                                };
                        scenario(64, nativeTick, canonicalTick);
                        result.put("ticks", 64);
                        result.put("rawBitComparisons", counts[0]);
                        result.put("singularLimitSamples", counts[1]);
                        result.put("zeroClientTickEventSamples", counts[2]);
                        result.put(
                                "singularMeaning",
                                "native 0/0 fade-in of a freshly installed empty rotation clip at"
                                        + " fraction 0; both evaluators use its right-hand limit,"
                                        + " which native fractions 2^-16 and 2^-24 converge to");
                        result.put(
                                "schedule",
                                "one native rotation reinstall per canonical tick (native per"
                                        + " render frame is the deterministic replacement)");
                        RemotePaPresentation.clearAll();
                        presentation(display, timer, catalog, result);
                    }
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    OracleTransformAudit.requireConfig("lrtactical.mixins.json");
                    OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return result;
                });
    }

    @Test
    void nativeSelectionAndMissingProfiles() {
        AnimSyncOracleRunner.run(
                "tacz",
                "selection-missing-profile",
                () -> {
                    var player = mock(AbstractClientPlayer.class);
                    var display = mock(GunDisplayInstance.class);
                    var abilities = new net.minecraft.world.entity.player.Abilities();
                    when(player.getAbilities()).thenReturn(abilities);
                    String[] selected = new String[2];
                    int cases = 0;
                    try (var manager = mockStatic(AnimationManager.class, CALLS_REAL_METHODS)) {
                        manager.when(
                                        () ->
                                                AnimationManager.playLoopAnimation(
                                                        eq(player),
                                                        eq(display),
                                                        any(ResourceLocation.class),
                                                        anyString()))
                                .thenAnswer(
                                        call -> {
                                            selected[
                                                            call.getArgument(2)
                                                                            .equals(
                                                                                    PlayerAnimatorCompat
                                                                                            .LOWER_ANIMATION)
                                                                    ? 0
                                                                    : 1] =
                                                    call.getArgument(3);
                                            return null;
                                        });
                        for (Pose pose : List.of(Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING))
                            for (int mask = 0; mask < 32; mask++)
                                for (float speed : new float[] {0, Math.nextDown(.05f), .05f, .75f})
                                    for (float aim : new float[] {0, .5f}) {
                                        boolean swimming = (mask & 1) != 0,
                                                riding = (mask & 2) != 0,
                                                grounded = (mask & 4) != 0,
                                                flying = (mask & 8) != 0,
                                                sprint = (mask & 16) != 0;
                                        when(player.getPose()).thenReturn(pose);
                                        when(player.isSwimming()).thenReturn(swimming);
                                        when(player.getVehicle())
                                                .thenReturn(riding ? mock(Entity.class) : null);
                                        when(player.onGround()).thenReturn(grounded);
                                        abilities.flying = flying;
                                        when(player.isSprinting()).thenReturn(sprint);
                                        when(((com.tacz.guns.api.entity.IGunOperator) player)
                                                        .getSynAimingProgress())
                                                .thenReturn(aim);
                                        selected[0] = selected[1] = null;
                                        AnimationManager.playLowerAnimation(player, display, speed);
                                        AnimationManager.playLoopUpperAnimation(
                                                player, display, speed);
                                        var expected =
                                                TaczPose.select(
                                                        com.lunares.blockfield.common.combat
                                                                .AnimationTypes.Pose.valueOf(
                                                                pose.name()),
                                                        swimming,
                                                        riding,
                                                        grounded,
                                                        flying,
                                                        sprint,
                                                        speed,
                                                        aim);
                                        assertEquals(expected.lower(), selected[0]);
                                        assertEquals(expected.upper(), selected[1]);
                                        cases++;
                                    }
                    }
                    when(display.getPlayerAnimator3rd()).thenReturn(null);
                    assertFalse(AnimationManager.hasPlayerAnimator3rd(display));
                    when(display.getPlayerAnimator3rd())
                            .thenReturn(ResourceLocation.parse("fixture:missing"));
                    assertFalse(AnimationManager.hasPlayerAnimator3rd(display));
                    when(display.getPlayerAnimator3rd()).thenReturn(RIFLE);
                    assertTrue(AnimationManager.hasPlayerAnimator3rd(display));
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return Map.of(
                            "nativeSelectionCases",
                            cases,
                            "missingProfilesChooseFallback",
                            true,
                            "threshold",
                            "float widened to double before >0.05");
                });
    }

    @Test
    void fallbackHoldAimFormulasAndAuthoritativeAimMatchNative() {
        AnimSyncOracleRunner.run(
                "tacz",
                "fallback-hold-aim",
                () -> {
                    com.tacz.guns.api.client.other.ThirdPersonManager.registerDefault();
                    var method =
                            com.tacz.guns.client.animation.third.InnerThirdPersonManager.class
                                    .getDeclaredMethod(
                                            "playVanillaAnimation",
                                            LivingEntity.class,
                                            net.minecraft.client.model.geom.ModelPart.class,
                                            net.minecraft.client.model.geom.ModelPart.class,
                                            net.minecraft.client.model.geom.ModelPart.class,
                                            net.minecraft.client.model.geom.ModelPart.class,
                                            com.tacz.guns.api.entity.IGunOperator.class,
                                            GunDisplayInstance.class);
                    method.setAccessible(true);
                    int comparisons = 0;
                    var player = player();
                    var operator = (com.tacz.guns.api.entity.IGunOperator) player;
                    var display = mock(GunDisplayInstance.class);
                    var fallbackCatalog = new PaGraph.Catalog(0);
                    var neutral =
                            PaGraph.Stack.create(
                                            fallbackCatalog,
                                            POLICY,
                                            id -> part -> Optional.empty(),
                                            LayerTag.PA_96,
                                            1)
                                    .freeze(AnimationGraphState.neutral());
                    for (String kind : List.of("default", "minigun", "unregistered"))
                        for (float aim : new float[] {-1, -0f, 0, 1f / 65536, .3f, 1})
                            for (float facing : new float[] {-3.14159f, -0f, 1.2f}) {
                                when(display.getThirdPersonAnimation()).thenReturn(kind);
                                doReturn(aim).when(operator).getSynAimingProgress();
                                var nativeParts =
                                        new java.util.ArrayList<
                                                net.minecraft.client.model.geom.ModelPart>();
                                float[] expected = new float[36];
                                for (int part = 0; part < 6; part++) {
                                    var nativePart =
                                            new net.minecraft.client.model.geom.ModelPart(
                                                    List.of(), Map.of());
                                    nativePart.setPos(part * .75f - 2, part + .25f, -part / 3f);
                                    nativePart.setRotation(facing, -facing, .125f);
                                    nativeParts.add(nativePart);
                                    writePart(expected, part, nativePart);
                                }
                                float[] baseline = expected.clone();
                                TaczPose.fallback(expected, aim, kind);
                                method.invoke(
                                        null,
                                        player,
                                        nativeParts.get(3),
                                        nativeParts.get(2),
                                        nativeParts.get(1),
                                        nativeParts.get(0),
                                        operator,
                                        display);
                                float[] actual = new float[36];
                                for (int part = 0; part < 6; part++)
                                    writePart(actual, part, nativeParts.get(part));
                                for (int i = 0; i < 36; i++) {
                                    assertEquals(
                                            Float.floatToRawIntBits(expected[i]),
                                            Float.floatToRawIntBits(actual[i]),
                                            kind + " aim=" + aim + " slot=" + i);
                                    comparisons++;
                                }
                                RemotePaPresentation.install(
                                        player,
                                        frame(
                                                neutral,
                                                fallbackCatalog,
                                                POLICY,
                                                .85f,
                                                .85f,
                                                aim,
                                                false,
                                                kind,
                                                false));
                                try {
                                    // The authoritative step wins even when the native live value
                                    // disagrees.
                                    doReturn(aim > 0 ? 0f : 1f)
                                            .when(operator)
                                            .getSynAimingProgress();
                                    for (int part = 0; part < 6; part++) {
                                        int i = part * 6;
                                        nativeParts
                                                .get(part)
                                                .setPos(
                                                        baseline[i],
                                                        baseline[i + 1],
                                                        baseline[i + 2]);
                                        nativeParts
                                                .get(part)
                                                .setRotation(
                                                        baseline[i + 3],
                                                        baseline[i + 4],
                                                        baseline[i + 5]);
                                    }
                                    method.invoke(
                                            null,
                                            player,
                                            nativeParts.get(3),
                                            nativeParts.get(2),
                                            nativeParts.get(1),
                                            nativeParts.get(0),
                                            operator,
                                            display);
                                    for (int part = 0; part < 6; part++)
                                        writePart(actual, part, nativeParts.get(part));
                                    for (int i = 0; i < 36; i++) {
                                        assertEquals(
                                                Float.floatToRawIntBits(expected[i]),
                                                Float.floatToRawIntBits(actual[i]),
                                                "authoritative aim slot=" + i);
                                        comparisons++;
                                    }
                                } finally {
                                    RemotePaPresentation.clear(player);
                                }
                            }
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return Map.of(
                            "nativeFallbackRawBitComparisons",
                            comparisons,
                            "negativeAimUsesHold",
                            true,
                            "authoritativeAimOverridesLive",
                            true);
                });
    }

    @Test
    void nativeMissingOverrideResolvesTheDefaultDisplay() {
        AnimSyncOracleRunner.run(
                "tacz",
                "native-effective-display-resolution",
                () -> {
                    var gun = ResourceLocation.parse("fixture:gun");
                    var missing = ResourceLocation.parse("fixture:missing");
                    var override = ResourceLocation.parse("fixture:override");
                    var fallback = mock(GunDisplayInstance.class);
                    var alternate = mock(GunDisplayInstance.class);
                    var index = mock(com.tacz.guns.client.resource.index.ClientGunIndex.class);
                    when(index.getDefaultDisplay()).thenReturn(fallback);
                    try (var api = mockStatic(TimelessAPI.class, CALLS_REAL_METHODS);
                            var nativeIndex =
                                    mockStatic(
                                            com.tacz.guns.client.resource.ClientIndexManager
                                                    .class)) {
                        api.when(() -> TimelessAPI.getClientGunIndex(gun))
                                .thenReturn(Optional.of(index));
                        nativeIndex
                                .when(
                                        () ->
                                                com.tacz.guns.client.resource.ClientIndexManager
                                                        .getOrCreateGunDisplay(missing))
                                .thenReturn(null);
                        nativeIndex
                                .when(
                                        () ->
                                                com.tacz.guns.client.resource.ClientIndexManager
                                                        .getOrCreateGunDisplay(override))
                                .thenReturn(alternate);
                        assertSame(fallback, TimelessAPI.getGunDisplay(missing, gun).orElseThrow());
                        assertSame(
                                alternate, TimelessAPI.getGunDisplay(override, gun).orElseThrow());
                        assertSame(
                                fallback,
                                TimelessAPI.getGunDisplay(
                                                com.tacz.guns.api.DefaultAssets
                                                        .DEFAULT_GUN_DISPLAY_ID,
                                                gun)
                                        .orElseThrow());
                        var assets =
                                new com.lunares.blockfield.common.combat.TaczAssets(
                                        new PaGraph.Catalog(1),
                                        Map.of("fixture:gun", "fixture:default"));
                        assets.register(
                                "fixture:default", "null", false, "default", false, Map.of());
                        assets.register(
                                "fixture:override", "null", true, "minigun", false, Map.of());
                        assertEquals(
                                "fixture:default",
                                assets.resolve("fixture:gun", missing.toString()).id());
                        assertEquals(
                                "fixture:override",
                                assets.resolve("fixture:gun", override.toString()).id());
                        assertEquals(
                                "fixture:default",
                                assets.resolve(
                                                "fixture:gun",
                                                com.tacz.guns.api.DefaultAssets
                                                        .DEFAULT_GUN_DISPLAY_ID
                                                        .toString())
                                        .id());
                        OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                        return Map.of(
                                "nativeResolutionCases",
                                3,
                                "missingOverrideFallsBack",
                                true,
                                "sixBoxEquality",
                                false);
                    }
                });
    }

    @Test
    void retainedNativeResourcesAndAuthoritativeFixedHand() {
        AnimSyncOracleRunner.run(
                "tacz",
                "retained-effective-display-fixed-hand",
                () -> {
                    try (var singleton = mockStatic(Minecraft.class);
                            var api = mockStatic(TimelessAPI.class)) {
                        var minecraft = mock(Minecraft.class);
                        var timer = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(timer);
                        when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(.85f);
                        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                        var live = mock(GunDisplayInstance.class);
                        when(live.getPlayerAnimator3rd()).thenReturn(RIFLE);
                        when(live.getThirdPersonAnimation()).thenReturn("minigun");
                        api.when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                .thenReturn(Optional.of(live));
                        var reference = player();
                        var driven = player();
                        var catalog = new PaGraph.Catalog(17);
                        var assets =
                                new com.lunares.blockfield.common.combat.TaczAssets(
                                        catalog, Map.of("fixture:gun", "fixture:retained"));
                        var retained =
                                com.lunares.blockfield.client.animation.RemoteTaczAssets.retain(
                                        assets, "fixture:retained", live);
                        assertTrue(retained.playerAnimator());
                        assertEquals("minigun", retained.fallback());
                        var graph =
                                PaGraph.Stack.create(
                                                catalog,
                                                POLICY,
                                                nativeSources(reference),
                                                LayerTag.PA_96,
                                                1)
                                        .freeze(AnimationGraphState.neutral());
                        int compared = 0;
                        for (boolean fixed : new boolean[] {true, false}) {
                            when(live.is3rdFixedHand()).thenReturn(fixed);
                            var expected =
                                    nativeSources(reference)
                                            .source(AdjustmentSource.HELD_WEAPON_POSE)
                                            .apply("leftArm");
                            var frame =
                                    frame(
                                            graph, catalog, POLICY, .85f, .2f, .4f, fixed,
                                            "default", true);
                            RemotePaPresentation.install(driven, frame);
                            when(live.is3rdFixedHand()).thenReturn(!fixed);
                            try {
                                var actual =
                                        nativeSources(driven)
                                                .source(AdjustmentSource.HELD_WEAPON_POSE)
                                                .apply("leftArm");
                                assertEquals(expected.isPresent(), actual.isPresent());
                                if (expected.isPresent())
                                    equalBits(
                                            expected.orElseThrow().rotation(),
                                            actual.orElseThrow().rotation(),
                                            "authoritative fixed-hand");
                                // No native live display query is needed by the driven rotation
                                // function.
                                api.when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                        .thenThrow(new AssertionError("live resource lookup"));
                                nativeSources(driven)
                                        .source(AdjustmentSource.HELD_WEAPON_POSE)
                                        .apply("rightArm");
                                assertEquals("default", RemotePaPresentation.fallback(driven));
                                com.tacz.guns.client.animation.third.InnerThirdPersonManager
                                        .setRotationAnglesHead(
                                                driven,
                                                new net.minecraft.client.model.geom.ModelPart(
                                                        List.of(), Map.of()),
                                                new net.minecraft.client.model.geom.ModelPart(
                                                        List.of(), Map.of()),
                                                new net.minecraft.client.model.geom.ModelPart(
                                                        List.of(), Map.of()),
                                                new net.minecraft.client.model.geom.ModelPart(
                                                        List.of(), Map.of()),
                                                .7f);
                                compared++;
                            } finally {
                                RemotePaPresentation.clear(driven);
                                api.when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                        .thenReturn(Optional.of(live));
                            }
                        }
                        var next =
                                new com.lunares.blockfield.common.combat.TaczAssets(
                                        new PaGraph.Catalog(18),
                                        Map.of("fixture:gun", "fixture:retained"));
                        next.register(
                                "fixture:retained",
                                RIFLE.toString(),
                                true,
                                "default",
                                false,
                                Map.of());
                        assertEquals("minigun", retained.fallback());
                        assertNotEquals(retained.ref(), next.resolve("fixture:gun", null).ref());
                        OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                        return Map.of(
                                "nativeFixedHandComparisons",
                                compared,
                                "liveLookupRefusedDuringDrivenEvaluation",
                                true,
                                "nativeEffectiveProfileRetained",
                                true,
                                "sameIdDifferentGeneration",
                                true,
                                "sixBoxEquality",
                                false);
                    }
                });
    }

    /**
     * U3 P1-3: the driven {@code setRotationAnglesHead} entry applies the native stop gate (no gun,
     * SLEEPING, onClimbable, isSwimming, FALL_FLYING) from the frame's authoritative flags: no
     * formulas run, exactly like the native call on a player in that state, even while the live
     * entity disagrees. An LR-only/empty-handed frame (null Tacz) installs and is STOPPED.
     */
    @Test
    void drivenEntryAppliesTheNativeStopGateFromAuthoritativeFlags() {
        AnimSyncOracleRunner.run(
                "tacz",
                "third-person-entry-gates",
                () -> {
                    com.tacz.guns.api.client.other.ThirdPersonManager.registerDefault();
                    var result = new LinkedHashMap<String, Object>();
                    try (var singleton = mockStatic(Minecraft.class);
                            var api = mockStatic(TimelessAPI.class)) {
                        var minecraft = mock(Minecraft.class);
                        var timer = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(timer);
                        when(minecraft.isPaused()).thenReturn(false);
                        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                        var vanillaDisplay = mock(GunDisplayInstance.class);
                        when(vanillaDisplay.getThirdPersonAnimation()).thenReturn("default");
                        var paDisplay = mock(GunDisplayInstance.class);
                        when(paDisplay.getPlayerAnimator3rd()).thenReturn(RIFLE);
                        api.when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                .thenReturn(Optional.of(vanillaDisplay));
                        var gunItem =
                                mock(
                                        net.minecraft.world.item.Item.class,
                                        withSettings()
                                                .extraInterfaces(
                                                        com.tacz.guns.api.item.IGun.class));
                        var gun = mock(ItemStack.class);
                        when(gun.getItem()).thenReturn(gunItem);
                        var catalog = new PaGraph.Catalog(0);
                        var graph =
                                PaGraph.Stack.create(
                                                catalog,
                                                POLICY,
                                                id -> part -> Optional.empty(),
                                                LayerTag.PA_96,
                                                1)
                                        .freeze(AnimationGraphState.neutral());
                        record Gate(
                                String name,
                                Pose pose,
                                boolean climb,
                                boolean swim,
                                boolean held) {}
                        var gates =
                                List.of(
                                        new Gate("no-gun", Pose.STANDING, false, false, false),
                                        new Gate("sleeping", Pose.SLEEPING, false, false, true),
                                        new Gate("climbing", Pose.STANDING, true, false, true),
                                        new Gate("swimming", Pose.SWIMMING, false, true, true),
                                        new Gate(
                                                "fall-flying",
                                                Pose.FALL_FLYING,
                                                false,
                                                false,
                                                true));
                        int compared = 0;
                        for (var gate : gates) {
                            // Native reference: the live entity is in the gated state.
                            var reference = player();
                            doReturn(gate.pose()).when(reference).getPose();
                            doReturn(gate.climb()).when(reference).onClimbable();
                            doReturn(gate.swim()).when(reference).isSwimming();
                            doReturn(gate.held() ? gun : ItemStack.EMPTY)
                                    .when(reference)
                                    .getMainHandItem();
                            AnimationManager.playLoopAnimation(
                                    reference,
                                    paDisplay,
                                    PlayerAnimatorCompat.LOWER_ANIMATION,
                                    "hold_lower");
                            var lower =
                                    (ModifierLayer<?>)
                                            dev.kosmx
                                                    .playerAnim
                                                    .minecraftApi
                                                    .PlayerAnimationAccess
                                                    .getPlayerAssociatedData(reference)
                                                    .get(PlayerAnimatorCompat.LOWER_ANIMATION);
                            assertNotNull(lower.getAnimation());
                            float[] expected = entry(reference);
                            assertNull(lower.getAnimation(), gate.name() + ": native stopAll ran");
                            // Driven: the live entity holds a gun and stands; the frame decides.
                            var driven = player();
                            doReturn(gun).when(driven).getMainHandItem();
                            var vanilla =
                                    com.lunares.blockfield.oracle.PaObserverNativeCases.vanilla(
                                            com.lunares.blockfield.common.combat.AnimationTypes.Pose
                                                    .valueOf(gate.pose().name()),
                                            gate.climb(),
                                            gate.swim());
                            for (boolean pa : new boolean[] {false, true}) {
                                var frame =
                                        gate.held()
                                                ? frame(
                                                        graph, catalog, POLICY, .5f, .5f, .4f,
                                                        false, "default", pa, vanilla)
                                                : new RemotePaPresentation.Frame(
                                                        graph, catalog, POLICY, .5f, .5f, vanilla,
                                                        null, null);
                                RemotePaPresentation.install(driven, frame);
                                try {
                                    assertEquals(
                                            RemotePaPresentation.ThirdPerson.STOPPED,
                                            RemotePaPresentation.thirdPerson(driven));
                                    float[] actual = entry(driven);
                                    for (int i = 0; i < 36; i++) {
                                        assertEquals(
                                                Float.floatToRawIntBits(expected[i]),
                                                Float.floatToRawIntBits(actual[i]),
                                                gate.name() + " pa=" + pa + " slot=" + i);
                                        compared++;
                                    }
                                } finally {
                                    RemotePaPresentation.clear(driven);
                                }
                                if (!gate.held()) break;
                            }
                        }
                        // The inverse: the live entity sleeps, the frame stands with a non-PA gun:
                        // the native hold formulas run from the authoritative flags.
                        var sleeper = player();
                        doReturn(Pose.SLEEPING).when(sleeper).getPose();
                        doReturn(gun).when(sleeper).getMainHandItem();
                        RemotePaPresentation.install(
                                sleeper,
                                frame(
                                        graph, catalog, POLICY, .5f, .5f, 0, false, "default",
                                        false));
                        try {
                            float[] expected = baseline();
                            TaczPose.fallback(expected, 0, "default");
                            float[] actual = entry(sleeper);
                            for (int i = 0; i < 36; i++)
                                assertEquals(
                                        Float.floatToRawIntBits(expected[i]),
                                        Float.floatToRawIntBits(actual[i]),
                                        "authoritative standing slot=" + i);
                        } finally {
                            RemotePaPresentation.clear(sleeper);
                        }
                        // An LR-only remote: no Tacz checkpoint, no TaCZ assets, still installable.
                        var lrOnly = player();
                        RemotePaPresentation.install(
                                lrOnly,
                                new RemotePaPresentation.Frame(
                                        graph,
                                        catalog,
                                        POLICY,
                                        .25f,
                                        .25f,
                                        com.lunares.blockfield.oracle.PaObserverNativeCases.vanilla(
                                                com.lunares.blockfield.common.combat.AnimationTypes
                                                        .Pose.STANDING,
                                                false,
                                                false),
                                        null,
                                        null));
                        try {
                            assertTrue(RemotePaPresentation.driven(lrOnly));
                            assertEquals(
                                    RemotePaPresentation.ThirdPerson.STOPPED,
                                    RemotePaPresentation.thirdPerson(lrOnly));
                            assertEquals(Boolean.FALSE, RemotePaPresentation.fixedHand(lrOnly));
                            assertNull(RemotePaPresentation.aim(lrOnly));
                        } finally {
                            RemotePaPresentation.clear(lrOnly);
                        }
                        result.put("gatedCases", gates.size());
                        result.put("rawBitComparisons", compared);
                        result.put("authoritativeFlagsOverrideLiveEntity", true);
                        result.put("lrOnlyFrameInstalls", true);
                    }
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return result;
                });
    }

    /**
     * U3 P2-6: a driven player never outlives its entity object. Install sweeps removed players,
     * and entity unload, world change and connection boundaries clear every driven player.
     */
    @Test
    void drivenPresentationRetentionIsBoundedByLiveEntities() {
        AnimSyncOracleRunner.run(
                "tacz",
                "presentation-retention-backstop",
                () -> {
                    RemotePaPresentation.clearAll();
                    RemotePaPresentation.register();
                    var catalog = new PaGraph.Catalog(0);
                    var graph =
                            PaGraph.Stack.create(
                                            catalog,
                                            POLICY,
                                            id -> part -> Optional.empty(),
                                            LayerTag.PA_96,
                                            1)
                                    .freeze(AnimationGraphState.neutral());
                    var vanilla =
                            com.lunares.blockfield.oracle.PaObserverNativeCases.vanilla(
                                    com.lunares.blockfield.common.combat.AnimationTypes.Pose
                                            .STANDING,
                                    false,
                                    false);
                    var frame =
                            new RemotePaPresentation.Frame(
                                    graph, catalog, POLICY, .5f, .5f, vanilla, null, null);
                    var respawned = player();
                    RemotePaPresentation.install(respawned, frame);
                    doReturn(true).when(respawned).isRemoved();
                    var next = player();
                    RemotePaPresentation.install(next, frame);
                    assertFalse(RemotePaPresentation.driven(respawned), "removed entity swept");
                    assertEquals(1, RemotePaPresentation.installed());
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
                            .ENTITY_UNLOAD
                            .invoker()
                            .onUnload(next, null);
                    assertEquals(0, RemotePaPresentation.installed(), "unload clears");
                    var other = player();
                    RemotePaPresentation.install(other, frame);
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
                            .DISCONNECT
                            .invoker()
                            .onPlayDisconnect(null, null);
                    assertEquals(0, RemotePaPresentation.installed(), "disconnect clears");
                    RemotePaPresentation.install(other, frame);
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
                            .AFTER_CLIENT_WORLD_CHANGE
                            .invoker()
                            .afterWorldChange(null, null);
                    assertEquals(0, RemotePaPresentation.installed(), "world change clears");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return Map.of(
                            "removedEntitySwept",
                            true,
                            "unloadDisconnectWorldChangeClear",
                            true,
                            "retainedAfterBackstops",
                            0);
                });
    }

    private static float[] baseline() {
        float[] values = new float[36];
        var parts = parts();
        for (int part = 0; part < 6; part++) writePart(values, part, parts.get(part));
        return values;
    }

    private static List<net.minecraft.client.model.geom.ModelPart> parts() {
        var parts = new java.util.ArrayList<net.minecraft.client.model.geom.ModelPart>();
        for (int part = 0; part < 6; part++) {
            var model = new net.minecraft.client.model.geom.ModelPart(List.of(), Map.of());
            model.setPos(part * .75f - 2, part + .25f, -part / 3f);
            model.setRotation(.4f - part / 7f, -.3f + part / 9f, .125f);
            parts.add(model);
        }
        return parts;
    }

    /** One native {@code setRotationAnglesHead} call over fresh six parts; returns their values. */
    private static float[] entry(LivingEntity entity) {
        var parts = parts();
        com.tacz.guns.client.animation.third.InnerThirdPersonManager.setRotationAnglesHead(
                entity, parts.get(3), parts.get(2), parts.get(1), parts.get(0), .5f);
        float[] values = new float[36];
        for (int part = 0; part < 6; part++) writePart(values, part, parts.get(part));
        return values;
    }

    /**
     * U3 P1-5: on a draw tick the fresh beginTick-0 rotation clip is sampled at frac(A)=0 through
     * {@code install}: the driven model gets the finite right-hand limit (native is 0/0 there and
     * converges to it at 2^-16 and 2^-24); a graph whose sample is nonfinite is refused before it
     * can reach PA or a ModelPart.
     */
    @Test
    void zeroAgeDrawTickInstallsTheRightHandLimit() {
        AnimSyncOracleRunner.run(
                "tacz",
                "zero-age-draw-tick-install",
                () -> {
                    var result = new LinkedHashMap<String, Object>();
                    try (var singleton = mockStatic(Minecraft.class);
                            var displayApi = mockStatic(TimelessAPI.class);
                            var staminaApi = mockStatic(com.alrex.parcool.api.Stamina.class)) {
                        var stamina = mock(com.alrex.parcool.api.Stamina.class);
                        staminaApi
                                .when(() -> com.alrex.parcool.api.Stamina.get(any(Player.class)))
                                .thenReturn(stamina);
                        var minecraft = mock(Minecraft.class);
                        var timer = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(timer);
                        when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(0f);
                        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                        var display = mock(GunDisplayInstance.class);
                        when(display.getPlayerAnimator3rd()).thenReturn(RIFLE);
                        displayApi
                                .when(() -> TimelessAPI.getGunDisplay(any(ItemStack.class)))
                                .thenReturn(Optional.of(display));
                        var reference = player();
                        nativeOps(reference, display, 0);
                        var nativeApplier =
                                ((IAnimatedPlayer) reference).playerAnimator_getAnimation();
                        var driven = player();
                        var sources = nativeSources(driven);
                        var catalog = new PaGraph.Catalog(0);
                        var canonical =
                                PaGraph.Stack.create(catalog, POLICY, sources, LayerTag.PA_96, 1);
                        canonicalOps(canonical, 0);
                        var graph = canonical.freeze(AnimationGraphState.neutral());
                        RemotePaPresentation.install(
                                driven,
                                frame(graph, catalog, POLICY, 0, 0, 0, false, "default", true));
                        var applier = ((IAnimatedPlayer) driven).playerAnimator_getAnimation();
                        applier.setTickDelta(.37f);
                        int singular = 0, finiteOutputs = 0;
                        try {
                            for (String part : PARTS)
                                for (TransformType type :
                                        List.of(TransformType.POSITION, TransformType.ROTATION)) {
                                    var base = new Vec3f(0.5f, -1.25f, 0.75f);
                                    nativeApplier.setTickDelta(0);
                                    Vec3f nativeZero =
                                            nativeApplier.get3DTransform(part, type, base);
                                    Vec3f driven0 = applier.get3DTransform(part, type, base);
                                    assertTrue(finite(driven0), part + "/" + type);
                                    finiteOutputs++;
                                    if (finite(nativeZero)) {
                                        equalBits(nativeZero, driven0, part + "/" + type);
                                        continue;
                                    }
                                    singular++;
                                    converges(nativeApplier, timer, part, type, base, driven0);
                                    when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(0f);
                                }
                        } finally {
                            RemotePaPresentation.clear(driven);
                        }
                        assertTrue(singular > 0, "the native draw tick is singular at fraction 0");
                        // A nonfinite sample is refused: it never reaches the player's layers.
                        // Finite keyframes whose linear interpolation overflows to -Infinity.
                        var builder =
                                new KeyframeAnimation.AnimationBuilder(
                                        dev.kosmx.playerAnim.core.data.AnimationFormat.UNKNOWN);
                        builder.endTick = 20;
                        builder.stopTick = 20;
                        builder.isLooped = true;
                        builder.setName("nonfinite_upper");
                        var pitch = builder.getOrCreatePart("rightArm").pitch;
                        pitch.setEnabled(true);
                        pitch.addKeyFrame(
                                0, Float.MAX_VALUE, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
                        pitch.addKeyFrame(
                                2, -Float.MAX_VALUE, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
                        var broken = builder.build();
                        var brokenStack =
                                PaGraph.Stack.create(catalog, POLICY, sources, LayerTag.PA_96, 1);
                        brokenStack.loop(LayerTag.PA_94, broken, 1);
                        brokenStack.tick();
                        var brokenGraph = brokenStack.freeze(AnimationGraphState.neutral());
                        assertThrows(
                                PaGraph.Unsupported.class,
                                () ->
                                        RemotePaPresentation.install(
                                                driven,
                                                frame(
                                                        brokenGraph,
                                                        catalog,
                                                        POLICY,
                                                        .5f,
                                                        .5f,
                                                        0,
                                                        false,
                                                        "default",
                                                        true)));
                        assertFalse(RemotePaPresentation.driven(driven));
                        result.put("singularOutputs", singular);
                        result.put("finiteDrivenOutputs", finiteOutputs);
                        result.put("nonfiniteGraphRefused", true);
                    }
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return result;
                });
    }

    /**
     * U3 P1-1: the server display port ({@link
     * com.lunares.blockfield.common.combat.TaczAssets.DisplayFile} plus native-key clip parsing)
     * against the native {@code GunDisplayInstance} loader and {@code PlayerAnimatorAssetManager}
     * over every default-pack display and blank/missing/animator-less edge cases: equal displays,
     * refs and definition bytes.
     */
    @Test
    void serverDisplayPortMatchesTheNativeLoader() {
        AnimSyncOracleRunner.run(
                "tacz",
                "display-loader-differential",
                () -> {
                    var lazyField =
                            com.tacz.guns.config.client.ResourceConfig.class.getField(
                                    "ENABLE_LAZY_CLIENT_ASSET_LOAD");
                    Object saved = lazyField.get(null);
                    var lazy = mock(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue.class);
                    when(lazy.get()).thenReturn(Boolean.TRUE);
                    lazyField.set(null, lazy);
                    var result = new LinkedHashMap<String, Object>();
                    try {
                        var root =
                                net.fabricmc.loader.api.FabricLoader.getInstance()
                                        .getModContainer("tacz")
                                        .orElseThrow()
                                        .findPath("assets/tacz/custom/tacz_default_gun/assets/tacz")
                                        .orElseThrow();
                        var put =
                                PlayerAnimatorAssetManager.class.getDeclaredMethod(
                                        "putAnimation",
                                        ResourceLocation.class,
                                        java.io.InputStream.class);
                        put.setAccessible(true);
                        var files = new java.util.TreeMap<String, JsonObject>();
                        try (var list = java.nio.file.Files.list(root.resolve("player_animator"))) {
                            for (var file : list.sorted().toList()) {
                                String name = file.getFileName().toString();
                                if (!name.endsWith(".json")) continue;
                                String id = "tacz:" + name.substring(0, name.length() - 5);
                                byte[] bytes = java.nio.file.Files.readAllBytes(file);
                                put.invoke(
                                        PlayerAnimatorAssetManager.get(),
                                        ResourceLocation.parse(id),
                                        new java.io.ByteArrayInputStream(bytes));
                                files.put(
                                        id,
                                        JsonParser.parseString(
                                                        new String(bytes, StandardCharsets.UTF_8))
                                                .getAsJsonObject());
                            }
                        }
                        var displays = new java.util.TreeMap<String, JsonObject>();
                        try (var list = java.nio.file.Files.list(root.resolve("display/guns"))) {
                            for (var file : list.sorted().toList()) {
                                String name = file.getFileName().toString();
                                if (!name.endsWith(".json")) continue;
                                displays.put(
                                        "tacz:" + name.substring(0, name.length() - 5),
                                        JsonParser.parseString(java.nio.file.Files.readString(file))
                                                .getAsJsonObject());
                            }
                        }
                        var template = displays.get("tacz:ak47_display").deepCopy();
                        var blank = template.deepCopy();
                        blank.addProperty("third_person_animation", "  \t");
                        displays.put("fixture:blank_third_person", blank);
                        var absent = template.deepCopy();
                        absent.remove("third_person_animation");
                        displays.put("fixture:absent_third_person", absent);
                        var fixedOnly = template.deepCopy();
                        fixedOnly.remove("player_animator_3rd");
                        fixedOnly.addProperty("3rd_fixed_hand", true);
                        displays.put("fixture:fixed_without_animator", fixedOnly);
                        var fixedNull = template.deepCopy();
                        fixedNull.add("player_animator_3rd", com.google.gson.JsonNull.INSTANCE);
                        fixedNull.addProperty("3rd_fixed_hand", true);
                        displays.put("fixture:fixed_null_animator", fixedNull);
                        var fixed = template.deepCopy();
                        fixed.addProperty("3rd_fixed_hand", true);
                        displays.put("fixture:fixed_with_animator", fixed);
                        var server =
                                new com.lunares.blockfield.common.combat.TaczAssets(
                                        new PaGraph.Catalog(41), Map.of());
                        var client =
                                new com.lunares.blockfield.common.combat.TaczAssets(
                                        new PaGraph.Catalog(41), Map.of());
                        int missingThirdPerson = 0, fixedHands = 0, withPa = 0;
                        for (var entry : displays.entrySet()) {
                            var pojo =
                                    com.tacz.guns.client.resource.ClientAssetsManager.GSON.fromJson(
                                            entry.getValue(),
                                            com.tacz.guns.client.resource.pojo.display.gun
                                                    .GunDisplay.class);
                            var instance =
                                    GunDisplayInstance.create(
                                            ResourceLocation.parse(entry.getKey()), pojo);
                            var file =
                                    com.lunares.blockfield.common.combat.TaczAssets.DisplayFile
                                            .parse(entry.getValue());
                            assertEquals(
                                    instance.getThirdPersonAnimation(),
                                    file.fallback(),
                                    entry.getKey());
                            assertEquals(
                                    instance.getPlayerAnimator3rd().toString(),
                                    file.animation(),
                                    entry.getKey());
                            assertEquals(
                                    instance.is3rdFixedHand(), file.fixedHand(), entry.getKey());
                            var parsed =
                                    files.containsKey(file.animation())
                                            ? dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer
                                                    .deserialize(files.get(file.animation()))
                                            : List.of();
                            var keyed =
                                    com.lunares.blockfield.common.combat.TaczAssets.keyed(parsed);
                            var expected =
                                    server.register(
                                            entry.getKey(),
                                            file.animation(),
                                            file.fixedHand(),
                                            file.fallback(),
                                            !keyed.isEmpty(),
                                            keyed);
                            var actual =
                                    com.lunares.blockfield.client.animation.RemoteTaczAssets.retain(
                                            client, entry.getKey(), instance);
                            assertEquals(expected, actual, entry.getKey());
                            if (!entry.getValue().has("third_person_animation"))
                                missingThirdPerson++;
                            if (file.fixedHand()) fixedHands++;
                            if (expected.playerAnimator()) withPa++;
                        }
                        assertEquals(
                                server.catalog().definitions(), client.catalog().definitions());
                        assertTrue(missingThirdPerson >= 19, "default displays without a name");
                        result.put("displays", displays.size());
                        result.put("defaultPackDisplays", displays.size() - 5);
                        result.put("withoutThirdPersonAnimation", missingThirdPerson);
                        result.put("fixedHand", fixedHands);
                        result.put("playerAnimatorDisplays", withPa);
                        result.put("animationFiles", files.size());
                        result.put("definitions", server.catalog().definitions().size());
                        result.put("definitionBytesEqual", true);
                    } finally {
                        lazyField.set(null, saved);
                    }
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    return result;
                });
    }

    /**
     * U4 P2-3: the ported native clip key equals PA's client-only {@code
     * PlayerAnimationRegistry.serializeTextToString(name).toLowerCase(ENGLISH)}.
     */
    @Test
    void portedClipKeyMatchesTheNativeRegistryKey() {
        AnimSyncOracleRunner.run(
                "tacz",
                "native-clip-key",
                () -> {
                    var names =
                            List.of(
                                    "hold_upper",
                                    "Hold_Upper",
                                    "\"quoted\"",
                                    "{\"text\":\"Json Text\"}",
                                    "[\"a\",\"B\"]",
                                    "with space",
                                    "",
                                    "1e5",
                                    "null",
                                    "true",
                                    "ÄÖÜ_ß",
                                    "{broken");
                    for (String name : names)
                        assertEquals(
                                dev.kosmx
                                        .playerAnim
                                        .minecraftApi
                                        .PlayerAnimationRegistry
                                        .serializeTextToString(name)
                                        .toLowerCase(java.util.Locale.ENGLISH),
                                PaGraph.nativeKey(name),
                                name);
                    return Map.of("names", names.size(), "nativeKeyEqual", true);
                });
    }

    private static void writePart(
            float[] target, int part, net.minecraft.client.model.geom.ModelPart model) {
        int i = part * 6;
        target[i] = model.x;
        target[i + 1] = model.y;
        target[i + 2] = model.z;
        target[i + 3] = model.xRot;
        target[i + 4] = model.yRot;
        target[i + 5] = model.zRot;
    }

    private static void nativeOps(AbstractClientPlayer player, GunDisplayInstance display, int t)
            throws Exception {
        if (t >= 50 && t < 52) {
            if (t == 50) AnimationManager.stopAllAnimation(player);
            return;
        }
        String upper = t >= 20 && t < 40 ? "aim_upper" : "hold_upper";
        AnimationManager.playLoopAnimation(
                player, display, PlayerAnimatorCompat.LOWER_ANIMATION, "hold_lower");
        AnimationManager.playLoopAnimation(
                player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, upper);
        AnimationManager.playRotationAnimation(player, display);
        var once = PlayerAnimatorCompat.ONCE_UPPER_ANIMATION;
        if (t == 5 || t == 14)
            AnimationManager.playOnceAnimation(player, display, once, "normal_fire_upper");
        if (t == 12) AnimationManager.playOnceAnimation(player, display, once, "reload_upper");
        if (t == 35) AnimationManager.playOnceAnimation(player, display, once, "absent_clip");
        if (t == 30) {
            var stop =
                    AnimationManager.class.getDeclaredMethod(
                            "stopAnimation",
                            AbstractClientPlayer.class,
                            ResourceLocation.class,
                            int.class);
            stop.setAccessible(true);
            stop.invoke(null, player, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, 8);
            stop.invoke(null, player, once, 8);
            stop.invoke(null, player, PlayerAnimatorCompat.LOWER_ANIMATION, 8);
            AnimationManager.playLoopAnimation(
                    player, display, PlayerAnimatorCompat.LOWER_ANIMATION, "hold_lower");
            AnimationManager.playLoopAnimation(
                    player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, upper);
        }
        if (t == 42) AnimationManager.playOnceAnimation(player, display, once, "melee_2_upper");
    }

    private static void canonicalOps(PaGraph.Stack stack, int t) {
        long sequence = t + 1L;
        if (t >= 50 && t < 52) {
            if (t == 50)
                for (LayerTag tag :
                        List.of(LayerTag.PA_93, LayerTag.PA_94, LayerTag.PA_95, LayerTag.PA_96))
                    stack.stop(tag, PaGraph.TACZ_FADE, sequence);
            return;
        }
        String upper = t >= 20 && t < 40 ? "aim_upper" : "hold_upper";
        stack.loop(LayerTag.PA_93, clips.get("hold_lower"), sequence);
        stack.loop(LayerTag.PA_94, clips.get(upper), sequence);
        stack.rotation(LayerTag.PA_96, clips.get("empty"), sequence);
        if (t == 5 || t == 14)
            stack.once(LayerTag.PA_95, clips.get("normal_fire_upper"), false, sequence);
        if (t == 12) stack.once(LayerTag.PA_95, clips.get("reload_upper"), false, sequence);
        if (t == 35) stack.once(LayerTag.PA_95, clips.get("absent_clip"), false, sequence);
        if (t == 30) {
            // Native onDraw order: loop upper, once upper, lower.
            stack.stop(LayerTag.PA_94, PaGraph.TACZ_FADE, sequence);
            stack.stop(LayerTag.PA_95, PaGraph.TACZ_FADE, sequence);
            stack.stop(LayerTag.PA_93, PaGraph.TACZ_FADE, sequence);
            stack.loop(LayerTag.PA_93, clips.get("hold_lower"), sequence);
            stack.loop(LayerTag.PA_94, clips.get(upper), sequence);
        }
        if (t == 42) stack.once(LayerTag.PA_95, clips.get("melee_2_upper"), false, sequence);
    }

    /** Native applier versus a fresh canonical materialization at the same fractions. */
    private static void compare(
            AbstractClientPlayer player,
            AnimationGraphState graph,
            PaGraph.Catalog catalog,
            PaGraph.Sources sources,
            DeltaTracker timer,
            int[] counts) {
        var applier = ((IAnimatedPlayer) player).playerAnimator_getAnimation();
        var singular = new java.util.ArrayList<Object[]>();
        for (float p : FRACTIONS) {
            when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(p);
            applier.setTickDelta(p);
            var scratch = PaGraph.Stack.materialize(graph, catalog, POLICY, sources);
            scratch.sample(p);
            for (String part : PARTS)
                for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
                    var base = new Vec3f(0.5f, -1.25f, 0.75f);
                    Vec3f expected = applier.get3DTransform(part, type, base);
                    Vec3f actual = scratch.transform(part, type, base);
                    if (finite(expected)) {
                        equalBits(expected, actual, part + "/" + type + "@" + p);
                        counts[0]++;
                        continue;
                    }
                    // ADR4.1 errata: only the exact zero-age sample is singular natively.
                    assertEquals(0f, p, part + "/" + type);
                    assertTrue(finite(actual), "right-hand limit, never NaN");
                    singular.add(new Object[] {part, type, actual});
                    counts[1]++;
                }
        }
        for (Object[] sample : singular)
            converges(
                    applier,
                    timer,
                    (String) sample[0],
                    (TransformType) sample[1],
                    new Vec3f(0.5f, -1.25f, 0.75f),
                    (Vec3f) sample[2]);
    }

    /** Native outputs at fractions 2^-16 and 2^-24 approach the right-hand limit. */
    private static void converges(
            AnimationApplier applier,
            DeltaTracker timer,
            String part,
            TransformType type,
            Vec3f base,
            Vec3f limit) {
        float[] distance = new float[2];
        float[] fractions = {1f / 65536, 1f / (1 << 24)};
        for (int k = 0; k < 2; k++) {
            when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(fractions[k]);
            applier.setTickDelta(fractions[k]);
            Vec3f near = applier.get3DTransform(part, type, base);
            distance[k] =
                    Math.max(
                            Math.abs(near.getX() - limit.getX()),
                            Math.max(
                                    Math.abs(near.getY() - limit.getY()),
                                    Math.abs(near.getZ() - limit.getZ())));
        }
        assertTrue(distance[1] <= distance[0], part + "/" + type + " converges");
        assertTrue(distance[1] <= 1e-6f, part + "/" + type + " at 2^-24");
    }

    private static boolean finite(Vec3f value) {
        return Float.isFinite(value.getX())
                && Float.isFinite(value.getY())
                && Float.isFinite(value.getZ());
    }

    private static void equalBits(Vec3f expected, Vec3f actual, String where) {
        assertEquals(
                Float.floatToRawIntBits(expected.getX()),
                Float.floatToRawIntBits(actual.getX()),
                where);
        assertEquals(
                Float.floatToRawIntBits(expected.getY()),
                Float.floatToRawIntBits(actual.getY()),
                where);
        assertEquals(
                Float.floatToRawIntBits(expected.getZ()),
                Float.floatToRawIntBits(actual.getZ()),
                where);
    }

    /**
     * Driven presentation: every install transplants a fresh materialization into the player's own
     * layers; PA's partial becomes frac(A); TaCZ leaves cannot touch a driven player; clear returns
     * the native layers.
     */
    private static void presentation(
            GunDisplayInstance display,
            DeltaTracker timer,
            PaGraph.Catalog catalog,
            Map<String, Object> result)
            throws Exception {
        var driven = player();
        var sources = nativeSources(driven);
        var canonical = PaGraph.Stack.create(catalog, POLICY, sources, LayerTag.PA_96, 1);
        var applier = ((IAnimatedPlayer) driven).playerAnimator_getAnimation();
        Field tickDelta = AnimationProcessor.class.getDeclaredField("tickDelta");
        tickDelta.setAccessible(true);
        var layer95 =
                (ModifierLayer<?>)
                        dev.kosmx
                                .playerAnim
                                .minecraftApi
                                .PlayerAnimationAccess
                                .getPlayerAssociatedData(driven)
                                .get(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION);
        int compared = 0, cancelled = 0;
        for (int t = 0; t < 24; t++) {
            if (t > 0) canonical.tick();
            canonicalOps(canonical, t);
            var graph = canonical.freeze(AnimationGraphState.neutral());
            float a = (t * 7 % 16) / 16f + 1f / 65536;
            RemotePaPresentation.install(
                    driven, frame(graph, catalog, POLICY, a, a, 0, false, "default", true));
            assertTrue(RemotePaPresentation.driven(driven));
            when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(a);
            applier.setTickDelta(0.37f);
            assertEquals(
                    Float.floatToRawIntBits(a),
                    Float.floatToRawIntBits(tickDelta.getFloat(applier)));
            var scratch = PaGraph.Stack.materialize(graph, catalog, POLICY, sources);
            scratch.sample(a);
            for (String part : PARTS)
                for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
                    var base = new Vec3f(-0.5f, 2.25f, 0.125f);
                    equalBits(
                            scratch.transform(part, type, base),
                            applier.get3DTransform(part, type, base),
                            "driven " + part + "/" + type + "@" + t);
                    compared++;
                }
            // Even several client ticks cannot advance a driven frame's scratch.
            ((IAnimatedPlayer) driven).getAnimationStack().tick();
            ((IAnimatedPlayer) driven).getAnimationStack().tick();
            for (String part : PARTS) {
                var base = new Vec3f(-0.5f, 2.25f, 0.125f);
                equalBits(
                        scratch.transform(part, TransformType.ROTATION, base),
                        applier.get3DTransform(part, TransformType.ROTATION, base),
                        "client ticks suppressed");
            }
            IAnimation before = layer95.getAnimation();
            AnimationManager.playOnceAnimation(
                    driven, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, "reload_upper");
            assertSame(before, layer95.getAnimation(), "driven layers ignore native TaCZ ops");
            cancelled++;
        }
        // The R lower alternative: 41/93 subtrees set up and sample at frac(R) inside the same
        // native ordered stack while upper/action layers stay on frac(A).
        var rPolicy =
                new AnimationPolicy(
                        0, PhaseDomain.R, true, true, true, true, AnimationPolicy.registered());
        var rStack = PaGraph.Stack.create(catalog, rPolicy, sources, LayerTag.PA_96, 1);
        int rCompared = 0;
        for (int t = 0; t < 16; t++) {
            if (t > 0) rStack.tick();
            canonicalOps(rStack, t);
            var graph = rStack.freeze(AnimationGraphState.neutral());
            float a = (t * 5 % 16) / 16f + 1f / 65536, r = (t * 3 % 16) / 16f + 1f / 32768;
            RemotePaPresentation.install(
                    driven, frame(graph, catalog, rPolicy, a, r, 0, false, "default", true));
            when(timer.getGameTimeDeltaPartialTick(false)).thenReturn(a);
            applier.setTickDelta(0.37f);
            var scratch = PaGraph.Stack.materialize(graph, catalog, rPolicy, sources);
            scratch.sample(a, r);
            for (String part : PARTS)
                for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
                    var base = new Vec3f(0.25f, -0.5f, 1.5f);
                    equalBits(
                            scratch.transform(part, type, base),
                            applier.get3DTransform(part, type, base),
                            "driven R " + part + "/" + type + "@" + t);
                    rCompared++;
                }
        }
        result.put("presentationLowerRRawBitComparisons", rCompared);
        RemotePaPresentation.clear(driven);
        assertFalse(RemotePaPresentation.driven(driven));
        applier.setTickDelta(0.37f);
        assertEquals(
                Float.floatToRawIntBits(0.37f),
                Float.floatToRawIntBits(tickDelta.getFloat(applier)));
        assertNull(layer95.getAnimation());
        AnimationManager.playOnceAnimation(
                driven, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, "reload_upper");
        assertInstanceOf(KeyframeAnimationPlayer.class, layer95.getAnimation());
        assertThrows(
                PaGraph.Unsupported.class,
                () ->
                        RemotePaPresentation.install(
                                mock(net.minecraft.client.player.LocalPlayer.class),
                                frame(
                                        rStack.freeze(AnimationGraphState.neutral()),
                                        catalog,
                                        rPolicy,
                                        0,
                                        0,
                                        0,
                                        false,
                                        "default",
                                        true)));
        result.put("localPlayerRefused", true);
        result.put("clientTickAdvancementSuppressed", true);
        result.put("presentationRawBitComparisons", compared);
        result.put("drivenNativeOpsCancelled", cancelled);
        result.put("partialRedirectedToA", true);
        result.put("clearRestoresNative", true);
    }
}
