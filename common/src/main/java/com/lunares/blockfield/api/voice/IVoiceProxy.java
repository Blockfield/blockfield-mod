package com.lunares.blockfield.api.voice;

import net.minecraft.server.level.ServerPlayer;

public interface IVoiceProxy {
    void setPlayerToRadioGroup(ServerPlayer player, String groupName);

    void removePlayerFromRadioGroup(ServerPlayer player);

    void clearGroups(java.util.List<ServerPlayer> players);

    void setRadioHeld(java.util.UUID playerId, boolean held);
}
