package com.lunares.blockfield.client.core;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Клиентское чтение содержимого разгрузочного жилета (синк accessories). Нужно, чтобы гейт
 * canReload в LocalPlayerReload TACZ пропускал перезарядку, когда патроны лежат только в жилете.
 */
@Environment(EnvType.CLIENT)
public final class ClientVestAmmo {

    private ClientVestAmmo() {}

    public static boolean hasAmmoFor(Player player, ItemStack gun) {
        return countAmmoFor(player, gun) != 0;
    }

    /**
     * Сколько патронов под этот ствол лежит в жилете (плюс содержимое ящиков). Возвращает -1, если
     * в жилете творческий ящик.
     */
    public static int countAmmoFor(Player player, ItemStack gun) {
        int total = 0;
        if (!FabricLoader.getInstance().isModLoaded("accessories")) return 0;
        AccessoriesCapability capability = AccessoriesCapability.get(player);
        if (capability == null) return 0;
        AccessoriesContainer container = capability.getContainers().get("rig");
        if (container == null) return 0;
        var accessories = container.getAccessories();
        for (int i = 0; i < accessories.getContainerSize(); i++) {
            ItemStack rig = accessories.getItem(i);
            if (rig.isEmpty() || !"blockfield:chest_rig".equals(itemId(rig))) continue;
            for (ItemStack stored : innerStacks(rig)) {
                if (stored.getItem() instanceof IAmmo ammo && ammo.isAmmoOfGun(gun, stored)) {
                    total += stored.getCount();
                } else if (stored.getItem() instanceof IAmmoBox box
                        && box.isAmmoBoxOfGun(gun, stored)) {
                    if (box.isAllTypeCreative(stored) || box.isCreative(stored)) return -1;
                    total += box.getAmmoCount(stored);
                }
            }
        }
        return total;
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.toString();
    }

    /**
     * Nonempty rig cells through the shared reader (component first, legacy {@code custom_data}
     * fallback while unmigrated rigs still sync).
     */
    private static java.util.List<ItemStack> innerStacks(ItemStack rig) {
        var level = Minecraft.getInstance().level;
        if (level == null) return java.util.List.of();
        return com.lunares.blockfield.common.item.ChestRigAmmo.storedStacks(
                rig, level.registryAccess());
    }
}
