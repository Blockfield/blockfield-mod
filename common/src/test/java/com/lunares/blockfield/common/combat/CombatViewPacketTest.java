package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.network.packet.CombatViewC2SPacket;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Exact ticket C2S codec: only the generic checks live here (constructor bounds and the payload
 * cap). Layout, round trip, truncation, trailing bytes and version rejection are covered by {@code
 * AnimationPacketTest}; non-canonical uvarlong rejection by {@code SnapshotWireMathTest}.
 */
class CombatViewPacketTest {
    private static byte[] encode(CombatViewC2SPacket packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CombatViewC2SPacket.ANIMATION_CODEC.encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static CombatViewC2SPacket decode(byte[] bytes) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return CombatViewC2SPacket.ANIMATION_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static CombatViewC2SPacket ticket(long h, long tick, int alpha) {
        var receipt =
                new CombatView.Receipt(
                        2,
                        3,
                        8,
                        2,
                        new Time(tick, alpha),
                        0,
                        0,
                        DIGEST,
                        new AnimationStartReport(List.of()));
        return new CombatViewC2SPacket(new CombatView(1, 1, h, tick, alpha, receipt));
    }

    @Test
    void absoluteTicksAndAlphaEdges() {
        long bigH = (1L << 40) + 9;
        long bigTick = (1L << 40) + 7;
        assertEquals(bigH, decode(encode(ticket(bigH, bigTick, 0))).receivedSnapshotTick());
        assertEquals(bigTick, decode(encode(ticket(bigH, bigTick, 0))).tick());
        assertEquals(0, decode(encode(ticket(5, 5, 0))).alpha());
        assertEquals(65535, decode(encode(ticket(6, 5, 65535))).alpha());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatView(0, 0, 0, 0, 65536, receipt(List.of()).animation()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatView(0, 0, 0, 0, -1, receipt(List.of()).animation()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatView(-1, 0, 0, 0, 0, receipt(List.of()).animation()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatView(0, 0, 0, -2, 0, receipt(List.of()).animation()));
        assertThrows(
                NullPointerException.class,
                () -> new CombatViewC2SPacket(1, 1, 103, 98, 0, null, null, null));
    }

    @Test
    void aTicketWithoutVictimRowsRoundTripsBehindItsPresenceByte() {
        var full = ticket(5, 5, 0);
        var a = full.animation();
        var bare =
                new CombatViewC2SPacket(
                        new CombatView(
                                1,
                                1,
                                5,
                                5,
                                0,
                                new CombatView.Receipt(
                                        a.connectionId(),
                                        a.sceneGeneration(),
                                        a.frameId(),
                                        a.presentationRevision(),
                                        a.a(),
                                        a.resourceGeneration(),
                                        a.policyGeneration(),
                                        a.compatibilityDigest(),
                                        null)));
        byte[] bytes = encode(bare);
        assertNull(decode(bytes).animation().starts());
        assertEquals(bare, decode(bytes));
        assertEquals(full, decode(encode(full)));
        // The presence byte is the last receipt byte of a row-less ticket: only 0 or 1.
        byte[] forged = bytes.clone();
        forged[forged.length - 1] = 2;
        assertThrows(IllegalArgumentException.class, () -> decode(forged));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new com.lunares.blockfield.network.packet.AnimationAppliedC2SPacket(
                                bare.view()));
    }

    @Test
    void fireEyeRoundTripsAndOldTicketsStillDecode() {
        var old = ticket(5, 5, 0);
        byte[] oldBytes = encode(old);
        var eye = new net.minecraft.world.phys.Vec3(1.25, 65.62, -3e6 - 0.0625);
        byte[] withEye = encode(new CombatViewC2SPacket(old.view().withFireEye(eye)));
        // The eye is a pure 24-byte tail: bytes without it are unchanged and still decode.
        assertArrayEquals(oldBytes, Arrays.copyOf(withEye, oldBytes.length));
        assertEquals(oldBytes.length + 24, withEye.length);
        assertNull(decode(oldBytes).fireEye());
        assertEquals(old, decode(oldBytes));
        assertEquals(eye, decode(withEye).fireEye());
        assertEquals(old.view().withFireEye(eye), decode(withEye).view());
        // Any other tail length and non-finite eyes are rejected.
        for (int cut = 1; cut < 24; cut++) {
            byte[] partial = Arrays.copyOf(withEye, withEye.length - cut);
            assertThrows(IllegalArgumentException.class, () -> decode(partial));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> decode(Arrays.copyOf(withEye, withEye.length + 1)));
        byte[] nan = withEye.clone();
        long bits = Double.doubleToRawLongBits(Double.NaN);
        for (int i = 0; i < 8; i++) nan[oldBytes.length + i] = (byte) (bits >>> (56 - 8 * i));
        assertThrows(IllegalArgumentException.class, () -> decode(nan));
    }

    @Test
    void fireAimFollowsTheEyeAsAnEightByteTail() {
        var old = ticket(5, 5, 0);
        var eye = new net.minecraft.world.phys.Vec3(1.25, 65.62, -3e6 - 0.0625);
        var aim = new CombatView.Aim(-179.53067f, 89.5f);
        byte[] withEye = encode(new CombatViewC2SPacket(old.view().withFireEye(eye)));
        byte[] withAim = encode(new CombatViewC2SPacket(old.view().withFire(eye, aim)));
        assertArrayEquals(withEye, Arrays.copyOf(withAim, withEye.length));
        assertEquals(withEye.length + 8, withAim.length);
        assertEquals(old.view().withFire(eye, aim), decode(withAim).view());
        assertNull(decode(withEye).fireAim());
        for (int cut = 1; cut < 8; cut++) {
            byte[] partial = Arrays.copyOf(withAim, withAim.length - cut);
            assertThrows(IllegalArgumentException.class, () -> decode(partial));
        }
        assertThrows(IllegalArgumentException.class, () -> old.view().withFire(null, aim));
        assertThrows(IllegalArgumentException.class, () -> new CombatView.Aim(0, 90.5f));
        assertThrows(IllegalArgumentException.class, () -> new CombatView.Aim(Float.NaN, 0));
        byte[] tilted = withAim.clone();
        int pitch = Float.floatToRawIntBits(91);
        for (int i = 0; i < 4; i++)
            tilted[withAim.length - 4 + i] = (byte) (pitch >>> (24 - 8 * i));
        assertThrows(IllegalArgumentException.class, () -> decode(tilted));
    }

    @Test
    void oversizePayloadIsRejectedBeforeDecoding() {
        byte[] bytes = encode(ticket(103, 98, 0));
        byte[] padded = Arrays.copyOf(bytes, AnimationSchema.MAX_PAYLOAD_BYTES + 1);
        var error = assertThrows(IllegalArgumentException.class, () -> decode(padded));
        assertTrue(error.getMessage().contains("capacity"));
    }

    @Test
    void receiptTimeSamplesTheCeilingRecord() {
        // 26214 / 65536 = 0.39999..., the quantized R of the walk's 1017.4 receipt.
        var view = new CombatView(500, 3, 1020, 1017, 26214, receipt(List.of()).animation());
        assertEquals(1017.4, view.renderTime(), 1e-4);
        assertEquals(new Time(1017, 26214), view.r());
    }
}
