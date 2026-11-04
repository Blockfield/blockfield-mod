package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.EffectiveEyeView;
import com.lunares.blockfield.common.StanceTransition;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crouch snaps the eye; prone carries one tick's eye segment for the render partial. */
@Mixin(net.minecraft.client.Camera.class)
public abstract class StanceCameraMixin {
    @Shadow private Entity entity;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    public abstract Vec3 getPosition();

    @Unique private float blockfield$lastCrouch;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void blockfield$followEasedEye(CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!(player instanceof StanceTransition.Access access)) return;
        StanceTransition stance = access.blockfield$stance();
        float crouch = stance == null ? 0 : stance.crouchness();
        boolean crouchChanged = crouch != blockfield$lastCrouch;
        blockfield$lastCrouch = crouch;
        if (stance == null || !stance.eases() || !stance.changing() && !crouchChanged) return;
        // Crouch snaps; prone shares the body's current-to-next tick segment.
        eyeHeightOld = player.getEyeHeight();
        var snap = stance.snapshot();
        eyeHeight =
                stance.changing()
                        ? net.minecraft.util.Mth.lerp(
                                Math.min(snap.age() + 1, StanceTransition.TICKS)
                                        / (float) StanceTransition.TICKS,
                                snap.from().eyeHeight(),
                                snap.to().eyeHeight())
                        : eyeHeightOld;
        ci.cancel();
    }

    /**
     * First-person camera on the animated head: after vanilla positions the camera from the eased
     * vanilla eye, shift it by the smoothed server-computed head offset, rotated by the current
     * partial-tick interpolated body yaw so the camera tracks the head through turns without
     * per-tick steps. Rotation stays the player's look rotation; third person and other camera
     * entities keep vanilla positions.
     */
    @Inject(method = "setup", at = @At("TAIL"))
    private void blockfield$followHeadAnchor(
            BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean thirdPersonReverse,
            float partialTick,
            CallbackInfo ci) {
        if (detached || !(cameraEntity instanceof LocalPlayer player)) return;
        float yaw = net.minecraft.util.Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot);
        Vec3 eye = EffectiveEyeView.renderedEye(player, getPosition(), yaw);
        if (eye != null) setPosition(eye);
    }
}
