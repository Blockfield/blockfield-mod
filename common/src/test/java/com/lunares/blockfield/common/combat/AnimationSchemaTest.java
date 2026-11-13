package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static com.lunares.blockfield.common.combat.AnimationResources.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class AnimationSchemaTest {
    @Test
    void everyRegisteredLayoutRoundTripsItsTypedFields() {
        assertTrue(AnimationSchema.recordTypes().size() >= 139);
        for (Class<?> type : AnimationSchema.recordTypes()) {
            Record original = (Record) sample(type);
            byte[] encoded = AnimationSchema.encode(original);
            FriendlyByteBuf buf = buffer(encoded);
            try {
                Record decoded = AnimationSchema.readComplete(buf, original.getClass());
                assertArrayEquals(encoded, AnimationSchema.encode(decoded), type.getName());
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void manifestCoversEveryFieldAndClosedEnum() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("docs/review/anim-sync-u1-schema.json")))
            root = root.getParent();
        JsonObject manifest =
                JsonParser.parseString(
                                Files.readString(
                                        root.resolve("docs/review/anim-sync-u1-schema.json")))
                        .getAsJsonObject();
        var records = manifest.getAsJsonArray("records");
        assertEquals(AnimationSchema.recordTypes().size(), records.size());
        for (int i = 0; i < records.size(); i++) {
            JsonObject row = records.get(i).getAsJsonObject();
            Class<?> type = AnimationSchema.recordTypes().get(i);
            assertEquals(
                    type.getName().replace('$', '.'),
                    "com.lunares.blockfield.common.combat." + row.get("name").getAsString());
            FriendlyByteBuf encoded = buffer(AnimationSchema.encode((Record) sample(type)));
            try {
                assertEquals(row.get("tag").getAsInt(), SnapshotWireMath.readUvarInt(encoded));
            } finally {
                encoded.release();
            }
            var fields = row.getAsJsonArray("fields");
            assertEquals(type.getRecordComponents().length, fields.size());
            for (int j = 0; j < fields.size(); j++) {
                var field = fields.get(j).getAsJsonObject();
                var component = type.getRecordComponents()[j];
                assertEquals(field.get("name").getAsString(), component.getName(), type.getName());
                assertEquals(
                        simple(field.get("type").getAsString()),
                        simple(component.getGenericType().getTypeName()),
                        type.getName() + "." + component.getName());
                assertEquals(
                        field.get("type").getAsString().contains("Nullable"),
                        component.isAnnotationPresent(AnimationSchema.Nullable.class),
                        type.getName() + "." + component.getName() + " presence byte");
            }
            assertFalse(row.get("section").getAsString().isBlank());
        }
        Set<String> seen = new HashSet<>();
        for (var value : manifest.getAsJsonArray("enums")) {
            var row = value.getAsJsonObject();
            Class<?> type =
                    Class.forName(
                            "com.lunares.blockfield.common.combat.AnimationTypes$"
                                    + row.get("name").getAsString());
            var values = row.getAsJsonObject("values");
            assertEquals(type.getEnumConstants().length, values.size());
            for (Object entry : type.getEnumConstants())
                assertEquals(
                        values.get(((Enum<?>) entry).name()).getAsInt(),
                        ((AnimationTypes.Tag) entry).id());
            seen.add(type.getSimpleName());
        }
        for (Class<?> type : AnimationTypes.class.getDeclaredClasses())
            if (type.isEnum()) assertTrue(seen.contains(type.getSimpleName()));
        // Any layout change (schema manifest 3: nullable Tacz) must update the pinned digest.
        assertEquals(
                manifest.get("layoutDigest").getAsString(),
                java.util.HexFormat.of().formatHex(AnimationSchema.layoutDigest().bytes()));
    }

    @Test
    void noGunTaczIsAPresenceByteNotAnUnavailableCheckpoint() {
        // Schema manifest 3 (U3 P1-4): a null Tacz is the native no-gun stopAll state.
        var state = sample(AnimationState.class);
        var none = with(state, "tacz", null);
        assertNull(roundTrip(none).tacz());
        var gun = with(state, "tacz", sample(AnimationState.Tacz.class));
        assertEquals(gun, roundTrip(gun));
        assertFalse(Arrays.equals(AnimationSchema.encode(none), AnimationSchema.encode(gun)));
    }

    private static String simple(String type) {
        return type.replaceAll("@[\\w.]+\\s+", "")
                .replace('$', '.')
                .replaceAll("(?:[\\w]+\\.)+", "");
    }

    @Test
    void floatAndDoubleCodecsPreserveAllClassesOfBitsAndRejectNonfiniteEvaluation() {
        var random = new Random(9271);
        int[] edges = {
            0, 0x80000000, 1, -1, 0x7f800000, 0xff800000, 0x7fc01234, 0x7f801234, 0x3f800000
        };
        for (int bits : edges) checkFloat(bits);
        for (int bit = 0; bit < 32; bit++) {
            checkFloat(1 << bit);
            checkFloat(~(1 << bit));
        }
        for (int i = 0; i < 4096; i++) checkFloat(random.nextInt());
        long[] wide = {
            0,
            Long.MIN_VALUE,
            1,
            -1,
            0x7ff0000000000000L,
            0xfff0000000000000L,
            0x7ff8000000001234L,
            0x7ff0000000001234L
        };
        for (long bits : wide) checkDouble(bits);
        for (int bit = 0; bit < 64; bit++) {
            checkDouble(1L << bit);
            checkDouble(~(1L << bit));
        }
        for (int i = 0; i < 4096; i++) checkDouble(random.nextLong());
    }

    private static void checkFloat(int bits) {
        var value = new AnimationState.FloatValue(Float.intBitsToFloat(bits));
        assertEquals(bits, Float.floatToRawIntBits(roundTrip(value).value()));
        if (Float.isFinite(value.value())) AnimationSchema.validateFinite(value);
        else
            assertThrows(
                    IllegalArgumentException.class, () -> AnimationSchema.validateFinite(value));
    }

    private static void checkDouble(long bits) {
        var value = new AnimationState.DoubleValue(Double.longBitsToDouble(bits));
        assertEquals(bits, Double.doubleToRawLongBits(roundTrip(value).value()));
        if (Double.isFinite(value.value())) AnimationSchema.validateFinite(value);
        else
            assertThrows(
                    IllegalArgumentException.class, () -> AnimationSchema.validateFinite(value));
    }

    @Test
    void signedIntegerAndEveryEnumValueRoundTrip() throws Exception {
        for (int value : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
            assertEquals(value, roundTrip(new AnimationState.IntValue(value)).value());
        for (long value : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE})
            assertEquals(value, roundTrip(new AnimationState.LongValue(value)).value());
        for (Class<?> type : AnimationSchema.recordTypes()) {
            Record original = (Record) sample(type);
            var fields = type.getRecordComponents();
            for (var field : fields)
                if (field.getType().isEnum())
                    for (Object value : field.getType().getEnumConstants()) {
                        Record changed = with(original, field.getName(), value);
                        assertArrayEquals(
                                AnimationSchema.encode(changed),
                                AnimationSchema.encode(roundTrip(changed)),
                                type.getName() + "." + field.getName());
                    }
        }
    }

    @Test
    void lrIdleNullAttackActionAndMissingConfigRoundTripExactly() {
        // P1-1: the native null idle state is exactly representable; no "" sentinel exists.
        var idle = sample(AnimationState.Lr.class);
        assertNull(idle.attackAction());
        assertEquals(idle, roundTrip(idle));
        var attack =
                with(
                        with(
                                with(idle, "config", new ConfigRef(0, 0, 1, DIGEST)),
                                "display",
                                new DefinitionRef(0, 0, 2, DIGEST)),
                        "attackAction",
                        "slash");
        assertEquals("slash", roundTrip(attack).attackAction());
        assertFalse(Arrays.equals(AnimationSchema.encode(idle), AnimationSchema.encode(attack)));
        // P1-4: a missing-config tick carries explicit null refs, never reused last-config bytes.
        var missing = with(with(idle, "config", null), "display", null);
        assertNull(roundTrip(missing).config());
        assertNull(roundTrip(missing).display());
        assertNull(roundTrip(missing).attackAction());
    }

    @Test
    void diveHasNoSpeedFieldAndZiplineGravityRoundTrips() {
        // P1-2: pinned ParCool bf7 has no Dive speed producer or reader; the schema carries none.
        var names =
                Arrays.stream(AnimationState.DiveInput.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();
        assertEquals(
                List.of(
                        "justJumped",
                        "initialYVelocityOfLastJump",
                        "playerYSpeedOld",
                        "playerYSpeed",
                        "fallingTick",
                        "fromSkyDive",
                        "notLandingTick",
                        "waterEntry"),
                names);
        var dive = sample(AnimationState.DiveInput.class);
        assertEquals(dive, roundTrip(dive));
        var zipline =
                with(sample(AnimationState.ZiplineInput.class), "gravity", 0.080000000000000002);
        assertEquals(
                Double.doubleToRawLongBits(0.080000000000000002),
                Double.doubleToRawLongBits(roundTrip(zipline).gravity()));
        AnimationSchema.validateFinite(roundTrip(zipline));
    }

    @Test
    void immutableValuesNeverExposeMutableListsOrBytes() {
        byte[] bytes = {1, 2};
        var stored = new Bytes(bytes);
        bytes[0] = 9;
        stored.value()[1] = 8;
        assertArrayEquals(new byte[] {1, 2}, stored.value());
        var nodes = new ArrayList<AnimationGraphState.Node>();
        var graph = new AnimationGraphState(List.of(), nodes);
        nodes.add(node(1, new AnimationGraphState.Container(null)));
        assertTrue(graph.nodes().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> graph.nodes().add(node(1, new AnimationGraphState.Container(null))));
    }

    @Test
    void malformedTypesBooleansLengthsAndTrailingBytesAreRejected() {
        byte[] valid = AnimationSchema.encode(new AnimationState.Option(false, 127));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            FriendlyByteBuf b = buffer(truncated);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AnimationSchema.readComplete(b, AnimationState.Option.class));
            } finally {
                b.release();
            }
        }
        FriendlyByteBuf wrong = buffer(valid);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AnimationSchema.readComplete(wrong, AnimationState.FloatValue.class));
        } finally {
            wrong.release();
        }
        FriendlyByteBuf bool = buffer(valid.clone());
        try {
            SnapshotWireMath.readUvarInt(bool);
            bool.setByte(bool.readerIndex(), 2);
            bool.readerIndex(0);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AnimationSchema.readComplete(bool, AnimationState.Option.class));
        } finally {
            bool.release();
        }
        FriendlyByteBuf trailing = buffer(Arrays.copyOf(valid, valid.length + 1));
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AnimationSchema.readComplete(trailing, AnimationState.Option.class));
        } finally {
            trailing.release();
        }
        FriendlyByteBuf unknown = buffer(new byte[] {(byte) 0xff, 0x7f});
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AnimationSchema.readComplete(unknown, AnimationState.Option.class));
        } finally {
            unknown.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnimationSchema.encodeBounded(
                                b ->
                                        b.writeBytes(
                                                new byte[AnimationSchema.MAX_PAYLOAD_BYTES + 1])));
    }

    @Test
    void preloadedAssetsCanExceedWireCapacityAndStringsUseThePayloadBound() {
        Store store = new Store();
        var ref = new ClipRef(1, 0, 1, DIGEST);
        var definition =
                new Definition(
                        ref, new Bytes(new byte[AnimationSchema.MAX_PAYLOAD_BYTES + 1]), List.of());
        store.installAll(List.of(definition));
        assertEquals(AnimationSchema.MAX_PAYLOAD_BYTES + 1L, store.retainedBytes());
        assertThrows(IllegalArgumentException.class, () -> AnimationSchema.encode(definition));
        var metadata = new AnimationGraphState.MetadataOverlay("x".repeat(32768), false);
        assertEquals(metadata, roundTrip(metadata));
    }

    @Test
    void newContinuationPinsExplicitOldAssetsAcrossActivation() {
        Store store = new Store();
        var clip = new ClipRef(1, 0, 5, DIGEST);
        var seed = new SeedRef(2, 0, 6, DIGEST);
        store.installAll(
                List.of(
                        new Definition(clip, new Bytes(new byte[] {1}), List.of()),
                        new Definition(seed, new Bytes(new byte[] {2}), List.of(clip))));
        try (var lease = store.pin(List.of(seed))) {
            store.retireUnpinned(1);
            assertEquals(1, store.get(clip).value().value()[0]);
            assertEquals(2, store.size());
        }
        store.retireUnpinned(1);
        assertThrows(IllegalArgumentException.class, () -> store.get(clip));
    }

    @Test
    void dependencyPublicationAndRetirementAreAtomicAndGenerationScoped() {
        Store store = new Store();
        EquipmentRef old = new EquipmentRef(1, 0, 5, DIGEST),
                fresh = new EquipmentRef(2, 0, 5, DIGEST);
        ItemRef item = new ItemRef(1, 0, 6, DIGEST);
        var equipment = new Definition(old, new Bytes(new byte[] {1}), List.of(item));
        assertThrows(IllegalArgumentException.class, () -> store.installAll(List.of(equipment)));
        assertEquals(0, store.size());
        store.installAll(
                List.of(
                        equipment,
                        new Definition(item, new Bytes(new byte[] {2}), List.of()),
                        new Definition(fresh, new Bytes(new byte[] {3}), List.of())));
        try (var lease = store.pin(List.of(old))) {
            store.retireUnpinned(1);
            assertEquals(3, store.size());
            assertEquals(1, store.get(old).value().value()[0]);
            assertEquals(3, store.get(fresh).value().value()[0]);
        }
        store.retireUnpinned(1);
        assertEquals(1, store.size());
        assertThrows(IllegalArgumentException.class, () -> store.get(old));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.installAll(
                                List.of(
                                        new Definition(
                                                fresh, new Bytes(new byte[] {99}), List.of()))));
        assertEquals(3, store.get(fresh).value().value()[0]);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.installAll(
                                List.of(
                                        new Definition(old, new Bytes(new byte[0]), List.of(item)),
                                        new Definition(
                                                item, new Bytes(new byte[0]), List.of(old)))));
        assertEquals(1, store.size());
    }

    @Test
    void referencesIterateInFirstOccurrenceWalkOrder() {
        // Set.copyOf/Set.of iterate from a per-JVM random start (SetN SALT32L): returning one
        // here scrambled batch definition order run to run (U10 variance). Pin walk order.
        var digest = new Digest(1, 2, 3, 4);
        var refs = new ArrayList<ResourceRef>();
        for (long i = 1; i <= 12; i++) refs.add(new DefinitionRef(1, 7, i, digest));
        var root = new Definition(refs.get(0), new Bytes(new byte[] {9}), List.copyOf(refs));
        assertEquals(refs, List.copyOf(AnimationSchema.references(root)));
        var dupes = new ArrayList<>(refs);
        dupes.addAll(refs);
        assertEquals(
                refs,
                List.copyOf(
                        AnimationSchema.references(
                                new Definition(refs.get(0), new Bytes(new byte[] {9}), dupes))));
    }
}
