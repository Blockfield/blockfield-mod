package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationRefTable;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.common.combat.AnimationWire.Family;
import com.lunares.blockfield.common.combat.AnimationWire.Fragment;
import com.lunares.blockfield.common.combat.AnimationWire.Header;
import com.lunares.blockfield.common.combat.AnimationWire.Transaction;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ADR4.2 §4 S2C wire: one envelope-v2 fragment of an interned snapshot or event transaction. The
 * transaction bodies keep the exact {@link PlayerSnapshotS2CPacket#ANIMATION_CODEC} and {@link
 * AnimationEventS2CPacket#CODEC} layouts, with every ref of the observer's READY table written as a
 * handle ({@link AnimationSchema#writeWire}); decoding resolves handles only through the table the
 * header names.
 */
public record AnimationWireS2CPacket(Fragment fragment) implements CustomPacketPayload {
    public static final Type<AnimationWireS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "anim_wire_s2c"));
    public static final StreamCodec<FriendlyByteBuf, AnimationWireS2CPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> packet.fragment().write(buf),
                    buf -> new AnimationWireS2CPacket(Fragment.read(buf, true)));

    public AnimationWireS2CPacket {
        if (!fragment.header().family().s2c)
            throw new IllegalArgumentException("C2S family on the S2C wire");
    }

    /** The transaction's packets in sending order. */
    public static List<AnimationWireS2CPacket> packets(Header header, byte[] body) {
        var result = new ArrayList<AnimationWireS2CPacket>();
        for (Fragment fragment : AnimationWire.fragments(header, body))
            result.add(new AnimationWireS2CPacket(fragment));
        return List.copyOf(result);
    }

    /**
     * Snapshot transaction body. Its header fields must equal the envelope's {@code
     * observerStreamEpoch}, {@code logicalTick} and {@code previousTickPlusOne}.
     */
    public static byte[] snapshot(
            PlayerSnapshotS2CPacket packet,
            AnimationRefTable table,
            AnimationSchema.SharedEncodings shared) {
        if (packet.animation() == null)
            throw new IllegalArgumentException("Complete ADR4 snapshot required");
        return body(
                b -> {
                    b.writeByte(AnimationSchema.VERSION);
                    SnapshotWireMath.writeUvarLong(b, packet.observerStreamEpoch());
                    SnapshotWireMath.writeUvarLong(b, packet.previousTickPlusOne());
                    SnapshotWireMath.writeUvarLong(b, packet.serverTick());
                    b.writeByte(packet.interpMin());
                    b.writeByte(packet.interpMax());
                    b.writeByte(packet.dilationPercent());
                    b.writeShort(packet.holdToSnapMs());
                    write(b, packet.animation(), table, shared);
                });
    }

    /** Event transaction body: one native logical delivery, as {@link AnimationEventS2CPacket}. */
    public static byte[] events(
            AnimationEventS2CPacket packet,
            AnimationRefTable table,
            AnimationSchema.SharedEncodings shared) {
        return body(
                b -> {
                    b.writeByte(AnimationSchema.VERSION);
                    SnapshotWireMath.writeUvarInt(b, packet.events().size());
                    for (AnimationEvent event : packet.events()) write(b, event, table, shared);
                });
    }

    private static void write(
            FriendlyByteBuf b,
            Record value,
            AnimationRefTable table,
            AnimationSchema.SharedEncodings shared) {
        if (shared == null) AnimationSchema.writeWire(b, value, table);
        else AnimationSchema.writeWire(b, value, table, shared);
    }

    private static byte[] body(Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer(4096, AnimationWire.MAX_S2C_BYTES));
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } catch (IndexOutOfBoundsException capacity) {
            throw new IllegalArgumentException("wire_capacity", capacity);
        } finally {
            buf.release();
        }
    }

    /**
     * The decoded payload of a complete S2C transaction: {@link PlayerSnapshotS2CPacket} or {@link
     * AnimationEventS2CPacket}. A nonzero {@code tableGeneration} must name a table {@code tables}
     * holds; a handle in a literal-only transaction, an unknown handle or tag, a handle of the
     * wrong type and trailing bytes are all rejected as protocol defects.
     */
    public static CustomPacketPayload decode(
            Transaction transaction, LongFunction<AnimationRefTable> tables) {
        Header header = transaction.header();
        AnimationRefTable table = null;
        if (header.tableGeneration() != 0) {
            table = tables.apply(header.tableGeneration());
            if (table == null || table.generation() != header.tableGeneration())
                throw new IllegalArgumentException(
                        "Wire transaction names table "
                                + header.tableGeneration()
                                + " this connection does not hold");
        }
        FriendlyByteBuf b = transaction.buffer();
        AnimationSchema.require(b, 1);
        AnimationSchema.checkVersion(b.readUnsignedByte(), "animation");
        CustomPacketPayload result;
        if (header.family() == Family.SNAPSHOT) {
            long stream = SnapshotWireMath.readUvarLong(b),
                    previous = SnapshotWireMath.readUvarLong(b),
                    tick = SnapshotWireMath.readUvarLong(b);
            AnimationSchema.require(b, 5);
            int min = b.readUnsignedByte(),
                    max = b.readUnsignedByte(),
                    dilation = b.readUnsignedByte(),
                    hold = b.readUnsignedShort();
            if (stream != header.observerStreamEpoch()
                    || tick != header.logicalTick()
                    || previous != header.previousTickPlusOne())
                throw new IllegalArgumentException("Snapshot body differs from its envelope");
            var batch = AnimationSchema.readWire(b, AnimationRecipe.Batch.class, table);
            result =
                    PlayerSnapshotS2CPacket.animation(
                            stream, previous, tick, min, max, dilation, hold, batch);
        } else if (header.family() == Family.EVENT) {
            int count = AnimationSchema.readCount(b, AnimationSchema.MAX_NODES, 1);
            var events = new ArrayList<AnimationEvent>(Math.min(count, 64));
            for (int i = 0; i < count; i++)
                events.add(AnimationSchema.readWire(b, AnimationEvent.class, table));
            result = new AnimationEventS2CPacket(events);
        } else throw new IllegalArgumentException("C2S family on the S2C wire");
        if (b.isReadable()) throw new IllegalArgumentException("Trailing wire transaction bytes");
        return result;
    }

    /**
     * WIRE-GATE differential: the canonical {@link PlayerSnapshotS2CPacket#ANIMATION_CODEC} or
     * {@link AnimationEventS2CPacket#CODEC} bytes of a transaction, transcoded from its interned
     * body without decoding values ({@link AnimationSchema#expand}). Unbounded like the wire.
     */
    public static byte[] expand(Transaction transaction, AnimationRefTable table) {
        FriendlyByteBuf in = transaction.buffer();
        return body(
                out -> {
                    AnimationSchema.require(in, 1);
                    out.writeBytes(in, 1);
                    if (transaction.header().family() == Family.SNAPSHOT) {
                        for (int i = 0; i < 3; i++)
                            SnapshotWireMath.writeUvarLong(out, SnapshotWireMath.readUvarLong(in));
                        AnimationSchema.require(in, 5);
                        out.writeBytes(in, 5);
                        AnimationSchema.expand(in, out, AnimationRecipe.Batch.class, table);
                    } else {
                        int count = AnimationSchema.readCount(in, AnimationSchema.MAX_NODES, 1);
                        SnapshotWireMath.writeUvarInt(out, count);
                        for (int i = 0; i < count; i++)
                            AnimationSchema.expand(in, out, AnimationEvent.class, table);
                    }
                    if (in.isReadable())
                        throw new IllegalArgumentException("Trailing wire transaction bytes");
                });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
