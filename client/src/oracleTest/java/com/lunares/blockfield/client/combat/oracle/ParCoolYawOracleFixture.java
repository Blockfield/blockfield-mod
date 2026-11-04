package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.client.animation.impl.*;
import com.alrex.parcool.fabric.RenderFrameEvent;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.oracle.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParCoolYawOracleFixture {
    private static final ActionTag[] WRITERS = {
        ActionTag.CLING_TO_CLIFF,
        ActionTag.HANG_DOWN,
        ActionTag.HORIZONTAL_WALL_RUN,
        ActionTag.QUICK_TURN,
        ActionTag.SKY_DIVE,
        ActionTag.VERTICAL_WALL_RUN
    };

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allActionWriterSubsetsAndAnimatorOverridesMatchNativeRegistrationOrder() {
        AnimSyncOracleRunner.run(
                "parcool",
                "canonical-yaw-native-order",
                () -> {
                    var adapter =
                            new ParCoolGraph(
                                    getClass().getClassLoader(),
                                    "com.alrex.parcool.client.animation.impl.");
                    var player = mock(Player.class, CALLS_REAL_METHODS);
                    var event = mock(RenderFrameEvent.Pre.class);
                    var partials = mock(DeltaTracker.class);
                    when(event.getPartialTick()).thenReturn(partials);
                    var look = new net.minecraft.world.phys.Vec3(.3, -.2, .9);
                    when(player.getLookAngle()).thenReturn(look);
                    var variants = new ArrayList<Animator>();
                    variants.add(null);
                    for (var direction :
                            com.alrex.parcool.common.action.impl.Roll.Direction.values())
                        variants.add(new RollAnimator(direction));
                    for (var direction :
                            com.alrex.parcool.common.action.impl.Dodge.DodgeDirection.values())
                        variants.add(new DodgeAnimator(direction));
                    variants.add(new RideZiplineAnimator());
                    var rows = new ArrayList<Map<String, Object>>();
                    int count = 0;
                    for (int configuration = 0; configuration < 3; configuration++) {
                        var baseline =
                                new Angles(
                                        171.125f,
                                        -179.75f,
                                        17,
                                        -13,
                                        -173.75f,
                                        configuration == 0 ? -0f : 179.5f,
                                        -175.25f,
                                        configuration == 1 ? -179.875f : 73.25f);
                        for (int mask = 0; mask < 64; mask++) {
                            for (Animator actor : variants) {
                                var graph =
                                        adapter.freeze(
                                                AnimationGraphState.neutral(),
                                                actor,
                                                new AtomicInteger()::incrementAndGet,
                                                ignored -> null);
                                var context = context(baseline, configuration, mask, player, event);
                                var root =
                                        graph.roots().stream()
                                                .filter(
                                                        value ->
                                                                value.layer()
                                                                        == LayerTag.PARCOOL_ACTIVE)
                                                .findFirst()
                                                .orElseThrow()
                                                .node();
                                var state =
                                        new ParCool(
                                                root,
                                                context.passiveStart(),
                                                context.option(),
                                                context.passive(),
                                                context.actions(),
                                                context.hangGrid(),
                                                context.diveGrid());
                                var frame = new ParCoolFrame(graph, state);
                                var actions = frame.scratchAttachment();
                                byte[] before = AnimationSchema.encode(state);
                                for (float fraction :
                                        new float[] {0, 1 / 65536f, .375f, 65535 / 65536f}) {
                                    when(partials.getGameTimeDeltaPartialTick(true))
                                            .thenReturn(fraction);
                                    write(player, baseline);
                                    for (var action : actions.getList())
                                        action.onRenderTick(event, player, actions);
                                    if (actor != null) actor.onRenderTick(event, player, actions);
                                    var nativeAngles = read(player);
                                    var selected =
                                            ParCoolYaw.apply(
                                                    baseline, frame, fraction, Time.whole(0));
                                    assertArrayEquals(
                                            AnimationSchema.encode(nativeAngles),
                                            AnimationSchema.encode(selected),
                                            "mask="
                                                    + mask
                                                    + ", configuration="
                                                    + configuration
                                                    + ", actor="
                                                    + (actor == null
                                                            ? "passive"
                                                            : actor.getClass().getSimpleName()));
                                    assertEquals(
                                            selected,
                                            ParCoolYaw.apply(
                                                    baseline, frame, fraction, Time.whole(0)));
                                    count++;
                                    if (mask == 63)
                                        rows.add(
                                                Map.of(
                                                        "configuration",
                                                        configuration,
                                                        "actor",
                                                        actor == null
                                                                ? "passive"
                                                                : adapter.tag(actor).name(),
                                                        "fractionBits",
                                                        Float.floatToRawIntBits(fraction),
                                                        "yawBits",
                                                        bits(selected)));
                                }
                                assertArrayEquals(before, AnimationSchema.encode(state));
                            }
                        }
                    }
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "cases",
                            count,
                            "writerSubsets",
                            64,
                            "animatorVariants",
                            variants.size(),
                            "orderedYawBits",
                            rows,
                            "retainedStateUnchanged",
                            true,
                            "boundary",
                            "Hang endpoint is supplied by a native Cling/Hang prefix; this checks ordered writes, not retained-grid production or composed native trajectory equality");
                });
    }

    private static ParCool context(
            Angles baseline, int configuration, int mask, Player player, RenderFrameEvent.Pre event)
            throws Exception {
        var context = ParCoolNativeValues.context();
        var states = new ArrayList<ActionState>(context.actions());
        var normalized = new net.minecraft.world.phys.Vec3(.3, 0, .9).normalize();
        for (int index = 0; index < WRITERS.length; index++) {
            var tag = WRITERS[index];
            List<ActionInput> inputs =
                    switch (tag) {
                        case CLING_TO_CLIFF ->
                                List.of(
                                        new ClingInput(
                                                .125f,
                                                FacingDirection.values()[configuration],
                                                new Vec3(.3, -.2, .9)));
                        case QUICK_TURN ->
                                List.of(
                                        new AcceptedStart(
                                                tag,
                                                0,
                                                configuration != 1,
                                                new Vec3(normalized.x, normalized.y, normalized.z),
                                                null,
                                                0,
                                                true));
                        default -> states.get(tag.id()).inputs();
                    };
            states.set(tag.id(), new ActionState(tag, (mask & (1 << index)) != 0, 3, 0, 3, inputs));
        }
        // The fold consumes an already resolved Hang endpoint. Obtain that fixture input from
        // the real native prefix, including Cling's earlier write, rather than mirroring a port.
        var prefixState =
                new ParCool(
                        null,
                        context.passiveStart(),
                        context.option(),
                        context.passive(),
                        states,
                        context.hangGrid(),
                        context.diveGrid());
        var prefix =
                new ParCoolFrame(AnimationGraphState.neutral(), prefixState).scratchAttachment();
        write(player, baseline);
        prefix.get(com.alrex.parcool.common.action.impl.ClingToCliff.class)
                .onRenderTick(event, player, prefix);
        float predecessor = get(player, LivingEntity.class, "yBodyRot");
        prefix.get(com.alrex.parcool.common.action.impl.HangDown.class)
                .onRenderTick(event, player, prefix);
        float hang = get(player, LivingEntity.class, "yBodyRot");
        return new ParCool(
                null,
                context.passiveStart(),
                context.option(),
                context.passive(),
                states,
                new HangGrid(new Grid4(0, predecessor, hang, hang, hang, hang), predecessor),
                context.diveGrid());
    }

    private static void write(Player player, Angles value) throws Exception {
        player.setYRot(value.yRot());
        player.setXRot(value.xRot());
        set(player, Entity.class, "yRotO", value.yRotO());
        set(player, Entity.class, "xRotO", value.xRotO());
        set(player, LivingEntity.class, "yBodyRotO", value.yBodyRotO());
        set(player, LivingEntity.class, "yBodyRot", value.yBodyRot());
        set(player, LivingEntity.class, "yHeadRotO", value.yHeadRotO());
        set(player, LivingEntity.class, "yHeadRot", value.yHeadRot());
    }

    private static Angles read(Player player) throws Exception {
        return new Angles(
                get(player, Entity.class, "yRotO"),
                player.getYRot(),
                get(player, Entity.class, "xRotO"),
                player.getXRot(),
                get(player, LivingEntity.class, "yBodyRotO"),
                get(player, LivingEntity.class, "yBodyRot"),
                get(player, LivingEntity.class, "yHeadRotO"),
                get(player, LivingEntity.class, "yHeadRot"));
    }

    private static void set(Player player, Class<?> owner, String name, float value)
            throws Exception {
        var field = OracleReflection.field(owner, name, "F");
        field.setAccessible(true);
        field.setFloat(player, value);
    }

    private static float get(Player player, Class<?> owner, String name) throws Exception {
        var field = OracleReflection.field(owner, name, "F");
        field.setAccessible(true);
        return field.getFloat(player);
    }

    private static List<Integer> bits(Angles value) {
        return java.util.stream.Stream.of(
                        value.yRotO(),
                        value.yRot(),
                        value.xRotO(),
                        value.xRot(),
                        value.yBodyRotO(),
                        value.yBodyRot(),
                        value.yHeadRotO(),
                        value.yHeadRot())
                .map(Float::floatToRawIntBits)
                .toList();
    }
}
