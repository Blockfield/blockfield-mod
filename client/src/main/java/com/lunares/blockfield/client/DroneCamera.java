package com.lunares.blockfield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class DroneCamera {

    private static final ResourceLocation MONITOR =
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "monitor");

    private DroneCamera() {}

    /**
     * Источник луча пинга. SBW при полёте на дроне не переставляет cameraEntity, а подменяет
     * позицию камеры своим миксином, поэтому дрон приходится искать по NBT монитора в руке — иначе
     * Ping Wheel ставит метку туда, куда смотрит стоящий на земле игрок.
     */
    public static Entity pingSource(Minecraft mc) {
        Entity camera = mc.getCameraEntity();
        if (camera != mc.player || mc.player == null || mc.level == null) return camera;
        ItemStack stack = mc.player.getMainHandItem();
        if (!MONITOR.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return camera;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return camera;
        CompoundTag tag = data.copyTag();
        if (!tag.getBoolean("Using") || !tag.getBoolean("Linked")) return camera;
        String linked = tag.getString("LinkedDrone");
        if (linked.isEmpty()) return camera;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (linked.equals(entity.getStringUUID())) return entity;
        }
        return camera;
    }
}
