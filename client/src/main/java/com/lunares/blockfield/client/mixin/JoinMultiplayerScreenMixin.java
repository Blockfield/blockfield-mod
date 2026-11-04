package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.ClientConfig;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Списка серверов в паке нет: главное меню подключает напрямую, ручное отключение (PauseScreen) и
 * кик вели бы в ванильный список — возвращаем в главное меню сразу. Дев-сборка (`menu.vanilla` в
 * blockfield-settings.properties) список оставляет.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void blockfield$noServerList(CallbackInfo ci) {
        if (ClientConfig.USER.getBoolean("menu.vanilla", false)) return;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(() -> minecraft.setScreen(new TitleScreen()));
        }
    }
}
