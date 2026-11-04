package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.api.unstable.animation.ParCoolAnimationInfoEvent;
import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.client.animation.AnimatorList;
import com.alrex.parcool.client.animation.PlayerModelTransformer;
import com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator;
import com.alrex.parcool.client.animation.impl.RideZiplineAnimator;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.impl.Dive;
import com.alrex.parcool.common.action.impl.HangDown;
import com.alrex.parcool.common.action.impl.RideZipline;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes;
import com.lunares.blockfield.common.combat.ParCoolGraph;
import com.lunares.blockfield.common.combat.ParCoolReducers;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.tacz.guns.api.item.IGun;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scalar/reducer differentials, not final-model or full-family acceptance evidence. */
class ParCoolReducerOracleFixture {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeHangRecurrenceMatchesEveryVirtualStepAndRecordsFpsDifference() {
        AnimSyncOracleRunner.run(
                "parcool",
                "hang-rational-grid-native",
                () -> {
                    var player = mock(Player.class, CALLS_REAL_METHODS);
                    Field yaw = OracleReflection.field(LivingEntity.class, "yBodyRot", "F");
                    yaw.setAccessible(true);
                    var action = new HangDown();
                    field(Action.class, "doing").setBoolean(action, true);
                    var frames = new ArrayList<Map<String, Object>>();
                    for (var axis : HangDown.BarAxis.values()) {
                        field(HangDown.class, "hangingBarAxis").set(action, axis);
                        for (float start : new float[] {-179.999f, -90, -0f, 45, 179.999f}) {
                            float expected = start;
                            yaw.setFloat(player, start);
                            for (int step = 0; step < 120; step++) {
                                var look = step < 57 ? new Vec3(1, .2, 0) : new Vec3(0, -.3, -1);
                                when(player.getLookAngle()).thenReturn(look);
                                expected =
                                        ParCoolReducers.hangStep(
                                                expected,
                                                new AnimationState.Vec3(look.x, look.y, look.z),
                                                AnimationTypes.BarAxis.valueOf(axis.name()));
                                action.onRenderTick(null, player, null);
                                assertEquals(bits(expected), bits(yaw.getFloat(player)));
                            }
                        }
                    }
                    field(HangDown.class, "hangingBarAxis").set(action, HangDown.BarAxis.X);
                    var virtualYaws = new float[17];
                    virtualYaws[0] = 43;
                    for (int i = 1; i < virtualYaws.length; i++)
                        virtualYaws[i] =
                                ParCoolReducers.hangStep(
                                        virtualYaws[i - 1],
                                        new AnimationState.Vec3(1, 0, 0),
                                        AnimationTypes.BarAxis.X);
                    for (int fps : new int[] {30, 60, 144, 240}) {
                        yaw.setFloat(player, 43);
                        var curve = new ArrayList<Integer>();
                        var selected = new ArrayList<Integer>();
                        var times = new ArrayList<Long>();
                        for (int frame = 0; frame <= fps / 5; frame++) {
                            when(player.getLookAngle()).thenReturn(new Vec3(1, 0, 0));
                            action.onRenderTick(null, player, null);
                            curve.add(bits(yaw.getFloat(player)));
                            long q16 = 20L * frame * 65536 / fps;
                            var time = new AnimationTypes.Time(q16 / 65536, (int) (q16 % 65536));
                            var grid =
                                    new AnimationState.HangGrid(
                                            interval(virtualYaws, time, 43), 43);
                            selected.add(bits(ParCoolReducers.hangYaw(grid, time)));
                            times.add(q16);
                        }
                        frames.add(
                                Map.of(
                                        "fps",
                                        fps,
                                        "ageQ16",
                                        times,
                                        "nativeYawBits",
                                        curve,
                                        "deterministicYawBits",
                                        selected));
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "nativeEqualitySteps",
                            1200,
                            "nativeFpsTrajectories",
                            frames,
                            "differenceClassification",
                            "remote-only fixed 60 Hz recurrence",
                            "finalModelAcceptance",
                            false);
                });
    }

    @Test
    void nativeZiplineDoublesAndEightTickContinuationMatch() {
        AnimSyncOracleRunner.run(
                "parcool",
                "zipline-native-continuation",
                () -> {
                    var player = mock(Player.class);
                    var parkour = mock(Parkourability.class);
                    var action = mock(RideZipline.class);
                    when(parkour.get(RideZipline.class)).thenReturn(action);
                    when(action.isDoing()).thenReturn(true);
                    var nativeAnimator = new RideZiplineAnimator();
                    var restored = new RideZiplineAnimator();
                    var state = new AnimationGraphState.RideZipline(-0., Math.nextUp(.125));
                    Field old = field(RideZiplineAnimator.class, "oldAngleRadian");
                    Field current = field(RideZiplineAnimator.class, "currentAngleRadian");
                    old.setDouble(nativeAnimator, state.oldAngleRadian());
                    current.setDouble(nativeAnimator, state.currentAngleRadian());
                    var samples = new ArrayList<Map<String, Object>>();
                    try (var nativeAttachment = mockStatic(Parkourability.class)) {
                        nativeAttachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
                        for (int tick = 0; tick < 12; tick++) {
                            double gravity = tick < 4 ? .08 : tick < 8 ? 0 : -.01;
                            double acceleration = tick % 2 == 0 ? .17 : -.23;
                            double slope = tick < 6 ? -.31 : .47;
                            when(player.getAttributeValue(Attributes.GRAVITY)).thenReturn(gravity);
                            when(action.getAcceleration()).thenReturn(acceleration);
                            when(action.getSlope()).thenReturn(slope);
                            state =
                                    ParCoolReducers.ziplineStep(
                                            state,
                                            new AnimationState.ZiplineInput(
                                                    null,
                                                    acceleration,
                                                    slope,
                                                    gravity,
                                                    false,
                                                    null),
                                            true);
                            nativeAnimator.tick(player);
                            assertEquals(
                                    Double.doubleToRawLongBits(state.oldAngleRadian()),
                                    Double.doubleToRawLongBits(old.getDouble(nativeAnimator)));
                            assertEquals(
                                    Double.doubleToRawLongBits(state.currentAngleRadian()),
                                    Double.doubleToRawLongBits(current.getDouble(nativeAnimator)));
                            if (tick == 3) {
                                old.setDouble(restored, old.getDouble(nativeAnimator));
                                current.setDouble(restored, current.getDouble(nativeAnimator));
                            } else if (tick > 3) {
                                restored.tick(player);
                                assertEquals(
                                        old.getDouble(nativeAnimator), old.getDouble(restored));
                                assertEquals(
                                        current.getDouble(nativeAnimator),
                                        current.getDouble(restored));
                            }
                            samples.add(
                                    Map.of(
                                            "oldBits",
                                            Double.doubleToRawLongBits(state.oldAngleRadian()),
                                            "currentBits",
                                            Double.doubleToRawLongBits(
                                                    state.currentAngleRadian())));
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "nativeTicks",
                            12,
                            "continuationTicks",
                            8,
                            "doubleSamples",
                            samples,
                            "ropePresenceRequiredByNativeAnimator",
                            false);
                });
    }

    @Test
    void nativeDiveFactorAndHistoryDependentLegImpulseAreCharacterized() {
        AnimSyncOracleRunner.run(
                "parcool",
                "dive-factor-native-history",
                () -> {
                    var factorMethod =
                            DiveAnimationHostAnimator.DiveAnimator.class.getDeclaredMethod(
                                    "getFactor", double.class);
                    factorMethod.setAccessible(true);
                    var singular = new ArrayList<Map<String, Object>>();
                    for (double initial : new double[] {.42, -.42, Double.MIN_VALUE, 0., -0.}) {
                        var nativeChild = new DiveAnimationHostAnimator.DiveAnimator(initial);
                        for (double speed : new double[] {-1, -.42, -0., 0, .42, 1}) {
                            float nativeFactor = (float) factorMethod.invoke(nativeChild, speed);
                            assertEquals(
                                    bits(nativeFactor),
                                    bits(ParCoolReducers.diveFactor(initial, speed)));
                            if (!Float.isFinite(nativeFactor))
                                singular.add(
                                        Map.of(
                                                "initialBits",
                                                Double.doubleToRawLongBits(initial),
                                                "speedBits",
                                                Double.doubleToRawLongBits(speed),
                                                "factorBits",
                                                bits(nativeFactor)));
                        }
                    }
                    var player = mock(Player.class);
                    var parkour = mock(Parkourability.class);
                    var dive = new Dive();
                    when(parkour.get(Dive.class)).thenReturn(dive);
                    Field speedOld = field(Dive.class, "playerYSpeedOld");
                    Field speedNow = field(Dive.class, "playerYSpeed");
                    Field retained =
                            field(DiveAnimationHostAnimator.DiveAnimator.class, "oldFactor");
                    var curves = new ArrayList<Map<String, Object>>();
                    var virtualFactors = new float[35];
                    for (int i = 0; i < virtualFactors.length; i++)
                        virtualFactors[i] = ParCoolReducers.diveFactor(.42, .42 - .07 * (i / 3.));
                    for (int fps : new int[] {30, 60, 144, 240}) {
                        var child = new DiveAnimationHostAnimator.DiveAnimator(.42);
                        var transformer = mock(PlayerModelTransformer.class, RETURNS_SELF);
                        var impulses = new ArrayList<Integer>();
                        var selected = new ArrayList<Integer>();
                        var times = new ArrayList<Long>();
                        doAnswer(
                                        call -> {
                                            impulses.add(bits(call.getArgument(0, Float.class)));
                                            return transformer;
                                        })
                                .when(transformer)
                                .rotateRightLeg(anyFloat(), anyFloat(), anyFloat(), anyFloat());
                        for (int frame = 0; frame <= fps / 2; frame++) {
                            double speed = .42 - .07 * (20. * frame / fps);
                            speedOld.setDouble(dive, speed);
                            speedNow.setDouble(dive, speed);
                            child.animatePost(player, parkour, transformer);
                            assertEquals(
                                    bits(ParCoolReducers.diveFactor(.42, speed)),
                                    bits(retained.getFloat(child)));
                            long q16 = 20L * frame * 65536 / fps;
                            var time = new AnimationTypes.Time(q16 / 65536, (int) (q16 % 65536));
                            var grid =
                                    new AnimationState.DiveGrid(interval(virtualFactors, time, 0));
                            selected.add(
                                    bits(
                                            (float)
                                                    Math.toRadians(
                                                            ParCoolReducers.diveLegDegrees(
                                                                    grid, time))));
                            times.add(q16);
                        }
                        curves.add(
                                Map.of(
                                        "fps",
                                        fps,
                                        "ageQ16",
                                        times,
                                        "nativeLegRadiansBits",
                                        impulses,
                                        "deterministicLegRadiansBits",
                                        selected));
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "factorCases",
                            30,
                            "nativeNonfiniteCases",
                            singular,
                            "nativeFpsTrajectories",
                            curves,
                            "differenceClassification",
                            "native current-minus-last-render factor; deterministic grid-increment interpolation is separate",
                            "finalModelAcceptance",
                            false);
                });
    }

    @Test
    void allRegisteredNativeOptionBranchesMatchImmutableReducer() {
        AnimSyncOracleRunner.run(
                "parcool",
                "all-animator-native-options",
                () -> {
                    var listener =
                            com.alrex.parcool.extern.tacz.TaCZAnimationCompat.class
                                    .getDeclaredMethod(
                                            "onAnimationInfo", ParCoolAnimationInfoEvent.class);
                    listener.setAccessible(true);
                    var player = mock(AbstractClientPlayer.class);
                    var stack = mock(ItemStack.class);
                    var gun = mock(Item.class, withSettings().extraInterfaces(IGun.class));
                    when(stack.getItem()).thenReturn(gun);
                    when(player.getMainHandItem()).thenReturn(stack);
                    var masks = new LinkedHashMap<String, Object>();
                    assertEquals(26, AnimatorList.ANIMATORS.size());
                    for (int i = 0; i < 26; i++) {
                        var nativeAnimator = construct(AnimatorList.ANIMATORS.get(i));
                        var tag = AnimationTypes.AnimatorTag.values()[i];
                        var event = new ParCoolAnimationInfoEvent(player, nativeAnimator);
                        listener.invoke(null, event);
                        var expected = ParCoolReducers.option(tag, true);
                        int allowed = 0;
                        for (var part :
                                com.alrex.parcool.api.unstable.animation.AnimationPart.values()) {
                            var wirePart = AnimationTypes.AnimationPart.valueOf(part.name());
                            if (!event.getOption().isCanceled(part)) allowed |= 1 << wirePart.id();
                        }
                        assertEquals(expected.allowedParts(), allowed, tag.name());
                        assertEquals(
                                expected.animationCanceled(),
                                event.getOption().isAnimationCanceled());
                        masks.put(nativeAnimator.getClass().getSimpleName(), allowed);
                    }
                    var passive = new ParCoolAnimationInfoEvent(player, null);
                    listener.invoke(null, passive);
                    assertTrue(
                            passive.getOption()
                                    .isCanceled(
                                            com.alrex.parcool.api.unstable.animation.AnimationPart
                                                    .LEFT_ARM));
                    assertFalse(
                            passive.getOption()
                                    .isCanceled(
                                            com.alrex.parcool.api.unstable.animation.AnimationPart
                                                    .ROTATION));
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "registeredAnimators",
                            26,
                            "allowedParts",
                            masks,
                            "passiveMask",
                            ParCoolReducers.option(null, true).allowedParts(),
                            "listenerBoundary",
                            "native TaCZAnimationCompat listener only; full production listener catalogue and renderer order remain integration gates");
                });
    }

    @Test
    void acceptedTargetIntentMatchesAllNativeSkyDiveKeyCombinations() {
        AnimSyncOracleRunner.run(
                "parcool",
                "target-intent-native-sky-dive",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    int steps = 0;
                    try (var keys = mockStatic(com.alrex.parcool.client.input.KeyBindings.class)) {
                        for (int mask = 0; mask < 16; mask++) {
                            var intent =
                                    new AnimationState.IntentInput(
                                            (mask & 1) != 0,
                                            (mask & 2) != 0,
                                            (mask & 4) != 0,
                                            (mask & 8) != 0);
                            keys.when(com.alrex.parcool.client.input.KeyBindings::isKeyForwardDown)
                                    .thenReturn(intent.forward());
                            keys.when(com.alrex.parcool.client.input.KeyBindings::isKeyBackDown)
                                    .thenReturn(intent.back());
                            keys.when(com.alrex.parcool.client.input.KeyBindings::isKeyLeftDown)
                                    .thenReturn(intent.left());
                            keys.when(com.alrex.parcool.client.input.KeyBindings::isKeyRightDown)
                                    .thenReturn(intent.right());
                            for (int forward : new int[] {-8, -1, 0, 1, 8})
                                for (int right : new int[] {-8, -1, 0, 1, 8}) {
                                    var nativeChild =
                                            new DiveAnimationHostAnimator.SkyDiveAnimator(37.125f);
                                    field(nativeChild.getClass(), "forwardAngleCount")
                                            .setInt(nativeChild, forward);
                                    field(nativeChild.getClass(), "rightAngleCount")
                                            .setInt(nativeChild, right);
                                    var state =
                                            new AnimationGraphState.SkyDiveChild(
                                                    forward, 0, right, 0, 37.125f, 0);
                                    for (int tick = 0; tick < 12; tick++) {
                                        nativeChild.tick(null);
                                        state = ParCoolReducers.skyDiveStep(state, intent);
                                        var graph =
                                                adapter.freeze(
                                                        AnimationGraphState.neutral(),
                                                        nativeChild,
                                                        new java.util.concurrent.atomic
                                                                        .AtomicInteger()
                                                                ::incrementAndGet,
                                                        ignored -> null);
                                        assertEquals(
                                                state,
                                                ((AnimationGraphState.Animator)
                                                                graph.nodes().getLast().data())
                                                        .state());
                                        steps++;
                                    }
                                }
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "nativeSteps",
                            steps,
                            "targetIntentMasks",
                            16,
                            "boundary",
                            "accepted immutable target intent reducer; transport and server acceptance remain separate");
                });
    }

    @Test
    void kongReducerReadsConsecutiveAuthoritativeYawEndpoints() {
        AnimSyncOracleRunner.run(
                "parcool",
                "kong-native-r-endpoints",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    var player = mock(Player.class);
                    Field old = OracleReflection.field(LivingEntity.class, "yBodyRotO", "F");
                    Field current = OracleReflection.field(LivingEntity.class, "yBodyRot", "F");
                    old.setAccessible(true);
                    current.setAccessible(true);
                    var nativeAnimator =
                            new com.alrex.parcool.client.animation.impl.KongVaultAnimator();
                    var state = new AnimationGraphState.KongVault(0, 0);
                    var samples = new ArrayList<Map<String, Object>>();
                    for (float before :
                            new float[] {-180, -179.999f, -90, -0f, 89.999f, 90, 179.999f}) {
                        for (float after :
                                new float[] {-180, -179.999f, -90, -0f, 89.999f, 90, 179.999f}) {
                            old.setFloat(player, before);
                            current.setFloat(player, after);
                            nativeAnimator.tick(player);
                            state = ParCoolReducers.kongStep(state, before, after);
                            var graph =
                                    adapter.freeze(
                                            AnimationGraphState.neutral(),
                                            nativeAnimator,
                                            new java.util.concurrent.atomic.AtomicInteger()
                                                    ::incrementAndGet,
                                            ignored -> null);
                            assertEquals(
                                    state,
                                    ((AnimationGraphState.Animator) graph.nodes().getLast().data())
                                            .state());
                            samples.add(
                                    Map.of(
                                            "oldYawBits",
                                            bits(before),
                                            "yawBits",
                                            bits(after),
                                            "differenceBits",
                                            bits(state.yRotDifference()),
                                            "oldDifferenceBits",
                                            bits(state.yRotDifferenceOld())));
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "endpointPairs", samples, "nativeSteps", 49, "atanNotAtan2", true);
                });
    }

    private static Animator construct(Class<? extends Animator> type)
            throws ReflectiveOperationException {
        for (var constructor : type.getConstructors()) {
            var args = new Object[constructor.getParameterCount()];
            var parameters = constructor.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] =
                        parameters[i] == boolean.class
                                ? false
                                : parameters[i] == double.class
                                        ? .42
                                        : parameters[i].isEnum()
                                                ? parameters[i].getEnumConstants()[0]
                                                : null;
            }
            return (Animator) constructor.newInstance(args);
        }
        throw new AssertionError("No native animator constructor: " + type);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AnimationState.Grid4 interval(
            float[] values, AnimationTypes.Time age, float predecessor) {
        int base = Math.toIntExact(Math.multiplyExact(age.tick(), 3));
        return new AnimationState.Grid4(
                base,
                base == 0 ? predecessor : values[base - 1],
                values[base],
                values[base + 1],
                values[base + 2],
                values[base + 3]);
    }

    private static int bits(float value) {
        return Float.floatToRawIntBits(value);
    }
}
