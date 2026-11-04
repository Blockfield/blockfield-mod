package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.render.WeaponHud;
import com.lunares.blockfield.network.packet.WorkshopAiLibraryC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiRequestC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class WorkshopToolsTickTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void sketchRotateKeyTurnsTheHandOncePerClick() throws Exception {
        try (var fixture = new Fixture()) {
            assertFalse(WorkshopSketchPlacement.active());
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            fixture.queueRotate();
            fixture.tick();
            assertEquals(1, WorkshopSketchPlacement.rotation());
            fixture.tick();
            assertEquals(1, WorkshopSketchPlacement.rotation());
            verify(fixture.player, never()).displayClientMessage(any(), eq(true));
        }
    }

    @Test
    void completedStampKeepsApplyAndUndoOnTheBaseId() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.stage("preview");
            var baseState = WorkshopAiClient.state();
            var completedStamp =
                    new WorkshopAiStateS2CPacket(
                            fixture.owner,
                            UUID.randomUUID(),
                            "complete",
                            "Copy complete",
                            "",
                            BlockPos.ZERO,
                            BlockPos.ZERO,
                            3,
                            3,
                            true,
                            false);
            WorkshopAiClient.accept(completedStamp, fixture.owner, ignored -> {});
            assertSame(baseState, WorkshopAiClient.state());
            fixture.stage("complete");
            var completedBase = WorkshopAiClient.state();
            WorkshopAiClient.accept(completedStamp, fixture.owner, ignored -> {});
            assertSame(completedBase, WorkshopAiClient.state());
            WorkshopAiClient.availability(true);
            List<WorkshopAiRequestC2SPacket> sent = new ArrayList<>();
            fixture.networking
                    .when(() -> ClientPlayNetworking.send(any(WorkshopAiRequestC2SPacket.class)))
                    .thenAnswer(
                            call -> {
                                sent.add(call.getArgument(0));
                                return null;
                            });
            for (String action : new String[] {"apply", "undo"}) {
                setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
                assertTrue(WorkshopAiClient.request(action, ""));
                assertEquals(fixture.base, sent.getLast().jobId());
                assertEquals(action, sent.getLast().action());
            }
        }
    }

    @Test
    void wandUsesClickedCoordinatesOncePerPhysicalPress() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.player.getMainHandItem())
                    .thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            setStatic(WorkshopToolsClient.class, "selectionMode", true);
            var connection = mock(ClientPacketListener.class);
            when(fixture.client.getConnection()).thenReturn(connection);
            WorkshopWandSelection.cancelSelect();
            BlockPos first = new BlockPos(12, 64, -7), second = new BlockPos(-3, 70, 25);
            assertTrue(WorkshopToolsClient.useWand(fixture.client, first));
            WorkshopToolsClient.releaseUse(true);
            assertTrue(WorkshopToolsClient.useWand(fixture.client, second));
            assertEquals(
                    WorkshopWandSelection.WandPhase.SELECTING, WorkshopWandSelection.wandPhase());
            verify(connection, times(1)).sendCommand("/pos1 12,64,-7");
            verifyNoMoreInteractions(connection);
            WorkshopToolsClient.releaseUse(false);
            assertTrue(WorkshopToolsClient.useWand(fixture.client, second));
            verify(connection).sendCommand("/pos2 -3,70,25");
            assertArrayEquals(new int[] {-3, 64, -7, 12, 70, 25}, WorkshopWandSelection.pending());
            WorkshopToolsClient.releaseUse(false);
            assertTrue(WorkshopToolsClient.useWand(fixture.client, null));
            verifyNoMoreInteractions(connection);
            WorkshopWandSelection.cancelSelect();
        }
    }

    @Test
    void levelChangeReleasesAnAwaitingHand() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            setStatic(WorkshopSketchPlacement.class, "anchor", new BlockPos(20, 70, 30));
            assertTrue(WorkshopSketchPlacement.place());
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            // The server forgets a job on a scope change without any state packet.
            setStatic(WorkshopSketchPlacement.class, "lastLevel", new Object());
            var tick = WorkshopSketchPlacement.class.getDeclaredMethod("tick", Minecraft.class);
            tick.setAccessible(true);
            tick.invoke(null, fixture.client);
            assertFalse(WorkshopSketchPlacement.active());
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
        }
    }

    @Test
    void libraryPlacementWaitsThenBuildsAndCancelReturnsTheSketchToHand() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            setStatic(WorkshopSketchPlacement.class, "anchor", new BlockPos(20, 70, 30));
            assertTrue(WorkshopSketchPlacement.place());
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            assertEquals("awaiting", WorkshopToolsClient.panelKind("idle", true, false));
            assertTrue(WorkshopSketchPlacement.use());
            WorkshopSketchPlacement.attack();
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            fixture.networking.verify(
                    () -> ClientPlayNetworking.send(any(WorkshopAiRequestC2SPacket.class)),
                    never());
            fixture.networking.verify(
                    () -> ClientPlayNetworking.send(any(WorkshopAiLibraryC2SPacket.class)),
                    times(1));
            UUID placed = UUID.randomUUID();
            fixture.job(placed, "planning");
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            fixture.job(placed, "preview");
            assertEquals(placed, WorkshopAiClient.state().jobId());
            assertEquals(WorkshopSketchPlacement.Phase.FIXED, WorkshopSketchPlacement.phase());
            assertEquals("fixed", WorkshopToolsClient.panelKind("preview", true, false));
            assertFalse(WorkshopSketchPlacement.held());
            assertNull(WorkshopSketchPlacement.anchor());
            int rotation = WorkshopSketchPlacement.rotation();
            WorkshopSketchPlacement.rotate();
            assertEquals(rotation, WorkshopSketchPlacement.rotation());
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            assertTrue(WorkshopSketchPlacement.use());
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    new WorkshopAiRequestC2SPacket("apply", placed, "")));
            WorkshopSketchPlacement.attack();
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    new WorkshopAiRequestC2SPacket("cancel", placed, "")));
            assertTrue(WorkshopSketchPlacement.held());
            fixture.job(placed, "cancelled");
            assertTrue(WorkshopSketchPlacement.held());
        }
    }

    @Test
    void pickedPreviewUsesSourceSidesAndTransformAndSameJobAcknowledgement() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("preview");
            assertTrue(WorkshopSketchPlacement.pickupPreview(fixture.owner, fixture.base));
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    new WorkshopAiLibraryC2SPacket(
                                            "take",
                                            WorkshopAiRequestC2SPacket.ZERO_ID,
                                            fixture.base,
                                            "",
                                            0,
                                            0,
                                            0,
                                            BlockPos.ZERO,
                                            0,
                                            false)));
            assertFalse(WorkshopSketchPlacement.active());
            WorkshopSketchPlacement.accept(
                    new WorkshopAiSketchPlanS2CPacket(
                            fixture.base,
                            "preview",
                            16,
                            8,
                            23,
                            "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,0,1],\"block\":\"minecraft:stone\",\"hollow\":false}]}",
                            3,
                            true));
            var hand = WorkshopSketchPlacement.hand();
            assertEquals(fixture.base, hand.jobId());
            assertEquals(WorkshopAiRequestC2SPacket.ZERO_ID, hand.sketchId());
            assertEquals(16, hand.sx());
            assertEquals(23, hand.sz());
            assertEquals(3, hand.rotY());
            assertTrue(hand.mirror());
            setStatic(WorkshopSketchPlacement.class, "anchor", new BlockPos(20, 70, 30));
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            assertTrue(WorkshopSketchPlacement.place());
            fixture.stage("preview"); // refusal, not acknowledgement of a re-snapshot
            assertTrue(WorkshopSketchPlacement.held());
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            assertTrue(WorkshopSketchPlacement.place());
            fixture.stage("planning");
            fixture.stage("preview");
            assertEquals(WorkshopSketchPlacement.Phase.FIXED, WorkshopSketchPlacement.phase());
            WorkshopSketchPlacement.attack();
            assertFalse(WorkshopSketchPlacement.active());
        }
    }

    @Test
    void refusedLibraryPlacementReturnsToHeldAndApplyingClearsFixedHand() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            setStatic(WorkshopSketchPlacement.class, "anchor", BlockPos.ZERO);
            assertTrue(WorkshopSketchPlacement.place());
            fixture.stage("idle");
            assertTrue(WorkshopSketchPlacement.held());
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            assertTrue(WorkshopSketchPlacement.place());
            UUID placed = UUID.randomUUID();
            fixture.job(placed, "preview");
            assertEquals(WorkshopSketchPlacement.Phase.FIXED, WorkshopSketchPlacement.phase());
            fixture.job(placed, "applying");
            assertFalse(WorkshopSketchPlacement.active());
            assertEquals("progress", WorkshopToolsClient.panelKind("applying", false, false));
        }
    }

    @Test
    void busyBuildKeepsAwaitingHandUntilStartedAndLmbLeavesTheQueue() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            setStatic(WorkshopSketchPlacement.class, "anchor", BlockPos.ZERO);
            assertTrue(WorkshopSketchPlacement.place());
            UUID placed = UUID.randomUUID();
            fixture.job(placed, "planning");
            fixture.job(placed, "preview");
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            assertTrue(WorkshopSketchPlacement.use());
            fixture.job(placed, "awaiting");
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            assertFalse(WorkshopSketchPlacement.recall());
            WorkshopSketchPlacement.attack();
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    new WorkshopAiRequestC2SPacket("cancel", placed, "")));
            assertTrue(WorkshopSketchPlacement.held());
        }
    }

    @Test
    void queuedBuildResnapshotKeepsHandAndApplyingReleasesIt() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            setStatic(WorkshopSketchPlacement.class, "anchor", BlockPos.ZERO);
            assertTrue(WorkshopSketchPlacement.place());
            UUID placed = UUID.randomUUID();
            fixture.job(placed, "preview");
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            assertTrue(WorkshopSketchPlacement.use());
            fixture.job(placed, "awaiting");
            fixture.job(placed, "planning");
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            fixture.job(placed, "applying");
            assertFalse(WorkshopSketchPlacement.active());
        }
    }

    @Test
    void awaitingRecallSurvivesLatePlanningAndPreviewAndPlainUseReplacesInsteadOfBuilding()
            throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            var rotated = fixture.hand().rotated().heightened(3);
            setStatic(WorkshopSketchPlacement.class, "hand", rotated);
            setStatic(WorkshopSketchPlacement.class, "anchor", BlockPos.ZERO);
            assertTrue(WorkshopSketchPlacement.place());
            // Recall is accepted even inside the placement debounce.
            assertTrue(WorkshopSketchPlacement.sendRecall());
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
            assertEquals("hand", WorkshopToolsClient.panelKind("planning", true, false));
            UUID placed = UUID.randomUUID();
            fixture.job(placed, "planning");
            fixture.job(placed, "preview");
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
            assertEquals(placed, WorkshopSketchPlacement.hand().jobId());
            assertEquals(1, WorkshopSketchPlacement.hand().rotY());
            assertEquals(3, WorkshopSketchPlacement.hand().dy());
            fixture.job(placed, "held");
            WorkshopSketchPlacement.accept(
                    new WorkshopAiSketchPlanS2CPacket(
                            placed,
                            "late source",
                            2,
                            1,
                            1,
                            "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,0,0],\"block\":\"minecraft:water\",\"hollow\":false}]}",
                            0,
                            false));
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
            assertEquals(1, WorkshopSketchPlacement.hand().rotY());
            assertEquals(3, WorkshopSketchPlacement.hand().dy());
            assertTrue(WorkshopSketchPlacement.hand().blueprint().ghosts().getFirst().water());
            assertTrue(WorkshopSketchPlacement.use());
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    argThat(
                                            packet ->
                                                    packet instanceof WorkshopAiLibraryC2SPacket p
                                                            && p.action().equals("place")
                                                            && p.jobId().equals(placed)
                                                            && p.rotY() == 1)));
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    argThat(
                                            packet ->
                                                    packet instanceof WorkshopAiRequestC2SPacket p
                                                            && p.action().equals("apply"))),
                    never());
            fixture.job(placed, "planning");
            fixture.job(placed, "preview");
            assertEquals(WorkshopSketchPlacement.Phase.FIXED, WorkshopSketchPlacement.phase());
        }
    }

    @Test
    void oldServerNeverReceivesAnUnknownRecallAction() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            fixture.networking
                    .when(
                            () ->
                                    ClientPlayNetworking.canSend(
                                            com.lunares.blockfield.network.packet
                                                    .WorkshopAiV3S2CPacket.TYPE))
                    .thenReturn(false);
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            WorkshopSketchPlacement.awaiting(fixture.base);
            assertFalse(WorkshopSketchPlacement.sendRecall());
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            fixture.networking.verify(() -> ClientPlayNetworking.send(any()), never());
        }
    }

    @Test
    void droppingRecalledAwaitingSketchCancelsItsExactLateJobAndNeverResurrectsTheHand()
            throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("idle");
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            setStatic(WorkshopSketchPlacement.class, "anchor", BlockPos.ZERO);
            assertTrue(WorkshopSketchPlacement.place());
            assertTrue(WorkshopSketchPlacement.sendRecall());
            WorkshopSketchPlacement.attack();
            assertFalse(WorkshopSketchPlacement.active());
            UUID placed = UUID.randomUUID();
            fixture.job(placed, "planning");
            fixture.job(placed, "held");
            assertFalse(WorkshopSketchPlacement.active());
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    argThat(
                                            packet ->
                                                    packet instanceof WorkshopAiRequestC2SPacket p
                                                            && p.action().equals("cancel")
                                                            && p.jobId().equals(placed))));
            WorkshopSketchPlacement.accept(
                    new WorkshopAiSketchPlanS2CPacket(
                            placed,
                            "late",
                            1,
                            1,
                            1,
                            "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\"minecraft:stone\",\"hollow\":false}]}"));
            assertFalse(WorkshopSketchPlacement.active());
        }
    }

    @Test
    void heldSourceReplyRecoversALiveJobWithoutTreatingItAsALibraryId() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.enableLibrary();
            fixture.stage("held");
            WorkshopSketchPlacement.accept(
                    new WorkshopAiSketchPlanS2CPacket(
                            fixture.base,
                            "source",
                            2,
                            1,
                            1,
                            "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,0,0],\"block\":\"minecraft:stone\",\"hollow\":false}]}",
                            3,
                            true));
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
            assertEquals(fixture.base, WorkshopSketchPlacement.hand().jobId());
            assertEquals(
                    WorkshopAiRequestC2SPacket.ZERO_ID, WorkshopSketchPlacement.hand().sketchId());
            assertEquals(3, WorkshopSketchPlacement.hand().rotY());
            assertTrue(WorkshopSketchPlacement.hand().mirror());
            setStatic(WorkshopSketchPlacement.class, "anchor", BlockPos.ZERO);
            assertTrue(WorkshopSketchPlacement.use());
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    argThat(
                                            packet ->
                                                    packet instanceof WorkshopAiLibraryC2SPacket p
                                                            && p.action().equals("place")
                                                            && p.jobId().equals(fixture.base))));
        }
    }

    @Test
    void slowTicksDoNotHideFreshShiftInputOrKeepTheReleasedUseLatch() throws Exception {
        try (var fixture = new Fixture();
                var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            fixture.enableLibrary();
            fixture.stage("idle");
            var options = mock(net.minecraft.client.Options.class);
            var shift = mock(KeyMapping.class);
            when(shift.isDown()).thenReturn(true);
            var shiftField = net.minecraft.client.Options.class.getDeclaredField("keyShift");
            shiftField.setAccessible(true);
            shiftField.set(options, shift);
            var optionsField = Minecraft.class.getDeclaredField("options");
            optionsField.setAccessible(true);
            optionsField.set(fixture.client, options);
            assertFalse(fixture.player.isShiftKeyDown());
            setStatic(WorkshopSketchPlacement.class, "hand", fixture.hand());
            WorkshopSketchPlacement.awaiting(fixture.base);
            setStatic(WorkshopToolsClient.class, "used", true);
            WorkshopToolsClient.releaseUse(false);
            var use = WorkshopToolsClient.class.getDeclaredMethod("useAi", Minecraft.class);
            use.setAccessible(true);
            use.invoke(null, fixture.client);
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
            fixture.networking.verify(
                    () ->
                            ClientPlayNetworking.send(
                                    argThat(
                                            packet ->
                                                    packet instanceof WorkshopAiLibraryC2SPacket p
                                                            && p.action().equals("recall"))));
        }
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static final class Fixture implements AutoCloseable {
        final UUID owner = UUID.randomUUID(), base = UUID.randomUUID();
        final Minecraft client = mock(Minecraft.class);
        final LocalPlayer player = mock(LocalPlayer.class);
        final MockedStatic<WeaponHud> hud = mockStatic(WeaponHud.class);
        final MockedStatic<ClientPlayNetworking> networking =
                mockStatic(ClientPlayNetworking.class);
        final java.lang.reflect.Field clicks;

        Fixture() throws Exception {
            client.player = player;
            when(player.getUUID()).thenReturn(owner);
            when(player.getLookAngle()).thenReturn(new net.minecraft.world.phys.Vec3(0, 0, 1));
            when(player.getMainHandItem()).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            hud.when(WeaponHud::inWorkshop).thenReturn(true);
            networking
                    .when(() -> ClientPlayNetworking.canSend(WorkshopAiRequestC2SPacket.TYPE))
                    .thenReturn(true);
            WorkshopAiClient.availability(false);
            WorkshopAiEffects.clear();
            WorkshopSketchPlacement.clear();
            WorkshopToolsClient.resetWheelSession();
            setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
            while (WorkshopToolsClient.SKETCH_ROTATE_KEY.consumeClick()) {}
            clicks = KeyMapping.class.getDeclaredField("clickCount");
            clicks.setAccessible(true);
        }

        WorkshopSketchPlacement.Hand hand() {
            var blueprint =
                    WorkshopBlueprint.parse(
                            "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,0,0],"
                                    + "\"block\":\"minecraft:stone\",\"hollow\":false}]}",
                            0,
                            0,
                            0);
            return new WorkshopSketchPlacement.Hand(
                    UUID.randomUUID(),
                    WorkshopAiRequestC2SPacket.ZERO_ID,
                    "bar",
                    blueprint,
                    2,
                    1,
                    1,
                    0,
                    false,
                    0);
        }

        void enableLibrary() {
            WorkshopAiClient.availability(true);
            networking
                    .when(
                            () ->
                                    ClientPlayNetworking.canSend(
                                            com.lunares.blockfield.network.packet
                                                    .WorkshopAiV3S2CPacket.TYPE))
                    .thenReturn(true);
            networking
                    .when(() -> ClientPlayNetworking.canSend(WorkshopAiLibraryC2SPacket.TYPE))
                    .thenReturn(true);
        }

        void stage(String stage) {
            job(base, stage);
        }

        void job(UUID id, String stage) {
            WorkshopAiClient.accept(
                    new WorkshopAiStateS2CPacket(
                            owner,
                            id,
                            stage,
                            "",
                            "",
                            BlockPos.ZERO,
                            BlockPos.ZERO,
                            1,
                            0,
                            false,
                            false),
                    owner,
                    ignored -> {});
        }

        void queueRotate() throws Exception {
            // Vanilla's key map omits Fabric's duplicate-key mixin in these JVM tests.
            clicks.setInt(WorkshopToolsClient.SKETCH_ROTATE_KEY, 1);
        }

        void tick() throws Exception {
            var method = WorkshopToolsClient.class.getDeclaredMethod("tick", Minecraft.class);
            method.setAccessible(true);
            method.invoke(null, client);
        }

        @Override
        public void close() throws Exception {
            try {
                setStatic(WorkshopSketchPlacement.class, "hand", null);
                setStatic(WorkshopSketchPlacement.class, "anchor", null);
                setStatic(WorkshopAiClient.class, "lastRequest", Long.MIN_VALUE);
                while (WorkshopToolsClient.SKETCH_ROTATE_KEY.consumeClick()) {}
                WorkshopToolsClient.resetWheelSession();
                WorkshopAiClient.availability(false);
                WorkshopAiEffects.clear();
                WorkshopSketchPlacement.clear();
            } finally {
                networking.close();
                hud.close();
            }
        }
    }
}
