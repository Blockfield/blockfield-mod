package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.CombatVehicleScope;
import com.lunares.blockfield.client.combat.SnapshotDrivenEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Boat.class)
public abstract class CombatBoatInterpolationMixin implements SnapshotDrivenEntity {
    @Shadow private int lerpSteps;

    @Shadow
    protected abstract float getSinglePassengerXOffset();

    @Shadow
    public abstract Boat.Type getVariant();

    @Override
    public void blockfield$clearInterpolation() {
        lerpSteps = 0;
    }

    @Inject(method = "tickLerp", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$boatLerp(CallbackInfo ci) {
        if (ClientCombatScene.driven((Boat) (Object) this)) {
            blockfield$clearInterpolation();
            ci.cancel();
        }
    }

    /**
     * Driven R passenger attachment (U6, ADR4 §4.5): the exact native offset formula sampled at the
     * scope's R yaw instead of the live boat yaw. Undriven boats keep the native path; the minecart
     * formula is yaw-free, so it needs no override (pinned by fixture).
     */
    @Inject(
            method = "getPassengerAttachmentPoint",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void blockfield$drivenAttachment(
            Entity passenger,
            EntityDimensions dimensions,
            float partialTick,
            CallbackInfoReturnable<Vec3> cir) {
        Boat self = (Boat) (Object) this;
        CombatVehicleScope.VehicleFrame frame = CombatVehicleScope.frame(self.getId());
        if (frame == null || !ClientCombatScene.driven(self)) return;
        float xOffset = getSinglePassengerXOffset();
        if (self.getPassengers().size() > 1) {
            int index = self.getPassengers().indexOf(passenger);
            xOffset = index == 0 ? 0.2F : -0.6F;
            if (passenger instanceof Animal) xOffset += 0.2F;
        }
        float height =
                getVariant() == Boat.Type.BAMBOO
                        ? dimensions.height() * 0.8888889F
                        : dimensions.height() / 3.0F;
        cir.setReturnValue(CombatVehicleScope.boatAttachment(xOffset, height, frame.yawAtR()));
    }
}
