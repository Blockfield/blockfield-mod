package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.SnapshotWireMath.*;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.lang.annotation.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Closed ADR4.1 record layouts. No class names, native objects or executable values are decoded.
 */
public final class AnimationSchema {
    private AnimationSchema() {}

    public static final int VERSION = 1;
    public static final int MAX_VICTIMS = 4096;
    public static final int MAX_NODES = 4096;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    public static final int MAX_STRING_BYTES = MAX_PAYLOAD_BYTES;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Nullable {}

    /**
     * One registered record: its components in declaration order, resolved once, so the per-value
     * walks never touch reflection metadata.
     */
    private record Layout(
            int tag,
            Class<?> type,
            RecordComponent[] fields,
            Constructor<?> constructor,
            Part[] parts,
            MethodHandle factory) {}

    /** {@code float} accessor without boxing. */
    interface FloatGetter {
        float get(Object record);
    }

    /**
     * One component: its generated accessor (boxed, and unboxed for primitives), generic type,
     * nullability and role. {@code inert} references (String, enum, Bytes) can hold neither a
     * nonfinite number nor a resource ref, so the semantic walks only depth-check them.
     */
    private static final class Part {
        static final int REF = 0, BOOLEAN = 1, INT = 2, LONG = 3, FLOAT = 4, DOUBLE = 5, OTHER = 6;

        final String name;
        final Type type;
        final boolean nullable;
        final int kind;
        final boolean inert;
        final java.util.function.Function<Object, Object> get;
        final java.util.function.Predicate<Object> getBoolean;
        final java.util.function.ToIntFunction<Object> getInt;
        final java.util.function.ToLongFunction<Object> getLong;
        final FloatGetter getFloat;
        final java.util.function.ToDoubleFunction<Object> getDouble;

        @SuppressWarnings("unchecked")
        Part(MethodHandles.Lookup lookup, Class<?> owner, RecordComponent field) throws Throwable {
            name = field.getName();
            type = field.getGenericType();
            nullable = field.isAnnotationPresent(Nullable.class);
            Class<?> raw = field.getType();
            kind =
                    raw == boolean.class
                            ? BOOLEAN
                            : raw == int.class
                                    ? INT
                                    : raw == long.class
                                            ? LONG
                                            : raw == float.class
                                                    ? FLOAT
                                                    : raw == double.class
                                                            ? DOUBLE
                                                            : raw.isPrimitive() ? OTHER : REF;
            inert = raw == String.class || raw.isEnum() || raw == Bytes.class;
            MethodHandle accessor = lookup.unreflect(field.getAccessor());
            get =
                    lambda(
                            lookup,
                            java.util.function.Function.class,
                            "apply",
                            MethodType.methodType(Object.class, Object.class),
                            accessor,
                            MethodType.methodType(
                                    MethodType.methodType(raw).wrap().returnType(), owner));
            getBoolean =
                    kind == BOOLEAN
                            ? lambda(
                                    lookup,
                                    java.util.function.Predicate.class,
                                    "test",
                                    MethodType.methodType(boolean.class, Object.class),
                                    accessor,
                                    MethodType.methodType(boolean.class, owner))
                            : null;
            getInt =
                    kind == INT
                            ? lambda(
                                    lookup,
                                    java.util.function.ToIntFunction.class,
                                    "applyAsInt",
                                    MethodType.methodType(int.class, Object.class),
                                    accessor,
                                    MethodType.methodType(int.class, owner))
                            : null;
            getLong =
                    kind == LONG
                            ? lambda(
                                    lookup,
                                    java.util.function.ToLongFunction.class,
                                    "applyAsLong",
                                    MethodType.methodType(long.class, Object.class),
                                    accessor,
                                    MethodType.methodType(long.class, owner))
                            : null;
            getFloat =
                    kind == FLOAT
                            ? lambda(
                                    lookup,
                                    FloatGetter.class,
                                    "get",
                                    MethodType.methodType(float.class, Object.class),
                                    accessor,
                                    MethodType.methodType(float.class, owner))
                            : null;
            getDouble =
                    kind == DOUBLE
                            ? lambda(
                                    lookup,
                                    java.util.function.ToDoubleFunction.class,
                                    "applyAsDouble",
                                    MethodType.methodType(double.class, Object.class),
                                    accessor,
                                    MethodType.methodType(double.class, owner))
                            : null;
        }

        private static <T> T lambda(
                MethodHandles.Lookup lookup,
                Class<?> api,
                String method,
                MethodType erased,
                MethodHandle implementation,
                MethodType instantiated)
                throws Throwable {
            @SuppressWarnings("unchecked")
            T value =
                    (T)
                            java.lang.invoke.LambdaMetafactory.metafactory(
                                            lookup,
                                            method,
                                            MethodType.methodType(api),
                                            erased,
                                            implementation,
                                            instantiated)
                                    .getTarget()
                                    .invoke();
            return value;
        }
    }

    /** Layout by record class, without a hash lookup per value. */
    private static final ClassValue<Layout> LAYOUTS =
            new ClassValue<>() {
                @Override
                protected Layout computeValue(Class<?> type) {
                    return BY_CLASS.get(type);
                }
            };

    /** Enum constants by type, cloned once ({@code getEnumConstants} copies on every call). */
    private static final ClassValue<Object[]> CONSTANTS =
            new ClassValue<>() {
                @Override
                protected Object[] computeValue(Class<?> type) {
                    return type.getEnumConstants();
                }
            };

    private static final Map<Integer, Layout> BY_ID = new LinkedHashMap<>();
    private static final Map<Class<?>, Layout> BY_CLASS = new LinkedHashMap<>();

    static {
        register(1, AnimationTypes.Time.class);
        register(2, AnimationResources.Digest.class);
        register(3, AnimationResources.EquipmentRef.class);
        register(4, AnimationResources.ItemRef.class);
        register(5, AnimationResources.ClipRef.class);
        register(6, AnimationResources.ConfigRef.class);
        register(7, AnimationResources.DefinitionRef.class);
        register(8, AnimationResources.AttachmentRef.class);
        register(9, AnimationResources.FirstPersonRef.class);
        register(10, AnimationResources.MetadataRef.class);
        register(11, AnimationResources.InputRef.class);
        register(12, AnimationResources.VehicleRef.class);
        register(13, AnimationResources.FunctionRef.class);
        register(14, AnimationResources.SeedRef.class);
        register(15, AnimationResources.Definition.class);
        register(16, AnimationState.class);
        register(17, AnimationState.Identity.class);
        register(18, AnimationState.Vec3.class);
        register(19, AnimationState.Vec3f.class);
        register(20, AnimationState.Angles.class);
        register(21, AnimationState.Walk.class);
        register(22, AnimationState.VehicleAngles.class);
        register(23, AnimationState.Placement.class);
        register(24, AnimationState.Attack.class);
        register(25, AnimationState.Vital.class);
        register(26, AnimationState.Flags.class);
        register(27, AnimationState.Size.class);
        register(28, AnimationState.ItemUse.class);
        register(29, AnimationState.Vanilla.class);
        register(30, AnimationState.DimensionsValue.class);
        register(31, AnimationState.Stance.class);
        register(32, AnimationState.Equipment.class);
        register(33, AnimationState.Tacz.class);
        register(34, AnimationState.Lr.class);
        register(35, AnimationState.PendingOperation.class);
        register(36, AnimationState.Option.class);
        register(37, AnimationState.Passive.class);
        register(38, AnimationState.Grid4.class);
        register(39, AnimationState.HangGrid.class);
        register(40, AnimationState.DiveGrid.class);
        register(41, AnimationState.ParCool.class);
        register(42, AnimationState.ActionState.class);
        register(43, AnimationState.AcceptedStart.class);
        register(44, AnimationState.DurationInput.class);
        register(45, AnimationState.SlideInput.class);
        register(46, AnimationState.HorizontalWallRunInput.class);
        register(47, AnimationState.VerticalWallRunInput.class);
        register(48, AnimationState.WallSlideInput.class);
        register(49, AnimationState.HangInput.class);
        register(50, AnimationState.ClingInput.class);
        register(51, AnimationState.DiveInput.class);
        register(52, AnimationState.ChargeInput.class);
        register(53, AnimationState.ZiplineInput.class);
        register(54, AnimationState.HideInput.class);
        register(55, AnimationState.IntentInput.class);
        register(56, AnimationState.PassiveInput.class);
        register(57, AnimationState.CompatibilityInput.class);
        register(58, AnimationState.NoInput.class);
        register(59, AnimationState.Create.class);
        register(60, AnimationState.Sbw.class);
        register(61, AnimationState.ReadSlot.class);
        register(62, AnimationState.BooleanValue.class);
        register(63, AnimationState.IntValue.class);
        register(64, AnimationState.LongValue.class);
        register(65, AnimationState.FloatValue.class);
        register(66, AnimationState.DoubleValue.class);
        register(67, AnimationState.EnumValue.class);
        register(68, AnimationState.RefValue.class);
        register(69, AnimationState.VectorValue.class);
        register(70, AnimationState.QuaternionValue.class);
        register(71, AnimationState.MatrixValue.class);
        register(72, AnimationState.DoubleQuaternionValue.class);
        register(73, AnimationState.DoubleMatrixValue.class);
        register(74, AnimationState.VehicleRead.class);
        register(75, AnimationGraphState.class);
        register(76, AnimationGraphState.NodeRef.class);
        register(77, AnimationGraphState.Root.class);
        register(78, AnimationGraphState.Node.class);
        register(79, AnimationGraphState.Stack.class);
        register(80, AnimationGraphState.Container.class);
        register(81, AnimationGraphState.ModifierLayer.class);
        register(82, AnimationGraphState.Modifier.class);
        register(83, AnimationGraphState.KeyframePlayer.class);
        register(84, AnimationGraphState.Fade.class);
        register(85, AnimationGraphState.Adjustment.class);
        register(86, AnimationGraphState.Speed.class);
        register(87, AnimationGraphState.LrFrozen.class);
        register(88, AnimationGraphState.LrForcedFade.class);
        register(89, AnimationGraphState.Animator.class);
        register(90, AnimationGraphState.PartState.class);
        register(91, AnimationGraphState.PartModifier.class);
        register(92, AnimationGraphState.BackwardWallJump.class);
        register(93, AnimationGraphState.CatLeap.class);
        register(94, AnimationGraphState.ChargeJump.class);
        register(95, AnimationGraphState.ClimbUp.class);
        register(96, AnimationGraphState.ClingToCliff.class);
        register(97, AnimationGraphState.Crawl.class);
        register(98, AnimationGraphState.DiveHost.class);
        register(99, AnimationGraphState.DiveIntoWater.class);
        register(100, AnimationGraphState.Dodge.class);
        register(101, AnimationGraphState.FastRunning.class);
        register(102, AnimationGraphState.FastSwim.class);
        register(103, AnimationGraphState.Flipping.class);
        register(104, AnimationGraphState.HideInBlock.class);
        register(105, AnimationGraphState.HorizontalWallRun.class);
        register(106, AnimationGraphState.JumpCharging.class);
        register(107, AnimationGraphState.JumpFromBar.class);
        register(108, AnimationGraphState.Hang.class);
        register(109, AnimationGraphState.KongVault.class);
        register(110, AnimationGraphState.RideZipline.class);
        register(111, AnimationGraphState.Roll.class);
        register(112, AnimationGraphState.Sliding.class);
        register(113, AnimationGraphState.SpeedVault.class);
        register(114, AnimationGraphState.Tap.class);
        register(115, AnimationGraphState.VerticalWallRun.class);
        register(116, AnimationGraphState.WallJump.class);
        register(117, AnimationGraphState.WallSlide.class);
        register(118, AnimationGraphState.DiveChild.class);
        register(119, AnimationGraphState.SkyDiveChild.class);
        register(120, AnimationEvent.class);
        register(121, AnimationEvent.EventId.class);
        register(122, AnimationEvent.NativePayload.class);
        register(123, AnimationEvent.Patch.class);
        register(124, AnimationEvent.Decision.class);
        register(125, AnimationEvent.ObserverSeed.class);
        register(126, AnimationStartReport.class);
        register(127, AnimationStartReport.StartKey.class);
        register(128, AnimationStartReport.Victim.class);
        register(129, AnimationStartReport.Start.class);
        register(130, AnimationStartReport.ObserverApply.class);
        register(131, AnimationStartReport.CanonicalTick.class);
        register(132, AnimationStartReport.ProjectedSuccessor.class);
        register(133, AnimationPolicy.class);
        register(134, AnimationRecipe.class);
        register(135, AnimationRecipe.SnapshotRecord.class);
        register(136, AnimationRecipe.Batch.class);
        register(137, AnimationResolver.ResolvedInputs.class);
        register(138, AnimationGraphState.Alias.class);
        register(139, AnimationGraphState.MetadataOverlay.class);
        register(140, AnimationResolver.RecipeIdentity.class);
        register(141, AnimationResolver.PresentationClosure.class);
        register(142, AnimationStartReport.Application.class);
    }

    /**
     * Thread-confined encode buffers, one per nesting level (a writer may encode a nested value),
     * each bounded exactly like a fresh {@code Unpooled.buffer(256, MAX_PAYLOAD_BYTES)}; reusing
     * them only spares regrowing the same capacity on every value.
     */
    private static final ThreadLocal<ArrayList<FriendlyByteBuf>> SCRATCH =
            ThreadLocal.withInitial(ArrayList::new);

    private static final ThreadLocal<int[]> SCRATCH_DEPTH =
            ThreadLocal.withInitial(() -> new int[1]);

    public static byte[] encodeBounded(java.util.function.Consumer<FriendlyByteBuf> writer) {
        var buffers = SCRATCH.get();
        int[] depth = SCRATCH_DEPTH.get();
        if (depth[0] == buffers.size())
            buffers.add(
                    new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(256, MAX_PAYLOAD_BYTES)));
        FriendlyByteBuf scratch = buffers.get(depth[0]++);
        scratch.clear();
        try {
            writer.accept(scratch);
            byte[] bytes = new byte[scratch.readableBytes()];
            scratch.readBytes(bytes);
            return bytes;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("report_capacity", e);
        } finally {
            scratch.clear();
            depth[0]--;
        }
    }

    public static byte[] encode(Record value) {
        return encodeBounded(buf -> write(buf, value));
    }

    private static void register(int tag, Class<?> type) {
        try {
            RecordComponent[] fields = type.getRecordComponents();
            Class<?>[] parameters =
                    Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new);
            var lookup = MethodHandles.lookup();
            var parts = new Part[fields.length];
            for (int i = 0; i < fields.length; i++) parts[i] = new Part(lookup, type, fields[i]);
            Constructor<?> constructor = type.getConstructor(parameters);
            MethodHandle factory =
                    lookup.unreflectConstructor(constructor)
                            .asType(MethodType.genericMethodType(fields.length))
                            .asSpreader(Object[].class, fields.length);
            Layout layout = new Layout(tag, type, fields, constructor, parts, factory);
            if (BY_ID.put(tag, layout) != null || BY_CLASS.put(type, layout) != null)
                throw new AssertionError("Duplicate schema tag");
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void count(int value, int maximum, String what) {
        if (value < 0 || value > maximum)
            throw new IllegalArgumentException(what + " capacity: " + value);
    }

    public static void nonnegative(long value, String what) {
        if (value < 0) throw new IllegalArgumentException("Negative " + what);
    }

    public static void require(FriendlyByteBuf buf, int length) {
        if (length < 0 || length > buf.readableBytes())
            throw new IllegalArgumentException("Truncated animation value");
    }

    public static int readCount(FriendlyByteBuf buf, int max, int minimumBytes) {
        int count = readUvarInt(buf);
        count(count, max, "count");
        if ((long) count * minimumBytes > buf.readableBytes())
            throw new IllegalArgumentException("Truncated animation collection");
        return count;
    }

    public static <E extends Enum<E> & Tag> E tag(Class<E> type, int id) {
        for (Object value : CONSTANTS.get(type))
            if (((Tag) value).id() == id) return type.cast(value);
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " tag " + id);
    }

    public static void writeTime(FriendlyByteBuf buf, Time time) {
        writeUvarLong(buf, time.tick());
        buf.writeShort(time.alpha());
    }

    public static Time readTime(FriendlyByteBuf buf) {
        long tick = readUvarLong(buf);
        require(buf, 2);
        return new Time(tick, buf.readUnsignedShort());
    }

    public static void writeDigest(FriendlyByteBuf buf, Digest digest) {
        buf.writeBytes(digest.bytes());
    }

    public static Digest readDigest(FriendlyByteBuf buf) {
        require(buf, 32);
        byte[] value = new byte[32];
        buf.readBytes(value);
        return Digest.of(value);
    }

    /**
     * A record's source declaration and this checked-in registry are its versioned wire layout.
     * Always canonical: digests, validation and C2S read and fill only canonical shared encodings.
     */
    public static void write(FriendlyByteBuf buf, Record value) {
        int start = buf.writerIndex();
        var splices = SPLICES.get();
        var mode = splices.table;
        splices.table = null;
        try {
            writeValue(buf, value, value.getClass(), 0, splices.values.isEmpty() ? null : splices);
        } finally {
            splices.table = mode;
        }
        count(buf.writerIndex() - start, MAX_PAYLOAD_BYTES, "animation payload");
    }

    /**
     * ADR4.2 §5 S2C wire writer: {@code value} with every ref of {@code table} as {@code uvarint 0,
     * uvarint handle}; a null table (literal-only transaction) writes the canonical bytes. No
     * per-call 256 KiB count: the envelope's transaction bound applies. Shared encodings are kept
     * per mode, so bytes interned for one table never reach another table or a canonical digest.
     */
    public static void writeWire(FriendlyByteBuf buf, Record value, AnimationRefTable table) {
        var splices = SPLICES.get();
        var mode = splices.table;
        splices.table = table;
        try {
            writeValue(buf, value, value.getClass(), 0, splices);
        } finally {
            splices.table = mode;
        }
    }

    /**
     * {@link #writeWire} reading shared encodings only from the frozen {@code shared} map (ADR4.2
     * §13.2: built on the main thread, then read by encode tasks; a missing entry is written in
     * full and never stored).
     */
    public static void writeWire(
            FriendlyByteBuf buf, Record value, AnimationRefTable table, SharedEncodings shared) {
        var splices = new Splices(shared.values, true);
        splices.table = table;
        writeValue(buf, value, value.getClass(), 0, splices);
    }

    /**
     * Reads one value written by {@link #writeWire} with {@code table} (null: a literal-only
     * transaction, where tag 0 is rejected like any unknown tag). Per-component bounds stay; the
     * caller bounds the transaction.
     */
    public static <T extends Record> T readWire(
            FriendlyByteBuf buf, Class<T> expected, AnimationRefTable table) {
        return expected.cast(readValue(buf, expected, 0, table));
    }

    // --- Shared encodings (UOPT) ---

    /**
     * Immutable values whose bytes this thread reuses within one scope (the server's batches of one
     * tick: every observer's stamped checkpoint holds its target's same components). A value's
     * encoding is a pure function of the value, so later writes copy the first write's bytes; each
     * copy repeats the position's type check and the depth bound of the deepest level the first
     * write reached.
     */
    private static final class Splices {
        long scope = Long.MIN_VALUE;
        final IdentityHashMap<Object, Spliced> values;
        int deepest;

        /** The current write's mode: null canonical, else the S2C table (ADR4.2 §5.2). */
        AnimationRefTable table;

        /** A frozen {@link SharedEncodings} map: read, never filled. */
        final boolean frozen;

        Splices() {
            this(new IdentityHashMap<>(), false);
        }

        Splices(IdentityHashMap<Object, Spliced> values, boolean frozen) {
            this.values = values;
            this.frozen = frozen;
        }
    }

    /** One shared value: its encoding per mode key (canonical or a table identity). */
    private static final class Spliced {
        Encoding first;
        long scope;

        Encoding find(AnimationRefTable table) {
            for (var entry = first; entry != null; entry = entry.next)
                if (entry.table == table) return entry;
            return null;
        }
    }

    private static final class Encoding {
        final AnimationRefTable table;
        final Encoding next;
        Type type;
        byte[] bytes;
        int depth;

        Encoding(AnimationRefTable table, Encoding next) {
            this.table = table;
            this.next = next;
        }
    }

    /**
     * ADR4.2 §13.2: an immutable map of shared encodings, keyed by value identity and mode, that
     * encode tasks read concurrently. Build it on one thread: {@link Builder#share} registers
     * immutable values and {@link Builder#prime} writes a root in one mode, recording each
     * registered value's bytes at its first position exactly as the serial path does.
     */
    public static final class SharedEncodings {
        private final IdentityHashMap<Object, Spliced> values;

        private SharedEncodings(IdentityHashMap<Object, Spliced> values) {
            this.values = values;
        }

        public static Builder builder() {
            return new Builder();
        }

        /** Entries of every map; the first map holding a value wins. */
        public static SharedEncodings merge(Collection<SharedEncodings> maps) {
            var values = new IdentityHashMap<Object, Spliced>();
            for (var map : maps) map.values.forEach(values::putIfAbsent);
            return new SharedEncodings(values);
        }

        /** Modes recorded for {@code value} (tests: the per-table cache separation). */
        int modes(Object value) {
            return AnimationSchema.modes(values.get(value));
        }

        public static final class Builder {
            private IdentityHashMap<Object, Spliced> values = new IdentityHashMap<>();

            public Builder share(Object value) {
                if (value != null) values.computeIfAbsent(value, k -> new Spliced());
                return this;
            }

            public Builder prime(Record root, AnimationRefTable table) {
                var splices = new Splices(values, false);
                splices.table = table;
                var scratch = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    writeValue(scratch, root, root.getClass(), 0, splices);
                } finally {
                    scratch.release();
                }
                return this;
            }

            /** Freezes the map; the builder cannot change it afterwards. */
            public SharedEncodings build() {
                var frozen = values;
                values = null;
                return new SharedEncodings(frozen);
            }
        }
    }

    private static int modes(Spliced shared) {
        int modes = 0;
        if (shared != null)
            for (var entry = shared.first; entry != null; entry = entry.next)
                if (entry.bytes != null) modes++;
        return modes;
    }

    /** Modes this thread has recorded for {@code value} (tests). */
    static int sharedModes(Object value) {
        return modes(SPLICES.get().values.get(value));
    }

    /**
     * Scopes a shared value stays reusable after its last registration: receipt validation
     * re-encodes the stamped endpoints of the last few sent ticks.
     */
    private static final int SPLICE_SCOPES = 8;

    private static final ThreadLocal<Splices> SPLICES = ThreadLocal.withInitial(Splices::new);

    /**
     * Lets this thread reuse {@code value}'s encoding for {@link #SPLICE_SCOPES} scopes after the
     * latest one that registered it. The caller guarantees the value is immutable (decoded retained
     * state).
     */
    public static void shareEncoding(long scope, Object value) {
        if (value == null) return;
        var splices = SPLICES.get();
        if (splices.scope != scope) {
            splices.values
                    .values()
                    .removeIf(
                            shared ->
                                    shared.scope > scope || shared.scope <= scope - SPLICE_SCOPES);
            splices.scope = scope;
        }
        splices.values.computeIfAbsent(value, k -> new Spliced()).scope = scope;
    }

    // --- Per-instance memos of immutable values (UOPT; set/digest uses only) ---

    private static final ThreadLocal<IdentityHashMap<Object, Digest>> DIGESTS =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /**
     * {@code Digest.hash(encode(value))}, remembered per instance for the cached immutable types
     * (checkpoints, seeds, graphs and their components); computed for any other value.
     */
    public static Digest digest(Record value) {
        if (!CACHED_FINITE.contains(value.getClass())) return Digest.hash(encode(value));
        var digests = DIGESTS.get();
        var known = digests.get(value);
        if (known != null) return known;
        var digest = Digest.hash(encode(value));
        // ponytail: cleared when full (bounded memory); an LRU if hit rates ever matter.
        if (digests.size() >= 1024) digests.clear();
        digests.put(value, digest);
        return digest;
    }

    private static final ThreadLocal<IdentityHashMap<Object, Set<ResourceRef>>> REFERENCES =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /** {@link #references}, remembered per instance for the cached immutable types. */
    public static Set<ResourceRef> rememberedReferences(Record record) {
        if (!CACHED_FINITE.contains(record.getClass())) return references(record);
        var known = REFERENCES.get();
        var refs = known.get(record);
        if (refs != null) return refs;
        refs = references(record);
        if (known.size() >= 1024) known.clear();
        known.put(record, refs);
        return refs;
    }

    private static void writeShared(
            FriendlyByteBuf buf,
            Object value,
            Type expected,
            int depth,
            Splices splices,
            Spliced shared) {
        Encoding entry = shared.find(splices.table);
        if (entry != null
                && entry.bytes != null
                && entry.type.equals(expected)
                && depth + entry.depth <= MAX_DEPTH) {
            buf.writeBytes(entry.bytes);
            splices.deepest = Math.max(splices.deepest, depth + entry.depth);
            return;
        }
        int start = buf.writerIndex();
        int outer = splices.deepest;
        splices.deepest = depth;
        writeBody(buf, value, expected, depth, splices);
        int reach = splices.deepest;
        splices.deepest = Math.max(outer, reach);
        if (splices.frozen) return;
        if (entry == null) shared.first = entry = new Encoding(splices.table, shared.first);
        if (entry.bytes == null) {
            byte[] bytes = new byte[buf.writerIndex() - start];
            buf.getBytes(start, bytes);
            entry.type = expected;
            entry.depth = reach - depth;
            entry.bytes = bytes;
        }
    }

    public static <T extends Record> T read(FriendlyByteBuf buf, Class<T> expected) {
        count(buf.readableBytes(), MAX_PAYLOAD_BYTES, "animation payload");
        return expected.cast(readValue(buf, expected, 0, null));
    }

    public static <T extends Record> T readComplete(FriendlyByteBuf buf, Class<T> expected) {
        T value = read(buf, expected);
        if (buf.isReadable()) throw new IllegalArgumentException("Trailing animation bytes");
        return value;
    }

    private static void writeValue(
            FriendlyByteBuf buf, Object value, Type expected, int depth, Splices splices) {
        count(depth, MAX_DEPTH, "record depth");
        if (splices != null) {
            if (depth > splices.deepest) splices.deepest = depth;
            if (splices.table != null && value instanceof ResourceRef ref) {
                int handle = splices.table.handle(ref);
                if (handle >= 0 && expected instanceof Class<?> type && type.isInstance(ref)) {
                    // The canonical record's depth checks, so both forms accept the same values.
                    int reach = depth + REF_DEPTH.get(ref.getClass());
                    count(reach, MAX_DEPTH, "record depth");
                    if (reach > splices.deepest) splices.deepest = reach;
                    writeUvarInt(buf, HANDLE);
                    writeUvarInt(buf, handle);
                    return;
                }
            }
            Spliced shared = splices.values.get(value);
            if (shared != null) {
                writeShared(buf, value, expected, depth, splices, shared);
                return;
            }
        }
        writeBody(buf, value, expected, depth, splices);
    }

    private static void writeBody(
            FriendlyByteBuf buf, Object value, Type expected, int depth, Splices splices) {
        if (expected instanceof ParameterizedType p && p.getRawType() == List.class) {
            List<?> list = (List<?>) value;
            count(list.size(), MAX_NODES, "list");
            writeUvarInt(buf, list.size());
            Type element = p.getActualTypeArguments()[0];
            for (Object entry : list)
                writeValue(buf, Objects.requireNonNull(entry), element, depth + 1, splices);
            return;
        }
        Class<?> type = (Class<?>) expected;
        if (type == boolean.class) {
            buf.writeByte((boolean) value ? 1 : 0);
            return;
        }
        if (type == int.class) {
            buf.writeInt((int) value);
            return;
        }
        if (type == long.class) {
            buf.writeLong((long) value);
            return;
        }
        if (type == float.class) {
            buf.writeInt(Float.floatToRawIntBits((float) value));
            return;
        }
        if (type == double.class) {
            buf.writeLong(Double.doubleToRawLongBits((double) value));
            return;
        }
        if (type == String.class) {
            byte[] text = ((String) value).getBytes(StandardCharsets.UTF_8);
            count(text.length, MAX_STRING_BYTES, "string");
            writeUvarInt(buf, text.length);
            buf.writeBytes(text);
            return;
        }
        if (type == Bytes.class) {
            byte[] bytes = ((Bytes) value).value();
            writeUvarInt(buf, bytes.length);
            buf.writeBytes(bytes);
            return;
        }
        if (type.isEnum()) {
            buf.writeByte(((Tag) value).id());
            return;
        }
        Layout layout = LAYOUTS.get(value.getClass());
        if (layout == null || !type.isInstance(value))
            throw new IllegalArgumentException("Unregistered animation value");
        writeUvarInt(buf, layout.tag());
        for (Part part : layout.parts()) {
            if (part.kind == Part.REF || part.kind == Part.OTHER) {
                Object child = part.get.apply(value);
                if (part.nullable) {
                    buf.writeByte(child == null ? 0 : 1);
                    if (child == null) continue;
                }
                writeValue(
                        buf,
                        Objects.requireNonNull(child, part.name),
                        part.type,
                        depth + 1,
                        splices);
                continue;
            }
            // A primitive is never null: its presence byte (if any), the depth check of the
            // nested write, then the same bytes, unboxed.
            if (part.nullable) buf.writeByte(1);
            count(depth + 1, MAX_DEPTH, "record depth");
            if (splices != null && depth + 1 > splices.deepest) splices.deepest = depth + 1;
            switch (part.kind) {
                case Part.BOOLEAN -> buf.writeByte(part.getBoolean.test(value) ? 1 : 0);
                case Part.INT -> buf.writeInt(part.getInt.applyAsInt(value));
                case Part.LONG -> buf.writeLong(part.getLong.applyAsLong(value));
                case Part.FLOAT -> buf.writeInt(Float.floatToRawIntBits(part.getFloat.get(value)));
                default ->
                        buf.writeLong(
                                Double.doubleToRawLongBits(part.getDouble.applyAsDouble(value)));
            }
        }
    }

    private static Object readValue(
            FriendlyByteBuf buf, Type expected, int depth, AnimationRefTable table) {
        count(depth, MAX_DEPTH, "record depth");
        if (expected instanceof ParameterizedType p && p.getRawType() == List.class) {
            int size = readCount(buf, MAX_NODES, 1);
            List<Object> list = new ArrayList<>(Math.min(size, 64));
            Type element = p.getActualTypeArguments()[0];
            for (int i = 0; i < size; i++) list.add(readValue(buf, element, depth + 1, table));
            return List.copyOf(list);
        }
        Class<?> type = (Class<?>) expected;
        if (type == boolean.class) return readBoolean(buf);
        if (type == int.class) {
            require(buf, 4);
            return buf.readInt();
        }
        if (type == long.class) {
            require(buf, 8);
            return buf.readLong();
        }
        if (type == float.class) {
            require(buf, 4);
            return Float.intBitsToFloat(buf.readInt());
        }
        if (type == double.class) {
            require(buf, 8);
            return Double.longBitsToDouble(buf.readLong());
        }
        if (type == String.class || type == Bytes.class) {
            int length =
                    readCount(buf, type == String.class ? MAX_STRING_BYTES : MAX_PAYLOAD_BYTES, 1);
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            if (type == Bytes.class) return new Bytes(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Invalid UTF-8");
            return value;
        }
        if (type.isEnum()) {
            require(buf, 1);
            int id = buf.readUnsignedByte();
            for (Object entry : CONSTANTS.get(type)) if (((Tag) entry).id() == id) return entry;
            throw new IllegalArgumentException("Unknown enum tag for " + type.getSimpleName());
        }
        int tag = readUvarInt(buf);
        if (tag == HANDLE && table != null) {
            int handle = readUvarInt(buf);
            if (handle >= table.size()) throw new IllegalArgumentException("Unknown ref handle");
            ResourceRef ref = table.entry(handle);
            if (!type.isInstance(ref))
                throw new IllegalArgumentException("Ref handle type mismatch");
            count(depth + REF_DEPTH.get(ref.getClass()), MAX_DEPTH, "record depth");
            return ref;
        }
        Layout layout = BY_ID.get(tag);
        if (layout == null || !type.isAssignableFrom(layout.type()))
            throw new IllegalArgumentException("Unknown or mismatched record tag");
        Part[] parts = layout.parts();
        Object[] arguments = new Object[parts.length];
        for (int i = 0; i < arguments.length; i++)
            arguments[i] =
                    parts[i].nullable && !readBoolean(buf)
                            ? null
                            : readValue(buf, parts[i].type, depth + 1, table);
        try {
            return layout.factory().invokeExact(arguments);
        } catch (Throwable e) {
            // Constructor.newInstance's shape: the record's own refusal is the cause.
            throw new IllegalArgumentException(
                    "Invalid animation record", new InvocationTargetException(e));
        }
    }

    /**
     * ADR4.2 §5 WIRE-GATE differential: transcodes one value written by {@link #writeWire} back to
     * its canonical bytes without building values; a handle becomes its table entry's canonical
     * record. Independent of {@link #readWire}, under the same bounds.
     */
    public static void expand(
            FriendlyByteBuf in, FriendlyByteBuf out, Type expected, AnimationRefTable table) {
        expand(in, out, expected, 0, table);
    }

    private static void expand(
            FriendlyByteBuf in,
            FriendlyByteBuf out,
            Type expected,
            int depth,
            AnimationRefTable table) {
        count(depth, MAX_DEPTH, "record depth");
        if (expected instanceof ParameterizedType p && p.getRawType() == List.class) {
            int size = readCount(in, MAX_NODES, 1);
            writeUvarInt(out, size);
            for (int i = 0; i < size; i++)
                expand(in, out, p.getActualTypeArguments()[0], depth + 1, table);
            return;
        }
        Class<?> type = (Class<?>) expected;
        int fixed =
                type == boolean.class || type.isEnum()
                        ? 1
                        : type == int.class || type == float.class
                                ? 4
                                : type == long.class || type == double.class ? 8 : 0;
        if (fixed > 0) {
            require(in, fixed);
            out.writeBytes(in, fixed);
            return;
        }
        if (type == String.class || type == Bytes.class) {
            int length =
                    readCount(in, type == String.class ? MAX_STRING_BYTES : MAX_PAYLOAD_BYTES, 1);
            writeUvarInt(out, length);
            out.writeBytes(in, length);
            return;
        }
        int tag = readUvarInt(in);
        if (tag == HANDLE && table != null) {
            int handle = readUvarInt(in);
            if (handle >= table.size()) throw new IllegalArgumentException("Unknown ref handle");
            ResourceRef ref = table.entry(handle);
            if (!type.isInstance(ref))
                throw new IllegalArgumentException("Ref handle type mismatch");
            count(depth + REF_DEPTH.get(ref.getClass()), MAX_DEPTH, "record depth");
            out.writeBytes(encode((Record) ref));
            return;
        }
        Layout layout = BY_ID.get(tag);
        if (layout == null || !type.isAssignableFrom(layout.type()))
            throw new IllegalArgumentException("Unknown or mismatched record tag");
        writeUvarInt(out, tag);
        for (Part part : layout.parts()) {
            if (part.nullable) {
                require(in, 1);
                int present = in.readUnsignedByte();
                if (present > 1) throw new IllegalArgumentException("Noncanonical boolean mask");
                out.writeByte(present);
                if (present == 0) continue;
            }
            expand(in, out, part.type, depth + 1, table);
        }
    }

    private static boolean readBoolean(FriendlyByteBuf buf) {
        require(buf, 1);
        int bits = buf.readUnsignedByte();
        if (bits > 1) throw new IllegalArgumentException("Noncanonical boolean mask");
        return bits == 1;
    }

    /** Rejects nonfinite native inputs after lossless decoding, before materializing scratch. */
    public static void validateFinite(Record value) {
        visit(value, 0);
    }

    /**
     * Large values this thread already found finite, by identity: every registered record copies
     * its lists, so a value is immutable and a revisit (a frozen graph's validation then its
     * checkpoint's; the same checkpoint on every frame of a tick) has nothing left to fail while
     * its deepest possible level is within the bound.
     */
    private static final int FINITE = 1024;

    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> FINITE_VALUES =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /** Value types worth remembering: whole checkpoints, seeds and their large components. */
    private static final Set<Class<?>> CACHED_FINITE =
            Set.of(
                    AnimationGraphState.class,
                    AnimationState.class,
                    AnimationState.Placement.class,
                    AnimationState.Vanilla.class,
                    AnimationState.Stance.class,
                    AnimationState.Equipment.class,
                    AnimationState.Tacz.class,
                    AnimationState.Lr.class,
                    AnimationState.ParCool.class,
                    AnimationState.Create.class,
                    AnimationState.Sbw.class,
                    AnimationEvent.Decision.class,
                    AnimationEvent.ObserverSeed.class);

    /** The deepest relative level a value of the cached types can reach; -1 for other types. */
    private static final ClassValue<Integer> FINITE_DEPTH =
            new ClassValue<>() {
                @Override
                protected Integer computeValue(Class<?> type) {
                    return CACHED_FINITE.contains(type) ? maxDepth(type, new HashSet<>()) : -1;
                }
            };

    private static int maxDepth(Type type, Set<Type> active) {
        if (type instanceof ParameterizedType p && p.getRawType() == List.class)
            return 1 + maxDepth(p.getActualTypeArguments()[0], active);
        if (!(type instanceof Class<?> raw)
                || raw.isPrimitive()
                || raw == String.class
                || raw.isEnum()
                || raw == Bytes.class) return 0;
        if (!active.add(type)) return MAX_DEPTH + 1;
        int deepest = 0;
        for (Layout layout : BY_ID.values())
            if (raw.isAssignableFrom(layout.type()))
                for (Part part : layout.parts())
                    deepest = Math.max(deepest, 1 + maxDepth(part.type, active));
        active.remove(type);
        return deepest;
    }

    private static void visit(Object value, int depth) {
        count(depth, MAX_DEPTH, "semantic depth");
        if (value == null) return;
        if (value instanceof Record record) {
            int bound = FINITE_DEPTH.get(record.getClass());
            if (bound >= 0 && depth + bound <= MAX_DEPTH) {
                var finite = FINITE_VALUES.get();
                if (finite.containsKey(record)) return;
                visitRecord(record, depth);
                // ponytail: cleared when full (bounded memory); an LRU if hit rates ever matter.
                if (finite.size() >= FINITE) finite.clear();
                finite.put(record, Boolean.TRUE);
                return;
            }
        }
        if (value instanceof Float f && !Float.isFinite(f)
                || value instanceof Double d && !Double.isFinite(d))
            throw new IllegalArgumentException("Nonfinite animation input");
        if (value instanceof ResourceRef ref) {
            nonnegative(ref.resourceGeneration(), "resource generation");
            nonnegative(ref.targetEpoch(), "reference life");
            nonnegative(ref.contentId(), "content id");
        }
        if (value instanceof List<?> list) {
            if (PartLists.cached(list, depth)) {
                var finite = FINITE_VALUES.get();
                if (finite.containsKey(list)) return;
                for (Object entry : list) visit(entry, depth + 1);
                if (finite.size() >= FINITE) finite.clear();
                finite.put(list, Boolean.TRUE);
                return;
            }
            for (Object entry : list) visit(entry, depth + 1);
        } else if (value instanceof Record record) visitRecord(record, depth);
    }

    /**
     * Keyframe players' body part lists, which PaGraph's catalog shares per clip with every frozen
     * graph: immutable (records copy their lists) and remembered by identity like {@link
     * #CACHED_FINITE} values.
     */
    private static final class PartLists {
        static final int DEPTH = 1 + maxDepth(AnimationGraphState.PartState.class, new HashSet<>());

        static final ThreadLocal<IdentityHashMap<Object, List<ResourceRef>>> REFERENCES =
                ThreadLocal.withInitial(IdentityHashMap::new);

        static boolean cached(List<?> list, int depth) {
            return !list.isEmpty()
                    && list.get(0) instanceof AnimationGraphState.PartState
                    && depth + DEPTH <= MAX_DEPTH;
        }

        /** The list's references without repeats, in first-occurrence walk order. */
        static List<ResourceRef> references(List<?> list, int depth) {
            var known = REFERENCES.get();
            var refs = known.get(list);
            if (refs != null) return refs;
            var walked = new LinkedHashSet<ResourceRef>();
            for (Object entry : list) AnimationSchema.references(entry, walked, depth + 1);
            refs = List.copyOf(walked);
            if (known.size() >= FINITE) known.clear();
            known.put(list, refs);
            return refs;
        }
    }

    private static void visitRecord(Record record, int depth) {
        Layout layout = LAYOUTS.get(record.getClass());
        if (layout == null) throw new IllegalArgumentException("Unregistered semantic value");
        Part[] parts = layout.parts();
        // Every component's visit starts with this depth check; the first one decides.
        if (parts.length > 0) count(depth + 1, MAX_DEPTH, "semantic depth");
        for (Part part : parts)
            switch (part.kind) {
                case Part.FLOAT -> {
                    if (!Float.isFinite(part.getFloat.get(record)))
                        throw new IllegalArgumentException("Nonfinite animation input");
                }
                case Part.DOUBLE -> {
                    if (!Double.isFinite(part.getDouble.applyAsDouble(record)))
                        throw new IllegalArgumentException("Nonfinite animation input");
                }
                case Part.REF -> {
                    if (!part.inert) visit(part.get.apply(record), depth + 1);
                }
                default -> {}
            }
    }

    public static java.util.Set<ResourceRef> references(Record record) {
        // Walk order, never Set.copyOf: SetN iterates from a per-JVM random start (SALT32L),
        // so immutable copies scrambled batch definition order run to run (U10 variance).
        java.util.Set<ResourceRef> result = new java.util.LinkedHashSet<>();
        references(record, result, 0);
        return java.util.Collections.unmodifiableSet(result);
    }

    private static void references(Object value, java.util.Set<ResourceRef> result, int depth) {
        count(depth, MAX_DEPTH, "reference depth");
        if (value == null) return;
        if (value instanceof ResourceRef ref) {
            result.add(ref);
            return;
        }
        if (value instanceof List<?> list) {
            // Adding a part list's distinct references in walk order leaves the set as the walk
            // would.
            if (PartLists.cached(list, depth)) result.addAll(PartLists.references(list, depth));
            else for (Object entry : list) references(entry, result, depth + 1);
        } else if (value instanceof Record record) {
            Layout layout = LAYOUTS.get(record.getClass());
            if (layout == null) throw new IllegalArgumentException("Unregistered reference record");
            Part[] parts = layout.parts();
            if (parts.length > 0) count(depth + 1, MAX_DEPTH, "reference depth");
            for (Part part : parts)
                if (part.kind == Part.REF && !part.inert)
                    references(part.get.apply(record), result, depth + 1);
        }
    }

    /** ADR4.2 §5: U1 tag 0 is unassigned; on the S2C wire it introduces a table handle. */
    public static final int HANDLE = 0;

    /** The deepest relative level a ref's canonical record reaches (its digest's words). */
    private static final ClassValue<Integer> REF_DEPTH =
            new ClassValue<>() {
                @Override
                protected Integer computeValue(Class<?> type) {
                    return maxDepth(type, new HashSet<>());
                }
            };

    /** The registered U1 tag of {@code value}'s record type. */
    static int tagOf(Object value) {
        Layout layout = LAYOUTS.get(value.getClass());
        if (layout == null) throw new IllegalArgumentException("Unregistered animation value");
        return layout.tag();
    }

    public static List<Class<?>> recordTypes() {
        return List.copyOf(BY_CLASS.keySet());
    }

    public static List<String> layout() {
        List<String> result = new ArrayList<>();
        for (Layout layout : BY_ID.values())
            for (RecordComponent field : layout.fields())
                result.add(
                        layout.tag()
                                + ":"
                                + layout.type().getSimpleName()
                                + "."
                                + field.getName()
                                + ":"
                                + field.getGenericType().getTypeName()
                                + ":"
                                + field.isAnnotationPresent(Nullable.class));
        result.add(
                "bounds:"
                        + VERSION
                        + ":"
                        + MAX_VICTIMS
                        + ":"
                        + MAX_NODES
                        + ":"
                        + MAX_DEPTH
                        + ":"
                        + MAX_PAYLOAD_BYTES);
        Arrays.stream(AnimationTypes.class.getDeclaredClasses())
                .filter(Class::isEnum)
                .sorted(Comparator.comparing(Class::getSimpleName))
                .forEach(
                        type -> {
                            for (Object entry : type.getEnumConstants())
                                result.add(
                                        "enum:"
                                                + type.getSimpleName()
                                                + ":"
                                                + ((Enum<?>) entry).name()
                                                + ":"
                                                + ((Tag) entry).id());
                        });
        return List.copyOf(result);
    }

    public static Digest layoutDigest() {
        return Digest.hash(String.join("\n", layout()).getBytes(StandardCharsets.UTF_8));
    }
}
