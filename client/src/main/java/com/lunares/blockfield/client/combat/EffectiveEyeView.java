package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.EffectiveEye;
import com.lunares.blockfield.network.packet.SelfEyeS2CPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The local player's server-computed head offset in the body-yaw frame, keyframe-interpolated per
 * rendered frame. The server sends one yaw-local offset per END tick; each frame advances the blend
 * by frame time over one tick (50 ms), rotates it by the given current body yaw back into world
 * space, adds it to the vanilla camera position (client-predicted and partial-tick interpolated, so
 * walking stays smooth) and applies the same wall clamp as the server. Stale data (over a second
 * without a packet: join, disconnect, frameless server) falls back to the vanilla eye.
 */
public final class EffectiveEyeView {
    /** One server tick: the camera chase completes it in this long. */
    static final float CHASE_MS = 50;

    static final long STALE_MS = 1000;

    private static Vec3 prev, curr;
    private static long currTick = -1;
    private static float phase = 1;
    private static long lastFrameNs = -1;
    private static long lastPacketMs = -1;

    private EffectiveEyeView() {}

    /** Client thread packet intake (Fabric already delivers play packets there). */
    public static void onPacket(SelfEyeS2CPacket packet) {
        var anchor = new Vec3(packet.dx(), packet.dy(), packet.dz());
        if (packet.serverTick() <= currTick) return;
        prev = curr == null ? anchor : blended();
        curr = anchor;
        currTick = packet.serverTick();
        phase = 0;
        lastPacketMs = System.currentTimeMillis();
    }

    /**
     * Per-frame effective eye on top of {@code vanilla} (the vanilla camera position this frame),
     * or null without fresh data (caller keeps the vanilla camera). The blended body-local offset
     * is rotated by {@code yawDeg} (the caller's current, partial-tick interpolated body yaw) so
     * the camera tracks the head through turns. The view rotation is untouched: vanilla keeps the
     * player's look rotation.
     */
    public static Vec3 renderedEye(Player player, Vec3 vanilla, float yawDeg) {
        if (curr == null
                || lastPacketMs < 0
                || System.currentTimeMillis() - lastPacketMs > STALE_MS) return null;
        long now = System.nanoTime();
        if (lastFrameNs > 0) phase = advance(phase, (now - lastFrameNs) / 1_000_000f);
        lastFrameNs = now;
        Vec3 world = EffectiveEye.yawToWorld(yawDeg, blended());
        return EffectiveEye.clampToSight(player.level(), player, vanilla, vanilla.add(world));
    }

    public static void onDisconnect() {
        prev = null;
        curr = null;
        currTick = -1;
        phase = 1;
        lastFrameNs = -1;
        lastPacketMs = -1;
    }

    private static Vec3 blended() {
        return blend(prev, curr, phase);
    }

    static Vec3 blend(Vec3 a, Vec3 b, float t) {
        return EffectiveEye.lerp(a, b, t);
    }

    static float advance(float phase, float dtMs) {
        return Math.min(1, phase + dtMs / CHASE_MS);
    }

    /** Test seam: per-frame chase positions for a fixed keyframe pair, like {@link #blended}. */
    static java.util.List<Vec3> chase(Vec3 from, Vec3 to, float[] frameDtMs) {
        var out = new java.util.ArrayList<Vec3>();
        float p = 0;
        for (float dt : frameDtMs) {
            p = advance(p, dt);
            out.add(blend(from, to, p));
        }
        return out;
    }
}
