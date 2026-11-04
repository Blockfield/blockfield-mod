package com.lunares.blockfield.client.mixin.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import java.util.Set;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create bf4 hanging pose of a driven remote (ADR4 §§4.5/5.3, U8b): membership is the reduced
 * accepted-membership start instead of Create's periodically published set, and the native {@code
 * setHangingPose} formula reads the phase since presentation enter ({@code CreateHanging}) instead
 * of the observer Catnip clock. The native before/after resets and the pose formula run unchanged;
 * undriven players and the local player keep Create's set and clock.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.render.PlayerSkyhookRenderer", remap = false)
public abstract class DrivenSkyhookMixin {
    // The hanging input of the player whose afterSetupAnim is posing (render thread only).
    @Unique private static DrivenPresentation.Hanging blockfield$posing;

    @WrapOperation(
            method = {"beforeSetupAnim", "afterSetupAnim"},
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z",
                            remap = false),
            require = 2,
            remap = false)
    private static boolean blockfield$drivenMembership(
            Set<?> hanging,
            Object id,
            Operation<Boolean> original,
            @Local(argsOnly = true) Player player) {
        var driven = DrivenPresentation.hanging(player);
        if (driven == null) return original.call(hanging, id);
        return driven != DrivenPresentation.NOT_HANGING;
    }

    @Inject(method = "afterSetupAnim", at = @At("HEAD"), remap = false, require = 1)
    private static void blockfield$openPose(
            Player player, HumanoidModel<?> model, CallbackInfo ci) {
        var driven = DrivenPresentation.hanging(player);
        blockfield$posing = driven == DrivenPresentation.NOT_HANGING ? null : driven;
    }

    @Inject(method = "afterSetupAnim", at = @At("RETURN"), remap = false, require = 1)
    private static void blockfield$closePose(
            Player player, HumanoidModel<?> model, CallbackInfo ci) {
        blockfield$posing = null;
    }

    @WrapOperation(
            method = "setHangingPose",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/createmod/catnip/animation/AnimationTickHolder;getTicks(Z)I",
                            remap = false),
            require = 1,
            remap = false)
    private static int blockfield$drivenTicks(boolean includePaused, Operation<Integer> original) {
        var posing = blockfield$posing;
        return posing == null ? original.call(includePaused) : posing.ticks();
    }

    @WrapOperation(
            method = "setHangingPose",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/createmod/catnip/animation/AnimationTickHolder;getPartialTicks()F",
                            remap = false),
            require = 1,
            remap = false)
    private static float blockfield$drivenPartial(Operation<Float> original) {
        var posing = blockfield$posing;
        return posing == null ? original.call() : posing.fraction();
    }
}
