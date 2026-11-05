package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.combat.ClientAnimationAssets;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.CombatAttackSender;
import com.lunares.blockfield.client.combat.CombatViewSender;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.AnimationWire;
import com.lunares.blockfield.common.combat.CombatView;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.TaczAssets;
import com.lunares.blockfield.network.packet.AnimationAppliedC2SPacket;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.AnimationProbeC2SPacket;
import com.lunares.blockfield.network.packet.AnimationProbeS2CPacket;
import com.lunares.blockfield.network.packet.AnimationWireC2SPacket;
import com.lunares.blockfield.network.packet.CombatViewC2SPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * FX-CLIENT production wire, client half. The S2C vector is what the production server stream sent
 * (recorded and re-verified by the server's {@code FxClientWireTest}): a cold join with a transport
 * stall, the reseed after the first completed probes, a Create enter held until that seed activates
 * and an exit. The production scene consumes those exact bytes with no added definitions, so the
 * seed and its closure resolve only from what the stream delivered. Its C2S packets (echoes,
 * AnimationApplied, two tickets) are the client section the server test then validates with the
 * real {@code AnimSyncValidation}.
 */
class FxClientWireOracleFixture {
    static final long MS = 1_000_000;
    static final long FRAME = 1_000_000_000L / 60;
    static final AnimationPolicy POLICY =
            new AnimationPolicy(
                    0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    // Relative to client/build/anim-sync-oracle/run.
    static final Path VECTOR =
            Path.of("..", "..", "..", "..", "docs", "review", "anim-sync-fx-client-wire.json");
    static final Path ACTUAL =
            Path.of("..", "..", "fx-client-wire", "anim-sync-fx-client-wire.json");

    @BeforeAll
    static void bootstrap() throws Exception {
        SceneTimingOracleFixture.bootstrap();
    }

    static JsonObject vector() throws Exception {
        // Committed next to the code it validates; a workspace-relative lookup would
        // only resolve inside a workspace checkout. Client-C2S equality below fails
        // loudly if this copy ever drifts from the recorded stream (see "regenerate").
        if (!Files.isRegularFile(VECTOR))
            throw new AssertionError(
                    "committed oracle vector missing: "
                            + VECTOR.toAbsolutePath()
                            + " (restore docs/review/anim-sync-fx-client-wire.json)");
        return JsonParser.parseString(Files.readString(VECTOR)).getAsJsonObject();
    }

    /** The S2C stream played into the production scene at its client arrival times. */
    static final class Replay implements AutoCloseable {
        final SceneTimingOracleFixture.World world;
        final JsonObject server;
        final List<JsonObject> sent = new ArrayList<>();
        final org.mockito.MockedStatic<ClientPlayNetworking> network;
        final AnimationState.Identity identity;
        final long offset;
        long now;
        BiConsumer<CustomPacketPayload, Long> intake = Replay::apply;
        int tickets;
        // Highest probe id applied by now.
        long probe = -1;
        // Production C2S reassembly: the scene sends APPLIED reports as anim_wire_c2s
        // fragments through the connection duck; the canonical bodies are recorded.
        final AnimationWire.Assembler assembler = new AnimationWire.Assembler(false);

        Replay(JsonObject server) throws Exception {
            this.server = server;
            offset = server.get("clientOffsetNs").getAsLong();
            ClientCombatScene.disconnect();
            world = new SceneTimingOracleFixture.World();
            var level = (net.minecraft.client.multiplayer.ClientLevel) world.driven.level();
            NativeCapsuleOracleFixture.attach(level);
            ExactSceneOracleFixture.sceneEntity(world.driven);
            int entity = server.get("entity").getAsInt();
            assertEquals(entity, world.driven.getId());
            doReturn(world.driven).when(level).getEntity(entity);
            ClientCombatScene.add(world.driven);
            assertEquals(POLICY.toString(), server.get("policy").getAsString());
            identity =
                    snapshot(server.getAsJsonArray("packets").get(1).getAsJsonObject())
                            .animation()
                            .records()
                            .getFirst()
                            .identity();
            long generation = server.get("generation").getAsLong();
            assertEquals(generation, identity.resourceGeneration());
            // The READY generation (U9): this pack resolves no TaCZ/LR display for these players.
            var catalog = new PaGraph.Catalog(generation);
            ClientAnimationAssets.install(
                    new ClientAnimationAssets.Generation(
                            generation,
                            POLICY,
                            new TaczAssets(catalog, Map.of()),
                            new LrAssets(catalog)),
                    identity.compatibilityDigest(),
                    identity.manifestDigest());
            network = mockStatic(ClientPlayNetworking.class);
            network.when(() -> ClientPlayNetworking.send(any(CustomPacketPayload.class)))
                    .thenAnswer(
                            call -> {
                                if (call.getArgument(0)
                                        instanceof AnimationAppliedC2SPacket applied)
                                    record(
                                            "applied",
                                            encode(
                                                    AnimationAppliedC2SPacket.CODEC::encode,
                                                    applied));
                                else fail("unexpected C2S " + call.getArgument(0));
                                return null;
                            });
            // The production scene sends APPLIED reports through the connection's
            // adjacency-monitored duck, not ClientPlayNetworking: expose the real
            // transformed send path and reassemble its fragments with the production
            // assembler/decoder before recording the canonical body.
            var listener = mock(ClientPacketListener.class, CALLS_REAL_METHODS);
            var socket = mock(Connection.class);
            var connection =
                    OracleReflection.field(
                            ClientCommonPacketListenerImpl.class,
                            "connection",
                            "Lnet/minecraft/network/Connection;");
            connection.setAccessible(true);
            connection.set(listener, socket);
            var combat = ClientCommonPacketListenerImpl.class.getDeclaredField("blockfield$combat");
            combat.setAccessible(true);
            combat.set(listener, new CombatAttackSender());
            assertNotNull(((CombatViewSender) listener).blockfield$combatSender());
            doAnswer(
                            call -> {
                                onWire((Packet<?>) call.getArgument(0));
                                return null;
                            })
                    .when(socket)
                    .send(any(Packet.class));
            var minecraft = Minecraft.getInstance();
            doReturn(listener).when(minecraft).getConnection();
        }

        void onWire(Packet<?> packet) {
            // reportApplications swallows RuntimeException: surface wire defects as
            // AssertionError so a broken capture fails loudly instead of vanishing.
            try {
                if (packet instanceof ServerboundCustomPayloadPacket custom
                        && custom.payload() instanceof AnimationWireC2SPacket wire) {
                    var transaction = assembler.accept(wire.fragment());
                    if (transaction == null) return;
                    var decoded = AnimationWireC2SPacket.decode(transaction);
                    if (decoded instanceof AnimationAppliedC2SPacket applied)
                        record("applied", encode(AnimationAppliedC2SPacket.CODEC::encode, applied));
                    else if (decoded instanceof CombatViewC2SPacket ticket) {
                        record(
                                "ticket",
                                encode(CombatViewC2SPacket.ANIMATION_CODEC::encode, ticket));
                        tickets++;
                    } else fail("unexpected C2S wire payload " + decoded);
                    return;
                }
                fail("unexpected connection packet " + packet);
            } catch (AssertionError fatal) {
                throw fatal;
            } catch (RuntimeException defect) {
                throw new AssertionError("wire reassembly failed", defect);
            }
        }

        void record(String type, byte[] bytes) {
            var packet = new JsonObject();
            packet.addProperty("type", type);
            packet.addProperty("clientNs", now);
            packet.addProperty("hex", HexFormat.of().formatHex(bytes));
            sent.add(packet);
        }

        /**
         * Plays every S2C packet (with {@code rewrite} applied to snapshots) and 60 FPS frames
         * until {@code untilNs} on the client clock; {@code frame} runs after each frame.
         */
        void play(
                UnaryOperator<PlayerSnapshotS2CPacket> rewrite,
                long untilNs,
                java.util.function.LongConsumer frame) {
            var packets = new ArrayList<JsonObject>();
            server.getAsJsonArray("packets").forEach(p -> packets.add(p.getAsJsonObject()));
            int next = 0;
            long frameAt = packets.getFirst().get("arrivalNs").getAsLong() + offset;
            while (frameAt <= untilNs) {
                while (next < packets.size()
                        && packets.get(next).get("arrivalNs").getAsLong() + offset <= frameAt) {
                    var packet = packets.get(next++);
                    now = packet.get("arrivalNs").getAsLong() + offset;
                    var bytes = HexFormat.of().parseHex(packet.get("hex").getAsString());
                    switch (packet.get("type").getAsString()) {
                        case "snapshot" -> intake.accept(rewrite.apply(snapshot(packet)), now);
                        case "events" ->
                                intake.accept(
                                        decode(AnimationEventS2CPacket.CODEC::decode, bytes), now);
                        case "probe" -> {
                            var probe = decode(AnimationProbeS2CPacket.CODEC::decode, bytes);
                            ClientCombatScene.probe(probe, now);
                            // The production receiver echoes the nonce right away.
                            var echo = new AnimationProbeC2SPacket(probe.id());
                            record("echo", encode(AnimationProbeC2SPacket.CODEC::encode, echo));
                            sent.getLast().addProperty("id", probe.id());
                            this.probe = probe.id();
                        }
                        default -> fail("unknown S2C " + packet);
                    }
                }
                now = frameAt;
                ClientCombatScene.frameEntry(now);
                frame.accept(now);
                ClientCombatScene.frameEnd();
                frameAt += FRAME;
            }
        }

        static void apply(CustomPacketPayload payload, long now) {
            if (payload instanceof PlayerSnapshotS2CPacket snapshot)
                ClientCombatScene.accept(snapshot, now / 1_000_000.0);
            else ClientCombatScene.capsules((AnimationEventS2CPacket) payload, now);
        }

        /** A ticket of the current receipt, recorded as sent. */
        CombatView ticket() {
            var view = ClientCombatScene.nextTicket(now / 1_000_000.0);
            assertNotNull(view, String.valueOf(ClientCombatScene.debugSnapshot()));
            record(
                    "ticket",
                    encode(
                            CombatViewC2SPacket.ANIMATION_CODEC::encode,
                            new CombatViewC2SPacket(view)));
            tickets++;
            return view;
        }

        PlayerSnapshotS2CPacket lastSnapshot() {
            PlayerSnapshotS2CPacket last = null;
            for (var packet : server.getAsJsonArray("packets"))
                if (packet.getAsJsonObject().get("type").getAsString().equals("snapshot"))
                    last = snapshot(packet.getAsJsonObject());
            return last;
        }

        long end() {
            var packets = server.getAsJsonArray("packets");
            return packets.get(packets.size() - 1).getAsJsonObject().get("arrivalNs").getAsLong()
                    + offset;
        }

        JsonObject client() {
            var out = new JsonObject();
            var list = new JsonArray();
            sent.forEach(list::add);
            out.add("packets", list);
            out.addProperty("tickets", tickets);
            return out;
        }

        @Override
        public void close() {
            network.close();
            ClientCombatScene.disconnect();
            world.close();
        }
    }

    static PlayerSnapshotS2CPacket snapshot(JsonObject packet) {
        return decode(
                PlayerSnapshotS2CPacket.ANIMATION_CODEC::decode,
                HexFormat.of().parseHex(packet.get("hex").getAsString()));
    }

    static <T> byte[] encode(BiConsumer<FriendlyByteBuf, T> codec, T value) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.accept(buf, value);
            var bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    static <T> T decode(Function<FriendlyByteBuf, T> codec, byte[] bytes) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            T value = codec.apply(buf);
            assertEquals(0, buf.readableBytes(), "trailing bytes");
            return value;
        } finally {
            buf.release();
        }
    }

    private static AnimationStartReport.Victim row(CombatView view) {
        var victims = view.animation().starts().victims();
        assertEquals(1, victims.size(), "the one subscribed remote");
        return victims.getFirst();
    }

    @Test
    void theProductionStreamResolvesAndReportsWithoutAddedDefinitions() {
        AnimSyncOracleRunner.run(
                "create",
                "fx-client-production-wire",
                () -> {
                    var vector = vector();
                    try (var replay = new Replay(vector.getAsJsonObject("server"))) {
                        var tickets = new ArrayList<CombatView>();
                        var trace = new ArrayList<String>();
                        long[] taken = {0};
                        replay.play(
                                UnaryOperator.identity(),
                                replay.end() + 4 * FRAME,
                                now -> {
                                    var d = ClientCombatScene.debugSnapshot();
                                    trace.add(
                                            (now - replay.offset) / 1_000_000
                                                    + " H="
                                                    + d.get("H")
                                                    + " A="
                                                    + d.get("A")
                                                    + " R="
                                                    + d.get("R")
                                                    + " "
                                                    + d.get("animationReason")
                                                    + " rep="
                                                    + d.get("reportUnavailable")
                                                    + " pres="
                                                    + d.get("presentationUnavailable")
                                                    + " j="
                                                    + d.get("journal"));
                                    var receipt = ClientCombatScene.receipt();
                                    var report =
                                            receipt == null || receipt.exact() == null
                                                    ? null
                                                    : receipt.exact().report();
                                    if (report == null) return;
                                    var row = report.victims().getFirst();
                                    boolean held =
                                            row.starts().stream()
                                                    .anyMatch(
                                                            s ->
                                                                    s.layerTag()
                                                                            == LayerTag
                                                                                    .CREATE_HANGING);
                                    // The first exact hanging/exited frames (their proofs are
                                    // first reported in that drain), then the next frame's.
                                    boolean exited = !held && row.applications().size() == 2;
                                    int phase = tickets.size();
                                    if (phase == 0 && held
                                            || phase == 1 && held && now > taken[0]
                                            || phase == 2 && exited
                                            || phase == 3 && exited && now > taken[0]) {
                                        taken[0] = now;
                                        tickets.add(replay.ticket());
                                    }
                                });
                        assertEquals(4, tickets.size(), () -> String.join("\n", trace));
                        assertTrue(DrivenPresentation.driven(replay.world.driven));
                        var hanging = row(tickets.get(1));
                        var exited = row(tickets.getLast());
                        // Cold join: the first seed has no send-time probe evidence; the tickets
                        // name the seed the server reissued after the first completed probes.
                        var seeds = new ArrayList<Long>();
                        for (var p : vector.getAsJsonObject("server").getAsJsonArray("packets"))
                            if (p.getAsJsonObject().get("type").getAsString().equals("snapshot"))
                                for (var seed : snapshot(p.getAsJsonObject()).animation().seeds())
                                    seeds.add(seed.ref().contentId());
                        assertEquals(2, seeds.size(), "cold seed and its timing reseed");
                        assertEquals(seeds.getLast(), hanging.seedRef());
                        assertEquals(seeds.getLast(), exited.seedRef());
                        var enter = exited.applications().getFirst();
                        var exit = exited.applications().getLast();
                        var client = replay.client();
                        var merged = new JsonObject();
                        merged.add("regenerate", vector.get("regenerate"));
                        merged.add("server", vector.get("server"));
                        merged.add("client", client);
                        Files.createDirectories(ACTUAL.getParent());
                        Files.writeString(
                                ACTUAL,
                                new GsonBuilder()
                                                .setPrettyPrinting()
                                                .serializeNulls()
                                                .create()
                                                .toJson(merged)
                                        + "\n");
                        assertEquals(
                                vector.get("client"),
                                client,
                                "The client's C2S changed: regenerate the checked-in vector ("
                                        + vector.get("regenerate").getAsString()
                                        + ")");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                        var out = new LinkedHashMap<String, Object>();
                        out.put(
                                "s2cPackets",
                                vector.getAsJsonObject("server").getAsJsonArray("packets").size());
                        out.put("c2sPackets", client.getAsJsonArray("packets").size());
                        out.put("seedRef", exited.seedRef());
                        out.put("enterMapping", enter.proof().mappingId());
                        out.put("exitMapping", exit.proof().mappingId());
                        out.put("hangingStarts", hanging.starts().size());
                        out.put("exitedStarts", exited.starts().size());
                        out.put("scene", String.valueOf(ClientCombatScene.debugSnapshot()));
                        return out;
                    }
                });
    }

    /** The production batch minus every SeedRef definition the stream delivered. */
    static PlayerSnapshotS2CPacket withoutSeedDefinitions(PlayerSnapshotS2CPacket packet) {
        var batch = packet.animation();
        var definitions =
                batch.definitions().stream()
                        .filter(d -> !(d.ref() instanceof AnimationResources.SeedRef))
                        .toList();
        return PlayerSnapshotS2CPacket.animation(
                packet.observerStreamEpoch(),
                packet.previousTickPlusOne(),
                packet.serverTick(),
                packet.interpMin(),
                packet.interpMax(),
                packet.dilationPercent(),
                packet.holdToSnapMs(),
                new AnimationRecipe.Batch(
                        batch.connectionId(),
                        batch.sceneGeneration(),
                        batch.records(),
                        definitions,
                        batch.seeds()));
    }
}
