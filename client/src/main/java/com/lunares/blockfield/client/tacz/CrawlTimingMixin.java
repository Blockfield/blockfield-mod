package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.common.combat.StanceLayers;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Retime the runner clock, so authored curves and sound keys stay together. */
@Mixin(value = ObjectAnimationRunner.class, remap = false)
public abstract class CrawlTimingMixin {
    @Shadow @Final private ObjectAnimation animation;

    @ModifyVariable(method = "updateProgress", at = @At("HEAD"), argsOnly = true, require = 1)
    private long blockfield$eightTickCrawl(long deltaNs) {
        return StanceLayers.crawlDelta(animation.name, animation.getMaxEndTimeS(), deltaNs);
    }
}
