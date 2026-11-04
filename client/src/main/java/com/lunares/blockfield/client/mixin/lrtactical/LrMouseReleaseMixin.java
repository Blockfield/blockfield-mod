package com.lunares.blockfield.client.mixin.lrtactical;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LR consumes mouse presses and releases while its melee/shield/consumable is held, so a button
 * pressed on a gun and released on the knife never un-presses vanilla's KeyMappings: TaCZ's
 * hold-to-aim then re-scopes the next drawn gun on its own. LR has already updated its own held
 * flags before returning, so the release can always reach vanilla.
 */
@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.client.input.LrInputRouter")
public abstract class LrMouseReleaseMixin {
    @Inject(method = "onMousePre", remap = false, at = @At("RETURN"), cancellable = true)
    private static void blockfield$passRelease(
            int button, int action, CallbackInfoReturnable<Boolean> cir) {
        if (action == GLFW.GLFW_RELEASE) cir.setReturnValue(false);
    }
}
