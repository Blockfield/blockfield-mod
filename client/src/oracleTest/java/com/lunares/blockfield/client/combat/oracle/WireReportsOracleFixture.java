package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.combat.*;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.network.packet.*;
import com.lunares.blockfield.oracle.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;

/** Actual transformed listener send wrapper and duck, with an isolated mocked socket sink. */
class WireReportsOracleFixture {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void appliedAndTaczThreadUseTheSameTransformedConnectionMonitor() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "wire-reports-adjacency",
                () -> {
                    var listener = mock(ClientPacketListener.class, CALLS_REAL_METHODS);
                    var socket = mock(Connection.class);
                    var connection =
                            OracleReflection.field(
                                    ClientCommonPacketListenerImpl.class,
                                    "connection",
                                    "Lnet/minecraft/network/Connection;");
                    connection.setAccessible(true);
                    connection.set(listener, socket);
                    var field =
                            ClientCommonPacketListenerImpl.class.getDeclaredField(
                                    "blockfield$combat");
                    field.setAccessible(true);
                    var sender = new CombatAttackSender();
                    field.set(listener, sender);
                    assertSame(sender, ((CombatViewSender) listener).blockfield$combatSender());
                    var sent = Collections.synchronizedList(new ArrayList<Packet<?>>());
                    var first = new CountDownLatch(1);
                    var racing = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    doAnswer(
                                    call -> {
                                        sent.add(call.getArgument(0));
                                        if (sent.size() == 1) {
                                            first.countDown();
                                            assertTrue(release.await(10, TimeUnit.SECONDS));
                                        }
                                        return null;
                                    })
                            .when(socket)
                            .send(any(Packet.class));
                    var view = WireReportVectors.view(1, 400);
                    var applied = new AnimationAppliedC2SPacket(view);
                    int count = applied.fragments().size();
                    assertTrue(count > 1);
                    var attack =
                            new ServerboundCustomPayloadPacket(
                                    new com.tacz.guns.network.message.ClientMessagePlayerShoot(
                                            0, 0));
                    // The native TaCZ packet type is pinned; its constructor/codec remain
                    // production code.
                    assertEquals(
                            ResourceLocation.parse("tacz:client_player_shoot"),
                            attack.payload().type().id());
                    var pool = Executors.newFixedThreadPool(2);
                    var mc = mock(Minecraft.class);
                    try (var api = mockStatic(Minecraft.class)) {
                        api.when(Minecraft::getInstance).thenReturn(mc);
                        var report =
                                pool.submit(
                                        () -> {
                                            // This callback reenters the transformed wrapper as the
                                            // real scene does.
                                            // Mockito's static scope is thread-local, so supply it
                                            // on this sending thread.
                                            try (var local = mockStatic(Minecraft.class)) {
                                                local.when(Minecraft::getInstance).thenReturn(mc);
                                                ((CombatViewSender) listener)
                                                        .blockfield$combatSender()
                                                        .sendApplied(applied, listener::send);
                                            }
                                        });
                        assertTrue(first.await(10, TimeUnit.SECONDS));
                        var shot =
                                pool.submit(
                                        () -> {
                                            racing.countDown();
                                            sender.send(attack, null, () -> view, sent::add);
                                        });
                        assertTrue(racing.await(10, TimeUnit.SECONDS));
                        release.countDown();
                        report.get(15, TimeUnit.SECONDS);
                        shot.get(15, TimeUnit.SECONDS);
                    } finally {
                        release.countDown();
                        pool.shutdownNow();
                        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
                    }
                    int ticketCount = new CombatViewC2SPacket(view).fragments().size();
                    assertEquals(count + ticketCount + 1, sent.size());
                    for (int i = 0; i < count + ticketCount; i++) {
                        var wire =
                                (AnimationWireC2SPacket)
                                        ((ServerboundCustomPayloadPacket) sent.get(i)).payload();
                        assertEquals(
                                i < count
                                        ? AnimationWire.Family.APPLIED
                                        : AnimationWire.Family.TICKET,
                                wire.fragment().header().family());
                        assertEquals(i < count ? i : i - count, wire.fragment().index());
                    }
                    assertSame(attack, sent.getLast());
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of(
                            "appliedFragments",
                            count,
                            "ticketFragments",
                            ticketCount,
                            "nativeAttackId",
                            attack.payload().type().id().toString(),
                            "transformedDuckAndReentry",
                            true);
                });
    }
}
