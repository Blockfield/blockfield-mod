package com.lunares.blockfield.client.tacz;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.functional.MuzzleFlashRender;
import com.tacz.guns.client.resource.pojo.display.gun.MuzzleFlash;
import com.tacz.guns.compat.iris.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MuzzleFlashRender.class)
public abstract class MuzzleFlashRenderMixin {
    @Unique private static boolean blockfield$firstPerson;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void blockfield$rememberContext(
            PoseStack poseStack,
            VertexConsumer buffer,
            ItemDisplayContext context,
            int light,
            int overlay,
            CallbackInfo ci) {
        blockfield$firstPerson = context.firstPerson();
    }

    @ModifyExpressionValue(
            method = "renderMuzzleFlash",
            remap = false,
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lcom/tacz/guns/client/model/functional/MuzzleFlashRender;muzzleFlashStartMark:Z"))
    private static boolean blockfield$followAnimatedMuzzle(boolean firstFrame) {
        // The cached pose outlives the frame while recoil moves the barrel, so first person
        // recaptures it.
        // The local player's third-person hand layer also renders with isSelf and a world-space
        // pose;
        // recapturing there pinned the flash to a world direction.
        return firstFrame || blockfield$firstPerson;
    }

    @Inject(method = "doRender", at = @At("TAIL"), remap = false)
    private static void blockfield$flushInHandProjection(
            int light, int overlay, MuzzleFlash muzzleFlash, long time, CallbackInfo ci) {
        // Without a shader pack TACZ draws first person inside HandRenderer.renderSolid with its
        // own projection and
        // a model-view without camera rotation, then restores vanilla matrices. The gun flushes its
        // batch in there,
        // the flash did not: vanilla flushed it later under the camera rotation, throwing it across
        // the screen.
        if (blockfield$firstPerson && !IrisCompat.isPackInUseQuick()) {
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        }
    }
}
