package com.lunares.blockfield.client.grenade;

import com.lunares.blockfield.client.render.BedrockItemRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** M18's native use and cloud stay on the server; only its first-person renderer changes. */
public final class M18SmokeAnimations {
    private static final ResourceLocation ITEM =
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "m18_smoke_grenade");
    private static Renderer renderer;

    private M18SmokeAnimations() {}

    public static void register() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("tacz")
                || !loader.isModLoaded("lrtactical")
                || !BuiltInRegistries.ITEM.containsKey(ITEM)) return;
        renderer = new Renderer();
        BuiltinItemRendererRegistry.INSTANCE.register(BuiltInRegistries.ITEM.get(ITEM), renderer);
        BedrockItemRenderer.reloadWith("m18_smoke_render", renderer);
    }

    public static void onNativeRelease(LivingEntity entity, boolean cookingComplete) {
        if (renderer == null || entity != Minecraft.getInstance().player) return;
        ItemStack stack = entity.getUseItem();
        if (!entity.isUsingItem()
                || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ITEM)
                || (!cookingComplete && entity.getTicksUsingItem() < 4)) return;
        // Polling isUsingItem between render frames can miss a successful four-tick throw.
        renderer.nativeThrow();
    }

    private static final class Renderer extends BedrockItemRenderer {
        private static final ResourceLocation TEXTURE =
                ResourceLocation.fromNamespaceAndPath(
                        "lrtactical", "textures/throwable/smoke_grenade_uv.png");

        Renderer() {
            super(
                    ResourceLocation.fromNamespaceAndPath(
                            "lrtactical", "throwable/smoke_grenade_geo"),
                    ResourceLocation.fromNamespaceAndPath("lrtactical", "throwable/smoke_grenade"),
                    ResourceLocation.fromNamespaceAndPath("blockfield", "m18_smoke"));
        }

        @Override
        protected void prepare(List<ObjectAnimation> animations) {
            for (var animation : animations) {
                if ((animation.name.equals("unlock_safe") || animation.name.equals("throw"))
                        && animation.getSoundChannel() != null) {
                    // The native M18 already plays the pin-pull and throw sounds.
                    animation.getSoundChannel().content = null;
                }
            }
        }

        @Override
        public ResourceLocation getTextureLocation(ItemStack stack) {
            return TEXTURE;
        }

        @Override
        public long getPutAwayTime(ItemStack stack) {
            return 380;
        }

        @Override
        public void doExtraTransforms(PoseStack pose, BedrockAnimatedModel model, ItemStack stack) {
            super.doExtraTransforms(pose, model, stack);
            // Native RZ180 reverses Y; keep the canister and its grip above the HUD.
            pose.translate(0, 0.02, -0.25);
        }

        void nativeThrow() {
            if (stateMachine != null && stateMachine.isInitialized())
                stateMachine.trigger("m18_throw");
        }
    }
}
