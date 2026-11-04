package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.client.CameraOverhaulAim;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.lunares.blockfield.common.combat.CombatView;
import com.lunares.blockfield.common.combat.ShotDebug;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.client.input.AimKey;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Only the render thread reads the mutable scene/model; the scheduler reads a frozen frame. */
public final class ClientShotDebug {
    private static volatile Map<String, Object> frame = Map.of();
    private static volatile Object requestedAim;

    private ClientShotDebug() {}

    public static void requestedAim(Object point) {
        requestedAim = point;
    }

    public static void frameEnd() {
        if (!ShotDebug.enabled()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var bodies = new LinkedHashMap<Integer, Object>();
        for (var player : mc.level.players())
            if (player != mc.player) {
                var body = BodyPartHitboxRenderer.debugSnapshot(player.getId());
                var described = new LinkedHashMap<String, Object>();
                if (body != null) described.putAll(body);
                else described.put("status", "not_rendered");
                described.put("uuid", player.getUUID());
                bodies.put(player.getId(), described);
            }
        var camera = mc.gameRenderer.getMainCamera();
        var out = new LinkedHashMap<String, Object>();
        out.put("scene", ClientCombatScene.debugSnapshot());
        out.put("bodies", bodies);
        out.put("drawNs", System.nanoTime());
        out.put("cameraOrigin", camera.getPosition());
        out.put("cameraYaw", camera.getYRot());
        out.put("cameraPitch", camera.getXRot());
        frame = java.util.Collections.unmodifiableMap(out);
    }

    public static void sent(LocalPlayer player, long timestamp, CombatView view) {
        if (!ShotDebug.enabled() || player == null) return;
        var op = IClientPlayerGunOperator.fromLocalPlayer(player);
        ShotDebug.record(
                ShotDebug.id(player.getUUID(), timestamp),
                "client_send",
                "clockDomain",
                "client",
                "nativeTimestamp",
                timestamp,
                "requestedAim",
                requestedAim,
                "frame",
                frame,
                "baseYaw",
                player.getYRot(),
                "basePitch",
                player.getXRot(),
                "coYaw",
                CameraOverhaulAim.yaw,
                "coPitch",
                CameraOverhaulAim.pitch,
                "vanillaEye",
                player.getEyePosition(),
                "aimKeyDown",
                AimKey.AIM_KEY.isDown(),
                "clientAim",
                op.getClientAimingProgress(1),
                "syncedAim",
                IGunOperator.fromLivingEntity(player).getSynAimingProgress(),
                "view",
                view,
                "omission",
                ClientCombatScene.ticketOmission());
    }
}
