package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.api.unstable.animation.AnimationOption;
import com.alrex.parcool.client.animation.*;
import com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.impl.*;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Native state restoration evidence; deterministic history replacement is a separate gate. */
class ParCoolCheckpointOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allNativeAnimatorsRestoreConstructorImmediateAndTwelveTickContinuation() {
        AnimSyncOracleRunner.run(
                "parcool",
                "typed-native-checkpoint-continuation",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    var player = player();
                    var parkour = new Parkourability();
                    for (Action action : parkour.getList()) {
                        field(Action.class, "doing").setBoolean(action, true);
                        field(Action.class, "doingTick").setInt(action, 3);
                        field(Action.class, "tickFromStarted").setInt(action, 3);
                        for (Field value : action.getClass().getDeclaredFields()) {
                            if (!Modifier.isStatic(value.getModifiers())
                                    && value.getType() == Vec3.class) {
                                value.setAccessible(true);
                                value.set(action, new Vec3(.3, -.2, .9));
                            }
                        }
                    }
                    field(Dive.class, "playerYSpeedOld").setDouble(parkour.get(Dive.class), .31);
                    field(Dive.class, "playerYSpeed").setDouble(parkour.get(Dive.class), -.13);
                    field(HangDown.class, "hangingBarAxis")
                            .set(parkour.get(HangDown.class), HangDown.BarAxis.X);
                    field(SkyDive.class.getSuperclass(), "doing")
                            .setBoolean(parkour.get(SkyDive.class), false);
                    var rows = new ArrayList<Map<String, Object>>();
                    try (var attachment = mockStatic(Parkourability.class);
                            var keys = mockStatic(KeyBindings.class);
                            var singleton = mockStatic(Minecraft.class)) {
                        attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        for (AnimatorTag tag : AnimatorTag.values()) {
                            for (Object original : constructors(adapter.type(tag))) {
                                Animator nativeActor = (Animator) original;
                                // Constructor state must survive before any renderer or integer
                                // tick.
                                AnimationGraphState constructor = freeze(adapter, nativeActor);
                                assertEquals(
                                        constructor,
                                        freeze(adapter, restore(adapter, constructor)));
                                for (float partial :
                                        new float[] {0, 1f / 65536, .3f, .85f, 1 - 1f / 65536, 1}) {
                                    assertArrayEquals(
                                            sample(nativeActor, player, parkour, partial),
                                            sample(
                                                    restore(adapter, constructor),
                                                    player,
                                                    parkour,
                                                    partial),
                                            tag + " constructor root/model " + partial);
                                    nativeActor = restore(adapter, constructor);
                                }
                                seedMutable(nativeActor);
                                if (nativeActor instanceof DiveAnimationHostAnimator host) {
                                    var child =
                                            new DiveAnimationHostAnimator.SkyDiveAnimator(37.125f);
                                    seedMutable(child);
                                    field(DiveAnimationHostAnimator.class, "skyDiveAnimator")
                                            .set(host, child);
                                    seedMutable(
                                            field(DiveAnimationHostAnimator.class, "diveAnimator")
                                                    .get(host));
                                }
                                AnimationGraphState retained = freeze(adapter, nativeActor);
                                Animator restored = restore(adapter, retained);
                                assertEquals(retained, freeze(adapter, restored));
                                int checks = 0;
                                for (int tick = 0; tick <= 12; tick++) {
                                    if (tick > 0) {
                                        nativeActor.tick(player);
                                        restored.tick(player);
                                        for (Action action : parkour.getList()) action.tick();
                                    }
                                    assertEquals(
                                            freeze(adapter, nativeActor),
                                            freeze(adapter, restored),
                                            tag + " integer " + tick);
                                    for (float partial :
                                            new float[] {
                                                0, 1f / 65536, .3f, .85f, 1 - 1f / 65536, 1
                                            }) {
                                        var checkpoint = freeze(adapter, nativeActor);
                                        assertArrayEquals(
                                                sample(nativeActor, player, parkour, partial),
                                                sample(restored, player, parkour, partial),
                                                tag + " root/model " + tick + "/" + partial);
                                        // Render history is allowed only in throwaway native
                                        // scratch.
                                        nativeActor = restore(adapter, checkpoint);
                                        restored = restore(adapter, checkpoint);
                                        assertEquals(checkpoint, freeze(adapter, nativeActor));
                                        checks++;
                                    }
                                    if (tick == 4 || tick == 8) {
                                        field(Action.class, "doing")
                                                .setBoolean(parkour.get(SkyDive.class), tick == 4);
                                        // Ordered interruption state is captured after the actual
                                        // native callbacks.
                                        sample(nativeActor, player, parkour, .5f);
                                        restored = restore(adapter, freeze(adapter, nativeActor));
                                    }
                                }
                                rows.add(
                                        Map.of(
                                                "tag",
                                                tag.name(),
                                                "constructor",
                                                original.getClass().getSimpleName(),
                                                "continuationTicks",
                                                12,
                                                "rootModelSamples",
                                                checks,
                                                "constructorU1WireHex",
                                                HexFormat.of()
                                                        .formatHex(
                                                                AnimationSchema.encode(
                                                                        constructor)),
                                                "retainedU1WireHex",
                                                HexFormat.of()
                                                        .formatHex(
                                                                AnimationSchema.encode(retained)),
                                                "nativeRootAndPartBitsAtAlpha19660",
                                                Arrays.stream(
                                                                sample(
                                                                        restore(adapter, retained),
                                                                        player,
                                                                        parkour,
                                                                        19660f / 65536))
                                                        .boxed()
                                                        .toList()));
                            }
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "registered",
                            26,
                            "children",
                            2,
                            "cases",
                            rows,
                            "boundary",
                            "native checkpoint restoration, native root and six model parts; no deterministic projection or mixed-renderer equality claim");
                });
    }

    @Test
    void passiveAndEveryOptionMaskRestoreExactly() {
        AnimSyncOracleRunner.run(
                "parcool",
                "typed-passive-options",
                () -> {
                    var passive = new PassiveCustomAnimation();
                    field(PassiveCustomAnimation.class, "fallingAnimationTick").setInt(passive, 31);
                    field(PassiveCustomAnimation.class, "flyingAnimationLevelOld")
                            .setInt(passive, 7);
                    field(PassiveCustomAnimation.class, "flyingAnimationLevel").setInt(passive, 8);
                    var state =
                            roundTrip(AnimationState.Passive.class, ParCoolGraph.passive(passive));
                    assertEquals(
                            state,
                            ParCoolGraph.passive(
                                    ParCoolGraph.passive(PassiveCustomAnimation.class, state)));
                    var player = player();
                    var parkour = new Parkourability();
                    var fall =
                            OracleReflection.field(
                                    net.minecraft.world.entity.Entity.class, "fallDistance", "F");
                    fall.setAccessible(true);
                    fall.setFloat(player, 4);
                    var continued =
                            (PassiveCustomAnimation)
                                    ParCoolGraph.passive(PassiveCustomAnimation.class, state);
                    try (var singleton = mockStatic(Minecraft.class);
                            var keys = mockStatic(KeyBindings.class)) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        for (int tick = 0; tick <= 12; tick++) {
                            if (tick > 0) {
                                passive.tick(player, parkour);
                                continued.tick(player, parkour);
                            }
                            assertEquals(
                                    ParCoolGraph.passive(passive), ParCoolGraph.passive(continued));
                            for (float partial : new float[] {0, .3f, .85f, 1}) {
                                assertArrayEquals(
                                        sample(passiveAnimator(passive), player, parkour, partial),
                                        sample(
                                                passiveAnimator(continued),
                                                player,
                                                parkour,
                                                partial));
                            }
                            continued =
                                    (PassiveCustomAnimation)
                                            ParCoolGraph.passive(
                                                    PassiveCustomAnimation.class,
                                                    ParCoolGraph.passive(passive));
                        }
                    }
                    for (int mask = 0; mask < 128; mask++)
                        for (boolean canceled : new boolean[] {false, true}) {
                            var option =
                                    roundTrip(
                                            AnimationState.Option.class,
                                            new AnimationState.Option(canceled, mask));
                            var nativeOption =
                                    (AnimationOption)
                                            ParCoolGraph.option(AnimationOption.class, option);
                            assertEquals(option, ParCoolGraph.option(nativeOption));
                            for (var part :
                                    com.alrex.parcool.api.unstable.animation.AnimationPart.values())
                                assertEquals(
                                        canceled
                                                || !option.allows(
                                                        AnimationPart.valueOf(part.name())),
                                        nativeOption.isCanceled(part));
                        }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "optionValues",
                            256,
                            "passiveFields",
                            3,
                            "passiveContinuationTicks",
                            12,
                            "retainedPassiveU1WireHex",
                            HexFormat.of().formatHex(AnimationSchema.encode(state)),
                            "nativeScratchRestored",
                            true);
                });
    }

    @Test
    void everyNativeActionCounterAndVisualInputRestoresWithoutStartOrFinish() {
        AnimSyncOracleRunner.run(
                "parcool",
                "typed-action-input-continuation",
                () -> {
                    var player = player();
                    var rows = new ArrayList<Map<String, Object>>();
                    for (ActionTag tag : ActionTag.values()) {
                        var inputs = new ArrayList<AnimationState.ActionInput>();
                        inputs.add(new AnimationState.NoInput());
                        inputs.add(
                                new AnimationState.PassiveInput(
                                        9, false, true, false, false, 3.125f));
                        inputs.add(
                                new AnimationState.CompatibilityInput(
                                        new AnimationResources.EquipmentRef(
                                                1, 2, 3, new AnimationResources.Digest(0, 0, 0, 0)),
                                        AnimatorTag.ROLL,
                                        7,
                                        new AnimationState.Option(false, 65)));
                        inputs.add(
                                new AnimationState.AcceptedStart(
                                        tag,
                                        1,
                                        true,
                                        new AnimationState.Vec3(-0., .125, Math.nextUp(.9)),
                                        new AnimationState.Vec3(.2, -.3, .4),
                                        Math.nextDown(-.125),
                                        true));
                        switch (tag) {
                            case SLIDE ->
                                    inputs.add(
                                            new AnimationState.SlideInput(
                                                    new AnimationState.Vec3(-0., .125, .9)));
                            case HORIZONTAL_WALL_RUN ->
                                    inputs.add(
                                            new AnimationState.HorizontalWallRunInput(
                                                    -0f,
                                                    true,
                                                    new AnimationState.Vec3(.1, .2, .3),
                                                    new AnimationState.Vec3(-0., -.2, -.3)));
                            case VERTICAL_WALL_RUN ->
                                    inputs.add(
                                            new AnimationState.VerticalWallRunInput(
                                                    Math.nextUp(.1),
                                                    new AnimationState.Vec3(.1, .2, .3)));
                            case WALL_SLIDE ->
                                    inputs.add(
                                            new AnimationState.WallSlideInput(
                                                    new AnimationState.Vec3(-0., .2, .3)));
                            case HANG_DOWN ->
                                    inputs.add(
                                            new AnimationState.HangInput(
                                                    Math.nextUp(.1),
                                                    -0f,
                                                    true,
                                                    BarAxis.Z,
                                                    179.999f,
                                                    true));
                            case CLING_TO_CLIFF ->
                                    inputs.add(
                                            new AnimationState.ClingInput(
                                                    -0f,
                                                    FacingDirection.RIGHT_AGAINST_WALL,
                                                    new AnimationState.Vec3(.1, .2, .3)));
                            case DIVE ->
                                    inputs.add(
                                            new AnimationState.DiveInput(
                                                    true,
                                                    Math.nextUp(.42),
                                                    -0.,
                                                    -.13,
                                                    7,
                                                    true,
                                                    11,
                                                    true));
                            case CHARGE_JUMP ->
                                    inputs.add(
                                            new AnimationState.ChargeInput(
                                                    11, 13, 7, true, 12, true));
                            case RIDE_ZIPLINE ->
                                    inputs.add(
                                            new AnimationState.ZiplineInput(
                                                    new AnimationState.Vec3(.1, -.2, .3),
                                                    Math.nextUp(.17),
                                                    -.31,
                                                    .08,
                                                    false,
                                                    null));
                            case HIDE_IN_BLOCK ->
                                    inputs.add(
                                            new AnimationState.HideInput(
                                                    new AnimationState.Vec3(.1, .2, .3), true));
                            default -> {}
                        }
                        if (tag == ActionTag.SKY_DIVE || tag == ActionTag.FAST_RUN)
                            inputs.add(new AnimationState.IntentInput(true, false, true, false));
                        if (tag == ActionTag.ROLL || tag == ActionTag.TAP || tag == ActionTag.VAULT)
                            inputs.add(
                                    new AnimationState.DurationInput(
                                            tag == ActionTag.ROLL
                                                    ? 9
                                                    : tag == ActionTag.TAP ? 8 : 11,
                                            new AnimationResources.ConfigRef(
                                                    1,
                                                    2,
                                                    3,
                                                    new AnimationResources.Digest(0, 0, 0, 0))));
                        var state = new AnimationState.ActionState(tag, true, 3, 7, 11, inputs);
                        state = roundTrip(AnimationState.ActionState.class, state);
                        Action actor = ParCoolActions.materialize(state);
                        assertEquals(
                                state, ParCoolActions.capture(actor, state, player), tag.name());
                        for (int tick = 0; tick < 12; tick++) {
                            actor.tick();
                            var next =
                                    new AnimationState.ActionState(
                                            tag, true, 4 + tick, 0, 12 + tick, inputs);
                            assertEquals(
                                    next,
                                    ParCoolActions.capture(actor, state, player),
                                    tag + " continuation " + tick);
                            actor = ParCoolActions.materialize(next);
                        }
                        // Replacement does not call native finish/start: stopped counters remain
                        // exact.
                        var stopped = new AnimationState.ActionState(tag, false, 0, 13, -1, inputs);
                        assertEquals(
                                stopped,
                                ParCoolActions.capture(
                                        ParCoolActions.materialize(stopped), stopped, player));
                        rows.add(
                                Map.of(
                                        "action",
                                        tag.name(),
                                        "inputs",
                                        inputs.size(),
                                        "continuationTicks",
                                        12,
                                        "initialU1WireHex",
                                        HexFormat.of().formatHex(AnimationSchema.encode(state))));
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "actions",
                            rows,
                            "actionCount",
                            27,
                            "boundary",
                            "geometry fields and counter replacement; canonical accepted context supplied explicitly; no action physics or intent transport");
                });
    }

    @Test
    void invalidTagsConstantsAndCollidingIdsFailClosed() {
        AnimSyncOracleRunner.run(
                "parcool",
                "typed-checkpoint-negative",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    assertEquals(
                            8,
                            field(
                                            com.alrex.parcool.client.animation.impl
                                                    .JumpFromBarAnimator.class,
                                            "MAX_TICK")
                                    .getInt(
                                            new com.alrex.parcool.client.animation.impl
                                                    .JumpFromBarAnimator()));
                    var base = AnimationGraphState.neutral();
                    var node =
                            new AnimationGraphState.Node(
                                    1,
                                    PhaseDomain.A,
                                    null,
                                    new AnimationGraphState.Animator(
                                            AnimatorTag.BACKWARD_WALL_JUMP,
                                            2,
                                            new AnimationGraphState.BackwardWallJump(13)));
                    var graph = new AnimationGraphState(base.roots(), List.of(node));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> adapter.materialize(graph, new AnimationGraphState.NodeRef(1)));
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    adapter.freeze(
                                            base,
                                            new DiveAnimationHostAnimator(.42, false),
                                            () -> 1,
                                            ignored -> null));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> adapter.freeze(base, new Object(), () -> 1, ignored -> null));
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    ParCoolActions.materialize(
                                            new AnimationState.ActionState(
                                                    ActionTag.HANG_DOWN,
                                                    true,
                                                    0,
                                                    0,
                                                    0,
                                                    List.of())));
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    ParCoolActions.materialize(
                                            new AnimationState.ActionState(
                                                    ActionTag.CAT_LEAP,
                                                    true,
                                                    0,
                                                    0,
                                                    0,
                                                    List.of(new AnimationState.SlideInput(null)))));
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    ParCoolActions.materialize(
                                            new AnimationState.ActionState(
                                                    ActionTag.CAT_LEAP,
                                                    true,
                                                    0,
                                                    0,
                                                    0,
                                                    List.of(
                                                            new AnimationState.NoInput(),
                                                            new AnimationState.NoInput()))));
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "changedConstantRejected",
                            true,
                            "childIdCollisionRejected",
                            true,
                            "unknownClassRejected",
                            true);
                });
    }

    private static AnimationGraphState freeze(ParCoolGraph adapter, Animator actor) {
        return roundTrip(
                AnimationGraphState.class,
                adapter.freeze(
                        AnimationGraphState.neutral(),
                        actor,
                        new AtomicInteger()::incrementAndGet,
                        ignored -> new AnimationStartReport.StartKey(7, 1)));
    }

    private static <T extends Record> T roundTrip(Class<T> type, T value) {
        byte[] encoded = AnimationSchema.encode(value);
        var buffer =
                new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(encoded));
        try {
            T decoded = AnimationSchema.readComplete(buffer, type);
            assertEquals(value, decoded);
            assertArrayEquals(encoded, AnimationSchema.encode(decoded));
            return decoded;
        } finally {
            buffer.release();
        }
    }

    static Animator restore(ParCoolGraph adapter, AnimationGraphState graph) {
        var root =
                graph.roots().stream()
                        .filter(entry -> entry.layer() == LayerTag.PARCOOL_ACTIVE)
                        .findFirst()
                        .orElseThrow()
                        .node();
        return (Animator) adapter.materialize(graph, root);
    }

    static List<Object> constructors(Class<?> type) throws ReflectiveOperationException {
        var constructor = type.getConstructors()[0];
        var combinations = new ArrayList<Object[]>();
        combinations.add(new Object[0]);
        for (Class<?> parameter : constructor.getParameterTypes()) {
            var next = new ArrayList<Object[]>();
            Object[] values =
                    parameter == boolean.class
                            ? new Object[] {false, true}
                            : parameter == double.class
                                    ? new Object[] {.42, -.42, Math.nextUp(.125)}
                                    : parameter == float.class
                                            ? new Object[] {-0f, 37.125f}
                                            : parameter.getEnumConstants();
            for (Object[] prefix : combinations)
                for (Object value : values) {
                    Object[] args = Arrays.copyOf(prefix, prefix.length + 1);
                    args[prefix.length] = value;
                    next.add(args);
                }
            combinations = next;
        }
        var results = new ArrayList<Object>();
        for (Object[] args : combinations) results.add(constructor.newInstance(args));
        return results;
    }

    private static void seedMutable(Object value) throws ReflectiveOperationException {
        field(Animator.class, "tick").setInt(value, 3);
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
                continue;
            field.setAccessible(true);
            if (field.getType() == float.class) field.setFloat(value, Math.nextUp(.125f));
            if (field.getType() == double.class) field.setDouble(value, Math.nextDown(-.125));
            if (field.getType() == int.class) field.setInt(value, 2);
            if (field.getType() == boolean.class) field.setBoolean(value, true);
        }
    }

    static Player player() throws ReflectiveOperationException {
        var player = mock(Player.class);
        when(player.getPose()).thenReturn(Pose.STANDING);
        when(player.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getLookAngle()).thenReturn(new Vec3(.3, -.2, .9));
        when(player.getDeltaMovement()).thenReturn(new Vec3(.03, -.02, .09));
        when(player.getAbilities()).thenReturn(new Abilities());
        when(player.getAttributeValue(Attributes.GRAVITY)).thenReturn(.08);
        when(player.getBbHeight()).thenReturn(1.8f);
        when(player.getYRot()).thenReturn(23f);
        for (String name : new String[] {"yBodyRot", "yBodyRotO"}) {
            Field field = OracleReflection.field(LivingEntity.class, name, "F");
            field.setAccessible(true);
            field.setFloat(player, name.endsWith("O") ? 17 : 23);
        }
        return player;
    }

    static Animator passiveAnimator(PassiveCustomAnimation passive) {
        return new Animator() {
            @Override
            public boolean shouldRemoved(Player player, Parkourability parkour) {
                return false;
            }

            @Override
            public void animatePost(
                    Player player, Parkourability parkour, PlayerModelTransformer transformer) {
                passive.animate(player, parkour, transformer);
            }

            @Override
            public void rotatePost(
                    Player player, Parkourability parkour, PlayerModelRotator rotator) {
                passive.rotate(player, parkour, rotator);
            }
        };
    }

    private static int[] sample(
            Animator animator, Player player, Parkourability parkour, float partial) {
        return sample(animator, player, parkour, partial, false, 12.5f);
    }

    static int[] sample(
            Animator animator,
            Player player,
            Parkourability parkour,
            float partial,
            boolean modelFirst,
            float age) {
        var model =
                new PlayerModel<Player>(
                        LayerDefinition.create(
                                        PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64)
                                .bakeRoot(),
                        false);
        var transformer =
                new PlayerModelTransformer(player, model, false, age, 7.25f, .4f, 13, -17) {
                    @Override
                    public float getPartialTick() {
                        return partial;
                    }
                };
        var stack = new PoseStack();
        var rotator = new PlayerModelRotator(stack, player, partial, 23);
        if (modelFirst) {
            animator.animatePre(player, parkour, transformer);
            animator.animatePost(player, parkour, transformer);
        }
        animator.rotatePre(player, parkour, rotator);
        animator.rotatePost(player, parkour, rotator);
        if (!modelFirst) {
            animator.animatePre(player, parkour, transformer);
            animator.animatePost(player, parkour, transformer);
        }
        var bits = new ArrayList<Integer>();
        for (float value : stack.last().pose().get(new float[16]))
            bits.add(Float.floatToRawIntBits(value));
        for (ModelPart part :
                new ModelPart[] {
                    model.head,
                    model.body,
                    model.leftArm,
                    model.rightArm,
                    model.leftLeg,
                    model.rightLeg
                }) {
            for (float value :
                    new float[] {
                        part.x,
                        part.y,
                        part.z,
                        part.xRot,
                        part.yRot,
                        part.zRot,
                        part.xScale,
                        part.yScale,
                        part.zScale
                    }) bits.add(Float.floatToRawIntBits(value));
        }
        for (int bit : bits)
            assertTrue(
                    Float.isFinite(Float.intBitsToFloat(bit)),
                    "nonfinite native sample is not equality evidence");
        return bits.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
