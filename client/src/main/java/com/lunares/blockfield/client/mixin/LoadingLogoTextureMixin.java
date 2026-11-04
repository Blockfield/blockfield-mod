package com.lunares.blockfield.client.mixin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Логотип на экране загрузки ваниль читает только из своего jar (ресурс-паки и моды ещё не
 * загружены), поэтому подменяем источник: PNG в той же раскладке, что mojangstudios.png (верх —
 * левая половина, низ — правая).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.LoadingOverlay$LogoTexture")
public class LoadingLogoTextureMixin {

    @Redirect(
            method = "getTextureImage",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/packs/VanillaPackResources;getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;"))
    private IoSupplier<InputStream> blockfield$logo(
            VanillaPackResources vanilla, PackType type, ResourceLocation location) {
        Path png =
                FabricLoader.getInstance()
                        .getModContainer("blockfield")
                        .flatMap(
                                mod ->
                                        mod.findPath(
                                                "assets/blockfield/textures/gui/loading_logo.png"))
                        .orElse(null);
        if (png == null) return vanilla.getResource(type, location);
        return () -> Files.newInputStream(png);
    }
}
