package com.lunares.blockfield.common.grenade;

import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Явный список ручных гранат с двумя способами броска; остальные предметы работают как раньше. */
public final class GrenadeThrowSupport {
    public enum Kind {
        LR,
        SBW_HAND,
        SBW_RGO,
        SBW_M18
    }

    // c4 (дистанционный подрыв) и неизвестные типы LR не поддерживаются: у них свой сценарий
    // использования.
    private static final Set<String> LR_THROWABLES =
            Set.of(
                    "lrtactical:m67",
                    "lrtactical:rgn",
                    "lrtactical:smoke_grenade",
                    "lrtactical:flash_grenade",
                    "lrtactical:molotov");

    private GrenadeThrowSupport() {}

    public static Kind kind(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return switch (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) {
            case "superbwarfare:hand_grenade" -> Kind.SBW_HAND;
            case "superbwarfare:rgo_grenade" -> Kind.SBW_RGO;
            case "superbwarfare:m18_smoke_grenade" -> Kind.SBW_M18;
            case "lrtactical:throwable" ->
                    LR_THROWABLES.contains(throwableId(stack)) ? Kind.LR : null;
            default -> null;
        };
    }

    public static String throwableId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString("ThrowableId");
    }
}
