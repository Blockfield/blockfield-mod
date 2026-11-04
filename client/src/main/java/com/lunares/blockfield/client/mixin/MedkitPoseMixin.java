package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.MedkitPickup;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third person of a player healing from a thrown medkit: crouched, left hand down at the kit, the
 * weapon held low. Priority above playerAnimator (2000), so TACZ's gun poses do not cover it.
 */
@Mixin(value = PlayerModel.class, priority = 2100)
public abstract class MedkitPoseMixin<T extends LivingEntity> extends HumanoidModel<T> {
    @Shadow @Final public ModelPart leftSleeve;
    @Shadow @Final public ModelPart rightSleeve;

    private MedkitPoseMixin(ModelPart root) {
        super(root);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void blockfield$medkitCrouch(
            T entity, float pos, float speed, float age, float yaw, float pitch, CallbackInfo ci) {
        if (MedkitPickup.posingBody(entity)) crouching = true;
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void blockfield$medkitReach(
            T entity, float pos, float speed, float age, float yaw, float pitch, CallbackInfo ci) {
        if (!MedkitPickup.posingBody(entity)) return;
        leftArm.xRot = -0.75f;
        leftArm.yRot = 0.25f;
        leftArm.zRot = 0;
        rightArm.xRot = -0.35f;
        rightArm.yRot = -0.1f;
        rightArm.zRot = 0;
        leftSleeve.copyFrom(leftArm);
        rightSleeve.copyFrom(rightArm);
    }
}
