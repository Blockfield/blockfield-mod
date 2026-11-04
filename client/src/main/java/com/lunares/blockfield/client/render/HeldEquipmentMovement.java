package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.tacz.AnimationControllerAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

public final class HeldEquipmentMovement {
    private HeldEquipmentMovement() {}

    public static boolean isIdle(AnimateGeoItemRenderer<?, ?> renderer, ItemStack stack) {
        // Throw/re-draw continue after native item use ends.
        var machine = renderer.getStateMachine(stack);
        if (machine == null || !machine.isInitialized()) return false;
        var controller = machine.getAnimationController();
        Iterable<Integer> tracks = controller.getUpdatingTrackArray();
        if (tracks == null) {
            int count =
                    ((AnimationControllerAccessor) controller)
                            .blockfield$getCurrentRunners()
                            .size();
            tracks = () -> java.util.stream.IntStream.range(0, count).iterator();
        }
        for (int track : tracks) {
            var runner = controller.getAnimation(track);
            if (runner != null
                    && (runner.isTransitioning()
                            || (!runner.isStopped()
                                    && !"static_idle".equals(runner.getAnimation().name))))
                return false;
        }
        return true;
    }

    public static void apply(
            PoseStack pose, AbstractClientPlayer player, float partialTick, int side) {
        var movement =
                sample(
                        Mth.lerp(partialTick, player.walkDistO, player.walkDist),
                        player.walkAnimation.speed(partialTick),
                        player.onGround() && !player.isPassenger(),
                        player.isSprinting(),
                        side);
        pose.translate(movement.x(), movement.y(), movement.z());
        pose.mulPose(Axis.XP.rotationDegrees(movement.pitch()));
        pose.mulPose(Axis.ZP.rotationDegrees(movement.roll()));
    }

    static EquipmentInspect.Pose sample(
            float distance, float speed, boolean grounded, boolean sprinting, int side) {
        if (!grounded || speed <= 0) return EquipmentInspect.Pose.IDENTITY;
        float strength = Mth.clamp(speed, 0, 1) * (sprinting ? 1 : .55f);
        // Distance freezes at a wall; the native movement speed smoothly settles the grip.
        float phase = distance * Mth.PI;
        float sway = Mth.sin(phase) * strength;
        float step = Mth.cos(phase * 2) * strength;
        return new EquipmentInspect.Pose(
                sway * .035f * side, step * .018f, step * .006f, step * .8f, 0, sway * 1.5f * side);
    }
}
