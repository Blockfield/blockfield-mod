package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.CombatVehicleScope;
import com.lunares.blockfield.client.combat.SnapshotDrivenEntity;
import com.lunares.blockfield.client.combat.SnapshotResolver;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity", remap = false)
public abstract class CombatSbwInterpolationMixin implements SnapshotDrivenEntity {
    @Shadow
    protected abstract void setInterpolationSteps(int value);

    @Shadow
    public abstract void setRoll(float value);

    @Shadow
    public abstract void setPrevRoll(float value);

    @Shadow
    public abstract void setTurretYRot(float value);

    @Shadow
    public abstract void setTurretXRot(float value);

    @Shadow
    public abstract void setTurretYRotO(float value);

    @Shadow
    public abstract void setTurretXRotO(float value);

    @Shadow
    public abstract void setGunYRot(float value);

    @Shadow
    public abstract void setGunXRot(float value);

    @Shadow
    public abstract void setGunYRotO(float value);

    @Shadow
    public abstract void setGunXRotO(float value);

    @Override
    public void blockfield$clearInterpolation() {
        setInterpolationSteps(0);
    }

    @Override
    public void blockfield$sampleAngles(SnapshotResolver.Sample sample) {
        if (sample.before().kind() != 2) return;
        // Only exact records carry reads; a migration stream never drives an exact passenger.
        var placement = sample.before().placement();
        if (placement != null)
            CombatVehicleScope.retainReads(((Entity) (Object) this).getId(), placement.reads());
        float roll = sample.angles().get(2).floatValue();
        setRoll(roll);
        setPrevRoll(roll);
        float ty = sample.angles().get(3).floatValue();
        setTurretYRot(ty);
        setTurretYRotO(ty);
        float tx = sample.angles().get(4).floatValue();
        setTurretXRot(tx);
        setTurretXRotO(tx);
        float gy = sample.angles().get(5).floatValue();
        setGunYRot(gy);
        setGunYRotO(gy);
        float gx = sample.angles().get(6).floatValue();
        setGunXRot(gx);
        setGunXRotO(gx);
    }

    @Inject(method = "handleClientSync", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$sbwLerp(CallbackInfo ci) {
        if (ClientCombatScene.driven((Entity) (Object) this)) {
            blockfield$clearInterpolation();
            ci.cancel();
        }
    }
}
