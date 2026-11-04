package com.lunares.blockfield.client.mixin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Фон главного меню -- панорама настоящей карты Blockfield, случайной на каждый запуск игры.
 * Снимки: грани куба 768x768 с тестового стенда (rig.sh, FOV 90, квадратное окно, скрытый HUD) в
 * assets/blockfield/textures/gui/panorama/&lt;карта&gt;/panorama_0..5.png. Новая карта -- папка и
 * строка в MAPS.
 */
@Mixin(CubeMap.class)
public abstract class TitlePanoramaMixin {
    private static final ResourceLocation VANILLA =
            ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama");
    private static final List<String> MAPS =
            List.of("baxmut", "berdichev", "konstaxa", "kozyatyn", "zaliznichne");

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static ResourceLocation blockfield$realMap(ResourceLocation base) {
        if (!VANILLA.equals(base)) return base;
        String map = MAPS.get(ThreadLocalRandom.current().nextInt(MAPS.size()));
        return ResourceLocation.fromNamespaceAndPath(
                "blockfield", "textures/gui/panorama/" + map + "/panorama");
    }
}
