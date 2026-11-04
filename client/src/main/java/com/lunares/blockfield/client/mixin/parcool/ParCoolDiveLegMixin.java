package com.lunares.blockfield.client.mixin.parcool;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ADR4 §5.2: a driven remote's Dive leg swing interpolates the retained 60 Hz factor increments
 * instead of subtracting its last render's factor. Outside a driven scope bf7 stays native.
 */
@Mixin(
        targets = "com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator$DiveAnimator",
        remap = false)
public abstract class ParCoolDiveLegMixin {
    @WrapOperation(
            method =
                    "animatePost(Lnet/minecraft/class_1657;Lcom/alrex/parcool/common/attachment/common/Parkourability;Lcom/alrex/parcool/client/animation/PlayerModelTransformer;F)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;toRadians(D)D", ordinal = 2),
            require = 1)
    private double blockfield$rightLeg(double degrees, Operation<Double> original) {
        return leg(degrees, original);
    }

    @WrapOperation(
            method =
                    "animatePost(Lnet/minecraft/class_1657;Lcom/alrex/parcool/common/attachment/common/Parkourability;Lcom/alrex/parcool/client/animation/PlayerModelTransformer;F)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;toRadians(D)D", ordinal = 3),
            require = 1)
    private double blockfield$leftLeg(double degrees, Operation<Double> original) {
        return leg(degrees, original);
    }

    private static double leg(double degrees, Operation<Double> original) {
        Float leg = RemoteParCoolPresentation.diveLegDegrees();
        return original.call(leg == null ? degrees : (double) leg);
    }
}
