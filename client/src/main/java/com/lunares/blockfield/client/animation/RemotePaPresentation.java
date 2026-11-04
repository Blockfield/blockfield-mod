package com.lunares.blockfield.client.animation;

import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.AdjustmentSource;
import com.lunares.blockfield.common.combat.AnimationTypes.EvaluatorKind;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.TaczAssets;
import com.lunares.blockfield.common.combat.TaczPose;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Driven remote PlayerAnimator presentation (U3, ADR4 §§2.4/5.1). For each render invocation U8
 * installs the frame's immutable projected graph; this materializes fresh native scratch and
 * transplants it into the player's own seven registered PA layers for native render preparation. PA
 * receives frac(A) instead of the ambient partial.
 *
 * <p>While installed, native TaCZ selection, its event listeners and the PA player tick do not
 * change these layers ({@code RemoteTacz*Mixin}); the next install replaces any scratch mutation.
 * The rotation-layer functions stay the native client functions of the player's own factory layers;
 * U8 scopes the global partial to A and collapses the facing endpoints to R. Nothing here affects
 * the local player, which keeps native animation.
 */
public final class RemotePaPresentation {
    private RemotePaPresentation() {}

    /**
     * One presentation revision. {@code fractionR} drives only the policy-selected R lower roots
     * (41/93 and their whole subtrees). The TaCZ checkpoint and retained effective resources supply
     * raw aim, fixed-hand and fallback selection; no live resource lookup is allowed. A null {@code
     * tacz} is the no-gun state (LR-only, ParCool-only or empty-handed remotes); {@code assets} is
     * then optional. The authoritative {@code vanilla} flags decide the native TaCZ stop gate.
     */
    public record Frame(
            AnimationGraphState graph,
            PaGraph.Catalog catalog,
            AnimationPolicy policy,
            float fractionA,
            float fractionR,
            AnimationState.Vanilla vanilla,
            AnimationState.Tacz tacz,
            TaczAssets assets) {
        public Frame {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(vanilla, "vanilla");
            if (tacz != null) {
                Objects.requireNonNull(assets, "assets").require(tacz);
                if (catalog.generation() != tacz.resolvedDisplayId().resourceGeneration())
                    throw new PaGraph.Unsupported("TaCZ graph/display generation mismatch");
                if (!Float.isFinite(tacz.aimProgress()))
                    throw new PaGraph.Unsupported("Nonfinite aim step");
            }
            if (assets != null && assets.catalog() != catalog)
                throw new PaGraph.Unsupported("TaCZ assets and graph use different catalogs");
            if (!(fractionA >= 0 && fractionA < 1) || !(fractionR >= 0 && fractionR < 1))
                throw new PaGraph.Unsupported("Presentation fraction outside [0,1)");
        }

        /** The native {@code setRotationAnglesHead} branch this frame selects for TaCZ. */
        public ThirdPerson thirdPerson() {
            if (TaczPose.stopAll(tacz, vanilla)) return ThirdPerson.STOPPED;
            return tacz.evaluatorKind() == EvaluatorKind.PA
                    ? ThirdPerson.PLAYER_ANIMATOR
                    : ThirdPerson.FALLBACK;
        }
    }

    /**
     * STOPPED: no gun or a gated pose, native {@code stopAllAnimation} (already in the graph) and
     * no formulas. PLAYER_ANIMATOR: the graph alone. FALLBACK: native vanilla hold/aim formulas.
     */
    public enum ThirdPerson {
        STOPPED,
        PLAYER_ANIMATOR,
        FALLBACK
    }

    private static final class Installed {
        final EnumMap<LayerTag, ModifierLayer<IAnimation>> layers;
        final AdjustmentModifier lrRotation, taczRotation;
        final Object applier;
        final AnimationStack stack;
        // Pair entries of the native stack replaced by R-scoped views, restored on clear.
        final Map<Pair<Integer, IAnimation>, Pair<Integer, IAnimation>> lower =
                new IdentityHashMap<>();
        Frame frame;

        Installed(
                EnumMap<LayerTag, ModifierLayer<IAnimation>> layers,
                AdjustmentModifier lrRotation,
                AdjustmentModifier taczRotation,
                Object applier,
                AnimationStack stack) {
            this.layers = layers;
            this.lrRotation = lrRotation;
            this.taczRotation = taczRotation;
            this.applier = applier;
            this.stack = stack;
        }
    }

    /**
     * The scoped lower adapter: the native ordered stack still composes every layer, but the R
     * lower root sets up and samples at frac(R) while upper/action layers use frac(A).
     */
    private record LowerScope(ModifierLayer<IAnimation> layer, Installed owner)
            implements IAnimation {
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
            layer.setupAnim(owner.frame.fractionR());
        }

        @Override
        public dev.kosmx.playerAnim.core.util.Vec3f get3DTransform(
                String part,
                dev.kosmx.playerAnim.api.TransformType type,
                float ignored,
                dev.kosmx.playerAnim.core.util.Vec3f base) {
            return layer.get3DTransform(part, type, owner.frame.fractionR(), base);
        }

        @Override
        public dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode getFirstPersonMode(
                float ignored) {
            return layer.getFirstPersonMode(owner.frame.fractionR());
        }

        @Override
        public dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration
                getFirstPersonConfiguration(float ignored) {
            return layer.getFirstPersonConfiguration(owner.frame.fractionR());
        }
    }

    private static final Map<AbstractClientPlayer, Installed> INSTALLED = new IdentityHashMap<>();
    private static final Map<Object, Float> FRACTIONS = new IdentityHashMap<>();
    private static final Map<AnimationStack, Installed> STACKS = new IdentityHashMap<>();
    private static final Field LAYERS = field(AnimationStack.class, "layers");
    private static final Field MODIFIERS = field(ModifierLayer.class, "modifiers");
    private static final Field ANIMATION = field(ModifierLayer.class, "animation");
    private static final Field SOURCE = field(AdjustmentModifier.class, "source");

    /**
     * Installs fresh scratch for this render invocation. Throws {@link PaGraph.Unsupported} (the
     * caller labels the victim unavailable and calls {@link #clear}) for an unrepresentable graph
     * or a player without the seven registered roots. With the R lower policy the native stack
     * entries at 41 and 93 become {@link LowerScope} views until clear.
     */
    public static void install(AbstractClientPlayer player, Frame frame) {
        if (player instanceof LocalPlayer)
            throw new PaGraph.Unsupported("The local player must keep native animation");
        // Retention backstop (U3 P2-6): a respawned/unloaded entity object never stays driven.
        for (var stale : List.copyOf(INSTALLED.keySet())) if (stale.isRemoved()) clear(stale);
        Installed previous = INSTALLED.get(player);
        var layers = previous != null ? previous.layers : nativeLayers(player);
        AdjustmentModifier lr =
                previous != null ? previous.lrRotation : rotation(layers.get(LayerTag.PA_42));
        AdjustmentModifier tacz =
                previous != null ? previous.taczRotation : rotation(layers.get(LayerTag.PA_96));
        PaGraph.Sources sources =
                new PaGraph.Sources() {
                    @Override
                    public Function<String, Optional<AdjustmentModifier.PartModifier>> source(
                            AdjustmentSource id) {
                        return get(SOURCE, id == AdjustmentSource.LR_BODY_POSE ? lr : tacz);
                    }
                };
        // A probe sample of the same revision: a nonfinite output never reaches ModelParts (P1-5).
        var probe =
                PaGraph.Stack.materialize(frame.graph(), frame.catalog(), frame.policy(), sources);
        probe.sample(
                frame.fractionA(),
                frame.policy().lowerBodyDomain() == PhaseDomain.R
                        ? frame.fractionR()
                        : frame.fractionA());
        probe.requireFinite();
        var scratch =
                PaGraph.Stack.materialize(frame.graph(), frame.catalog(), frame.policy(), sources);
        for (LayerTag tag : PaGraph.PA_ROOTS) transplant(scratch.layer(tag), layers.get(tag));
        Installed installed =
                previous != null
                        ? previous
                        : new Installed(
                                layers,
                                lr,
                                tacz,
                                ((IAnimatedPlayer) player).playerAnimator_getAnimation(),
                                ((IAnimatedPlayer) player).getAnimationStack());
        installed.frame = frame;
        scopeLower(installed, frame.policy().lowerBodyDomain() == PhaseDomain.R);
        FRACTIONS.put(installed.applier, frame.fractionA());
        STACKS.put(installed.stack, installed);
        INSTALLED.put(player, installed);
    }

    private static void scopeLower(Installed installed, boolean r) {
        List<Pair<Integer, IAnimation>> entries = get(LAYERS, installed.stack);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (!r) {
                var original = installed.lower.remove(entry);
                if (original != null) entries.set(i, original);
                continue;
            }
            if (installed.lower.containsKey(entry)) continue;
            for (LayerTag tag : List.of(LayerTag.PA_41, LayerTag.PA_93))
                if (entry.getRight() == installed.layers.get(tag)) {
                    var scoped =
                            new Pair<Integer, IAnimation>(
                                    entry.getLeft(),
                                    new LowerScope(installed.layers.get(tag), installed));
                    installed.lower.put(scoped, entry);
                    entries.set(i, scoped);
                }
        }
    }

    /** Returns the player to native layers: original rotation modifiers, no animations. */
    public static void clear(AbstractClientPlayer player) {
        Installed installed = INSTALLED.remove(player);
        if (installed == null) return;
        FRACTIONS.remove(installed.applier);
        STACKS.remove(installed.stack);
        scopeLower(installed, false);
        for (var entry : installed.layers.entrySet()) {
            ModifierLayer<IAnimation> layer = entry.getValue();
            List<AbstractModifier> modifiers = get(MODIFIERS, layer);
            modifiers.clear();
            AdjustmentModifier original =
                    entry.getKey() == LayerTag.PA_42
                            ? installed.lrRotation
                            : entry.getKey() == LayerTag.PA_96 ? installed.taczRotation : null;
            if (original != null) {
                original.setHost(layer);
                modifiers.add(original);
            }
            layer.setAnimation(null);
        }
    }

    public static void clearAll() {
        for (var player : List.copyOf(INSTALLED.keySet())) clear(player);
    }

    /**
     * Backstop clears (U3 P2-6) besides U8's own: entity unload, a world change and every
     * connection boundary return players to native layers and drop their retained objects.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register(
                (entity, level) -> {
                    if (entity instanceof AbstractClientPlayer player) clear(player);
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
                .AFTER_CLIENT_WORLD_CHANGE
                .register((client, level) -> clearAll());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> clearAll());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> clearAll());
    }

    /** Retained driven players; bounded by the loaded, unremoved remotes. */
    public static int installed() {
        return INSTALLED.size();
    }

    public static boolean driven(Entity entity) {
        return entity instanceof AbstractClientPlayer player && INSTALLED.containsKey(player);
    }

    /** frac(A) for a driven player's PA processor, or null for native partials. */
    public static Float fraction(Object processor) {
        return FRACTIONS.get(processor);
    }

    public static boolean driven(AnimationStack stack) {
        return STACKS.containsKey(stack);
    }

    private static Frame frame(Entity entity) {
        if (!(entity instanceof AbstractClientPlayer player)) return null;
        Installed installed = INSTALLED.get(player);
        return installed == null ? null : installed.frame;
    }

    /** The checkpoint raw aim step; null for a native player or a no-gun frame. */
    public static Float aim(Entity entity) {
        Frame frame = frame(entity);
        return frame == null || frame.tacz() == null ? null : frame.tacz().aimProgress();
    }

    /** Authoritative fixed-hand; no gun is native {@code orElse(false)}; null for native. */
    public static Boolean fixedHand(Entity entity) {
        Frame frame = frame(entity);
        return frame == null ? null : frame.tacz() != null && frame.tacz().fixedHand();
    }

    /** The retained fallback animation name of a FALLBACK frame; null for a native player. */
    public static String fallback(Entity entity) {
        Frame frame = frame(entity);
        return frame == null ? null : frame.assets().require(frame.tacz()).fallback();
    }

    /** The driven TaCZ third-person branch, or null for a native (local/undriven) player. */
    public static ThirdPerson thirdPerson(Entity entity) {
        Frame frame = frame(entity);
        return frame == null ? null : frame.thirdPerson();
    }

    private static void transplant(
            ModifierLayer<IAnimation> source, ModifierLayer<IAnimation> target) {
        List<AbstractModifier> from = get(MODIFIERS, source);
        List<AbstractModifier> to = get(MODIFIERS, target);
        to.clear();
        for (AbstractModifier modifier : from) {
            modifier.setHost(target);
            to.add(modifier);
        }
        // Field write, not setAnimation: the materialized modifier links are already exact.
        set(ANIMATION, target, source.getAnimation());
    }

    private static EnumMap<LayerTag, ModifierLayer<IAnimation>> nativeLayers(
            AbstractClientPlayer player) {
        AnimationStack stack = ((IAnimatedPlayer) player).getAnimationStack();
        List<Pair<Integer, IAnimation>> entries = get(LAYERS, stack);
        var layers = new EnumMap<LayerTag, ModifierLayer<IAnimation>>(LayerTag.class);
        for (var entry : entries)
            for (LayerTag tag : PaGraph.PA_ROOTS)
                if (AnimationGraphState.priority(tag) == entry.getLeft()) {
                    if (entry.getRight().getClass() != ModifierLayer.class
                            || layers.containsKey(tag))
                        throw new PaGraph.Unsupported("Native PA root layout at " + tag);
                    @SuppressWarnings("unchecked")
                    var layer = (ModifierLayer<IAnimation>) entry.getRight();
                    layers.put(tag, layer);
                }
        if (layers.size() != PaGraph.PA_ROOTS.size())
            throw new PaGraph.Unsupported("Player lacks the seven registered PA roots");
        return layers;
    }

    private static AdjustmentModifier rotation(ModifierLayer<IAnimation> layer) {
        List<AbstractModifier> modifiers = get(MODIFIERS, layer);
        if (modifiers.isEmpty() || !(modifiers.getFirst() instanceof AdjustmentModifier native_))
            throw new PaGraph.Unsupported("Rotation layer lacks its native adjustment");
        return native_;
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

    @SuppressWarnings("unchecked")
    private static <T> T get(Field field, Object owner) {
        try {
            return (T) field.get(owner);
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
}
