package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationGraphState.Adjustment;
import com.lunares.blockfield.common.combat.AnimationGraphState.Alias;
import com.lunares.blockfield.common.combat.AnimationGraphState.Container;
import com.lunares.blockfield.common.combat.AnimationGraphState.Fade;
import com.lunares.blockfield.common.combat.AnimationGraphState.KeyframePlayer;
import com.lunares.blockfield.common.combat.AnimationGraphState.LrForcedFade;
import com.lunares.blockfield.common.combat.AnimationGraphState.LrFrozen;
import com.lunares.blockfield.common.combat.AnimationGraphState.MetadataOverlay;
import com.lunares.blockfield.common.combat.AnimationGraphState.Node;
import com.lunares.blockfield.common.combat.AnimationGraphState.NodeData;
import com.lunares.blockfield.common.combat.AnimationGraphState.NodeRef;
import com.lunares.blockfield.common.combat.AnimationGraphState.PartState;
import com.lunares.blockfield.common.combat.AnimationGraphState.Root;
import com.lunares.blockfield.common.combat.AnimationGraphState.Speed;
import com.lunares.blockfield.common.combat.AnimationResources.ClipRef;
import com.lunares.blockfield.common.combat.AnimationResources.Definition;
import com.lunares.blockfield.common.combat.AnimationResources.DefinitionRef;
import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.AnimationResources.FirstPersonRef;
import com.lunares.blockfield.common.combat.AnimationResources.MetadataRef;
import com.lunares.blockfield.common.combat.AnimationResources.ResourceRef;
import com.lunares.blockfield.common.combat.AnimationStartReport.StartKey;
import com.lunares.blockfield.common.combat.AnimationTypes.AdjustmentSource;
import com.lunares.blockfield.common.combat.AnimationTypes.Ease;
import com.lunares.blockfield.common.combat.AnimationTypes.FadeType;
import com.lunares.blockfield.common.combat.AnimationTypes.FirstPersonMode;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.Outcome;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.layered.AnimationContainer;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * ADR4 §4.3/§5.1: lossless freeze of the native PlayerAnimator stack into {@link
 * AnimationGraphState}, exact materialization back into fresh native objects, and the shared TaCZ
 * layer reducer. Server canonical advancement, server rewind and remote presentation all run the
 * real pinned PA classes over these values; nothing here re-implements PA arithmetic.
 *
 * <p>Fractional sampling is always done on a fresh materialization: {@code setupAnim(f)} may tick a
 * speed-wrapped child inside scratch, which must never leak into a retained graph.
 */
public final class PaGraph {
    private PaGraph() {}

    /** The associated-data key both TaCZ (96) and LR (42) register for their rotation layer. */
    public static final String ROTATION_KEY = "tacz:rotation";

    /** LR's shared-clip attack flag ({@code PlayerAnimatorIntegration.playAttackAnimation}). */
    public static final String LR_ATTACK = "lr_attack_action";

    /**
     * TaCZ's clip lookup key ({@code PlayerAnimatorAssetManager.putAnimation/prepare}): PA's {@code
     * PlayerAnimationRegistry.serializeTextToString(name)}, then {@code toLowerCase(ENGLISH)}.
     * Ported instruction for instruction because Fabric strips that client-only class from the
     * dedicated server.
     */
    public static String nativeKey(String name) {
        String text;
        try {
            var component =
                    net.minecraft.network.chat.Component.Serializer.fromJson(
                            name, net.minecraft.core.RegistryAccess.EMPTY);
            text = component != null ? component.getString() : name.replace("\"", "");
        } catch (Exception malformed) {
            text = name.replace("\"", "");
        }
        return text.toLowerCase(java.util.Locale.ENGLISH);
    }

    /**
     * TaCZ bf8 {@code AnimationManager}: every play/stop uses {@code standardFadeIn(8, INOUTSINE)}.
     */
    public static final int TACZ_FADE = 8;

    /** Blockfield exhausted-reload wrapper speed (ExhaustedModifierLayerMixin / server port). */
    public static final float EXHAUSTED_SPEED = 2f / 3;

    /** Every part name the six-box rig and PA's player model read. */
    public static final List<String> RIG_PARTS =
            List.of("body", "head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg");

    public static final List<LayerTag> PA_ROOTS =
            List.of(
                    LayerTag.PA_40,
                    LayerTag.PA_41,
                    LayerTag.PA_42,
                    LayerTag.PA_93,
                    LayerTag.PA_94,
                    LayerTag.PA_95,
                    LayerTag.PA_96);

    private static final String STANDARD_FADE =
            "dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier$1";
    private static final String LR_INTEGRATION =
            "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration";
    private static final String LR_FROZEN = LR_INTEGRATION + "$FrozenAnimationSnapshot";
    private static final String LR_FORCED = LR_INTEGRATION + "$ForcedFadeModifier";

    /** First-person-only modifiers no driven layer installs (audit P2-5); fail closed if seen. */
    private static final List<String> REFUSED =
            List.of(
                    "dev.kosmx.playerAnim.api.layered.modifier.MirrorModifier",
                    "dev.kosmx.playerAnim.api.layered.modifier.FirstPersonModifier",
                    "dev.kosmx.playerAnim.minecraftApi.layers.LeftHandedHelperModifier");

    /** Unrepresentable native topology; callers label the sample unavailable, never guess. */
    public static final class Unsupported extends IllegalArgumentException {
        public Unsupported(String message) {
            super(message);
        }
    }

    /**
     * Content-addressed clip refs of one resource generation. Resource refs are generation-scoped
     * (their {@code targetEpoch} is always 0): a target life never scopes resource content, it
     * belongs to {@code AnimationState.Identity}. Every life's stack, the TaCZ/LR asset views and
     * every projection of that generation share one catalog, so all their refs resolve in it. Both
     * sides register the same parsed clips (U9 validates the resource manifest), so the same
     * content yields the same ref; nothing here depends on JVM identity across processes.
     */
    public static final class Catalog {
        private static final ThreadLocal<NameSink> NAME_SINK = new ThreadLocal<>();

        /** A name is a value until main replays this player's effects in serial order. */
        public record Name(Catalog catalog, String kind, String value) {
            public Name {
                Objects.requireNonNull(catalog, "catalog");
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(value, "value");
            }

            public Definition definition() {
                return new Definition(
                        catalog.nameRef(kind, value),
                        new AnimationResources.Bytes(nameBytes(kind, value)),
                        List.of());
            }
        }

        /** Thread-confined, nestable collection; closing restores the previous task's sink. */
        public static final class NameSink implements AutoCloseable {
            private final NameSink previous = NAME_SINK.get();
            private final List<Name> names = new ArrayList<>();

            public NameSink() {
                NAME_SINK.set(this);
            }

            public List<Name> names() {
                return List.copyOf(names);
            }

            @Override
            public void close() {
                if (NAME_SINK.get() != this)
                    throw new IllegalStateException("Catalog name sinks closed out of order");
                if (previous == null) NAME_SINK.remove();
                else NAME_SINK.set(previous);
            }
        }

        public static void replayNames(List<Name> names) {
            for (Name name : names) name.catalog().guard.accept("catalog names replay");
            for (Name name : names) name.catalog().storeName(name.kind(), name.value());
        }

        /**
         * Names the current task recorded so far (copy; empty with no sink). UPAR-3 U3-4: a
         * task-side closure resolves its own pending names from these values; the commit still
         * replays them in player order.
         */
        public static List<Name> recordedNames() {
            NameSink sink = NAME_SINK.get();
            return sink == null ? List.of() : sink.names();
        }

        private final long generation;
        private final java.util.function.Consumer<String> guard;
        private final IdentityHashMap<KeyframeAnimation, ClipRef> refs = new IdentityHashMap<>();
        private final Map<ClipRef, KeyframeAnimation> clips = new LinkedHashMap<>();
        private final Map<ClipRef, byte[]> content = new LinkedHashMap<>();
        private final Map<DefinitionRef, byte[]> names = new LinkedHashMap<>();
        private final Map<DefinitionRef, Definition> values = new LinkedHashMap<>();
        // Pure derivations of (generation, clip digest[, part]): computed once, never evicted.
        private final Map<ClipRef, MetadataRef> metadata =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<ClipRef, Map<String, DefinitionRef>> parts =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final FirstPersonRef[] firstPerson = new FirstPersonRef[16];
        // Clips whose PA copy() keeps their canonical content: freeze compares actors with the
        // stored clip itself instead of a fresh copy of it (see sameContent).
        private final java.util.Set<ClipRef> copyStable =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<ClipRef, List<PartState>> bodyParts =
                new java.util.concurrent.ConcurrentHashMap<>();
        // Overlaid actor clips of sample-only stacks: PA locks the tracks and those stacks never
        // write extraData, so one copy per (clip, overlay) serves every sample.
        private final Map<ClipRef, Map<MetadataOverlay, KeyframeAnimation>> sampleClips =
                new java.util.concurrent.ConcurrentHashMap<>();

        /** An unguarded catalog: the client's, or scratch confined to one task's lane. */
        public Catalog(long resourceGeneration) {
            this(resourceGeneration, operation -> {});
        }

        /**
         * A catalog shared across tasks: {@code guard} runs before every mutation. Per instance, so
         * a lane-local scratch catalog never depends on which classes the JVM loaded first.
         */
        public Catalog(long resourceGeneration, java.util.function.Consumer<String> guard) {
            AnimationSchema.nonnegative(resourceGeneration, "resource generation");
            generation = resourceGeneration;
            this.guard = Objects.requireNonNull(guard, "guard");
        }

        public long generation() {
            return generation;
        }

        /** Content definition for a native effective resource, including its dependency closure. */
        public DefinitionRef define(byte[] bytes, List<ResourceRef> dependencies) {
            guard.accept("catalog define");
            Digest digest = Digest.hash(bytes);
            var ref = new DefinitionRef(generation, 0, contentId(digest), digest);
            var value = new Definition(ref, new AnimationResources.Bytes(bytes), dependencies);
            var previous = values.putIfAbsent(ref, value);
            if (previous != null && !previous.equals(value))
                throw new Unsupported("Resource definition collision");
            return ref;
        }

        /**
         * Registers a native clip by identity (frozen native stacks name their clips this way).
         * Production starts never register: they take a Stack-private copy of a resource ref.
         */
        public ClipRef register(KeyframeAnimation clip) {
            guard.accept("catalog register");
            ClipRef known = refs.get(clip);
            if (known != null) return known;
            ClipRef ref = store(clip.copy());
            refs.put(clip, ref);
            return ref;
        }

        /**
         * One effective resource clip under its native lookup key. The stored name is that key and
         * LR's shared {@code lr_attack_action} flag is dropped, so the ref never depends on native
         * metadata another selector wrote into the live clip (U4 P2-4).
         */
        public ClipRef resource(String key, KeyframeAnimation clip) {
            guard.accept("catalog resource");
            var stored = clip.copy();
            stored.extraData.put("name", Objects.requireNonNull(key, "key"));
            return store(stored);
        }

        private ClipRef store(KeyframeAnimation stored) {
            // Per-actor LR state, never resource content.
            stored.extraData.remove(LR_ATTACK);
            byte[] bytes = canonical(stored);
            Digest digest = Digest.hash(bytes);
            ClipRef ref = new ClipRef(generation, 0, contentId(digest), digest);
            KeyframeAnimation previous = clips.putIfAbsent(ref, stored);
            if (previous != null && !java.util.Arrays.equals(content.get(ref), bytes))
                throw new Unsupported("Clip content id collision");
            if (content.putIfAbsent(ref, bytes) == null) {
                var kept = clips.get(ref);
                if (java.util.Arrays.equals(
                        canonical(kept), contentWithMetadata(kept, kept.copy())))
                    copyStable.add(ref);
            }
            return ref;
        }

        /** Whether a PA copy of the registered clip has the registered content. */
        boolean copyStable(ClipRef ref) {
            return copyStable.contains(ref);
        }

        /** The body part states of {@code ref}'s actors, in part-name order (immutable, shared). */
        List<PartState> bodyParts(ClipRef ref) {
            return bodyParts.computeIfAbsent(
                    ref,
                    key -> {
                        var parts = new ArrayList<PartState>();
                        for (String part : new TreeMap<>(stored(key).getBodyParts()).keySet()) {
                            DefinitionRef partRef = part(key, part);
                            parts.add(new PartState(part, partRef, partRef, partRef));
                        }
                        return List.copyOf(parts);
                    });
        }

        /**
         * Whether {@code actor} still has the registered content of {@code ref} (its own name
         * aside): the comparison freeze has always made against a fresh copy of the stored clip.
         */
        boolean sameContent(KeyframeAnimation actor, ClipRef ref) {
            if (!copyStable.contains(ref))
                return java.util.Arrays.equals(
                        canonical(actor), contentWithMetadata(actor, clip(ref)));
            return PaGraph.sameContent(actor, stored(ref));
        }

        /** Retained entries; constant once a generation is built (no start may grow it). */
        public int size() {
            return refs.size() + clips.size() + names.size() + values.size();
        }

        public ClipRef ref(KeyframeAnimation clip) {
            ClipRef ref = refs.get(clip);
            if (ref == null) throw new Unsupported("Unregistered PlayerAnimator clip");
            return ref;
        }

        /** A private copy; materialized actors never share mutable tracks with retained state. */
        public KeyframeAnimation clip(ClipRef ref) {
            return stored(ref).copy();
        }

        /**
         * Exclusive tick bound of {@code ref}'s players keeping their ease argument in [0,1]:
         * native tick() stops a finite player at stopTick and wraps a loop past endTick.
         */
        public int tickLimit(ClipRef ref) {
            KeyframeAnimation clip = stored(ref);
            return clip.isInfinite ? clip.endTick + 1 : clip.stopTick;
        }

        /** The shared read-only actor clip of a sample-only stack (never mutate it). */
        KeyframeAnimation sampleClip(ClipRef ref, MetadataOverlay overlay) {
            return sampleClips
                    .computeIfAbsent(ref, key -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(overlay, key -> overlaid(stored(ref), key));
        }

        private KeyframeAnimation stored(ClipRef ref) {
            KeyframeAnimation clip = clips.get(ref);
            if (clip == null) throw new Unsupported("Unknown clip ref");
            return clip;
        }

        /**
         * Immutable resource-id definition (gun/display/melee-variant ids in {@code
         * AnimationState.Tacz}); content is the UTF-8 id under its kind tag.
         */
        public DefinitionRef named(String kind, String value) {
            NameSink sink = NAME_SINK.get();
            if (sink != null) {
                DefinitionRef ref = nameRef(kind, value);
                sink.names.add(new Name(this, kind, value));
                return ref;
            }
            guard.accept("catalog named");
            return storeName(kind, value);
        }

        /** Pure identity derivation, also valid before this name has been registered. */
        public DefinitionRef nameRef(String kind, String value) {
            Digest digest = Digest.hash(nameBytes(kind, value));
            return new DefinitionRef(generation, 0, contentId(digest), digest);
        }

        private static byte[] nameBytes(String kind, String value) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            return (kind + ":" + value).getBytes(StandardCharsets.UTF_8);
        }

        private DefinitionRef storeName(String kind, String value) {
            byte[] bytes = nameBytes(kind, value);
            DefinitionRef ref = nameRef(kind, value);
            byte[] previous = names.putIfAbsent(ref, bytes);
            if (previous != null && !java.util.Arrays.equals(previous, bytes))
                throw new Unsupported("Named definition collision");
            return ref;
        }

        public MetadataRef metadata(ClipRef clip) {
            return metadata.computeIfAbsent(
                    clip,
                    key -> {
                        Digest digest = Digest.hash(tagged("pa.metadata", key.dependencyDigest()));
                        return new MetadataRef(generation, 0, contentId(digest), digest);
                    });
        }

        public DefinitionRef part(ClipRef clip, String part) {
            return parts.computeIfAbsent(
                            clip, key -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(
                            part,
                            name -> {
                                Digest digest =
                                        Digest.hash(
                                                tagged("pa.part:" + name, clip.dependencyDigest()));
                                return new DefinitionRef(generation, 0, contentId(digest), digest);
                            });
        }

        public FirstPersonRef firstPerson(int mask) {
            if ((mask & ~15) != 0) throw new Unsupported("First-person mask");
            FirstPersonRef known = firstPerson[mask];
            if (known != null) return known;
            Digest digest =
                    Digest.hash(("pa.firstPerson:" + mask).getBytes(StandardCharsets.UTF_8));
            return firstPerson[mask] = new FirstPersonRef(generation, 0, mask, digest);
        }

        /**
         * Immutable definitions for every registered clip and its derived refs (U9 store input).
         */
        public List<Definition> definitions() {
            List<Definition> result = new ArrayList<>();
            for (int mask = 0; mask < 16; mask++)
                result.add(
                        new Definition(
                                firstPerson(mask),
                                new AnimationResources.Bytes(new byte[] {(byte) mask}),
                                List.of()));
            content.forEach(
                    (ref, bytes) -> {
                        result.add(
                                new Definition(
                                        ref, new AnimationResources.Bytes(bytes), List.of()));
                        result.add(
                                new Definition(
                                        metadata(ref),
                                        new AnimationResources.Bytes(
                                                ref.dependencyDigest().bytes()),
                                        List.of(ref)));
                        for (String part : new TreeMap<>(clips.get(ref).getBodyParts()).keySet())
                            result.add(
                                    new Definition(
                                            part(ref, part),
                                            new AnimationResources.Bytes(
                                                    part.getBytes(StandardCharsets.UTF_8)),
                                            List.of(ref)));
                    });
            names.forEach(
                    (ref, bytes) ->
                            result.add(
                                    new Definition(
                                            ref, new AnimationResources.Bytes(bytes), List.of())));
            result.addAll(values.values());
            return List.copyOf(result);
        }

        private static long contentId(Digest digest) {
            return digest.a() & Long.MAX_VALUE;
        }

        private static byte[] tagged(String tag, Digest digest) {
            byte[] head = tag.getBytes(StandardCharsets.UTF_8);
            byte[] tail = digest.bytes();
            byte[] out = java.util.Arrays.copyOf(head, head.length + tail.length);
            System.arraycopy(tail, 0, out, head.length, tail.length);
            return out;
        }
    }

    /**
     * Canonical clip content: every field the KAP evaluator reads, in fixed order. Two parses of
     * the same resource bytes produce equal content regardless of map iteration order.
     */
    static byte[] canonical(KeyframeAnimation clip) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF("pa.clip.v1");
            out.writeInt(clip.beginTick);
            out.writeInt(clip.endTick);
            out.writeInt(clip.stopTick);
            out.writeBoolean(clip.isInfinite);
            out.writeInt(clip.returnToTick);
            out.writeBoolean(clip.isEasingBefore);
            Object name = clip.extraData.get("name");
            out.writeUTF(name instanceof String text ? text : "");
            var parts = new TreeMap<>(clip.getBodyParts());
            out.writeInt(parts.size());
            for (var entry : parts.entrySet()) {
                var part = entry.getValue();
                out.writeUTF(entry.getKey());
                out.writeBoolean(part.isBendable);
                out.writeBoolean(part.isScalable);
                for (var state :
                        new KeyframeAnimation.StateCollection.State[] {
                            part.x,
                            part.y,
                            part.z,
                            part.pitch,
                            part.yaw,
                            part.roll,
                            part.bend,
                            part.bendDirection,
                            part.scaleX,
                            part.scaleY,
                            part.scaleZ
                        }) {
                    out.writeBoolean(state != null);
                    if (state == null) continue;
                    out.writeUTF(state.name);
                    out.writeInt(Float.floatToRawIntBits(state.defaultValue));
                    out.writeInt(Float.floatToRawIntBits(state.threshold));
                    out.writeBoolean(state.isEnabled());
                    var frames = state.getKeyFrames();
                    out.writeInt(frames.size());
                    for (var frame : frames) {
                        out.writeInt(frame.tick);
                        out.writeInt(Float.floatToRawIntBits(frame.value));
                        out.writeByte(frame.ease.getId());
                        out.writeBoolean(frame.easingArg != null);
                        if (frame.easingArg != null)
                            out.writeInt(Float.floatToRawIntBits(frame.easingArg));
                    }
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final Sources NO_SOURCES =
            id -> {
                throw new Unsupported("Adjustment inside a transition fragment");
            };

    /** Adjustment source function for one materialization (server port or native client). */
    public interface Sources {
        Function<String, Optional<AdjustmentModifier.PartModifier>> source(AdjustmentSource id);

        /** The modifier instance for a source; frozen exactly like the native base class. */
        default AdjustmentModifier modifier(AdjustmentSource id) {
            return new LimitAdjustment(source(id));
        }
    }

    /**
     * PA 2.0.4 {@code AdjustmentModifier} with one singular sample replaced on both evaluators
     * (ADR4.1 errata, U3 P1-5). Native {@code getFadeIn(delta)} is {@code min(((float) getTick() +
     * delta) / (float) beginTick, 1f)} over a keyframe child: a clip with {@code beginTick == 0}
     * sampled at tick 0 and fraction 0 (a rotation layer installed at age 0, every underrun hold at
     * integer A) yields {@code 0f/0f = NaN}. For any positive argument the quotient is {@code
     * +Infinity} and the fade is exactly {@code 1f}, so its right-hand limit is {@code 1f}. Every
     * other value, including other nonfinite ones, stays native.
     */
    public static final class LimitAdjustment extends AdjustmentModifier {
        public LimitAdjustment(Function<String, Optional<AdjustmentModifier.PartModifier>> source) {
            super(source);
        }

        @Override
        protected float getFadeIn(float delta) {
            float fade = super.getFadeIn(delta);
            return Float.isNaN(fade)
                            && getAnim() instanceof KeyframeAnimationPlayer player
                            && player.getData().beginTick == 0
                            && (float) player.getTick() + delta == 0f
                    ? 1f
                    : fade;
        }
    }

    /** Freezes a live native stack without ticking, sampling or changing its objects. */
    public static AnimationGraphState freezeNative(
            AnimationStack nativeStack,
            Catalog catalog,
            AnimationPolicy policy,
            Map<Object, StartKey> starts,
            Map<AdjustmentModifier, AdjustmentSource> sources,
            LayerTag rotationWinner,
            int firstId,
            AnimationGraphState base) {
        if (firstId <= 0) throw new Unsupported("Node id base");
        if (rotationWinner != LayerTag.PA_42 && rotationWinner != LayerTag.PA_96)
            throw new Unsupported("Rotation alias winner");
        var state = new Stack(catalog, policy);
        state.nextId = firstId;
        state.starts.putAll(starts);
        state.sources.putAll(sources);
        List<Pair<Integer, IAnimation>> layers = get(Reflect.STACK_LAYERS, nativeStack);
        for (var entry : layers) {
            LayerTag tag =
                    PA_ROOTS.stream()
                            .filter(root -> AnimationGraphState.priority(root) == entry.getLeft())
                            .findFirst()
                            .orElseThrow(() -> new Unsupported("Unknown native PA root priority"));
            if (entry.getRight().getClass() != ModifierLayer.class)
                throw new Unsupported("Native PA root is not a ModifierLayer");
            @SuppressWarnings("unchecked")
            var layer = (ModifierLayer<IAnimation>) entry.getRight();
            if (state.layers.put(tag, layer) != null)
                throw new Unsupported("Duplicate native PA priority");
        }
        if (state.layers.size() != PA_ROOTS.size())
            throw new Unsupported("Incomplete native PA roots");
        state.aliases.put(ROTATION_KEY, rotationWinner);
        return state.freeze(base);
    }

    /**
     * Mutable native scratch for one graph: canonical advancement (one integer step, then freeze)
     * or a single fractional evaluation. Node ids and start keys follow every native object, so a
     * freeze after materialize+ops keeps unchanged ids and allocates new ones deterministically.
     */
    public static final class Stack {
        private final AnimationStack stack = new AnimationStack();
        private final EnumMap<LayerTag, ModifierLayer<IAnimation>> layers =
                new EnumMap<>(LayerTag.class);
        private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        private final IdentityHashMap<Object, StartKey> starts = new IdentityHashMap<>();
        private final IdentityHashMap<Object, AdjustmentSource> sources = new IdentityHashMap<>();
        // Private per-actor clip copies made by this scratch keep their base clip identity.
        private final IdentityHashMap<KeyframeAnimation, ClipRef> clipRefs =
                new IdentityHashMap<>();
        private final Map<String, LayerTag> aliases = new LinkedHashMap<>();

        /**
         * Actor clips whose content equals their registered ref, by identity. It cannot change: PA
         * 2.0.4 locks a {@code KeyframeAnimation} in its constructor (copied part collections in an
         * unmodifiable map, unmodifiable keyframe lists, final scalars, {@code setEnabled} refusing
         * once locked). A copy of a copy-stable registered clip is equal by construction.
         */
        private final java.util.Set<KeyframeAnimation> verified =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        private final Catalog catalog;
        private final AnimationPolicy policy;
        private int nextId;
        private boolean stableIds;
        private long allocationEvent = -1;
        private int allocationOrdinal;

        /** Observer nodes use event/ordinal ids, independent of R/A advancement interleaving. */
        public void stableEventIds() {
            stableIds = true;
        }

        /**
         * Starts the next operation on a scratch that just froze into the graph that operation
         * receives: the per-operation allocation state is reset to that of a freshly materialized
         * scratch. Every native field, the bookkeeping maps and the id high-water already equal
         * {@code materialize(frozen)}'s (freeze keeps exactly the reachable natives and sets the
         * next id above the frozen graph), so the operation and its freeze are unchanged.
         */
        public void reuse() {
            requireCanonical();
            stableIds = false;
            allocationEvent = -1;
            allocationOrdinal = 0;
        }

        // The graph this scratch's last freeze returned (its natives have not changed since).
        private AnimationGraphState lastFrozen;

        /** Whether this scratch's last freeze returned exactly {@code graph}. */
        boolean frozeInto(AnimationGraphState graph) {
            return lastFrozen == graph;
        }

        /**
         * HOTFIX-2751 D1: other families allocate above this freeze's output before it commits, so
         * the install-time high-water trails the committed graph and a later install can reuse a
         * committed family id. Adopt their high-water (monotonic, never rewinds) so later installs
         * never reuse committed ids and a later freeze over that graph cannot see a base node id in
         * its PA visit.
         */
        public void noteCommittedMax(int maxId) {
            if (maxId >= nextId) nextId = Math.addExact(maxId, 1);
        }

        /** Freezes on the calling thread (diagnostics and the A6 one-freeze-per-step check). */
        private static final ThreadLocal<Long> FREEZES = ThreadLocal.withInitial(() -> 0L);

        /** Freezes so far on the calling thread. */
        public static long freezes() {
            return FREEZES.get();
        }

        private void allocateEventNode(Object value, long eventSequence) {
            if (!stableIds) return;
            if (allocationEvent != eventSequence) {
                allocationEvent = eventSequence;
                allocationOrdinal = 0;
            }
            var bytes =
                    java.nio.ByteBuffer.allocate(12)
                            .putLong(eventSequence)
                            .putInt(allocationOrdinal++)
                            .array();
            int id = 0x40000000 | ((int) Digest.hash(bytes).a() & 0x1fffffff);
            if (ids.containsValue(id)) throw new Unsupported("Observer node identity collision");
            ids.put(value, id);
        }

        private boolean sampled;
        // Actors share the catalog's overlaid clips: only sample/transform/active are allowed.
        private boolean sampleOnly;
        private float fraction;
        private float lowerFraction;

        private Stack(Catalog catalog, AnimationPolicy policy) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        /**
         * Fresh registration of the seven PA roots: TaCZ 93..96 (adjustment on 96) and LR 40..42
         * (adjustment on 42), as the pinned factories create them. The rotation alias names the
         * last registered factory, which the production mod order decides.
         */
        public static Stack create(
                Catalog catalog,
                AnimationPolicy policy,
                Sources sources,
                LayerTag rotationWinner,
                int firstId) {
            if (rotationWinner != LayerTag.PA_42 && rotationWinner != LayerTag.PA_96)
                throw new Unsupported("Rotation alias winner");
            if (firstId <= 0) throw new Unsupported("Node id base");
            Stack s = new Stack(catalog, policy);
            s.nextId = firstId;
            for (LayerTag tag : PA_ROOTS) {
                ModifierLayer<IAnimation> layer = new ModifierLayer<>();
                s.id(layer);
                AdjustmentSource source =
                        tag == LayerTag.PA_42
                                ? AdjustmentSource.LR_BODY_POSE
                                : tag == LayerTag.PA_96 ? AdjustmentSource.HELD_WEAPON_POSE : null;
                if (source != null) {
                    var adjustment = sources.modifier(source);
                    s.sources.put(adjustment, source);
                    layer.addModifierLast(adjustment);
                    s.id(adjustment);
                }
                s.layers.put(tag, layer);
                s.addLayer(tag, layer);
            }
            s.aliases.put(ROTATION_KEY, rotationWinner);
            return s;
        }

        private void addLayer(LayerTag tag, ModifierLayer<IAnimation> layer) {
            stack.addAnimLayer(
                    AnimationGraphState.priority(tag),
                    policy.domain(tag) == PhaseDomain.R ? new LowerDomain(layer) : layer);
        }

        private final class LowerDomain implements IAnimation {
            private final ModifierLayer<IAnimation> layer;

            private LowerDomain(ModifierLayer<IAnimation> layer) {
                this.layer = layer;
            }

            @Override
            public boolean isActive() {
                return layer.isActive();
            }

            @Override
            public void tick() {
                layer.tick();
            }

            @Override
            public void setupAnim(float ignored) {
                layer.setupAnim(lowerFraction);
            }

            @Override
            public Vec3f get3DTransform(
                    String part, TransformType type, float ignored, Vec3f base) {
                return layer.get3DTransform(part, type, lowerFraction, base);
            }

            @Override
            public dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode getFirstPersonMode(
                    float ignored) {
                return layer.getFirstPersonMode(lowerFraction);
            }

            @Override
            public FirstPersonConfiguration getFirstPersonConfiguration(float ignored) {
                return layer.getFirstPersonConfiguration(lowerFraction);
            }
        }

        /** Exact materialization of a validated graph's PA roots into fresh native objects. */
        public static Stack materialize(
                AnimationGraphState graph,
                Catalog catalog,
                AnimationPolicy policy,
                Sources sources) {
            return materialize(graph, catalog, policy, sources, false);
        }

        /**
         * {@link #materialize} for one fractional sample without per-actor clip copies: the stack
         * refuses every operation but {@link #sample}, {@link #transform} and {@link #active}.
         */
        public static Stack sampleOnly(
                AnimationGraphState graph,
                Catalog catalog,
                AnimationPolicy policy,
                Sources sources) {
            return materialize(graph, catalog, policy, sources, true);
        }

        private static Stack materialize(
                AnimationGraphState graph,
                Catalog catalog,
                AnimationPolicy policy,
                Sources sources,
                boolean sampleOnly) {
            Map<Integer, Node> nodes = AnimationGraphValidator.validateComplete(graph, policy);
            Stack s = new Stack(catalog, policy);
            s.sampleOnly = sampleOnly;
            int max = 0;
            for (Node node : graph.nodes()) max = Math.max(max, node.id());
            s.nextId = Math.addExact(max, 1);
            var built = new LinkedHashMap<Integer, Object>();
            var roots = new EnumMap<LayerTag, Root>(LayerTag.class);
            for (Root root : graph.roots())
                if (PA_ROOTS.contains(root.layer())) roots.put(root.layer(), root);
            for (LayerTag tag : PA_ROOTS) {
                Root root = roots.get(tag);
                if (root == null || root.node() == null)
                    throw new Unsupported("Missing PA root layer " + tag);
                Object layer = s.build(root.node().id(), nodes, built, sources);
                if (!(layer instanceof ModifierLayer<?> native_))
                    throw new Unsupported("PA root is not a ModifierLayer");
                @SuppressWarnings("unchecked")
                ModifierLayer<IAnimation> typed = (ModifierLayer<IAnimation>) native_;
                s.layers.put(tag, typed);
                s.addLayer(tag, typed);
            }
            for (Alias alias : graph.aliases()) {
                LayerTag owner = null;
                for (var entry : roots.entrySet())
                    if (entry.getValue().node().equals(alias.winner())) owner = entry.getKey();
                if (owner == null) throw new Unsupported("Alias winner is not a PA root");
                s.aliases.put(alias.associatedKey(), owner);
            }
            return s;
        }

        private Object build(
                int id, Map<Integer, Node> nodes, Map<Integer, Object> built, Sources sources) {
            Object existing = built.get(id);
            if (existing != null) return existing;
            Node node = nodes.get(id);
            if (node == null) throw new Unsupported("Dangling node " + id);
            Object made =
                    switch (node.data()) {
                        case AnimationGraphState.ModifierLayer layer -> {
                            ModifierLayer<IAnimation> nativeLayer = new ModifierLayer<>();
                            built.put(id, nativeLayer);
                            yield nativeLayer;
                        }
                        case KeyframePlayer player -> keyframe(player);
                        case Fade fade -> {
                            if (fade.subtype() != FadeType.STANDARD || fade.function() != null)
                                throw new Unsupported("Only standard PA fades are whitelisted");
                            var made_ =
                                    AbstractFadeModifier.standardFadeIn(
                                            fade.length(), ease(fade.ease()));
                            set(Reflect.FADE_TIME, made_, fade.time());
                            yield made_;
                        }
                        case LrForcedFade fade -> {
                            Object made_ = Reflect.forced(fade.length(), ease(fade.ease()));
                            set(Reflect.FADE_TIME, made_, fade.time());
                            yield made_;
                        }
                        case Adjustment adjustment -> {
                            if (!adjustment.parts().isEmpty())
                                throw new Unsupported("Adjustment parts are sampled, not retained");
                            var made_ = sources.modifier(adjustment.source());
                            made_.enabled = adjustment.enabled();
                            set(Reflect.INSTRUCTED, made_, adjustment.instructedFadeout());
                            set(Reflect.REMAINING, made_, adjustment.remainingFadeout());
                            this.sources.put(made_, adjustment.source());
                            yield made_;
                        }
                        case Speed speed -> {
                            var made_ = new SpeedModifier(speed.speed());
                            set(Reflect.DELTA, made_, speed.delta());
                            set(Reflect.SHIFTED, made_, speed.shiftedDelta());
                            yield made_;
                        }
                        case Container container -> new AnimationContainer<IAnimation>();
                        case LrFrozen frozen -> null; // needs its built source first
                        default ->
                                throw new Unsupported(
                                        "Not a PA node: " + node.data().getClass().getSimpleName());
                    };
            if (node.data() instanceof LrFrozen frozen) {
                Object source = build(frozen.source().id(), nodes, built, sources);
                made = Reflect.frozen((KeyframeAnimationPlayer) source);
            }
            built.put(id, made);
            ids.put(made, id);
            if (node.start() != null) starts.put(made, node.start());
            link(node.data(), made, nodes, built, sources);
            return made;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void link(
                NodeData data,
                Object made,
                Map<Integer, Node> nodes,
                Map<Integer, Object> built,
                Sources sources) {
            switch (data) {
                case AnimationGraphState.ModifierLayer layer -> {
                    var nativeLayer = (ModifierLayer<IAnimation>) made;
                    List<AbstractModifier> list = modifiers(nativeLayer);
                    for (NodeRef ref : layer.modifiers()) {
                        Object modifier = build(ref.id(), nodes, built, sources);
                        if (!(modifier instanceof AbstractModifier m))
                            throw new Unsupported("Layer modifier is not a PA modifier");
                        list.add(m);
                    }
                    set(
                            Reflect.LAYER_ANIMATION,
                            nativeLayer,
                            layer.animation() == null
                                    ? null
                                    : build(layer.animation().id(), nodes, built, sources));
                }
                case Fade f -> {
                    linkModifier(made, f.host(), f.anim(), nodes, built, sources);
                    ((AbstractFadeModifier) made)
                            .setBeginAnimation(
                                    animation(f.beginAnimation(), nodes, built, sources));
                }
                case LrForcedFade f -> {
                    linkModifier(made, f.host(), f.anim(), nodes, built, sources);
                    ((AbstractFadeModifier) made)
                            .setBeginAnimation(
                                    animation(f.beginAnimation(), nodes, built, sources));
                }
                case Adjustment a -> linkModifier(made, a.host(), a.anim(), nodes, built, sources);
                case Speed sp -> linkModifier(made, sp.host(), sp.anim(), nodes, built, sources);
                case Container c ->
                        ((AnimationContainer) made)
                                .setAnim(animation(c.child(), nodes, built, sources));
                default -> {}
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void linkModifier(
                Object made,
                NodeRef host,
                NodeRef anim,
                Map<Integer, Node> nodes,
                Map<Integer, Object> built,
                Sources sources) {
            var modifier = (AbstractModifier) made;
            modifier.setHost((ModifierLayer) build(host.id(), nodes, built, sources));
            modifier.setAnim(animation(anim, nodes, built, sources));
        }

        private IAnimation animation(
                NodeRef ref,
                Map<Integer, Node> nodes,
                Map<Integer, Object> built,
                Sources sources) {
            if (ref == null) return null;
            Object value = build(ref.id(), nodes, built, sources);
            if (!(value instanceof IAnimation animation))
                throw new Unsupported("Edge target is not an IAnimation");
            return animation;
        }

        private KeyframeAnimationPlayer keyframe(KeyframePlayer player) {
            KeyframeAnimation clip = catalog.stored(player.data());
            if (!player.actorMetadata().equals(catalog.metadata(player.data())))
                throw new Unsupported("Actor metadata ref does not belong to its clip");
            // The registered part states in part-name order (shared instance when frozen here).
            var expected = catalog.bodyParts(player.data());
            if (player.bodyParts() != expected) {
                if (player.bodyParts().size() != expected.size())
                    throw new Unsupported("Body part inventory differs from clip");
                if (!player.bodyParts().equals(expected))
                    throw new Unsupported("Body part ref differs from clip");
            }
            var overlay = player.metadata();
            clip =
                    sampleOnly
                            ? catalog.sampleClip(player.data(), overlay)
                            : overlaid(clip, overlay);
            clipRefs.put(clip, player.data());
            if (catalog.copyStable(player.data())) verified.add(clip);
            var actor = new KeyframeAnimationPlayer(clip, 0);
            set(Reflect.RUNNING, actor, player.isRunning());
            set(Reflect.CURRENT_TICK, actor, player.currentTick());
            set(Reflect.LOOP_STARTED, actor, player.isLoopStarted());
            set(Reflect.TICK_DELTA, actor, player.tickDelta());
            actor.perspective = player.perspective();
            int mask = (int) player.firstPersonConfiguration().contentId();
            if (!player.firstPersonConfiguration().equals(catalog.firstPerson(mask)))
                throw new Unsupported("First-person ref");
            actor.setFirstPersonConfiguration(
                    new FirstPersonConfiguration(
                            (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0));
            actor.setFirstPersonMode(firstPersonMode(player.firstPersonMode()));
            return actor;
        }

        /** Native PA tick of every active layer (the PA player-tick HEAD stage). */
        public void tick() {
            requireCanonical();
            stack.tick();
            retainReachable();
        }

        /** Observer projection advances only roots whose policy fixes them to this clock. */
        public void tick(PhaseDomain domain) {
            requireCanonical();
            for (LayerTag tag : PA_ROOTS) {
                var layer = layer(tag);
                if (policy.domain(tag) == domain && layer.isActive()) layer.tick();
            }
            retainReachable();
        }

        /**
         * Drops bookkeeping for natives the PA graph no longer reaches (finished fades, replaced
         * actors), so a long-lived canonical stack retains only its live graph.
         */
        private void retainReachable() {
            var reachable = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            var data = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            var pending = new java.util.ArrayDeque<Object>(layers.values());
            while (!pending.isEmpty()) {
                Object value = pending.pop();
                if (!reachable.add(value)) continue;
                List<Object> next = new ArrayList<>();
                if (value instanceof ModifierLayer<?> layer) {
                    next.addAll(modifiers(layer));
                    next.add(layer.getAnimation());
                } else if (value instanceof KeyframeAnimationPlayer player)
                    data.add(player.getData());
                else if (value instanceof AbstractModifier modifier) {
                    next.add(modifier.getAnim());
                    if (modifier instanceof AbstractFadeModifier fade)
                        next.add(get(Reflect.FADE_BEGIN, fade));
                } else if (value.getClass().getName().equals(LR_FROZEN))
                    next.add(Reflect.frozenSource(value));
                else if (value instanceof AnimationContainer<?> container)
                    next.add(container.getAnim());
                for (Object child : next) if (child != null) pending.push(child);
            }
            ids.keySet().retainAll(reachable);
            starts.keySet().retainAll(reachable);
            sources.keySet().retainAll(reachable);
            clipRefs.keySet().retainAll(data);
            verified.retainAll(data);
        }

        /** Bookkeeping entries; bounded by the live graph (U4 P1-2 retention check). */
        public int retained() {
            return ids.size() + starts.size() + sources.size() + clipRefs.size();
        }

        /**
         * TaCZ {@code playLoopAnimation}: an active keyframe incumbent keeps running when its
         * {@code extraData} name is not a String or equals the requested lookup {@code key} (native
         * case-sensitive {@code key.equals(name)}); any other incumbent fades out over eight ticks.
         * {@code sequence} is invoked only when a transition is installed, so unchanged selections
         * consume no journal id.
         */
        public Outcome loop(LayerTag tag, ClipRef clip, String key, LongSupplier sequence) {
            requireCanonical();
            if (clip == null) return Outcome.MISSING_CLIP;
            ModifierLayer<IAnimation> layer = layer(tag);
            if (layer.getAnimation() instanceof KeyframeAnimationPlayer current
                    && current.isActive()) {
                if (!(current.getData().extraData.get("name") instanceof String name)
                        || Objects.requireNonNull(key, "key").equals(name))
                    return Outcome.NO_CHANGE;
            }
            install(layer, new KeyframeAnimationPlayer(copy(clip)), sequence);
            return Outcome.SELECTED;
        }

        public Outcome loop(LayerTag tag, ClipRef clip, String key, long eventSequence) {
            return loop(tag, clip, key, () -> eventSequence);
        }

        /** Fixture form over a registered native clip; its own name is the lookup key. */
        public Outcome loop(LayerTag tag, KeyframeAnimation clip, LongSupplier sequence) {
            return clip == null
                    ? loop(tag, (ClipRef) null, null, sequence)
                    : loop(
                            tag,
                            catalog.register(clip),
                            (String) clip.extraData.get("name"),
                            sequence);
        }

        public Outcome loop(LayerTag tag, KeyframeAnimation clip, long eventSequence) {
            return loop(tag, clip, () -> eventSequence);
        }

        /** TaCZ {@code playOnceAnimation}: an active incumbent wins (active-once ignore). */
        public Outcome once(LayerTag tag, ClipRef clip, boolean exhausted, LongSupplier sequence) {
            requireCanonical();
            if (clip == null) return Outcome.MISSING_CLIP;
            ModifierLayer<IAnimation> layer = layer(tag);
            IAnimation current = layer.getAnimation();
            if (current != null && current.isActive()) return Outcome.ACTIVE_ONCE_IGNORED;
            IAnimation actor = new KeyframeAnimationPlayer(copy(clip));
            if (exhausted) actor = exhaustedWrapper(actor);
            install(layer, actor, sequence);
            return Outcome.SELECTED;
        }

        public Outcome once(
                LayerTag tag, KeyframeAnimation clip, boolean exhausted, LongSupplier sequence) {
            return once(tag, clip == null ? null : catalog.register(clip), exhausted, sequence);
        }

        public Outcome once(
                LayerTag tag, KeyframeAnimation clip, boolean exhausted, long eventSequence) {
            return once(tag, clip, exhausted, () -> eventSequence);
        }

        /** TaCZ {@code playRotationAnimation}: unconditional replacement (fade only if active). */
        public Outcome rotation(LayerTag tag, ClipRef clip, LongSupplier sequence) {
            requireCanonical();
            if (clip == null) return Outcome.MISSING_CLIP;
            install(layer(tag), new KeyframeAnimationPlayer(copy(clip)), sequence);
            return Outcome.SELECTED;
        }

        public Outcome rotation(LayerTag tag, ClipRef clip, long eventSequence) {
            return rotation(tag, clip, () -> eventSequence);
        }

        public Outcome rotation(LayerTag tag, KeyframeAnimation clip, LongSupplier sequence) {
            return rotation(tag, clip == null ? null : catalog.register(clip), sequence);
        }

        public Outcome rotation(LayerTag tag, KeyframeAnimation clip, long eventSequence) {
            return rotation(tag, clip, () -> eventSequence);
        }

        /**
         * A per-start private copy of a resource clip. It lives only as long as this scratch's
         * reachable natives (U4 P1-2): the generation catalog never grows per start.
         */
        public KeyframeAnimation copy(ClipRef ref) {
            KeyframeAnimation clip = catalog.clip(ref);
            clipRefs.put(clip, ref);
            if (catalog.copyStable(ref)) verified.add(clip);
            return clip;
        }

        /** TaCZ {@code stopAnimation}: fades an active layer to null; inactive layers stay. */
        public Outcome stop(LayerTag tag, int fade, LongSupplier sequence) {
            requireCanonical();
            ModifierLayer<IAnimation> layer = layer(tag);
            if (!layer.isActive()) return Outcome.NO_CHANGE;
            var modifier =
                    AbstractFadeModifier.standardFadeIn(
                            fade, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
            tagNew(modifier, sequence.getAsLong(), null);
            // The three-argument overload: Blockfield's client exhausted mixin targets only the
            // two-argument one, and driven ops must never be rewrapped by it.
            layer.replaceAnimationWithFade(modifier, null, false);
            return Outcome.STOPPED;
        }

        public Outcome stop(LayerTag tag, int fade, long eventSequence) {
            return stop(tag, fade, () -> eventSequence);
        }

        private void install(
                ModifierLayer<IAnimation> layer, IAnimation actor, LongSupplier sequence) {
            long eventSequence = sequence.getAsLong();
            // Ids follow creation order: actor subtree root first, then its inner nodes, then fade.
            var key = tagNew(actor, eventSequence, null);
            if (actor instanceof ModifierLayer<?> wrapper) {
                for (AbstractModifier modifier : modifiers(wrapper))
                    tagNew(modifier, eventSequence, key);
                tagNew(wrapper.getAnimation(), eventSequence, key);
            }
            var fade =
                    AbstractFadeModifier.standardFadeIn(
                            TACZ_FADE, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE);
            tagNew(fade, eventSequence, key);
            layer.replaceAnimationWithFade(fade, actor, false);
        }

        /**
         * The layer's current animation subtree as a transition fragment: one root on the layer and
         * the subtree's nodes in a fragment-local id space, without starts (the journal names the
         * start). Taken right after a SELECTED transition, this is the authoritative patch topology
         * at age zero.
         */
        public AnimationGraphState fragment(LayerTag tag) {
            requireCanonical();
            IAnimation actor = layer(tag).getAnimation();
            var local = new Stack(catalog, policy);
            local.nextId = 1;
            local.clipRefs.putAll(clipRefs);
            local.verified.addAll(verified);
            var out = new LinkedHashMap<Integer, Node>();
            NodeRef ref = actor == null ? null : local.visit(actor, policy.domain(tag), out);
            var graph =
                    new AnimationGraphState(
                            List.of(new Root(AnimationGraphState.priority(tag), tag, ref)),
                            List.copyOf(out.values()));
            AnimationGraphValidator.validate(graph, policy);
            return graph;
        }

        /**
         * Applies one authoritative TaCZ transition (capsule or decision). SELECTED installs the
         * fragment actor with the native eight-tick INOUTSINE replace; STOPPED is the native stop.
         * The authoritative outcome decides: the native selection predicates (active-once, same
         * name) are not rerun on the observer's bucket (ADR4 §§3.2/3.6). Other outcomes change no
         * topology.
         */
        public Outcome apply(AnimationEvent.Patch patch, LongSupplier sequence) {
            requireCanonical();
            LayerTag tag = patch.layer();
            ModifierLayer<IAnimation> layer = layer(tag);
            AnimationGraphState fragment = patch.replacement();
            if (fragment.roots().size() != 1
                    || fragment.roots().getFirst().layer() != tag
                    || !fragment.aliases().isEmpty())
                throw new Unsupported("TaCZ transition fragment shape");
            NodeRef actorRef = fragment.roots().getFirst().node();
            if ((patch.outcome() == Outcome.SELECTED) != (actorRef != null))
                throw new Unsupported("Transition fragment does not match its outcome");
            switch (patch.outcome()) {
                case SELECTED -> {
                    var nodes = AnimationGraphValidator.validate(fragment, policy);
                    for (Node node : fragment.nodes())
                        if (node.start() != null)
                            throw new Unsupported("Fragment nodes cannot carry journal starts");
                    var local = new Stack(catalog, policy);
                    Object actor =
                            local.build(actorRef.id(), nodes, new LinkedHashMap<>(), NO_SOURCES);
                    clipRefs.putAll(local.clipRefs);
                    verified.addAll(local.verified);
                    install(layer, (IAnimation) actor, sequence);
                }
                case STOPPED -> stop(tag, TACZ_FADE, sequence);
                default -> {}
            }
            return patch.outcome();
        }

        /**
         * An explicit reseed of one root: only its registered adjustment modifier survives (fades
         * and their sources are dropped) and the root has no animation.
         */
        public void reset(LayerTag tag) {
            requireCanonical();
            ModifierLayer<IAnimation> layer = layer(tag);
            List<AbstractModifier> list = modifiers(layer);
            for (int i = list.size() - 1; i >= 0; i--)
                if (!sources.containsKey(list.get(i))) layer.removeModifier(i);
            layer.setAnimation(null);
        }

        /** Linked exhausted wrapper, matching the production Blockfield mixin. */
        public static ModifierLayer<IAnimation> exhaustedWrapper(IAnimation animation) {
            var wrapper = new ModifierLayer<>(animation);
            wrapper.addModifierLast(new SpeedModifier(EXHAUSTED_SPEED));
            return wrapper;
        }

        private StartKey tagNew(Object value, long eventSequence, StartKey key) {
            if (value == null) return key;
            if (ids.containsKey(value)) throw new Unsupported("Native reused across installs");
            allocateEventNode(value, eventSequence);
            int id = id(value);
            StartKey start = key != null ? key : new StartKey(eventSequence, id);
            if (eventSequence >= 0) starts.put(value, start);
            return start;
        }

        private int id(Object value) {
            Integer known = ids.get(value);
            if (known != null) return known;
            int id = nextId;
            nextId = Math.addExact(nextId, 1);
            ids.put(value, id);
            return id;
        }

        public ModifierLayer<IAnimation> layer(LayerTag tag) {
            requireOwnedNatives();
            ModifierLayer<IAnimation> layer = layers.get(tag);
            if (layer == null) throw new Unsupported("Not a PA root: " + tag);
            return layer;
        }

        /** The layer the associated-data key resolves to (both families address the winner). */
        public ModifierLayer<IAnimation> associated(String key) {
            requireOwnedNatives();
            LayerTag owner = aliases.get(key);
            if (owner == null) throw new Unsupported("Unknown associated key " + key);
            return layer(owner);
        }

        /** Tags natives created by family code (U4 LR ops) before the next freeze. */
        public void started(Object nativeNode, long eventSequence, Object startOwner) {
            requireCanonical();
            StartKey key = startOwner == null ? null : starts.get(startOwner);
            if (startOwner != null && key == null) throw new Unsupported("Unknown start owner");
            tagNew(nativeNode, eventSequence, key);
        }

        public AnimationStack nativeStack() {
            requireOwnedNatives();
            return stack;
        }

        public Catalog catalog() {
            requireOwnedNatives();
            return catalog;
        }

        /** Selected native actor, including an exhausted wrapper; fade sources are separate. */
        public ClipRef selectedClip(LayerTag tag) {
            IAnimation actor = layer(tag).getAnimation();
            if (actor instanceof ModifierLayer<?> wrapper) actor = wrapper.getAnimation();
            return actor instanceof KeyframeAnimationPlayer player
                    ? clipRef(player.getData())
                    : null;
        }

        /** Journal start of the layer's current animation subtree, or null if untagged/empty. */
        public StartKey start(LayerTag tag) {
            IAnimation current = layer(tag).getAnimation();
            return current == null ? null : starts.get(current);
        }

        /**
         * One fractional setup of the whole mixed stack at the common fraction, in native order;
         * afterwards this scratch can only be read, never frozen or advanced.
         */
        public void sample(float fraction) {
            if (policy.lowerBodyDomain() == PhaseDomain.R)
                throw new Unsupported("R lower layers require an explicit R fraction");
            sample(fraction, fraction);
        }

        public void sample(float fraction, float lowerFraction) {
            if (!(fraction >= 0 && fraction <= 1) || !(lowerFraction >= 0 && lowerFraction <= 1))
                throw new Unsupported("Sample fraction");
            if (sampled) throw new IllegalStateException("One fractional setup per scratch");
            sampled = true;
            this.fraction = fraction;
            this.lowerFraction = lowerFraction;
            stack.setupAnim(fraction);
        }

        /** Native stack transform at the sampled fraction (fades/adjustments read it again). */
        public Vec3f transform(String part, TransformType type, Vec3f base) {
            if (!sampled) throw new IllegalStateException("Sample before reading transforms");
            return stack.get3DTransform(part, type, fraction, base);
        }

        public boolean active() {
            return stack.isActive();
        }

        /**
         * Refuses a sampled scratch whose transform of any rig part is nonfinite: such a sample is
         * unavailable on both evaluators, never handed to ModelParts or counted as equality.
         */
        public void requireFinite() {
            for (String part : RIG_PARTS)
                for (TransformType type : TransformType.values()) {
                    float base = type == TransformType.SCALE ? 1 : 0;
                    Vec3f value = transform(part, type, new Vec3f(base, base, base));
                    if (!Float.isFinite(value.getX())
                            || !Float.isFinite(value.getY())
                            || !Float.isFinite(value.getZ()))
                        throw new Unsupported("Nonfinite PA sample: " + part + " " + type);
                }
        }

        private void requireCanonical() {
            if (sampled) throw new IllegalStateException("Sampled scratch cannot advance");
            if (sampleOnly) throw new IllegalStateException("Sample-only scratch cannot advance");
        }

        // Sample-only stacks share the catalog's clips across worker threads: no owned natives.
        private void requireOwnedNatives() {
            if (sampleOnly)
                throw new IllegalStateException("Sample-only scratch shares catalog clips");
        }

        /**
         * Freezes the PA roots into {@code base} (which supplies the other families' roots and
         * nodes). New natives get ids above every id in either graph, in traversal order.
         */
        public AnimationGraphState freeze(AnimationGraphState base) {
            requireCanonical();
            FREEZES.set(FREEZES.get() + 1);
            var nodesById = new java.util.HashMap<Integer, Node>();
            for (Node node : base.nodes()) nodesById.put(node.id(), node);
            var paOwned = new java.util.HashSet<Integer>();
            var pending = new java.util.ArrayDeque<NodeRef>();
            for (Root root : base.roots())
                if (PA_ROOTS.contains(root.layer()) && root.node() != null)
                    pending.push(root.node());
            while (!pending.isEmpty()) {
                NodeRef ref = pending.pop();
                Node node = nodesById.get(ref.id());
                if (node == null || !paOwned.add(ref.id())) continue;
                for (NodeRef edge : AnimationGraphValidator.edges(node.data())) pending.push(edge);
            }
            int max = nextId - 1;
            for (Node node : base.nodes()) max = Math.max(max, node.id());
            nextId = Math.addExact(max, 1);
            var out = new LinkedHashMap<Integer, Node>();
            var roots = new ArrayList<Root>();
            var seen = java.util.EnumSet.noneOf(LayerTag.class);
            for (Root root : base.roots()) {
                if (!PA_ROOTS.contains(root.layer())) {
                    roots.add(root);
                    continue;
                }
                seen.add(root.layer());
                roots.add(
                        new Root(
                                root.priority(),
                                root.layer(),
                                visit(layer(root.layer()), policy.domain(root.layer()), out)));
            }
            if (!seen.containsAll(PA_ROOTS)) throw new Unsupported("Base lacks PA roots");
            var nodes = new ArrayList<Node>();
            for (Node node : base.nodes()) {
                if (paOwned.contains(node.id())) continue;
                if (out.containsKey(node.id())) throw new Unsupported("Node id collision");
                nodes.add(node);
            }
            nodes.addAll(out.values());
            var aliasList = new ArrayList<Alias>();
            for (Alias alias : base.aliases())
                if (!aliases.containsKey(alias.associatedKey())) aliasList.add(alias);
            aliases.forEach(
                    (key, owner) ->
                            aliasList.add(new Alias(key, new NodeRef(ids.get(layer(owner))))));
            var graph = new AnimationGraphState(roots, nodes, aliasList);
            AnimationGraphValidator.validateComplete(graph, policy);
            // Allocation restarts above the live ids, exactly as materialize does, so persistent
            // advancement and restore-then-advance allocate the same ids for the next tick.
            int top = 0;
            for (Node node : graph.nodes()) top = Math.max(top, node.id());
            nextId = Math.addExact(top, 1);
            retainReachable();
            lastFrozen = graph;
            return graph;
        }

        private NodeRef visit(Object value, PhaseDomain domain, Map<Integer, Node> out) {
            if (value == null) return null;
            int id = id(value);
            if (out.containsKey(id)) {
                if (out.get(id).domain() != domain)
                    throw new Unsupported("Shared PA node crosses phase domains");
                return new NodeRef(id);
            }
            // Placeholder blocks cycles; replaced once children are visited.
            out.put(id, new Node(id, domain, null, new Container(null)));
            NodeData data = describe(value, domain, out);
            out.put(id, new Node(id, domain, starts.get(value), data));
            return new NodeRef(id);
        }

        private NodeData describe(Object value, PhaseDomain domain, Map<Integer, Node> out) {
            String name = value.getClass().getName();
            if (REFUSED.contains(name))
                throw new Unsupported("First-person-only modifier on a driven layer: " + name);
            if (value instanceof ModifierLayer<?> layer) {
                if (value.getClass() != ModifierLayer.class)
                    throw new Unsupported("ModifierLayer subclass " + name);
                var refs = new ArrayList<NodeRef>();
                for (AbstractModifier modifier : modifiers(layer))
                    refs.add(visit(modifier, domain, out));
                return new AnimationGraphState.ModifierLayer(
                        visit(layer.getAnimation(), domain, out), refs);
            }
            if (value instanceof KeyframeAnimationPlayer player) {
                if (value.getClass() != KeyframeAnimationPlayer.class)
                    throw new Unsupported("KeyframeAnimationPlayer subclass " + name);
                return keyframe(player);
            }
            if (value instanceof AbstractModifier modifier) {
                NodeRef host = hostRef(modifier);
                NodeRef anim = visit(modifier.getAnim(), domain, out);
                if (value instanceof SpeedModifier speed) {
                    if (value.getClass() != SpeedModifier.class)
                        throw new Unsupported("SpeedModifier subclass " + name);
                    return new Speed(
                            host,
                            anim,
                            speed.speed,
                            get(Reflect.DELTA, speed),
                            get(Reflect.SHIFTED, speed));
                }
                if (value instanceof AdjustmentModifier adjustment) {
                    AdjustmentSource source = sources.get(adjustment);
                    if (source == null) throw new Unsupported("Unbound adjustment source");
                    // Subclasses only with registered provenance (Sources.modifier); state is the
                    // base class's.
                    return new Adjustment(
                            host,
                            anim,
                            adjustment.enabled,
                            get(Reflect.INSTRUCTED, adjustment),
                            get(Reflect.REMAINING, adjustment),
                            source,
                            List.of());
                }
                if (value instanceof AbstractFadeModifier fade) {
                    int time = get(Reflect.FADE_TIME, fade),
                            length = get(Reflect.FADE_LENGTH, fade);
                    NodeRef begin = visit(get(Reflect.FADE_BEGIN, fade), domain, out);
                    if (name.equals(STANDARD_FADE))
                        return new Fade(
                                host,
                                anim,
                                time,
                                length,
                                begin,
                                ease(Reflect.standardEase(fade)),
                                FadeType.STANDARD,
                                null);
                    if (name.equals(LR_FORCED))
                        return new LrForcedFade(
                                host, anim, time, length, begin, ease(Reflect.forcedEase(fade)));
                    throw new Unsupported("Unwhitelisted fade " + name);
                }
                throw new Unsupported("Unknown PA modifier " + name);
            }
            if (name.equals(LR_FROZEN))
                return new LrFrozen(visit(Reflect.frozenSource(value), domain, out));
            if (value.getClass() == AnimationContainer.class)
                return new Container(visit(((AnimationContainer<?>) value).getAnim(), domain, out));
            throw new Unsupported("Unknown PA animation " + name);
        }

        private NodeRef hostRef(AbstractModifier modifier) {
            Object host = get(Reflect.HOST, modifier);
            if (host == null) throw new Unsupported("Unhosted PA modifier");
            Integer id = ids.get(host);
            if (id == null) throw new Unsupported("Modifier host outside graph");
            return new NodeRef(id);
        }

        private ClipRef clipRef(KeyframeAnimation clip) {
            ClipRef ref = clipRefs.get(clip);
            return ref != null ? ref : catalog.ref(clip);
        }

        private KeyframePlayer keyframe(KeyframeAnimationPlayer player) {
            KeyframeAnimation clip = player.getData();
            ClipRef ref = clipRef(clip);
            if (!verified.contains(clip)) {
                if (!catalog.sameContent(clip, ref))
                    throw new Unsupported("Clip tracks changed after registration");
                verified.add(clip);
            }
            // One pass: equal sizes and every actor key in the clip is key set equality, which
            // still takes precedence over a track mutation.
            var clipParts = clip.getBodyParts();
            if (player.bodyParts.size() != clipParts.size())
                throw new Unsupported("Per-actor body part inventory differs from clip");
            boolean mutated = false;
            for (var entry : player.bodyParts.entrySet()) {
                var part = clipParts.get(entry.getKey());
                if (part == null && !clipParts.containsKey(entry.getKey()))
                    throw new Unsupported("Per-actor body part inventory differs from clip");
                if (!mutated && !entry.getValue().part.equals(part)) mutated = true;
            }
            if (mutated) throw new Unsupported("Per-actor clip track mutation");
            // Equal content, so the actor's part names are the registered clip's.
            var parts = catalog.bodyParts(ref);
            FirstPersonConfiguration config = player.getFirstPersonConfiguration(0);
            int mask =
                    (config.isShowRightArm() ? 1 : 0)
                            | (config.isShowLeftArm() ? 2 : 0)
                            | (config.isShowRightItem() ? 4 : 0)
                            | (config.isShowLeftItem() ? 8 : 0);
            return new KeyframePlayer(
                    ref,
                    player.isActive(),
                    get(Reflect.CURRENT_TICK, player),
                    player.isLoopStarted(),
                    get(Reflect.TICK_DELTA, player),
                    player.perspective,
                    catalog.firstPerson(mask),
                    firstPersonMode(player.getFirstPersonMode(0)),
                    catalog.metadata(ref),
                    overlay(clip),
                    parts);
        }
    }

    /**
     * {@code Arrays.equals(canonical(actor), contentWithMetadata(actor, copy of stored))} without
     * serializing or copying: the name is the actor's on both sides, every other field {@link
     * #canonical} writes is compared in its written form (raw float bits, ease id byte). Strings
     * {@code writeUTF} could refuse take the serializing path, so even that failure is the same.
     */
    static boolean sameContent(KeyframeAnimation actor, KeyframeAnimation stored) {
        Object name = actor.extraData.get("name");
        if (name instanceof String text && text.length() > MAX_PLAIN_UTF)
            return java.util.Arrays.equals(
                    canonical(actor), contentWithMetadata(actor, stored.copy()));
        if (actor.beginTick != stored.beginTick
                || actor.endTick != stored.endTick
                || actor.stopTick != stored.stopTick
                || actor.isInfinite != stored.isInfinite
                || actor.returnToTick != stored.returnToTick
                || actor.isEasingBefore != stored.isEasingBefore) return false;
        var a = actor.getBodyParts();
        var b = stored.getBodyParts();
        for (String key : a.keySet())
            if (key.length() > MAX_PLAIN_UTF)
                return java.util.Arrays.equals(
                        canonical(actor), contentWithMetadata(actor, stored.copy()));
        if (a.size() != b.size() || !a.keySet().equals(b.keySet())) return false;
        for (var entry : a.entrySet()) {
            var x = entry.getValue();
            var y = b.get(entry.getKey());
            if (x == y) continue;
            if (x.isBendable != y.isBendable || x.isScalable != y.isScalable) return false;
            if (!sameState(x.x, y.x)
                    || !sameState(x.y, y.y)
                    || !sameState(x.z, y.z)
                    || !sameState(x.pitch, y.pitch)
                    || !sameState(x.yaw, y.yaw)
                    || !sameState(x.roll, y.roll)
                    || !sameState(x.bend, y.bend)
                    || !sameState(x.bendDirection, y.bendDirection)
                    || !sameState(x.scaleX, y.scaleX)
                    || !sameState(x.scaleY, y.scaleY)
                    || !sameState(x.scaleZ, y.scaleZ)) return false;
        }
        return true;
    }

    /** Characters below which modified UTF-8 can never exceed {@code writeUTF}'s 65535 bytes. */
    private static final int MAX_PLAIN_UTF = 65535 / 3;

    private static boolean sameState(
            KeyframeAnimation.StateCollection.State x, KeyframeAnimation.StateCollection.State y) {
        if (x == y) return true;
        if (x == null || y == null) return false;
        if (!x.name.equals(y.name)
                || Float.floatToRawIntBits(x.defaultValue)
                        != Float.floatToRawIntBits(y.defaultValue)
                || Float.floatToRawIntBits(x.threshold) != Float.floatToRawIntBits(y.threshold)
                || x.isEnabled() != y.isEnabled()) return false;
        var p = x.getKeyFrames();
        var q = y.getKeyFrames();
        if (p == q) return true;
        if (p.size() != q.size()) return false;
        for (int i = 0; i < p.size(); i++) {
            var f = p.get(i);
            var g = q.get(i);
            if (f.tick != g.tick
                    || Float.floatToRawIntBits(f.value) != Float.floatToRawIntBits(g.value)
                    || f.ease.getId() != g.ease.getId()
                    || (f.easingArg == null) != (g.easingArg == null)
                    || f.easingArg != null
                            && Float.floatToRawIntBits(f.easingArg)
                                    != Float.floatToRawIntBits(g.easingArg)) return false;
        }
        return true;
    }

    private static byte[] contentWithMetadata(KeyframeAnimation actor, KeyframeAnimation base) {
        Object name = actor.extraData.get("name");
        if (name == null) base.extraData.remove("name");
        else base.extraData.put("name", name);
        return canonical(base);
    }

    /** Per-actor metadata the LR/TaCZ selectors read from {@code extraData}. */
    static MetadataOverlay overlay(KeyframeAnimation clip) {
        Object name = clip.extraData.get("name");
        if (!(name instanceof String text)) throw new Unsupported("Clip without a name");
        return new MetadataOverlay(text, Boolean.TRUE.equals(clip.extraData.get(LR_ATTACK)));
    }

    /** A per-actor view whose metadata overlay differs from the shared clip (driven isolation). */
    private static KeyframeAnimation overlaid(KeyframeAnimation clip, MetadataOverlay overlay) {
        KeyframeAnimation copy = clip.copy();
        copy.extraData.put("name", overlay.name());
        if (overlay.lrAttackAction()) copy.extraData.put(LR_ATTACK, Boolean.TRUE);
        else copy.extraData.remove(LR_ATTACK);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractModifier> modifiers(ModifierLayer<?> layer) {
        return (List<AbstractModifier>) get(Reflect.MODIFIERS, layer);
    }

    static Ease ease(dev.kosmx.playerAnim.core.util.Ease ease) {
        int id = ease.getId();
        for (Ease value : Ease.values()) if (value.id() == id) return value;
        throw new Unsupported("Unknown native ease id " + id);
    }

    static dev.kosmx.playerAnim.core.util.Ease ease(Ease ease) {
        var resolved = dev.kosmx.playerAnim.core.util.Ease.getEase((byte) ease.id());
        if (resolved.getId() != ease.id()) throw new Unsupported("Ease id " + ease.id());
        return resolved;
    }

    static FirstPersonMode firstPersonMode(
            dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode mode) {
        return switch (mode) {
            case NONE -> FirstPersonMode.NONE;
            case VANILLA -> FirstPersonMode.VANILLA;
            case THIRD_PERSON_MODEL -> FirstPersonMode.THIRD_PERSON_MODEL;
            case DISABLED -> FirstPersonMode.DISABLED;
        };
    }

    static dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode firstPersonMode(
            FirstPersonMode mode) {
        return switch (mode) {
            case NONE -> dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.NONE;
            case VANILLA -> dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.VANILLA;
            case THIRD_PERSON_MODEL ->
                    dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.THIRD_PERSON_MODEL;
            case DISABLED -> dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.DISABLED;
        };
    }

    private static <T> T get(Field field, Object owner) {
        try {
            @SuppressWarnings("unchecked")
            T value = (T) field.get(owner);
            return value;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field field, Object owner, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pinned PA 2.0.4 / LR bf2 private members; resolved once, failing loudly on a pin change. */
    static final class Reflect {
        static final Field RUNNING = field(KeyframeAnimationPlayer.class, "isRunning");
        static final Field CURRENT_TICK = field(KeyframeAnimationPlayer.class, "currentTick");
        static final Field LOOP_STARTED = field(KeyframeAnimationPlayer.class, "isLoopStarted");
        static final Field TICK_DELTA = field(KeyframeAnimationPlayer.class, "tickDelta");
        static final Field FADE_TIME = field(AbstractFadeModifier.class, "time");
        static final Field FADE_LENGTH = field(AbstractFadeModifier.class, "length");
        static final Field FADE_BEGIN = field(AbstractFadeModifier.class, "beginAnimation");
        static final Field INSTRUCTED = field(AdjustmentModifier.class, "instructedFadeout");
        static final Field REMAINING = field(AdjustmentModifier.class, "remainingFadeout");
        static final Field DELTA = field(SpeedModifier.class, "delta");
        static final Field SHIFTED = field(SpeedModifier.class, "shiftedDelta");
        static final Field HOST = field(AbstractModifier.class, "host");
        static final Field MODIFIERS = field(ModifierLayer.class, "modifiers");
        static final Field LAYER_ANIMATION = field(ModifierLayer.class, "animation");
        static final Field STACK_LAYERS = field(AnimationStack.class, "layers");

        private static volatile Field standardEase, forcedEase, frozenSource;
        private static volatile ClassLoader lrLoader = PaGraph.class.getClassLoader();
        private static volatile Constructor<?> frozenCtor, forcedCtor;

        private Reflect() {}

        static dev.kosmx.playerAnim.core.util.Ease standardEase(Object fade) {
            if (standardEase == null) standardEase = field(fade.getClass(), "val$ease");
            return get(standardEase, fade);
        }

        static dev.kosmx.playerAnim.core.util.Ease forcedEase(Object fade) {
            if (forcedEase == null) forcedEase = field(lr(LR_FORCED), "ease");
            return get(forcedEase, fade);
        }

        static Object frozenSource(Object frozen) {
            if (frozenSource == null) frozenSource = field(lr(LR_FROZEN), "source");
            return get(frozenSource, frozen);
        }

        static Object frozen(KeyframeAnimationPlayer source) {
            try {
                if (frozenCtor == null) {
                    var ctor = lr(LR_FROZEN).getDeclaredConstructor(KeyframeAnimationPlayer.class);
                    ctor.setAccessible(true);
                    frozenCtor = ctor;
                }
                return frozenCtor.newInstance(source);
            } catch (ReflectiveOperationException e) {
                throw new Unsupported("LR frozen snapshot unavailable: " + e);
            }
        }

        static Object forced(int length, dev.kosmx.playerAnim.core.util.Ease ease) {
            try {
                if (forcedCtor == null) {
                    var ctor =
                            lr(LR_FORCED)
                                    .getDeclaredConstructor(
                                            int.class, dev.kosmx.playerAnim.core.util.Ease.class);
                    ctor.setAccessible(true);
                    forcedCtor = ctor;
                }
                return forcedCtor.newInstance(length, ease);
            } catch (ReflectiveOperationException e) {
                throw new Unsupported("LR forced fade unavailable: " + e);
            }
        }

        private static Class<?> lr(String name) {
            try {
                return Class.forName(name, true, lrLoader);
            } catch (ClassNotFoundException e) {
                throw new Unsupported("LR Tactical class absent: " + name);
            }
        }

        private static Field field(Class<?> owner, String name) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("Pinned PlayerAnimator member missing: " + name, e);
            }
        }
    }

    /**
     * Test/runtime hook: the loader that defines LR Tactical when it is not on PaGraph's own loader
     * (plain JUnit loads the pinned LR jar in a child loader over the shared PA classes).
     */
    public static void lrClassLoader(ClassLoader loader) {
        Reflect.lrLoader = Objects.requireNonNull(loader, "loader");
        Reflect.forcedEase = null;
        Reflect.frozenSource = null;
        Reflect.frozenCtor = null;
        Reflect.forcedCtor = null;
    }

    /** Refs reachable from the PA part of a graph (pins for rings/send logs/tickets). */
    public static List<ResourceRef> references(AnimationGraphState graph) {
        return List.copyOf(AnimationSchema.references(graph));
    }
}
