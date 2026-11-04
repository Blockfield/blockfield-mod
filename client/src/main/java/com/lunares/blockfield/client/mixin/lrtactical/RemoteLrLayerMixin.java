package com.lunares.blockfield.client.mixin.lrtactical;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lunares.blockfield.client.animation.RemoteLrCompat;
import com.lunares.blockfield.common.combat.StanceLayers;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import java.lang.reflect.Field;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LR bf2 reaches a player's layers only through {@code getLayer} (idle, attack, stop) and the
 * associated-data lookup in {@code enableRotationModifier}. For a driven remote both resolve to its
 * {@link RemoteLrCompat} view, so the native selector/listener keep their schedule and shared-clip
 * writes without changing the installed authoritative geometry.
 */
@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration")
public abstract class RemoteLrLayerMixin {
    private static volatile Field blockfield$id;

    @Inject(method = "getLayer", remap = false, at = @At("HEAD"), cancellable = true)
    private static void blockfield$compatLayer(
            AbstractClientPlayer player,
            @Coerce Object layer,
            CallbackInfoReturnable<ModifierLayer<IAnimation>> cir) {
        var shadow = RemoteLrCompat.layer(player, blockfield$id(layer));
        if (shadow != null) cir.setReturnValue(shadow);
    }

    @WrapOperation(
            method = "enableRotationModifier",
            remap = false,
            at =
                    @At(
                            value = "INVOKE",
                            remap = false,
                            target =
                                    "Ldev/kosmx/playerAnim/minecraftApi/PlayerAnimationAccess$PlayerAssociatedAnimationData;get(Lnet/minecraft/class_2960;)Ldev/kosmx/playerAnim/api/layered/IAnimation;"))
    private static IAnimation blockfield$compatRotation(
            PlayerAnimationAccess.PlayerAssociatedAnimationData data,
            ResourceLocation id,
            Operation<IAnimation> original,
            @Local(argsOnly = true) AbstractClientPlayer player) {
        var shadow = RemoteLrCompat.layer(player, id);
        return shadow != null ? shadow : original.call(data, id);
    }

    @ModifyArg(
            method = "playIdleAnimation",
            remap = false,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/kosmx/playerAnim/api/layered/ModifierLayer;addModifierLast(Ldev/kosmx/playerAnim/api/layered/modifier/AbstractModifier;)V"),
            index = 0,
            require = 1)
    private static AbstractModifier blockfield$stanceFade(
            AbstractModifier modifier, @Local(argsOnly = true) AbstractClientPlayer player) {
        if (modifier instanceof AbstractFadeModifier fade)
            return StanceLayers.lrFade(fade, StanceLayers.snapshot(player));
        return modifier;
    }

    /**
     * Re-samples the layer's installed LR stance fades from the live shared coefficient every
     * client tick, before the native same-clip early return. New selections seed through {@link
     * #blockfield$stanceFade}; this keeps held and continuing transitions on the coefficient.
     */
    @Inject(method = "playIdleAnimation", remap = false, at = @At("HEAD"), require = 1)
    private static void blockfield$sampleStance(
            AbstractClientPlayer player,
            ResourceLocation id,
            String name,
            @Coerce Object layer,
            int fade,
            CallbackInfo ci) {
        var stance = StanceLayers.snapshot(player);
        if (stance == null || !stance.eased()) return;
        ResourceLocation layerId = blockfield$id(layer);
        ModifierLayer<IAnimation> target;
        try {
            target = RemoteLrCompat.layer(player, layerId);
        } catch (com.lunares.blockfield.common.combat.PaGraph.Unsupported e) {
            return;
        }
        if (target == null) {
            var associated =
                    dev.kosmx
                            .playerAnim
                            .minecraftApi
                            .PlayerAnimationAccess
                            .getPlayerAssociatedData(player)
                            .get(layerId);
            target =
                    associated instanceof ModifierLayer<?>
                            ? (ModifierLayer<IAnimation>) associated
                            : null;
        }
        if (target == null) return;
        StanceLayers.syncLrLayer(blockfield$modifiers(target), stance);
    }

    private static ResourceLocation blockfield$id(Object layer) {
        try {
            Field field = blockfield$id;
            if (field == null) {
                field = layer.getClass().getDeclaredField("id");
                field.setAccessible(true);
                blockfield$id = field;
            }
            return (ResourceLocation) field.get(layer);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Pinned LR AnimationLayer.id changed", error);
        }
    }

    private static volatile Field blockfield$modifiers;

    @SuppressWarnings("unchecked")
    private static java.util.List<AbstractModifier> blockfield$modifiers(
            ModifierLayer<IAnimation> layer) {
        try {
            Field field = blockfield$modifiers;
            if (field == null) {
                field = ModifierLayer.class.getDeclaredField("modifiers");
                field.setAccessible(true);
                blockfield$modifiers = field;
            }
            return (java.util.List<AbstractModifier>) field.get(layer);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "Pinned PlayerAnimator member missing: modifiers", error);
        }
    }
}
