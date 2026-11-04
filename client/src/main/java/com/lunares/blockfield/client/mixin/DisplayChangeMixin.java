package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.DisplayBatcher;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * New synced data of a display (text, block, transformation...) or a move from the server makes its
 * baked section stale (DisplayBatcher); also carries the baked flag.
 */
@Mixin(Display.class)
public class DisplayChangeMixin implements DisplayBatcher.Flag {
    @Unique private boolean blockfield$baked;

    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void blockfield$changed(EntityDataAccessor<?> key, CallbackInfo ci) {
        Display self = (Display) (Object) this;
        if (self.level().isClientSide) DisplayBatcher.changed(self);
    }

    @Inject(method = "lerpTo", at = @At("HEAD"))
    private void blockfield$moved(
            double x, double y, double z, float yRot, float xRot, int steps, CallbackInfo ci) {
        DisplayBatcher.moved((Display) (Object) this, x, y, z, yRot, xRot);
    }

    @Override
    public boolean blockfield$baked() {
        return blockfield$baked;
    }

    @Override
    public void blockfield$setBaked(boolean baked) {
        blockfield$baked = baked;
    }
}
