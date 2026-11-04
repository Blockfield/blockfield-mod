package com.lunares.blockfield.network;

import com.lunares.blockfield.api.voice.VoiceProxyHolder;
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
     * Snapshot wire: the ADR4 codec directly. R10-OUT removed the migration codec and its format
     * byte; the exact bytes ({@code ANIMATION_CODEC}) are unchanged.
     *
     * <p>Lazy methods, not static fields: the thin Workshop jar never loads these combat-payload
     * classes, so even referencing them at class-init time would drag netcode into the Workshop
     * build (owner rule 2026-10-31).
     */
    public static StreamCodec<FriendlyByteBuf, PlayerSnapshotS2CPacket> snapshotWire() {
        return PlayerSnapshotS2CPacket.ANIMATION_CODEC;
    }

    /** Ticket wire: the complete ADR4 receipt directly (same R10-OUT removal, same laziness). */
    public static StreamCodec<FriendlyByteBuf, CombatViewC2SPacket> viewWire() {
        return CombatViewC2SPacket.ANIMATION_CODEC;
    }

    /**
     * Регистрация типов пакетов. Обе стороны обязаны знать оба направления, иначе кодек не
     * найдётся, поэтому это общий код, а не серверный.
     */
    public static void register() {
        onceS2C(GunBalanceS2CPacket.TYPE, GunBalanceS2CPacket.CODEC);
        onceC2S(RadioStateC2SPacket.TYPE, RadioStateC2SPacket.CODEC);
        onceC2S(MenuActionC2SPacket.TYPE, MenuActionC2SPacket.CODEC);
        onceC2S(RigOpenC2SPacket.TYPE, RigOpenC2SPacket.CODEC);
        onceC2S(GrenadeThrowC2SPacket.TYPE, GrenadeThrowC2SPacket.CODEC);
        onceC2S(MapPingC2SPacket.TYPE, MapPingC2SPacket.CODEC);
        onceC2S(MedkitChannelC2SPacket.TYPE, MedkitChannelC2SPacket.CODEC);
        onceC2S(KnifeAimC2SPacket.TYPE, KnifeAimC2SPacket.CODEC);
        onceC2S(ParCoolIntentC2SPacket.TYPE, ParCoolIntentC2SPacket.CODEC);
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

        onceS2C(OpenClassMenuS2CPacket.TYPE, OpenClassMenuS2CPacket.CODEC);
        onceS2C(OpenSpawnMenuS2CPacket.TYPE, OpenSpawnMenuS2CPacket.CODEC);
        onceS2C(OpenMenuS2CPacket.TYPE, OpenMenuS2CPacket.CODEC);
        onceS2C(UpdateUIS2CPacket.TYPE, UpdateUIS2CPacket.CODEC);
        onceS2C(TicketUpdatePacket.TYPE, TicketUpdatePacket.CODEC);
        onceS2C(ScoreDeltaPacket.TYPE, ScoreDeltaPacket.CODEC);
        onceS2C(RadioStateS2CPacket.TYPE, RadioStateS2CPacket.CODEC);
        onceS2C(PlaySound2DS2CPacket.TYPE, PlaySound2DS2CPacket.CODEC);
        onceS2C(ConsumeStaminaS2CPacket.TYPE, ConsumeStaminaS2CPacket.CODEC);
        onceS2C(MapPingS2CPacket.TYPE, MapPingS2CPacket.CODEC);
        onceS2C(MapSnapshotS2CPacket.TYPE, MapSnapshotS2CPacket.CODEC);
        onceS2C(MapImageS2CPacket.TYPE, MapImageS2CPacket.CODEC);
        onceS2C(MapTileS2CPacket.TYPE, MapTileS2CPacket.CODEC);
        onceS2C(MapTerrainS2CPacket.TYPE, MapTerrainS2CPacket.CODEC);
        onceS2C(MapPlayersS2CPacket.TYPE, MapPlayersS2CPacket.CODEC);
        onceS2C(SelfEyeS2CPacket.TYPE, SelfEyeS2CPacket.CODEC);
        onceS2C(PoseDebugPacket.TYPE, PoseDebugPacket.CODEC);
        onceC2S(PoseDebugPacket.TYPE, PoseDebugPacket.CODEC);
        onceS2C(MedkitChannelS2CPacket.TYPE, MedkitChannelS2CPacket.CODEC);
        // Phase-3 snapshot + view channels: payload types only; the server stream/history (U10'')
        // and the client buffer/driver (U12'') own the handlers. The phase-2 U17 bone debug
        // channel is removed: F3+B reads the locally captured posed model and driven feet,
        // never a server bone stream.
        onceS2C(PlayerSnapshotS2CPacket.TYPE, snapshotWire());
        onceC2S(CombatViewC2SPacket.TYPE, viewWire());
        onceC2S(SnapshotRestartC2SPacket.TYPE, SnapshotRestartC2SPacket.CODEC);
        // ADR4 exact transport (U8): capsules, timing probes and applied reports.
        onceS2C(AnimationEventS2CPacket.TYPE, AnimationEventS2CPacket.CODEC);
        onceS2C(AnimationProbeS2CPacket.TYPE, AnimationProbeS2CPacket.CODEC);
        onceC2S(AnimationProbeC2SPacket.TYPE, AnimationProbeC2SPacket.CODEC);
        onceC2S(AnimationAppliedC2SPacket.TYPE, AnimationAppliedC2SPacket.CODEC);
        // ADR4.2 envelope v2 (WIRE-CODEC): interned, fragmented snapshot/event transactions and
        // fragmented applied reports/tickets. The senders and receivers own the handlers.
        onceS2C(AnimationWireS2CPacket.TYPE, AnimationWireS2CPacket.CODEC);
        onceC2S(AnimationWireC2SPacket.TYPE, AnimationWireC2SPacket.CODEC);
        // ADR4 §6 READY (U9) for a newly activated generation; registerConfiguration has the
        // configuration-phase pair that precedes JOIN.
        onceS2C(AnimationManifestS2CPacket.TYPE, AnimationManifestS2CPacket.CODEC);
        onceC2S(AnimationReadyC2SPacket.TYPE, AnimationReadyC2SPacket.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                RadioStateC2SPacket.TYPE,
                (payload, context) ->
                        VoiceProxyHolder.getProxy()
                                .setRadioHeld(context.player().getUUID(), payload.isHeld()));
    }

    /**
     * Thin-Workshop registration: handshake-adjacent map/AI/menu payloads only. The combat scene
     * clock, tickets, anim-sync, voice and match UI channels stay unregistered (and their classes
     * never load on the Workshop profile). Called by BlockFieldServer on that profile instead of
     * {@link #register()}; the game path still calls {@link #register()}.
     *
     * <p>The game jar runs both entrypoints, so on the Workshop profile {@link #register()} has
     * already claimed the shared types: each id registers once, and the second call only adds the
     * Workshop-only remainder instead of crashing on the duplicates.
     */
    private static final Set<ResourceLocation> REGISTERED_C2S = new HashSet<>();

    private static final Set<ResourceLocation> REGISTERED_S2C = new HashSet<>();

    private static synchronized <T extends CustomPacketPayload> void onceC2S(
            CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf, T> codec) {
        if (REGISTERED_C2S.add(type.id())) {
            PayloadTypeRegistry.playC2S().register(type, codec);
        }
    }

    private static synchronized <T extends CustomPacketPayload> void onceS2C(
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

    private static boolean configuration;

    /** ADR4 §6 READY before JOIN (U9); each side's READY owner calls it once. */
    public static synchronized void registerConfiguration() {
        if (configuration) return;
        configuration = true;
        PayloadTypeRegistry.configurationS2C()
                .register(AnimationManifestS2CPacket.TYPE, AnimationManifestS2CPacket.CODEC);
        PayloadTypeRegistry.configurationC2S()
                .register(AnimationReadyC2SPacket.TYPE, AnimationReadyC2SPacket.CODEC);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        ServerPlayNetworking.send(player, packet);
    }

    /** Звук «в ушах» игрока: без 3D-затухания и без слышимости для окружающих. */
    /** Итог боя для этого игрока: "win", "loss" или "draw" (музыка победы/поражения на клиенте). */
    public static void sendMatchResult(ServerPlayer player, String result) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("matchResult", result);
        sendToPlayer(player, new UpdateUIS2CPacket(json.toString()));
    }

    public static void sendSound2D(
            ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        if (player == null || sound == null) return;
        sendToPlayer(
                player, new PlaySound2DS2CPacket(sound.getLocation().toString(), volume, pitch));
    }
}
