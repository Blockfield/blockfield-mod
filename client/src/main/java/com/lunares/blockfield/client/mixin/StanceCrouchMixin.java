package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.MedkitPickup;
import com.lunares.blockfield.common.StanceTransition;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eases the vanilla crouch bones over {@link StanceTransition#TICKS} instead of one tick. Only
 * while the live stance runs: settled poses keep the exact vanilla boolean path, and driven mirrors
 * restore the coefficient snapped (no wire field), so they render exactly as before.
 *
 * <p>Native order (pinned 1.21.1 {@code HumanoidModel.setupAnim} bytecode: attack, the crouch
 * branch, bob, swim) is the composition contract the server port ({@code VanillaBodyPose.pose})
 * follows. The additive arm term therefore lands right after the attack animation, where the
 * vanilla branch would have added it; the TAIL below only rewrites fields bob/swim never touch.
 */
@Mixin(net.minecraft.client.model.HumanoidModel.class)
public abstract class StanceCrouchMixin<T extends LivingEntity> {
    @Shadow public boolean crouching;
    @Shadow public net.minecraft.client.model.geom.ModelPart head;
    @Shadow public net.minecraft.client.model.geom.ModelPart body;
    @Shadow public net.minecraft.client.model.geom.ModelPart rightArm;
    @Shadow public net.minecraft.client.model.geom.ModelPart leftArm;
    @Shadow public net.minecraft.client.model.geom.ModelPart rightLeg;
    @Shadow public net.minecraft.client.model.geom.ModelPart leftLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void blockfield$suppressInstantCrouch(
            T entity, float walk, float speed, float age, float yaw, float pitch, CallbackInfo ci) {
        // A retained medkit pose forces full crouch exactly like the server projection
        // (VanillaAnimSync.project: crouchAmount = 1); its vanilla branch must stand.
        if (easing(entity) && !MedkitPickup.posingBody(entity)) crouching = false;
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/model/HumanoidModel;setupAttackAnimation(Lnet/minecraft/world/entity/LivingEntity;F)V",
                            shift = At.Shift.AFTER))
    private void blockfield$easeCrouchArms(
            T entity, float walk, float speed, float age, float yaw, float pitch, CallbackInfo ci) {
        StanceTransition stance = eased(entity);
        if (stance == null) return;
        // Additive like the vanilla branch, before bob/swim compose over it.
        float add = StanceTransition.Crouch.at(stance.crouchness()).armXRotAdd();
        leftArm.xRot += add;
        rightArm.xRot += add;
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void blockfield$easeCrouch(
            T entity, float walk, float speed, float age, float yaw, float pitch, CallbackInfo ci) {
        StanceTransition stance = eased(entity);
        if (stance == null) return;
        var k = StanceTransition.Crouch.at(stance.crouchness());
        body.xRot = k.torsoXRot();
        leftLeg.z = rightLeg.z = k.legZ();
        leftLeg.y = rightLeg.y = k.legY();
        head.y = k.headY();
        body.y = k.torsoY();
        leftArm.y = rightArm.y = k.armY();
    }

    private static StanceTransition eased(LivingEntity entity) {
        if (MedkitPickup.posingBody(entity)) return null;
        return stance(entity);
    }

    private static StanceTransition stance(LivingEntity entity) {
        if (!(entity instanceof Player player)) return null;
        if (!(player instanceof StanceTransition.Access access)) return null;
        StanceTransition stance = access.blockfield$stance();
        // Settled poses (and non-easing ones) keep the vanilla boolean path exactly.
        if (stance == null || !stance.eases() || !stance.changing()) return null;
        return stance;
    }

    private static boolean easing(LivingEntity entity) {
        return stance(entity) != null;
    }
}
