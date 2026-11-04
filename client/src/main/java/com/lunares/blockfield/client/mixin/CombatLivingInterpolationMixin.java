package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.SnapshotDrivenEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class CombatLivingInterpolationMixin implements SnapshotDrivenEntity {
    @Shadow protected int lerpSteps;
    @Shadow protected int lerpHeadSteps;

    @Override
    public void blockfield$clearInterpolation() {
        lerpSteps = 0;
        lerpHeadSteps = 0;
    }

    // ClientLevel.tickNonPassenger freezes xo before RemotePlayer.tick, and a driven entity only
    // moves at the main-frame write, so the native x-xo walk distance would always be zero. A
    // driven remote pins the R walk triple instead; its own chord integration never runs.
    @Inject(method = "calculateEntityAnimation", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$drivenWalk(boolean includeHeight, CallbackInfo ci) {
        if (ClientCombatScene.drivenSample((LivingEntity) (Object) this) == null) return;
        ci.cancel();
    }
}
