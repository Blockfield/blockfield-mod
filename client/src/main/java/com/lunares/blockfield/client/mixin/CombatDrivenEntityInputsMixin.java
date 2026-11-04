package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.client.combat.VanillaDrivenScope;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The entity-level half of the driven render inputs (U8b, see {@code
 * CombatDrivenLivingInputsMixin}): riding, freezing and the standing eye height the bed offset
 * reads come from the checkpoint inside a driven remote's render pass.
 */
@Mixin(Entity.class)
public abstract class CombatDrivenEntityInputsMixin {
    /** HumanoidModel's elytra limb divisor reads the raw getter, inside this render scope only. */
    @Inject(method = "getDeltaMovement", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$movement(CallbackInfoReturnable<Vec3> cir) {
        if (DrivenPresentation.inputs(this) == null) return;
        var velocity = VanillaDrivenScope.lerpedVelocity(((Entity) (Object) this).getId());
        if (velocity != null) cir.setReturnValue(velocity);
    }

    @Inject(method = "isPassenger", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$passenger(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().passenger());
    }

    @Inject(method = "isFullyFrozen", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$fullyFrozen(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().fullyFrozen());
    }

    @Inject(method = "getTicksFrozen", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$ticksFrozen(CallbackInfoReturnable<Integer> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.vital().ticksFrozen());
    }

    /** LivingEntityRenderer.render: the bed offset is {@code getEyeHeight(STANDING) - 0.1f}. */
    @Inject(
            method = "getEyeHeight(Lnet/minecraft/world/entity/Pose;)F",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void blockfield$standingEyeHeight(Pose pose, CallbackInfoReturnable<Float> cir) {
        if (pose != Pose.STANDING) return;
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.size().standingEyeHeight());
    }

    /** PlayerRenderer.setupRotations: the swim tilt is {@code -90 - xRot} only in water. */
    @Inject(method = "isInWater", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$inWater(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().inWater());
    }

    /** LivingEntityRenderer.setupRotations: the upside-down lift is {@code bbHeight + 0.1}. */
    @Inject(method = "getBbHeight", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$bbHeight(CallbackInfoReturnable<Float> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.size().bbHeight());
    }
}
