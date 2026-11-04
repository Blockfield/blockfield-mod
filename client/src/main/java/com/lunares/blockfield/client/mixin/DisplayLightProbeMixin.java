package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lunares.blockfield.client.render.DisplayLight;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A display whose origin is buried in a block takes its light where it is drawn (DisplayLight). */
@Mixin(EntityRenderer.class)
public class DisplayLightProbeMixin {
    @WrapOperation(
            method = "getPackedLightCoords",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/core/BlockPos;containing(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos blockfield$unburied(
            Position probe, Operation<BlockPos> original, @Local(argsOnly = true) Entity entity) {
        BlockPos pos = original.call(probe);
        return entity instanceof Display display ? DisplayLight.probe(display, pos) : pos;
    }
}
