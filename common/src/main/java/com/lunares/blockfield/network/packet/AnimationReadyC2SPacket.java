package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationManifest;
import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ADR4 §6 READY answer (U9): the client's own value for every entry of the server manifest, in the
 * server's order ({@code null}: the client cannot produce it). The server decides; the client
 * installs its generation only when its own comparison of the same values matches. Layout: {@code
 * resourceGeneration uvarlong, policyGeneration uvarlong, count uvarint, count × (present u8,
 * digest 32 bytes if present)}.
 */
public record AnimationReadyC2SPacket(
        long resourceGeneration, long policyGeneration, List<Digest> values)
        implements CustomPacketPayload {
    public static final Type<AnimationReadyC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "animation_ready"));
    public static final StreamCodec<FriendlyByteBuf, AnimationReadyC2SPacket> CODEC =
            StreamCodec.of(AnimationReadyC2SPacket::write, AnimationReadyC2SPacket::read);

    public AnimationReadyC2SPacket {
        AnimationSchema.nonnegative(resourceGeneration, "resource generation");
        AnimationSchema.nonnegative(policyGeneration, "policy generation");
        AnimationSchema.count(values.size(), AnimationManifest.MAX_ENTRIES, "ready values");
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static void write(FriendlyByteBuf b, AnimationReadyC2SPacket packet) {
        SnapshotWireMath.writeUvarLong(b, packet.resourceGeneration());
        SnapshotWireMath.writeUvarLong(b, packet.policyGeneration());
        SnapshotWireMath.writeUvarInt(b, packet.values().size());
        for (Digest value : packet.values()) {
            b.writeBoolean(value != null);
            if (value != null) AnimationSchema.writeDigest(b, value);
        }
    }

    private static AnimationReadyC2SPacket read(FriendlyByteBuf b) {
        long generation = SnapshotWireMath.readUvarLong(b);
        long policy = SnapshotWireMath.readUvarLong(b);
        int count = AnimationSchema.readCount(b, AnimationManifest.MAX_ENTRIES, 1);
        var values = new ArrayList<Digest>(count);
        for (int i = 0; i < count; i++) {
            AnimationSchema.require(b, 1);
            int present = b.readUnsignedByte();
            if (present > 1) throw new IllegalArgumentException("Noncanonical presence byte");
            values.add(present == 1 ? AnimationSchema.readDigest(b) : null);
        }
        if (b.isReadable()) throw new IllegalArgumentException("Trailing ready bytes");
        return new AnimationReadyC2SPacket(generation, policy, values);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
