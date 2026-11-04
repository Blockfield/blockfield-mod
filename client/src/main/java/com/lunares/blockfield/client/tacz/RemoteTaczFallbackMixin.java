package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.tacz.guns.api.client.other.ThirdPersonManager;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.client.animation.third.InnerThirdPersonManager;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Driven entry of the native {@code InnerThirdPersonManager.setRotationAnglesHead}: its no-gun and
 * pose stop gate come from the authoritative frame, never the live entity (U3 P1-3). STOPPED and PA
 * frames only cancel (the graph already holds stopAll/PA); non-PA guns run the native hold/aim
 * formulas fed the checkpoint's raw synced aim step at A (ADR4 §4.3; never interpolated, {@code <=
 * 0} selects hold).
 */
@Mixin(value = InnerThirdPersonManager.class, remap = false)
public abstract class RemoteTaczFallbackMixin {
    @Inject(method = "setRotationAnglesHead", at = @At("HEAD"), cancellable = true)
    private static void blockfield$retainedEntry(
            LivingEntity entity,
            ModelPart rightArm,
            ModelPart leftArm,
            ModelPart body,
            ModelPart head,
            float partial,
            CallbackInfo ci) {
        driven(entity, rightArm, leftArm, body, head, ci);
    }

    private static void driven(
            LivingEntity entity,
            ModelPart rightArm,
            ModelPart leftArm,
            ModelPart body,
            ModelPart head,
            CallbackInfo ci) {
        var branch = RemotePaPresentation.thirdPerson(entity);
        if (branch == null) return;
        if (branch == RemotePaPresentation.ThirdPerson.FALLBACK) {
            float aim = RemotePaPresentation.aim(entity);
            var animation = ThirdPersonManager.getAnimation(RemotePaPresentation.fallback(entity));
            if (aim <= 0) animation.animateGunHold(entity, rightArm, leftArm, body, head);
            else animation.animateGunAim(entity, rightArm, leftArm, body, head, aim);
        }
        ci.cancel();
    }

    @Inject(method = "playVanillaAnimation", at = @At("HEAD"), cancellable = true)
    private static void blockfield$drivenFallback(
            LivingEntity entity,
            ModelPart rightArm,
            ModelPart leftArm,
            ModelPart body,
            ModelPart head,
            IGunOperator operator,
            GunDisplayInstance display,
            CallbackInfo ci) {
        driven(entity, rightArm, leftArm, body, head, ci);
    }
}
