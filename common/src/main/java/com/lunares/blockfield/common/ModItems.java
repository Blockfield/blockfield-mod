package com.lunares.blockfield.common;

import static com.lunares.blockfield.common.item.UniformItem.Role.*;
import static com.lunares.blockfield.common.item.UniformItem.Side.*;

import com.lunares.blockfield.common.item.UniformItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Cosmetic uniforms use the accessories uniform slot; combat armor still supplies protection. */
public final class ModItems {

    public static final Item SOVIET_UNIFORM = uniform("soviet_uniform", INFANTRY, RED);
    public static final Item NATO_UNIFORM = uniform("nato_uniform", INFANTRY, BLUE);
    public static final Item RED_ENGINEER_UNIFORM = uniform("red_engineer_uniform", ENGINEER, RED);
    public static final Item BLUE_ENGINEER_UNIFORM =
            uniform("blue_engineer_uniform", ENGINEER, BLUE);
    public static final Item RED_SNIPER_UNIFORM = uniform("red_sniper_uniform", SNIPER, RED);
    public static final Item BLUE_SNIPER_UNIFORM = uniform("blue_sniper_uniform", SNIPER, BLUE);
    public static final Item RED_AIRBORNE_UNIFORM = uniform("red_airborne_uniform", AIRBORNE, RED);
    public static final Item BLUE_AIRBORNE_UNIFORM =
            uniform("blue_airborne_uniform", AIRBORNE, BLUE);
    public static final Item RED_DRONE_UNIFORM = uniform("red_drone_uniform", DRONE, RED);
    public static final Item BLUE_DRONE_UNIFORM = uniform("blue_drone_uniform", DRONE, BLUE);
    public static final Item NEUTRAL_ENGINEER_UNIFORM =
            uniform("neutral_engineer_uniform", ENGINEER, NEUTRAL);
    public static final Item NEUTRAL_SNIPER_UNIFORM =
            uniform("neutral_sniper_uniform", SNIPER, NEUTRAL);
    public static final Item NEUTRAL_AIRBORNE_UNIFORM =
            uniform("neutral_airborne_uniform", AIRBORNE, NEUTRAL);
    public static final Item NEUTRAL_DRONE_UNIFORM =
            uniform("neutral_drone_uniform", DRONE, NEUTRAL);
    public static final Item DM_UNIFORM = uniform("dm_uniform", DM, NEUTRAL);

    /** Планшет командира: единственная кнопка игрового меню в хабе и в матче (ПКМ). */
    public static final Item COMMAND_TABLET =
            Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(
                            BlockFieldCommon.MOD_ID, "command_tablet"),
                    new Item(new Item.Properties().stacksTo(1)));

    /** Разгрузочный жилет: отдельный слот (accessories:rig), хранит бесконечно патроны tacz. */
    public static final Item CHEST_RIG =
            Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(BlockFieldCommon.MOD_ID, "chest_rig"),
                    new Item(new Item.Properties().stacksTo(1)));

    private ModItems() {}

    /**
     * Класс грузится (и регистрирует предметы) на обеих сторонах: common едет и в client-, и в
     * server-jar.
     */
    public static void register() {}

    private static Item uniform(String name, UniformItem.Role role, UniformItem.Side side) {
        return Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(BlockFieldCommon.MOD_ID, name),
                new UniformItem(role, side));
    }
}
