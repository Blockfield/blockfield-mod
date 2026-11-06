package com.lunares.blockfield.network;

import com.lunares.blockfield.network.packet.*;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class NetworkHandler {
    /**
     * Thin-Workshop registration: handshake-adjacent map/AI/menu payloads only. The combat scene
     * clock, tickets, anim-sync, voice and match UI channels stay unregistered (and their classes
     * never load on the Workshop profile). Called by BlockFieldServer on that profile instead of
     * {@link GameNetworkHandler#register()}; the game path still calls {@link
     * GameNetworkHandler#register()}.
     *
     * <p>The game jar runs both entrypoints, so on the Workshop profile {@link
     * GameNetworkHandler#register()} has already claimed the shared types: each id registers once,
     * and the second call only adds the Workshop-only remainder instead of crashing on the
     * duplicates.
     */
    static final Set<ResourceLocation> REGISTERED_C2S = new HashSet<>();

    static final Set<ResourceLocation> REGISTERED_S2C = new HashSet<>();

    static synchronized <T extends CustomPacketPayload> void onceC2S(
            CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf, T> codec) {
        if (REGISTERED_C2S.add(type.id())) {
            PayloadTypeRegistry.playC2S().register(type, codec);
        }
    }

    static synchronized <T extends CustomPacketPayload> void onceS2C(
            CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf, T> codec) {
        if (REGISTERED_S2C.add(type.id())) {
            PayloadTypeRegistry.playS2C().register(type, codec);
        }
    }

    public static void registerWorkshop() {
        onceC2S(MenuActionC2SPacket.TYPE, MenuActionC2SPacket.CODEC);
        onceC2S(MapPingC2SPacket.TYPE, MapPingC2SPacket.CODEC);
        onceS2C(MapPingS2CPacket.TYPE, MapPingS2CPacket.CODEC);
        onceS2C(MapSnapshotS2CPacket.TYPE, MapSnapshotS2CPacket.CODEC);
        onceS2C(MapImageS2CPacket.TYPE, MapImageS2CPacket.CODEC);
        onceS2C(MapTileS2CPacket.TYPE, MapTileS2CPacket.CODEC);
        onceS2C(MapTerrainS2CPacket.TYPE, MapTerrainS2CPacket.CODEC);
        onceS2C(MapPlayersS2CPacket.TYPE, MapPlayersS2CPacket.CODEC);
        onceS2C(OpenMenuS2CPacket.TYPE, OpenMenuS2CPacket.CODEC);
        onceS2C(UpdateUIS2CPacket.TYPE, UpdateUIS2CPacket.CODEC);
        onceC2S(WorkshopAiRequestC2SPacket.TYPE, WorkshopAiRequestC2SPacket.CODEC);
        onceC2S(WorkshopAiAudioC2SPacket.TYPE, WorkshopAiAudioC2SPacket.CODEC);
        onceC2S(WorkshopAiTransformC2SPacket.TYPE, WorkshopAiTransformC2SPacket.CODEC);
        onceC2S(WorkshopAiLibraryC2SPacket.TYPE, WorkshopAiLibraryC2SPacket.CODEC);
        onceC2S(WorkshopAiFillC2SPacket.TYPE, WorkshopAiFillC2SPacket.CODEC);
        onceS2C(WorkshopAiLibraryS2CPacket.TYPE, WorkshopAiLibraryS2CPacket.CODEC);
        onceS2C(WorkshopAiSketchPlanS2CPacket.TYPE, WorkshopAiSketchPlanS2CPacket.CODEC);
        onceS2C(WorkshopAiStateS2CPacket.TYPE, WorkshopAiStateS2CPacket.CODEC);
        onceS2C(WorkshopAiPreviewS2CPacket.TYPE, WorkshopAiPreviewS2CPacket.CODEC);
        onceS2C(WorkshopAiV2S2CPacket.TYPE, WorkshopAiV2S2CPacket.CODEC);
        onceS2C(WorkshopAiProgressS2CPacket.TYPE, WorkshopAiProgressS2CPacket.CODEC);
        onceS2C(WorkshopAiPlanChunkS2CPacket.TYPE, WorkshopAiPlanChunkS2CPacket.CODEC);
        onceS2C(WorkshopAiV3S2CPacket.TYPE, WorkshopAiV3S2CPacket.CODEC);
        onceC2S(WorkshopAiV3S2CPacket.TYPE, WorkshopAiV3S2CPacket.CODEC);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        ServerPlayNetworking.send(player, packet);
    }

    /** Итог боя для этого игрока: "win", "loss" или "draw" (музыка победы/поражения на клиенте). */
    public static void sendMatchResult(ServerPlayer player, String result) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("matchResult", result);
        sendToPlayer(player, new UpdateUIS2CPacket(json.toString()));
    }
}
