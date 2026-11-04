package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.SnapshotDrivenEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractMinecart.class)
public abstract class CombatMinecartInterpolationMixin implements SnapshotDrivenEntity {
    @Shadow private int lerpSteps;
    @Shadow private Vec3 targetDeltaMovement;

    @Override
    public void blockfield$clearInterpolation() {
        lerpSteps = 0;
        targetDeltaMovement = Vec3.ZERO;
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void blockfield$cartLerp(CallbackInfo ci) {
        if (ClientCombatScene.driven((AbstractMinecart) (Object) this))
            blockfield$clearInterpolation();
    }
}
