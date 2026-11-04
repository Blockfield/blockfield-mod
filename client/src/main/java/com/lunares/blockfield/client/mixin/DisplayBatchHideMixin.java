package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.DisplayBatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A display baked into a DisplayBatcher buffer is drawn by that buffer, not again as an entity. */
@Mixin(EntityRenderDispatcher.class)
public class DisplayBatchHideMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void blockfield$baked(
            E entity,
            Frustum frustum,
            double x,
            double y,
            double z,
            CallbackInfoReturnable<Boolean> cir) {
        if (DisplayBatcher.hides(entity)) cir.setReturnValue(false);
    }
}
