package com.lunares.blockfield.api.voice;

public class VoiceProxyHolder {
    private static IVoiceProxy activeProxy = new NoOpVoiceProxy();

    public static void setProxy(IVoiceProxy proxy) {
        if (proxy != null) {
            activeProxy = proxy;
        }
    }

    public static IVoiceProxy getProxy() {
        return activeProxy;
    }

    private static class NoOpVoiceProxy implements IVoiceProxy {
        @Override
        public void setPlayerToRadioGroup(net.minecraft.server.level.ServerPlayer p, String g) {}

        @Override
        public void removePlayerFromRadioGroup(net.minecraft.server.level.ServerPlayer p) {}

        @Override
        public void clearGroups(java.util.List<net.minecraft.server.level.ServerPlayer> players) {}

        @Override
        public void setRadioHeld(java.util.UUID id, boolean held) {}
    }
}
