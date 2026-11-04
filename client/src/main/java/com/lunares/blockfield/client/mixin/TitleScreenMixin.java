package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.ClientConfig;
import com.lunares.blockfield.client.gui.DirectConnect;
import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Главное меню — витрина проекта: остаются «Играть» (сразу на сервер), настройки и выход. Одиночка,
 * Realms, уведомления Realms и угловые кнопки доступности/языка удаляются.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends net.minecraft.client.gui.screens.Screen {

    @Mutable @Shadow @Nullable private RealmsNotificationsScreen realmsNotificationsScreen;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void blockfield$slimMenu(CallbackInfo ci) {
        // Разработке нужна одиночка (посмотреть карту в паке): флаг оставляет ванильное меню
        // целиком.
        if (ClientConfig.USER.getBoolean("menu.vanilla", false)) return;

        Button multiplayer = null;
        for (var listener : List.copyOf(this.children())) {
            if (!(listener instanceof Button button)) continue;
            switch (blockfield$key(button)) {
                case "menu.singleplayer",
                        "menu.online",
                        "accessibility.onboarding.accessibility_button",
                        "language.button" ->
                        this.removeWidget(button);
                case "menu.multiplayer" -> multiplayer = button;
            }
        }
        if (this.realmsNotificationsScreen != null) {
            this.removeWidget(this.realmsNotificationsScreen);
            this.realmsNotificationsScreen = null;
        }
        if (multiplayer == null) return;

        // Одиночка удалена: «Играть» встаёт на её ряд, настройки/выход подтягиваются на ряд
        // мультиплеера.
        int x = multiplayer.getX();
        int row = multiplayer.getY() - 24;
        this.removeWidget(multiplayer);
        Button play =
                Button.builder(
                                Component.translatable("blockfield.menu.play"),
                                button -> DirectConnect.connect(this))
                        .bounds(x, row, 200, 20)
                        .build();
        this.addRenderableWidget(play);
        for (var listener : List.copyOf(this.children())) {
            if (!(listener instanceof Button button)) continue;
            String key = blockfield$key(button);
            if (key.equals("menu.options") || key.equals("menu.quit")) {
                button.setY(row + 24);
            }
        }
    }

    private static String blockfield$key(Button button) {
        if (button.getMessage().getContents() instanceof TranslatableContents contents) {
            return contents.getKey();
        }
        return "";
    }
}
