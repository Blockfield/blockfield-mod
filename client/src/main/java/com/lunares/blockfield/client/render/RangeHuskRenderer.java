package com.lunares.blockfield.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.HuskRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;

/**
 * Range dummies stay a real {@link Husk} server-side (hit registration, armor, DamageTrial), but
 * must not look like a zombie. Delegates each render call to a player-model renderer for named
 * range dummies and to the vanilla husk renderer for any other husk (e.g. a natural one elsewhere),
 * so nothing outside the firing range changes.
 */
public final class RangeHuskRenderer extends EntityRenderer<Husk> {
    private final EntityRenderer<? super Husk> zombie;
    private final EntityRenderer<? super Husk> player;

    public RangeHuskRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.zombie = new HuskRenderer(context);
        this.player = new PlayerLike(context);
    }

    public static void register() {
        EntityRendererRegistry.register(EntityType.HUSK, RangeHuskRenderer::new);
    }

    /**
     * Entity tags never reach the client; RangeTargets.configure() names every range dummy after
     * its target id and hides the nameplate, so a synced custom name is what the client can see.
     */
    private static boolean isRangeDummy(Husk entity) {
        return entity.hasCustomName();
    }

    @Override
    public void render(
            Husk entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight) {
        if (isRangeDummy(entity))
            player.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        else zombie.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(Husk entity) {
        return isRangeDummy(entity)
                ? player.getTextureLocation(entity)
                : zombie.getTextureLocation(entity);
    }

    /**
     * No zombie geometry/pose: a plain default-skinned humanoid wearing whatever armor is equipped.
     */
    private static final class PlayerLike extends HumanoidMobRenderer<Husk, PlayerModel<Husk>> {
        PlayerLike(EntityRendererProvider.Context context) {
            super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
            this.addLayer(
                    new HumanoidArmorLayer<>(
                            this,
                            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                            context.getModelManager()));
        }

        @Override
        public ResourceLocation getTextureLocation(Husk entity) {
            return DefaultPlayerSkin.getDefaultTexture();
        }
    }
}
