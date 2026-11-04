package com.lunares.blockfield.client.gui.screen;

import com.lunares.blockfield.client.gui.ClientEventHandler;
import com.lunares.blockfield.common.item.ChestRigMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Экран клеток жилета: ванильная геометрия сундука (generic_54.png) на одну строку -- 9 клеток
 * гнезда сверху, инвентарь игрока снизу. Открывается клавишей key.blockfield.rig или вкладкой в
 * окне инвентаря; после закрытия возвращаем вкладку инвентаря.
 */
public class ChestRigScreen extends AbstractContainerScreen<ChestRigMenu> {

    public static boolean openedFromInventory = false;

    private static final ResourceLocation CONTAINER_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROWS = 1;

    public ChestRigScreen(ChestRigMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 114 + ROWS * 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ClientEventHandler.RIG_KEY.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        // Экран открывается из инвентаря (вкладка жилета), поэтому и закрывается обратно в
        // инвентарь -- как экран Accessories. Смерть и серверное закрытие идут мимо onClose.
        if (openedFromInventory && this.minecraft != null && this.minecraft.player != null) {
            openedFromInventory = false;
            this.minecraft.setScreen(new InventoryScreen(this.minecraft.player));
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int left = (this.width - this.imageWidth) / 2;
        int top = (this.height - this.imageHeight) / 2;
        // Как в ванильном ContainerScreen: верх (титул + строки гнезда) и низ (инвентарь игрока)
        // рисуются двумя нарезками одной текстуры.
        guiGraphics.blit(CONTAINER_BACKGROUND, left, top, 0, 0, this.imageWidth, ROWS * 18 + 17);
        guiGraphics.blit(
                CONTAINER_BACKGROUND, left, top + ROWS * 18 + 17, 0, 126, this.imageWidth, 96);
    }
}
