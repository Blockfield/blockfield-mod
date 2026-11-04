package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.render.WeaponHud;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Builder and ghost flags the server sends in the workshop UI packet. They gate the F3+N / F3+F4
 * debug shortcuts (vanilla needs permission level 2) and the ghost rendering/physics tweaks; the
 * server re-checks everything authoritatively.
 */
public final class WorkshopGhostClient {
    private static volatile boolean builder;
    private static volatile boolean ghost;

    private WorkshopGhostClient() {}

    public static void update(boolean canBuild, boolean ghostMode) {
        builder = canBuild;
        ghost = ghostMode;
    }

    public static boolean canBuild() {
        return builder && WeaponHud.inWorkshop();
    }

    /** Own ghost state; only meaningful inside the workshop. */
    public static boolean inGhost() {
        return ghost && WeaponHud.inWorkshop();
    }

    /** The F3+F4 switcher may send for a builder without op level (server enforces the modes). */
    public static boolean mayDebugSwitch() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                && !mc.player.hasPermissions(2)
                && builder
                && WeaponHud.inWorkshop();
    }

    /**
     * F3+N toggles ghost through the tablet channel; F3+F4 opens the vanilla switcher. True when
     * consumed; ops and everyone outside the workshop keep the vanilla path.
     */
    public static boolean consumeDebugKey(int key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.hasPermissions(2)) return false;
        if (!WeaponHud.inWorkshop() || !builder) return false;
        if (key == GLFW.GLFW_KEY_N) {
            if (ClientPlayNetworking.canSend(MenuActionC2SPacket.TYPE))
                ClientPlayNetworking.send(new MenuActionC2SPacket("workshop:ghost"));
            return true;
        }
        if (key == GLFW.GLFW_KEY_F4) {
            mc.setScreen(new GameModeSwitcherScreen());
            return true;
        }
        return false;
    }
}
