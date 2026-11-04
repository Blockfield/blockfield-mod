package com.lunares.blockfield.common.mixin.copperagebackport;

import com.google.common.base.Suppliers;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ArmorMaterial;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copper Age Backport 0.1.4 sets COPPER to {@code () -> createCopper()}, so each copper armor item
 * registers minecraft:copper again (5 raw ids, one key). The Fabric sync map carries only the last
 * raw id, the client registry is left with holes after the remap, and the next remap (disconnect,
 * proxy server switch) crashes in MappedRegistry.iterator with a NullPointerException. Both sides
 * need this: the holes come from the server's map and from the client's own saved ids.
 */
@Pseudo
@Mixin(
        targets = "com.github.smallinger.copperagebackport.item.armor.CopperArmorMaterial",
        remap = false)
public abstract class CopperArmorMaterialMixin {
    @Shadow public static Supplier<Holder<ArmorMaterial>> COPPER;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private static void blockfield$registerOnce(CallbackInfo ci) {
        COPPER = Suppliers.memoize(COPPER::get);
    }
}
