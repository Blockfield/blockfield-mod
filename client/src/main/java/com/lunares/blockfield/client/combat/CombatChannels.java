package com.lunares.blockfield.client.combat;

import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

/** Raknetify bf3's supported payload override map; TCP is unchanged. */
public final class CombatChannels {
    public static final int CHANNEL = 2;

    private CombatChannels() {}

    public static boolean rawCombatClass(Class<?> type) {
        return type == ServerboundCustomPayloadPacket.class
                || type == ServerboundSetCarriedItemPacket.class
                || type == ServerboundPickItemPacket.class
                || type == ServerboundContainerClickPacket.class
                || type == ServerboundPlayerCommandPacket.class
                || type == ServerboundInteractPacket.class
                || type == ServerboundPlayerActionPacket.class
                || type == ServerboundUseItemPacket.class
                || type == ServerboundUseItemOnPacket.class;
    }

    public static void install() {
        if (!FabricLoader.getInstance().isModLoaded("raknetify")) return;
        try {
            Class<?> type =
                    Class.forName(
                            "com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel");
            @SuppressWarnings("unchecked")
            var channels = (Map<String, Integer>) type.getField("identifier2channel").get(null);
            install(channels);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Raknetify combat channel contract changed", ex);
        }
    }

    public static void install(Map<String, Integer> channels) {
        // Bulk recording fragments must not hold weapon/aim state behind an audio upload.
        channels.put("blockfield:workshop_ai_audio", 3);
        channels.put("blockfield:combat_view", CHANNEL);
        channels.put("blockfield:anim_wire_c2s", CHANNEL);
        // ADR4: applied reports flush before the attack on its channel; probes measure that path.
        channels.put("blockfield:animation_applied", CHANNEL);
        channels.put("blockfield:animation_probe_echo", CHANNEL);
        CombatAttackSender.CUSTOM_IDS.forEach(id -> channels.put(id, CHANNEL));
    }
}
