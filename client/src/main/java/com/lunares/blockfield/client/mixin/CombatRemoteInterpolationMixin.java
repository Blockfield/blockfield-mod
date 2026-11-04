package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.SnapshotDrivenEntity;
import net.minecraft.client.player.RemotePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RemotePlayer.class)
public abstract class CombatRemoteInterpolationMixin implements SnapshotDrivenEntity {
    @Override
    public void blockfield$clearMotionInterpolation() {
        lerpDeltaMovementSteps = 0;
    }

    @Shadow private int lerpDeltaMovementSteps;

    @Inject(method = "aiStep", at = @At("HEAD"), require = 1)
    private void blockfield$clearRemoteLerp(CallbackInfo ci) {
        var entity = (RemotePlayer) (Object) this;
        if (ClientCombatScene.driven(entity)) {
            ((SnapshotDrivenEntity) entity).blockfield$clearInterpolation();
            blockfield$clearMotionInterpolation();
        }
    }
}
