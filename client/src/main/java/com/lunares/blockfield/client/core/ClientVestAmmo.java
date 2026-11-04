package com.lunares.blockfield.client.core;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
     * Зеркало ChestRigManager.innerStacks: формат {stack: <полный ItemStack.save>} + старый {id,
     * Count}.
     */
    private static java.util.List<ItemStack> innerStacks(ItemStack rig) {
        java.util.List<ItemStack> slots = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) slots.add(ItemStack.EMPTY);
        CompoundTag data =
                rig.get(DataComponents.CUSTOM_DATA) == null
                        ? null
                        : rig.get(DataComponents.CUSTOM_DATA).copyTag();
        if (data == null) return slots;
        var level = Minecraft.getInstance().level;
        if (level == null) return slots;
        Tag slotsTag = data.get("Slots");
        if (!(slotsTag instanceof net.minecraft.nbt.ListTag list)) return slots;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot");
            if (slot < 0 || slot >= 9) continue;
            ItemStack stack = readStack(entry, level);
            if (!stack.isEmpty()) slots.set(slot, stack);
        }
        return slots;
    }

    private static ItemStack readStack(CompoundTag entry, net.minecraft.world.level.Level level) {
        ItemStack stack = readBase(entry, level);
        // ChestRigManager keeps the real cell size in Amount: the item codec caps count at 99.
        if (!stack.isEmpty() && entry.contains("Amount", Tag.TAG_INT))
            stack.setCount(entry.getInt("Amount"));
        return stack;
    }

    private static ItemStack readBase(CompoundTag entry, net.minecraft.world.level.Level level) {
        if (entry.contains("stack", Tag.TAG_COMPOUND)) {
            return ItemStack.parse(level.registryAccess(), entry.getCompound("stack"))
                    .orElse(ItemStack.EMPTY);
        }
        ResourceLocation legacyId = ResourceLocation.tryParse(entry.getString("id"));
        if (legacyId == null) return ItemStack.EMPTY;
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(legacyId);
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, entry.getInt("Count"));
    }
}
