package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.impl.QuickTurn;
import com.alrex.parcool.common.attachment.client.Animation;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.StartKey;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.ParCoolStagesCases;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ParCoolAccepted against the pinned bf7 Action.start/finish other-client path: installed animator
 * (constructor values), replacement of an incumbent, and the stored visual inputs.
 */
class ParCoolAcceptedOracleFixture {
    private static final ParCoolGraph NATIVE =
            new ParCoolGraph(
                    ParCoolAcceptedOracleFixture.class.getClassLoader(),
                    "com.alrex.parcool.client.animation.impl.");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptedPatchesMatchNativeOtherClientCallbacks() {
        AnimSyncOracleRunner.run(
                "parcool",
                "production-accepted-patches",
                () -> {
                    boolean previous =
                            com.lunares.blockfield.client.combat.AnimationPolicy.forcing();
                    {
                        com.lunares.blockfield.client.combat.AnimationPolicy.arm(
                                com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                        com.lunares.blockfield.client.combat.AnimationPolicy.install();
                    }
                    try (var attachment = mockStatic(Parkourability.class);
                            var singleton = mockStatic(Minecraft.class);
                            var config = ParCoolConfigFixture.inMemory()) {
                        singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                        var rows = new ArrayList<Map<String, Object>>();
                        for (ActionTag tag : ActionTag.values())
                            for (boolean busy : new boolean[] {false, true}) {
                                rows.add(start(tag, busy, attachment));
                                rows.add(finish(tag, busy, false, attachment));
                                if (tag == ActionTag.DIVE)
                                    rows.add(finish(tag, busy, true, attachment));
                            }
                        rows.add(quickTurn());
                        OracleTransformAudit.requireConfig("parcool.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "cases",
                                rows,
                                "scope",
                                "bf7 Action.start/finish on a remote client player for all 27"
                                        + " actions, empty and occupied Animation; QuickTurn local"
                                        + " start semantics for the server-owned turn flag");
                    } finally {
                        if (previous) {
                            com.lunares.blockfield.client.combat.AnimationPolicy.arm(
                                    com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                            com.lunares.blockfield.client.combat.AnimationPolicy.install();
                        } else com.lunares.blockfield.client.combat.AnimationPolicy.restore();
                    }
                });
    }

    private static Player remote() throws Exception {
        var player = ParCoolCheckpointOracleFixture.player();
        var level = mock(ClientLevel.class, RETURNS_DEEP_STUBS);
        when(level.isClientSide()).thenReturn(true);
        when(level.isLoaded(any())).thenReturn(false);
        doReturn(level).when(player).level();
        doReturn(level).when(player).getCommandSenderWorld();
        when(player.isLocalPlayer()).thenReturn(false);
        when(player.getBoundingBox()).thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3));
        when(player.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(player.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(7));
        return player;
    }

    static ByteBuffer payload(ActionTag tag) {
        var buffer = ByteBuffer.allocate(128);
        switch (tag) {
            case CAT_LEAP, CLING_TO_CLIFF, SLIDE, QUICK_TURN -> buffer.putDouble(.6).putDouble(-.8);
            case DIVE -> buffer.putDouble(-.37).put((byte) 1);
            case DODGE, ROLL -> buffer.putInt(2);
            case FLIPPING -> buffer.putInt(1).putInt(1);
            case HANG_DOWN -> buffer.putDouble(.45);
            case HIDE_IN_BLOCK -> {
                buffer.put((byte) 1).put((byte) 1);
                buffer.putInt(1).putInt(2).putInt(3).putInt(4).putInt(5).putInt(6);
                for (double value : new double[] {1, 2, 3, 4, 5, 6, .3, -.1, .9})
                    buffer.putDouble(value);
            }
            case HORIZONTAL_WALL_RUN ->
                    buffer.put((byte) 1).putDouble(.6).putDouble(-.8).putDouble(-.8).putDouble(-.6);
            case RIDE_ZIPLINE -> buffer.putDouble(3).putDouble(-1.25).putDouble(.5);
            case VAULT -> buffer.put((byte) 2).putDouble(1).putDouble(2).putDouble(3).putDouble(4);
            case VERTICAL_WALL_RUN ->
                    buffer.putDouble(1.5).putFloat(.6f).putDouble(.6).putDouble(.1).putDouble(-.8);
            case WALL_JUMP ->
                    buffer.putDouble(.2)
                            .putDouble(.4)
                            .putDouble(.1)
                            .putDouble(-1)
                            .putDouble(0)
                            .put((byte) 1);
            default -> {
                for (int i = 0; i < 8; i++) buffer.putDouble(0);
            }
        }
        buffer.flip();
        return buffer;
    }

    private static EnumMap<ActionTag, ActionState> rows(boolean doing) {
        var rows = new EnumMap<ActionTag, ActionState>(ActionTag.class);
        for (ActionState row : ParCoolStagesCases.neutral().actions()) {
            var value =
                    new ActionState(
                            row.action(), doing, doing ? 4 : 0, doing ? 0 : 6, 3, row.inputs());
            if (row.action() == ActionTag.RIDE_ZIPLINE)
                value =
                        ParCoolStagesCases.with(
                                value, new ZiplineInput(null, .03, -.2, .08, false, null));
            if (row.action() == ActionTag.CHARGE_JUMP)
                value = ParCoolStagesCases.with(value, new ChargeInput(0, 0, 0, false, 0, true));
            rows.put(row.action(), value);
        }
        return rows;
    }

    private static Map<String, Object> start(
            ActionTag tag, boolean busy, org.mockito.MockedStatic<Parkourability> attachment)
            throws Exception {
        var player = remote();
        var rows = rows(false);
        var parkour = ParCoolStagesOracleFixture.parkour(rows);
        attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
        var animation = new Animation();
        var incumbent = busy ? new com.alrex.parcool.client.animation.impl.TapAnimator() : null;
        field(animation, "animator").set(animation, incumbent);
        doReturn(animation).when(player).getAttachedOrCreate(any());
        Action action = parkour.get(ParCoolActions.type(tag));
        var data = payload(tag);
        action.start(player, parkour, data.duplicate());
        var context =
                tag == ActionTag.HANG_DOWN
                        ? new ParCoolAccepted.Context(
                                axis(field(action, "hangingBarAxis").get(action)),
                                field(action, "orthogonalToBar").getBoolean(action),
                                false,
                                false,
                                0,
                                false)
                        : new ParCoolAccepted.Context(null, false, false, false, 0, false);
        var patch = ParCoolAccepted.start(rows.get(tag), data, context, busy);
        compare(tag, animation, incumbent, patch);
        var row = patch.actionInputs().getFirst();
        if (tag != ActionTag.QUICK_TURN)
            assertEquals(row, ParCoolActions.capture(action, row, player), tag + " start inputs");
        assertTrue(row.doing());
        assertEquals(0, row.tickFromStarted());
        return Map.of(
                "action",
                tag.name(),
                "busy",
                busy,
                "kind",
                "start",
                "outcome",
                patch.outcome().name(),
                "patchHex",
                HexFormat.of().formatHex(AnimationSchema.encode(patch)));
    }

    private static Map<String, Object> finish(
            ActionTag tag,
            boolean busy,
            boolean water,
            org.mockito.MockedStatic<Parkourability> attachment)
            throws Exception {
        var player = remote();
        when(player.isInWaterOrBubble()).thenReturn(water);
        when(player.getDeltaMovement()).thenReturn(new net.minecraft.world.phys.Vec3(0, -.4, 0));
        var rows = rows(true);
        var parkour = ParCoolStagesOracleFixture.parkour(rows);
        field(parkour.getAdditionalProperties(), "notLandingTick")
                .setInt(parkour.getAdditionalProperties(), 9);
        attachment.when(() -> Parkourability.get(player)).thenReturn(parkour);
        var animation = new Animation();
        doReturn(animation).when(player).getAttachedOrCreate(any());
        Action action = parkour.get(ParCoolActions.type(tag));
        // Stop callbacks read fields their own start stored (HideInBlock hiding point).
        action.start(player, parkour, payload(tag));
        var incumbent = busy ? new com.alrex.parcool.client.animation.impl.TapAnimator() : null;
        field(animation, "animator").set(animation, incumbent);
        action.finish(player);
        boolean skyDive = parkour.get(com.alrex.parcool.common.action.impl.SkyDive.class).isDoing();
        var context = new ParCoolAccepted.Context(null, false, false, water, 9, skyDive);
        var patch = ParCoolAccepted.finish(rows.get(tag), context, busy);
        compare(tag, animation, incumbent, patch);
        assertFalse(patch.actionInputs().getFirst().doing());
        return Map.of(
                "action",
                tag.name(),
                "busy",
                busy,
                "kind",
                water ? "finish-water" : "finish",
                "outcome",
                patch.outcome().name(),
                "patchHex",
                HexFormat.of().formatHex(AnimationSchema.encode(patch)));
    }

    private static void compare(
            ActionTag tag, Animation animation, Object incumbent, AnimationEvent.Patch patch)
            throws Exception {
        var installed = field(animation, "animator").get(animation);
        if (patch.outcome() == Outcome.NO_CHANGE) {
            assertSame(incumbent, installed, tag + " must not install");
            return;
        }
        assertNotSame(incumbent, installed, tag + " must install");
        var keys = new AtomicInteger(1);
        var nativeGraph =
                NATIVE.freeze(
                        AnimationGraphState.neutral(),
                        installed,
                        new AtomicInteger()::incrementAndGet,
                        ignored -> new StartKey(7, keys.getAndIncrement()));
        var expected = new ArrayList<NodeData>();
        for (Node node : nativeGraph.nodes()) expected.add(strip(node.data()));
        var actual = new ArrayList<NodeData>();
        for (Node node : patch.replacement().nodes()) actual.add(strip(node.data()));
        Comparator<NodeData> byTag = Comparator.comparingInt(d -> ((Animator) d).tag().id());
        expected.sort(byTag);
        actual.sort(byTag);
        assertEquals(expected, actual, tag + " constructor outcome");
    }

    /** Fragment node references are local IDs; compare constructor values only. */
    private static NodeData strip(NodeData data) {
        var actor = (Animator) data;
        if (actor.state() instanceof DiveHost host)
            return new Animator(
                    actor.tag(),
                    actor.tick(),
                    new DiveHost(
                            new NodeRef(0),
                            host.skyDiveAnimator() == null ? null : new NodeRef(0),
                            host.fromInAir(),
                            host.oldSkyDiveDoing(),
                            host.transitioning(),
                            host.transitionTick()));
        return actor;
    }

    private static Map<String, Object> quickTurn() throws Exception {
        var player = remote();
        var data = payload(ActionTag.QUICK_TURN);
        var turn = new QuickTurn();
        turn.onStartInLocalClient(player, null, data.duplicate());
        boolean rightward = field(turn, "turnRightward").getBoolean(turn);
        var start = (net.minecraft.world.phys.Vec3) field(turn, "startAngle").get(turn);
        var patch =
                ParCoolAccepted.start(
                        rows(false).get(ActionTag.QUICK_TURN),
                        data,
                        new ParCoolAccepted.Context(null, false, rightward, false, 0, false),
                        false);
        var accepted =
                (AcceptedStart)
                        patch.actionInputs().getFirst().inputs().stream()
                                .filter(AcceptedStart.class::isInstance)
                                .findFirst()
                                .orElseThrow();
        assertEquals(new Vec3(start.x, start.y, start.z), accepted.direction());
        assertEquals(rightward, accepted.flag());
        assertTrue(rightward, "first local start toggles false to true");
        return Map.of("action", "QUICK_TURN", "kind", "local-start-semantics", "flag", rightward);
    }

    private static BarAxis axis(Object value) {
        return value == null ? null : BarAxis.valueOf(((Enum<?>) value).name());
    }

    private static java.lang.reflect.Field field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass())
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // continue to the superclass
            }
        throw new NoSuchFieldException(name);
    }
}
