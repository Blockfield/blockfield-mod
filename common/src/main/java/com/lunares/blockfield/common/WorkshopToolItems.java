package com.lunares.blockfield.common;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Labels distinguish issued builder tools; every operation still requires server permissions. */
public final class WorkshopToolItems {
    private static final String WAND = "blockfield_workshop_wand";
    private static final String AI = "blockfield_workshop_ai";

    private WorkshopToolItems() {}

    public static boolean isWand(ItemStack stack) {
        return tagged(stack, Items.WOODEN_AXE, WAND);
    }

    public static boolean isAi(ItemStack stack) {
        return tagged(stack, Items.BLAZE_ROD, AI);
    }

    private static boolean tagged(ItemStack stack, Item item, String tag) {
        return stack.is(item)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getBoolean(tag);
    }

    public static ItemStack wand() {
        return create(Items.WOODEN_AXE, WAND, "item.blockfield.workshop_wand");
    }

    public static ItemStack ai() {
        return create(Items.BLAZE_ROD, AI, "item.blockfield.workshop_ai_tool");
    }

    private static ItemStack create(Item item, String tag, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(name));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> data.putBoolean(tag, true));
        return stack;
    }
}
