package com.lunares.blockfield.common.combat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The same forgiving, obstructed eye ray on both logical sides. */
public final class MedkitTarget {
    private static final ResourceLocation KIT =
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "medical_kit");

    private MedkitTarget() {}

    public static Entity find(Player player) {
        double reach = player.entityInteractionRange() + 1.0;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1f).scale(reach));
        HitResult wall = player.pick(reach, 1f, false);
        if (wall.getType() != HitResult.Type.MISS) end = wall.getLocation();
        double nearestDistance = eye.distanceToSqr(end);
        Entity nearest = null;
        for (Entity kit :
                player.level()
                        .getEntities(
                                player,
                                new AABB(eye, end).inflate(0.45),
                                entity ->
                                        entity.isAlive()
                                                && KIT.equals(
                                                        BuiltInRegistries.ENTITY_TYPE.getKey(
                                                                entity.getType())))) {
            // An inflated box may extend through a thin wall: check the actual kit too.
            if (!player.canInteractWithEntity(kit, 1.0) || !player.hasLineOfSight(kit)) continue;
            AABB box = kit.getBoundingBox().inflate(0.45, 0.35, 0.45);
            double distance =
                    box.contains(eye)
                            ? 0
                            : box.clip(eye, end).map(eye::distanceToSqr).orElse(Double.MAX_VALUE);
            if (distance <= nearestDistance) {
                nearest = kit;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
