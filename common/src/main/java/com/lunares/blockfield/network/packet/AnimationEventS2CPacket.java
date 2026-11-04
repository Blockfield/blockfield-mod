package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One native logical delivery, including ParCool's unmodified all-player batch membership.
 *
 * <p>Per-target-life event sequences start at 1 with predecessor 0 (genesis); the ordered batch
 * requires {@code sequence > predecessor} so no event names itself its predecessor.
 */
public record AnimationEventS2CPacket(List<AnimationEvent> events) implements CustomPacketPayload {
    public static final Type<AnimationEventS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "animation_event"));
    public static final StreamCodec<FriendlyByteBuf, AnimationEventS2CPacket> CODEC =
            StreamCodec.of(AnimationEventS2CPacket::write, AnimationEventS2CPacket::read);

    public AnimationEventS2CPacket {
        events = List.copyOf(events);
        AnimationSchema.count(events.size(), AnimationSchema.MAX_NODES, "event batch");
        Map<AnimationState.Identity, Long> previous = new HashMap<>();
        for (AnimationEvent event : events) {
            AnimationSchema.nonnegative(event.id().serverTick(), "event tick");
            AnimationSchema.nonnegative(event.predecessorEventSequence(), "predecessor event");
            if (event.id().eventSequence() <= event.predecessorEventSequence()
                    || event.broadcastNs() < event.acceptedNs())
                throw new IllegalArgumentException("Invalid accepted event order");
            Long predecessor = previous.put(event.identity(), event.id().eventSequence());
            if (predecessor != null && predecessor != event.predecessorEventSequence())
                throw new IllegalArgumentException("Unordered native event batch");
        }
    }

    private static void write(FriendlyByteBuf b, AnimationEventS2CPacket packet) {
        b.writeBytes(
                AnimationSchema.encodeBounded(
                        s -> {
                            s.writeByte(AnimationSchema.VERSION);
                            SnapshotWireMath.writeUvarInt(s, packet.events().size());
                            for (AnimationEvent event : packet.events())
                                AnimationSchema.write(s, event);
                        }));
    }

    private static AnimationEventS2CPacket read(FriendlyByteBuf b) {
        AnimationSchema.count(
                b.readableBytes(), AnimationSchema.MAX_PAYLOAD_BYTES, "event payload");
        AnimationSchema.require(b, 1);
        if (b.readUnsignedByte() != AnimationSchema.VERSION)
            throw new IllegalArgumentException("Unknown event version");
        int count = AnimationSchema.readCount(b, AnimationSchema.MAX_NODES, 1);
        List<AnimationEvent> events = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) events.add(AnimationSchema.read(b, AnimationEvent.class));
        if (b.isReadable()) throw new IllegalArgumentException("Trailing event bytes");
        return new AnimationEventS2CPacket(events);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
