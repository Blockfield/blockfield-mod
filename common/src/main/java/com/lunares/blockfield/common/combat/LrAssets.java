package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generation-owned native effective LR bf2 melee view: display id -> third-person config -> clip
 * file. Retain it with every seed/lease that names one of its refs. Lookups never consult a live
 * resource manager, and an old generation never resolves through a newer one.
 */
public final class LrAssets {
    /**
     * Gson-deserialized {@code ThirdPersonAnimationConfig}. A list keeps a null slot for each
     * non-string element so the native {@code count % size} index still selects it (and yields
     * null); non-string/non-list values and empty lists are absent, as natively.
     */
    public record Config(
            ConfigRef ref,
            String animation,
            Map<String, List<String>> upper,
            Map<String, List<String>> lower) {
        public Config {
            Objects.requireNonNull(ref, "ref");
            upper = frozen(upper);
            lower = frozen(lower);
        }

        /**
         * Native {@code getAnimation(layer, key, count)}; a negative count has no native result.
         */
        public String clip(boolean upperLayer, String key, int count) {
            List<String> value = (upperLayer ? upper : lower).get(key);
            if (value == null) return null;
            if (count < 0 && value.size() > 1)
                throw new PaGraph.Unsupported("Negative LR action count");
            return value.get(Math.floorMod(count, value.size()));
        }
    }

    /**
     * The register inputs of one LR melee display file as the server reads it ({@code
     * LrBodyPose.parseDisplay}, then {@code generation}): {@code config} is the presence of the
     * {@code third_person_animation} object, an unparsable animation path is null, a section keeps
     * string values and non-empty lists (a null slot per non-string element). Shared so the U9
     * client oracle compares the server's reading with LR's native loader; a server test pins it to
     * {@code LrBodyPose}.
     */
    public record DisplayFile(
            boolean config,
            String animation,
            Map<String, List<String>> upper,
            Map<String, List<String>> lower) {
        public static DisplayFile parse(com.google.gson.JsonObject json) {
            boolean config =
                    json.has("third_person_animation")
                            && json.get("third_person_animation").isJsonObject();
            var third =
                    config
                            ? json.getAsJsonObject("third_person_animation")
                            : new com.google.gson.JsonObject();
            var animation =
                    third.has("animation") && !third.get("animation").isJsonNull()
                            ? net.minecraft.resources.ResourceLocation.tryParse(
                                    third.get("animation").getAsString())
                            : null;
            return new DisplayFile(
                    config,
                    animation == null ? null : animation.toString(),
                    section(third, "upper"),
                    section(third, "lower"));
        }

        private static Map<String, List<String>> section(
                com.google.gson.JsonObject third, String key) {
            var result = new TreeMap<String, List<String>>();
            if (!third.has(key) || !third.get(key).isJsonObject()) return result;
            for (var entry : third.getAsJsonObject(key).entrySet()) {
                var value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                    result.put(entry.getKey(), List.of(value.getAsString()));
                else if (value.isJsonArray()) {
                    var names = new ArrayList<String>();
                    for (var element : value.getAsJsonArray())
                        names.add(
                                element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                                        ? element.getAsString()
                                        : null);
                    if (!names.isEmpty())
                        result.put(entry.getKey(), Collections.unmodifiableList(names));
                }
            }
            return result;
        }
    }

    public record Display(
            DefinitionRef ref,
            String id,
            Config config,
            Map<String, ClipRef> clips,
            Set<String> nonRigid) {
        public Display {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(id, "id");
            clips = Map.copyOf(clips);
            nonRigid = Set.copyOf(nonRigid);
        }

        /**
         * Native {@code getAnimation(path, name)}: exact key lookup in the path's clip file. A clip
         * with a scale/bend track cannot drive the six rigid boxes: both evaluators refuse it.
         */
        public ClipRef clip(String name) {
            if (name == null || config == null || config.animation() == null) return null;
            if (nonRigid.contains(name)) throw new PaGraph.Unsupported(NON_RIGID + name);
            return clips.get(name);
        }
    }

    /** Message prefix of the refusal for a non-rigid selected clip. */
    public static final String NON_RIGID = "LR non-rigid clip: ";

    private final PaGraph.Catalog catalog;
    private final Map<String, Display> displays = new TreeMap<>();
    private final Map<ConfigRef, Definition> configs = new LinkedHashMap<>();

    public LrAssets(PaGraph.Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public PaGraph.Catalog catalog() {
        return catalog;
    }

    /**
     * Called while building a generation from the native effective resolution. {@code animation} is
     * the config's parsed {@code ResourceLocation} string (null: native null path); {@code clips}
     * are that file's parsed clips under their native lookup keys (empty: file missing).
     */
    public Display register(
            String id,
            boolean config,
            String animation,
            Map<String, List<String>> upper,
            Map<String, List<String>> lower,
            Map<String, KeyframeAnimation> clips) {
        Objects.requireNonNull(id, "id");
        var refs = new TreeMap<String, ClipRef>();
        var nonRigid = new TreeSet<String>();
        // Sorted: the native HashMap order must not change catalog definition order.
        new TreeMap<>(clips)
                .forEach(
                        (name, clip) -> {
                            refs.put(name, catalog.resource(name, clip));
                            if (!rigid(clip)) nonRigid.add(name);
                        });
        Config parsed = null;
        if (config) {
            var bytes = new StringBuilder("lr.third-person-config.v1\n");
            bytes.append(animation == null ? "\u0000" : animation).append('\n');
            section(bytes, "upper", upper);
            section(bytes, "lower", lower);
            byte[] content = utf8(bytes);
            // The catalog supplies generation/life/content numbering; the store keys the typed ref.
            var numbered = catalog.define(content, List.of());
            var ref =
                    new ConfigRef(
                            numbered.resourceGeneration(),
                            numbered.targetEpoch(),
                            numbered.contentId(),
                            numbered.dependencyDigest());
            configs.putIfAbsent(ref, new Definition(ref, new Bytes(content), List.of()));
            parsed = new Config(ref, animation, upper, lower);
        }
        var bytes = new StringBuilder("lr.effective-melee-display.v1\n").append(id).append('\n');
        bytes.append(parsed == null ? "no-config" : parsed.ref().toString()).append('\n');
        refs.forEach((name, ref) -> bytes.append(name).append('=').append(ref).append('\n'));
        nonRigid.forEach(name -> bytes.append("non-rigid ").append(name).append('\n'));
        var dependencies = new ArrayList<ResourceRef>(refs.values());
        if (parsed != null) dependencies.add(parsed.ref());
        var display =
                new Display(
                        catalog.define(utf8(bytes), dependencies),
                        id,
                        parsed,
                        parsed == null || animation == null ? Map.of() : refs,
                        parsed == null || animation == null ? Set.of() : nonRigid);
        var previous = displays.putIfAbsent(id, display);
        if (previous != null && !previous.equals(display))
            throw new PaGraph.Unsupported(
                    "Effective LR display changed within a generation: " + id);
        return display;
    }

    /** Every effective melee display of this generation, for U9's manifest collector. */
    public Collection<Display> displays() {
        return Collections.unmodifiableCollection(displays.values());
    }

    /** Native {@code LrClientAssetsManager.getMeleeDisplay(id)}; null when absent. */
    public Display display(String id) {
        return id == null ? null : displays.get(id);
    }

    /**
     * Native {@code LrTacticalClientAPI.getMeleeDisplay(stack)}: the display-id override, else the
     * melee id. Both ids come from the pinned {@code IMeleeWeapon} defaults on the held stack.
     */
    public Display held(String displayId, String meleeId) {
        Display display = display(displayId);
        return display != null ? display : display(meleeId);
    }

    /** Pinned rule shared with the TaCZ server gate: enabled scale tracks stay 1, bend stays 0. */
    public static boolean rigid(KeyframeAnimation clip) {
        for (var part : clip.getBodyParts().values())
            for (var state : Arrays.asList(part.scaleX, part.scaleY, part.scaleZ, part.bend)) {
                if (state == null || !state.isEnabled()) continue;
                float expected = state == part.bend ? 0 : 1;
                if (state.defaultValue != expected) return false;
                for (var frame : state.getKeyFrames()) if (frame.value != expected) return false;
            }
        return true;
    }

    /** The retained display a checkpoint names; another generation or unknown ref fails closed. */
    public Display require(DefinitionRef ref) {
        if (ref.resourceGeneration() != catalog.generation())
            throw new PaGraph.Unsupported("LR display from another resource generation");
        for (Display display : displays.values()) if (display.ref().equals(ref)) return display;
        throw new PaGraph.Unsupported("Unknown retained LR display");
    }

    /** Append-only source sizes; derived catalog refs are already covered by their clip. */
    public int definitionVersion() {
        return catalog.size() + configs.size();
    }

    /** Immutable definitions for U9's store: the catalog's plus every typed config ref. */
    public List<Definition> definitions() {
        var result = new ArrayList<>(catalog.definitions());
        result.addAll(configs.values());
        return List.copyOf(result);
    }

    /**
     * Family manifest contribution: transition algorithm/pins plus every effective display, config
     * and clip of this generation. U9 passes the actual associated-data winner from the native
     * registered stack, and hashes this with raw bytes, precedence and missing ids.
     */
    public DefinitionRef manifest(AnimationTypes.LayerTag rotationWinner) {
        var algorithm = LrPose.manifest(catalog, rotationWinner);
        var bytes =
                new StringBuilder("lr.effective-assets.v1\n")
                        .append(algorithm)
                        .append('\n')
                        .append("clip-key=native-exact;config=gson-index-preserving\n");
        var dependencies = new ArrayList<ResourceRef>();
        dependencies.add(algorithm);
        for (Display display : displays.values()) {
            bytes.append(display.id()).append('=').append(display.ref()).append('\n');
            dependencies.add(display.ref());
        }
        return catalog.define(utf8(bytes), dependencies);
    }

    private static void section(StringBuilder out, String name, Map<String, List<String>> values) {
        out.append(name).append(' ').append(values.size()).append('\n');
        new TreeMap<>(values)
                .forEach(
                        (key, list) -> {
                            out.append(key.length()).append(':').append(key);
                            for (String value : list)
                                out.append(value == null ? " \u0000" : " " + value.length() + ":")
                                        .append(value == null ? "" : value);
                            out.append('\n');
                        });
    }

    private static Map<String, List<String>> frozen(Map<String, List<String>> values) {
        var result = new TreeMap<String, List<String>>();
        values.forEach(
                (key, list) -> {
                    if (list.isEmpty())
                        throw new PaGraph.Unsupported("Empty LR config list is natively absent");
                    result.put(key, Collections.unmodifiableList(new ArrayList<>(list)));
                });
        return Collections.unmodifiableMap(result);
    }

    private static byte[] utf8(StringBuilder text) {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
