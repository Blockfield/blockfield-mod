package com.lunares.blockfield.client.mixin.lrtactical;

import com.lunares.blockfield.common.item.KitKnife;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LR's {@code AttackKeys.tick(Minecraft)} auto-repeats only the light (LEFT) attack while its
 * key/mouse is held; a held heavy (RIGHT) input gets exactly one edge-triggered attempt when first
 * pressed. That single attempt almost always lands mid the shared cooldown while light is already
 * cycling and is then dropped with no retry: holding RMB after LMB looks like the heavy never
 * fires.
 *
 * <p>Injecting at HEAD of the same {@code tick} method (rather than a separate tick hook elsewhere)
 * matters: it runs before LR's own light-repeat check in that method body, so a held heavy claims a
 * freshly-cleared cooldown before light's auto-repeat can ({@code CombatProperties.tick()} runs too
 * late to win that race). Restricted to the knife so other LR melee weapons keep stock behavior.
 */
@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.client.input.AttackKeys")
public abstract class KnifeHeavyHoldMixin {
    // LR's own name for the heavy swing animation, passed to its melee item renderer.
    private static final String HEAVY_ANIMATION = "attack_right";
    // None of these LR types are on the compile classpath (see KnifeSwingMixin); resolve everything
    // reflectively once. A @Shadow method taking MeleeAction (even via @Coerce) fails Mixin's
    // attach
    // step, since shadow method matching does not widen parameter types the way @Inject callbacks
    // do.
    private static Method triggerIfReady;
    private static Object heavyAction;
    private static boolean resolved;

    // LR intercepts the raw RMB event itself (AttackKeys.onMousePre) and never lets it reach
    // vanilla's
    // own KeyMapping tracking while a blocking-use item (shouldBlockUse() = true, true for LR
    // melee) is
    // held: mc.options.keyUse never reports down/clicked for a held knife. These are LR's own "is
    // RMB
    // currently held" flags, the only place that state actually exists.
    @Shadow(remap = false)
    private static boolean useMouseHeld;

    @Shadow(remap = false)
    private static boolean useKeyHeld;

    // Calling LR's own triggerIfReady (the exact method its RMB press runs) instead of only
    // preAttack: preAttack alone prepares the hit, plays the sound and syncs the animation to
    // other clients, but the first-person animation comes from triggerIfReady's own
    // LrItemRenderers.MELEE.triggerAnimation("attack_right") -- without it every auto-repeated
    // heavy swing hits without animating. triggerIfReady also stops block breaking and swings the
    // arm, and re-checks LR's isReady (screen, spectator, gameMode, melee item in hand) itself.
    @Inject(method = "tick", remap = false, at = @At("HEAD"))
    private static void blockfield$retryHeldHeavy(Minecraft mc, CallbackInfo ci) {
        LocalPlayer local = mc.player;
        if (local == null
                || !(useMouseHeld || useKeyHeld)
                || !KitKnife.is(local.getMainHandItem())) {
            return;
        }
        resolve();
        if (triggerIfReady == null || heavyAction == null) return;
        try {
            // A heavy swing still on cooldown is dropped inside CombatProperties.preAttack.
            triggerIfReady.invoke(null, mc, heavyAction, HEAVY_ANIMATION);
        } catch (ReflectiveOperationException ignored) {
            // LR's signature changed underneath us; drop the retry rather than crash the client.
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> actionType = Class.forName("me.xjqsh.lrtactical.api.melee.MeleeAction");
            for (Object constant : actionType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("RIGHT")) {
                    heavyAction = constant;
                    break;
                }
            }
            triggerIfReady =
                    Class.forName("me.xjqsh.lrtactical.client.input.AttackKeys")
                            .getDeclaredMethod(
                                    "triggerIfReady", Minecraft.class, actionType, String.class);
            triggerIfReady.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
            // LR Tactical not installed, or its API changed; nothing to retry.
        }
    }
}
