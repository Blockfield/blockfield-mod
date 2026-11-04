package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.CameraOverhaulAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The TACZ server fires along the rotation of the last movement packet, while the client sends the
 * shot from its scheduler thread as soon as the button fires: the aim of this very frame reaches
 * the server only with the next client tick. A shot right after turning onto a moving enemy went
 * out up to a tick (plus the scheduler delay) behind the crosshair. Sending the current rotation
 * just before the shot packet makes the server fire where the player aimed; rotation is
 * client-controlled anyway, so this opens nothing new.
 *
 * <p>In first person Camera Overhaul's view tilt ({@link CameraOverhaulAim}, up to ~1.7 deg with
 * speed, jumps, idle sway, sprint mouse smoothing) is added: it moves the crosshair and the scope
 * reticle, so the bullet goes where the player sees the crosshair. TACZ's own camera animation is
 * not added -- recoil stays as designed. The server keeps that rotation until the next look change,
 * i.e. other players may see the head off by that much.
 */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerShoot", remap = false)
public abstract class ShootRotationSyncMixin {
    @Shadow @Final private LocalPlayer player;

    /** The scheduler sends this shot later; the fire frame's eye travels with its ticket. */
    @Inject(method = "doShoot", remap = false, at = @At("HEAD"))
    private void blockfield$fireFrameEye(CallbackInfo ci) {
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        com.lunares.blockfield.client.combat.CombatAttackSender.fired(
                player.getEyePosition(partial));
    }

    @Inject(
            method = "lambda$doShoot$2",
            remap = false,
            at =
                    @At(
                            value = "INVOKE",
                            remap = true,
                            target =
                                    "Lnet/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking;send(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"))
    private void blockfield$sendAimWithShot(CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (connection == null) return;
        float yaw = player.getYRot(), pitch = player.getXRot();
        if (mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == player) {
            yaw += CameraOverhaulAim.yaw;
            pitch = Mth.clamp(pitch + CameraOverhaulAim.pitch, -90.0F, 90.0F);
        }
        com.lunares.blockfield.client.combat.CombatAttackSender.aimed(yaw, pitch);
        connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
    }
}
