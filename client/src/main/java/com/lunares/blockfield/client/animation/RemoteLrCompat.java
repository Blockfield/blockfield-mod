package com.lunares.blockfield.client.animation;

import com.lunares.blockfield.common.combat.PaGraph;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * The separate native LR compatibility view of a driven remote (ADR4 §5.1, U4). LR bf2's selector
 * and attack listener keep running for that remote at their original client-tick/event schedule,
 * but on private shadow layers ticked like its native stack: they never touch the driven geometry
 * that {@link RemotePaPresentation} installs. Their only lasting effect is LR's native writes to
 * the shared clip cache ({@code name}, {@code lr_attack_action}), which the local player's native
 * selector still observes exactly as without driving. No gameplay, sound or particle path runs
 * here.
 *
 * <p>U8 calls {@link #attach} before the first install of a remote and {@link #detach} after its
 * {@code RemotePaPresentation.clear}; the native LR state moves into and back out of the view, so
 * an undriven remote continues natively. U8 installs through {@link #install}; a missing view
 * refuses the presentation, never fabricates native history.
 */
public final class RemoteLrCompat {
    private RemoteLrCompat() {}

    public static final ResourceLocation UPPER =
            ResourceLocation.fromNamespaceAndPath("lrtactical", "upper");
    public static final ResourceLocation LOWER =
            ResourceLocation.fromNamespaceAndPath("lrtactical", "lower");
    public static final ResourceLocation ROTATION =
            ResourceLocation.fromNamespaceAndPath("tacz", "rotation");

    private static final class View {
        final ModifierLayer<IAnimation> upper = new ModifierLayer<>();
        final ModifierLayer<IAnimation> lower = new ModifierLayer<>();
        final ModifierLayer<IAnimation> rotation = new ModifierLayer<>();

        ModifierLayer<IAnimation> layer(ResourceLocation id) {
            if (UPPER.equals(id)) return upper;
            if (LOWER.equals(id)) return lower;
            return ROTATION.equals(id) ? rotation : null;
        }
    }

    // Entities compare by identity; a removed remote's view is collected with it.
    private static final Map<AbstractClientPlayer, View> VIEWS = new WeakHashMap<>();
    private static final Field MODIFIERS = field("modifiers");

    /** Moves the remote's native LR layer state into a new view before driving starts. */
    public static void attach(AbstractClientPlayer player) {
        if (player instanceof LocalPlayer)
            throw new IllegalArgumentException("The local player keeps its native LR layers");
        if (VIEWS.containsKey(player)) return;
        if (RemotePaPresentation.driven(player))
            throw new PaGraph.Unsupported("Cannot seed LR native history from driven scratch");
        var view = new View();
        for (var id : List.of(UPPER, LOWER, ROTATION)) move(real(player, id), view.layer(id));
        VIEWS.put(player, view);
    }

    /** Returns the view's state to the remote's native layers after driving ends. */
    public static void detach(AbstractClientPlayer player) {
        View view = VIEWS.remove(player);
        if (view == null) return;
        for (var id : List.of(UPPER, LOWER, ROTATION)) move(view.layer(id), real(player, id));
    }

    public static void clearAll() {
        VIEWS.clear();
    }

    /**
     * The LR layer the native selector/listener addresses for {@code player}: the view's shadow for
     * a driven remote, otherwise null (native associated data).
     */
    public static ModifierLayer<IAnimation> layer(
            AbstractClientPlayer player, ResourceLocation id) {
        View view = VIEWS.get(player);
        if (view == null) {
            if (!RemotePaPresentation.driven(player)) return null;
            throw new PaGraph.Unsupported(
                    "LR native compatibility view unavailable; attach required");
        }
        return view.layer(id);
    }

    /** U8's LR-aware install boundary: validate native history before any driven layer changes. */
    public static void install(AbstractClientPlayer player, RemotePaPresentation.Frame frame) {
        if (!VIEWS.containsKey(player))
            throw new PaGraph.Unsupported(
                    "LR native compatibility view unavailable; attach required");
        RemotePaPresentation.install(player, frame);
    }

    /** True immediately if a driven remote has lost its native compatibility history. */
    public static boolean unseeded(AbstractClientPlayer player) {
        return RemotePaPresentation.driven(player) && !VIEWS.containsKey(player);
    }

    /** The native stack's tick for the view, once per player tick: only active layers advance. */
    public static void tick(Player player) {
        if (!(player instanceof AbstractClientPlayer remote)) return;
        View view = VIEWS.get(remote);
        if (view == null) return;
        for (var layer : List.of(view.upper, view.lower, view.rotation))
            if (layer.isActive()) layer.tick();
    }

    private static ModifierLayer<IAnimation> real(
            AbstractClientPlayer player, ResourceLocation id) {
        @SuppressWarnings("unchecked")
        var layer =
                (ModifierLayer<IAnimation>)
                        ((IAnimatedPlayer) player).playerAnimator_getAnimation(id);
        if (layer == null) throw new IllegalStateException("Missing native LR layer " + id);
        return layer;
    }

    /**
     * Moves animation and fade modifiers; a native rotation adjustment (first modifier of the
     * factory layer) stays on the real layer.
     */
    private static void move(ModifierLayer<IAnimation> from, ModifierLayer<IAnimation> to) {
        List<AbstractModifier> source = get(MODIFIERS, from);
        List<AbstractModifier> target = get(MODIFIERS, to);
        for (var iterator = source.iterator(); iterator.hasNext(); ) {
            var modifier = iterator.next();
            if (modifier instanceof AdjustmentModifier) continue;
            iterator.remove();
            modifier.setHost(to);
            target.add(modifier);
        }
        // setAnimation relinks each remaining modifier chain onto its layer's animation.
        to.setAnimation(from.getAnimation());
        from.setAnimation(null);
    }

    private static Field field(String name) {
        try {
            Field field = ModifierLayer.class.getDeclaredField(name);
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
}
