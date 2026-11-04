package com.lunares.blockfield.common.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Converts legacy 1.20 attachment slots ({Count, id, tag:{AttachmentId...}}) nested in a gun's
 * custom_data to the codec shape TaCZ 1.21.1 reads ({id, count, components}).
 */
public final class AttachmentNbtMigration {
    private AttachmentNbtMigration() {}

    /** Idempotent: slots that already carry components, or lack a legacy tag, are untouched. */
    public static void upgrade(ItemStack gun, String key) {
        if (gun.isEmpty()) return;
        CustomData custom = gun.get(DataComponents.CUSTOM_DATA);
        if (custom == null || !custom.contains(key)) return;
        CompoundTag slot = custom.copyTag().getCompound(key);
        if (slot.contains("components", CompoundTag.TAG_COMPOUND)
                || !slot.contains("tag", CompoundTag.TAG_COMPOUND)) return;
        CompoundTag customData = new CompoundTag();
        CompoundTag legacy = slot.getCompound("tag");
        customData.putString("AttachmentId", legacy.getString("AttachmentId"));
        if (legacy.contains("ZoomNumber"))
            customData.putInt("ZoomNumber", legacy.getInt("ZoomNumber"));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", customData);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", slot.getString("id"));
        stack.putInt("count", slot.contains("Count") ? slot.getByte("Count") : 1);
        stack.put("components", components);
        CustomData.update(DataComponents.CUSTOM_DATA, gun, tag -> tag.put(key, stack));
    }
}
