package com.lunares.blockfield.client.mixin.tweaks;

import com.lunares.blockfield.client.combat.AnimationPolicy;
import kotlin.jvm.functions.Function1;
import kotlin.reflect.KProperty0;
import me.muksc.tacztweaks.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Locks the TaCZ Tweaks {@code smootherCrawlAnimation} YACL widget while the policy forces it (ADR4
 * §6). Every config-screen option bound to a property passes through this lambda, which binds the
 * stored entry and runs the option's own configuration; the widget then renders disabled, showing
 * the stored value. The screen is rebuilt on every open, so no stale lock survives a disconnect.
 */
@Mixin(value = Config.class, remap = false)
public abstract class AnimationPolicyTweaksScreenMixin {
    @Inject(
            method =
                    "option$lambda$1(Lkotlin/reflect/KProperty0;Lkotlin/jvm/functions/Function1;"
                            + "Lme/muksc/tacztweaks/config/Config$OptionBuilder;)Lkotlin/Unit;",
            at = @At("TAIL"),
            require = 1)
    private static void blockfield$lockSmoother(
            KProperty0<?> property,
            Function1<?, ?> configure,
            Config.OptionBuilder<?> option,
            CallbackInfoReturnable<?> cir) {
        if (AnimationPolicy.lockedSmootherCrawl()
                && property.getName().equals("smootherCrawlAnimation")) disable(option);
    }

    // YACL is not on the compile classpath (it only arrives with the pack), so the one builder
    // call goes through its public API interface.
    private static void disable(Object option) {
        try {
            Object builder = option.getClass().getMethod("getBuilder").invoke(option);
            Class.forName(
                            "dev.isxander.yacl3.api.Option$Builder",
                            false,
                            builder.getClass().getClassLoader())
                    .getMethod("available", boolean.class)
                    .invoke(builder, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TaCZ Tweaks smoother-crawl lock failed", e);
        }
    }
}
