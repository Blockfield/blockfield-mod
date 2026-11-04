package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.common.combat.AnimationWire.Family;
import com.lunares.blockfield.common.combat.AnimationWire.Fragment;
import com.lunares.blockfield.common.combat.AnimationWire.Header;
import com.lunares.blockfield.common.combat.AnimationWire.Transaction;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ADR4.2 §8 C2S wire: one envelope-v2 fragment of an applied report or ticket, RAW and canonical
 * (no ref table; tag 0 is rejected), at most {@link AnimationWire#MAX_C2S_FRAGMENTS} fragments of a
 * {@link AnimationWire#MAX_C2S_BYTES} transaction. The bodies keep the exact {@link
 * AnimationAppliedC2SPacket#CODEC} and {@link CombatViewC2SPacket#ANIMATION_CODEC} layouts.
 */
public record AnimationWireC2SPacket(Fragment fragment) implements CustomPacketPayload {
    public static final Type<AnimationWireC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "anim_wire_c2s"));
    public static final StreamCodec<FriendlyByteBuf, AnimationWireC2SPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> packet.fragment().write(buf),
                    buf -> new AnimationWireC2SPacket(Fragment.read(buf, false)));

    public AnimationWireC2SPacket {
        if (fragment.header().family().s2c)
            throw new IllegalArgumentException("S2C family on the C2S wire");
    }

    /** The transaction's packets in sending order. */
    public static List<AnimationWireC2SPacket> packets(Header header, byte[] body) {
        var result = new ArrayList<AnimationWireC2SPacket>();
        for (Fragment fragment : AnimationWire.fragments(header, body))
            result.add(new AnimationWireC2SPacket(fragment));
        return List.copyOf(result);
    }

    public static byte[] ticket(CombatViewC2SPacket packet) {
        return body(buf -> CombatViewC2SPacket.ANIMATION_CODEC.encode(buf, packet));
    }

    public static byte[] applied(AnimationAppliedC2SPacket packet) {
        return body(buf -> AnimationAppliedC2SPacket.CODEC.encode(buf, packet));
    }

    private static byte[] body(java.util.function.Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer(1024, AnimationWire.MAX_C2S_BYTES));
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } catch (IndexOutOfBoundsException capacity) {
            throw new IllegalArgumentException("report_capacity", capacity);
        } finally {
            buf.release();
        }
    }

    /** {@link CombatViewC2SPacket} (TICKET) or {@link AnimationAppliedC2SPacket} (APPLIED). */
    public static CustomPacketPayload decode(Transaction transaction) {
        FriendlyByteBuf buf = transaction.buffer();
        CustomPacketPayload result =
                switch (transaction.header().family()) {
                    case TICKET -> CombatViewC2SPacket.ANIMATION_CODEC.decode(buf);
                    case APPLIED -> AnimationAppliedC2SPacket.CODEC.decode(buf);
                    default -> throw new IllegalArgumentException("S2C family on the C2S wire");
                };
        if (buf.isReadable()) throw new IllegalArgumentException("Trailing wire transaction bytes");
        return result;
    }

    /** The family a C2S payload travels as. */
    public static Family family(CustomPacketPayload payload) {
        if (payload instanceof AnimationAppliedC2SPacket) return Family.APPLIED;
        if (payload instanceof CombatViewC2SPacket) return Family.TICKET;
        throw new IllegalArgumentException("Not a C2S wire payload: " + payload.type().id());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
