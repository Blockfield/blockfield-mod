package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HudSessionResetTest {
    @Test
    void joiningLimboWithoutUiPacketsRestoresVanillaPlayerList() throws Exception {
        TabMenuRenderer.setTabActive(true);
        TabMenuRenderer.setFfaMode(true);
        var reset = HudRenderer.class.getDeclaredMethod("onClientLogout");
        reset.setAccessible(true);
        reset.invoke(null);
        assertFalse(TabMenuRenderer.isTabActive());
        var ffa = TabMenuRenderer.class.getDeclaredField("ffaMode");
        ffa.setAccessible(true);
        assertFalse(ffa.getBoolean(null));
        assertFalse(HudRenderer.isHudActive());
        assertFalse(HudRenderer.isDeployed());
    }
}
