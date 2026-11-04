package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.DisplayBatcher;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A packet addressing a detached baked display finds it, back in the level first
 * (DisplayBatcher.detach).
 */
@Mixin(ClientLevel.class)
public class DisplayDetachMixin {
    @Inject(method = "getEntity", at = @At("RETURN"), cancellable = true)
    private void blockfield$findDetached(int id, CallbackInfoReturnable<Entity> cir) {
        if (cir.getReturnValue() == null) {
            Entity detached = DisplayBatcher.attach(id);
            if (detached != null) cir.setReturnValue(detached);
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void blockfield$attachBeforeRemoval(
            int id, Entity.RemovalReason reason, CallbackInfo ci) {
        DisplayBatcher.attach(id);
    }
}
