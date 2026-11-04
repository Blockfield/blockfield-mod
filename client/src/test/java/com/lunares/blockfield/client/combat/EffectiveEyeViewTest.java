package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.network.packet.SelfEyeS2CPacket;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Eye-sync client chase: monotonic and snap-free at 30/60/144 FPS, stale packets ignored. */
class EffectiveEyeViewTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void reset() {
        EffectiveEyeView.onDisconnect();
    }

    static float[] frames(float dtMs, float totalMs) {
        int n = Math.max(1, Math.round(totalMs / dtMs));
        var out = new float[n];
        for (int i = 0; i < n; i++) out[i] = dtMs;
        return out;
    }

    void assertChase(float dtMs) {
        Vec3 from = new Vec3(0, 65.62, 0);
        Vec3 to = new Vec3(0.6, 64.3, 0.6);
        double jump = from.distanceTo(to);
        var path = EffectiveEyeView.chase(from, to, frames(dtMs, 100));
        assertFalse(path.isEmpty());
        double prev = jump;
        double step = 0;
        for (Vec3 at : path) {
            double d = at.distanceTo(to);
            assertTrue(d <= prev + 1e-9, "monotonic at " + dtMs + "ms frames");
            step = Math.max(step, prev - d);
            prev = d;
        }
        // One tick (50 ms) of frames completes the chase; the first frame already moves.
        assertEquals(0, path.get(path.size() - 1).distanceTo(to), 1e-9);
        assertTrue(path.get(0).distanceTo(from) > 0, "first frame moves");
        assertTrue(
                step <= jump * dtMs / 50 + 1e-6, "max step " + step + " at " + dtMs + "ms frames");
        System.out.println("EYE-SYNC chase dt=" + dtMs + "ms maxStep=" + step + " jump=" + jump);
    }

    @Test
    void chaseIsMonotonicAndSnapFreeAt30Fps() {
        assertChase(1000f / 30);
    }

    @Test
    void chaseIsMonotonicAndSnapFreeAt60Fps() {
        assertChase(1000f / 60);
    }

    @Test
    void chaseIsMonotonicAndSnapFreeAt144Fps() {
        assertChase(1000f / 144);
    }

    @Test
    void stalePacketsAreIgnored() {
        var player = mock(Player.class);
        Vec3 vanilla = new Vec3(0, 65.62, 0);
        EffectiveEyeView.onPacket(new SelfEyeS2CPacket(5, 0, -0.5f, 0));
        EffectiveEyeView.onPacket(new SelfEyeS2CPacket(3, 0, -1.5f, 0));
        // No level on the mock: the clamp passes the anchor through.
        assertEquals(vanilla.add(0, -0.5, 0), EffectiveEyeView.renderedEye(player, vanilla, 0));
        // The offset follows the client's own camera, not a lagged server position.
        Vec3 moved = vanilla.add(3, 0, 0);
        assertEquals(moved.add(0, -0.5, 0), EffectiveEyeView.renderedEye(player, moved, 0));
    }

    @Test
    void localOffsetRotatesWithTheCurrentBodyYaw() {
        var player = mock(Player.class);
        Vec3 vanilla = new Vec3(0, 65.62, 0);
        // Head straight ahead in body space: +Z at yaw 0, -X at yaw 90
        // (packet floats round-trip, so compare with tolerance).
        EffectiveEyeView.onPacket(new SelfEyeS2CPacket(7, 0, -0.2f, 0.4f));
        Vec3 straight = EffectiveEyeView.renderedEye(player, vanilla, 0);
        assertEquals(0, straight.subtract(vanilla.add(0, -0.2, 0.4)).length(), 1e-6);
        EffectiveEyeView.onDisconnect();
        EffectiveEyeView.onPacket(new SelfEyeS2CPacket(7, 0, -0.2f, 0.4f));
        Vec3 turned = EffectiveEyeView.renderedEye(player, vanilla, 90);
        assertEquals(0, turned.subtract(vanilla.add(-0.4, -0.2, 0)).length(), 1e-6);
    }

    @Test
    void noPacketsKeepsVanilla() {
        var player = mock(Player.class);
        assertNull(EffectiveEyeView.renderedEye(player, Vec3.ZERO, 0));
    }
}
