package com.lunares.blockfield.client.render;

import com.lunares.blockfield.common.item.UniformItem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class UniformRenderers implements AccessoryRenderer {
    private final RenderType renderType;
    private final UniformModel model;

    private UniformRenderers(UniformItem item) {
        renderType = UniformTexture.renderType(UniformModel.kit(item.role, item.side));
        model = new UniformModel(item.role, item.side);
    }

    public static void register() {
        for (var item : BuiltInRegistries.ITEM)
            if (item instanceof UniformItem uniform)
                AccessoriesRendererRegistry.registerRenderer(
                        item, () -> new UniformRenderers(uniform));
    }

    public static boolean isEquipped(LivingEntity entity) {
        if (entity == null) return false;
        var capability = AccessoriesCapability.get(entity);
        if (capability == null) return false;
        var container = capability.getContainers().get("uniform");
        if (container == null
                || container.getAccessories().getContainerSize() == 0
                || !container.shouldRender(0)) return false;
        var cosmetic = container.getCosmeticAccessories().getItem(0);
        var visible =
                !cosmetic.isEmpty()
                                && io.wispforest.accessories.Accessories.config()
                                        .clientOptions
                                        .showCosmeticAccessories()
                        ? cosmetic
                        : container.getAccessories().getItem(0);
        return visible.getItem() instanceof UniformItem;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M extends LivingEntity> void render(
            ItemStack stack,
            SlotReference reference,
            PoseStack poseStack,
            EntityModel<M> parentModel,
            MultiBufferSource buffers,
            int light,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        if (!(parentModel instanceof HumanoidModel<?> parent) || reference.entity().isInvisible())
            return;
        ((HumanoidModel) parent).copyPropertiesTo(model);
        model.setAllVisible(true);
        model.hat.visible = false;
        model.renderToBuffer(
                poseStack, buffers.getBuffer(renderType), light, OverlayTexture.NO_OVERLAY);
    }

    @Override
    public boolean shouldRenderInFirstPerson(
            HumanoidArm arm, ItemStack stack, SlotReference reference) {
        return true;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M extends LivingEntity> void renderOnFirstPerson(
            HumanoidArm arm,
            ItemStack stack,
            SlotReference reference,
            PoseStack poseStack,
            EntityModel<M> parentModel,
            MultiBufferSource buffers,
            int light) {
        if (!(parentModel instanceof HumanoidModel<?> parent)) return;
        ((HumanoidModel) parent).copyPropertiesTo(model);
        ModelPart sleeve = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        sleeve.visible = true;
        sleeve.xRot = 0;
        sleeve.render(poseStack, buffers.getBuffer(renderType), light, OverlayTexture.NO_OVERLAY);
    }
}
