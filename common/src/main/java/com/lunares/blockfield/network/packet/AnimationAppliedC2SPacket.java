package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** New OBSERVER_APPLY entries for one immutable revision; flush before its ordered attack. */
public record AnimationAppliedC2SPacket(CombatView view) implements CustomPacketPayload {
    public static final Type<AnimationAppliedC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "animation_applied"));
    public static final StreamCodec<FriendlyByteBuf, AnimationAppliedC2SPacket> CODEC =
            StreamCodec.of(AnimationAppliedC2SPacket::write, AnimationAppliedC2SPacket::read);

    public AnimationAppliedC2SPacket {
        view.validateAnimation();
        if (view.fireEye() != null)
            throw new IllegalArgumentException("Applied report carries a fire eye");
        if (view.animation().starts() == null)
            throw new IllegalArgumentException("Applied report needs its victim rows");
        for (var victim : view.animation().starts().victims())
            for (var start : victim.starts())
                if (start.anchorClass() != AnimationTypes.AnchorClass.OBSERVER_APPLY)
                    throw new IllegalArgumentException(
                            "Applied report contains nonapplication start");
    }

    private static void write(FriendlyByteBuf b, AnimationAppliedC2SPacket packet) {
        CombatViewC2SPacket.ANIMATION_CODEC.encode(b, new CombatViewC2SPacket(packet.view()));
    }

    private static AnimationAppliedC2SPacket read(FriendlyByteBuf b) {
        return new AnimationAppliedC2SPacket(CombatViewC2SPacket.ANIMATION_CODEC.decode(b).view());
    }

    /** Canonical logical body on ADR4.2's bounded C2S envelope. */
    public java.util.List<AnimationWireC2SPacket> fragments() {
        CombatView view = view();
        view.validateAnimation();
        byte[] body = AnimationWireC2SPacket.applied(this);
        var receipt = view.animation();
        var header =
                com.lunares.blockfield.common.combat.AnimationWire.Header.of(
                        com.lunares.blockfield.common.combat.AnimationWire.Family.APPLIED,
                        0,
                        receipt.connectionId(),
                        view.observerStreamEpoch(),
                        receipt.sceneGeneration(),
                        view.animation().presentationRevision(),
                        view.receivedSnapshotTick(),
                        0,
                        body);
        return AnimationWireC2SPacket.packets(header, body);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
