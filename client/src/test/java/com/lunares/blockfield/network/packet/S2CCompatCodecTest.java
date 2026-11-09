package com.lunares.blockfield.network.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.workshop.WorkshopAiProgress;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * N-1 compatibility: binary S2C payloads degrade to an ignored INVALID instead of disconnecting.
 */
class S2CCompatCodecTest {
    private static final ResourceLocation DIM =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, packet);
        T decoded = codec.decode(buf);
        assertEquals(0, buf.readableBytes());
        return decoded;
    }

    private static FriendlyByteBuf encodedBytes(
            StreamCodec<FriendlyByteBuf, ?> codec, Object packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        @SuppressWarnings({"unchecked", "rawtypes"})
        var raw = (StreamCodec<FriendlyByteBuf, Object>) codec;
        raw.encode(buf, packet);
        return buf;
    }

    private static FriendlyByteBuf firstBytes(
            StreamCodec<FriendlyByteBuf, ?> codec, Object packet, int keep) {
        var buf = encodedBytes(codec, packet);
        byte[] full = new byte[buf.readableBytes()];
        buf.readBytes(full);
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(full, 0, keep));
    }

    private static void assertInvalidConsumed(FriendlyByteBuf buf, boolean valid) {
        assertFalse(valid);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void radioRoundTrip() {
        var decoded =
                roundTrip(
                        RadioStateS2CPacket.CODEC,
                        new RadioStateS2CPacket(UUID.randomUUID(), true));
        assertTrue(decoded.valid());
        assertTrue(decoded.held());
    }

    @Test
    void radioTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        RadioStateS2CPacket.CODEC,
                        new RadioStateS2CPacket(UUID.randomUUID(), true),
                        5);
        assertInvalidConsumed(buf, RadioStateS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void radioTrailingFieldStaysValid() {
        var buf =
                encodedBytes(
                        RadioStateS2CPacket.CODEC,
                        new RadioStateS2CPacket(UUID.randomUUID(), true));
        buf.writeBoolean(true); // field added by a newer server
        var decoded = RadioStateS2CPacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void selfEyeRoundTrip() {
        var decoded =
                roundTrip(SelfEyeS2CPacket.CODEC, new SelfEyeS2CPacket(100L, 0.1f, 0.2f, -0.1f));
        assertTrue(decoded.valid());
        assertEquals(100L, decoded.serverTick());
    }

    @Test
    void selfEyeTruncatedIsInvalid() {
        var buf = firstBytes(SelfEyeS2CPacket.CODEC, new SelfEyeS2CPacket(1L, 0f, 0f, 0f), 4);
        assertInvalidConsumed(buf, SelfEyeS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void selfEyeTrailingFieldStaysValid() {
        var buf = encodedBytes(SelfEyeS2CPacket.CODEC, new SelfEyeS2CPacket(1L, 0f, 0f, 0f));
        buf.writeByte(7); // field added by a newer server
        var decoded = SelfEyeS2CPacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void soundRoundTrip() {
        var decoded =
                roundTrip(
                        PlaySound2DS2CPacket.CODEC,
                        new PlaySound2DS2CPacket("blockfield:test", 1.0f, 0.5f));
        assertTrue(decoded.valid());
        assertEquals("blockfield:test", decoded.soundId);
    }

    @Test
    void soundTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        PlaySound2DS2CPacket.CODEC,
                        new PlaySound2DS2CPacket("blockfield:test", 1.0f, 0.5f),
                        4);
        assertInvalidConsumed(buf, PlaySound2DS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void staminaRoundTrip() {
        var decoded = roundTrip(ConsumeStaminaS2CPacket.CODEC, new ConsumeStaminaS2CPacket(5));
        assertTrue(decoded.valid());
        assertEquals(5, decoded.amount);
    }

    @Test
    void staminaTrailingFieldStaysValid() {
        var buf = encodedBytes(ConsumeStaminaS2CPacket.CODEC, new ConsumeStaminaS2CPacket(5));
        buf.writeVarInt(9); // field added by a newer server
        var decoded = ConsumeStaminaS2CPacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(5, decoded.amount);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void medkitRoundTrip() {
        var decoded =
                roundTrip(MedkitChannelS2CPacket.CODEC, new MedkitChannelS2CPacket(3, 7, true));
        assertTrue(decoded.valid());
        assertEquals(3, decoded.player());
    }

    @Test
    void medkitTruncatedIsInvalid() {
        var buf =
                firstBytes(MedkitChannelS2CPacket.CODEC, new MedkitChannelS2CPacket(3, 7, true), 1);
        assertInvalidConsumed(buf, MedkitChannelS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void mapPingRoundTrip() {
        var decoded =
                roundTrip(MapPingS2CPacket.CODEC, new MapPingS2CPacket("AB", 1.0, 64.0, 2.0, 10));
        assertTrue(decoded.valid());
        assertEquals("AB", decoded.author());
    }

    @Test
    void mapPingTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        MapPingS2CPacket.CODEC, new MapPingS2CPacket("AB", 1.0, 64.0, 2.0, 10), 6);
        assertInvalidConsumed(buf, MapPingS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void mapImageRoundTrip() {
        var decoded =
                roundTrip(
                        MapImageS2CPacket.CODEC,
                        new MapImageS2CPacket(DIM, "m", 0, 64, 0, 64, 2, 3, new byte[18]));
        assertTrue(decoded.valid());
        assertEquals(2, decoded.width());
    }

    @Test
    void mapImageTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        MapImageS2CPacket.CODEC,
                        new MapImageS2CPacket(DIM, "m", 0, 64, 0, 64, 2, 3, new byte[18]),
                        10);
        assertInvalidConsumed(buf, MapImageS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void mapTileRoundTrip() {
        var decoded =
                roundTrip(
                        MapTileS2CPacket.CODEC,
                        new MapTileS2CPacket(DIM, "m", 0, 64, 0, 64, 2, 3, 5L, 0, new byte[18]));
        assertTrue(decoded.valid());
        assertEquals(5L, decoded.revision());
    }

    @Test
    void mapTileTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        MapTileS2CPacket.CODEC,
                        new MapTileS2CPacket(DIM, "m", 0, 64, 0, 64, 2, 3, 5L, 0, new byte[18]),
                        10);
        assertInvalidConsumed(buf, MapTileS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void mapTileTrailingFieldStaysValid() {
        var buf =
                encodedBytes(
                        MapTileS2CPacket.CODEC,
                        new MapTileS2CPacket(DIM, "m", 0, 64, 0, 64, 2, 3, 5L, 0, new byte[18]));
        buf.writeLong(99L); // field added by a newer server
        var decoded = MapTileS2CPacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void mapSnapshotRoundTrip() {
        var decoded =
                roundTrip(
                        MapSnapshotS2CPacket.CODEC,
                        new MapSnapshotS2CPacket(
                                DIM, "m", 0, 64, 0, 64, new byte[MapSnapshotS2CPacket.BYTES]));
        assertTrue(decoded.valid());
    }

    @Test
    void mapSnapshotTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        MapSnapshotS2CPacket.CODEC,
                        new MapSnapshotS2CPacket(
                                DIM, "m", 0, 64, 0, 64, new byte[MapSnapshotS2CPacket.BYTES]),
                        10);
        assertInvalidConsumed(buf, MapSnapshotS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void mapTerrainRoundTrip() {
        var decoded = roundTrip(MapTerrainS2CPacket.CODEC, terrain());
        assertTrue(decoded.valid());
        assertEquals(2L, decoded.revision());
    }

    @Test
    void mapTerrainTruncatedIsInvalid() {
        var buf = firstBytes(MapTerrainS2CPacket.CODEC, terrain(), 10);
        assertInvalidConsumed(buf, MapTerrainS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void workshopStateRoundTrip() {
        var decoded = roundTrip(WorkshopAiStateS2CPacket.CODEC, workshopState());
        assertTrue(decoded.valid());
        assertEquals("planning", decoded.stage());
    }

    @Test
    void workshopStateTruncatedIsInvalid() {
        var buf = firstBytes(WorkshopAiStateS2CPacket.CODEC, workshopState(), 10);
        assertInvalidConsumed(buf, WorkshopAiStateS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void workshopStateUnknownStageIsInvalid() {
        // A newer server names a stage this client never learned: ignored, not a disconnect.
        var buf = encodedBytes(WorkshopAiStateS2CPacket.CODEC, workshopState());
        var decoded = WorkshopAiStateS2CPacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void workshopLibraryRoundTrip() {
        var decoded =
                roundTrip(
                        WorkshopAiLibraryS2CPacket.CODEC,
                        new WorkshopAiLibraryS2CPacket(
                                List.of(
                                        new WorkshopAiLibraryS2CPacket.Entry(
                                                UUID.randomUUID(), "n", 1, 1, 1, 0, 0))));
        assertTrue(decoded.valid());
        assertEquals(1, decoded.entries().size());
    }

    @Test
    void workshopLibraryTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        WorkshopAiLibraryS2CPacket.CODEC,
                        new WorkshopAiLibraryS2CPacket(List.of()),
                        0);
        // Empty payload decodes to an empty (valid) list; a cut entry is invalid.
        var cut =
                firstBytes(
                        WorkshopAiLibraryS2CPacket.CODEC,
                        new WorkshopAiLibraryS2CPacket(
                                List.of(
                                        new WorkshopAiLibraryS2CPacket.Entry(
                                                UUID.randomUUID(), "n", 1, 1, 1, 0, 0))),
                        5);
        assertInvalidConsumed(cut, WorkshopAiLibraryS2CPacket.CODEC.decode(cut).valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void workshopPlanChunkRoundTrip() {
        var decoded = roundTrip(WorkshopAiPlanChunkS2CPacket.CODEC, planChunk());
        assertTrue(decoded.valid());
        assertEquals("x", decoded.data());
    }

    @Test
    void workshopPlanChunkTruncatedIsInvalid() {
        var buf = firstBytes(WorkshopAiPlanChunkS2CPacket.CODEC, planChunk(), 10);
        assertInvalidConsumed(buf, WorkshopAiPlanChunkS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void workshopPreviewRoundTrip() {
        var decoded =
                roundTrip(
                        WorkshopAiPreviewS2CPacket.CODEC,
                        new WorkshopAiPreviewS2CPacket(
                                UUID.randomUUID(), UUID.randomUUID(), BlockPos.ZERO, "plan"));
        assertTrue(decoded.valid());
    }

    @Test
    void workshopPreviewTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        WorkshopAiPreviewS2CPacket.CODEC,
                        new WorkshopAiPreviewS2CPacket(
                                UUID.randomUUID(), UUID.randomUUID(), BlockPos.ZERO, "plan"),
                        10);
        assertInvalidConsumed(buf, WorkshopAiPreviewS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void workshopProgressRoundTrip() {
        var decoded = roundTrip(WorkshopAiProgressS2CPacket.CODEC, progress());
        assertTrue(decoded.valid());
        assertEquals(0L, decoded.serverNow());
    }

    @Test
    void workshopProgressTruncatedIsInvalid() {
        var buf = firstBytes(WorkshopAiProgressS2CPacket.CODEC, progress(), 10);
        assertInvalidConsumed(buf, WorkshopAiProgressS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void workshopSketchPlanRoundTrip() {
        var decoded =
                roundTrip(
                        WorkshopAiSketchPlanS2CPacket.CODEC,
                        new WorkshopAiSketchPlanS2CPacket(UUID.randomUUID(), "n", 1, 1, 1, "plan"));
        assertTrue(decoded.valid());
    }

    @Test
    void workshopSketchPlanTruncatedIsInvalid() {
        var buf =
                firstBytes(
                        WorkshopAiSketchPlanS2CPacket.CODEC,
                        new WorkshopAiSketchPlanS2CPacket(UUID.randomUUID(), "n", 1, 1, 1, "plan"),
                        10);
        assertInvalidConsumed(buf, WorkshopAiSketchPlanS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void schemaVersionMismatchNamesVersions() {
        // A newer server's event batch must reject with a versioned message, not a raw disconnect.
        var buf =
                encodedBytes(AnimationEventS2CPacket.CODEC, new AnimationEventS2CPacket(List.of()));
        buf.setByte(0, AnimationSchema.VERSION + 1);
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AnimationEventS2CPacket.CODEC.decode(buf));
        assertTrue(failure.getMessage().contains("v" + (AnimationSchema.VERSION + 1)));
        assertTrue(failure.getMessage().contains("v" + AnimationSchema.VERSION));
        assertTrue(failure.getMessage().contains("update BlockField"));
    }

    @Test
    void schemaCheckRejectsUnknownVersion() {
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                AnimationSchema.checkVersion(
                                        AnimationSchema.VERSION + 1, "snapshot"));
        assertTrue(failure.getMessage().contains("v" + (AnimationSchema.VERSION + 1)));
        AnimationSchema.checkVersion(AnimationSchema.VERSION, "snapshot");
    }

    private static MapTerrainS2CPacket terrain() {
        return new MapTerrainS2CPacket(
                DIM,
                "m",
                0,
                64,
                0,
                64,
                2,
                3,
                2L,
                1L,
                1,
                0,
                new int[] {7},
                new int[] {0xFF0000},
                new byte[12],
                new byte[6]);
    }

    private static WorkshopAiStateS2CPacket workshopState() {
        return new WorkshopAiStateS2CPacket(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "planning",
                "msg",
                "prompt",
                BlockPos.ZERO,
                BlockPos.ZERO,
                10,
                3,
                true,
                false);
    }

    private static WorkshopAiPlanChunkS2CPacket planChunk() {
        return new WorkshopAiPlanChunkS2CPacket(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BlockPos.ZERO,
                "n",
                1,
                1,
                1,
                0,
                false,
                false,
                0,
                1,
                "x");
    }

    private static WorkshopAiProgressS2CPacket progress() {
        return new WorkshopAiProgressS2CPacket(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new WorkshopAiProgress(WorkshopAiProgress.Phase.NONE, 0, 0, 0, 0),
                0L);
    }
}
