package com.lunares.blockfield.network.packet;

import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One never-throwing decode shape for binary S2C payloads: malformed bytes degrade to the packet's
 * INVALID sentinel, and the packet's own slice is always consumed, so a newer server's trailing
 * fields never trip vanilla PacketDecoder's leftover-bytes disconnect. Receivers ignore {@code
 * !valid()} packets.
 */
public final class PacketCompat {
    private PacketCompat() {}

    public static <P> P decode(
            FriendlyByteBuf buf, Function<FriendlyByteBuf, P> reader, P invalid) {
        try {
            return reader.apply(buf);
        } catch (RuntimeException bad) {
            return invalid;
        } finally {
            // PacketDecoder disconnects on any unread bytes: trailing fields added by newer
            // servers and malformed tails are both ignored instead of fatal.
            buf.skipBytes(buf.readableBytes());
        }
    }
}
