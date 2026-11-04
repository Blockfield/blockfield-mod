package com.lunares.blockfield.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Husk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity.shouldRenderAtSqrDistance caps render distance at boundingBoxSize * 64 *
 * entityDistanceScaling (~67 blocks for a Husk-sized box at the default 100% "Entity Distance"
 * setting, still short of the far lanes even at higher settings) - regardless of
 * RangeTargetTrackingMixin widening server-side tracking, the client itself never draws the entity
 * past that vanilla cap. A firing-range dummy (a named Husk; RangeHuskRenderer uses the same
 * signal) needs to render out to the far lanes, so this bypasses the cap for it specifically
 * instead of raising entityDistanceScaling globally, which would also pull in every other entity at
 * whatever distance the player chose.
 */
@Mixin(Entity.class)
public abstract class RangeDummyRenderDistanceMixin {
    /**
     * Comfortably past RangeTargets.TRACKING_RANGE_BLOCKS (160) so this is never the limiter
     * either.
     */
    private static final double RANGE_DUMMY_RENDER_DISTANCE_SQR = 200.0 * 200.0;

    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void blockfield$rangeDummyIgnoresEntityDistanceSetting(
            double distance, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Husk husk && husk.hasCustomName()) {
            cir.setReturnValue(distance < RANGE_DUMMY_RENDER_DISTANCE_SQR);
        }
    }
}
