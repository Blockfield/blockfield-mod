package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.common.combat.CombatView;
import com.lunares.blockfield.network.packet.AnimationWireC2SPacket;
import com.lunares.blockfield.network.packet.CombatViewC2SPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CombatAttackSenderTest {
    // Exact bf8/bf24/bf2 wire ids from r8 §3.2 table; not derived from the implementation set.
    static final List<String> IDS =
            List.of(
                    "tacz:client_player_shoot",
                    "tacz:client_player_melee",
                    "superbwarfare:shoot",
                    "superbwarfare:vehicle_fire",
                    "superbwarfare:melee_attack",
                    "superbwarfare:fire_key",
                    "superbwarfare:drone_fire",
                    "superbwarfare:artillery_indicator_fire",
                    "superbwarfare:lunge_mine_attack",
                    "lrtactical:melee_attack_request",
                    "lrtactical:prepare_melee_attack",
                    "blockfield:knife_aim",
                    "blockfield:grenade_throw",
                    "superbwarfare:interact");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    record Payload(Type<Payload> type) implements CustomPacketPayload {}

    static CombatView view(long sequence) {
        var a = new Time(97, 123);
        return new CombatView(
                sequence,
                17,
                100,
                97,
                123,
                new CombatView.Receipt(
                        3,
                        1,
                        0,
                        0,
                        a,
                        0,
                        0,
                        new AnimationResources.Digest(0, 0, 0, 0),
                        new AnimationStartReport(List.of())));
    }

    /** One complete ticket transaction (these receipts fit in a single fragment). */
    static CombatView ticketOf(AnimationWireC2SPacket fragment) {
        var transaction = new AnimationWire.Assembler(false).accept(fragment.fragment());
        assertNotNull(transaction);
        return ((CombatViewC2SPacket) AnimationWireC2SPacket.decode(transaction)).view();
    }

    static CustomPacketPayload payload(String id) {
        return new Payload(new CustomPacketPayload.Type<>(ResourceLocation.parse(id)));
    }

    @Test
    void everyPinnedAllowlistedIdTravelsThroughRealFabricBeforeItsAttack() {
        assertEquals(Set.copyOf(IDS), CombatAttackSender.CUSTOM_IDS);
        var mc = mock(Minecraft.class);
        var listener = mock(ClientPacketListener.class);
        var packets = new ArrayList<Packet<?>>();
        var sender = new CombatAttackSender();
        var seq = new AtomicLong();
        when(mc.getConnection()).thenReturn(listener);
        doAnswer(
                        call -> {
                            sender.send(
                                    call.getArgument(0),
                                    null,
                                    () -> view(seq.incrementAndGet()),
                                    packets::add);
                            return null;
                        })
                .when(listener)
                .send(any());
        // Fabric 4.3.0 ClientPlayNetworking.java:239-245: createC2SPacket then listener.send.
        try (var api = mockStatic(Minecraft.class)) {
            api.when(Minecraft::getInstance).thenReturn(mc);
            for (String id : IDS) ClientPlayNetworking.send(payload(id));
        }
        assertEquals(28, packets.size());
        for (int i = 0; i < IDS.size(); i++) {
            var wire = (ServerboundCustomPayloadPacket) packets.get(i * 2);
            assertTrue(wire.payload() instanceof AnimationWireC2SPacket);
            var ticket = ticketOf((AnimationWireC2SPacket) wire.payload());
            assertEquals(i + 1, ticket.sequence());
            assertEquals(97, ticket.tick());
            assertEquals(100, ticket.receivedSnapshotTick());
            assertEquals(
                    IDS.get(i),
                    ((ServerboundCustomPayloadPacket) packets.get(i * 2 + 1))
                            .payload()
                            .type()
                            .id()
                            .toString());
        }
    }

    @Test
    void fireFrameEyeRidesOnlyTheNextShotTicket() {
        var sender = new CombatAttackSender();
        var packets = new ArrayList<Packet<?>>();
        var eye = new net.minecraft.world.phys.Vec3(1.5, 65.62, -2.25);
        CombatAttackSender.fired(eye);
        CombatAttackSender.aimed(-31.25f, 4.5f);
        sender.send(
                new ServerboundCustomPayloadPacket(payload("tacz:client_player_melee")),
                null,
                () -> view(1),
                packets::add);
        for (int shot = 0; shot < 2; shot++)
            sender.send(
                    new ServerboundCustomPayloadPacket(payload("tacz:client_player_shoot")),
                    null,
                    () -> view(2),
                    packets::add);
        assertEquals(6, packets.size());
        var tickets = new ArrayList<CombatView>();
        for (int i = 0; i < 6; i += 2)
            tickets.add(
                    ticketOf(
                            (AnimationWireC2SPacket)
                                    ((ServerboundCustomPayloadPacket) packets.get(i)).payload()));
        assertNull(tickets.get(0).fireEye());
        assertEquals(eye, tickets.get(1).fireEye());
        assertEquals(new CombatView.Aim(-31.25f, 4.5f), tickets.get(1).fireAim());
        assertNull(tickets.get(2).fireEye());
        assertNull(tickets.get(2).fireAim());
    }

    @Test
    void rawInteractAttackOnlyAndUnknownVictimStillSend() {
        var sender = new CombatAttackSender();
        var packets = new ArrayList<Packet<?>>();
        var entity = mock(Entity.class);
        sender.send(
                ServerboundInteractPacket.createAttackPacket(entity, false),
                null,
                () -> view(1),
                packets::add);
        assertEquals(2, packets.size());
        assertFalse(
                sender.matches(
                        ServerboundInteractPacket.createInteractionPacket(
                                entity, false, InteractionHand.MAIN_HAND),
                        null));
        assertFalse(
                sender.matches(
                        ServerboundInteractPacket.createInteractionPacket(
                                entity, false, InteractionHand.MAIN_HAND, Vec3.ZERO),
                        null));
    }

    @Test
    void undrivenAttackSendsOnlyOriginalAndViewCannotReenter() {
        var sender = new CombatAttackSender();
        var packets = new ArrayList<Packet<?>>();
        var attack = ClientPlayNetworking.createC2SPacket(payload("superbwarfare:shoot"));
        sender.send(attack, null, () -> null, packets::add);
        assertEquals(List.of(attack), packets);
        assertFalse(
                sender.matches(
                        new ServerboundCustomPayloadPacket(payload("blockfield:unrelated")), null));
    }

    @Test
    void eatingAndBuildingDoNotRefreshAndReleaseRequiresThrowableUse() {
        var sender = new CombatAttackSender();
        var p = mock(LocalPlayer.class);
        when(p.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(new ItemStack(Items.APPLE));
        assertFalse(
                sender.matches(
                        new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0, 0), p));
        when(p.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(new ItemStack(Items.STONE));
        assertFalse(
                sender.matches(
                        new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false),
                                1),
                        p));
        assertFalse(
                sender.matches(
                        new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                                BlockPos.ZERO,
                                Direction.DOWN),
                        p));
    }

    @Test
    void qualifiedCookReleaseRefreshesOnceAndUnrelatedUseClearsContext() {
        var sender = new CombatAttackSender();
        var player = mock(LocalPlayer.class);
        var stack = new ItemStack(Items.SNOWBALL);
        when(player.isUsingItem()).thenReturn(true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(stack);
        var release =
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                        BlockPos.ZERO,
                        Direction.DOWN);
        try (var support =
                mockStatic(com.lunares.blockfield.common.grenade.GrenadeThrowSupport.class)) {
            support.when(
                            () ->
                                    com.lunares.blockfield.common.grenade.GrenadeThrowSupport.kind(
                                            stack))
                    .thenReturn(com.lunares.blockfield.common.grenade.GrenadeThrowSupport.Kind.LR);
            assertTrue(
                    sender.matches(
                            new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0, 0),
                            player));
            assertTrue(sender.matches(release, player));
            assertFalse(sender.matches(release, player));
            assertTrue(
                    sender.matches(
                            new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 2, 0, 0),
                            player));
            when(player.getItemInHand(InteractionHand.MAIN_HAND))
                    .thenReturn(new ItemStack(Items.APPLE));
            assertFalse(
                    sender.matches(
                            new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 3, 0, 0),
                            player));
            assertFalse(sender.matches(release, player));
        }
    }

    @Test
    void receiveHAfterRenderDoesNotChangeTicketsAndDuplicateRIncrementsSequence() {
        var sender = new CombatAttackSender();
        var seq = new AtomicLong();
        var r =
                new CombatRenderReceipt(
                        17,
                        100,
                        97,
                        123,
                        java.util.Map.of(),
                        new CombatRenderReceipt.Exact(
                                3,
                                1,
                                0,
                                0,
                                new Time(97, 123),
                                0,
                                0,
                                new AnimationResources.Digest(0, 0, 0, 0),
                                new AnimationStartReport(List.of()),
                                null));
        var packets = new ArrayList<Packet<?>>();
        var attack = ClientPlayNetworking.createC2SPacket(payload("superbwarfare:shoot"));
        sender.send(attack, null, () -> r.ticket(seq.incrementAndGet()), packets::add);
        sender.send(attack, null, () -> r.ticket(seq.incrementAndGet()), packets::add);
        assertEquals(4, packets.size());
        for (int i : new int[] {0, 2}) {
            var wire = (ServerboundCustomPayloadPacket) packets.get(i);
            assertTrue(wire.payload() instanceof AnimationWireC2SPacket);
            assertSame(attack, packets.get(i + 1));
        }
        var a = r.ticket(1);
        var b = r.ticket(2);
        assertEquals(a.tick(), b.tick());
        assertEquals(a.alpha(), b.alpha());
        assertEquals(100, b.receivedSnapshotTick());
        assertEquals(2, b.sequence());
    }

    // TaCZ bf8 LocalPlayerShoot.lambda$doShoot$2 sends from LocalPlayerDataHolder's scheduler
    // while the client thread sends its own packets through the same listener.
    @Test
    void concurrentShooterThreadKeepsEveryTicketAdjacentToItsAttack() throws Exception {
        var sender = new CombatAttackSender();
        var out = java.util.Collections.synchronizedList(new ArrayList<Packet<?>>());
        var seq = new AtomicLong();
        var attack = ClientPlayNetworking.createC2SPacket(payload("tacz:client_player_shoot"));
        var move =
                new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly(
                        true);
        Runnable shooter =
                () -> {
                    for (int i = 0; i < 2000; i++)
                        sender.send(attack, null, () -> view(seq.incrementAndGet()), out::add);
                };
        Runnable client =
                () -> {
                    for (int i = 0; i < 4000; i++) sender.send(move, null, () -> null, out::add);
                };
        var threads = List.of(new Thread(shooter), new Thread(shooter), new Thread(client));
        threads.forEach(Thread::start);
        for (var t : threads) t.join(30_000);
        assertEquals(12_000, out.size());
        var assembler = new AnimationWire.Assembler(false);
        var sequences = new java.util.HashSet<Long>();
        for (int i = 0; i < out.size(); i++) {
            if (!(out.get(i) instanceof ServerboundCustomPayloadPacket custom
                    && custom.payload() instanceof AnimationWireC2SPacket wire)) continue;
            assertSame(attack, out.get(i + 1));
            // One sender call emits its ticket's fragments atomically, so each wire packet
            // here completes a single-fragment ticket transaction on a fresh assembler.
            var transaction = assembler.accept(wire.fragment());
            assertNotNull(transaction);
            var ticket = ((CombatViewC2SPacket) AnimationWireC2SPacket.decode(transaction)).view();
            assertTrue(sequences.add(ticket.sequence()));
        }
        assertEquals(4000, sequences.size());
    }
}
