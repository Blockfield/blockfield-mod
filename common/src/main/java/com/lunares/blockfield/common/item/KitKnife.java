package com.lunares.blockfield.common.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The kit knife as LR Tactical stores it: one melee item, the weapon chosen by a custom-data id.
 */
public final class KitKnife {
    private static final ResourceLocation LR_MELEE_ITEM =
            ResourceLocation.fromNamespaceAndPath("lrtactical", "melee");
    private static final String KNIFE_ID = "lrtactical:dagger";
    private static final String LR_ID_TAG = "MeleeWeaponId";

    private KitKnife() {}

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty()
                && LR_MELEE_ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                && KNIFE_ID.equals(
                        stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .copyTag()
                                .getString(LR_ID_TAG));
    }
}
