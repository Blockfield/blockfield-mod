package com.lunares.blockfield.client.animation;

import com.lunares.blockfield.common.combat.TaczAssets;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** U9 calls this on staged native effective displays before resource activation. */
public final class RemoteTaczAssets {
    private RemoteTaczAssets() {}

    public static TaczAssets.Display retain(
            TaczAssets assets, String effectiveId, GunDisplayInstance display) {
        return retain(
                assets,
                effectiveId,
                display.getPlayerAnimator3rd(),
                display.is3rdFixedHand(),
                display.getThirdPersonAnimation());
    }

    /**
     * The same view from the native display POJO (U9 READY), without constructing a {@code
     * GunDisplayInstance}: TaCZ fills its instance cache only on the play-phase gun pack sync, and
     * construction loads models. Mirrors native {@code initBase}: the blank third-person name
     * becomes {@code "empty"}; the animator file and fixed hand replace the constructor defaults
     * only when the POJO names an animator.
     */
    public static TaczAssets.Display retain(
            TaczAssets assets, String effectiveId, GunDisplay display) {
        ResourceLocation animator = display.getPlayerAnimator3rd();
        return retain(
                assets,
                effectiveId,
                animator != null
                        ? animator
                        : ResourceLocation.parse(TaczAssets.DisplayFile.DEFAULT_ANIMATION),
                animator != null && display.is3rdFixedHand(),
                org.apache.commons.lang3.StringUtils.defaultIfBlank(
                        display.getThirdPersonAnimation(), "empty"));
    }

    private static TaczAssets.Display retain(
            TaczAssets assets,
            String effectiveId,
            ResourceLocation id,
            boolean fixedHand,
            String thirdPerson) {
        var manager = PlayerAnimatorAssetManager.get();
        boolean hasPa = id != null && manager.containsKey(id);
        Map<String, KeyframeAnimation> clips = Map.of();
        if (hasPa) {
            try {
                var field = PlayerAnimatorAssetManager.class.getDeclaredField("animations");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var all =
                        (Map<ResourceLocation, Map<String, KeyframeAnimation>>) field.get(manager);
                clips = all.get(id);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        return assets.register(
                effectiveId, Objects.toString(id, "null"), fixedHand, thirdPerson, hasPa, clips);
    }
}
