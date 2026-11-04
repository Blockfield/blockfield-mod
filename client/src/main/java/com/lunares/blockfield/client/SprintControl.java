package com.lunares.blockfield.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Blockfield exposes one sprint intent to the player: vanilla sprint key held down. ParCool FastRun
 * follows vanilla sprint automatically instead of owning a second key state.
 */
public final class SprintControl {
    private SprintControl() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SprintControl::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft mc) {
        forceParCoolToFollowVanillaSprint();
        if (mc.player == null || mc.options == null) return;

        boolean sprintKeyDown = mc.screen == null && mc.options.keySprint.isDown();
        if (shouldStopSprint(sprintKeyDown, mc.player.isSprinting())) {
            // Never force sprint on: vanilla/TACZ/ParCool restrictions remain authoritative.
            // We only enforce the Blockfield contract that releasing the key ends sprint now.
            mc.player.setSprinting(false);
        }
    }

    static boolean shouldStopSprint(boolean sprintKeyDown, boolean sprinting) {
        return sprinting && !sprintKeyDown;
    }

    private static void forceParCoolToFollowVanillaSprint() {
        if (!FabricLoader.getInstance().isModLoaded("parcool")) return;
        ParCoolBridge.forceAuto();
    }

    /**
     * Kept separate so Blockfield can still load without ParCool outside the production modpack.
     * FastRunControl is a NeoForge ModConfigSpec.EnumValue, which is not on the compile classpath:
     * reflect it once.
     */
    private static final class ParCoolBridge {
        private static final Object CONTROL;
        private static final java.lang.reflect.Method GET;
        private static final java.lang.reflect.Method SET;
        private static final Object AUTO =
                com.alrex.parcool.common.action.impl.FastRun.ControlType.Auto;

        static {
            try {
                CONTROL =
                        com.alrex.parcool.config.ParCoolConfig.Client.class
                                .getField("FastRunControl")
                                .get(com.alrex.parcool.config.ParCoolConfig.Client.getInstance());
                GET = CONTROL.getClass().getMethod("get");
                SET = CONTROL.getClass().getMethod("set", Object.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static void forceAuto() {
            try {
                if (GET.invoke(CONTROL) != AUTO) SET.invoke(CONTROL, AUTO);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("ParCool FastRunControl", e);
            }
        }
    }
}
