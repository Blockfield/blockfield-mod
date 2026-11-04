package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ClientCombatSceneTest {
    private static final long CONNECTION = 3, STREAM = 17, SCENE = 1, SUBSCRIPTION = 5;
    private static final int FULL_RESET =
            AnimationRecipe.SnapshotRecord.FLAG_FULL | AnimationRecipe.SnapshotRecord.FLAG_RESET;
    private static final int FULL_SEAT =
            AnimationRecipe.SnapshotRecord.FLAG_FULL | AnimationRecipe.SnapshotRecord.FLAG_SEAT;
    private static final int FULL = AnimationRecipe.SnapshotRecord.FLAG_FULL;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        ClientCombatScene.disconnect();
    }

    static RemotePlayer player(int id) {
        var p = mock(RemotePlayer.class);
        when(p.getId()).thenReturn(id);
        when(p.getUUID()).thenReturn(new UUID(0, id));
        try {
            // DrivenPresentation.placement reads the walk field of every RemotePlayer sample.
            var field =
                    net.minecraft.world.entity.LivingEntity.class.getDeclaredField("walkAnimation");
            field.setAccessible(true);
            field.set(p, new net.minecraft.world.entity.WalkAnimationState());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return p;
    }

    /** One exact record at {@code tick}, continuous with the previous tick's origin. */
    private static AnimationRecipe.SnapshotRecord rec(
            long stream,
            long connection,
            long scene,
            int entity,
            long targetEpoch,
            long seat,
            long tick,
            double prevX,
            double x,
            int flags) {
        var identity =
                new AnimationState.Identity(
                        connection,
                        stream,
                        scene,
                        entity,
                        0,
                        SUBSCRIPTION,
                        targetEpoch,
                        seat,
                        0,
                        0,
                        0,
                        AnimationSchema.layoutDigest(),
                        ExactFixtures.ZERO);
        var base = ExactFixtures.checkpoint(identity, tick, x);
        var placement =
                new AnimationState.Placement(
                        new AnimationState.Vec3(prevX, 64, 0),
                        new AnimationState.Vec3(x, 64, 0),
                        base.placement().angles(),
                        base.placement().walk(),
                        base.placement().deltaMovementOnPreviousTick(),
                        base.placement().deltaMovement(),
                        base.placement().vehicleAngles(),
                        List.of());
        var checkpoint =
                new AnimationState(
                        identity,
                        tick,
                        0,
                        tick * 256,
                        placement,
                        base.vanilla(),
                        base.stance(),
                        base.equipment(),
                        base.tacz(),
                        base.lr(),
                        base.graph(),
                        base.parcool(),
                        base.create(),
                        base.sbw(),
                        base.decisions());
        return new AnimationRecipe.SnapshotRecord(
                identity, flags, checkpoint.placement(), checkpoint);
    }

    private static AnimationRecipe.SnapshotRecord rec(
            int entity, long targetEpoch, long seat, long tick, double prevX, double x, int flags) {
        return rec(STREAM, CONNECTION, SCENE, entity, targetEpoch, seat, tick, prevX, x, flags);
    }

    private static PlayerSnapshotS2CPacket batch(
            long stream,
            long prev,
            long tick,
            long connection,
            long scene,
            AnimationRecipe.SnapshotRecord... records) {
        return PlayerSnapshotS2CPacket.animation(
                stream,
                prev,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(
                        connection, scene, List.of(records), List.of(), List.of()));
    }

    private static PlayerSnapshotS2CPacket batch(
            long prev, long tick, AnimationRecipe.SnapshotRecord... records) {
        return batch(STREAM, prev, tick, CONNECTION, SCENE, records);
    }

    static void warm(double now) {
        for (int n = 0; n < 8; n++) {
            ClientCombatScene.accept(
                    batch(
                            n == 0 ? 0 : 100 + n,
                            100 + n,
                            rec(1, 7, 0, 100 + n, (n - 1) * .28, n * .28, n == 0 ? FULL_RESET : 0)),
                    now + n * 50);
            ClientCombatScene.mainFrame(now + n * 50);
        }
    }

    // Wall-clock isolation: driven() reads the real clock while arrivals/frames carry
    // synthetic stamps, so any stall past the hold window failed these tests spuriously.
    // Frozen at the anchor (before every arrival) driven() never times out; accepts and
    // frames keep their explicit synthetic stamps, so the clock logic runs unchanged.
    static MockedStatic<ClientCombatScene> freeze(double now) {
        var frozen = mockStatic(ClientCombatScene.class, CALLS_REAL_METHODS);
        frozen.when(ClientCombatScene::nowMs).thenReturn(now);
        return frozen;
    }

    @Test
    void lateLinkageAndRepeatedIdenticalListsDoNotCorruptFreshSeatFull() {
        for (boolean fullFirst : List.of(false, true)) {
            ClientCombatScene.disconnect();
            var p = player(1);
            var mc = mock(Minecraft.class);
            double now = ClientCombatScene.nowMs();
            try (var app = mockStatic(Minecraft.class);
                    var wall = freeze(now)) {
                app.when(Minecraft::getInstance).thenReturn(mc);
                ClientCombatScene.add(p);
                warm(now);
                var vehicle = mock(Entity.class);
                when(vehicle.getUUID()).thenReturn(new UUID(0, 30));
                when(vehicle.getPassengers()).thenReturn(List.of(p));
                if (!fullFirst) {
                    when(p.getVehicle()).thenReturn(vehicle);
                    ClientCombatScene.linkageChanged();
                    assertFalse(ClientCombatScene.driven(p));
                }
                ClientCombatScene.accept(
                        batch(108, 108, rec(1, 7, 1, 108, 7 * .28, 7, FULL_SEAT)), now + 400);
                if (fullFirst) {
                    when(p.getVehicle()).thenReturn(vehicle);
                    ClientCombatScene.linkageChanged();
                }
                ClientCombatScene.mainFrame(now + 400);
                assertTrue(ClientCombatScene.driven(p));
                assertEquals(7, ClientCombatScene.receipt().entities().get(1).x());
                ClientCombatScene.linkageChanged();
                ClientCombatScene.mainFrame(now + 410);
                assertTrue(ClientCombatScene.driven(p));
                assertEquals(1, ClientCombatScene.receipt().entities().get(1).before().seat());
            }
        }
    }

    @Test
    void nativeRemoveAfterNewSnapshotLeavesOtherVictimsAndReceiptWatermarkAlone() {
        var p = player(1);
        var other = player(2);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            ClientCombatScene.add(other);
            for (int n = 0; n < 8; n++) {
                // New pairing (n == 0) starts FULL|RESET; later fulls
                // are ordinary codec refreshes.
                ClientCombatScene.accept(
                        batch(
                                n == 0 ? 0 : 100 + n,
                                100 + n,
                                rec(
                                        1,
                                        7,
                                        0,
                                        100 + n,
                                        (n - 1) * .28,
                                        n * .28,
                                        n == 0 ? FULL_RESET : FULL),
                                rec(
                                        2,
                                        7,
                                        0,
                                        100 + n,
                                        1 + (n - 1) * .28,
                                        1 + n * .28,
                                        n == 0 ? FULL_RESET : FULL)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            var before = ClientCombatScene.receipt();
            ClientCombatScene.remove(1);
            assertFalse(ClientCombatScene.driven(p));
            assertTrue(ClientCombatScene.driven(other));
            assertEquals(before, ClientCombatScene.receipt());
            ClientCombatScene.mainFrame(now + 360);
            assertFalse(ClientCombatScene.receipt().entities().containsKey(1));
            assertTrue(ClientCombatScene.receipt().entities().containsKey(2));
            assertEquals(107, ClientCombatScene.receipt().high());
        }
    }

    @Test
    void sceneResetKeepsConnectionSequenceAndNewArrivalDoesNotChangeRenderedTicketH() {
        var p = player(1);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            warm(now);
            assertEquals(107, ClientCombatScene.receipt().high());
            ClientCombatScene.accept(
                    batch(108, 108, rec(1, 7, 0, 108, 7 * .28, 8 * .28, 0)), now + 400);
            // An arrival without a frame never changes the rendered H.
            assertEquals(107, ClientCombatScene.receipt().high());
            ClientCombatScene.mainFrame(now + 401);
            assertEquals(108, ClientCombatScene.receipt().high());
            ClientCombatScene.sceneReset();
            assertNull(ClientCombatScene.receipt());
            assertNull(ClientCombatScene.nextTicket(now + 402));
            ClientCombatScene.add(p);
            for (int n = 9; n < 17; n++) {
                // A reset pairing restarts with FULL|RESET, then continues plain.
                ClientCombatScene.accept(
                        batch(
                                100 + n,
                                100 + n,
                                rec(
                                        1,
                                        7,
                                        0,
                                        100 + n,
                                        (n - 1) * .28,
                                        n * .28,
                                        n == 9 ? FULL_RESET : 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            // The same stream continues its high-water after the reset.
            assertEquals(116, ClientCombatScene.receipt().high());
            assertEquals(STREAM, ClientCombatScene.receipt().observerStreamEpoch());
            assertTrue(ClientCombatScene.driven(p));
        }
    }

    @Test
    void backendJoinSchedulesWholeResetThenAcceptsLowEpochAndRestartsSequence() {
        var mc = mock(Minecraft.class);
        var old = player(1);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(old);
            for (int n = 0; n < 8; n++) {
                ClientCombatScene.accept(
                        batch(
                                57,
                                n == 0 ? 0 : 2_400_000 + n,
                                2_400_000 + n,
                                57,
                                1,
                                rec(
                                        57,
                                        57,
                                        1,
                                        1,
                                        7,
                                        0,
                                        2_400_000 + n,
                                        (n - 1) * .28,
                                        n * .28,
                                        n == 0 ? FULL_RESET : 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            assertNotNull(ClientCombatScene.receipt());
            assertEquals(57L, ClientCombatScene.debugSnapshot().get("stream"));
            ClientCombatScene.debugLog(0);
            ClientCombatScene.clientTick(now + 353);
            try (var networking =
                            mockStatic(
                                    net.fabricmc.fabric.api.client.networking.v1
                                            .ClientPlayNetworking.class);
                    var loader = mockStatic(net.fabricmc.loader.api.FabricLoader.class)) {
                loader.when(net.fabricmc.loader.api.FabricLoader::getInstance)
                        .thenReturn(mock(net.fabricmc.loader.api.FabricLoader.class));
                ClientCombatScene.register();
            }
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN
                    .invoker()
                    .onPlayReady(null, null, mc);
            var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(mc).execute(task.capture());
            assertEquals(57L, ClientCombatScene.debugSnapshot().get("stream"));
            task.getValue().run();
            assertNull(ClientCombatScene.receipt());
            assertNull(ClientCombatScene.nextTicket(now + 354));
            assertTrue(ClientCombatScene.debugLog(0).isEmpty());
            var fresh = player(1);
            ClientCombatScene.add(fresh);
            for (int n = 0; n < 8; n++) {
                ClientCombatScene.accept(
                        batch(
                                1,
                                n == 0 ? 0 : 200 + n,
                                200 + n,
                                1,
                                1,
                                rec(
                                        1,
                                        1,
                                        1,
                                        1,
                                        7,
                                        0,
                                        200 + n,
                                        5 + (n - 1) * .28,
                                        5 + n * .28,
                                        n == 0 ? FULL_RESET : 0)),
                        now + 400 + n * 50);
                ClientCombatScene.mainFrame(now + 400 + n * 50);
            }
            assertTrue(ClientCombatScene.driven(fresh));
            assertFalse(ClientCombatScene.driven(old));
            assertEquals(207, ClientCombatScene.receipt().high());
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("protocolDefects"));
            // A new backend has a new per-connection server intake; the stream restarts low.
            assertEquals(1L, ClientCombatScene.debugSnapshot().get("stream"));
            assertNotNull(ClientCombatScene.receipt());
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                    .invoker()
                    .onPlayDisconnect(null, mc);
            verify(mc, times(2)).execute(task.capture());
            assertNotNull(ClientCombatScene.receipt());
            task.getValue().run();
            assertEquals(-1L, ClientCombatScene.debugSnapshot().get("stream"));
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("sequence"));
            assertNull(ClientCombatScene.receipt());
        }
    }

    @Test
    void resetOnClientThreadCannotBeQueuedBehindNewBackendPayloads() {
        var mc = mock(Minecraft.class);
        when(mc.isSameThread()).thenReturn(true);
        var p = player(1);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            warm(now);
            assertNotNull(ClientCombatScene.receipt());
            assertTrue(ClientCombatScene.driven(p));
            ClientCombatScene.resetConnection(mc);
            verify(mc, never()).execute(any(Runnable.class));
            assertNull(ClientCombatScene.receipt());
            assertEquals(-1L, ClientCombatScene.debugSnapshot().get("stream"));
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("sequence"));
        }
    }

    @Test
    void sameDimensionRespawnReregistersSurvivingEntitiesAndDecodesDeltas() {
        var mc = mock(Minecraft.class);
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        mc.level = level;
        var survivor = player(1);
        when(level.entitiesForRendering()).thenReturn(List.of(survivor));
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(survivor);
            warm(now);
            ClientCombatScene.sceneReset();
            assertNull(ClientCombatScene.receipt());
            for (int n = 8; n < 16; n++) {
                ClientCombatScene.accept(
                        batch(100 + n, 100 + n, rec(1, 7, 0, 100 + n, (n - 1) * .28, n * .28, 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            assertTrue(ClientCombatScene.driven(survivor));
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("protocolDefects"));
            assertEquals(1L, ClientCombatScene.debugSnapshot().get("sceneGeneration"));
        }
    }

    @Test
    void respawnCannotResumeAnOldBaselineForANativeRePairStillWaitingForFull() {
        var mc = mock(Minecraft.class);
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        mc.level = level;
        var old = player(1);
        var replacement = player(1);
        when(level.entitiesForRendering()).thenReturn(List.of(replacement));
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(old);
            warm(now);
            ClientCombatScene.remove(1);
            ClientCombatScene.add(replacement);
            ClientCombatScene.sceneReset();
            for (int n = 8; n < 16; n++) {
                ClientCombatScene.accept(
                        batch(100 + n, 100 + n, rec(1, 7, 0, 100 + n, (n - 1) * .28, n * .28, 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
                assertFalse(ClientCombatScene.driven(replacement));
            }
            ClientCombatScene.accept(
                    batch(116, 116, rec(1, 8, 0, 116, 5 - .28, 5, FULL_RESET)), now + 800);
            ClientCombatScene.mainFrame(now + 800);
            assertTrue(ClientCombatScene.driven(replacement));
            assertEquals(5, ClientCombatScene.receipt().entities().get(1).x());
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("protocolDefects"));
        }
    }

    @Test
    void sameDimensionRespawnPreservesCreditForSeatFullBeforeNativeLinkage() {
        var mc = mock(Minecraft.class);
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        mc.level = level;
        var p = player(1);
        when(level.entitiesForRendering()).thenReturn(List.of(p));
        var vehicle = mock(Entity.class);
        when(vehicle.getUUID()).thenReturn(new UUID(0, 30));
        when(vehicle.getPassengers()).thenReturn(List.of(p));
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            warm(now);
            ClientCombatScene.accept(
                    batch(108, 108, rec(1, 7, 1, 108, 7 * .28, 3, FULL_SEAT)), now + 400);
            ClientCombatScene.sceneReset();
            for (int n = 9; n < 17; n++) {
                ClientCombatScene.accept(
                        batch(
                                100 + n,
                                100 + n,
                                rec(1, 7, 1, 100 + n, 3 + (n - 9) * .28, 3 + (n - 8) * .28, 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            assertTrue(ClientCombatScene.driven(p));
            when(p.getVehicle()).thenReturn(vehicle);
            ClientCombatScene.linkageChanged();
            assertTrue(ClientCombatScene.driven(p));
            assertEquals(1, ClientCombatScene.receipt().entities().get(1).before().seat());
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("protocolDefects"));
        }
    }

    @Test
    void localPredictionAndTheLocalRideStackAreNeverDriven() {
        var mc = mock(Minecraft.class);
        var local = mock(LocalPlayer.class);
        mc.player = local;
        var vehicle = mock(Entity.class);
        var coPassenger = player(2);
        var other = player(1);
        when(vehicle.getControllingPassenger()).thenReturn(local);
        for (Entity e : List.of(local, vehicle, coPassenger))
            when(e.getRootVehicle()).thenReturn(vehicle);
        when(other.getRootVehicle()).thenReturn(other);
        try (var app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            assertFalse(ClientCombatScene.eligible(local));
            assertFalse(ClientCombatScene.eligible(vehicle));
            assertFalse(ClientCombatScene.eligible(coPassenger));
            assertTrue(ClientCombatScene.eligible(other));
        }
    }

    // Pinned ClientLevel.addEntity offsets 0-8: removeEntity(id, DISCARDED) precedes the store.
    @Test
    void addEntityInternalRemoveCannotDiscardTheFullThatArrivedBeforeAdd() {
        var p = player(1);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            warm(now);
            ClientCombatScene.remove(1);
            ClientCombatScene.add(p);
            ClientCombatScene.mainFrame(now + 360);
            assertTrue(ClientCombatScene.driven(p));
            var r = ClientCombatScene.receipt();
            assertTrue(r.time() >= 104 && r.time() < 105, "R " + r.time());
            // 280 mm per tick from 0 at 100: 1.12 m at 104.
            assertEquals(1.12 + (r.time() - 104) * .28, r.entities().get(1).x(), 1e-9);
        }
    }

    @Test
    void sustainedFastMotionDoesNotLagSharedClock() {
        var normal = player(1);
        var fast = player(2);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(normal);
            ClientCombatScene.add(fast);
            for (int n = 0; n < 200; n++) {
                long tick = 100 + n;
                ClientCombatScene.accept(
                        batch(
                                n == 0 ? 0 : tick,
                                tick,
                                rec(1, 7, 0, tick, (n - 1) * .28, n * .28, n == 0 ? FULL_RESET : 0),
                                rec(
                                        2,
                                        7,
                                        0,
                                        tick,
                                        (n - 1) * 1.5,
                                        n * 1.5,
                                        n == 0 ? FULL_RESET : 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            // 10 s of 1.5 m/tick on one remote: exempt as sustained motion, so R keeps tracking
            // arrivals and the shared clock never lags behind one fast entity.
            var snap = ClientCombatScene.debugSnapshot();
            assertTrue((double) snap.get("occupancy") <= 5, "shared clock tracks arrivals");
            assertEquals(0L, snap.get("recoverySnaps"));
            var entities = ClientCombatScene.receipt().entities();
            assertTrue(entities.containsKey(1) && entities.containsKey(2));
            // The fast remote draws its real speed, not the traversal cap.
            double r = ClientCombatScene.receipt().time();
            assertEquals(1.5 * (r - 100), entities.get(2).x(), 1e-3);
            assertEquals(0.28 * (r - 100), entities.get(1).x(), 1e-3);
        }
    }

    @Test
    void burstStepTraversesAtCappedSpeedAndPassengerSkip() {
        var normal = player(1);
        var other = player(2);
        var riding = player(3);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(normal);
            ClientCombatScene.add(other);
            ClientCombatScene.add(riding);
            when(riding.getVehicle()).thenReturn(mock(Entity.class));
            for (int n = 0; n < 10; n++) {
                long tick = 100 + n;
                ClientCombatScene.accept(
                        batch(
                                n == 0 ? 0 : tick,
                                tick,
                                rec(1, 7, 0, tick, (n - 1) * .28, n * .28, n == 0 ? FULL_RESET : 0),
                                rec(2, 7, 0, tick, (n - 1) * .28, n * .28, n == 0 ? FULL_RESET : 0),
                                rec(
                                        3,
                                        7,
                                        0,
                                        tick,
                                        (n - 1) * .28,
                                        n * .28,
                                        n == 0 ? FULL_RESET : 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            // 1 s stall, then a catch-up burst: a 5.6 m step on the normal remote and a 9 m step
            // on the passenger, which must never cap the shared clock.
            double lastNow = now + 450;
            for (double t = now + 466; t < now + 1450; t += 16) {
                ClientCombatScene.mainFrame(t);
                lastNow = t;
            }
            double burstAt = lastNow + 16;
            double base = 9 * .28;
            for (int k = 0; k < 20; k++) {
                long tick = 110 + k;
                ClientCombatScene.accept(
                        batch(
                                tick,
                                tick,
                                rec(
                                        1,
                                        7,
                                        0,
                                        tick,
                                        base + (k == 0 ? 0 : 5.6 + (k - 1) * .28),
                                        base + 5.6 + k * .28,
                                        0),
                                rec(
                                        2,
                                        7,
                                        0,
                                        tick,
                                        base + (k == 0 ? 0 : (k - 1) * .28),
                                        base + k * .28,
                                        0),
                                rec(
                                        3,
                                        7,
                                        0,
                                        tick,
                                        base + (k == 0 ? 0 : 9 + (k - 1) * .28),
                                        base + 9 + k * .28,
                                        0)),
                        burstAt);
            }
            // Wiring pin: only the normal remote's step counts.
            assertEquals(5.6, ClientCombatScene.maxTraverseStep(110), 1e-9);
            assertEquals(0.28, ClientCombatScene.maxTraverseStep(111), 1e-9);
            double t = burstAt;
            double end = burstAt + 3000;
            double nextArrival = burstAt + 50;
            long lastTick = 129;
            // Analytic pre-burst baseline: R froze at the pre-stall high 109, so both remotes
            // draw record 109's origin. This asserts the first post-burst frame too, where the
            // uncapped clock draws the whole step at once.
            double prevX1 = base;
            double prevX2 = base;
            double maxDrawn = 0;
            boolean converged = false;
            while (t < end) {
                while (nextArrival <= t) {
                    lastTick++;
                    double x = base + 5.6 + (lastTick - 110) * .28;
                    double px = base + 5.6 + (lastTick - 111) * .28;
                    double nx = base + (lastTick - 110) * .28;
                    double npx = base + (lastTick - 111) * .28;
                    ClientCombatScene.accept(
                            batch(
                                    lastTick,
                                    lastTick,
                                    rec(1, 7, 0, lastTick, px, x, 0),
                                    rec(2, 7, 0, lastTick, npx, nx, 0),
                                    rec(3, 7, 0, lastTick, npx + 9, nx + 9, 0)),
                            nextArrival);
                    nextArrival += 50;
                }
                ClientCombatScene.mainFrame(t);
                var entities = ClientCombatScene.receipt().entities();
                double x1 = entities.get(1).x();
                double x2 = entities.get(2).x();
                assertTrue(x1 - prevX1 >= -1e-9, "drawn never moves back at " + t);
                assertTrue(x2 - prevX2 >= -1e-9, "drawn never moves back at " + t);
                assertTrue(x1 - prevX1 <= 16.0 / 50 + 1e-3, "drawn step above cap at " + t);
                assertTrue(x2 - prevX2 <= 16.0 / 50 + 1e-3, "drawn step above cap at " + t);
                maxDrawn = Math.max(maxDrawn, Math.max(x1 - prevX1, x2 - prevX2));
                prevX1 = x1;
                prevX2 = x2;
                var snap = ClientCombatScene.debugSnapshot();
                if ((double) snap.get("occupancy") <= (int) snap.get("delayTicks") + 1
                        && (double) snap.get("rate") <= 1.1) {
                    converged = true;
                    break;
                }
                t += 16;
            }
            assertTrue(converged, "lag converges back to servo regulation");
            assertTrue(maxDrawn < 1.0, "no metre-scale frame jump: " + maxDrawn);
            assertEquals(0L, ClientCombatScene.debugSnapshot().get("recoverySnaps"));
        }
    }

    @Test
    void fluctuatingFastMotionDoesNotLagSharedClock() {
        var normal = player(1);
        var flaky = player(2);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        double[] pattern = {1.5, 1.5, 0.5};
        try (var app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(normal);
            ClientCombatScene.add(flaky);
            double fx = 0;
            for (int n = 0; n < 400; n++) {
                long tick = 100 + n;
                double step = n == 0 ? 0 : pattern[(n - 1) % pattern.length];
                double prevFx = fx;
                fx += step;
                ClientCombatScene.accept(
                        batch(
                                n == 0 ? 0 : tick,
                                tick,
                                rec(1, 7, 0, tick, (n - 1) * .28, n * .28, n == 0 ? FULL_RESET : 0),
                                rec(2, 7, 0, tick, prevFx, fx, n == 0 ? FULL_RESET : 0)),
                        now + n * 50);
                ClientCombatScene.mainFrame(now + n * 50);
            }
            // 20 s of fluctuating fast motion beside a normal remote: the servo backstop holds
            // occupancy no matter the pattern, and the normal remote draws pose(R) exactly.
            var snap = ClientCombatScene.debugSnapshot();
            assertTrue((double) snap.get("occupancy") <= 15, "occupancy bounded for any pattern");
            assertEquals(0L, snap.get("recoverySnaps"));
            var entities = ClientCombatScene.receipt().entities();
            assertTrue(entities.containsKey(1) && entities.containsKey(2));
            double r = ClientCombatScene.receipt().time();
            assertEquals(0.28 * (r - 100), entities.get(1).x(), 1e-3);
        }
    }

    @Test
    void passengersPacketForUnknownVehicleSkipsTheSeatTraversal() {
        var mc = mock(Minecraft.class);
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        mc.level = level;
        var p = player(1);
        var vehicle = mock(Entity.class);
        when(vehicle.getUUID()).thenReturn(new UUID(0, 30));
        when(vehicle.getPassengers()).thenReturn(List.of(p));
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            // Unseated fast path: no seating to refresh, no state change.
            ClientCombatScene.linkageChanged();
            warm(now);
            ClientCombatScene.accept(
                    batch(108, 108, rec(1, 7, 1, 108, 7 * .28, 3, FULL_SEAT)), now + 400);
            when(p.getVehicle()).thenReturn(vehicle);
            ClientCombatScene.linkageChanged();
            ClientCombatScene.mainFrame(now + 400);
            assertTrue(ClientCombatScene.driven(p));
            // Dismount natively, then a burst for an unknown (respawn-removed) vehicle:
            // vanilla drops those packets before touching seating, so the stale seat
            // entry must survive the burst untouched.
            when(p.getVehicle()).thenReturn(null);
            for (int n = 0; n < 200; n++) ClientCombatScene.linkageChanged(424242);
            ClientCombatScene.mainFrame(now + 410);
            assertTrue(ClientCombatScene.driven(p));
            assertEquals(1, ClientCombatScene.receipt().entities().get(1).before().seat());
            // The same dismount through the full refresh is still detected.
            ClientCombatScene.linkageChanged();
            ClientCombatScene.mainFrame(now + 420);
            assertFalse(ClientCombatScene.driven(p));
        }
    }

    @Test
    void passengersPacketForKnownVehicleRefreshesSeatsAsBefore() {
        var mc = mock(Minecraft.class);
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        mc.level = level;
        var p = player(1);
        var vehicle = mock(Entity.class);
        when(vehicle.getUUID()).thenReturn(new UUID(0, 30));
        when(vehicle.getPassengers()).thenReturn(List.of(p));
        int vehicleId = 30;
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class);
                var wall = freeze(now)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            warm(now);
            ClientCombatScene.accept(
                    batch(108, 108, rec(1, 7, 1, 108, 7 * .28, 3, FULL_SEAT)), now + 400);
            // Mount natively while the vehicle is still unknown: vanilla drops that
            // packet before touching seating, so the overload must skip the refresh
            // without corrupting anything (the mount stays visible through the
            // seat full that already arrived, exactly like no packet at all).
            when(p.getVehicle()).thenReturn(vehicle);
            for (int n = 0; n < 5; n++) ClientCombatScene.linkageChanged(vehicleId);
            ClientCombatScene.mainFrame(now + 400);
            assertTrue(ClientCombatScene.driven(p));
            // The same id, now a known vehicle (entity arrived late): the overload
            // must forward to the full refresh, picking up the mount as before.
            when(level.getEntity(vehicleId)).thenReturn(vehicle);
            ClientCombatScene.linkageChanged(vehicleId);
            ClientCombatScene.mainFrame(now + 410);
            assertTrue(ClientCombatScene.driven(p));
            assertEquals(1, ClientCombatScene.receipt().entities().get(1).before().seat());
            // Dismount through the overload for the known vehicle: detected as before.
            when(p.getVehicle()).thenReturn(null);
            when(vehicle.getPassengers()).thenReturn(List.of());
            ClientCombatScene.linkageChanged(vehicleId);
            ClientCombatScene.mainFrame(now + 420);
            assertFalse(ClientCombatScene.driven(p));
            // Unseated fast path through the overload: nothing to refresh, state kept.
            ClientCombatScene.linkageChanged(vehicleId);
            ClientCombatScene.linkageChanged();
            ClientCombatScene.mainFrame(now + 430);
            assertFalse(ClientCombatScene.driven(p));
        }
    }
}
