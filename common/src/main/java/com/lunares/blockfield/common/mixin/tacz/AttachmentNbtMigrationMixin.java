package com.lunares.blockfield.common.mixin.tacz;

import com.lunares.blockfield.common.item.AttachmentNbtMigration;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Kit/RCON gives and old world containers still carry TaCZ attachments in the 1.20 shape nested in
 * custom_data; TaCZ 1.21.1 decodes them with ItemStack.CODEC and falls back to missing-texture
 * quads. Upgrade the slot in place wherever TaCZ reads it.
 */
@Mixin(value = GunItemDataAccessor.class, remap = false)
public interface AttachmentNbtMigrationMixin {
    @Inject(method = "getAttachmentTag", at = @At("HEAD"), remap = false)
    default void blockfield$upgradeLegacyAttachment(
            ItemStack gun, AttachmentType type, CallbackInfoReturnable<CompoundTag> cir) {
        AttachmentNbtMigration.upgrade(gun, "Attachment" + type.name());
    }

    @Inject(method = "getAttachment", at = @At("HEAD"), remap = false)
    default void blockfield$upgradeLegacyAttachmentStack(
            HolderLookup.Provider registries,
            ItemStack gun,
            AttachmentType type,
            CallbackInfoReturnable<ItemStack> cir) {
        AttachmentNbtMigration.upgrade(gun, "Attachment" + type.name());
    }
}
