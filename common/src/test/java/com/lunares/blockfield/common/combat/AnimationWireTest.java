package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static com.lunares.blockfield.common.combat.AnimationResources.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.NativeStage;
import com.lunares.blockfield.common.combat.AnimationWire.Family;
import com.lunares.blockfield.common.combat.AnimationWire.Fragment;
import com.lunares.blockfield.common.combat.AnimationWire.Header;
import com.lunares.blockfield.common.combat.AnimationWire.Transaction;
import com.lunares.blockfield.network.packet.AnimationAppliedC2SPacket;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.AnimationWireC2SPacket;
import com.lunares.blockfield.network.packet.AnimationWireS2CPacket;
import com.lunares.blockfield.network.packet.CombatViewC2SPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import io.netty.buffer.Unpooled;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongFunction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

/**
 * WIRE-CODEC (ADR4.2 v3 §5–§6): the production interned encoder, fragmenter, assembler and decoder
 * reproduce the canonical U1 bytes and values exactly; tables never cross observers, generations or
 * digests; malicious input is rejected before any claimed allocation.
 */
class AnimationWireTest {
    static final long G = 5;

    /** One ref per (class, slot): slot 0 is in the table, 1 a generation literal, 2 life-scoped. */
    static final Map<Class<?>, ResourceRef[]> REFS = new LinkedHashMap<>();

    static final AnimationRefTable TABLE;

    static {
        var inTable = new ArrayList<ResourceRef>();
        int content = 1;
        for (var type : ResourceRef.class.getPermittedSubclasses()) {
            var refs = new ResourceRef[3];
            for (int slot = 0; slot < 3; slot++)
                refs[slot] = ref(type, G, slot == 2 ? 9 : 0, content++ * 7919L);
            REFS.put(type, refs);
            inTable.add(refs[0]);
        }
        TABLE = AnimationRefTable.of(G, inTable);
    }

    static ResourceRef ref(Class<?> type, long generation, long life, long content) {
        try {
            return (ResourceRef)
                    type.getConstructor(long.class, long.class, long.class, Digest.class)
                            .newInstance(
                                    generation,
                                    life,
                                    content,
                                    Digest.hash(
                                            (type.getSimpleName() + content)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // --- A ref-rich sample of every registered type ---

    private static int rotation;

    static Object rich(Type type, int depth) {
        if (type instanceof ParameterizedType p) {
            if (depth > 7) return List.of();
            return List.of(rich(p.getActualTypeArguments()[0], depth + 1));
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == ResourceRef.class || ResourceRef.class.isAssignableFrom(clazz)) {
            var candidates =
                    clazz == ResourceRef.class ? REFS.get(DefinitionRef.class) : REFS.get(clazz);
            return candidates[rotation++ % 3];
        }
        if (clazz == boolean.class) return true;
        if (clazz == int.class) return 0;
        if (clazz == long.class) return 0L;
        if (clazz == float.class) return -0.0f;
        if (clazz == double.class) return -0.0d;
        if (!clazz.isRecord() || clazz == AnimationPolicy.class || depth > 30) return sample(clazz);
        var fields = clazz.getRecordComponents();
        Class<?>[] parameters = new Class<?>[fields.length];
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            parameters[i] = fields[i].getType();
            values[i] = rich(fields[i].getGenericType(), depth + 1);
        }
        try {
            return clazz.getConstructor(parameters).newInstance(values);
        } catch (ReflectiveOperationException | RuntimeException refused) {
            return sample(clazz);
        }
    }

    static Object richOf(Class<?> type) {
        if (type.isInterface()) return rich(type.getPermittedSubclasses()[0], 0);
        return rich(type, 0);
    }

    static byte[] wire(Record value, AnimationRefTable table) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AnimationSchema.writeWire(buf, value, table);
            return bytes(buf);
        } finally {
            buf.release();
        }
    }

    static byte[] expand(byte[] interned, Type type, AnimationRefTable table) {
        var in = buffer(interned);
        var out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AnimationSchema.expand(in, out, type, table);
            assertFalse(in.isReadable(), "trailing interned bytes");
            return bytes(out);
        } finally {
            out.release();
        }
    }

    static <T extends Record> T readWire(byte[] bytes, Class<T> type, AnimationRefTable table) {
        var buf = buffer(bytes);
        T value = AnimationSchema.readWire(buf, type, table);
        assertFalse(buf.isReadable(), "trailing bytes");
        return value;
    }

    @Test
    void everyRegisteredTypeInternsAndExpandsToItsCanonicalBytes() {
        int withHandles = 0;
        for (Class<?> type : AnimationSchema.recordTypes()) {
            var value = (Record) richOf(type);
            byte[] canonical = AnimationSchema.encode(value);
            byte[] interned = wire(value, TABLE);
            assertArrayEquals(canonical, expand(interned, value.getClass(), TABLE), type.getName());
            assertEquals(
                    roundTrip(value),
                    readWire(interned, value.getClass(), TABLE),
                    type.getSimpleName());
            assertArrayEquals(canonical, wire(value, null), "no table: canonical bytes");
            if (interned.length < canonical.length) {
                withHandles++;
                // A handle never resolves without the table its transaction names.
                assertThrows(
                        IllegalArgumentException.class,
                        () -> readWire(interned, value.getClass(), null),
                        type.getSimpleName());
            }
        }
        // Types whose constructors refuse the generic rich value fall back to the plain sample;
        // the transformed fixture covers them with production values.
        assertTrue(withHandles >= 23, "ref-bearing types exercised: " + withHandles);
        // Each ref class: the table member is a 2-3 byte handle, the others stay 58-byte literals.
        for (var refs : REFS.values()) {
            assertEquals(2, wire((Record) refs[0], TABLE).length);
            assertArrayEquals(
                    AnimationSchema.encode((Record) refs[1]), wire((Record) refs[1], TABLE));
            assertArrayEquals(
                    AnimationSchema.encode((Record) refs[2]), wire((Record) refs[2], TABLE));
            assertEquals(58, AnimationSchema.encode((Record) refs[1]).length);
        }
    }

    // --- Full transport path ---

    static Transaction transport(Header header, byte[] body) {
        var assembler = new AnimationWire.Assembler(true);
        Transaction done = null;
        for (var packet : AnimationWireS2CPacket.packets(header, body)) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            AnimationWireS2CPacket.CODEC.encode(buf, packet);
            assertTrue(buf.readableBytes() <= AnimationWire.BODY + AnimationWire.MAX_HEADER_BYTES);
            var decoded = AnimationWireS2CPacket.CODEC.decode(buf);
            assertFalse(buf.isReadable());
            buf.release();
            assertNull(done, "nothing after the last fragment");
            done = assembler.accept(decoded.fragment());
        }
        assertNotNull(done);
        return done;
    }

    static PlayerSnapshotS2CPacket snapshot(int definitions, int bytesEach) {
        var record = (AnimationRecipe.SnapshotRecord) rich(AnimationRecipe.SnapshotRecord.class, 0);
        var defs = new ArrayList<Definition>();
        for (int i = 0; i < definitions; i++) {
            byte[] content = new byte[bytesEach];
            Arrays.fill(content, (byte) i);
            var refs = REFS.get(DefinitionRef.class);
            defs.add(
                    new Definition(
                            i % 2 == 0 ? refs[0] : ref(DefinitionRef.class, G, 0, 100_000 + i),
                            new Bytes(content),
                            List.of(REFS.get(ClipRef.class)[0], refs[2])));
        }
        var batch = new AnimationRecipe.Batch(0, 0, List.of(record), defs, List.of());
        return PlayerSnapshotS2CPacket.animation(0, 0, 0, 2, 6, 5, 300, batch);
    }

    static byte[] canonicalSnapshot(PlayerSnapshotS2CPacket packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PlayerSnapshotS2CPacket.ANIMATION_CODEC.encode(buf, packet);
            return bytes(buf);
        } finally {
            buf.release();
        }
    }

    static AnimationEventS2CPacket events() {
        var event =
                AnimationFixtures.with(
                        (AnimationEvent) rich(AnimationEvent.class, 0),
                        "id",
                        new AnimationEvent.EventId(103, 1, NativeStage.INTER_TICK_ACCEPT));
        event = AnimationFixtures.with(event, "predecessorEventSequence", 0L);
        event = AnimationFixtures.with(event, "broadcastNs", event.acceptedNs());
        var second =
                AnimationFixtures.with(
                        AnimationFixtures.with(
                                event,
                                "id",
                                new AnimationEvent.EventId(103, 2, NativeStage.PRE_NATIVE_TICK)),
                        "predecessorEventSequence",
                        1L);
        return new AnimationEventS2CPacket(List.of(event, second));
    }

    static byte[] canonicalEvents(AnimationEventS2CPacket packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AnimationEventS2CPacket.CODEC.encode(buf, packet);
            return bytes(buf);
        } finally {
            buf.release();
        }
    }

    static Header snapshotHeader(long table, PlayerSnapshotS2CPacket packet, byte[] body) {
        return Header.of(
                Family.SNAPSHOT,
                table,
                3,
                packet.observerStreamEpoch(),
                0,
                11,
                packet.serverTick(),
                packet.previousTickPlusOne(),
                body);
    }

    @Test
    void snapshotAndEventTransactionsSurviveFragmentationExactly() {
        LongFunction<AnimationRefTable> tables = g -> g == G ? TABLE : null;
        // 100 definitions of 1 000 bytes: a five-fragment snapshot.
        var packet = snapshot(100, 1000);
        byte[] canonical = canonicalSnapshot(packet);
        byte[] body = AnimationWireS2CPacket.snapshot(packet, TABLE, null);
        assertTrue(body.length < canonical.length);
        var header = snapshotHeader(G, packet, body);
        assertTrue(header.fragmentCount() >= 4, "fragments: " + header.fragmentCount());
        var transaction = transport(header, body);
        assertArrayEquals(canonical, AnimationWireS2CPacket.expand(transaction, TABLE));
        assertEquals(packet, AnimationWireS2CPacket.decode(transaction, tables));
        // Literal-only (an observer without READY): the canonical bytes themselves.
        byte[] literal = AnimationWireS2CPacket.snapshot(packet, null, null);
        assertArrayEquals(canonical, literal);
        var literalTx = transport(snapshotHeader(0, packet, literal), literal);
        assertEquals(packet, AnimationWireS2CPacket.decode(literalTx, tables));
        // An event delivery: normally one fragment.
        var events = events();
        byte[] eventCanonical = canonicalEvents(events);
        byte[] eventBody = AnimationWireS2CPacket.events(events, TABLE, null);
        assertTrue(eventBody.length < eventCanonical.length);
        var eventTx =
                transport(Header.of(Family.EVENT, G, 3, 1, 0, 4, 103, 0, eventBody), eventBody);
        assertEquals(1, eventTx.header().fragmentCount());
        assertArrayEquals(eventCanonical, AnimationWireS2CPacket.expand(eventTx, TABLE));
        assertEquals(events, AnimationWireS2CPacket.decode(eventTx, tables));
        // A snapshot body that differs from its envelope is a sender defect.
        var lying = Header.of(Family.SNAPSHOT, G, 3, 9, 0, 11, packet.serverTick(), 0, body);
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWireS2CPacket.decode(transport(lying, body), tables));
    }

    @Test
    void largeTransactionsSplitIntoTheFixedPartition() {
        // 1.1 MB, the size of a measured 30-player FULL: 46 fragments of exactly 24 576 bytes.
        var packet = snapshot(1100, 1000);
        byte[] body = AnimationWireS2CPacket.snapshot(packet, null, null);
        assertTrue(body.length > AnimationSchema.MAX_PAYLOAD_BYTES);
        var header = snapshotHeader(0, packet, body);
        var fragments = AnimationWire.fragments(header, body);
        assertEquals((body.length + AnimationWire.BODY - 1) / AnimationWire.BODY, fragments.size());
        for (int i = 0; i < fragments.size() - 1; i++)
            assertEquals(AnimationWire.BODY, fragments.get(i).body().length);
        var transaction = transport(header, body);
        assertArrayEquals(body, transaction.body());
        assertEquals(packet, AnimationWireS2CPacket.decode(transaction, g -> null));
    }

    // --- §5.3: two observers on G-1 and G sharing one target in one tick ---

    @Test
    void observersOnTwoGenerationsShareOneTargetWithoutCrossTalk() {
        var previous =
                AnimationRefTable.of(
                        G - 1,
                        List.of(
                                ref(ClipRef.class, G - 1, 0, 1),
                                ref(DefinitionRef.class, G - 1, 0, 2)));
        // One target component, stamped with generation-G refs, registered for the tick.
        var component = (AnimationState.Tacz) rich(AnimationState.Tacz.class, 0);
        byte[] canonical = AnimationSchema.encode(component);
        var digest = Digest.hash(canonical);
        assertEquals(digest, AnimationSchema.digest(component));
        AnimationSchema.shareEncoding(77, component);
        // The observer still on G-1 and the observer on G, in the same scope.
        byte[] old = wire(component, previous);
        byte[] current = wire(component, TABLE);
        assertArrayEquals(canonical, old, "G refs travel literal to a G-1 observer");
        assertArrayEquals(canonical, expand(old, AnimationState.Tacz.class, previous));
        assertTrue(current.length < canonical.length, "G handles for the G observer");
        assertArrayEquals(canonical, expand(current, AnimationState.Tacz.class, TABLE));
        assertEquals(component, readWire(current, AnimationState.Tacz.class, TABLE));
        assertEquals(component, readWire(old, AnimationState.Tacz.class, previous));
        // Distinct cache entries for the two tables, then the canonical one.
        assertEquals(2, AnimationSchema.sharedModes(component));
        assertArrayEquals(canonical, AnimationSchema.encode(component));
        assertEquals(3, AnimationSchema.sharedModes(component));
        // Digests stay canonical after interned writes in the same scope.
        assertEquals(digest, AnimationSchema.digest(component));
        assertEquals(digest, Digest.hash(AnimationSchema.encode(component)));
        // Later writes copy their own mode's entry: identical bytes, no cross-talk.
        assertArrayEquals(current, wire(component, TABLE));
        assertArrayEquals(old, wire(component, previous));
        var holder =
                new Definition(REFS.get(DefinitionRef.class)[0], new Bytes(new byte[1]), List.of());
        assertArrayEquals(
                AnimationSchema.encode(holder),
                expand(wire(holder, TABLE), Definition.class, TABLE));
    }

    @Test
    void frozenSharedEncodingsWriteTheSerialBytes() {
        var record = (AnimationRecipe.SnapshotRecord) rich(AnimationRecipe.SnapshotRecord.class, 0);
        var batch = new AnimationRecipe.Batch(0, 0, List.of(record), List.of(), List.of());
        var shared =
                AnimationSchema.SharedEncodings.builder()
                        .share(record.checkpoint())
                        .share(record.placement())
                        .prime(batch, TABLE)
                        .prime(batch, null)
                        .build();
        assertEquals(2, shared.modes(record.checkpoint()));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        AnimationSchema.writeWire(buf, batch, TABLE, shared);
        assertArrayEquals(wire(batch, TABLE), bytes(buf));
        AnimationSchema.writeWire(buf, batch, null, shared);
        assertArrayEquals(AnimationSchema.encode(batch), bytes(buf));
        // A mode the map never primed is written in full and never stored.
        var other = AnimationRefTable.of(G, List.of(REFS.get(ClipRef.class)[1]));
        AnimationSchema.writeWire(buf, batch, other, shared);
        assertArrayEquals(wire(batch, other), bytes(buf));
        assertEquals(2, shared.modes(record.checkpoint()));
        buf.release();
        var merged =
                AnimationSchema.SharedEncodings.merge(
                        List.of(shared, AnimationSchema.SharedEncodings.builder().build()));
        assertEquals(2, merged.modes(record.checkpoint()));
    }

    // --- Wrong tables (§5.3) ---

    @Test
    void wrongTablesAreRejectedNeverDecodedToAnotherValue() {
        var packet = snapshot(3, 10);
        byte[] interned = AnimationWireS2CPacket.snapshot(packet, TABLE, null);
        // A handle in a tableGeneration = 0 transaction, even if a generation-0 table exists.
        var literalHeader = transport(snapshotHeader(0, packet, interned), interned);
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWireS2CPacket.decode(literalHeader, g -> TABLE));
        var offline = AnimationRefTable.of(0, List.of(ref(ClipRef.class, 0, 0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWireS2CPacket.decode(literalHeader, g -> offline));
        // A table the client dropped, or one cleared at configuration start (old backend number).
        var named = transport(snapshotHeader(G, packet, interned), interned);
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWireS2CPacket.decode(named, g -> null));
        Map<Long, AnimationRefTable> cleared = new HashMap<>();
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWireS2CPacket.decode(named, cleared::get));
        // A holder answering another generation's table is refused too.
        var stale = AnimationRefTable.of(G + 1, List.of(ref(ClipRef.class, G + 1, 0, 3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWireS2CPacket.decode(named, g -> stale));
        assertEquals(packet, AnimationWireS2CPacket.decode(named, g -> g == G ? TABLE : null));
    }

    // --- Malicious handles and tags ---

    static byte[] handle(int index) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        SnapshotWireMath.writeUvarInt(buf, AnimationSchema.HANDLE);
        SnapshotWireMath.writeUvarInt(buf, index);
        return bytes(buf);
    }

    @Test
    void maliciousHandlesAndTagsAreRejected() {
        int clip = TABLE.handle(REFS.get(ClipRef.class)[0]);
        int config = TABLE.handle(REFS.get(ConfigRef.class)[0]);
        assertEquals(REFS.get(ClipRef.class)[0], readWire(handle(clip), ClipRef.class, TABLE));
        // Out-of-range, wrong-type and non-ref positions.
        assertThrows(
                IllegalArgumentException.class,
                () -> readWire(handle(TABLE.size()), ClipRef.class, TABLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> readWire(handle(Integer.MAX_VALUE), ClipRef.class, TABLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> readWire(handle(config), ClipRef.class, TABLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> readWire(handle(clip), AnimationState.Tacz.class, TABLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> expand(handle(TABLE.size()), ClipRef.class, TABLE));
        assertThrows(
                IllegalArgumentException.class, () -> expand(handle(config), ClipRef.class, TABLE));
        // Tag 0 without a table: canonical and C2S readers reject it like any unknown tag.
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationSchema.readComplete(buffer(handle(clip)), ClipRef.class));
        assertThrows(
                IllegalArgumentException.class, () -> readWire(handle(clip), ClipRef.class, null));
        // Unknown tags, truncation and non-canonical varints.
        for (byte[] bad :
                new byte[][] {
                    {(byte) 143},
                    {(byte) 0xff, 0x7f},
                    {0},
                    {0, (byte) 0x80},
                    {0, (byte) 0x80, 0},
                    {0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f}
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> readWire(bad, ClipRef.class, TABLE),
                    Arrays.toString(bad));
    }

    // --- Malicious fragments ---

    static byte[] fragmentBytes(Fragment fragment) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        fragment.write(buf);
        return bytes(buf);
    }

    /** A raw S2C fragment with arbitrary (possibly lying) fields. */
    static byte[] raw(
            int version,
            int family,
            int codec,
            long table,
            int length,
            int index,
            int count,
            int body) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(version);
        buf.writeByte(family);
        buf.writeByte(codec);
        SnapshotWireMath.writeUvarLong(buf, table);
        for (int i = 0; i < 6; i++) SnapshotWireMath.writeUvarLong(buf, 1);
        SnapshotWireMath.writeUvarInt(buf, length);
        buf.writeInt(0);
        SnapshotWireMath.writeUvarInt(buf, index);
        SnapshotWireMath.writeUvarInt(buf, count);
        buf.writeBytes(new byte[body]);
        return bytes(buf);
    }

    static Fragment read(byte[] bytes, boolean s2c) {
        return Fragment.read(buffer(bytes), s2c);
    }

    @Test
    void maliciousFragmentsAreRejectedBeforeAllocation() {
        int body = AnimationWire.BODY;
        // The control: a valid two-fragment header shape parses.
        assertEquals(1, read(raw(2, 0, 0, 0, body + 5, 1, 2, 5), true).index());
        var bad =
                Map.of(
                        "version", raw(1, 0, 0, 0, 10, 0, 1, 10),
                        "family", raw(2, 4, 0, 0, 10, 0, 1, 10),
                        "direction", raw(2, 2, 0, 0, 10, 0, 1, 10),
                        "zlib reserved", raw(2, 0, 1, 0, 10, 0, 1, 10),
                        "empty", raw(2, 0, 0, 0, 0, 0, 0, 0),
                        "oversize", raw(2, 0, 0, 0, AnimationWire.MAX_S2C_BYTES + 1, 0, 2731, body),
                        "count lie", raw(2, 0, 0, 0, body + 5, 0, 3, body),
                        "index past count", raw(2, 0, 0, 0, body + 5, 2, 2, 5),
                        "short body", raw(2, 0, 0, 0, body + 5, 0, 2, body - 1),
                        "long body", raw(2, 0, 0, 0, 10, 0, 1, 11));
        bad.forEach(
                (name, bytes) ->
                        assertThrows(
                                IllegalArgumentException.class, () -> read(bytes, true), name));
        // Truncated headers at every length.
        byte[] valid = raw(2, 0, 0, 0, 10, 0, 1, 10);
        for (int cut = 0; cut < valid.length - 10; cut++) {
            byte[] prefix = Arrays.copyOf(valid, cut);
            assertThrows(IllegalArgumentException.class, () -> read(prefix, true), "cut " + cut);
        }
        // C2S: no ref table, at most 11 fragments of a 256 KiB transaction.
        assertThrows(
                IllegalArgumentException.class, () -> read(raw(2, 2, 0, 1, 10, 0, 1, 10), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> read(raw(2, 3, 0, 0, AnimationWire.MAX_C2S_BYTES + 1, 0, 11, body), false));
        assertEquals(11, AnimationWire.MAX_C2S_FRAGMENTS);
        assertEquals(
                11, new Header(Family.TICKET, 0, 1, 1, 1, 1, 1, 1, 262_144, 0).fragmentCount());
        assertThrows(
                IllegalArgumentException.class,
                () -> new Header(Family.APPLIED, 1, 1, 1, 1, 1, 1, 1, 10, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Header(Family.SNAPSHOT, -1, 1, 1, 1, 1, 1, 1, 10, 0));
        // The S2C payload never carries a C2S family and vice versa.
        var c2s = Fragment.read(buffer(raw(2, 2, 0, 0, 10, 0, 1, 10)), false);
        assertThrows(IllegalArgumentException.class, () -> new AnimationWireS2CPacket(c2s));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationWire.Assembler(true).accept(c2s));
    }

    @Test
    void assemblerRejectsGapsRepeatsSplitsAndCrcFailures() {
        byte[] body = new byte[3 * AnimationWire.BODY - 7];
        new Random(5).nextBytes(body);
        var header = Header.of(Family.SNAPSHOT, 0, 1, 2, 0, 9, 9, 8, body);
        var fragments = AnimationWire.fragments(header, body);
        byte[] small = {1, 2, 3};
        var event =
                AnimationWire.fragments(Header.of(Family.EVENT, 0, 1, 2, 0, 1, 9, 0, small), small);
        // Other families may interleave; the snapshot still completes exactly.
        var assembler = new AnimationWire.Assembler(true);
        assertNull(assembler.accept(fragments.get(0)));
        assertNotNull(assembler.accept(event.get(0)));
        assertNull(assembler.accept(fragments.get(1)));
        assertTrue(assembler.open(Family.SNAPSHOT));
        assertArrayEquals(body, assembler.accept(fragments.get(2)).body());
        assertFalse(assembler.open(Family.SNAPSHOT));
        // Gap, repeat, a new same-family transaction before completion, changed header.
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationWire.Assembler(true).accept(fragments.get(1)));
        var repeat = new AnimationWire.Assembler(true);
        repeat.accept(fragments.get(0));
        assertThrows(IllegalArgumentException.class, () -> repeat.accept(fragments.get(0)));
        assertFalse(repeat.open(Family.SNAPSHOT), "a defect discards the partial transaction");
        var skip = new AnimationWire.Assembler(true);
        skip.accept(fragments.get(0));
        assertThrows(IllegalArgumentException.class, () -> skip.accept(fragments.get(2)));
        var changed = new AnimationWire.Assembler(true);
        changed.accept(fragments.get(0));
        var other = Header.of(Family.SNAPSHOT, 0, 1, 2, 0, 10, 9, 8, body);
        assertThrows(
                IllegalArgumentException.class,
                () -> changed.accept(AnimationWire.fragments(other, body).get(1)));
        // A CRC mismatch (the body changed in transit) never yields a transaction.
        byte[] corrupt = body.clone();
        corrupt[AnimationWire.BODY + 3] ^= 1;
        var wrongCrc =
                new Header(Family.SNAPSHOT, 0, 1, 2, 0, 9, 9, 8, body.length, header.crc32c());
        var crc = new AnimationWire.Assembler(true);
        crc.accept(new Fragment(wrongCrc, 0, Arrays.copyOfRange(corrupt, 0, AnimationWire.BODY)));
        crc.accept(
                new Fragment(
                        wrongCrc,
                        1,
                        Arrays.copyOfRange(corrupt, AnimationWire.BODY, 2 * AnimationWire.BODY)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        crc.accept(
                                new Fragment(
                                        wrongCrc,
                                        2,
                                        Arrays.copyOfRange(
                                                corrupt, 2 * AnimationWire.BODY, body.length))));
        // JOIN or a connection change discards assemblies.
        var join = new AnimationWire.Assembler(true);
        join.accept(fragments.get(0));
        join.clear();
        assertThrows(IllegalArgumentException.class, () -> join.accept(fragments.get(1)));
        // A header must describe its body.
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationWire.fragments(header, Arrays.copyOf(body, body.length - 1)));
    }

    @Test
    void aLengthLieAllocatesOnlyTheReceivedFragments() {
        var threads =
                (com.sun.management.ThreadMXBean)
                        java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        byte[] bytes =
                raw(
                        2,
                        0,
                        0,
                        0,
                        AnimationWire.MAX_S2C_BYTES,
                        0,
                        AnimationWire.MAX_S2C_BYTES / AnimationWire.BODY + 1,
                        AnimationWire.BODY);
        var assembler = new AnimationWire.Assembler(true);
        assembler.accept(read(bytes, true));
        assembler.clear();
        long before = threads.getThreadAllocatedBytes(id);
        var fragment = read(bytes, true);
        assembler.accept(fragment);
        long allocated = threads.getThreadAllocatedBytes(id) - before;
        assertTrue(allocated < (1 << 20), "a 64 MiB claim allocated " + allocated + " bytes");
        assertTrue(assembler.open(Family.SNAPSHOT));
    }

    // --- C2S ---

    @Test
    void c2sTransactionsKeepTheirLayoutsAndStayCanonical() {
        var view =
                receipt(
                        List.of(
                                victim(
                                        1,
                                        List.of(
                                                observer(
                                                        1,
                                                        1,
                                                        LayerTag.PA_94,
                                                        AnimationTypes.PhaseDomain.A,
                                                        100,
                                                        0)))));
        var applied = new AnimationAppliedC2SPacket(view);
        var ticket = new CombatViewC2SPacket(view);
        for (CustomPacketPayload payload : List.<CustomPacketPayload>of(applied, ticket)) {
            byte[] body =
                    payload == applied
                            ? AnimationWireC2SPacket.applied(applied)
                            : AnimationWireC2SPacket.ticket(ticket);
            var header =
                    Header.of(AnimationWireC2SPacket.family(payload), 0, 3, 1, 2, 5, 0, 0, body);
            var assembler = new AnimationWire.Assembler(false);
            Transaction done = null;
            for (var packet : AnimationWireC2SPacket.packets(header, body)) {
                var buf = new FriendlyByteBuf(Unpooled.buffer());
                AnimationWireC2SPacket.CODEC.encode(buf, packet);
                assertTrue(buf.readableBytes() <= 32767, "vanilla C2S payload limit");
                done = assembler.accept(AnimationWireC2SPacket.CODEC.decode(buf).fragment());
                buf.release();
            }
            assertEquals(payload, AnimationWireC2SPacket.decode(done));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationWire.Assembler(false).accept(snapshotFragment()));
    }

    static Fragment snapshotFragment() {
        byte[] body = {1};
        return AnimationWire.fragments(Header.of(Family.SNAPSHOT, 0, 0, 0, 0, 0, 0, 0, body), body)
                .get(0);
    }

    // --- Table derivation ---

    static dev.kosmx.playerAnim.core.data.KeyframeAnimation clip(String name, float pitch) {
        var builder =
                new dev.kosmx.playerAnim.core.data.KeyframeAnimation.AnimationBuilder(
                        dev.kosmx.playerAnim.core.data.AnimationFormat.UNKNOWN);
        builder.endTick = 10;
        builder.stopTick = 10;
        builder.setName(name);
        builder.getOrCreatePart("head").pitch.setEnabled(true);
        builder.getOrCreatePart("head")
                .pitch
                .addKeyFrame(0, pitch, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
        builder.getOrCreatePart("rightArm").yaw.setEnabled(true);
        builder.getOrCreatePart("rightArm")
                .yaw
                .addKeyFrame(0, pitch, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
        return builder.build();
    }

    /** One generation's views, built from scratch as either peer builds them. */
    static Object[] views(long generation, boolean extraDisplay) {
        var catalog = new PaGraph.Catalog(generation);
        var tacz = new TaczAssets(catalog, Map.of("tacz:ak47", "tacz:ak47_display"));
        tacz.register(
                "tacz:ak47_display",
                "tacz:rifle_default.player_animation",
                false,
                "empty",
                true,
                Map.of(
                        "idle", clip("idle", 0.1f),
                        "melee_bayonet", clip("melee_bayonet", 0.2f)));
        if (extraDisplay)
            tacz.register(
                    "tacz:client_only_display",
                    "tacz:x",
                    false,
                    "empty",
                    true,
                    Map.of("idle", clip("idle", 0.9f)));
        var lr = new LrAssets(catalog);
        lr.register(
                "lrtactical:bat",
                true,
                "lrtactical:bat",
                Map.of("idle", List.of("idle")),
                Map.of(),
                Map.of("idle", clip("idle", 0.3f)));
        return new Object[] {tacz, lr};
    }

    @Test
    void readyViewsResolveTheNamedDefinitionsInTheirRefTable() {
        var views = views(3, false);
        var tacz = (TaczAssets) views[0];
        var lr = (LrAssets) views[1];
        var entries = AnimationManifest.resources(tacz, lr, LayerTag.PA_42);
        var table = AnimationRefTable.derive(3, entries.keySet(), tacz, lr);
        var names =
                List.<ResourceRef>of(
                        AnimationRefTable.named(3, "tacz.gun", "tacz:ak47"),
                        AnimationRefTable.named(3, "tacz.display", "tacz:ak47_display"),
                        AnimationRefTable.named(3, "tacz.display", "tacz:default"),
                        AnimationRefTable.named(3, "tacz.melee", "melee_bayonet"),
                        AnimationRefTable.named(3, "lr.melee-display", "lrtactical:bat"));
        var store = new AnimationResources.Store();
        store.installAll(lr.definitions());
        for (var ref : names) {
            assertTrue(table.handle(ref) >= 0);
            assertDoesNotThrow(
                    () -> {
                        try (var lease = store.pin(List.of(ref))) {
                            assertEquals(ref, store.get(ref).ref());
                        }
                    },
                    ref.toString());
        }
    }

    @Test
    void bothPeersDeriveTheSameTableFromTheManifestKeys() {
        var server = views(3, false);
        var entries =
                AnimationManifest.resources(
                        (TaczAssets) server[0], (LrAssets) server[1], LayerTag.PA_42);
        assertTrue(entries.containsKey(AnimationManifest.TACZ_GUN + "tacz:ak47"));
        assertEquals(
                Digest.hash("tacz:ak47_display".getBytes(StandardCharsets.UTF_8)),
                entries.get(AnimationManifest.TACZ_GUN + "tacz:ak47"));
        var table =
                AnimationRefTable.derive(
                        3, entries.keySet(), (TaczAssets) server[0], (LrAssets) server[1]);
        assertEquals(table.digest(), entries.get(AnimationManifest.WIRE_REF_TABLE));
        // A client builds fresh views (no gun index) plus a client-only display: same table.
        var client = views(3, true);
        var derived =
                AnimationRefTable.derive(
                        3, entries.keySet(), (TaczAssets) client[0], (LrAssets) client[1]);
        assertEquals(table.digest(), derived.digest());
        assertEquals(table.size(), derived.size());
        // Contents: named ids, display, clips with metadata and parts, masks, carriers.
        var catalog = ((TaczAssets) server[0]).catalog();
        var display = ((TaczAssets) server[0]).displays().iterator().next();
        var expected = new ArrayList<ResourceRef>();
        expected.add(catalog.named("tacz.gun", "tacz:ak47"));
        expected.add(catalog.named("tacz.display", "tacz:ak47_display"));
        expected.add(catalog.named("tacz.display", "tacz:default"));
        expected.add(catalog.named("tacz.melee", "melee_bayonet"));
        expected.add(catalog.named("lr.melee-display", "lrtactical:bat"));
        expected.add(display.ref());
        for (var clip : display.clips().values()) {
            expected.add(clip);
            expected.add(catalog.metadata(clip));
            expected.add(catalog.part(clip, "head"));
            expected.add(catalog.part(clip, "rightArm"));
        }
        var bat = ((LrAssets) server[1]).displays().iterator().next();
        expected.add(bat.ref());
        expected.add(bat.config().ref());
        for (int mask = 0; mask < 16; mask++) expected.add(catalog.firstPerson(mask));
        for (String id : AnimationRefTable.CARRIERS)
            expected.add(AnimationRefTable.carrier(3, AnimationRefTable.CARRIER + id).ref());
        for (String marker : AnimationRefTable.MARKERS)
            expected.add(AnimationRefTable.carrier(3, marker).ref());
        for (var ref : expected) assertTrue(table.handle(ref) >= 0, String.valueOf(ref));
        // Sorted by U1 tag, then generation, life, content id and digest; digest of the records.
        for (int i = 1; i < table.size(); i++)
            assertTrue(
                    AnimationRefTable.ORDER.compare(table.entry(i - 1), table.entry(i)) < 0,
                    "order at " + i);
        var all = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < table.size(); i++)
            all.writeBytes(AnimationSchema.encode((Record) table.entry(i)));
        assertEquals(Digest.hash(all.toByteArray()), table.digest());
        // Another generation's views never derive this table.
        var next = views(4, false);
        var nextTable =
                AnimationRefTable.derive(
                        4, entries.keySet(), (TaczAssets) next[0], (LrAssets) next[1]);
        assertNotEquals(table.digest(), nextTable.digest());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnimationRefTable.derive(
                                4, entries.keySet(), (TaczAssets) server[0], (LrAssets) null));
        // Refs of another generation or a life never enter.
        assertThrows(IllegalArgumentException.class, () -> AnimationRefTable.of(-1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationRefTable.of(G, List.of(REFS.get(ClipRef.class)[2])));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationRefTable.of(G, List.of(ref(ClipRef.class, G + 1, 0, 1))));
    }

    @Test
    void anOverCapacityGenerationSendsLiteralsOnly() {
        var refs = new ArrayList<ResourceRef>();
        for (int i = 0; i < 4; i++) refs.add(ref(ClipRef.class, G, 0, i + 1));
        var table = AnimationRefTable.of(G, refs, 3);
        assertEquals(0, table.size());
        assertEquals(4, table.derived());
        assertEquals(-1, table.handle(refs.get(0)));
        assertArrayEquals(
                AnimationSchema.encode((Record) refs.get(0)), wire((Record) refs.get(0), table));
        assertEquals(Digest.hash(new byte[0]), table.digest());
        assertEquals(1 << 20, AnimationRefTable.MAX_ENTRIES);
    }

    @Test
    void manifestNamesTheWireEntriesAndRefusesUnknownKeys() {
        var compat = AnimationManifest.compatibility(mod -> "absent");
        assertEquals(
                Digest.hash(AnimationRefTable.compatibility().getBytes(StandardCharsets.UTF_8)),
                compat.get(AnimationManifest.COMPAT + "wire"));
        assertTrue(AnimationRefTable.compatibility().contains("tacz:server_gun_shoot"));
        var entries = new TreeMap<String, Digest>(compat);
        entries.put(AnimationManifest.TACZ_GUN + "tacz:ak47", DIGEST);
        entries.put(AnimationManifest.WIRE_REF_TABLE, DIGEST);
        var manifest = new AnimationManifest(G, policy(), entries);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        manifest.write(buf);
        assertEquals(manifest, AnimationManifest.read(buf));
        buf.release();
        entries.put("wire.other", DIGEST);
        assertThrows(
                IllegalArgumentException.class, () -> new AnimationManifest(G, policy(), entries));
    }
}
