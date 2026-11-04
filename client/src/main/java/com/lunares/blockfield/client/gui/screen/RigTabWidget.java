package com.lunares.blockfield.client.gui.screen;

import com.lunares.blockfield.client.gui.ClientEventHandler;
import com.lunares.blockfield.network.packet.RigOpenC2SPacket;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Вкладка «Разгрузочный жилет» на левом краю окна инвентаря (по образцу вкладок Accessories). Клик
 * отправляет blockfield:rig_open -- тот же запрос, что и клавиша key.blockfield.rig, и сервер
 * поднимает ChestRigMenu; обратно в инвентарь возвращает ChestRigScreen.onClose. Позиция задаётся
 * поставщиками leftPos/topPos: при открытом справочнике рецептов панель инвентаря сдвигается без
 * вызова init, поэтому вкладка догоняет её в начале каждого кадра. Прилипает к левому краю панели:
 * right = leftPos, верх = topPos + 12.
 */
public class RigTabWidget extends AbstractWidget {

    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath("blockfield", "textures/gui/rig_tab_icon.png");
    private static final int TAB_WIDTH = 14;
    private static final int TAB_HEIGHT = 52;
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_HOVER = 0xFFDADADA;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int OUTLINE = 0xFF000000;
    private static final int ICON_SIZE = 8;

    private final IntSupplier panelX;
    private final IntSupplier panelY;

    public RigTabWidget(IntSupplier panelX, IntSupplier panelY) {
        super(0, 0, TAB_WIDTH, TAB_HEIGHT, Component.translatable("item.blockfield.chest_rig"));
        this.panelX = panelX;
        this.panelY = panelY;
        Component open =
                Component.translatable(
                                "blockfield.gui.rig_tab.open",
                                ClientEventHandler.RIG_KEY.getTranslatedKeyMessage())
                        .withStyle(net.minecraft.ChatFormatting.GRAY);
        this.setTooltip(Tooltip.create(this.getMessage().copy().append("\n").append(open)));
    }

    @Override
    protected void renderWidget(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int px = this.panelX.getAsInt() - this.width;
        int py = this.panelY.getAsInt() + 12;
        if (px != this.getX() || py != this.getY()) this.setPosition(px, py);
        int x = this.getX();
        int y = this.getY();
        int right = x + this.width;
        int bottom = y + this.height;
        boolean hovered = this.isHoveredOrFocused();
        int fill = hovered ? PANEL_HOVER : PANEL;
        // ponytail: правая колонка вкладки перекрашивается в цвет панели, закрывая чёрную
        // кромку контейнера -- так вкладка выглядит приклеенной к краю, как вкладки Creative;
        // если панель сдвинется (справочник рецептов), перекраска уедет на один кадр.
        guiGraphics.fill(x, y, right - 1, bottom, fill);
        guiGraphics.fill(x, y, right, y + 1, OUTLINE);
        guiGraphics.fill(x, bottom - 1, right, bottom, OUTLINE);
        guiGraphics.fill(x, y, x + 1, bottom, OUTLINE);
        guiGraphics.fill(x + 1, y + 1, right - 2, y + 2, HIGHLIGHT);
        guiGraphics.fill(x + 1, y + 1, x + 2, bottom - 1, HIGHLIGHT);
        guiGraphics.fill(x + 1, bottom - 2, right - 2, bottom - 1, SHADOW);
        guiGraphics.fill(right - 1, y + 1, right, bottom - 1, fill);
        guiGraphics.blit(
                ICON,
                x + (this.width - ICON_SIZE) / 2,
                y + (this.height - ICON_SIZE) / 2,
                0,
                0,
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE);
    }

    @Override
    public void updateWidgetNarration(
            net.minecraft.client.gui.narration.NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ClientPlayNetworking.canSend(RigOpenC2SPacket.TYPE)) return;
        ChestRigScreen.openedFromInventory = true;
        this.playDownSound(mc.getSoundManager());
        ClientPlayNetworking.send(RigOpenC2SPacket.INSTANCE);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // База учитывает кромку панели, которую вкладка перекрашивает под себя.
        return this.active
                && this.visible
                && mouseX >= this.getX() - 1.0
                && mouseY >= this.getY()
                && mouseX < this.getX() + this.width
                && mouseY < this.getY() + this.height;
    }
}
