package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.gui.screen.RigTabWidget;
import com.lunares.blockfield.network.packet.RigOpenC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Вкладка клеток жилета в окне инвентаря: слоты жилета не встраиваются в InventoryMenu -- его
 * индексы слотов зашиты в ваниль (синк инвентаря, креатив) и в моды (TACZ, Accessories), сдвиг
 * ломал бы клики и синхронизацию. Вместо этого вкладка открывает ChestRigMenu тем же запросом, что
 * и клавиша, а закрытие возвращает в инвентарь.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenRigTabMixin extends AbstractContainerScreen<InventoryMenu> {

    @Unique private RigTabWidget blockfield$rigTab;

    protected InventoryScreenRigTabMixin(
            InventoryMenu menu,
            Inventory playerInventory,
            net.minecraft.network.chat.Component title) {
        super(menu, playerInventory, title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void blockfield$addRigTab(CallbackInfo ci) {
        if (!ClientPlayNetworking.canSend(RigOpenC2SPacket.TYPE)) return;
        this.blockfield$rigTab = new RigTabWidget(() -> this.leftPos, () -> this.topPos);
        // В renderables не добавляем: панель справочника рецептов рисуется после виджетов
        // экрана и перекрыла бы вкладку. Клик и ховер работают через children, отрисовка
        // идёт вручную в хвосте render -- поверх справочника, но под его тултипами.
        this.addWidget(this.blockfield$rigTab);
    }

    @Unique private boolean blockfield$accessoriesButtonRemoved;

    /**
     * Accessories вешает кнопку своего экрана owo-слоем уже после Screen.init
     * (ScreenEvents.AFTER_INIT), поэтому снимаем её перед первым кадром. Жилет и форма открываются
     * своей вкладкой и клавишей.
     */
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"))
    private void blockfield$removeAccessoriesButton(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.blockfield$accessoriesButtonRemoved
                || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("accessories"))
            return;
        this.blockfield$accessoriesButtonRemoved = true;
        for (var layer :
                io.wispforest.owo.ui.layers.Layers.getInstances((InventoryScreen) (Object) this)) {
            var root = layer.adapter.rootComponent;
            for (var child : new java.util.ArrayList<>(root.children())) {
                if (child instanceof io.wispforest.owo.ui.component.ButtonComponent)
                    root.removeChild(child);
            }
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void blockfield$renderRigTab(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.blockfield$rigTab != null) {
            this.blockfield$rigTab.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }
}
