package com.lunares.blockfield.common.item;

import com.lunares.blockfield.common.BlockFieldCommon;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Инвентарь разгрузочного жилета: 9 клеток гнезда + обычный инвентарь игрока. Класс живёт в common:
 * MenuType обязан существовать на обеих сторонах -- клиент строит экран по типу меню (фабрика ниже
 * даёт контейнер-плейсхолдер, содержимое приходит ванильным синком слотов), сервер подставляет
 * настоящий ChestRigContainer. Клетки принимают боеприпасы TACZ и креативную коробку Superb Warfare
 * -- то же общее правило, что у автоматического подбора в жилет.
 */
public class ChestRigMenu extends AbstractContainerMenu {

    public static final int RIG_SLOT_COUNT = 9;

    /**
     * Вместимость клетки жилета: не ограничена. Слоты гнезда перекрывают getMaxStackSize, иначе
     * ваниль режет слияние по максимуму предмета (tacz -- 32/64), а требование -- неограниченное
     * хранение боеприпасов. Кэп нужен только как защита int-арифметики.
     */
    public static final int RIG_SLOT_CAP = Integer.MAX_VALUE;

    /** Смещение инвентаря игрока для однорядного контейнера: (rows - 4) * 18, как в ChestMenu. */
    private static final int PLAYER_INV_OFFSET = (1 - 4) * 18;

    public static final MenuType<ChestRigMenu> TYPE =
            Registry.register(
                    BuiltInRegistries.MENU,
                    ResourceLocation.fromNamespaceAndPath(BlockFieldCommon.MOD_ID, "chest_rig"),
                    new MenuType<>(ChestRigMenu::clientMenu, FeatureFlags.VANILLA_SET));

    /** Вызывается из BlockFieldCommon.onInitialize на обеих сторонах. */
    public static void registerMenu() {}

    private final Container rigContainer;

    public ChestRigMenu(int containerId, Inventory playerInventory, Container rigContainer) {
        super(TYPE, containerId);
        this.rigContainer = rigContainer;
        for (int i = 0; i < RIG_SLOT_COUNT; i++) {
            this.addSlot(
                    new Slot(rigContainer, i, 8 + i * 18, 18) {
                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            return isRigAmmo(stack);
                        }

                        /** Клетка принимает весь переносимый стак и сливает его целиком. */
                        @Override
                        public int getMaxStackSize() {
                            return RIG_SLOT_CAP;
                        }

                        @Override
                        public int getMaxStackSize(ItemStack stack) {
                            return RIG_SLOT_CAP;
                        }
                    });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(
                        new Slot(
                                playerInventory,
                                col + row * 9 + 9,
                                8 + col * 18,
                                103 + row * 18 + PLAYER_INV_OFFSET));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 161 + PLAYER_INV_OFFSET));
        }
    }

    /** Клиентская фабрика MenuType: реальное содержимое наливается ванильным синком слотов. */
    private static ChestRigMenu clientMenu(int containerId, Inventory playerInventory) {
        return new ChestRigMenu(
                containerId,
                playerInventory,
                new SimpleContainer(RIG_SLOT_COUNT) {
                    // SimpleContainer.setItem обрезает стак до максимума предмета (боеприпас TACZ
                    // -- 60),
                    // и клетка с 461 патроном показывала бы 60.
                    @Override
                    public void setItem(int slot, ItemStack stack) {
                        this.getItems().set(slot, stack);
                        this.setChanged();
                    }
                });
    }

    @Override
    public boolean stillValid(Player player) {
        return this.rigContainer.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot from = this.slots.get(index);
        if (!from.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = from.getItem();
        ItemStack copy = stack.copy();
        if (index < RIG_SLOT_COUNT) {
            if (!this.moveItemStackTo(stack, RIG_SLOT_COUNT, RIG_SLOT_COUNT + 36, true))
                return ItemStack.EMPTY;
        } else {
            if (!this.moveItemStackTo(stack, 0, RIG_SLOT_COUNT, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            from.setByPlayer(ItemStack.EMPTY);
        } else {
            from.setChanged();
        }
        return copy;
    }

    /** Shared with server absorption and manual slot writes. */
    public static boolean isRigAmmo(ItemStack stack) {
        return ChestRigAmmo.accepts(stack);
    }
}
