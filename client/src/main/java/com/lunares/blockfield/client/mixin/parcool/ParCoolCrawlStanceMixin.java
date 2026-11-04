package com.lunares.blockfield.client.mixin.parcool;

import com.alrex.parcool.client.animation.PlayerModelTransformer;
import com.alrex.parcool.client.animation.impl.CrawlAnimator;
import com.alrex.parcool.common.attachment.common.Parkourability;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.common.StanceTransition;
import com.lunares.blockfield.common.combat.StanceLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CrawlAnimator.class, remap = false)
public abstract class ParCoolCrawlStanceMixin {
    @Inject(method = "shouldRemoved", at = @At("RETURN"), cancellable = true, require = 1)
    private void blockfield$finishStance(
            Player player, Parkourability parkour, CallbackInfoReturnable<Boolean> cir) {
        var stance = StanceLayers.snapshot(player);
        if (stance != null) {
            int notDoing =
                    parkour.get(com.alrex.parcool.common.action.impl.Crawl.class).getNotDoingTick();
            if (StanceLayers.retainCrawl(
                    stance.eased(),
                    stance.flat(),
                    stance.toFlat(),
                    stance.fromFlat(),
                    notDoing,
                    stance.age())) cir.setReturnValue(false);
        }
    }

    @WrapMethod(method = "animatePost")
    private void blockfield$blend(
            Player player,
            Parkourability parkour,
            PlayerModelTransformer transformer,
            Operation<Void> original) {
        var stance = StanceLayers.snapshot(player);
        float flat =
                stance != null && stance.eased()
                        ? StanceTransition.flatAt(
                                stance.age(),
                                stance.fromFlat(),
                                stance.toFlat(),
                                stance.flat(),
                                transformer.getPartialTick())
                        : 1;
        if (flat == 1) {
            original.call(player, parkour, transformer);
            return;
        }
        var model = transformer.getRawModel();
        var parts = new ModelPart[] {model.leftArm, model.rightArm, model.leftLeg, model.rightLeg};
        var before = new float[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            var part = parts[i];
            before[i] = new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot};
        }
        original.call(player, parkour, transformer);
        for (int i = 0; i < parts.length; i++) {
            var part = parts[i];
            var b = before[i];
            part.x = StanceLayers.blend(b[0], part.x, flat);
            part.y = StanceLayers.blend(b[1], part.y, flat);
            part.z = StanceLayers.blend(b[2], part.z, flat);
            part.xRot = StanceLayers.blend(b[3], part.xRot, flat);
            part.yRot = StanceLayers.blend(b[4], part.yRot, flat);
            part.zRot = StanceLayers.blend(b[5], part.zRot, flat);
        }
    }
}
