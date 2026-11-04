package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.lunares.blockfield.client.render.RenderCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADR4 §8.3 capture of the already-posed native model at its final {@code renderToBuffer}; never
 * writes a bone or consumes server bones. The entry records the pass, invocation id, the
 * dispatcher's entry pose and the double origin/offset/camera of this invocation.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class CombatRenderedBodyMixin {
    @Unique private LivingEntity blockfield$rendered;
    @Unique private Matrix4f blockfield$entry;
    @Unique private Vec3 blockfield$origin, blockfield$offset, blockfield$camera;
    @Unique private RenderCapture.Pass blockfield$pass;
    @Unique private long blockfield$invocation;

    @Inject(
            method =
                    "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            require = 1)
    private void blockfield$entry(
            LivingEntity entity,
            float yaw,
            float partial,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            CallbackInfo ci) {
        blockfield$rendered = entity;
        blockfield$entry =
                BodyPartHitboxRenderer.wantsCapture() && BodyPartHitboxRenderer.captures(entity)
                        ? new Matrix4f(poses.last().pose())
                        : null;
        if (blockfield$entry == null) return;
        @SuppressWarnings("unchecked")
        var renderer = (EntityRenderer<LivingEntity>) (Object) this;
        // LevelRenderer.renderEntity lerps xOld (not xo) at this partial before the dispatcher.
        blockfield$origin =
                new Vec3(
                        Mth.lerp(partial, entity.xOld, entity.getX()),
                        Mth.lerp(partial, entity.yOld, entity.getY()),
                        Mth.lerp(partial, entity.zOld, entity.getZ()));
        blockfield$offset = renderer.getRenderOffset(entity, partial);
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        blockfield$camera =
                dispatcher == null || dispatcher.camera == null
                        ? null
                        : dispatcher.camera.getPosition();
        blockfield$pass = BodyPartHitboxRenderer.pass();
        blockfield$invocation = BodyPartHitboxRenderer.nextInvocation();
    }

    @WrapOperation(
            method =
                    "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 1)
    private void blockfield$posedBoxes(
            EntityModel<?> model,
            PoseStack poses,
            VertexConsumer consumer,
            int light,
            int overlay,
            int color,
            Operation<Void> original) {
        if (model instanceof HumanoidModel<?> humanoid && blockfield$entry != null)
            BodyPartHitboxRenderer.capture(
                    blockfield$rendered,
                    humanoid,
                    blockfield$entry,
                    poses.last().pose(),
                    blockfield$pass,
                    blockfield$invocation,
                    blockfield$origin,
                    blockfield$offset,
                    blockfield$camera);
        original.call(model, poses, consumer, light, overlay, color);
    }
}
