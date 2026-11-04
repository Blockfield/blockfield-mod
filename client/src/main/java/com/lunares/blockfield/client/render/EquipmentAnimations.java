package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.combat.MedkitPickup;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Animated first-person models with both hands for the SuperbWarfare class equipment. */
public final class EquipmentAnimations {
    private static final String[] ITEMS = {
        "medical_kit", "armor_plate", "monitor", "drone", "scout_drone"
    };
    // The monitor's linked state is SuperbWarfare's own item property.
    private static final ResourceLocation MONITOR_LINKED =
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "monitor_linked");
    private static final Map<Item, Renderer> RENDERERS = new HashMap<>();

    private EquipmentAnimations() {}

    public static void register() {
        if (BuiltInRegistries.ITEM.containsKey(
                ResourceLocation.fromNamespaceAndPath("superbwarfare", "monitor"))) {
            DroneLinkTransition.register();
        }
        if (!FabricLoader.getInstance().isModLoaded("tacz")) return;
        for (String id : ITEMS) {
            var key = ResourceLocation.fromNamespaceAndPath("superbwarfare", id);
            if (!BuiltInRegistries.ITEM.containsKey(key)) continue;
            Renderer renderer = new Renderer(id);
            Item item = BuiltInRegistries.ITEM.get(key);
            RENDERERS.put(item, renderer);
            BuiltinItemRendererRegistry.INSTANCE.register(item, renderer);
            BedrockItemRenderer.reloadWith("equipment_" + id, renderer);
        }
    }

    /** The animated renderer of a class item, null when it has none. */
    static BedrockItemRenderer renderer(Item item) {
        return RENDERERS.get(item);
    }

    /** Starts the held item's own inspect clip; false when it has none. */
    public static boolean inspect(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        Renderer renderer = RENDERERS.get(held.getItem());
        if (renderer == null) return false;
        var machine = renderer.getStateMachine(held);
        if (machine != null && machine.isInitialized()) machine.trigger("inspect");
        return true;
    }

    private static final class Renderer extends BedrockItemRenderer {
        private final ResourceLocation texture;
        private final ResourceLocation linkedTexture;
        private final boolean monitor;

        Renderer(String id) {
            super(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "equipment/" + id),
                    ResourceLocation.fromNamespaceAndPath("blockfield", "equipment/" + id),
                    ResourceLocation.fromNamespaceAndPath("blockfield", "equipment"));
            texture =
                    ResourceLocation.fromNamespaceAndPath(
                            "blockfield", "textures/item/equipment/" + id + ".png");
            monitor = id.equals("monitor");
            linkedTexture =
                    ResourceLocation.fromNamespaceAndPath(
                            "blockfield", "textures/item/equipment/" + id + "_linked.png");
        }

        @Override
        public ResourceLocation getTextureLocation(ItemStack stack) {
            return isLinked(stack) ? linkedTexture : texture;
        }

        @Override
        protected boolean isLinked(ItemStack stack) {
            var linked = ItemProperties.getProperty(stack, MONITOR_LINKED);
            Minecraft mc = Minecraft.getInstance();
            return linked != null && linked.call(stack, mc.level, mc.player, 0) > 0;
        }

        @Override
        protected boolean virtualUsing() {
            // Only the medkit is drawn virtually: healing from a thrown one (MedkitAnimations).
            return MedkitPickup.channeling();
        }

        @Override
        protected boolean consumeRemoteEnd() {
            return monitor && DroneLinkTransition.takeEnded();
        }

        @Override
        protected boolean consumeRemoteStart() {
            return monitor && DroneLinkTransition.takeStarted();
        }

        @Override
        public long getPutAwayTime(ItemStack stack) {
            return 500;
        }

        @Override
        public void doExtraTransforms(PoseStack pose, BedrockAnimatedModel model, ItemStack stack) {
            super.doExtraTransforms(pose, model, stack);
            // Same view offset as the M18 grip; the rigs were calibrated against it.
            pose.translate(0, 0.02, -0.25);
        }
    }
}
