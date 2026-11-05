package com.lunares.blockfield.common.item;

import com.lunares.blockfield.common.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** One storage policy for server absorption and both sides of the rig menu. */
public final class ChestRigAmmo {
    private static final ResourceLocation SUPERB_CREATIVE_BOX =
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "creative_ammo_box");
    // RPG reload takes these from the rig through server.mixin.superbwarfare.AmmoConsumerRigMixin.
    private static final java.util.Set<ResourceLocation> SUPERB_ROCKETS =
            java.util.Set.of(
                    ResourceLocation.fromNamespaceAndPath("superbwarfare", "rpg_rocket_tbg"),
                    ResourceLocation.fromNamespaceAndPath("superbwarfare", "rpg_rocket_standard"));

    private ChestRigAmmo() {}

    public static boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // Preserve the existing TACZ policy. Do not admit the entire SBW ammo/explosives namespace.
        return ("tacz".equals(id.getNamespace()) && id.getPath().contains("ammo"))
                || SUPERB_CREATIVE_BOX.equals(id)
                || SUPERB_ROCKETS.contains(id);
    }

    public static boolean isSuperbCreativeBox(Item item) {
        return item != null && SUPERB_CREATIVE_BOX.equals(BuiltInRegistries.ITEM.getKey(item));
    }

    /**
     * Read-only view of the persisted rig format; no consumption, materialization or recursive
     * search.
     */
    public static boolean containsSuperbCreativeBox(
            ItemStack rig, HolderLookup.Provider registries) {
        for (ItemStack stored : storedStacks(rig, registries))
            if (isSuperbCreativeBox(stored.getItem())) return true;
        return false;
    }

    /**
     * Nonempty rig cells, last entry per slot like ChestRigManager, Amount restored over the
     * codec's 99 cap. Reads the {@code rig_slots} component; rigs saved before the migration are
     * still read from legacy {@code custom_data.Slots}.
     */
    public static java.util.List<ItemStack> storedStacks(
            ItemStack rig, HolderLookup.Provider registries) {
        if (rig.isEmpty() || !rig.is(ModItems.CHEST_RIG)) return java.util.List.of();
        RigCells cells = rig.get(RigCells.TYPE);
        if (cells != null) {
            java.util.List<ItemStack> out = new java.util.ArrayList<>();
            for (ItemStack cell : cells.unpack(ChestRigMenu.RIG_SLOT_COUNT))
                if (!cell.isEmpty()) out.add(cell);
            return out;
        }
        ListTag entries =
                rig.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getList("Slots", Tag.TAG_COMPOUND);
        ItemStack[] cells = new ItemStack[ChestRigMenu.RIG_SLOT_COUNT];
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            int slot = entry.getByte("Slot");
            if (slot < 0 || slot >= cells.length) continue;
            ItemStack stored;
            if (entry.contains("stack", Tag.TAG_COMPOUND)) {
                stored =
                        ItemStack.parse(registries, entry.getCompound("stack"))
                                .orElse(ItemStack.EMPTY);
            } else {
                // Compatibility with the legacy id/Count format read by ChestRigManager.
                ResourceLocation id = ResourceLocation.tryParse(entry.getString("id"));
                if (id == null) continue;
                stored = new ItemStack(BuiltInRegistries.ITEM.get(id), entry.getInt("Count"));
            }
            if (stored.isEmpty()) continue;
            if (entry.contains("Amount", Tag.TAG_ANY_NUMERIC))
                stored.setCount(entry.getInt("Amount"));
            if (!stored.isEmpty()) cells[slot] = stored;
        }
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (ItemStack cell : cells) if (cell != null) out.add(cell);
        return out;
    }
}
