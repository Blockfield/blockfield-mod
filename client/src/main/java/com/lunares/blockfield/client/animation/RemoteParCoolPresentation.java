package com.lunares.blockfield.client.animation;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.common.attachment.client.Animation;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.lunares.blockfield.common.combat.*;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * U5 per-invocation native scratch. U8 owns receipt/lifecycle validation and calls install/scope.
 */
public final class RemoteParCoolPresentation {
    private RemoteParCoolPresentation() {}

    public record Frame(
            ParCoolFrame value, AnimationPolicy policy, float fractionA, float fractionR) {
        public Frame {
            Objects.requireNonNull(value);
            Objects.requireNonNull(policy);
            if (!(fractionA >= 0 && fractionA < 1) || !(fractionR >= 0 && fractionR < 1))
                throw new IllegalArgumentException("ParCool fraction outside [0,1)");
            AnimationGraphValidator.validateComplete(value.graph(), policy);
            for (var action : value.state().actions())
                for (var input : action.inputs())
                    if (input instanceof AnimationState.CompatibilityInput compatibility
                            && compatibility.policyGeneration() != policy.generation())
                        throw new IllegalArgumentException("ParCool policy generation mismatch");
        }
    }

    private static final ParCoolGraph NATIVE =
            new ParCoolGraph(
                    RemoteParCoolPresentation.class.getClassLoader(),
                    "com.alrex.parcool.client.animation.impl.");
    private static final Map<AbstractClientPlayer, Frame> FRAMES = new IdentityHashMap<>();
    private static final Map<Animation, AbstractClientPlayer> NATIVE_OWNERS =
            new IdentityHashMap<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    public static void install(AbstractClientPlayer player, Frame frame) {
        if (player.isLocalPlayer())
            throw new IllegalArgumentException("Local ParCool stays native");
        FRAMES.put(player, Objects.requireNonNull(frame));
    }

    public static boolean driven(Player player) {
        return FRAMES.containsKey(player);
    }

    /** Construct all scratch before publishing a scope, so failed materialization is atomic. */
    public static Scope scope(AbstractClientPlayer player) {
        Frame frame = FRAMES.get(player);
        if (frame == null) throw new IllegalStateException("No installed ParCool frame");
        var actor =
                (Animator)
                        NATIVE.materialize(frame.value().graph(), frame.value().state().animator());
        // ADR4 §5.2: root and model both read the current R limb input, never a prior render's.
        if (actor instanceof com.alrex.parcool.client.animation.impl.FastRunningAnimator
                || actor
                        instanceof
                        com.alrex.parcool.client.animation.impl.HorizontalWallRunAnimator)
            // The passenger/dead/baby gate reads the checkpoint, as the renderer's walk input does.
            try (var inputs =
                    com.lunares.blockfield.client.combat.DrivenPresentation.scope(player)) {
                put(actor, "limbSwing", limb(player, frame.fractionR()));
            }
        var attachment = frame.value().scratchAttachment();
        var animation = new Animation();
        put(animation, "animator", actor);
        put(
                animation,
                "passiveAnimation",
                ParCoolGraph.passive(
                        com.alrex.parcool.client.animation.PassiveCustomAnimation.class,
                        frame.value().state().passive()));
        put(
                animation,
                "option",
                ParCoolGraph.option(
                        com.alrex.parcool.api.unstable.animation.AnimationOption.class,
                        frame.value().state().option()));
        var scope = new Scope(player, frame, animation, attachment, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** 1.21.1 LivingEntityRenderer.render limb input at p_R: passengers/the dead do not walk. */
    public static float limb(AbstractClientPlayer player, float partialR) {
        if (player.isPassenger() || !player.isAlive()) return 0;
        float limb = player.walkAnimation.position(partialR);
        if (player.isBaby()) limb *= 3;
        return limb;
    }

    public static final class Scope implements AutoCloseable {
        private final AbstractClientPlayer player;
        private final Frame frame;
        private final Animation animation;
        private final Parkourability attachment;
        private final Scope previous;
        private boolean closed;

        private Scope(
                AbstractClientPlayer player,
                Frame frame,
                Animation animation,
                Parkourability attachment,
                Scope previous) {
            this.player = player;
            this.frame = frame;
            this.animation = animation;
            this.attachment = attachment;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("ParCool scope close order");
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Parkourability attachment(Player player) {
        Scope scope = CURRENT.get();
        return scope != null && scope.player == player ? scope.attachment : null;
    }

    public static Animation animation(Player player) {
        Scope scope = CURRENT.get();
        return scope != null && scope.player == player ? scope.animation : null;
    }

    public static Float fractionA(Player player) {
        Scope scope = CURRENT.get();
        return scope == null || scope.player != player ? null : scope.frame.fractionA();
    }

    /**
     * The current driven scope's Dive leg term on its retained grid (ADR4 §5.2), or null for a
     * native invocation. A Dive child shares its host's A age.
     */
    public static Float diveLegDegrees() {
        Scope scope = CURRENT.get();
        if (scope == null) return null;
        var value = scope.frame.value();
        var root = value.state().animator();
        if (root == null) return null;
        var host = actor(value.graph(), root.id());
        if (!(host.state() instanceof AnimationGraphState.DiveHost dive)) return null;
        float fraction = scope.frame.fractionA();
        int alpha = (int) (fraction * 65536f);
        if (alpha / 65536f != fraction)
            throw new IllegalStateException("ParCool A fraction is not a Q16 sample");
        return ParCoolReducers.diveLegDegrees(
                value.state().diveGrid(),
                new AnimationTypes.Time(
                        actor(value.graph(), dive.diveAnimator().id()).tick(), alpha));
    }

    private static AnimationGraphState.Animator actor(AnimationGraphState graph, int id) {
        for (var node : graph.nodes())
            if (node.id() == id) return (AnimationGraphState.Animator) node.data();
        throw new IllegalStateException("Missing ParCool graph node");
    }

    public static void observeNative(Player player, Animation animation) {
        if (driven(player) && animation(player) != animation)
            NATIVE_OWNERS.put(animation, (AbstractClientPlayer) player);
    }

    public static boolean drivenAnimation(Object animation) {
        Scope scope = CURRENT.get();
        return scope != null && scope.animation == animation
                || NATIVE_OWNERS.containsKey(animation);
    }

    public static void requireScope(Player player) {
        if (driven(player) && animation(player) == null)
            throw new IllegalStateException("Driven ParCool render requires invocation scratch");
    }

    public static void clear(AbstractClientPlayer player) {
        for (Scope scope = CURRENT.get(); scope != null; scope = scope.previous)
            if (scope.player == player)
                throw new IllegalStateException("ParCool cleared during render scope");
        FRAMES.remove(player);
        NATIVE_OWNERS
                .entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getValue() != player) return false;
                            // These are mirror-only fields. Do not resume a stale pre-driven native
                            // actor.
                            put(entry.getKey(), "animator", null);
                            put(
                                    entry.getKey(),
                                    "passiveAnimation",
                                    new com.alrex.parcool.client.animation
                                            .PassiveCustomAnimation());
                            put(
                                    entry.getKey(),
                                    "option",
                                    new com.alrex.parcool.api.unstable.animation.AnimationOption());
                            return true;
                        });
    }

    public static void clearAll() {
        if (CURRENT.get() != null)
            throw new IllegalStateException("ParCool reset during render scope");
        for (var player : java.util.List.copyOf(FRAMES.keySet())) clear(player);
    }

    private static void put(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Pinned ParCool animation layout changed: " + name, e);
        }
    }
}
