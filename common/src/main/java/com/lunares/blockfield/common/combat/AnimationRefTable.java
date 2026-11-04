package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ADR4.2 §5 generation ref table: the immutable, sorted list of generation-scoped refs one resource
 * generation can name without minting, derived identically by both peers from the compared manifest
 * and their validated views; no table bytes travel. The S2C wire writes a ref of the table as
 * {@code uvarint 0, uvarint position} (U1 tag 0 is unassigned); every other ref stays its canonical
 * record (literal escape). READY compares {@link #digest} as the manifest entry {@link
 * AnimationManifest#WIRE_REF_TABLE}, so a handle never resolves through a different list.
 */
public final class AnimationRefTable {
    /** Table derivation version; hashed into {@code compat.wire} with the envelope. */
    public static final int DERIVATION = 1;

    /** A larger generation sends literals only (an empty table with the same digest rule). */
    public static final int MAX_ENTRIES = 1 << 20;

    /** Pinned native payload ids a TaCZ/LR capsule can carry ({@code payload.type().id()}). */
    public static final List<String> CARRIERS =
            List.of(
                    "tacz:server_gun_shoot",
                    "tacz:server_gun_reload",
                    "tacz:server_gun_melee",
                    "tacz:server_gun_draw",
                    "lrtactical:melee_animation_sync");

    /** The carrier prefix naming the native payload type a capsule's bytes decode as. */
    public static final String CARRIER = "native.payload:";

    /** Code-defined carrier markers: TaCZ/LR follow-up capsules, ParCool's batch, END-tick. */
    public static final List<String> MARKERS =
            List.of(
                    "native.batched",
                    "native.parcool.batch",
                    "native.parcool.batched",
                    "native.unwrapped");

    /** TaCZ {@code DefaultAssets.DEFAULT_GUN_DISPLAY_ID}: an override naming the default. */
    public static final String DEFAULT_DISPLAY = "tacz:default";

    private final long generation;
    private final ResourceRef[] entries;
    private final Map<ResourceRef, Integer> index;
    private final Digest digest;
    private final int derived;

    private AnimationRefTable(long generation, List<ResourceRef> refs, int derived) {
        this.generation = generation;
        entries = refs.toArray(new ResourceRef[0]);
        index = new HashMap<>(entries.length * 2);
        for (int i = 0; i < entries.length; i++) index.put(entries[i], i);
        this.derived = derived;
        var all = new java.io.ByteArrayOutputStream();
        for (ResourceRef ref : entries) all.writeBytes(AnimationSchema.encode((Record) ref));
        digest = Digest.hash(all.toByteArray());
    }

    /** The table of exactly {@code refs} (sorted, distinct, all of {@code generation}). */
    public static AnimationRefTable of(long generation, Collection<? extends ResourceRef> refs) {
        return of(generation, refs, MAX_ENTRIES);
    }

    static AnimationRefTable of(
            long generation, Collection<? extends ResourceRef> refs, int capacity) {
        // Generation numbers start at 1 and a transaction stamped 0 is literal-only: its reader
        // resolves no table, so a generation-0 table exists only offline (WIRE-1b evidence).
        AnimationSchema.nonnegative(generation, "resource generation");
        var sorted = new TreeSet<ResourceRef>(ORDER);
        for (ResourceRef ref : refs) {
            if (ref.resourceGeneration() != generation || ref.targetEpoch() != 0)
                throw new IllegalArgumentException("Not a generation-" + generation + " ref");
            sorted.add(ref);
        }
        int derived = sorted.size();
        return new AnimationRefTable(
                generation, derived > capacity ? List.of() : List.copyOf(sorted), derived);
    }

    /**
     * §5 derivation for {@code generation}: (1) everything reachable from the manifest's named
     * TaCZ/LR displays (display definitions, clip refs, each clip's metadata and part definitions,
     * LR config refs) and the 16 first-person masks; (2) the named ids a capture mints ({@code
     * tacz.gun} for every {@code tacz.gun:} entry, {@code tacz.display} for every named display and
     * {@code tacz:default}, {@code tacz.melee} for every display clip key starting with {@code
     * melee}, {@code lr.melee-display} for every named LR display); (3) the native carriers. The
     * manifest keys decide which displays count, so a client-only extra display never enters. Named
     * definitions are materialized in the shared catalog for the READY resource store.
     */
    public static AnimationRefTable derive(
            long generation, Collection<String> manifestKeys, TaczAssets tacz, LrAssets lr) {
        var catalog =
                tacz != null
                        ? tacz.catalog()
                        : lr != null ? lr.catalog() : new PaGraph.Catalog(generation);
        if (catalog.generation() != generation)
            throw new IllegalArgumentException("Views of another generation");
        var refs = new ArrayList<ResourceRef>();
        var taczIds = new TreeSet<String>();
        var lrIds = new TreeSet<String>();
        for (String key : manifestKeys) {
            if (key.startsWith(AnimationManifest.TACZ_GUN))
                refs.add(catalog.named("tacz.gun", rest(key, AnimationManifest.TACZ_GUN)));
            else if (key.startsWith(AnimationManifest.TACZ_DISPLAY))
                taczIds.add(rest(key, AnimationManifest.TACZ_DISPLAY));
            else if (key.startsWith(AnimationManifest.LR_DISPLAY))
                lrIds.add(rest(key, AnimationManifest.LR_DISPLAY));
        }
        refs.add(catalog.named("tacz.display", DEFAULT_DISPLAY));
        if (tacz != null) {
            var clips = new ArrayList<ClipRef>();
            for (var display : tacz.displays()) {
                if (!taczIds.contains(display.id())) continue;
                refs.add(display.ref());
                refs.add(catalog.named("tacz.display", display.id()));
                clips.addAll(display.clips().values());
                for (String key : display.clips().keySet())
                    if (key.startsWith("melee")) refs.add(catalog.named("tacz.melee", key));
            }
            clips(refs, tacz.catalog(), generation, clips);
        }
        if (lr != null) {
            var clips = new ArrayList<ClipRef>();
            for (var display : lr.displays()) {
                if (!lrIds.contains(display.id())) continue;
                refs.add(display.ref());
                refs.add(catalog.named("lr.melee-display", display.id()));
                clips.addAll(display.clips().values());
                if (display.config() != null) refs.add(display.config().ref());
            }
            clips(refs, lr.catalog(), generation, clips);
        }
        for (String id : CARRIERS) refs.add(carrier(generation, CARRIER + id).ref());
        for (String marker : MARKERS) refs.add(carrier(generation, marker).ref());
        return of(generation, refs);
    }

    /** Each clip with its metadata and part definitions, and the first-person masks. */
    private static void clips(
            List<ResourceRef> refs, PaGraph.Catalog catalog, long generation, List<ClipRef> clips) {
        if (catalog.generation() != generation)
            throw new IllegalArgumentException("Views of another generation");
        for (int mask = 0; mask < 16; mask++) refs.add(catalog.firstPerson(mask));
        for (ClipRef clip : clips) {
            refs.add(clip);
            refs.add(catalog.metadata(clip));
            for (var part : catalog.bodyParts(clip)) refs.add(part.part());
        }
    }

    /**
     * The definition a capsule names as its native carrier: content-addressed bytes of {@code
     * text}, as {@code AnimSyncNative}, {@code AnimSyncParCool} and {@code AnimSyncCapture} build.
     */
    public static Definition carrier(long generation, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var digest = Digest.hash(bytes);
        return new Definition(
                new DefinitionRef(generation, 0, digest.a() & Long.MAX_VALUE, digest),
                new Bytes(bytes),
                List.of());
    }

    /** {@code PaGraph.Catalog.named} without registering it: the ref a capture mints. */
    static DefinitionRef named(long generation, String kind, String value) {
        var digest = Digest.hash((kind + ":" + value).getBytes(StandardCharsets.UTF_8));
        return new DefinitionRef(generation, 0, digest.a() & Long.MAX_VALUE, digest);
    }

    private static String rest(String key, String prefix) {
        return key.substring(prefix.length());
    }

    /** U1 tag, generation, life, content id, digest words: the order handles are positions in. */
    static final Comparator<ResourceRef> ORDER =
            Comparator.<ResourceRef>comparingInt(AnimationSchema::tagOf)
                    .thenComparingLong(ResourceRef::resourceGeneration)
                    .thenComparingLong(ResourceRef::targetEpoch)
                    .thenComparingLong(ResourceRef::contentId)
                    .thenComparingLong(r -> r.dependencyDigest().a())
                    .thenComparingLong(r -> r.dependencyDigest().b())
                    .thenComparingLong(r -> r.dependencyDigest().c())
                    .thenComparingLong(r -> r.dependencyDigest().d());

    /** {@code compat.wire}: envelope, derivation and carrier list, compared at READY. */
    public static String compatibility() {
        return "wire.v"
                + AnimationWire.VERSION
                + ";body="
                + AnimationWire.BODY
                + ";crc32c;s2c="
                + AnimationWire.MAX_S2C_BYTES
                + ";c2s="
                + AnimationWire.MAX_C2S_BYTES
                + "/"
                + AnimationWire.MAX_C2S_FRAGMENTS
                + ";table.v"
                + DERIVATION
                + ";max="
                + MAX_ENTRIES
                + ";carriers="
                + String.join(",", CARRIERS)
                + ";markers="
                + String.join(",", MARKERS)
                + ";default="
                + DEFAULT_DISPLAY;
    }

    public long generation() {
        return generation;
    }

    public int size() {
        return entries.length;
    }

    /** Refs the derivation produced, including any beyond {@link #MAX_ENTRIES}. */
    public int derived() {
        return derived;
    }

    /** SHA-256 of the concatenated canonical ref records, in handle order. */
    public Digest digest() {
        return digest;
    }

    /** The handle of {@code ref}, or -1 when it travels literally. */
    public int handle(ResourceRef ref) {
        Integer handle = index.get(ref);
        return handle == null ? -1 : handle;
    }

    /** The immutable ref at {@code handle}; the caller checks the bound. */
    public ResourceRef entry(int handle) {
        return entries[handle];
    }

    @Override
    public String toString() {
        return "AnimationRefTable[" + generation + ", " + entries.length + "]";
    }
}
