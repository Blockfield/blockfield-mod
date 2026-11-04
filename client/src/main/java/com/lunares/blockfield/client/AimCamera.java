package com.lunares.blockfield.client;

import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.LocalPlayer;

/**
 * Прицеливание из третьего лица бесполезно: TaCZ рисует прицельную марку только в первом, поэтому
 * на время прицеливания камера сама уходит в первое лицо и потом возвращается. Класс трогает TaCZ
 * напрямую, грузить его можно только при загруженном моде.
 */
public final class AimCamera {

    private static CameraType restore;

    private AimCamera() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(
                mc -> {
                    LocalPlayer player = mc.player;
                    if (player == null) {
                        if (restore != null
                                && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
                            mc.options.setCameraType(restore);
                        }
                        restore = null;
                        return;
                    }
                    CameraType current = mc.options.getCameraType();
                    if (IClientPlayerGunOperator.fromLocalPlayer(player).isAim()) {
                        if (current != CameraType.FIRST_PERSON) {
                            restore = current;
                            mc.options.setCameraType(CameraType.FIRST_PERSON);
                        }
                    } else if (restore != null) {
                        // Если игрок сам сменил вид во время прицеливания — не отбираем у него
                        // камеру.
                        if (current == CameraType.FIRST_PERSON) mc.options.setCameraType(restore);
                        restore = null;
                    }
                });
    }
}
