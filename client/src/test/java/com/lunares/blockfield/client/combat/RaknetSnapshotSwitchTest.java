package com.lunares.blockfield.client.combat;

import static com.lunares.blockfield.client.combat.ExactFixtures.CONNECTION;
import static com.lunares.blockfield.client.combat.ExactFixtures.SCENE;
import static com.lunares.blockfield.client.combat.ExactFixtures.SUBSCRIPTION;
import static com.lunares.blockfield.client.combat.ExactFixtures.TARGET;
import static com.lunares.blockfield.client.combat.ExactFixtures.ZERO;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class RaknetSnapshotSwitchTest {
    /** One exact record: absolute placement x at the batch tick. */
    record Spec(int entity, double x, int flags) {}

    static Spec full(int entity, double x) {
        return new Spec(
                entity,
                x,
                AnimationRecipe.SnapshotRecord.FLAG_FULL
                        | AnimationRecipe.SnapshotRecord.FLAG_RESET);
    }

    static Spec delta(int entity, double x) {
        return new Spec(entity, x, 0);
    }

    static PlayerSnapshotS2CPacket sent(long epoch, long previous, long tick, Spec... specs) {
        var records = new ArrayList<AnimationRecipe.SnapshotRecord>();
        var seeds = new ArrayList<AnimationEvent.ObserverSeed>();
        for (var spec : specs) {
            var identity =
                    new AnimationState.Identity(
                            CONNECTION,
                            epoch,
                            SCENE,
                            spec.entity,
                            0,
                            SUBSCRIPTION,
                            TARGET,
                            0,
                            0,
                            0,
                            0,
                            AnimationSchema.layoutDigest(),
                            ZERO);
            var checkpoint = ExactFixtures.checkpoint(identity, tick, spec.x);
            records.add(
                    new AnimationRecipe.SnapshotRecord(
                            identity, spec.flags, checkpoint.placement(), checkpoint));
            if ((spec.flags & AnimationRecipe.SnapshotRecord.FLAG_FULL) != 0)
                seeds.add(ExactFixtures.seed(checkpoint, tick * 256 + 1));
        }
        return sent(epoch, previous, tick, records, seeds);
    }

    static PlayerSnapshotS2CPacket sent(
            long epoch,
            long previous,
            long tick,
            List<AnimationRecipe.SnapshotRecord> records,
            List<AnimationEvent.ObserverSeed> seeds) {
        return PlayerSnapshotS2CPacket.animation(
                epoch,
                previous,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(CONNECTION, SCENE, records, List.of(), seeds));
    }

    /** Held write promises model the marker awaiting its FrameSet ACK, not an application fence. */
    private static final class Wire extends ChannelOutboundHandlerAdapter {
        final List<Object> frames = new ArrayList<>();
        final List<ChannelPromise> promises = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (!message.getClass().getName().equals("network.ycc.raknet.frame.FrameData")) return;
            frames.add(message);
            promises.add(promise);
        }
    }

    private static void write(EmbeddedChannel sender, PlayerSnapshotS2CPacket batch) {
        var bytes = new FriendlyByteBuf(Unpooled.buffer());
        PlayerSnapshotS2CPacket.ANIMATION_CODEC.encode(bytes, batch);
        sender.writeOutbound(bytes);
    }

    @Test
    void realPinnedRepeatedRespawnSwitchReordersWithoutDefectsOrGuessedBases() throws Exception {
        for (String artifact : List.of("raknetifyJar", "raknetifyVelocityJar")) {
            Path jar = Path.of(System.getProperty("blockfield.test." + artifact));
            try (var loader =
                    new URLClassLoader(
                            new java.net.URL[] {jar.toUri().toURL()},
                            getClass().getClassLoader())) {
                var codecType =
                        loader.loadClass(
                                "com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec");
                var dataType = loader.loadClass("network.ycc.raknet.frame.FrameData");
                var frameType = loader.loadClass("network.ycc.raknet.frame.Frame");
                var overrideType = loader.loadClass(codecType.getName() + "$OverrideHandler");
                var codec = codecType.getConstructor(int.class).newInstance(253);
                // S2C payload class maps to channel 3; this override isolates that real codec path.
                var handler =
                        java.lang.reflect.Proxy.newProxyInstance(
                                loader, new Class<?>[] {overrideType}, (proxy, method, args) -> 3);
                codecType.getMethod("addHandler", overrideType).invoke(codec, handler);
                var wire = new Wire();
                var sender = new EmbeddedChannel(wire, (ChannelHandler) codec);
                var receiver =
                        new EmbeddedChannel(
                                (ChannelHandler)
                                        loader.loadClass("network.ycc.raknet.pipeline.FrameOrderIn")
                                                .getConstructor()
                                                .newInstance(),
                                (ChannelHandler)
                                        codecType.getConstructor(int.class).newInstance(253));
                try {
                    var channel = dataType.getMethod("getOrderChannel");
                    var create =
                            frameType.getMethod("createOrdered", dataType, int.class, int.class);
                    var buffer = new ClientSnapshotBuffer();
                    buffer.add(1, 0);
                    var applied = new ArrayList<Long>();
                    var order = new int[8];
                    // Absolute lossless placements: the exact stream never borrows a retired base.
                    var x = new double[] {1.0, 1.28, 1.84, 2.12, 2.68};
                    write(sender, sent(17, 0, 100, full(1, x[0])));
                    receiver.writeInbound(create.invoke(null, wire.frames.get(0), order[0]++, 0));
                    drain(receiver, buffer, applied, 0);
                    Object sync =
                            loader.loadClass(
                                            "com.ishland.raknetify.common.connection.SynchronizationLayer")
                                    .getField("SYNC_REQUEST_OBJECT")
                                    .get(null);
                    for (int round = 0; round < 2; round++) {
                        int start = wire.frames.size();
                        long before = 100 + round * 2;
                        // Pinned listeners emit SYNC before Respawn/Login and START before
                        // Commands.
                        // 0xfe/0xff stand for their encoded native packets; only snapshots are
                        // decoded below.
                        sender.writeOutbound(sync);
                        sender.writeOutbound(Unpooled.buffer().writeByte(0xfe));
                        sender.writeOutbound(
                                codecType.getField("SIGNAL_START_MULTICHANNEL").get(null));
                        sender.writeOutbound(Unpooled.buffer().writeByte(0xff));
                        write(sender, sent(17, before + 1, before + 1, delta(1, x[round * 2 + 1])));
                        assertEquals(start + 2, wire.frames.size());
                        wire.promises.get(start + 1).setSuccess();
                        sender.runPendingTasks();
                        write(sender, sent(17, before + 2, before + 2, delta(1, x[round * 2 + 2])));
                        var channels = new ArrayList<Integer>();
                        var indices = new int[5];
                        for (int n = 0; n < 5; n++) {
                            int ch = (Integer) channel.invoke(wire.frames.get(start + n));
                            channels.add(ch);
                            indices[n] = order[ch]++;
                        }
                        assertEquals(List.of(0, 7, 0, 0, 3), channels);
                        for (int n : List.of(1, 4))
                            receiver.writeInbound(
                                    create.invoke(null, wire.frames.get(start + n), indices[n], 0));
                        drain(receiver, buffer, applied, round * 100 + 1);
                        assertEquals(before, buffer.high());
                        assertEquals(0, buffer.defects());
                        for (int n : List.of(0, 2, 3))
                            receiver.writeInbound(
                                    create.invoke(null, wire.frames.get(start + n), indices[n], 0));
                        drain(receiver, buffer, applied, round * 100 + 2);
                        assertEquals(before + 2, buffer.high());
                        assertEquals(
                                1 + (round + 1) * .84, buffer.frames(1).get(before + 2).x(), 1e-9);
                        assertEquals(0, buffer.defects());
                    }
                    assertEquals(List.of(100L, 101L, 102L, 103L, 104L), applied);
                } finally {
                    sender.finishAndReleaseAll();
                    receiver.finishAndReleaseAll();
                    wire.frames.forEach(ReferenceCountUtil::release);
                }
            }
        }
    }

    private static void drain(
            EmbeddedChannel receiver, ClientSnapshotBuffer buffer, List<Long> applied, double now) {
        ByteBuf bytes;
        while ((bytes = receiver.readInbound()) != null) {
            try {
                int tag = bytes.getUnsignedByte(bytes.readerIndex());
                if (tag == 0xfe) {
                    buffer.sceneReset(id -> id == 1);
                    buffer.add(1, 0, true);
                } else if (tag != 0xff) {
                    buffer.apply(
                            PlayerSnapshotS2CPacket.ANIMATION_CODEC.decode(
                                    new FriendlyByteBuf(bytes)),
                            id -> true,
                            now,
                            batch -> applied.add(batch.serverTick()));
                }
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void pinnedFabricAndVelocityListenersRearmSyncOnRespawnAndLoginBeforeStartingAtCommands()
            throws Exception {
        for (String artifact : List.of("raknetifyJar", "raknetifyVelocityJar")) {
            boolean fabric = artifact.equals("raknetifyJar");
            String name =
                    fabric
                            ? "com/ishland/raknetify/fabric/common/connection/RakNetFabricChannelEventListener.class"
                            : "com/ishland/raknetify/velocity/connection/RakNetVelocityChannelEventListener.class";
            try (var jar = new ZipFile(System.getProperty("blockfield.test." + artifact))) {
                var node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(jar.getInputStream(jar.getEntry(name)))
                        .accept(node, 0);
                var write =
                        node.methods.stream()
                                .filter(m -> m.name.equals("write"))
                                .findFirst()
                                .orElseThrow();
                for (String type :
                        fabric
                                ? List.of(
                                        "net/minecraft/class_2724",
                                        "net/minecraft/class_2678",
                                        "net/minecraft/class_2641")
                                : List.of(
                                        "com/velocitypowered/proxy/protocol/packet/RespawnPacket",
                                        "com/velocitypowered/proxy/protocol/packet/JoinGamePacket",
                                        "com/velocitypowered/proxy/protocol/packet/AvailableCommandsPacket")) {
                    boolean found = false;
                    for (var instruction : write.instructions) {
                        if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode t
                                && t.desc.equals(type)) {
                            for (var next = instruction.getNext();
                                    next != null;
                                    next = next.getNext()) {
                                if (next instanceof org.objectweb.asm.tree.FieldInsnNode field
                                        && field.getOpcode()
                                                == org.objectweb.asm.Opcodes.GETSTATIC) {
                                    assertEquals(
                                            type.endsWith("class_2641")
                                                            || type.endsWith(
                                                                    "AvailableCommandsPacket")
                                                    ? "SIGNAL_START_MULTICHANNEL"
                                                    : "SYNC_REQUEST_OBJECT",
                                            field.name,
                                            type);
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }
                    assertTrue(found, type);
                }
            }
        }
    }

    @Test
    void realPinnedQueueRestoresWithinChannelDatagramReordering() throws Exception {
        Path jar = Path.of(System.getProperty("blockfield.test.raknetifyJar"));
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            var dataType = loader.loadClass("network.ycc.raknet.frame.FrameData");
            var frameType = loader.loadClass("network.ycc.raknet.frame.Frame");
            var createData =
                    dataType.getMethod(
                            "create",
                            io.netty.buffer.ByteBufAllocator.class,
                            int.class,
                            ByteBuf.class);
            var createFrame = frameType.getMethod("createOrdered", dataType, int.class, int.class);
            var receiver =
                    new EmbeddedChannel(
                            (ChannelHandler)
                                    loader.loadClass("network.ycc.raknet.pipeline.FrameOrderIn")
                                            .getConstructor()
                                            .newInstance());
            try {
                for (int index : List.of(0, 2, 1)) {
                    var bytes = Unpooled.buffer().writeInt(100 + index);
                    Object data;
                    try {
                        data =
                                createData.invoke(
                                        null, io.netty.buffer.ByteBufAllocator.DEFAULT, 253, bytes);
                    } finally {
                        bytes.release();
                    }
                    try {
                        dataType.getMethod("setOrderChannel", int.class).invoke(data, 3);
                        receiver.writeInbound(createFrame.invoke(null, data, index, 0));
                    } finally {
                        ReferenceCountUtil.release(data);
                    }
                }
                var ticks = new ArrayList<Integer>();
                Object data;
                while ((data = receiver.readInbound()) != null) {
                    var bytes = (ByteBuf) dataType.getMethod("createData").invoke(data);
                    try {
                        ticks.add(bytes.skipBytes(1).readInt());
                    } finally {
                        bytes.release();
                        ReferenceCountUtil.release(data);
                    }
                }
                assertEquals(List.of(100, 101, 102), ticks);
            } finally {
                receiver.finishAndReleaseAll();
            }
        }
    }

    private static byte[] normalizedClass(byte[] bytes) {
        // Fabric remapping rebuilds the constant pool; debug tables do not affect transport order.
        var writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(bytes)
                .accept(
                        writer,
                        org.objectweb.asm.ClassReader.SKIP_DEBUG
                                | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    @Test
    void productionVelocityUsesIdenticalCommonSwitchAndOrderingCode() throws Exception {
        assertEquals(
                "87fc0ca3ac5614c77f657c2c1337e589438f4d1bf4a8e90523409ea15d6465bd",
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                java.nio.file.Files.readAllBytes(
                                                        Path.of(
                                                                System.getProperty(
                                                                        "blockfield.test.raknetifyVelocityJar"))))));
        try (var fabric = new ZipFile(System.getProperty("blockfield.test.raknetifyJar"));
                var velocity =
                        new ZipFile(System.getProperty("blockfield.test.raknetifyVelocityJar"))) {
            for (String name :
                    List.of(
                            "com/ishland/raknetify/common/connection/RakNetSimpleMultiChannelCodec.class",
                            "network/ycc/raknet/pipeline/FrameOrderIn.class",
                            "network/ycc/raknet/pipeline/FrameOrderIn$OrderedChannelPacketQueue.class",
                            "network/ycc/raknet/pipeline/ReliabilityHandler.class")) {
                assertArrayEquals(
                        normalizedClass(
                                fabric.getInputStream(fabric.getEntry(name)).readAllBytes()),
                        normalizedClass(
                                velocity.getInputStream(velocity.getEntry(name)).readAllBytes()),
                        name);
            }
        }
    }
}
