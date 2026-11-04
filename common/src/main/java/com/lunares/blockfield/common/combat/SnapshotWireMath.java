package com.lunares.blockfield.common.combat;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Phase-3 snapshot wire math shared by the server sender and the client buffer: the canonical
 * uvarlong/uvarint framing both codecs use, render-time helpers, lerps and placement samplers. No
 * pose frames or matrices here; the server-side interpolator owns those (it works on retained frame
 * inputs, never wire values).
 */
public final class SnapshotWireMath {
    private SnapshotWireMath() {}

    /** u16 angle steps per full turn (the render-time alpha quantum). */
    public static final int ANGLE_STEPS = 65536;

    /** Wire alpha to the render-time fraction in [0, 1); the quantum is 1/65536 tick. */
    public static double wireFraction(int alpha) {
        if (alpha < 0 || alpha > 0xFFFF)
            throw new IllegalArgumentException("Alpha out of u16 range: " + alpha);
        return alpha / (double) ANGLE_STEPS;
    }

    /** Absolute rendered time R = tick + alpha/65536. */
    public static double renderTime(long tick, int alpha) {
        if (tick < 0) throw new IllegalArgumentException("Negative render tick: " + tick);
        return tick + wireFraction(alpha);
    }

    /**
     * Minimal unsigned-LEB128 length of a non-negative long (at most 9 bytes for Long.MAX_VALUE).
     */
    public static int sizeOfUvarLong(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative uvarlong: " + value);
        int bytes = 1;
        while ((value >>>= 7) != 0) bytes++;
        return bytes;
    }

    /** Minimal unsigned-LEB128 length of a non-negative int (at most 5 bytes). */
    public static int sizeOfUvarInt(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative uvarint: " + value);
        int bytes = 1;
        while ((value >>>= 7) != 0) bytes++;
        return bytes;
    }

    /**
     * Canonical minimal-length unsigned LEB128, at most 9 bytes (exactly Long.MAX_VALUE's length).
     * A 10-byte negative-style encoding never fits.
     */
    public static void writeUvarLong(FriendlyByteBuf buf, long value) {
        if (value < 0) throw new IllegalArgumentException("Negative uvarlong: " + value);
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    /**
     * Reads {@link #writeUvarLong}: rejects truncation, encodings past 9 bytes and non-minimal
     * (overlong) forms.
     */
    public static long readUvarLong(FriendlyByteBuf buf) {
        long value = 0;
        int shift = 0;
        int bytes = 0;
        while (true) {
            if (!buf.isReadable()) throw new IllegalArgumentException("Truncated uvarlong");
            byte part = buf.readByte();
            bytes++;
            if (bytes > 9) throw new IllegalArgumentException("uvarlong exceeds 9 bytes");
            value |= (long) (part & 0x7F) << shift;
            if ((part & 0x80) == 0) break;
            shift += 7;
        }
        if (bytes != sizeOfUvarLong(value))
            throw new IllegalArgumentException("Non-canonical uvarlong");
        return value;
    }

    /** Canonical minimal-length unsigned LEB128 for a non-negative int, at most 5 bytes. */
    public static void writeUvarInt(FriendlyByteBuf buf, int value) {
        if (value < 0) throw new IllegalArgumentException("Negative uvarint: " + value);
        buf.writeVarInt(value);
    }

    /**
     * Reads {@link #writeUvarInt}: rejects truncation, overflow past 5 bytes and overlong forms.
     */
    public static int readUvarInt(FriendlyByteBuf buf) {
        long value = 0;
        int shift = 0;
        int bytes = 0;
        while (true) {
            if (!buf.isReadable()) throw new IllegalArgumentException("Truncated uvarint");
            byte part = buf.readByte();
            bytes++;
            if (bytes > 5) throw new IllegalArgumentException("uvarint exceeds 5 bytes");
            value |= (long) (part & 0x7F) << shift;
            if ((part & 0x80) == 0) break;
            shift += 7;
        }
        if (value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("uvarint overflows int32");
        if (bytes != sizeOfUvarInt((int) value))
            throw new IllegalArgumentException("Non-canonical uvarint");
        return (int) value;
    }

    public static double lerp(double p, double old, double current) {
        return old + p * (current - old);
    }

    public static float lerp(float p, float old, float current) {
        return old + p * (current - old);
    }

    public static float rotLerp(float p, float old, float current) {
        float delta = (current - old) % 360f;
        if (delta >= 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        return old + p * delta;
    }

    public static AnimationState.Vec3 origin(AnimationState.Placement value, float p) {
        var old = value.previousOrigin();
        var current = value.origin();
        return new AnimationState.Vec3(
                lerp((double) p, old.x(), current.x()),
                lerp((double) p, old.y(), current.y()),
                lerp((double) p, old.z(), current.z()));
    }

    public static AnimationState.Vec3 velocity(AnimationState.Placement value, float p) {
        var old = value.deltaMovementOnPreviousTick();
        var current = value.deltaMovement();
        return new AnimationState.Vec3(
                lerp((double) p, old.x(), current.x()),
                lerp((double) p, old.y(), current.y()),
                lerp((double) p, old.z(), current.z()));
    }

    public static float walkPosition(AnimationState.Walk walk, float p) {
        return walk.position() - walk.speed() * (1 - p);
    }

    public static float walkSpeed(AnimationState.Walk walk, float p) {
        return lerp(p, walk.speedOld(), walk.speed());
    }
}
