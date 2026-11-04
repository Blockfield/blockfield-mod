package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.StanceTransition;
import com.lunares.blockfield.common.combat.AnimationResources.ClipRef;
import com.lunares.blockfield.common.combat.AnimationResources.DefinitionRef;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.Outcome;
import com.lunares.blockfield.common.combat.AnimationTypes.Pose;
import com.lunares.blockfield.common.combat.AnimationTypes.StartKind;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** LR bf4 transitions on U3's single mixed PA graph; no ticks, render samples or side effects. */
public final class LrPose {
    private LrPose() {}

    public static final int UPPER_IDLE_FADE = 4, LOWER_IDLE_FADE = 8, ATTACK_FADE = 4;
    public static final int STOP_FADE = 8, ROTATION_FADE = 4;

    /**
     * One native LR call on one layer: the outcome and the journal id its new nodes consumed (-1:
     * no node was created, including a stop that only drops an inactive actor).
     */
    public record Step(LayerTag layer, StartKind kind, Outcome outcome, long eventSequence) {}

    /** Algorithm/read-layout contribution; U9 additionally hashes all effective assets/lookups. */
    public static DefinitionRef manifest(PaGraph.Catalog catalog, LayerTag rotationWinner) {
        if (rotationWinner != LayerTag.PA_42 && rotationWinner != LayerTag.PA_96)
            throw new PaGraph.Unsupported("Invalid LR rotation alias winner: " + rotationWinner);
        return catalog.define(
                ("lr.transitions.v5\n"
                                + "lr.bf4=cb03ae8e190e1858822207041a22e1ba9ae2832420292b394f33b6a5696f0472\n"
                                + "pa.2.0.4=86ef8a23a5bc8f86d67ed37f3a93380d3946bad64123b22862ed057e7b624efd\n"
                                + "metadata=name:String,lr_attack_action:Boolean\n"
                                + "stack-order=40,41,42,93,94,95,96\n"
                                + "upper=40,lower=41,rotation=associated(tacz:rotation;winner="
                                + AnimationGraphState.priority(rotationWinner)
                                + ")\n"
                                + "fade=idle:4/8,stance:8-linear-from-shared-coefficient,attack:4,stop:8/8/4,ease:8\n"
                                + "idle=frozen-keyframe-source-at-integer,forced-fade,disabled-first-person\n"
                                + "attack=live-predecessor,replace-active,disabled-first-person\n"
                                + "rotation=latch-nonnull-no-fade;movement=double-strict(.001,.05)\n"
                                + "stop=replace-null,fade-if-animation-active\n"
                                + "selector=suppress(sleep,fall-fly,climb,swim)|config-null>stopAll;"
                                + "upper-unless-attack,lower,rotation\n"
                                + "stages=accept:INTER_TICK_ACCEPT,tick:NATIVE_TICK,select:POST_NATIVE_TICK\n"
                                + "driven-metadata=private-actor-overlay;idle-clears-attack-flag\n"
                                + "projection=integer-bucket-patch,no-retick,policy-scoped-lower\n")
                        .getBytes(StandardCharsets.UTF_8),
                List.of());
    }

    /** Native {@code onClientTick} suppression: sleeping/fall-flying pose, climbing or swimming. */
    public static boolean suppressed(Pose pose, boolean climbing, boolean swimming) {
        return pose == Pose.SLEEPING || climbing || swimming || pose == Pose.FALL_FLYING;
    }

    /** Native idle selector uses strict double comparisons (including exactly .001 and .05). */
    public static String movement(
            boolean ground, double y, double horizontalSqr, boolean crouching, boolean sprinting) {
        if (!ground) return y > 0 ? "jump" : "fall";
        if (crouching) return horizontalSqr > .001 ? "sneak_walk" : "sneak";
        if (sprinting) return "sprint";
        if (horizontalSqr > .001) return horizontalSqr > .05 ? "run" : "walk";
        return "idle";
    }

    public static LayerTag rotationLayer(PaGraph.Stack stack) {
        return stack.associated(PaGraph.ROTATION_KEY) == stack.layer(LayerTag.PA_42)
                ? LayerTag.PA_42
                : LayerTag.PA_96;
    }

    public static boolean attackActive(PaGraph.Stack stack) {
        return stack.layer(LayerTag.PA_40).getAnimation() instanceof KeyframeAnimationPlayer actor
                && Boolean.TRUE.equals(actor.getData().extraData.get(PaGraph.LR_ATTACK))
                && actor.getCurrentTick() < actor.getData().endTick;
    }

    /**
     * One player's native {@code IdleAnimationHandler.onClientTick} body at its canonical stage, in
     * native call order. {@code display} is the effective held display (null: not a melee weapon or
     * no display); a display without config routes to {@code stopAll}.
     */
    public static List<Step> tick(
            PaGraph.Stack stack,
            LrAssets.Display display,
            boolean suppressed,
            String movement,
            LongSupplier sequence) {
        var steps = new ArrayList<Step>(3);
        for (LayerTag layer : List.of(LayerTag.PA_40, LayerTag.PA_41, rotationLayer(stack)))
            steps.add(select(stack, display, suppressed, movement, layer, sequence));
        return steps;
    }

    /** The {@link #tick} call for one layer; the upper attack guard is read at its own call. */
    public static Step select(
            PaGraph.Stack stack,
            LrAssets.Display display,
            boolean suppressed,
            String movement,
            LayerTag layer,
            LongSupplier sequence) {
        return select(stack, display, suppressed, movement, layer, sequence, null);
    }

    public static Step select(
            PaGraph.Stack stack,
            LrAssets.Display display,
            boolean suppressed,
            String movement,
            LayerTag layer,
            LongSupplier sequence,
            StanceTransition.Snapshot stance) {
        syncLayer(stack, layer, stance);
        LrAssets.Config config = display == null ? null : display.config();
        if (suppressed || config == null) return stopStep(stack, layer, sequence);
        if (layer == LayerTag.PA_40)
            return attackActive(stack)
                    ? new Step(layer, StartKind.NEW, Outcome.ACTIVE_ONCE_IGNORED, -1)
                    : idleStep(stack, display, true, movement, sequence, stance);
        if (layer == LayerTag.PA_41)
            return idleStep(stack, display, false, movement, sequence, stance);
        if (layer == rotationLayer(stack)) return rotationStep(stack, display, sequence);
        throw new PaGraph.Unsupported("Not an LR selection layer: " + layer);
    }

    /**
     * Native {@code MeleeAnimationListener} for an accepted action: the event's display, its upper
     * clip for {@code (action, count)}, then {@code playAttackAnimation}. Absent config/path/clip
     * keep the incumbent.
     */
    public static Step attack(
            PaGraph.Stack stack,
            LrAssets.Display display,
            String action,
            int count,
            LongSupplier sequence) {
        var config = display == null ? null : display.config();
        if (config == null || config.animation() == null)
            return new Step(LayerTag.PA_40, StartKind.NEW, Outcome.MISSING_CONFIG, -1);
        String name = config.clip(true, action, count);
        ClipRef clip = name == null ? null : display.clip(name);
        return transition(stack, LayerTag.PA_40, sequence, used -> attack(stack, clip, used));
    }

    private static Step idleStep(
            PaGraph.Stack stack,
            LrAssets.Display display,
            boolean upper,
            String movement,
            LongSupplier sequence,
            StanceTransition.Snapshot stance) {
        LayerTag tag = upper ? LayerTag.PA_40 : LayerTag.PA_41;
        // Native applyIdleAnimationFor/applyLowerBodyAnimation skip a null path or name.
        if (display.config().animation() == null)
            return new Step(tag, StartKind.NEW, Outcome.NO_CHANGE, -1);
        String name = display.config().clip(upper, movement, 0);
        if (name == null) return new Step(tag, StartKind.NEW, Outcome.NO_CHANGE, -1);
        ClipRef clip = display.clip(name);
        return transition(stack, tag, sequence, used -> idle(stack, tag, clip, name, used, stance));
    }

    private static Step rotationStep(
            PaGraph.Stack stack, LrAssets.Display display, LongSupplier sequence) {
        LayerTag tag = rotationLayer(stack);
        if (display.config().animation() == null)
            return new Step(tag, StartKind.NEW, Outcome.NO_CHANGE, -1);
        ClipRef empty = display.clip("empty");
        return transition(stack, tag, sequence, used -> rotation(stack, empty, used));
    }

    private static Step stopStep(PaGraph.Stack stack, LayerTag tag, LongSupplier sequence) {
        return transition(stack, tag, sequence, used -> stop(stack, tag, used));
    }

    /**
     * Re-samples the layer's installed LR stance fades from this tick's stance snapshot. The
     * selector runs every tick on the canonical stack and on every replayed observer decision, so
     * both runtimes track the shared coefficient instead of a separate fade clock.
     */
    @SuppressWarnings("unchecked")
    private static void syncLayer(
            PaGraph.Stack stack, LayerTag tag, StanceTransition.Snapshot stance) {
        if (stance == null) return;
        try {
            var modifiers =
                    (java.util.List<dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier>)
                            PaGraph.Reflect.MODIFIERS.get(stack.layer(tag));
            StanceLayers.syncLrLayer(modifiers, stance);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Pinned PA layer layout", error);
        }
    }

    private static Step transition(
            PaGraph.Stack stack,
            LayerTag tag,
            LongSupplier sequence,
            java.util.function.Function<LongSupplier, Outcome> operation) {
        boolean active = stack.layer(tag).isActive();
        long[] used = {-1};
        Outcome outcome = operation.apply(() -> used[0] = sequence.getAsLong());
        StartKind kind =
                outcome == Outcome.STOPPED
                        ? StartKind.STOP_FADE
                        : active ? StartKind.REPLACE : StartKind.NEW;
        return new Step(tag, kind, outcome, used[0]);
    }

    /**
     * Native idle always adds a forced fade, even for an inactive source. A keyframe source is
     * frozen at its exact integer state; existing modifiers remain in their native order.
     */
    public static Outcome idle(
            PaGraph.Stack stack, LayerTag tag, ClipRef clip, String name, LongSupplier sequence) {
        return idle(stack, tag, clip, name, sequence, null);
    }

    public static Outcome idle(
            PaGraph.Stack stack,
            LayerTag tag,
            ClipRef clip,
            String name,
            LongSupplier sequence,
            StanceTransition.Snapshot stance) {
        requireBody(tag);
        if (clip == null) return Outcome.MISSING_CLIP;
        Objects.requireNonNull(name, "idle clip name");
        var layer = stack.layer(tag);
        var current = layer.getAnimation();
        if (current instanceof KeyframeAnimationPlayer actor
                && actor.isActive()
                && name.equals(actor.getData().extraData.get("name"))) return Outcome.NO_CHANGE;
        var next = actor(stack, clip, false, name);
        long event = sequence.getAsLong();
        stack.started(next, event, null);
        IAnimation begin = current;
        if (current instanceof KeyframeAnimationPlayer source) {
            begin = frozen(source);
            // The frozen wrapper retains the predecessor's phase, not the replacement's start.
            stack.started(begin, event, source);
        }
        var fade = forced(tag == LayerTag.PA_40 ? UPPER_IDLE_FADE : LOWER_IDLE_FADE);
        fade.setBeginAnimation(begin);
        fade = StanceLayers.lrFade(fade, stance);
        stack.started(fade, event, next);
        layer.addModifierLast(fade);
        layer.setAnimation(next);
        return Outcome.SELECTED;
    }

    /** Accepted attacks replace even another active attack; a missing clip keeps the incumbent. */
    public static Outcome attack(PaGraph.Stack stack, ClipRef clip, LongSupplier sequence) {
        if (clip == null) return Outcome.MISSING_CLIP;
        var next = actor(stack, clip, true, null);
        var layer = stack.layer(LayerTag.PA_40);
        long event = sequence.getAsLong();
        stack.started(next, event, null);
        var fade = fade(ATTACK_FADE);
        // PA's two-argument replace installs the fade only over an active animation.
        if (fades(layer.getAnimation())) stack.started(fade, event, next);
        layer.replaceAnimationWithFade(fade, next, false);
        return Outcome.SELECTED;
    }

    /**
     * LR latches rotation while the associated layer is non-null, including an inactive actor; the
     * latch means the replace never installs its fade.
     */
    public static Outcome rotation(PaGraph.Stack stack, ClipRef clip, LongSupplier sequence) {
        if (clip == null) return Outcome.MISSING_CLIP;
        var layer = stack.associated(PaGraph.ROTATION_KEY);
        if (layer.getAnimation() != null) return Outcome.NO_CHANGE;
        var next = new KeyframeAnimationPlayer(stack.copy(clip));
        stack.started(next, sequence.getAsLong(), null);
        layer.replaceAnimationWithFade(fade(ROTATION_FADE), next, false);
        return Outcome.SELECTED;
    }

    /**
     * Native {@code stopAnimation}: replace with null. The fade exists only over an active
     * animation; an inactive one is dropped without a node. Repeated stops while fading are no-ops.
     */
    public static Outcome stop(PaGraph.Stack stack, LayerTag tag, LongSupplier sequence) {
        if (tag != LayerTag.PA_40 && tag != LayerTag.PA_41 && tag != rotationLayer(stack))
            throw new PaGraph.Unsupported("Not an addressable LR layer: " + tag);
        var layer = stack.layer(tag);
        IAnimation current = layer.getAnimation();
        if (current == null) return Outcome.NO_CHANGE;
        var fade = fade(tag == rotationLayer(stack) ? ROTATION_FADE : STOP_FADE);
        if (fades(current)) stack.started(fade, sequence.getAsLong(), null);
        layer.replaceAnimationWithFade(fade, null, false);
        return Outcome.STOPPED;
    }

    private static boolean fades(IAnimation current) {
        return current != null && current.isActive();
    }

    private static KeyframeAnimationPlayer actor(
            PaGraph.Stack stack, ClipRef ref, boolean attack, String name) {
        // A Stack-private copy: its per-actor metadata never reaches the retained resource.
        var clip = stack.copy(ref);
        if (attack) clip.extraData.put(PaGraph.LR_ATTACK, Boolean.TRUE);
        else {
            clip.extraData.put("name", name);
            clip.extraData.remove(PaGraph.LR_ATTACK);
        }
        return new KeyframeAnimationPlayer(clip, 0).setFirstPersonMode(FirstPersonMode.DISABLED);
    }

    private static void requireBody(LayerTag tag) {
        if (tag != LayerTag.PA_40 && tag != LayerTag.PA_41)
            throw new PaGraph.Unsupported("Not an LR body layer: " + tag);
    }

    private static AbstractFadeModifier fade(int length) {
        return AbstractFadeModifier.standardFadeIn(length, Ease.INOUTSINE);
    }

    // U3's loader-aware constructors: plain JUnit defines LR in a child loader.
    private static IAnimation frozen(KeyframeAnimationPlayer source) {
        return (IAnimation) PaGraph.Reflect.frozen(source);
    }

    private static AbstractFadeModifier forced(int length) {
        return (AbstractFadeModifier) PaGraph.Reflect.forced(length, Ease.INOUTSINE);
    }
}
