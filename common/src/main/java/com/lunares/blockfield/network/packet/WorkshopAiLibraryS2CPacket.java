package com.lunares.blockfield.network.packet;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Bounded sketch-library listing: summaries only, never plans. The client fetches one plan at a
 * time with a {@code take} call. Old clients never register this channel and get nothing.
 */
public record WorkshopAiLibraryS2CPacket(List<Entry> entries) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_NAME = 64;
    public static final Type<WorkshopAiLibraryS2CPacket> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            "blockfield", "workshop_ai_library_list"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiLibraryS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    WorkshopAiLibraryS2CPacket::write, WorkshopAiLibraryS2CPacket::new);

    public record Entry(UUID id, String name, int sx, int sy, int sz, int cells, long createdAt) {
        public Entry {
            if (id == null
                    || name == null
                    || name.isEmpty()
                    || name.length() > MAX_NAME
                    || sx < 1
                    || sx > WorkshopAiPreviewS2CPacket.MAX_SIDE
                    || sy < 1
                    || sy > WorkshopAiPreviewS2CPacket.MAX_SIDE
                    || sz < 1
                    || sz > WorkshopAiPreviewS2CPacket.MAX_SIDE
                    || cells < 0
                    || cells > 32768
                    || createdAt < 0) throw new IllegalArgumentException("Invalid library entry");
        }
    }

    public WorkshopAiLibraryS2CPacket {
        if (entries == null || entries.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid workshop AI library list");
        entries = List.copyOf(entries);
    }

    private WorkshopAiLibraryS2CPacket(FriendlyByteBuf buf) {
        this(readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid workshop AI library list");
        var entries = new java.util.ArrayList<Entry>(count);
        for (int i = 0; i < count; i++)
            entries.add(
                    new Entry(
                            buf.readUUID(),
                            buf.readUtf(MAX_NAME),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarLong()));
        return List.copyOf(entries);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUUID(entry.id());
            buf.writeUtf(entry.name(), MAX_NAME);
            buf.writeVarInt(entry.sx());
            buf.writeVarInt(entry.sy());
            buf.writeVarInt(entry.sz());
            buf.writeVarInt(entry.cells());
            buf.writeVarLong(entry.createdAt());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
