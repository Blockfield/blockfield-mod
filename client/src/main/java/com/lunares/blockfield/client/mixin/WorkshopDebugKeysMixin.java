package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.workshop.WorkshopGhostClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * F3+N and F3+F4 for workshop builders: vanilla gates both debug shortcuts on permission level 2,
 * which builders don't have. Ops and everyone outside the workshop keep the vanilla path.
 */
@Mixin(KeyboardHandler.class)
public abstract class WorkshopDebugKeysMixin {
    @Inject(method = "handleDebugKeys(I)Z", at = @At("HEAD"), cancellable = true)
    private void blockfield$workshopDebugKeys(int key, CallbackInfoReturnable<Boolean> cir) {
        if (WorkshopGhostClient.consumeDebugKey(key)) cir.setReturnValue(true);
    }
}
