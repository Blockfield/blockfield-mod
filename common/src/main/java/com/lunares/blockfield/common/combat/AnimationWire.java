package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.SnapshotWireMath.*;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;
import net.minecraft.network.FriendlyByteBuf;

/**
 * ADR4.2 §6 envelope v2: one logical transaction (a snapshot, an event delivery, an applied report
 * or a ticket) split into a fixed 24 KiB partition of contiguous fragments that repeat one header.
 * Transport chunking never truncates: every bound below is a failure bound, checked before anything
 * is allocated for it, and a defect is reported, never repaired by reordering or holding.
 *
 * <pre>
 * wireVersion:u8=2, family:u8, codec:u8 (RAW=0), tableGeneration:uvarlong,
 * connectionId, observerStreamEpoch, sceneGeneration, logicalSequence,
 * logicalTick, previousTickPlusOne:uvarlong, decodedLength:uvarint, crc32c:u32,
 * fragmentIndex:uvarint, fragmentCount:uvarint, body (the rest of the payload)
 * </pre>
 */
public final class AnimationWire {
    private AnimationWire() {}

    public static final int VERSION = 2;

    /** Fixed partition: every fragment body is this long except the last. */
    public static final int BODY = 24 * 1024;

    /** S2C transaction bound (≈110× the largest measured interned transaction). */
    public static final int MAX_S2C_BYTES = 64 << 20;

    public static final int MAX_C2S_BYTES = 256 * 1024;

    public static final int MAX_C2S_FRAGMENTS = (MAX_C2S_BYTES + BODY - 1) / BODY;

    /** Codec tags; ZLIB is reserved for the §0 fallback and rejected until it is adopted. */
    public static final int RAW = 0, ZLIB = 1;

    /** Largest header: three bytes, seven 9-byte uvarlongs, three 5-byte uvarints, the CRC. */
    public static final int MAX_HEADER_BYTES = 3 + 7 * 9 + 3 * 5 + 4;

    public enum Family {
        SNAPSHOT(0, true),
        EVENT(1, true),
        APPLIED(2, false),
        TICKET(3, false);

        public final int id;
        public final boolean s2c;

        Family(int id, boolean s2c) {
            this.id = id;
            this.s2c = s2c;
        }

        public int maxBytes() {
            return s2c ? MAX_S2C_BYTES : MAX_C2S_BYTES;
        }

        static Family of(int id) {
            for (Family family : values()) if (family.id == id) return family;
            throw new IllegalArgumentException("Unknown wire family " + id);
        }
    }

    /**
     * Identical in every fragment of one transaction. {@code tableGeneration} names the observer's
     * READY table ({@link AnimationRefTable}); 0 is a literal-only transaction and is required on
     * every C2S family. {@code decodedLength} and {@code crc32c} cover the concatenated bodies.
     */
    public record Header(
            Family family,
            long tableGeneration,
            long connectionId,
            long observerStreamEpoch,
            long sceneGeneration,
            long logicalSequence,
            long logicalTick,
            long previousTickPlusOne,
            int decodedLength,
            int crc32c) {
        public Header {
            java.util.Objects.requireNonNull(family, "family");
            for (long value :
                    new long[] {
                        tableGeneration,
                        connectionId,
                        observerStreamEpoch,
                        sceneGeneration,
                        logicalSequence,
                        logicalTick,
                        previousTickPlusOne
                    }) AnimationSchema.nonnegative(value, "wire header field");
            if (decodedLength < 1 || decodedLength > family.maxBytes())
                throw new IllegalArgumentException("Wire transaction length " + decodedLength);
            if (!family.s2c && tableGeneration != 0)
                throw new IllegalArgumentException("C2S wire never names a ref table");
        }

        /** {@code ceil(decodedLength / BODY)}. */
        public int fragmentCount() {
            return (decodedLength + BODY - 1) / BODY;
        }

        /** The body length of fragment {@code index}. */
        public int bodyLength(int index) {
            return index < fragmentCount() - 1 ? BODY : decodedLength - index * BODY;
        }

        /** The header of {@code body}: its length and CRC32C. */
        public static Header of(
                Family family,
                long tableGeneration,
                long connectionId,
                long observerStreamEpoch,
                long sceneGeneration,
                long logicalSequence,
                long logicalTick,
                long previousTickPlusOne,
                byte[] body) {
            return new Header(
                    family,
                    tableGeneration,
                    connectionId,
                    observerStreamEpoch,
                    sceneGeneration,
                    logicalSequence,
                    logicalTick,
                    previousTickPlusOne,
                    body.length,
                    AnimationWire.crc32c(body));
        }
    }

    /** One payload: the header, its position and its exact body slice. */
    public record Fragment(Header header, int index, byte[] body) {
        public Fragment {
            java.util.Objects.requireNonNull(header, "header");
            if (index < 0 || index >= header.fragmentCount())
                throw new IllegalArgumentException("Wire fragment index " + index);
            if (body.length != header.bodyLength(index))
                throw new IllegalArgumentException("Wire fragment body length " + body.length);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeByte(VERSION);
            buf.writeByte(header.family().id);
            buf.writeByte(RAW);
            writeUvarLong(buf, header.tableGeneration());
            writeUvarLong(buf, header.connectionId());
            writeUvarLong(buf, header.observerStreamEpoch());
            writeUvarLong(buf, header.sceneGeneration());
            writeUvarLong(buf, header.logicalSequence());
            writeUvarLong(buf, header.logicalTick());
            writeUvarLong(buf, header.previousTickPlusOne());
            writeUvarInt(buf, header.decodedLength());
            buf.writeInt(header.crc32c());
            writeUvarInt(buf, index);
            writeUvarInt(buf, header.fragmentCount());
            buf.writeBytes(body);
        }

        /**
         * One fragment of the given direction. Every field is checked before the body is copied:
         * version, family direction, codec, lengths against the direction's bound, the fragment
         * count against {@code ceil(decodedLength / BODY)} and the exact remaining body length.
         */
        public static Fragment read(FriendlyByteBuf buf, boolean s2c) {
            AnimationSchema.require(buf, 3);
            if (buf.readUnsignedByte() != VERSION)
                throw new IllegalArgumentException("Unknown wire version");
            Family family = Family.of(buf.readUnsignedByte());
            if (family.s2c != s2c) throw new IllegalArgumentException("Wire family direction");
            int codec = buf.readUnsignedByte();
            if (codec != RAW) throw new IllegalArgumentException("Unsupported wire codec " + codec);
            long table = readUvarLong(buf);
            long connection = readUvarLong(buf);
            long stream = readUvarLong(buf);
            long scene = readUvarLong(buf);
            long sequence = readUvarLong(buf);
            long tick = readUvarLong(buf);
            long previous = readUvarLong(buf);
            int length = readUvarInt(buf);
            AnimationSchema.require(buf, 4);
            int crc = buf.readInt();
            int index = readUvarInt(buf);
            int count = readUvarInt(buf);
            var header =
                    new Header(
                            family,
                            table,
                            connection,
                            stream,
                            scene,
                            sequence,
                            tick,
                            previous,
                            length,
                            crc);
            if (count != header.fragmentCount())
                throw new IllegalArgumentException("Wire fragment count " + count);
            if (index >= count) throw new IllegalArgumentException("Wire fragment index " + index);
            if (buf.readableBytes() != header.bodyLength(index))
                throw new IllegalArgumentException("Wire fragment body length lie");
            byte[] body = new byte[buf.readableBytes()];
            buf.readBytes(body);
            return new Fragment(header, index, body);
        }
    }

    /** A complete, CRC-checked logical transaction. */
    public record Transaction(Header header, byte[] body) {
        @Override
        public byte[] body() {
            return body.clone();
        }

        /** The body without a copy, for the decoder of the receiving stage. */
        public FriendlyByteBuf buffer() {
            return new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(body));
        }
    }

    /** {@code body}'s fragments in sending order; every body is BODY bytes except the last. */
    public static List<Fragment> fragments(Header header, byte[] body) {
        if (body.length != header.decodedLength() || crc32c(body) != header.crc32c())
            throw new IllegalArgumentException("Wire header does not describe the body");
        int count = header.fragmentCount();
        var result = new ArrayList<Fragment>(count);
        for (int i = 0; i < count; i++) {
            int from = i * BODY;
            result.add(
                    new Fragment(
                            header,
                            i,
                            java.util.Arrays.copyOfRange(body, from, from + header.bodyLength(i))));
        }
        return List.copyOf(result);
    }

    public static int crc32c(byte[] body) {
        var crc = new CRC32C();
        crc.update(body);
        return (int) crc.getValue();
    }

    /**
     * Per-connection reassembly of one direction: at most one transaction in progress per family,
     * fragments 0..count-1 in order with identical headers. A gap, a repeat, a new same-family
     * transaction before completion, or a CRC mismatch throws and discards that family's partial
     * transaction; the owner takes its restart (S2C) or {@code malformed} (C2S) path. Other
     * families may interleave. Memory grows with the fragments actually received, never with a
     * claimed length. Not thread-safe: one owner thread per connection.
     */
    public static final class Assembler {
        private final boolean s2c;
        private final Partial[] partial = new Partial[Family.values().length];

        private static final class Partial {
            final Header header;
            final List<byte[]> bodies = new ArrayList<>();

            Partial(Header header) {
                this.header = header;
            }
        }

        public Assembler(boolean s2c) {
            this.s2c = s2c;
        }

        /** The completed transaction at its last fragment, else null. */
        public Transaction accept(Fragment fragment) {
            Header header = fragment.header();
            if (header.family().s2c != s2c)
                throw new IllegalArgumentException("Wire family direction");
            int slot = header.family().id;
            Partial open = partial[slot];
            if (open == null) {
                if (fragment.index() != 0)
                    throw new IllegalArgumentException("Wire fragment gap: no transaction open");
                open = new Partial(header);
            } else if (!open.header.equals(header) || fragment.index() != open.bodies.size()) {
                partial[slot] = null;
                throw new IllegalArgumentException(
                        fragment.index() == 0
                                ? "New wire transaction before the previous completed"
                                : "Wire fragment out of order or header changed");
            }
            open.bodies.add(fragment.body);
            if (fragment.index() < header.fragmentCount() - 1) {
                partial[slot] = open;
                return null;
            }
            partial[slot] = null;
            byte[] body = new byte[header.decodedLength()];
            int at = 0;
            for (byte[] part : open.bodies) {
                System.arraycopy(part, 0, body, at, part.length);
                at += part.length;
            }
            if (crc32c(body) != header.crc32c())
                throw new IllegalArgumentException("Wire transaction CRC32C mismatch");
            return new Transaction(header, body);
        }

        /** Whether a transaction of {@code family} is in progress. */
        public boolean open(Family family) {
            return partial[family.id] != null;
        }

        /** JOIN, a connection change or a restart: every partial transaction is discarded. */
        public void clear() {
            java.util.Arrays.fill(partial, null);
        }
    }
}
