package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.KeyMappingClickAccess;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ваниль держит одну привязку на клавишу (KeyMapping.MAP), поэтому из четырёх биндов на Left Ctrl
 * нажатие получает только последний зарегистрированный — спринт на Fabric не работал. NeoForge
 * раздаёт нажатие всем привязкам клавиши.
 *
 * <p>Keybind Fix Plus (нужен lrtactical) делает ту же раздачу на TAIL этих же методов всем, кроме
 * победителя MAP. Два раздатчика на одну клавишу = два клика за нажатие: дверь открывалась и тут же
 * закрывалась, меню компаса приходило дважды. Поэтому здесь HEAD + cancel: раздаём сами всем
 * привязкам, включая победителя, а до ванильного тела и TAIL-инъекции KBFP дело не доходит.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingSpreadMixin implements KeyMappingClickAccess {

    @Shadow @Final private static Map<InputConstants.Key, KeyMapping> MAP;
    @Shadow private InputConstants.Key key;
    @Shadow private int clickCount;

    @Override
    public void blockfield$click() {
        this.clickCount++;
    }

    @Unique
    private static void blockfield$forEachBound(
            InputConstants.Key key, Consumer<KeyMapping> action) {
        KeyMapping vanilla = MAP.get(key);
        Minecraft mc = Minecraft.getInstance();
        boolean vanillaSeen = false;
        if (mc.options != null) {
            for (KeyMapping mapping : mc.options.keyMappings) {
                if (!((KeyMappingSpreadMixin) (Object) mapping).key.equals(key)) continue;
                action.accept(mapping);
                if (mapping == vanilla) vanillaSeen = true;
            }
        }
        // Привязка, которой нет в options.keyMappings (зарегистрирована мимо KeyBindingHelper).
        if (vanilla != null && !vanillaSeen) action.accept(vanilla);
    }

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private static void blockfield$spreadSet(
            InputConstants.Key key, boolean pressed, CallbackInfo ci) {
        blockfield$forEachBound(key, mapping -> mapping.setDown(pressed));
        ci.cancel();
    }

    @Inject(method = "click", at = @At("HEAD"), cancellable = true)
    private static void blockfield$spreadClick(InputConstants.Key key, CallbackInfo ci) {
        blockfield$forEachBound(
                key, mapping -> ((KeyMappingClickAccess) mapping).blockfield$click());
        ci.cancel();
    }
}
