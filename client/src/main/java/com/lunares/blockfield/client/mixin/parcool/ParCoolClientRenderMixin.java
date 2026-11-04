package com.lunares.blockfield.client.mixin.parcool;

import com.alrex.parcool.client.ClientRenderProcessor;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.alrex.parcool.fabric.RenderFrameEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ClientRenderProcessor.class, remap = false)
public abstract class ParCoolClientRenderMixin {
    @WrapOperation(
            method = "onRenderTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/alrex/parcool/common/action/Action;onRenderTick(Lcom/alrex/parcool/fabric/RenderFrameEvent;Lnet/minecraft/class_1657;Lcom/alrex/parcool/common/attachment/common/Parkourability;)V"),
            require = 1)
    private static void blockfield$canonicalYaw(
            Action action,
            RenderFrameEvent event,
            Player player,
            Parkourability parkour,
            Operation<Void> original) {
        if (!RemoteParCoolPresentation.driven(player))
            original.call(action, event, player, parkour);
    }
}
