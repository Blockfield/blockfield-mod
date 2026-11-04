package com.lunares.blockfield.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.lunares.blockfield.common.StanceTransition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eases player stance changes ({@link StanceTransition}). Only the applied dimensions change: fit
 * checks for a wanted pose (vanilla standing up, ParCool crawl) still ask getDimensions directly.
 */
@Mixin(Entity.class)
public abstract class StanceTransitionMixin implements StanceTransition.Access {
    @Unique
    private static final AttributeModifier BLOCKFIELD$SLOWDOWN =
            new AttributeModifier(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "stance_transition"),
                    StanceTransition.SLOWDOWN,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    @Unique private StanceTransition blockfield$stance;

    @Shadow private EntityDimensions dimensions;

    @Shadow
    public abstract void refreshDimensions();

    @ModifyExpressionValue(
            method = "refreshDimensions",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"))
    private EntityDimensions blockfield$easeStance(EntityDimensions target) {
        if (!((Object) this instanceof Player player)) return target;
        if (blockfield$stance == null) blockfield$stance = new StanceTransition();
        Pose pose = player.getPose();
        boolean eases =
                !player.isSpectator()
                        && !player.getAbilities().flying
                        && (pose == Pose.STANDING
                                || pose == Pose.CROUCHING
                                || pose == Pose.SWIMMING && !player.isInWater());
        // A driven mirror never holds from client physics: its box is the replicated one.
        if (blockfield$stance.isDriven())
            return blockfield$stance.apply(
                    dimensions, target, eases, pose == Pose.SWIMMING, pose == Pose.CROUCHING);
        // A ceiling can become obstructed after standing was requested (or while moving).
        if (eases
                && target.height() > dimensions.height()
                && !player.level()
                        .noCollision(
                                player, target.makeBoundingBox(player.position()).deflate(1e-7)))
            return blockfield$stance.hold(dimensions);
        return blockfield$stance.apply(
                dimensions, target, eases, pose == Pose.SWIMMING, pose == Pose.CROUCHING);
    }

    @Override
    public StanceTransition blockfield$stance() {
        return blockfield$stance;
    }

    @Inject(method = "baseTick", at = @At("HEAD"))
    private void blockfield$tickStance(CallbackInfo ci) {
        if (blockfield$stance == null || !((Object) this instanceof Player player)) return;
        if (blockfield$stance.tick()) refreshDimensions();
        // The local client owns its movement; the server would re-send a server-side modifier.
        if (!player.isLocalPlayer()) return;
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        if (!blockfield$stance.changing()) speed.removeModifier(BLOCKFIELD$SLOWDOWN.id());
        else if (!speed.hasModifier(BLOCKFIELD$SLOWDOWN.id()))
            speed.addTransientModifier(BLOCKFIELD$SLOWDOWN);
    }
}
