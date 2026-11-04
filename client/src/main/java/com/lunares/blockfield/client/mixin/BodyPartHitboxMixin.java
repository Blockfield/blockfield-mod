package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** F3+B replaces the player AABB with six boxes captured from the native posed model. */
@Mixin(EntityRenderDispatcher.class)
public class BodyPartHitboxMixin {
    @WrapMethod(method = "renderHitbox")
    private static void blockfield$debugPartHitbox(
            PoseStack poseStack,
            VertexConsumer consumer,
            Entity entity,
            float partialTick,
            float red,
            float green,
            float blue,
            Operation<Void> original) {
        if (BodyPartHitboxRenderer.handle(entity, poseStack, consumer, partialTick)) return;
        original.call(poseStack, consumer, entity, partialTick, red, green, blue);
    }
}
