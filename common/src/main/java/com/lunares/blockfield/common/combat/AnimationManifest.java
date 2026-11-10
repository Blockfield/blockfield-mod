package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import net.minecraft.network.FriendlyByteBuf;

/**
 * ADR4 §6 combat-animation identity of one resource generation (U9). The server builds it with the
 * generation and sends it before JOIN; the client recomputes every named entry from its own native
 * effective lookup and returns the values in the server's order, so both sides decide on the same
 * comparison. Keys:
 *
 * <ul>
 *   <li>{@code compat.*}: code and native identity (schema layout, reducer, rig, registries,
 *       numeric options, the family JARs actually loaded). Hashed into {@link
 *       #compatibilityDigest}.
 *   <li>{@code tacz.display:<id>}, {@code lr.display:<id>}: the effective display definition of the
 *       generation catalog, whose bytes name every clip ref (content digests) it can select.
 *   <li>{@code lr.assets}: {@link LrAssets#manifest} with the actual rotation alias winner (LR
 *       transition algorithm, every display/config/clip of the generation).
 *   <li>{@code tacz.gun:<id>} (ADR4.2 §5): the server's TaCZ gun index, valued by the digest of the
 *       gun's default display id. Server-only data: checkpoints name the resolved display, so the
 *       client never resolves a gun and answers the server's value; the gun list itself is compared
 *       through {@code wire.ref-table}, whose {@code tacz.gun} refs it names.
 *   <li>{@code wire.ref-table}: {@link AnimationRefTable#digest} of this generation's ref table,
 *       which each peer derives from these keys and its own validated views. {@code compat.wire} is
 *       the envelope/derivation version and carrier list.
 * </ul>
 *
 * Only keys the server names are compared: an extra client-only display cannot be selected by any
 * server checkpoint. A noncombat override (textures, models, sounds) changes no entry.
 */
public record AnimationManifest(
        long resourceGeneration, AnimationPolicy policy, SortedMap<String, Digest> entries) {
    public static final String COMPAT = "compat.";
    public static final String TACZ_DISPLAY = "tacz.display:";
    public static final String LR_DISPLAY = "lr.display:";
    public static final String LR_ASSETS = "lr.assets";
    public static final String TACZ_GUN = "tacz.gun:";
    public static final String WIRE_REF_TABLE = "wire.ref-table";

    /** Entry bound; a pack needing more is a reviewed protocol change, never a truncated list. */
    public static final int MAX_ENTRIES = 16384;

    public static final int MAX_KEY_CHARS = 256;

    /**
     * Family JARs whose bytes the evaluators replay, with the reviewed ADR4 §13 pins. The pin only
     * labels the log line; both sides hash what is actually loaded and compare those values.
     */
    public static final Map<String, String> PINS =
            Collections.unmodifiableMap(
                    new TreeMap<>(
                            Map.of(
                                    "tacz",
                                    "b95cd70bae2f5bec56a7bdc14822d3afc63e5ec3102a5b42403376be374ced3d",
                                    "lrtactical",
                                    "e4d43b10ba590d0f7b21b19c9aae9c814921ca31295bbeb210f5819903ed7f60",
                                    "parcool",
                                    "7caaddc5ee48aefad610d520c4cbd92426561df9f29cc56a9ca60c4eeb61800b",
                                    "playeranimator",
                                    "86ef8a23a5bc8f86d67ed37f3a93380d3946bad64123b22862ed057e7b624efd",
                                    "superbwarfare",
                                    "2a5e4371a125e5438dd5d8035e3194819ccd874416fe17afd12dca82450022b9",
                                    "create",
                                    "c61dc24635e8ce0aa84c171b96d739bb53e41686bd7d933cc11ce1ded494ce08",
                                    "tacztweaks",
                                    "9b8606ee22a59ad1a618813110818b7f03cf8aa58e6b28ccf6b9ec8c76a965df")));

    public AnimationManifest {
        AnimationSchema.nonnegative(resourceGeneration, "resource generation");
        Objects.requireNonNull(policy, "policy");
        var copy = new TreeMap<String, Digest>();
        for (var entry : entries.entrySet()) {
            requireKey(entry.getKey());
            copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), entry.getKey()));
        }
        AnimationSchema.count(copy.size(), MAX_ENTRIES, "manifest entries");
        entries = Collections.unmodifiableSortedMap(copy);
    }

    /** Code and native identity: the stamped {@code compatibilityDigest} of every record. */
    public Digest compatibilityDigest() {
        return compatibilityDigest(entries);
    }

    public static Digest compatibilityDigest(Map<String, Digest> entries) {
        var text = new StringBuilder("blockfield.anim-compat.v1\n");
        for (var entry : new TreeMap<>(entries).entrySet())
            if (entry.getKey().startsWith(COMPAT)) line(text, entry.getKey(), entry.getValue());
        return Digest.hash(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Effective resources of this generation and its policy: the stamped {@code manifestDigest}.
     */
    public Digest manifestDigest() {
        var text = new StringBuilder("blockfield.anim-manifest.v1\n");
        text.append("generation=").append(resourceGeneration).append('\n');
        text.append(policyText(policy));
        for (var entry : entries.entrySet())
            if (!entry.getKey().startsWith(COMPAT)) line(text, entry.getKey(), entry.getValue());
        return Digest.hash(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The values in {@link #entries} order; {@code null} is an entry the peer cannot produce. */
    public List<String> mismatches(List<Digest> values) {
        if (values.size() != entries.size())
            throw new IllegalArgumentException("Manifest answer size mismatch");
        var result = new ArrayList<String>();
        int i = 0;
        for (var entry : entries.entrySet()) {
            Digest value = values.get(i++);
            if (value == null) result.add(entry.getKey() + " (missing)");
            else if (!value.equals(entry.getValue())) result.add(entry.getKey());
        }
        return result;
    }

    // --- Collectors (both sides call the same code) ---

    /**
     * Compatibility entries of this runtime. {@code nativeIdentity} names a loaded family JAR by
     * mod id (see {@link #nativeIdentity}); tests pass a fixed function.
     */
    public static SortedMap<String, Digest> compatibility(
            java.util.function.Function<String, String> nativeIdentity) {
        var result = new TreeMap<String, Digest>();
        result.put(COMPAT + "schema", AnimationSchema.layoutDigest());
        result.put(COMPAT + "reducer", ObserverReducer.DIGEST);
        result.put(COMPAT + "geometry", text(BodyGeometry.DIGEST));
        var animators = new StringBuilder();
        for (var tag : AnimationPolicy.registered()) animators.append(tag.name()).append(',');
        result.put(COMPAT + "animators", text(animators.toString()));
        var roots = new StringBuilder();
        for (LayerTag tag : PaGraph.PA_ROOTS)
            roots.append(AnimationGraphState.priority(tag)).append(',');
        result.put(COMPAT + "pa-roots", text(roots.toString()));
        result.put(
                COMPAT + "joml",
                text(
                        "fastmath="
                                + org.joml.Options.FASTMATH
                                + ";fma="
                                + org.joml.Options.USE_MATH_FMA
                                + ";sinLookup="
                                + org.joml.Options.SIN_LOOKUP));
        // U7c: the ParCool family's replayed formulas and constants (the JAR is native.parcool).
        result.put(COMPAT + "family.parcool", text(ParCoolCheckpoints.familyIdentity()));
        // U8b: the SBW read layout and the Create hanging adapter, so both peers compare them.
        result.put(COMPAT + "family.sbw", text(SbwLayouts.identity()));
        result.put(COMPAT + "family.create", text(CreateHanging.identity()));
        result.put(COMPAT + "wire", text(AnimationRefTable.compatibility()));
        for (String mod : PINS.keySet())
            result.put(COMPAT + "native." + mod, text(nativeIdentity.apply(mod)));
        return result;
    }

    /**
     * Resource entries of one generation: every effective TaCZ and LR display of its catalog, the
     * LR assets/algorithm manifest, the TaCZ gun index and the ref-table digest derived from those
     * keys. Registers the LR manifest definition in the shared catalog.
     */
    public static SortedMap<String, Digest> resources(
            TaczAssets tacz, LrAssets lr, LayerTag rotationWinner) {
        var result = new TreeMap<String, Digest>();
        if (tacz != null) {
            for (var display : tacz.displays())
                result.put(TACZ_DISPLAY + display.id(), display.ref().dependencyDigest());
            tacz.defaults().forEach((gun, display) -> result.put(TACZ_GUN + gun, text(display)));
        }
        if (lr != null) {
            for (var display : lr.displays())
                result.put(LR_DISPLAY + display.id(), display.ref().dependencyDigest());
            result.put(LR_ASSETS, lr.manifest(rotationWinner).dependencyDigest());
        }
        var catalog = tacz != null ? tacz.catalog() : lr != null ? lr.catalog() : null;
        // Generation numbers start at 1; only bare unit-test catalogs use 0.
        if (catalog != null && catalog.generation() > 0)
            result.put(
                    WIRE_REF_TABLE,
                    AnimationRefTable.derive(catalog.generation(), result.keySet(), tacz, lr)
                            .digest());
        return result;
    }

    /**
     * Pinned PA association rule: both rotation layers are added, and the factory registered last
     * owns the {@code tacz:rotation} slot; client entrypoints run in {@code getAllMods} order. Same
     * rule as the server's {@code LrBodyPose.lrRotationWins}; the client oracle checks it against
     * the native factory registration.
     */
    public static LayerTag rotationWinner(Iterable<String> modIds) {
        boolean lr = true;
        for (String id : modIds) {
            if (id.equals("lrtactical")) lr = true;
            else if (id.equals("tacz")) lr = false;
        }
        return lr ? LayerTag.PA_42 : LayerTag.PA_96;
    }

    /** The loaded mods in Fabric order. */
    public static List<String> loadedMods() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId())
                .toList();
    }

    /**
     * SHA-256 of the JAR a family mod was loaded from, or a labelled identity when it is absent,
     * nested or not a single file (development runs). Fork builds keep upstream versions in their
     * metadata, so only the bytes identify a pin.
     */
    public static String nativeIdentity(String modId) {
        var mod = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(modId);
        if (mod.isEmpty()) return "absent";
        String version = mod.get().getMetadata().getVersion().getFriendlyString();
        var origin = mod.get().getOrigin();
        if (origin.getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH
                || origin.getPaths().size() != 1
                || !Files.isRegularFile(origin.getPaths().get(0)))
            return "unhashed:" + origin.getKind() + ":" + version;
        return "sha256:" + sha256(origin.getPaths().get(0));
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            return "unreadable:" + failure.getClass().getSimpleName();
        }
    }

    /** {@code pinned}/{@code unpinned:<value>} for logs; never part of the comparison. */
    public static String pinStatus(String modId, String identity) {
        return identity.equals("sha256:" + PINS.get(modId)) ? "pinned" : "unpinned:" + identity;
    }

    // --- Wire ---

    public void write(FriendlyByteBuf buf) {
        SnapshotWireMath.writeUvarLong(buf, resourceGeneration);
        writePolicy(buf, policy);
        SnapshotWireMath.writeUvarInt(buf, entries.size());
        for (var entry : entries.entrySet()) {
            buf.writeUtf(entry.getKey(), MAX_KEY_CHARS);
            AnimationSchema.writeDigest(buf, entry.getValue());
        }
    }

    public static AnimationManifest read(FriendlyByteBuf buf) {
        long generation = SnapshotWireMath.readUvarLong(buf);
        var policy = readPolicy(buf);
        int count = AnimationSchema.readCount(buf, MAX_ENTRIES, 33);
        var entries = new TreeMap<String, Digest>();
        String previous = null;
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf(MAX_KEY_CHARS);
            // Canonical order: a peer cannot smuggle duplicates or reorder the positional answer.
            if (previous != null && key.compareTo(previous) <= 0)
                throw new IllegalArgumentException("Unordered manifest entries");
            previous = key;
            entries.put(key, AnimationSchema.readDigest(buf));
        }
        return new AnimationManifest(generation, policy, entries);
    }

    public static void writePolicy(FriendlyByteBuf buf, AnimationPolicy policy) {
        SnapshotWireMath.writeUvarLong(buf, policy.generation());
        buf.writeByte(policy.lowerBodyDomain().id());
    }

    /** The forced toggles and registry are invariants of {@link AnimationPolicy} itself. */
    public static AnimationPolicy readPolicy(FriendlyByteBuf buf) {
        long generation = SnapshotWireMath.readUvarLong(buf);
        AnimationSchema.require(buf, 1);
        var domain = AnimationSchema.tag(PhaseDomain.class, buf.readUnsignedByte());
        return new AnimationPolicy(
                generation, domain, true, true, true, true, AnimationPolicy.registered());
    }

    private static void requireKey(String key) {
        if (key == null
                || key.isEmpty()
                || key.length() > MAX_KEY_CHARS
                || !(key.startsWith(COMPAT)
                        || key.startsWith(TACZ_DISPLAY)
                        || key.startsWith(LR_DISPLAY)
                        || key.startsWith(TACZ_GUN)
                        || key.equals(LR_ASSETS)
                        || key.equals(WIRE_REF_TABLE)))
            throw new IllegalArgumentException("Unknown manifest key: " + key);
    }

    private static String policyText(AnimationPolicy policy) {
        var text = new StringBuilder("policy.v1;generation=").append(policy.generation());
        text.append(";lower=").append(policy.lowerBodyDomain().name());
        text.append(";animation=").append(policy.enableAnimation());
        text.append(";falling=").append(policy.enableFallingAnimation());
        text.append(";lean=").append(policy.enableFastRunLean());
        text.append(";crawl=").append(policy.smootherCrawlAnimation());
        text.append(";animators=");
        for (var tag : policy.registeredAnimators()) text.append(tag.name()).append(',');
        return text.append('\n').toString();
    }

    private static void line(StringBuilder text, String key, Digest value) {
        text.append(key.length())
                .append(':')
                .append(key)
                .append('=')
                .append(HexFormat.of().formatHex(value.bytes()))
                .append('\n');
    }

    private static Digest text(String value) {
        return Digest.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
