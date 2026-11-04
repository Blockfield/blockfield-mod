package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.gui.ClientEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Клавиша рации подменяет PTT в Simple Voice Chat. Публичного API для этого нет, а правка
 * приватного поля pttKeyDown рефлексией ломалась о собственный обработчик клавиатуры SVC.
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.PTTKeyHandler", remap = false)
public class PTTKeyHandlerMixin {

    @Inject(method = "isPTTDown", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void blockfield$isPTTDown(CallbackInfoReturnable<Boolean> cir) {
        if (com.lunares.blockfield.client.workshop.WorkshopVoiceCapture.privateAudio()) {
            cir.setReturnValue(
                    com.lunares.blockfield.client.workshop.WorkshopVoiceCapture.recording());
        } else if (ClientEventHandler.isRadioHeld()) cir.setReturnValue(true);
    }

    @Inject(method = "isAnyDown", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void blockfield$isAnyDown(CallbackInfoReturnable<Boolean> cir) {
        if (com.lunares.blockfield.client.workshop.WorkshopVoiceCapture.privateAudio()) {
            cir.setReturnValue(
                    com.lunares.blockfield.client.workshop.WorkshopVoiceCapture.recording());
        } else if (ClientEventHandler.isRadioHeld()) cir.setReturnValue(true);
    }
}
