package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.AnimationManifest;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ADR4 §6 READY request (U9): the server's combat-animation manifest of one resource generation and
 * its policy. Sent in the configuration phase before JOIN (Velocity relays configuration payloads,
 * not backend login queries) and again in play when the server activates a new generation. Layout:
 * {@link AnimationManifest#write}, exact payload exhaustion.
 */
public record AnimationManifestS2CPacket(AnimationManifest manifest)
        implements CustomPacketPayload {
    public static final Type<AnimationManifestS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "animation_manifest"));
    public static final StreamCodec<FriendlyByteBuf, AnimationManifestS2CPacket> CODEC =
            StreamCodec.of(
                    (b, packet) -> packet.manifest().write(b),
                    b -> {
                        var manifest = AnimationManifest.read(b);
                        if (b.isReadable())
                            throw new IllegalArgumentException("Trailing manifest bytes");
                        return new AnimationManifestS2CPacket(manifest);
                    });

    public AnimationManifestS2CPacket {
        Objects.requireNonNull(manifest, "manifest");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
