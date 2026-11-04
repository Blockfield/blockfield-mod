package com.lunares.blockfield.network.packet;

import com.lunares.blockfield.common.combat.CombatView;
import com.lunares.blockfield.common.combat.SnapshotWireMath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server view ticket (phase 3): the shooter's rendered receipt (R, H) for one allowlisted
 * attack, sent immediately before it on the same order channel. Content, in order: {@code sequence
 * uvarlong, observerStreamEpoch uvarlong, receivedSnapshotTick H uvarlong, tick uvarlong
 * (absolute), alpha u16}. All uvarlongs are canonical non-negative values of at most 9 bytes; alpha
 * is {@code alpha / 65536}. No target id/epoch, no gap bits. Since protocol 9 the victim report is
 * preceded by a presence byte: 0 means the client had no complete report and the server rewinds
 * retained poses to R (ADR4 R10 safety net). The receipt body may end with the optional fire eye
 * (three big-endian doubles, finite, protocol 8), optionally followed by the fire aim (yaw, pitch
 * big-endian floats); any other tail length or a truncated body is rejected, and a body without
 * them decodes as before.
 */
public record CombatViewC2SPacket(
        long sequence,
        long observerStreamEpoch,
        long receivedSnapshotTick,
        long tick,
        int alpha,
        CombatView.Receipt animation,
        net.minecraft.world.phys.Vec3 fireEye,
        CombatView.Aim fireAim)
        implements CustomPacketPayload {
    public static final Type<CombatViewC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "combat_view"));
    private static final int FIRE_EYE_BYTES = 3 * Double.BYTES;
    private static final int FIRE_AIM_BYTES = FIRE_EYE_BYTES + 2 * Float.BYTES;
    public static final StreamCodec<FriendlyByteBuf, CombatViewC2SPacket> ANIMATION_CODEC =
            StreamCodec.of(CombatViewC2SPacket::writeAnimation, CombatViewC2SPacket::readAnimation);

    public CombatViewC2SPacket(CombatView view) {
        this(
                view.sequence(),
                view.observerStreamEpoch(),
                view.receivedSnapshotTick(),
                view.tick(),
                view.alpha(),
                view.animation(),
                view.fireEye(),
                view.fireAim());
    }

    public CombatViewC2SPacket {
        if (sequence < 0 || observerStreamEpoch < 0 || receivedSnapshotTick < 0 || tick < 0)
            throw new IllegalArgumentException("CombatView ticks must be non-negative");
        if (alpha < 0 || alpha > 0xFFFF)
            throw new IllegalArgumentException("CombatView alpha out of u16 range: " + alpha);
        java.util.Objects.requireNonNull(animation, "ADR4 receipt required");
    }

    /** The carried receipt as plain wire values. */
    public CombatView view() {
        return new CombatView(
                sequence,
                observerStreamEpoch,
                receivedSnapshotTick,
                tick,
                alpha,
                animation,
                fireEye,
                fireAim);
    }

    private static void writeAnimation(FriendlyByteBuf buf, CombatViewC2SPacket packet) {
        CombatView view = packet.view();
        view.validateAnimation();
        byte[] bytes =
                com.lunares.blockfield.common.combat.AnimationSchema.encodeBounded(
                        scratch -> writeReceipt(scratch, view));
        buf.writeBytes(bytes);
    }

    public static void writeReceipt(FriendlyByteBuf b, CombatView view) {
        view.validateAnimation();
        var a = view.animation();
        b.writeByte(com.lunares.blockfield.common.combat.AnimationSchema.VERSION);
        for (long value :
                new long[] {
                    view.sequence(),
                    a.connectionId(),
                    view.observerStreamEpoch(),
                    a.sceneGeneration(),
                    a.frameId(),
                    a.presentationRevision(),
                    view.receivedSnapshotTick()
                }) SnapshotWireMath.writeUvarLong(b, value);
        com.lunares.blockfield.common.combat.AnimationSchema.writeTime(b, view.r());
        com.lunares.blockfield.common.combat.AnimationSchema.writeTime(b, a.a());
        SnapshotWireMath.writeUvarLong(b, a.resourceGeneration());
        SnapshotWireMath.writeUvarLong(b, a.policyGeneration());
        com.lunares.blockfield.common.combat.AnimationSchema.writeDigest(
                b, a.compatibilityDigest());
        b.writeBoolean(a.starts() != null);
        if (a.starts() != null)
            com.lunares.blockfield.common.combat.AnimationStartReport.write(b, a.starts());
        if (view.fireEye() != null) {
            b.writeDouble(view.fireEye().x);
            b.writeDouble(view.fireEye().y);
            b.writeDouble(view.fireEye().z);
        }
        if (view.fireAim() != null) {
            b.writeFloat(view.fireAim().yaw());
            b.writeFloat(view.fireAim().pitch());
        }
    }

    public static CombatViewC2SPacket readAnimation(FriendlyByteBuf b) {
        com.lunares.blockfield.common.combat.AnimationSchema.count(
                b.readableBytes(),
                com.lunares.blockfield.common.combat.AnimationSchema.MAX_PAYLOAD_BYTES,
                "ticket payload");
        com.lunares.blockfield.common.combat.AnimationSchema.require(b, 1);
        if (b.readUnsignedByte() != com.lunares.blockfield.common.combat.AnimationSchema.VERSION)
            throw new IllegalArgumentException("Unknown animation receipt version");
        long seq = SnapshotWireMath.readUvarLong(b),
                connection = SnapshotWireMath.readUvarLong(b),
                stream = SnapshotWireMath.readUvarLong(b),
                scene = SnapshotWireMath.readUvarLong(b),
                frame = SnapshotWireMath.readUvarLong(b),
                revision = SnapshotWireMath.readUvarLong(b),
                h = SnapshotWireMath.readUvarLong(b);
        var r = com.lunares.blockfield.common.combat.AnimationSchema.readTime(b);
        var a = com.lunares.blockfield.common.combat.AnimationSchema.readTime(b);
        long resource = SnapshotWireMath.readUvarLong(b), policy = SnapshotWireMath.readUvarLong(b);
        var compatibility = com.lunares.blockfield.common.combat.AnimationSchema.readDigest(b);
        com.lunares.blockfield.common.combat.AnimationSchema.require(b, 1);
        int reported = b.readUnsignedByte();
        if (reported > 1) throw new IllegalArgumentException("Invalid victim report flag");
        var starts =
                reported == 0
                        ? null
                        : com.lunares.blockfield.common.combat.AnimationStartReport.read(b);
        net.minecraft.world.phys.Vec3 fireEye = null;
        CombatView.Aim fireAim = null;
        int tail = b.readableBytes();
        if (tail == FIRE_EYE_BYTES || tail == FIRE_AIM_BYTES)
            fireEye =
                    new net.minecraft.world.phys.Vec3(
                            b.readDouble(), b.readDouble(), b.readDouble());
        if (tail == FIRE_AIM_BYTES) fireAim = new CombatView.Aim(b.readFloat(), b.readFloat());
        if (b.isReadable()) throw new IllegalArgumentException("Trailing receipt bytes");
        var view =
                new CombatView(
                        seq,
                        stream,
                        h,
                        r.tick(),
                        r.alpha(),
                        new CombatView.Receipt(
                                connection,
                                scene,
                                frame,
                                revision,
                                a,
                                resource,
                                policy,
                                compatibility,
                                starts),
                        fireEye,
                        fireAim);
        view.validateAnimation();
        return new CombatViewC2SPacket(view);
    }

    /** Canonical logical body on ADR4.2's bounded C2S envelope. */
    public java.util.List<AnimationWireC2SPacket> fragments() {
        CombatView view = view();
        view.validateAnimation();
        byte[] body = AnimationWireC2SPacket.ticket(this);
        var receipt = view.animation();
        var header =
                com.lunares.blockfield.common.combat.AnimationWire.Header.of(
                        com.lunares.blockfield.common.combat.AnimationWire.Family.TICKET,
                        0,
                        receipt.connectionId(),
                        view.observerStreamEpoch(),
                        receipt.sceneGeneration(),
                        view.sequence(),
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
