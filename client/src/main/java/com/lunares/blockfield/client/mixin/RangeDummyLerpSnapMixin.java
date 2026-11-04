package com.lunares.blockfield.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Husk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A firing-range dummy (a named Husk; RangeHuskRenderer uses the same signal) is server-driven at a
 * deterministic, already-smooth pace (RangeRules.strafeX every tick). LivingEntity.lerpTo would
 * still spread that update over 3 more client ticks on top of it (vanilla's generic smoothing for
 * network jitter on entities whose movement the client can't otherwise predict), stacking an extra
 * ~0.6+ block lag on a 4 b/s target - wider than its own hitbox, so an aimed shot at the rendered
 * position resolves behind the real one. Snap immediately instead: strictly more accurate for an
 * entity that never needs jitter smoothing in the first place.
 */
@Mixin(LivingEntity.class)
public abstract class RangeDummyLerpSnapMixin {
    @Inject(method = "lerpTo", at = @At("HEAD"), cancellable = true)
    private void blockfield$snapRangeDummy(
            double x, double y, double z, float yRot, float xRot, int steps, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Husk husk && husk.hasCustomName()) {
            self.setPos(x, y, z);
            self.setYRot(yRot);
            self.setXRot(xRot);
            self.setYHeadRot(yRot);
            ci.cancel();
        }
    }
}
