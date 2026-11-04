package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.CreateHanging;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U8b native differential per driven input (ADR4 §§4.2/4.5/5.3): the inputs U8 left live on the
 * client now come from the authoritative checkpoint inside a driven remote's render pass. Each case
 * runs the production-transformed native consumer three times, on mocked remote players without a
 * constructor:
 *
 * <ul>
 *   <li>D, driven through the real journal/presentation path, whose live entity state (synced
 *       flags, fields, Create's hanging set and Catnip clock) contradicts its checkpoint;
 *   <li>B, undriven, whose live state equals that checkpoint (the native reference);
 *   <li>C, undriven, with D's contradicting live state (sensitivity).
 * </ul>
 *
 * D must equal B bit for bit and B must differ from C: the native code consumed exactly the
 * checkpoint value, and the input matters. D's getters read the checkpoint only inside its scope
 * and the live value outside it. The server consumes the same checkpoint fields ({@code
 * AnimSyncEval}, {@code VanillaAnimSync.project}, {@code CreateBodyPose.apply} through {@code
 * CreateHanging.phase}); the Create case compares the native pose with the server port's mirror.
 * {@code LivingEntityRenderer.render} itself (riding's {@code model.riding} and walk gate, the bed
 * translation) is not executed here: its getters are pinned and asserted in scope; the full mixed
 * renderer is the U10 gate.
 */
class DrivenInputsOracleFixture {
    private static final long MS = 1_000_000;
    private static final float AGE = 33.25f;
    private static final float BODY_YAW = 30f;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Shared PA factory registration (as SceneTimingOracleFixture's bootstrap).
        SceneTimingOracleFixture.bootstrap();
    }

    // -- cases ------------------------------------------------------------------------------

    /** One item-use state: the checkpoint value and B's native live equipment producing it. */
    private record Use(
            String name,
            AnimationState.ItemUse checkpoint,
            ItemStack main,
            ItemStack off,
            InteractionHand used,
            int remaining,
            int ticks) {}

    private static List<Use> uses() {
        return List.of(
                new Use(
                        "bow-draw-main",
                        use(
                                true,
                                AnimationTypes.Hand.MAIN_HAND,
                                71_990,
                                10,
                                "BOW_AND_ARROW",
                                "EMPTY",
                                AnimationTypes.UseAnimation.BOW,
                                72_000,
                                25),
                        new ItemStack(Items.BOW),
                        ItemStack.EMPTY,
                        InteractionHand.MAIN_HAND,
                        71_990,
                        10),
                new Use(
                        "crossbow-charge-main",
                        use(
                                true,
                                AnimationTypes.Hand.MAIN_HAND,
                                71_993,
                                7,
                                "CROSSBOW_CHARGE",
                                "EMPTY",
                                AnimationTypes.UseAnimation.CROSSBOW,
                                72_000,
                                25),
                        new ItemStack(Items.CROSSBOW),
                        ItemStack.EMPTY,
                        InteractionHand.MAIN_HAND,
                        71_993,
                        7),
                new Use(
                        "shield-block-off",
                        use(
                                true,
                                AnimationTypes.Hand.OFF_HAND,
                                71_000,
                                1_000,
                                "ITEM",
                                "BLOCK",
                                AnimationTypes.UseAnimation.BLOCK,
                                72_000,
                                25),
                        new ItemStack(Items.STICK),
                        new ItemStack(Items.SHIELD),
                        InteractionHand.OFF_HAND,
                        71_000,
                        1_000),
                new Use(
                        "spyglass-main",
                        use(
                                true,
                                AnimationTypes.Hand.MAIN_HAND,
                                1_190,
                                10,
                                "SPYGLASS",
                                "EMPTY",
                                AnimationTypes.UseAnimation.SPYGLASS,
                                1_200,
                                25),
                        new ItemStack(Items.SPYGLASS),
                        ItemStack.EMPTY,
                        InteractionHand.MAIN_HAND,
                        1_190,
                        10));
    }

    private static AnimationState.ItemUse use(
            boolean using,
            AnimationTypes.Hand hand,
            int remaining,
            int ticks,
            String main,
            String off,
            AnimationTypes.UseAnimation animation,
            int duration,
            int charge) {
        return new AnimationState.ItemUse(
                using,
                hand,
                remaining,
                ticks,
                AnimationTypes.ArmPose.valueOf(main),
                AnimationTypes.ArmPose.valueOf(off),
                animation,
                duration,
                charge);
    }

    @Test
    void itemUseArmPosesFollowTheCheckpoint() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "u8b-driven-item-use-arm-poses",
                () -> {
                    var rows = new ArrayList<Object>();
                    for (var use : uses()) {
                        var world =
                                new SceneTimingOracleFixture.World(
                                        v -> with(right(v), "itemUse", use.checkpoint()));
                        try {
                            var d = drive(world);
                            // D's live state: empty hands, nothing in use (synced flags 0).
                            var live = live(d);
                            assertFalse(d.isUsingItem(), "outside its scope D reads live state");
                            assertEquals(0, d.getTicksUsingItem());
                            int[] driven;
                            try (var scope = DrivenPresentation.scope(d)) {
                                assertTrue(d.isUsingItem());
                                assertEquals(use.used(), d.getUsedItemHand());
                                assertEquals(use.remaining(), d.getUseItemRemainingTicks());
                                assertEquals(use.ticks(), d.getTicksUsingItem());
                            }
                            driven = model(d, DrivenInputsOracleFixture::properties);
                            var b = SceneTimingOracleFixture.player(701);
                            undriven(b, world);
                            holding(b, use);
                            int[] reference = model(b, DrivenInputsOracleFixture::properties);
                            var c = SceneTimingOracleFixture.player(702);
                            undriven(c, world);
                            int[] contradicting = model(c, DrivenInputsOracleFixture::properties);
                            assertArrayEquals(reference, driven, use.name() + ": D == B");
                            assertFalse(
                                    Arrays.equals(reference, contradicting),
                                    use.name() + ": the input moves the native pose");
                            assertFalse(live.isEmpty());
                            var row = new LinkedHashMap<String, Object>();
                            row.put("case", use.name());
                            row.put("bits", driven);
                            rows.add(row);
                        } finally {
                            world.close();
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var out = new LinkedHashMap<String, Object>();
                    out.put("cases", rows);
                    out.put(
                            "consumers",
                            "PlayerRenderer.setModelProperties (getArmPose, two-handed rule),"
                                    + " PlayerModel.prepareMobModel/setupAnim incl."
                                    + " AnimationUtils.animateCrossbowCharge");
                    return out;
                });
    }

    /** One root-stage state: the checkpoint rewrite and B's native live state producing it. */
    private record Root(
            String name,
            UnaryOperator<AnimationState.Vanilla> checkpoint,
            Consumer<AbstractClientPlayer> native_) {}

    private static List<Root> roots() {
        return List.of(
                new Root(
                        "elytra",
                        v ->
                                with(
                                        with(
                                                with(v, "pose", AnimationTypes.Pose.FALL_FLYING),
                                                "flags",
                                                with(v.flags(), "fallFlying", true)),
                                        "vital",
                                        with(v.vital(), "fallFlyTicks", 12)),
                        p -> {
                            doReturn(true).when(p).isFallFlying();
                            doReturn(12).when(p).getFallFlyingTicks();
                            doReturn(Pose.FALL_FLYING).when(p).getPose();
                        }),
                new Root(
                        "riptide",
                        v ->
                                with(
                                        with(v, "pose", AnimationTypes.Pose.SPIN_ATTACK),
                                        "flags",
                                        with(v.flags(), "autoSpinAttack", true)),
                        p -> {
                            doReturn(true).when(p).isAutoSpinAttack();
                            doReturn(Pose.SPIN_ATTACK).when(p).getPose();
                        }),
                new Root(
                        "freezing",
                        v ->
                                with(
                                        with(v, "flags", with(v.flags(), "fullyFrozen", true)),
                                        "vital",
                                        with(v.vital(), "ticksFrozen", 140)),
                        p -> {
                            doReturn(true).when(p).isFullyFrozen();
                            doReturn(140).when(p).getTicksFrozen();
                        }),
                new Root(
                        "bed-east",
                        v ->
                                with(
                                        with(
                                                with(v, "pose", AnimationTypes.Pose.SLEEPING),
                                                "bedOrientation",
                                                AnimationTypes.Direction.EAST),
                                        "size",
                                        with(v.size(), "standingEyeHeight", 1.62f)),
                        p -> {
                            doReturn(Direction.EAST).when(p).getBedOrientation();
                            doReturn(Pose.SLEEPING).when(p).getPose();
                        }),
                new Root(
                        "upside-down",
                        v -> with(v, "flags", with(v.flags(), "upsideDown", true)),
                        p -> doReturn(Component.literal("Dinnerbone")).when(p).getName()));
    }

    @Test
    void rootFlagsFollowTheCheckpoint() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "u8b-driven-root-flags",
                () -> {
                    var rows = new ArrayList<Object>();
                    for (var root : roots()) {
                        var world =
                                new SceneTimingOracleFixture.World(
                                        v -> root.checkpoint().apply(right(v)));
                        try {
                            var d = drive(world);
                            live(d);
                            var vanilla = world.frames.get(101L).checkpoint().vanilla();
                            // Pose and fall-fly ticks are written A-domain state (U8): on this
                            // constructor-free mock setPose is inert, so stub what it would set.
                            doReturn(Pose.valueOf(vanilla.pose().name())).when(d).getPose();
                            doCallRealMethod().when(d).getFallFlyingTicks();
                            assertFalse(
                                    d.isFallFlying() || d.isAutoSpinAttack() || d.isFullyFrozen());
                            assertNull(d.getBedOrientation());
                            try (var scope = DrivenPresentation.scope(d)) {
                                assertEquals(vanilla.flags().fallFlying(), d.isFallFlying());
                                assertEquals(
                                        vanilla.flags().autoSpinAttack(), d.isAutoSpinAttack());
                                assertEquals(vanilla.flags().fullyFrozen(), d.isFullyFrozen());
                                assertEquals(vanilla.vital().ticksFrozen(), d.getTicksFrozen());
                                assertEquals(
                                        vanilla.bedOrientation() == null
                                                ? null
                                                : Direction.valueOf(
                                                        vanilla.bedOrientation().name()),
                                        d.getBedOrientation());
                                assertEquals(
                                        vanilla.flags().upsideDown(),
                                        LivingEntityRenderer.isEntityUpsideDown(d));
                                if (vanilla.bedOrientation() != null)
                                    assertEquals(
                                            vanilla.size().standingEyeHeight(),
                                            d.getEyeHeight(Pose.STANDING),
                                            "the bed offset's standing eye height");
                            }
                            int[] driven = root(d);
                            var b = SceneTimingOracleFixture.player(711);
                            undriven(b, world);
                            b.tickCount = d.tickCount;
                            root.native_().accept(b);
                            int[] reference = root(b);
                            var c = SceneTimingOracleFixture.player(712);
                            undriven(c, world);
                            c.tickCount = d.tickCount;
                            int[] contradicting = root(c);
                            assertArrayEquals(reference, driven, root.name() + ": D == B");
                            assertFalse(
                                    Arrays.equals(reference, contradicting),
                                    root.name() + ": the input moves the native root");
                            var row = new LinkedHashMap<String, Object>();
                            row.put("case", root.name());
                            row.put("rootBits", driven);
                            rows.add(row);
                        } finally {
                            world.close();
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var out = new LinkedHashMap<String, Object>();
                    out.put("cases", rows);
                    out.put(
                            "consumers",
                            "PlayerRenderer.setupRotations -> LivingEntityRenderer.setupRotations"
                                    + " (isShaking, auto-spin, sleep rotation, upside-down), with"
                                    + " every production mixin on them");
                    return out;
                });
    }

    @Test
    void ridingAndClimbingFollowTheCheckpoint() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "u8b-driven-riding-climbing",
                () -> {
                    var out = new LinkedHashMap<String, Object>();
                    for (boolean riding : new boolean[] {false, true}) {
                        var world =
                                new SceneTimingOracleFixture.World(
                                        v ->
                                                with(
                                                        right(v),
                                                        "flags",
                                                        with(
                                                                with(
                                                                        v.flags(),
                                                                        "passenger",
                                                                        riding),
                                                                "climbing",
                                                                !riding)));
                        try {
                            var d = drive(world);
                            live(d);
                            // Live riding is the opposite of the checkpoint.
                            vehicle(d, riding ? null : mock(Entity.class));
                            assertEquals(!riding, d.isPassenger(), "outside its scope D is live");
                            Consumer<PlayerModel<AbstractClientPlayer>> render;
                            int[] driven;
                            try (var scope = DrivenPresentation.scope(d)) {
                                assertEquals(riding, d.isPassenger());
                                assertEquals(!riding, d.onClimbable());
                            }
                            // LivingEntityRenderer.render: model.riding = entity.isPassenger(),
                            // read inside the pass scope like every other getter.
                            render = model -> model.riding = d.isPassenger();
                            driven = model(d, render);
                            var b = SceneTimingOracleFixture.player(721);
                            undriven(b, world);
                            doReturn(riding).when(b).isPassenger();
                            int[] reference = model(b, model -> model.riding = b.isPassenger());
                            var c = SceneTimingOracleFixture.player(722);
                            undriven(c, world);
                            doReturn(!riding).when(c).isPassenger();
                            int[] contradicting = model(c, model -> model.riding = c.isPassenger());
                            assertArrayEquals(reference, driven, "riding " + riding + ": D == B");
                            assertFalse(Arrays.equals(reference, contradicting));
                            out.put("riding." + riding, driven);
                        } finally {
                            world.close();
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    out.put(
                            "boundary",
                            "LivingEntityRenderer.render is not executed: its riding statement"
                                    + " reads the scoped isPassenger; onClimbable readers (TaCZ"
                                    + " InnerThirdPersonManager fallback, LR IdleAnimationHandler"
                                    + " tick) see the pinned getter");
                    return out;
                });
    }

    @Test
    void createHangingFollowsTheReducedMembershipAndPhase() {
        AnimSyncOracleRunner.run(
                "create",
                "u8b-driven-hanging-membership-phase",
                () -> {
                    var world =
                            new SceneTimingOracleFixture.World(DrivenInputsOracleFixture::right);
                    var create = Create.load();
                    try {
                        var d = drive(world);
                        live(d);
                        var id = new UUID(7, 610);
                        doReturn(id).when(d).getUUID();
                        // Create's live set says D hangs and its clock runs elsewhere.
                        create.reset();
                        create.drive(777);
                        create.update(List.of(id));
                        int[] notMember = model(d, model -> {});
                        var b = SceneTimingOracleFixture.player(731);
                        undriven(b, world);
                        doReturn(new UUID(7, 731)).when(b).getUUID();
                        int[] nativeIdle = model(b, model -> {});
                        assertArrayEquals(
                                nativeIdle, notMember, "not a reduced member: no hanging pose");
                        // An accepted enter applied at A = 101.25 starts the phase at sA 101.
                        var enter = world.create(102, 102 * 256 + 1, 0, 5_060 * MS, true);
                        assertTrue(world.apply(enter, 5_062 * MS));
                        assertNull(world.present(world.stamp(new Time(101, 0x4000), 5_063 * MS)));
                        var a = new Time(104, 0x8000);
                        assertNull(world.present(world.stamp(a, 5_200 * MS)));
                        var start = world.journal.presentation(610).create();
                        assertNotNull(start, "the reduced membership start");
                        assertEquals(101, start.phaseAnchor());
                        assertEquals(0, start.u0());
                        create.update(List.of());
                        int[] member = model(d, model -> {});
                        float phase =
                                CreateHanging.phase(
                                        start.u0(), start.phaseAnchor(), a.tick(), a.fraction());
                        assertEquals(3.5f, phase);
                        int[] expected = CreateHangingOracleFixture.expectedBits(pivots(), phase);
                        assertArrayEquals(
                                expected,
                                Arrays.copyOf(member, 36),
                                "native setHangingPose at the server port's phase");
                        // Sensitivity: an undriven member hangs at the observer clock.
                        create.update(List.of(new UUID(7, 731)));
                        int[] observer = model(b, model -> {});
                        assertFalse(Arrays.equals(Arrays.copyOf(observer, 36), expected));
                        var out = new LinkedHashMap<String, Object>();
                        out.put("phase", phase);
                        out.put("phaseBits", Float.floatToRawIntBits(phase));
                        out.put("memberBits", member);
                        out.put("observerClockTicks", 777);
                        OracleTransformAudit.requireConfig("create.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return out;
                    } finally {
                        create.reset();
                        create.update(List.of());
                        world.close();
                    }
                });
    }

    // -- native plumbing ---------------------------------------------------------------------

    /** Create's renderer membership set and the Catnip observer clock. */
    private record Create(Method updateList, Method resetClock, Method tickClock) {
        static Create load() throws Exception {
            Class<?> renderer = Class.forName(CreateHanging.RENDERER);
            Class<?> clock = Class.forName(CreateHanging.CLOCK);
            return new Create(
                    renderer.getMethod("updatePlayerList", java.util.Collection.class),
                    clock.getMethod("reset"),
                    clock.getMethod("tick"));
        }

        void update(List<UUID> ids) throws Exception {
            updateList.invoke(null, ids);
        }

        void reset() throws Exception {
            resetClock.invoke(null);
        }

        void drive(int ticks) throws Exception {
            for (int i = 0; i < ticks; i++) tickClock.invoke(null);
        }
    }

    /** Presents the world's driven remote at one frame and returns it. */
    private static AbstractClientPlayer drive(SceneTimingOracleFixture.World world) {
        var stamp = world.stamp(new Time(101, 0x4000), 5_050 * MS);
        assertNull(world.present(stamp), "driven presentation");
        assertTrue(DrivenPresentation.driven(world.driven));
        common(world.driven);
        return world.driven;
    }

    /** Stubs every player shares (no constructor ran): model parts, name, facing inputs. */
    private static void common(AbstractClientPlayer player) {
        doReturn(true).when(player).isModelPartShown(any());
        doReturn(Component.literal("Steve")).when(player).getName();
        doReturn(1.8f).when(player).getBbHeight();
        doReturn(Vec3.ZERO).when(player).getDeltaMovement();
        doReturn(true).when(player).isAlive();
        doReturn(false).when(player).isFullyFrozen();
        doReturn(0).when(player).getTicksFrozen();
        doReturn(null).when(player).getBedOrientation();
    }

    /** An undriven player in the world's level, with the driven remote's written A state. */
    private static void undriven(AbstractClientPlayer player, SceneTimingOracleFixture.World world)
            throws Exception {
        common(player);
        doReturn(world.driven.level()).when(player).level();
        var previous =
                OracleReflection.field(
                        AbstractClientPlayer.class,
                        "deltaMovementOnPreviousTick",
                        "Lnet/minecraft/world/phys/Vec3;");
        previous.setAccessible(true);
        previous.set(player, Vec3.ZERO);
        player.tickCount = world.driven.tickCount;
    }

    /** B's live equipment and use state natively producing {@code use}'s checkpoint. */
    private static void holding(AbstractClientPlayer player, Use use) {
        doReturn(use.main()).when(player).getMainHandItem();
        doReturn(use.off()).when(player).getOffhandItem();
        doReturn(use.main()).when(player).getItemBySlot(EquipmentSlot.MAINHAND);
        doReturn(use.off()).when(player).getItemBySlot(EquipmentSlot.OFFHAND);
        doReturn(true).when(player).isUsingItem();
        doReturn(use.used()).when(player).getUsedItemHand();
        doReturn(use.remaining()).when(player).getUseItemRemainingTicks();
        doReturn(use.ticks()).when(player).getTicksUsingItem();
        doReturn(use.used() == InteractionHand.MAIN_HAND ? use.main() : use.off())
                .when(player)
                .getUseItem();
    }

    /**
     * D's live state contradicting every checkpoint input: the pinned getters run their real
     * (transformed) bodies over synced data with every flag clear, no frozen ticks and no bed.
     */
    private static Map<String, Object> live(AbstractClientPlayer player) throws Exception {
        for (var call :
                List.<Consumer<AbstractClientPlayer>>of(
                        p -> p.isUsingItem(),
                        p -> p.getUsedItemHand(),
                        p -> p.getUseItemRemainingTicks(),
                        p -> p.getTicksUsingItem(),
                        p -> p.isFallFlying(),
                        p -> p.isAutoSpinAttack(),
                        p -> p.getBedOrientation(),
                        p -> p.isPassenger(),
                        p -> p.isFullyFrozen(),
                        p -> p.getTicksFrozen())) call.accept(doCallRealMethod().when(player));
        var flags = accessor(LivingEntity.class, "DATA_LIVING_ENTITY_FLAGS");
        var shared = accessor(Entity.class, "DATA_SHARED_FLAGS_ID");
        var frozen = accessor(Entity.class, "DATA_TICKS_FROZEN");
        var sleeping = accessor(LivingEntity.class, "SLEEPING_POS_ID");
        var data = mock(SynchedEntityData.class);
        doAnswer(
                        call -> {
                            Object key = call.getArgument(0);
                            if (key == flags || key == shared) return (byte) 0;
                            if (key == frozen) return 0;
                            if (key == sleeping) return Optional.empty();
                            throw new AssertionError("unexpected synced read " + key);
                        })
                .when(data)
                .get(any());
        var field =
                OracleReflection.field(
                        Entity.class,
                        "entityData",
                        "Lnet/minecraft/network/syncher/SynchedEntityData;");
        field.setAccessible(true);
        field.set(player, data);
        var remaining = OracleReflection.field(LivingEntity.class, "useItemRemaining", "I");
        remaining.setAccessible(true);
        remaining.setInt(player, 0);
        vehicle(player, null);
        doReturn(ItemStack.EMPTY).when(player).getUseItem();
        var result = new LinkedHashMap<String, Object>();
        result.put("flags", 0);
        return result;
    }

    private static void vehicle(Entity player, Entity vehicle) throws Exception {
        var field =
                OracleReflection.field(
                        Entity.class, "vehicle", "Lnet/minecraft/world/entity/Entity;");
        field.setAccessible(true);
        field.set(player, vehicle);
    }

    private static EntityDataAccessor<?> accessor(Class<?> owner, String name) throws Exception {
        var field =
                OracleReflection.field(
                        owner, name, "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        field.setAccessible(true);
        return (EntityDataAccessor<?>) field.get(null);
    }

    /** The native renderer's model-property stage on a constructor-free renderer. */
    private static void properties(
            PlayerModel<AbstractClientPlayer> model, AbstractClientPlayer player) {
        try {
            var renderer = renderer();
            var field =
                    OracleReflection.field(
                            LivingEntityRenderer.class,
                            "model",
                            "Lnet/minecraft/client/model/EntityModel;");
            field.setAccessible(true);
            field.set(renderer, model);
            OracleReflection.method(
                            PlayerRenderer.class,
                            "setModelProperties",
                            "(Lnet/minecraft/client/player/AbstractClientPlayer;)V",
                            AbstractClientPlayer.class)
                    .invoke(renderer, player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A renderer allocated without its constructor (no model baking, no layers). Not a Mockito
     * mock: inline mocks re-dispatch the native {@code super.setupRotations} call to the override.
     */
    private static PlayerRenderer renderer() throws ReflectiveOperationException {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        return (PlayerRenderer)
                unsafe.getClass()
                        .getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, PlayerRenderer.class);
    }

    private interface Stage {
        void accept(PlayerModel<AbstractClientPlayer> model, AbstractClientPlayer player);
    }

    private static int[] model(
            AbstractClientPlayer player, Consumer<PlayerModel<AbstractClientPlayer>> before) {
        return model(player, (model, p) -> before.accept(model));
    }

    /**
     * One render pass of the transformed model inside the pass scopes production opens ({@code
     * EntityRenderDispatcher.render}): six parts' raw bits, then both arm-pose ordinals.
     */
    private static int[] model(AbstractClientPlayer player, Stage before) {
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        var model =
                new PlayerModel<AbstractClientPlayer>(
                        LayerDefinition.create(mesh, 64, 64).bakeRoot(), false);
        ((IAnimatedPlayer) player).playerAnimator_getAnimation().setTickDelta(.25f);
        try (var parcool =
                        RemoteParCoolPresentation.driven(player)
                                ? RemoteParCoolPresentation.scope(player)
                                : null;
                var inputs = DrivenPresentation.scope(player)) {
            before.accept(model, player);
            model.prepareMobModel(player, 0, 0, .25f);
            model.setupAnim(player, 0, 0, AGE, 0, 0);
        }
        var result = new int[38];
        int i = 0;
        for (var part : parts(model))
            for (float value :
                    new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot}) {
                assertTrue(Float.isFinite(value), "nonfinite model result");
                result[i++] = Float.floatToRawIntBits(value);
            }
        result[36] = model.rightArmPose.ordinal();
        result[37] = model.leftArmPose.ordinal();
        return result;
    }

    /** The native root stages (setupRotations) on a fresh pose stack inside the pass scopes. */
    private static int[] root(AbstractClientPlayer player) {
        try {
            var renderer = renderer();
            var stack = new PoseStack();
            var setup =
                    OracleReflection.method(
                            PlayerRenderer.class,
                            "setupRotations",
                            "(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
                            AbstractClientPlayer.class,
                            PoseStack.class,
                            float.class,
                            float.class,
                            float.class,
                            float.class);
            try (var parcool =
                            RemoteParCoolPresentation.driven(player)
                                    ? RemoteParCoolPresentation.scope(player)
                                    : null;
                    var inputs = DrivenPresentation.scope(player)) {
                setup.invoke(renderer, player, stack, player.tickCount + .25f, BODY_YAW, .25f, 1f);
            }
            var matrix = stack.last().pose().get(new float[16]);
            var bits = new int[16];
            for (int i = 0; i < 16; i++) {
                assertTrue(Float.isFinite(matrix[i]), "nonfinite root");
                bits[i] = Float.floatToRawIntBits(matrix[i]);
            }
            return bits;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<ModelPart> parts(PlayerModel<?> model) {
        return List.of(
                model.head,
                model.body,
                model.leftArm,
                model.rightArm,
                model.leftLeg,
                model.rightLeg);
    }

    /** Baked wide-model pivots in the Create mirror's layout (x, y, z per part). */
    static float[] pivots() {
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        var model =
                new PlayerModel<AbstractClientPlayer>(
                        LayerDefinition.create(mesh, 64, 64).bakeRoot(), false);
        var pivots = new float[18];
        int i = 0;
        for (var part : parts(model)) {
            var initial = part.getInitialPose();
            pivots[i++] = initial.x;
            pivots[i++] = initial.y;
            pivots[i++] = initial.z;
        }
        return pivots;
    }

    /** The checkpoint's main arm is RIGHT, like every fixture player's live {@code getMainArm}. */
    private static AnimationState.Vanilla right(AnimationState.Vanilla vanilla) {
        assertEquals(HumanoidArm.RIGHT, HumanoidArm.RIGHT);
        return with(vanilla, "mainArm", AnimationTypes.Arm.RIGHT);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T with(T original, String field, Object replacement) {
        try {
            var components = original.getClass().getRecordComponents();
            var args = new Object[components.length];
            var types = new Class<?>[components.length];
            boolean found = false;
            for (int i = 0; i < args.length; i++) {
                types[i] = components[i].getType();
                boolean match = components[i].getName().equals(field);
                found |= match;
                args[i] = match ? replacement : components[i].getAccessor().invoke(original);
            }
            assertTrue(found, "no record component " + field);
            return (T) original.getClass().getConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
