package com.lunares.blockfield.common.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Generation-owned native effective TaCZ display/clip view. Retain this with every seed/lease. */
public final class TaczAssets {
    public record Display(
            DefinitionRef ref,
            String id,
            boolean fixedHand,
            String fallback,
            Map<String, ClipRef> clips,
            boolean playerAnimator,
            Set<String> nonKey) {
        public Display {
            clips = Map.copyOf(clips);
            nonKey = Set.copyOf(nonKey);
        }

        /**
         * A loop clip by its native lookup key. Native {@code playLoopAnimation} compares the
         * incumbent's raw clip name with that key, so a clip whose raw name is not its key restarts
         * on every native render; no deterministic evaluator reproduces that (U3 P2-4): refused.
         */
        public ClipRef loop(String key) {
            if (key != null && nonKey.contains(key)) throw new PaGraph.Unsupported(NON_KEY + key);
            return key == null ? null : clips.get(key);
        }
    }

    /**
     * The three fields TaCZ bf8 {@code GunDisplayInstance} reads from a display POJO for the
     * third-person path. Native {@code initBase}: {@code thirdPersonAnimation =
     * StringUtils.defaultIfBlank(pojo, "empty")}; {@code playerAnimator3rd} and {@code
     * is3rdFixedHand} are assigned only when the POJO's {@code player_animator_3rd} is non-null,
     * keeping the constructor defaults {@code tacz:rifle_default.player_animation} and false.
     */
    public record DisplayFile(String animation, boolean fixedHand, String fallback) {
        public static final String DEFAULT_ANIMATION = "tacz:rifle_default.player_animation";

        public static DisplayFile parse(JsonObject display) {
            String fallback =
                    org.apache.commons.lang3.StringUtils.defaultIfBlank(
                            string(display.get("third_person_animation")), "empty");
            String animation = string(display.get("player_animator_3rd"));
            if (animation == null) return new DisplayFile(DEFAULT_ANIMATION, false, fallback);
            JsonElement fixed = display.get("3rd_fixed_hand");
            return new DisplayFile(
                    net.minecraft.resources.ResourceLocation.parse(animation).toString(),
                    fixed != null && !fixed.isJsonNull() && fixed.getAsBoolean(),
                    fallback);
        }

        private static String string(JsonElement value) {
            return value == null || value.isJsonNull() ? null : value.getAsString();
        }
    }

    /**
     * A parsed animation file under TaCZ's native keys ({@code PlayerAnimatorAssetManager}): every
     * clip with a String name, keyed by {@link PaGraph#nativeKey}; a later duplicate wins.
     */
    public static Map<String, KeyframeAnimation> keyed(Collection<?> parsed) {
        var clips = new LinkedHashMap<String, KeyframeAnimation>();
        for (Object value : parsed)
            if (value instanceof KeyframeAnimation clip
                    && clip.extraData.get("name") instanceof String name)
                clips.put(PaGraph.nativeKey(name), clip);
        return clips;
    }

    /** Message prefix of the refusal for a loop clip whose raw name differs from its key. */
    public static final String NON_KEY = "TaCZ clip name is not its lookup key: ";

    private final PaGraph.Catalog catalog;
    private final Map<String, String> defaults;
    private final Map<String, Display> displays = new LinkedHashMap<>();

    public TaczAssets(PaGraph.Catalog catalog, Map<String, String> defaults) {
        this.catalog = Objects.requireNonNull(catalog);
        this.defaults = Map.copyOf(defaults);
    }

    /**
     * Called during generation construction, using native effective lookup, never during sampling.
     */
    public Display register(
            String id,
            String animationId,
            boolean fixedHand,
            String fallback,
            boolean playerAnimator,
            Map<String, KeyframeAnimation> nativeClips) {
        var clips = new TreeMap<String, ClipRef>();
        var nonKey = new TreeSet<String>();
        // Sorted: the native HashMap order must not change catalog definition order.
        new TreeMap<>(nativeClips)
                .forEach(
                        (key, clip) -> {
                            clips.put(key, catalog.resource(key, clip));
                            if (!key.equals(clip.extraData.get("name"))) nonKey.add(key);
                        });
        // This is resource content, not a second actor-state schema. Clip refs cover
        // tracks/metadata.
        var bytes =
                new StringBuilder("tacz.effective-display.v1\n")
                        .append(id)
                        .append('\n')
                        .append(animationId)
                        .append('\n')
                        .append(fixedHand)
                        .append('\n')
                        .append(fallback)
                        .append('\n')
                        .append(playerAnimator)
                        .append('\n');
        clips.forEach((name, ref) -> bytes.append(name).append('=').append(ref).append('\n'));
        nonKey.forEach(name -> bytes.append("non-key ").append(name).append('\n'));
        var ref =
                catalog.define(
                        bytes.toString().getBytes(StandardCharsets.UTF_8),
                        new ArrayList<ResourceRef>(clips.values()));
        var display =
                new Display(
                        ref,
                        id,
                        fixedHand,
                        Objects.requireNonNull(fallback),
                        clips,
                        playerAnimator,
                        nonKey);
        var previous = displays.putIfAbsent(id, display);
        if (previous != null && !previous.equals(display))
            throw new PaGraph.Unsupported("Effective display changed within a generation: " + id);
        return display;
    }

    /** TimelessAPI: a missing override falls back to the gun's default display. */
    public Display resolve(String gun, String override) {
        if (!defaults.containsKey(gun))
            throw new PaGraph.Unsupported("Missing native common gun index: " + gun);
        String selected = override;
        if (selected == null || selected.equals("tacz:default") || !displays.containsKey(selected))
            selected = defaults.get(gun);
        Display display = displays.get(selected);
        if (display == null)
            throw new PaGraph.Unsupported("Missing effective TaCZ display: " + gun);
        return display;
    }

    public Display require(AnimationState.Tacz tacz) {
        Display display =
                displays.values().stream()
                        .filter(d -> d.ref().equals(tacz.resolvedDisplayId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PaGraph.Unsupported(
                                                "Unknown retained TaCZ display generation"));
        if (display.fixedHand() != tacz.fixedHand()
                || display.playerAnimator()
                        != (tacz.evaluatorKind() == AnimationTypes.EvaluatorKind.PA))
            throw new PaGraph.Unsupported("TaCZ effective display/checkpoint mismatch");
        return display;
    }

    public PaGraph.Catalog catalog() {
        return catalog;
    }

    /** The native common gun index: gun id to default display id (U9/WIRE manifest input). */
    public Map<String, String> defaults() {
        return defaults;
    }

    /** Every effective display of this generation, for U9's manifest collector. */
    public Collection<Display> displays() {
        return Collections.unmodifiableCollection(displays.values());
    }
}
