package com.lunares.blockfield.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** После отключения от сервера возвращаем в главное меню: списка серверов в паке нет. */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    protected DisconnectedScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void blockfield$backToTitle(CallbackInfo ci) {
        int y = this.height - 30;
        for (var listener : List.copyOf(this.children())) {
            if (listener instanceof Button button) {
                y = button.getY();
                this.removeWidget(button);
                break;
            }
        }
        this.addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_BACK,
                                button -> this.minecraft.setScreen(new TitleScreen()))
                        .bounds(this.width / 2 - 100, y, 200, 20)
                        .build());
    }
}
