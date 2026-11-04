package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.animation.RemoteLrCompat;
import com.lunares.blockfield.client.combat.ClientAnimationAssets;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.client.combat.SnapshotResolver;
import com.lunares.blockfield.client.combat.SnapshotTransform;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.lunares.blockfield.client.render.RenderCapture;
import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationTiming;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.ObserverReducer;
import com.lunares.blockfield.oracle.MixedRendererCases;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U10 client half of the mixed-renderer differential (ADR4 §§8.3, 10 P2-9). Every shared case frame
 * installs the driven presentation exactly as {@code ClientCombatScene} does (R placement write,
 * {@code DrivenPresentation.install} of the reduced graph), then renders the remote through the
 * real {@code LevelRenderer.renderEntity -> EntityRenderDispatcher.render -> PlayerRenderer.render}
 * with every production mixin (vanilla root and model, PA, ParCool, TaCZ, TaCZ Tweaks, LR, Create,
 * SBW, Accessories, Iris, EntityCulling, Blockfield's driven scopes and the §8.3 capture). The
 * sample is the production MAIN capture; the server half evaluates the same frame through {@code
 * AnimSyncEval.pose} and the comparator requires the same root, six parts and 48 corners.
 *
 * <p>Pass permutations per frame: Iris shadow pass before and after main (Iris's own shadow flag,
 * read by the production pass detection), a repeated main pass, F3+B drawing the capture; every
 * fifth frame a culled main pass through EntityCulling's real {@code renderEntity} gate (must be
 * {@code not_rendered}) and the same frame with culling disabled. Renderer instances are allocated
 * without constructors (no GL, no resource reload); body layers draw after {@code renderToBuffer}
 * and are left out (recorded boundary). The Minecraft loop, GL and network stack never start.
 */
class MixedRendererOracleFixture {
    private static final float AMBIENT = .37f, AMBIENT_REPEAT = .81f;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SceneTimingOracleFixture.bootstrap();
    }

    @Test
    void sbwCollapsedYawMatchesNativeRawPairsAtWrapAndIntegerR() {
        AnimSyncOracleRunner.run(
                "sbw",
                "fx-client-native-yaw-pairs",
                () -> {
                    var rows = new ArrayList<Map<String, Object>>();
                    try (var env = new Env()) {
                        var base =
                                MixedRendererCases.cases().stream()
                                        .filter(c -> c.name().equals("passenger-seat"))
                                        .findFirst()
                                        .orElseThrow();
                        env.switchCase(base);
                        for (var r :
                                List.of(
                                        new com.lunares.blockfield.common.combat.AnimationTypes
                                                .Time(103, 32768),
                                        com.lunares.blockfield.common.combat.AnimationTypes.Time
                                                .whole(104))) {
                            var frame =
                                    new MixedRendererCases.Frame(
                                            60,
                                            rows.size(),
                                            r,
                                            new com.lunares.blockfield.common.combat.AnimationTypes
                                                    .Time(106, 16384));
                            var value = com.lunares.blockfield.oracle.FxClientCases.yawPairs(base);
                            var placement = value.rootVehicles().get(104L);
                            var angles = placement.angles();
                            var pairs = placement.vehicleAngles();
                            env.vehicle(value, frame);
                            var vehicle =
                                    (com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
                                            env.player.getVehicle();
                            var id = value.identity();
                            var identity =
                                    new com.lunares.blockfield.common.combat.AnimationState
                                            .Identity(
                                            id.connectionId(),
                                            id.observerStreamEpoch(),
                                            id.sceneGeneration(),
                                            vehicle.getId(),
                                            2,
                                            id.subscriptionGeneration(),
                                            0,
                                            0,
                                            0,
                                            id.resourceGeneration(),
                                            id.policyGeneration(),
                                            id.compatibilityDigest(),
                                            id.manifestDigest());
                            var records = new TreeMap<Long, SnapshotTransform>();
                            records.put(
                                    104L,
                                    SnapshotTransform.exact(104, identity, 0, placement, null));
                            var sample = SnapshotResolver.exact(records, r);
                            float p = (float) sample.fraction();
                            // Reference: the real native matrix functions reading the uncollapsed
                            // pairs.
                            vehicle.setYRot(angles.yRot());
                            set(Entity.class, vehicle, "yRotO", "F", angles.yRotO());
                            vehicle.setTurretYRotO(pairs.turretYRotO());
                            vehicle.setTurretYRot(pairs.turretYRot());
                            vehicle.setGunYRotO(pairs.gunYRotO());
                            vehicle.setGunYRot(pairs.gunYRot());
                            vehicle.setTurretXRotO(pairs.turretXRotO());
                            vehicle.setTurretXRot(pairs.turretXRot());
                            vehicle.setGunXRotO(pairs.gunXRotO());
                            vehicle.setGunXRot(pairs.gunXRot());
                            var expected = new ArrayList<org.joml.Quaterniond>();
                            for (String transform : List.of("Default", "Turret", "WeaponStation"))
                                expected.add(
                                        new org.joml.Quaterniond(
                                                vehicle.getRotationFromString(transform, p)));
                            ClientCombatScene.write(vehicle, sample);
                            int i = 0;
                            for (String transform : List.of("Default", "Turret", "WeaponStation")) {
                                var actual = vehicle.getRotationFromString(transform, .37f);
                                var reference = expected.get(i++);
                                assertEquals(
                                        Double.doubleToRawLongBits(reference.x),
                                        Double.doubleToRawLongBits(actual.x),
                                        transform);
                                assertEquals(
                                        Double.doubleToRawLongBits(reference.y),
                                        Double.doubleToRawLongBits(actual.y),
                                        transform);
                                assertEquals(
                                        Double.doubleToRawLongBits(reference.z),
                                        Double.doubleToRawLongBits(actual.z),
                                        transform);
                                assertEquals(
                                        Double.doubleToRawLongBits(reference.w),
                                        Double.doubleToRawLongBits(actual.w),
                                        transform);
                            }
                            assertEquals(
                                    Float.floatToRawIntBits(
                                            net.minecraft.util.Mth.lerp(
                                                    p, angles.yRotO(), angles.yRot())),
                                    Float.floatToRawIntBits(vehicle.getYRot()));
                            rows.add(
                                    Map.of(
                                            "rTick",
                                            r.tick(),
                                            "rAlpha",
                                            r.alpha(),
                                            "sampledAngles",
                                            sample.angles()));
                        }
                    }
                    OracleTransformAudit.requireConfig("mixins.superbwarfare.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of("cases", rows);
                });
    }

    @Test
    void nativeSeatDirectionDependsOnUnretainedTurretTranslation() {
        AnimSyncOracleRunner.run(
                "sbw",
                "seat-direction-translation-dependency",
                () -> {
                    try (var env = new Env()) {
                        var value =
                                MixedRendererCases.cases().stream()
                                        .filter(c -> c.name().equals("passenger-seat"))
                                        .findFirst()
                                        .orElseThrow();
                        env.switchCase(value);
                        var frame = MixedRendererCases.seatWitnessFrame();
                        env.vehicle(value, frame);
                        var vehicle =
                                (com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
                                        env.player.getVehicle();
                        double x = MixedRendererCases.SEAT_WITNESS_X;
                        double z = MixedRendererCases.SEAT_WITNESS_Z;
                        vehicle.setPos(x, 63, z);
                        set(Entity.class, vehicle, "xo", "D", x);
                        set(Entity.class, vehicle, "yo", "D", 63.0);
                        set(Entity.class, vehicle, "zo", "D", z);
                        var data = vehicle.computed();
                        float yaw = Float.intBitsToFloat(MixedRendererCases.SEAT_WITNESS_YAW_BITS);
                        vehicle.setYRot(yaw);
                        set(Entity.class, vehicle, "yRotO", "F", yaw);
                        var turrets = MixedRendererCases.SEAT_WITNESS_TURRETS;
                        doReturn(vec(turrets.get(0))).when(data).getTurretPos();
                        var first = vehicle.getTransformDirectionNoOrientation(.5f, env.player);
                        var orientation = vehicle.getRotationFromString("Turret", .5f);
                        float firstYaw =
                                (float)
                                        com.atsuishio.superbwarfare.entity.vehicle.utils
                                                .VehicleVecUtils.getYRotFromVector(first);
                        doReturn(vec(turrets.get(1))).when(data).getTurretPos();
                        var second = vehicle.getTransformDirectionNoOrientation(.5f, env.player);
                        float secondYaw =
                                (float)
                                        com.atsuishio.superbwarfare.entity.vehicle.utils
                                                .VehicleVecUtils.getYRotFromVector(second);
                        assertNotEquals(
                                Float.floatToRawIntBits(firstYaw),
                                Float.floatToRawIntBits(secondYaw),
                                "the translated native matrix changes the float seat yaw");
                        // The server port must reproduce exactly these native bits (server twin).
                        assertEquals(
                                MixedRendererCases.SEAT_WITNESS_NATIVE_YAW_BITS,
                                List.of(
                                        Float.floatToRawIntBits(firstYaw),
                                        Float.floatToRawIntBits(secondYaw)));
                        assertEquals(orientation, vehicle.getRotationFromString("Turret", .5f));
                        var named = new LinkedHashMap<String, Integer>();
                        doReturn(vec(turrets.get(0))).when(data).getTurretPos();
                        doReturn(new Vec3(0, 2, 1)).when(data).getBarrelPos();
                        doReturn(new Vec3(1, 1, 0)).when(data).getPassengerWeaponStationPos();
                        doReturn(new Vec3(1, 1, 1)).when(data).getPassengerWeaponStationBarrelPos();
                        var seat = data.seats().getFirst();
                        for (String transform :
                                List.of("Barrel", "WeaponStation", "WeaponStationBarrel")) {
                            seat.transform = transform;
                            var direction =
                                    vehicle.getTransformDirectionNoOrientation(.5f, env.player);
                            float nativeYaw =
                                    (float)
                                            com.atsuishio.superbwarfare.entity.vehicle.utils
                                                    .VehicleVecUtils.getYRotFromVector(direction);
                            named.put(transform, Float.floatToRawIntBits(nativeYaw));
                            assertEquals(
                                    direction,
                                    vehicle.getTransformDirectionNoOrientation(.5f, env.player),
                                    "repeat native sample");
                        }
                        OracleTransformAudit.requireConfig("mixins.superbwarfare.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        var result = new LinkedHashMap<String, Object>();
                        assertEquals(MixedRendererCases.NAMED_SEAT_WITNESS_NATIVE_YAW_BITS, named);
                        result.put("namedSeatYawBits", named);
                        result.putAll(
                                Map.of(
                                        "yawBits", Float.floatToRawIntBits(yaw),
                                        "firstSeatYawBits", Float.floatToRawIntBits(firstYaw),
                                        "secondSeatYawBits", Float.floatToRawIntBits(secondYaw),
                                        "firstDirection", List.of(first.x, first.y, first.z),
                                        "secondDirection", List.of(second.x, second.y, second.z),
                                        "firstTurretPos", List.of(0, 2, 0),
                                        "secondTurretPos", List.of(4, 3, -2),
                                        "origin", List.of(x, 63.0, z),
                                        "retainedLayout",
                                                com.lunares.blockfield.common.combat.SbwLayouts
                                                        .identity()));
                        return result;
                    }
                });
    }

    @Test
    void drivenRemotesRenderEveryMixedFrameThroughTheProductionRenderer() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "mixed-renderer-native",
                () -> {
                    try (var env = new Env()) {
                        var measurements = new LinkedHashMap<>(MixedRendererCases.run(env::sample));
                        measurements.put("passes", env.passes);
                        measurements.put("hookOrder", hookOrder());
                        measurements.put("recreatedRendererFields", List.copyOf(env.recreated));
                        measurements.put("nullRendererFields", List.copyOf(env.nullFields));
                        measurements.put(
                                "liveContradictions",
                                List.of(
                                        "attribute scale 1.25",
                                        "standing bbHeight 1.8",
                                        "health opposite to alive",
                                        "wasTouchingWater opposite to inWater"));
                        measurements.put(
                                "boundary",
                                "renderers allocated without constructors (no GL/resource reload);"
                                        + " body layers (armor, held items, cape) are not executed;"
                                        + " they draw after renderToBuffer and cannot move the"
                                        + " captured six boxes. Minecraft.runTick, GL and network"
                                        + " are not started.");
                        for (var config :
                                List.of(
                                        "blockfield-client.mixins.json",
                                        "playerAnimator-common.mixins.json",
                                        "parcool.mixins.json",
                                        "create.mixins.json",
                                        "tacz.mixins.json",
                                        "lrtactical.mixins.json",
                                        "mixins.superbwarfare.json",
                                        "entityculling.mixins.json",
                                        "accessories-fabric.mixins.json"))
                            OracleTransformAudit.requireConfig(config);
                        return measurements;
                    }
                });
    }

    /**
     * One transformed render environment; every renderer is real, allocated constructor-free.
     * Package-visible for the UOPT client benchmark ({@code DrivenPresentationBench}).
     */
    static final class Env implements AutoCloseable {
        final org.mockito.MockedStatic<Minecraft> singleton;
        // Native ParCool reads stored settings before the forced policy substitutes them.
        final ParCoolConfigFixture config = ParCoolConfigFixture.inMemory();
        final Minecraft minecraft;
        final Camera camera = new Camera();
        final EntityRenderDispatcher dispatcher;
        final LevelRenderer levelRenderer;
        final ClientLevel level;
        final Map<String, Recording> buffers = new LinkedHashMap<>();
        final MultiBufferSource source =
                type -> buffers.computeIfAbsent(type.toString(), key -> new Recording());
        final Map<String, Integer> passes = new LinkedHashMap<>();
        final java.util.Set<String> recreated = new java.util.TreeSet<>(),
                nullFields = new java.util.TreeSet<>();
        final Class<?> shadowRenderer, cullable, cullingBase;
        MixedRendererCases.Case current;
        AbstractClientPlayer player;
        TreeMap<Long, SnapshotTransform> frames;
        ClientAnimationAssets.Generation assets;
        org.mockito.MockedStatic<com.atsuishio.superbwarfare.data.vehicle.VehicleData> vehicleData;

        Env() throws Exception {
            this(null, null);
        }

        Env(Minecraft existing, ClientLevel existingLevel) throws Exception {
            minecraft = existing == null ? mock(Minecraft.class, quiet()) : existing;
            level = existingLevel == null ? mock(ClientLevel.class, quiet()) : existingLevel;
            singleton = existing == null ? mockStatic(Minecraft.class, quiet()) : null;
            if (singleton != null) singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            var timer = mock(net.minecraft.client.DeltaTracker.class, quiet());
            doReturn(timer).when(minecraft).getTimer();
            doReturn(AMBIENT).when(timer).getGameTimeDeltaPartialTick(anyBoolean());
            minecraft.player = mock(LocalPlayer.class, quiet());
            set(Minecraft.class, minecraft, "options", "Lnet/minecraft/client/Options;", options());
            var gameRenderer = mock(net.minecraft.client.renderer.GameRenderer.class, quiet());
            doReturn(camera).when(gameRenderer).getMainCamera();
            set(
                    Minecraft.class,
                    minecraft,
                    "gameRenderer",
                    "Lnet/minecraft/client/renderer/GameRenderer;",
                    gameRenderer);
            doReturn(true).when(level).isClientSide();
            set(net.minecraft.world.level.Level.class, level, "isClientSide", "Z", true);

            dispatcher = allocate(EntityRenderDispatcher.class);
            dispatcher.camera = camera;
            set(
                    EntityRenderDispatcher.class,
                    dispatcher,
                    "playerRenderers",
                    "Ljava/util/Map;",
                    Map.of(
                            PlayerSkin.Model.WIDE,
                            renderer(false),
                            PlayerSkin.Model.SLIM,
                            renderer(true)));
            set(EntityRenderDispatcher.class, dispatcher, "renderers", "Ljava/util/Map;", Map.of());
            set(
                    EntityRenderDispatcher.class,
                    dispatcher,
                    "options",
                    "Lnet/minecraft/client/Options;",
                    options());
            set(EntityRenderDispatcher.class, dispatcher, "shouldRenderShadow", "Z", false);
            set(EntityRenderDispatcher.class, dispatcher, "renderHitBoxes", "Z", true);
            set(
                    EntityRenderDispatcher.class,
                    dispatcher,
                    "cameraOrientation",
                    "Lorg/joml/Quaternionf;",
                    new org.joml.Quaternionf());
            dispatcher.setLevel(level);
            var hands = mock(net.minecraft.client.renderer.ItemInHandRenderer.class, quiet());
            doReturn(ItemStack.EMPTY)
                    .when((com.tacz.guns.api.client.other.KeepingItemRenderer) hands)
                    .getCurrentItem();
            set(
                    EntityRenderDispatcher.class,
                    dispatcher,
                    "itemInHandRenderer",
                    "Lnet/minecraft/client/renderer/ItemInHandRenderer;",
                    hands);
            doReturn(dispatcher).when(minecraft).getEntityRenderDispatcher();

            levelRenderer = allocate(LevelRenderer.class);
            // Vanilla's field and EntityCulling's merged copy both name this dispatcher.
            for (Field field : LevelRenderer.class.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == EntityRenderDispatcher.class)
                    field.set(levelRenderer, dispatcher);
                else if (field.getName().equals("lateRenders"))
                    field.set(levelRenderer, new ArrayList<Runnable>());
            }

            // EntityCulling: its client entrypoint is not run; install its default config.
            cullingBase = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
            var mod = allocate(Class.forName("dev.tr7zw.entityculling.EntityCullingMod"));
            var versionless =
                    Class.forName(
                            "dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase");
            versionless
                    .getField("config")
                    .set(
                            mod,
                            Class.forName("dev.tr7zw.entityculling.versionless.Config")
                                    .getConstructor()
                                    .newInstance());
            cullingBase.getField("instance").set(null, mod);
            versionless.getField("enabled").setBoolean(null, true);
            cullable = Class.forName("dev.tr7zw.entityculling.versionless.access.Cullable");
            shadowRenderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            com.tacz.guns.api.client.other.ThirdPersonManager.registerDefault();
            BodyPartHitboxRenderer.evidence(true);
            // The connected server's forced policy (ADR4 §6): TaCZ Tweaks smoother crawl and the
            // ParCool geometry settings are on for every client, as after a READY admission.
            com.lunares.blockfield.client.combat.AnimationPolicy.arm(MixedRendererCases.POLICY);
            com.lunares.blockfield.client.combat.AnimationPolicy.install();
        }

        /** A constructor-free PlayerRenderer over a baked wide or slim player model. */
        private PlayerRenderer renderer(boolean slim) throws Exception {
            var renderer = allocate(PlayerRenderer.class);
            var model =
                    new PlayerModel<AbstractClientPlayer>(
                            LayerDefinition.create(
                                            PlayerModel.createMesh(CubeDeformation.NONE, slim),
                                            64,
                                            64)
                                    .bakeRoot(),
                            slim);
            set(
                    LivingEntityRenderer.class,
                    renderer,
                    "model",
                    "Lnet/minecraft/client/model/EntityModel;",
                    model);
            set(
                    LivingEntityRenderer.class,
                    renderer,
                    "layers",
                    "Ljava/util/List;",
                    new ArrayList<>());
            set(
                    EntityRenderer.class,
                    renderer,
                    "entityRenderDispatcher",
                    "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;",
                    dispatcher);
            // Constructor-installed mixin state (VanillaBackport's leash renderer) is recreated
            // from its dispatcher constructor; any other null renderer field is reported.
            for (Class<?> owner :
                    List.of(EntityRenderer.class, LivingEntityRenderer.class, PlayerRenderer.class))
                for (Field field : owner.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    if (field.get(renderer) != null) continue;
                    try {
                        field.set(
                                renderer,
                                field.getType()
                                        .getConstructor(EntityRenderDispatcher.class)
                                        .newInstance(dispatcher));
                        recreated.add(owner.getSimpleName() + "." + field.getName());
                    } catch (NoSuchMethodException none) {
                        nullFields.add(owner.getSimpleName() + "." + field.getName());
                    }
                }
            return renderer;
        }

        @Override
        public void close() throws Exception {
            release();
            com.lunares.blockfield.client.combat.AnimationPolicy.restore();
            com.lunares.blockfield.client.combat.AnimationPolicy.disarm();
            BodyPartHitboxRenderer.onDisconnect();
            shadowRenderer.getField("ACTIVE").setBoolean(null, false);
            cullingBase.getField("instance").set(null, null);
            config.close();
            if (singleton != null) singleton.close();
        }

        private void release() {
            FxPoseLive.close();
            if (player == null) return;
            DrivenPresentation.forget(player);
            RemoteLrCompat.detach(player);
            ClientAnimationAssets.clear();
            com.lunares.blockfield.client.combat.CombatVehicleScope.clearAll();
            if (vehicleData != null) {
                vehicleData.close();
                vehicleData = null;
            }
            player = null;
        }

        private void switchCase(MixedRendererCases.Case value) throws Exception {
            release();
            current = value;
            frames = new TreeMap<>();
            value.states()
                    .forEach(
                            (tick, state) ->
                                    frames.put(
                                            tick,
                                            SnapshotTransform.exact(
                                                    tick,
                                                    value.identity(),
                                                    0,
                                                    state.placement(),
                                                    state)));
            assets =
                    new ClientAnimationAssets.Generation(
                            0,
                            MixedRendererCases.POLICY,
                            value.tacz() != null
                                    ? value.tacz()
                                    : com.lunares.blockfield.oracle.FxPoseCases.taczAssets(value),
                            value.lr());
            ClientAnimationAssets.install(assets);
            player = player(MixedRendererCases.ENTITY, level, value.slim());
            var rotation =
                    ((IAnimatedPlayer) player).playerAnimator_getAnimation(RemoteLrCompat.ROTATION);
            var winner =
                    rotation == layer(player, LayerTag.PA_42) ? LayerTag.PA_42 : LayerTag.PA_96;
            assertEquals(MixedRendererCases.WINNER, winner, "native rotation alias winner");
        }

        MixedRendererCases.Sample sample(
                MixedRendererCases.Case value,
                MixedRendererCases.Frame frame,
                ObserverReducer.Result reduced)
                throws Exception {
            if (value != current) switchCase(value);
            var r = frame.r();
            var a = frame.a();
            var placement = SnapshotResolver.exact(frames, r);
            ClientCombatScene.write(player, placement);
            var animationEnd = AnimationTiming.endpoint(a);
            var animationRecord = frames.get(animationEnd.tick());
            live(player, animationRecord.checkpoint().vanilla());
            if (!value.rootVehicles().isEmpty()) vehicle(value, frame);

            FxPoseLive.frame(value, player);
            String why =
                    DrivenPresentation.install(
                            player,
                            placement.before(),
                            (float) placement.fraction(),
                            animationRecord,
                            animationEnd.partial(),
                            reduced,
                            assets,
                            a,
                            r.fraction());
            if (why != null) {
                MixedRendererCases.note("presentation:" + why);
                return null;
            }
            // The raw elytra getter is R-pinned only during rendering, never in gameplay.
            assertEquals(Vec3.ZERO, player.getDeltaMovement());
            var liveHand = player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            try (var scope = DrivenPresentation.scope(player)) {
                assertSame(
                        liveHand,
                        player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
                        "non-SBW readers retain the live stack inside the pass");
                assertEquals(
                        com.lunares.blockfield.client.combat.VanillaDrivenScope.lerpedVelocity(
                                player.getId()),
                        player.getDeltaMovement());
            }
            assertEquals(Vec3.ZERO, player.getDeltaMovement());
            if (value.name().equals(com.lunares.blockfield.oracle.FxPoseCases.SBW + "seat-root")) {
                var vehicle =
                        (com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
                                player.getVehicle();
                assertEquals(
                        1, vehicle.getSeatIndex(player), "native live seat contradicts checkpoint");
                assertEquals(1.1f, vehicle.getPassengerRenderScale());
                assertEquals(.5, vehicle.getRotateOffsetHeight());
                assertEquals(new Vec3(4, 3, -2), vehicle.getTurretPos());
                try (var scope = DrivenPresentation.scope(player)) {
                    assertEquals(0, vehicle.getSeatIndex(player), "retained direct/root seat");
                    assertEquals(
                            .75f, vehicle.getPassengerRenderScale(), "retained passenger scale");
                    assertEquals(0.0, vehicle.getRotateOffsetHeight(), "retained offset height");
                    assertEquals(new Vec3(0, 2, 0), vehicle.getTurretPos(), "retained turret");
                }
            }
            if (!value.rootVehicles().isEmpty()) {
                var vehicle =
                        (com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
                                player.getVehicle();
                assertEquals(new Vec3(4, 3, -2), vehicle.getBarrelPosition());
                assertEquals(new Vec3(-2, 4, 3), vehicle.getPassengerWeaponStationPosition());
                assertEquals(new Vec3(2, -1, 4), vehicle.getPassengerWeaponStationBarrelPosition());
                try (var scope = DrivenPresentation.scope(player)) {
                    assertEquals(new Vec3(0, 2, 1), vehicle.getBarrelPosition());
                    assertEquals(new Vec3(1, 1, 0), vehicle.getPassengerWeaponStationPosition());
                    assertEquals(
                            new Vec3(1, 1, 1), vehicle.getPassengerWeaponStationBarrelPosition());
                }
                assertEquals(new Vec3(4, 3, -2), vehicle.getBarrelPosition());
            }
            int id = player.getId();
            camera(player);
            BodyPartHitboxRenderer.frameEntry();
            BodyPartHitboxRenderer.levelPass(true);
            try {
                var shadowBefore = shadow(id);
                int lines = lines();
                render(AMBIENT);
                var main = BodyPartHitboxRenderer.mainCapture(id);
                if (main == null) {
                    MixedRendererCases.note("main_pass_not_rendered");
                    return null;
                }
                count("main");
                assertEquals(6 * 24 + 24 + 2, lines() - lines, "F3+B draws the MAIN capture");
                assertEquals(RenderCapture.Pass.MAIN, main.pass());
                assertTrue(main.rebaseExact(), "camera at the feet: exact feet-relative root");
                assertArrayEquals(
                        new double[] {placement.x(), placement.y(), placement.z()},
                        main.origin(),
                        "double origin is the R placement");
                render(AMBIENT_REPEAT);
                var repeat = BodyPartHitboxRenderer.mainCapture(id);
                count("mainRepeat");
                same(main, repeat, "a repeated main pass is fresh scratch");
                assertNotEquals(main.invocation(), repeat.invocation());
                var shadowAfter = shadow(id);
                assertSame(repeat, BodyPartHitboxRenderer.mainCapture(id), "shadow kept MAIN");
                same(main, shadowBefore, "shadow before main");
                same(main, shadowAfter, "shadow after main");
                if (frame.index() % 5 == 0) culled(id, main);
                return new MixedRendererCases.Sample(main.root(), main.parts(), main.origin());
            } finally {
                BodyPartHitboxRenderer.levelPass(false);
            }
        }

        String nativeLocal(AbstractClientPlayer local, float partial) throws Exception {
            player = local;
            camera(local);
            BodyPartHitboxRenderer.frameEntry();
            BodyPartHitboxRenderer.levelPass(true);
            try {
                render(partial);
                var capture = BodyPartHitboxRenderer.mainCapture(local.getId());
                assertNotNull(capture, "the local native MAIN render must produce evidence");
                return java.util.Arrays.toString(bits(capture.root()))
                        + ":"
                        + java.util.Arrays.toString(bits(capture.parts()))
                        + ":"
                        + java.util.Arrays.toString(bits(capture.corners()));
            } finally {
                BodyPartHitboxRenderer.levelPass(false);
            }
        }

        private void vehicle(MixedRendererCases.Case value, MixedRendererCases.Frame frame)
                throws Exception {
            var endpoint = AnimationTiming.endpoint(frame.r());
            var p = value.rootVehicles().get(endpoint.tick());
            var v =
                    mock(
                            com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity.class,
                            quiet().defaultAnswer(CALLS_REAL_METHODS));
            for (String name :
                    List.of("positionTransform", "vectorTransform", "rotationTransform")) {
                var field =
                        com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity.class
                                .getDeclaredField(name);
                field.setAccessible(true);
                field.set(v, new HashMap<>());
            }
            var register =
                    com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity.class
                            .getDeclaredMethod("registerTransforms");
            register.setAccessible(true);
            register.invoke(v);
            int id = MixedRendererCases.ENTITY + 1;
            doReturn(id).when(v).getId();
            set(Entity.class, v, "position", "Lnet/minecraft/world/phys/Vec3;", Vec3.ZERO);
            doReturn(Vec3.ZERO).when(v).getDeltaMovement();
            set(
                    Entity.class,
                    v,
                    "entityData",
                    "Lnet/minecraft/network/syncher/SynchedEntityData;",
                    synchedData(v));
            doAnswer(
                            call -> {
                                set(
                                        Entity.class,
                                        v,
                                        "position",
                                        "Lnet/minecraft/world/phys/Vec3;",
                                        new Vec3(
                                                call.getArgument(0),
                                                call.getArgument(1),
                                                call.getArgument(2)));
                                return null;
                            })
                    .when(v)
                    .setPos(anyDouble(), anyDouble(), anyDouble());
            var data =
                    mock(
                            com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData.class,
                            quiet());
            var seat = new com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo();
            seat.transform = value.rootTransform();
            seat.pose = "Pilot";
            boolean contradictSeat =
                    value.name()
                            .equals(com.lunares.blockfield.oracle.FxPoseCases.SBW + "seat-root");
            var otherSeat = new com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo();
            otherSeat.transform = "Default";
            otherSeat.pose = "MachineGunStand";
            doReturn(contradictSeat ? List.of(seat, otherSeat) : List.of(seat)).when(data).seats();
            doReturn(4f).when(data).getTurretCustomPitch();
            doReturn(new net.minecraft.world.phys.Vec2(-30, 30))
                    .when(data)
                    .getPassengerWeaponStationPitchRange();
            doReturn(contradictSeat ? 1.1f : .75f).when(data).getPassengerRenderScale();
            // The seat-root case's live offset height and turret contradict the retained reads.
            doReturn(contradictSeat ? .5f : 0f).when(data).getRotateOffsetHeight();
            doReturn(contradictSeat ? new Vec3(4, 3, -2) : new Vec3(0, 2, 0))
                    .when(data)
                    .getTurretPos();
            // Contradict the retained named-transform positions; only the scoped redirects recover
            // them.
            doReturn(new Vec3(4, 3, -2)).when(data).getBarrelPos();
            doReturn(new Vec3(-2, 4, 3)).when(data).getPassengerWeaponStationPos();
            doReturn(new Vec3(2, -1, 4)).when(data).getPassengerWeaponStationBarrelPos();
            doReturn(data).when(v).computed();
            // The native renderer resolves seat resource data through the static API as well.
            if (vehicleData == null)
                vehicleData =
                        mockStatic(
                                com.atsuishio.superbwarfare.data.vehicle.VehicleData.class,
                                quiet().defaultAnswer(CALLS_REAL_METHODS));
            vehicleData
                    .when(() -> com.atsuishio.superbwarfare.data.vehicle.VehicleData.compute(v))
                    .thenReturn(data);
            var passengers =
                    com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity.class
                            .getDeclaredField("orderedPassengers");
            passengers.setAccessible(true);
            passengers.set(
                    v, contradictSeat ? java.util.Arrays.asList(null, player) : List.of(player));
            set(Entity.class, player, "vehicle", "Lnet/minecraft/world/entity/Entity;", v);
            var a = p.angles();
            var x = p.vehicleAngles();
            var identity = value.identity();
            var vehicleIdentity =
                    new com.lunares.blockfield.common.combat.AnimationState.Identity(
                            identity.connectionId(),
                            identity.observerStreamEpoch(),
                            identity.sceneGeneration(),
                            id,
                            2,
                            identity.subscriptionGeneration(),
                            0,
                            0,
                            0,
                            identity.resourceGeneration(),
                            identity.policyGeneration(),
                            identity.compatibilityDigest(),
                            identity.manifestDigest());
            var records = new TreeMap<Long, SnapshotTransform>();
            value.rootVehicles()
                    .forEach(
                            (tick, placement) ->
                                    records.put(
                                            tick,
                                            SnapshotTransform.exact(
                                                    tick, vehicleIdentity, 0, placement, null)));
            ClientCombatScene.write(v, SnapshotResolver.exact(records, frame.r()));
            com.lunares.blockfield.client.combat.CombatVehicleScope.install(
                    id,
                    new com.lunares.blockfield.client.combat.CombatVehicleScope.VehicleFrame(
                            new Vec3(
                                    p.previousOrigin().x(),
                                    p.previousOrigin().y(),
                                    p.previousOrigin().z()),
                            new Vec3(p.origin().x(), p.origin().y(), p.origin().z()),
                            a.yRotO(),
                            a.yRot(),
                            a.xRotO(),
                            a.xRot(),
                            x.prevRoll(),
                            x.roll(),
                            x.turretYRotO(),
                            x.turretYRot(),
                            x.turretXRotO(),
                            x.turretXRot(),
                            x.gunYRotO(),
                            x.gunYRot(),
                            x.gunXRotO(),
                            x.gunXRot(),
                            endpoint.partial(),
                            frame.index()));
        }

        /** Iris shadow pass: Iris's own flag is what the production pass detection reads. */
        private RenderCapture shadow(int id) throws Exception {
            var active = shadowRenderer.getField("ACTIVE");
            active.setBoolean(null, true);
            try {
                render(AMBIENT);
            } finally {
                active.setBoolean(null, false);
            }
            var capture = BodyPartHitboxRenderer.shadowCapture(id);
            assertNotNull(capture, "shadow pass captured");
            assertEquals(RenderCapture.Pass.SHADOW, capture.pass());
            count("shadow");
            return capture;
        }

        /**
         * A new frame culled by EntityCulling's real renderEntity gate: no capture, F3+B silent,
         * evidence {@code not_rendered}; with culling disabled the same frame renders again.
         */
        private void culled(int id, RenderCapture main) throws Exception {
            BodyPartHitboxRenderer.frameEntry();
            var set = cullable.getMethod("setCulled", boolean.class);
            // EntityCulling keeps an unculled entity forced visible for a timeout; let it lapse.
            var lasttime = Entity.class.getDeclaredField("lasttime");
            lasttime.setAccessible(true);
            lasttime.setLong(player, 0);
            set.invoke(player, true);
            try {
                int lines = lines();
                render(AMBIENT);
                assertNull(BodyPartHitboxRenderer.mainCapture(id), "culled main is not rendered");
                assertEquals(lines, lines(), "no F3+B for a culled entity");
                assertEquals(
                        BodyPartHitboxRenderer.NOT_RENDERED,
                        BodyPartHitboxRenderer.captureSnapshot(id).get("status"));
                count("culledNotRendered");
                var enabled =
                        Class.forName(
                                        "dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase")
                                .getField("enabled");
                enabled.setBoolean(null, false);
                try {
                    render(AMBIENT);
                } finally {
                    enabled.setBoolean(null, true);
                }
                var unculled = BodyPartHitboxRenderer.mainCapture(id);
                assertNotNull(unculled, "EntityCulling off renders the culled entity");
                same(main, unculled, "culling off");
                count("cullingOff");
            } finally {
                set.invoke(player, false);
            }
        }

        void render(float ambient) throws Exception {
            var renderEntity =
                    OracleReflection.method(
                            LevelRenderer.class,
                            "renderEntity",
                            "(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
                            Entity.class,
                            double.class,
                            double.class,
                            double.class,
                            float.class,
                            PoseStack.class,
                            MultiBufferSource.class);
            var position = camera.getPosition();
            renderEntity.invoke(
                    levelRenderer,
                    player,
                    position.x,
                    position.y,
                    position.z,
                    ambient,
                    new PoseStack(),
                    source);
        }

        void camera(Entity entity) throws Exception {
            set(
                    Camera.class,
                    camera,
                    "position",
                    "Lnet/minecraft/world/phys/Vec3;",
                    new Vec3(entity.getX(), entity.getY(), entity.getZ()));
        }

        private int lines() {
            var lines = buffers.get(net.minecraft.client.renderer.RenderType.lines().toString());
            return lines == null ? 0 : lines.vertices;
        }

        private void count(String pass) {
            passes.merge(pass, 1, Integer::sum);
        }
    }

    /**
     * The merged mixin handler calls of the six-box pipeline methods in execution order (P2-9 hook
     * order evidence), keyed by named owner and method.
     */
    private static Map<String, Object> hookOrder() {
        var mappings = net.fabricmc.loader.api.FabricLoader.getInstance().getMappingResolver();
        var order = OracleTransformAudit.hookOrder();
        var out = new LinkedHashMap<String, Object>();
        for (String[] method :
                new String[][] {
                    {
                        "net.minecraft.client.model.HumanoidModel",
                        "setupAnim",
                        "(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V"
                    },
                    {
                        "net.minecraft.client.model.PlayerModel",
                        "setupAnim",
                        "(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V"
                    },
                    {
                        "net.minecraft.client.renderer.entity.LivingEntityRenderer",
                        "render",
                        "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
                    },
                    {
                        "net.minecraft.client.renderer.entity.LivingEntityRenderer",
                        "setupRotations",
                        "(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V"
                    },
                    {
                        "net.minecraft.client.renderer.entity.player.PlayerRenderer",
                        "setupRotations",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V"
                    },
                    {
                        "net.minecraft.client.renderer.entity.player.PlayerRenderer",
                        "render",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
                    },
                    {
                        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
                        "render",
                        "(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
                    },
                    {
                        "net.minecraft.client.renderer.LevelRenderer",
                        "renderEntity",
                        "(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
                    }
                }) {
            String owner = mappings.mapClassName("named", method[0]);
            String name = mappings.mapMethodName("named", method[0], method[1], method[2]);
            var matcher = java.util.regex.Pattern.compile("L([^;]+);").matcher(method[2]);
            var desc = new StringBuilder();
            while (matcher.find())
                matcher.appendReplacement(
                        desc,
                        "L"
                                + mappings.mapClassName("named", matcher.group(1).replace('/', '.'))
                                        .replace('.', '/')
                                + ";");
            matcher.appendTail(desc);
            var calls = order.getOrDefault(owner, Map.of()).get(name + desc);
            out.put(
                    method[0].substring(method[0].lastIndexOf('.') + 1) + "." + method[1],
                    calls == null ? List.of() : calls);
        }
        // Every hooked method of the audited classes, by runtime name (overloads and bridges).
        out.put("allByRuntimeName", order);
        return out;
    }

    /**
     * Mocks that never record invocations: thousands of render passes would otherwise retain every
     * call (and its arguments) for verification.
     */
    private static org.mockito.MockSettings quiet() {
        return withSettings().stubOnly();
    }

    /** Options with entity shadows off (the dispatcher's only read in these passes). */
    private static Options options() {
        var options = mock(Options.class, quiet());
        @SuppressWarnings("unchecked")
        net.minecraft.client.OptionInstance<Boolean> shadows =
                mock(net.minecraft.client.OptionInstance.class, quiet());
        doReturn(false).when(shadows).get();
        doReturn(shadows).when(options).entityShadows();
        doReturn(net.minecraft.client.CameraType.THIRD_PERSON_BACK).when(options).getCameraType();
        return options;
    }

    private static void same(RenderCapture expected, RenderCapture actual, String label) {
        assertArrayEquals(bits(expected.root()), bits(actual.root()), label + ": root");
        assertArrayEquals(bits(expected.parts()), bits(actual.parts()), label + ": parts");
        assertArrayEquals(bits(expected.corners()), bits(actual.corners()), label + ": corners");
    }

    private static int[] bits(float[] values) {
        var out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = Float.floatToRawIntBits(values[i]);
        return out;
    }

    /**
     * Live entity state that contradicts the checkpoint wherever the driven scope must supply the
     * value (attribute scale 1.25, standing bbHeight, the opposite water and alive states): a live
     * read reaching the six boxes shows up as a native/server difference.
     */
    private static void live(
            AbstractClientPlayer player,
            com.lunares.blockfield.common.combat.AnimationState.Vanilla v)
            throws Exception {
        player.getEntityData()
                .set(accessor(LivingEntity.class, "DATA_HEALTH_ID"), v.flags().alive() ? 0f : 20f);
        set(Entity.class, player, "wasTouchingWater", "Z", !v.flags().inWater());
    }

    /**
     * A constructor-free remote player with real synced data and PA attachments: getters the driven
     * scope covers run their real (transformed) bodies; only constructor-owned services (skin
     * lookup, attributes, inventory, player info) are stubbed with neutral values.
     */
    static RemotePlayer player(int id, ClientLevel level, boolean slim) throws Exception {
        return player(RemotePlayer.class, id, level, slim);
    }

    static <T extends AbstractClientPlayer> T player(
            Class<T> type, int id, ClientLevel level, boolean slim) throws Exception {
        var player = mock(type, quiet().defaultAnswer(CALLS_REAL_METHODS));
        var stack = new AnimationStack();
        var data = new HashMap<ResourceLocation, IAnimation>();
        for (Field field : Player.class.getDeclaredFields()) {
            if (field.getType() == AnimationStack.class) {
                field.setAccessible(true);
                field.set(player, stack);
            } else if (field.getType() == AnimationApplier.class) {
                field.setAccessible(true);
                field.set(player, new AnimationApplier(stack));
            } else if (field.getName().equals("modAnimationData")) {
                field.setAccessible(true);
                field.set(player, data);
            }
        }
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.prepareAnimations(player, stack, data);
        var uuid = new UUID(0x6d697865644c4cL, id);
        if (player instanceof LocalPlayer)
            set(
                    Player.class,
                    player,
                    "gameProfile",
                    "Lcom/mojang/authlib/GameProfile;",
                    new com.mojang.authlib.GameProfile(uuid, "oracle-local"));
        set(Entity.class, player, "uuid", "Ljava/util/UUID;", uuid);
        set(Entity.class, player, "stringUUID", "Ljava/lang/String;", uuid.toString());
        set(
                Entity.class,
                player,
                "levelCallback",
                "Lnet/minecraft/world/level/entity/EntityInLevelCallback;",
                net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
        set(Entity.class, player, "position", "Lnet/minecraft/world/phys/Vec3;", Vec3.ZERO);
        set(Entity.class, player, "deltaMovement", "Lnet/minecraft/world/phys/Vec3;", Vec3.ZERO);
        set(Entity.class, player, "blockPosition", "Lnet/minecraft/core/BlockPos;", BlockPos.ZERO);
        set(
                Entity.class,
                player,
                "bb",
                "Lnet/minecraft/world/phys/AABB;",
                new AABB(-.3, 0, -.3, .3, 1.8, .3));
        set(
                Entity.class,
                player,
                "passengers",
                "Lcom/google/common/collect/ImmutableList;",
                com.google.common.collect.ImmutableList.of());
        set(
                AbstractClientPlayer.class,
                player,
                "deltaMovementOnPreviousTick",
                "Lnet/minecraft/world/phys/Vec3;",
                Vec3.ZERO);
        set(
                LivingEntity.class,
                player,
                "walkAnimation",
                "Lnet/minecraft/world/entity/WalkAnimationState;",
                new WalkAnimationState());
        set(
                LivingEntity.class,
                player,
                "useItem",
                "Lnet/minecraft/world/item/ItemStack;",
                ItemStack.EMPTY);
        set(
                Entity.class,
                player,
                "entityData",
                "Lnet/minecraft/network/syncher/SynchedEntityData;",
                synchedData(player));
        set(Entity.class, player, "level", "Lnet/minecraft/world/level/Level;", level);
        set(
                Player.class,
                player,
                "abilities",
                "Lnet/minecraft/world/entity/player/Abilities;",
                new net.minecraft.world.entity.player.Abilities());
        var attributes =
                new net.minecraft.world.entity.ai.attributes.AttributeMap(
                        Player.createAttributes().build());
        attributes.getInstance(Attributes.SCALE).setBaseValue(1.25);
        set(
                LivingEntity.class,
                player,
                "attributes",
                "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;",
                attributes);
        doReturn(id).when(player).getId();
        doReturn(level).when(player).level();
        doReturn(EntityType.PLAYER).when(player).getType();
        doReturn(
                        new PlayerSkin(
                                ResourceLocation.withDefaultNamespace(
                                        slim
                                                ? "textures/entity/player/slim/alex.png"
                                                : "textures/entity/player/wide/steve.png"),
                                null,
                                null,
                                null,
                                slim ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE,
                                true))
                .when(player)
                .getSkin();
        doReturn(false).when(player).isSpectator();
        doReturn(false).when(player).isCreative();
        doReturn(null).when(player).getTeam();
        doReturn(ItemStack.EMPTY).when(player).getItemBySlot(any());
        doReturn(ItemStack.EMPTY).when(player).getMainHandItem();
        doReturn(ItemStack.EMPTY).when(player).getOffhandItem();
        doReturn(ItemStack.EMPTY).when(player).getItemInHand(any());
        doReturn(ItemStack.EMPTY).when(player).getUseItem();
        doReturn(new net.minecraft.world.entity.player.Abilities()).when(player).getAbilities();
        doAnswer(
                        call -> {
                            double x = call.getArgument(0), y = call.getArgument(1);
                            double z = call.getArgument(2);
                            set(
                                    Entity.class,
                                    player,
                                    "position",
                                    "Lnet/minecraft/world/phys/Vec3;",
                                    new Vec3(x, y, z));
                            set(
                                    Entity.class,
                                    player,
                                    "blockPosition",
                                    "Lnet/minecraft/core/BlockPos;",
                                    BlockPos.containing(x, y, z));
                            set(
                                    Entity.class,
                                    player,
                                    "bb",
                                    "Lnet/minecraft/world/phys/AABB;",
                                    new AABB(x - .3, y, z - .3, x + .3, y + 1.8, z + .3));
                            return null;
                        })
                .when(player)
                .setPos(anyDouble(), anyDouble(), anyDouble());
        var standing = player.getDimensions(net.minecraft.world.entity.Pose.STANDING);
        set(
                Entity.class,
                player,
                "dimensions",
                "Lnet/minecraft/world/entity/EntityDimensions;",
                standing);
        set(Entity.class, player, "eyeHeight", "F", standing.eyeHeight());
        doAnswer(call -> List.of(player)).when(level).players();
        return player;
    }

    /** The entity's real synced data: Entity's constructor items, then the class chain's own. */
    private static SynchedEntityData synchedData(Entity entity) throws Exception {
        var builder = new SynchedEntityData.Builder(entity);
        builder.define(accessor(Entity.class, "DATA_SHARED_FLAGS_ID"), (byte) 0);
        builder.define(accessor(Entity.class, "DATA_AIR_SUPPLY_ID"), 300);
        builder.define(accessor(Entity.class, "DATA_CUSTOM_NAME_VISIBLE"), false);
        builder.define(accessor(Entity.class, "DATA_CUSTOM_NAME"), Optional.empty());
        builder.define(accessor(Entity.class, "DATA_SILENT"), false);
        builder.define(accessor(Entity.class, "DATA_NO_GRAVITY"), false);
        builder.define(
                accessor(Entity.class, "DATA_POSE"), net.minecraft.world.entity.Pose.STANDING);
        builder.define(accessor(Entity.class, "DATA_TICKS_FROZEN"), 0);
        OracleReflection.method(
                        Entity.class,
                        "defineSynchedData",
                        "(Lnet/minecraft/network/syncher/SynchedEntityData$Builder;)V",
                        SynchedEntityData.Builder.class)
                .invoke(entity, builder);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> accessor(Class<?> owner, String name)
            throws Exception {
        var field =
                OracleReflection.field(
                        owner, name, "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        field.setAccessible(true);
        return (EntityDataAccessor<T>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static dev.kosmx.playerAnim.api.layered.ModifierLayer<IAnimation> layer(
            AbstractClientPlayer player, LayerTag tag) throws Exception {
        var field = AnimationStack.class.getDeclaredField("layers");
        field.setAccessible(true);
        for (var entry :
                (List<dev.kosmx.playerAnim.core.util.Pair<Integer, IAnimation>>)
                        field.get(((IAnimatedPlayer) player).getAnimationStack()))
            if (entry.getLeft() == AnimationGraphState.priority(tag))
                return (dev.kosmx.playerAnim.api.layered.ModifierLayer<IAnimation>)
                        entry.getRight();
        throw new AssertionError("no native layer " + tag);
    }

    private static Vec3 vec(com.lunares.blockfield.common.combat.AnimationState.Vec3 value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static void set(
            Class<?> owner, Object target, String name, String descriptor, Object value)
            throws Exception {
        var field = OracleReflection.field(owner, name, descriptor);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        return (T)
                unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    /** Counts vertices per render type; F3+B lines are asserted, body quads are discarded. */
    private static final class Recording implements VertexConsumer {
        int vertices;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
