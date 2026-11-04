package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientCombatNetLogTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        ClientCombatScene.disconnect();
    }

    static net.minecraft.client.player.RemotePlayer player(int id) {
        var p = mock(net.minecraft.client.player.RemotePlayer.class);
        when(p.getId()).thenReturn(id);
        when(p.getUUID()).thenReturn(new java.util.UUID(0, id));
        return p;
    }

    static void warm(double now) {
        var identity = ExactFixtures.identity(1, 9, 5, 0, 0);
        for (int n = 0; n < 8; n++) {
            long tick = 100 + n;
            var checkpoint = ExactFixtures.checkpoint(identity, tick, n * .28);
            int flags =
                    n == 0
                            ? AnimationRecipe.SnapshotRecord.FLAG_FULL
                                    | AnimationRecipe.SnapshotRecord.FLAG_RESET
                            : 0;
            var record =
                    new AnimationRecipe.SnapshotRecord(
                            identity, flags, checkpoint.placement(), checkpoint);
            ClientCombatScene.accept(
                    PlayerSnapshotS2CPacket.animation(
                            ExactFixtures.STREAM,
                            n == 0 ? 0 : tick,
                            tick,
                            2,
                            6,
                            10,
                            500,
                            new AnimationRecipe.Batch(
                                    ExactFixtures.CONNECTION,
                                    ExactFixtures.SCENE,
                                    List.of(record),
                                    List.of(),
                                    List.of())),
                    now + n * 50);
            ClientCombatScene.mainFrame(now + n * 50);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void logCursorSurvivesSceneResetWithGeneration() {
        var p = player(1);
        when(p.getX()).thenReturn(1.5);
        when(p.getY()).thenReturn(64.0);
        when(p.getZ()).thenReturn(-2.5);
        var mc = mock(Minecraft.class);
        double now = ClientCombatScene.nowMs();
        try (var app = mockStatic(Minecraft.class)) {
            app.when(Minecraft::getInstance).thenReturn(mc);
            ClientCombatScene.add(p);
            warm(now);
            ClientCombatScene.clientTick(now + 350);
            assertTrue(ClientCombatScene.debugLog(0).isEmpty()); // arms the log
            ClientCombatScene.clientTick(now + 400);
            ClientCombatScene.clientTick(now + 450);
            ClientCombatScene.clientTick(now + 500);
            var entries = ClientCombatScene.debugLog(0);
            assertEquals(3, entries.size());
            var last = entries.get(2);
            assertEquals(3L, last.get("tick"));
            assertEquals(
                    List.of(1.5, 64.0, -2.5),
                    ((Map<Integer, Object>) last.get("positions")).get(1));
            assertTrue(((Number) last.get("R")).doubleValue() > 0);
            assertEquals(107L, ((Number) last.get("H")).longValue());
            assertEquals(1, ClientCombatScene.debugLog(2).size());
            assertTrue(ClientCombatScene.debugLog(3).isEmpty());
            ClientCombatScene.sceneReset();
            assertEquals(3, ClientCombatScene.debugLog(0).size());
            ClientCombatScene.clientTick(now + 550);
            var next = ClientCombatScene.debugLog(3);
            assertEquals(1, next.size());
            assertEquals(4L, next.get(0).get("tick"));
            assertEquals(1L, next.get(0).get("sceneGeneration"));
        }
    }

    @Test
    void ringKeepsOnlyTheLast6000Ticks() {
        double now = ClientCombatScene.nowMs();
        ClientCombatScene.debugLog(0);
        for (int i = 0; i < 6100; i++) ClientCombatScene.clientTick(now + i);
        var entries = ClientCombatScene.debugLog(0);
        assertEquals(6000, entries.size());
        assertEquals(101L, entries.get(0).get("tick"));
        assertEquals(6100L, entries.get(5999).get("tick"));
    }
}
