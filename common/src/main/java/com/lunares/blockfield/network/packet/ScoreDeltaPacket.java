package com.lunares.blockfield.network.packet;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class ScoreDeltaPacket implements CustomPacketPayload {
    public static final Type<ScoreDeltaPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "score_delta"));
    public static final StreamCodec<FriendlyByteBuf, ScoreDeltaPacket> CODEC =
            CustomPacketPayload.codec(ScoreDeltaPacket::toBytes, ScoreDeltaPacket::decode);

    private static final ScoreDeltaPacket INVALID =
            new ScoreDeltaPacket(new UUID(0, 0), 0, 0, 0.0, 0, "assault", 0);

    public final UUID playerUUID;
    public final int kills;
    public final int deaths;
    public final double score;
    public final int pointTime;
    public final String classId;
    public final int streak;

    public ScoreDeltaPacket(
            UUID uuid,
            int kills,
            int deaths,
            double score,
            int pointTime,
            String classId,
            int streak) {
        this.playerUUID = uuid;
        this.kills = kills;
        this.deaths = deaths;
        this.score = score;
        this.pointTime = pointTime;
        this.classId = classId != null ? classId : "assault";
        this.streak = streak;
    }

    public ScoreDeltaPacket(FriendlyByteBuf buf) {
        this.playerUUID = buf.readUUID();
        this.kills = buf.readInt();
        this.deaths = buf.readInt();
        this.score = buf.readDouble();
        this.pointTime = buf.readInt();
        this.classId = buf.readUtf();
        this.streak = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(this.playerUUID);
        buf.writeInt(this.kills);
        buf.writeInt(this.deaths);
        buf.writeDouble(this.score);
        buf.writeInt(this.pointTime);
        buf.writeUtf(this.classId);
        buf.writeInt(this.streak);
    }

    /** False only for a wire payload that failed to decode; receivers ignore those. */
    public boolean valid() {
        return this != INVALID;
    }

    /** Malformed payloads degrade to the invalid sentinel instead of throwing. */
    static ScoreDeltaPacket decode(FriendlyByteBuf buf) {
        try {
            return new ScoreDeltaPacket(buf);
        } catch (RuntimeException bad) {
            return INVALID;
        } finally {
            // PacketDecoder disconnects on any unread bytes, so consume this
            // packet's slice: trailing fields added by newer servers and
            // malformed tails are both ignored instead of fatal.
            buf.skipBytes(buf.readableBytes());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
