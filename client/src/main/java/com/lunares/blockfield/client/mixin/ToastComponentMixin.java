package com.lunares.blockfield.client.mixin;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Сервер offline-mode (скины через SkinRestorer), поэтому ваниль на каждом входе показывает
 * «Невозможно проверить сообщения чата». Подписей чата в паке нет и не будет — тост лишний.
 */
@Mixin(ToastComponent.class)
public class ToastComponentMixin {

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void blockfield$dropUnsecureChatToast(Toast toast, CallbackInfo ci) {
        if (toast instanceof SystemToast t
                && t.getToken() == SystemToast.SystemToastId.UNSECURE_SERVER_WARNING) {
            ci.cancel();
        }
    }
}
