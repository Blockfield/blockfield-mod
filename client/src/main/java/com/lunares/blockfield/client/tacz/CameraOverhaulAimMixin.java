package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.CameraOverhaulAim;
import mirsario.cameraoverhaul.utilities.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures only what Camera Overhaul adds to the view (velocity/jump tilt, idle sway, sprint mouse
 * smoothing, explosion shakes): the rotation it receives already carries TACZ's camera animation
 * and recoil frame lag (SimpleBedrockModel wraps {@code Camera.setRotation}), which the shot must
 * not follow.
 */
@Mixin(targets = "mirsario.cameraoverhaul.CameraSystem", remap = false)
public abstract class CameraOverhaulAimMixin {
    @Unique private double blockfield$basePitch;
    @Unique private double blockfield$baseYaw;

    @Inject(method = "modifyCameraTransform", at = @At("HEAD"), remap = false)
    private void blockfield$rememberBase(Transform transform, CallbackInfo ci) {
        blockfield$basePitch = transform.eulerRot.x;
        blockfield$baseYaw = transform.eulerRot.y;
    }

    @Inject(method = "modifyCameraTransform", at = @At("RETURN"), remap = false)
    private void blockfield$storeTilt(Transform transform, CallbackInfo ci) {
        CameraOverhaulAim.pitch = (float) (transform.eulerRot.x - blockfield$basePitch);
        CameraOverhaulAim.yaw = (float) (transform.eulerRot.y - blockfield$baseYaw);
    }
}
