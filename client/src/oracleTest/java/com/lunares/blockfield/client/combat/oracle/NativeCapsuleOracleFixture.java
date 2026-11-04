package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationTypes.NativeFamily;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.network.message.event.ServerMessageGunShoot;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U8b native broadcast carriage (ADR4 §2.2) in production Knot: a capsule delivery carrying the
 * server-encoded TaCZ {@code ServerMessageGunShoot} / LR {@code SMeleeAnimationSync} bytes runs the
 * pinned native client body exactly once in the capsule task (real stream codecs and private
 * handler bodies), a duplicate delivery of the same event id runs nothing, the journal accepts the
 * carrier, and an unknown carrier fails closed.
 */
class NativeCapsuleOracleFixture {
    private static final int ENTITY = 610;
    private static final long MS = 1_000_000;
    private static final AtomicInteger SHOOTS = new AtomicInteger();

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SceneTimingOracleFixture.bootstrap();
        // Counts the native CLIENT post of our shooter (registered after TaCZ's own listeners).
        GunShootEvent.CALLBACK.register(
                event -> {
                    if (event.getLogicalSide().isClient()
                            && event.getShooter().getId() == ENTITY
                            && event.getGunItemStack().is(Items.STICK)) SHOOTS.incrementAndGet();
                });
    }

    @Test
    void taczShootPayloadRunsItsNativeBodyOnceAndInstallsTheCapsule() {
        AnimSyncOracleRunner.run(
                "tacz",
                "u8b-native-payload-dispatch-once",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        var level = (ClientLevel) world.driven.level();
                        ExactSceneOracleFixture.prime(world, 5_060 * MS);
                        doReturn(world.driven).when(level).getEntity(ENTITY);
                        var shoot = new ServerMessageGunShoot(ENTITY, new ItemStack(Items.STICK));
                        byte[] bytes = encode(ServerMessageGunShoot.STREAM_CODEC, shoot);
                        var event =
                                carried(
                                        world.capsule(102, 102 * 256 + 1, 0, 5_060 * MS),
                                        ServerMessageGunShoot.TYPE.id(),
                                        bytes);
                        int before = SHOOTS.get();
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), 5_061 * MS);
                        assertEquals(before + 1, SHOOTS.get(), "the native body ran once");
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), 5_062 * MS);
                        assertEquals(before + 1, SHOOTS.get(), "a duplicate runs nothing");
                        // The journal accepts the carrier and installs the capsule's start.
                        assertTrue(world.apply(event, 5_063 * MS));
                        assertNull(world.present(world.stamp(new Time(101, 0x8000), 5_064 * MS)));
                        assertTrue(
                                world.journal.presentation(ENTITY).starts().stream()
                                        .anyMatch(s -> s.eventSequence() == 102 * 256 + 1),
                                "installed in its application frame");
                        // An unknown carrier is not delivered: fail closed, never guessed.
                        var unknown =
                                carried(
                                        world.capsule(
                                                103, 103 * 256 + 1, 102 * 256 + 1, 5_110 * MS),
                                        ResourceLocation.parse("tacz:unknown_payload"),
                                        bytes);
                        assertFalse(world.apply(unknown, 5_111 * MS));
                        assertEquals(
                                "native_payload_unavailable", world.journal.unavailable(ENTITY));
                        OracleTransformAudit.requireConfig("tacz.mixins.json");
                        var out = new LinkedHashMap<String, Object>();
                        out.put("payloadBytes", bytes.length);
                        out.put("type", ServerMessageGunShoot.TYPE.id().toString());
                        out.put("nativeRuns", SHOOTS.get() - before);
                        return out;
                    } finally {
                        world.close();
                        ClientCombatScene.disconnect();
                    }
                });
    }

    @Test
    void lrMeleePayloadRunsItsNativeBodyOnce() {
        AnimSyncOracleRunner.run(
                "lr",
                "u8b-native-payload-dispatch-once",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        var level = (ClientLevel) world.driven.level();
                        ExactSceneOracleFixture.prime(world, 5_060 * MS);
                        Class<?> sync =
                                Class.forName(
                                        "me.xjqsh.lrtactical.network.message.SMeleeAnimationSync");
                        Class<?> actionType =
                                Class.forName("me.xjqsh.lrtactical.api.melee.MeleeAction");
                        Object action = actionType.getEnumConstants()[0];
                        var animation = ResourceLocation.parse("lrtactical:melee/baseball_bat");
                        Object message =
                                sync.getConstructor(
                                                int.class,
                                                actionType,
                                                int.class,
                                                ResourceLocation.class)
                                        .newInstance(ENTITY, action, 2, animation);
                        @SuppressWarnings("unchecked")
                        var codec =
                                (StreamCodec<RegistryFriendlyByteBuf, Object>)
                                        sync.getField("STREAM_CODEC").get(null);
                        byte[] bytes = encode(codec, message);
                        var id =
                                ((net.minecraft.network.protocol.common.custom.CustomPacketPayload
                                                                .Type<
                                                        ?>)
                                                sync.getField("TYPE").get(null))
                                        .id();
                        var event =
                                carried(
                                        withFamily(
                                                world.capsule(102, 102 * 256 + 1, 0, 5_060 * MS),
                                                NativeFamily.LR),
                                        id,
                                        bytes);
                        Class<?> pre =
                                Class.forName("me.xjqsh.lrtactical.api.event.MeleePreAttackEvent");
                        var arguments = new ArrayList<List<?>>();
                        try (var constructed =
                                mockConstruction(
                                        pre,
                                        (mock, context) ->
                                                arguments.add(List.copyOf(context.arguments())))) {
                            ClientCombatScene.capsules(
                                    new AnimationEventS2CPacket(List.of(event)), 5_061 * MS);
                            ClientCombatScene.capsules(
                                    new AnimationEventS2CPacket(List.of(event)), 5_062 * MS);
                            assertEquals(1, constructed.constructed().size(), "native body once");
                            // The native body posts the event it built (verified through the mock's
                            // own method).
                            Object verifier =
                                    verify(constructed.constructed().getFirst(), times(1));
                            pre.getMethod("post").invoke(verifier);
                        }
                        assertEquals(List.of(ENTITY, action, 2, animation), arguments.getFirst());
                        assertTrue(world.apply(event, 5_063 * MS), "the journal accepts it");
                        var out = new LinkedHashMap<String, Object>();
                        out.put("payloadBytes", bytes.length);
                        out.put("type", id.toString());
                        out.put("nativeArguments", arguments.getFirst().toString());
                        return out;
                    } finally {
                        world.close();
                        ClientCombatScene.disconnect();
                    }
                });
    }

    @Test
    void missingNativeAddRetainsTheCompleteCarrierUntilValidApplication() {
        AnimSyncOracleRunner.run(
                "tacz",
                "fx-client-carrier-missing-add",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        long now = 5_060 * MS;
                        ExactSceneOracleFixture.prime(world, now, false);
                        var event =
                                carried(
                                        world.capsule(112, 112 * 256 + 1, 0, now),
                                        ServerMessageGunShoot.TYPE.id(),
                                        encode(
                                                ServerMessageGunShoot.STREAM_CODEC,
                                                new ServerMessageGunShoot(
                                                        ENTITY, new ItemStack(Items.STICK))));
                        int before = SHOOTS.get();
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), now + MS);
                        ClientCombatScene.frameEntry(now + 2 * MS);
                        assertEquals(
                                before,
                                SHOOTS.get(),
                                "no native target, no side effect or dedupe commit");
                        var level = (ClientLevel) world.driven.level();
                        doReturn(world.driven).when(level).getEntity(ENTITY);
                        ClientCombatScene.add(world.driven);
                        ClientCombatScene.frameEntry(now + 3 * MS);
                        assertEquals(
                                before + 1,
                                SHOOTS.get(),
                                "the retained payload really delivers after Add");
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), now + 4 * MS);
                        assertEquals(before + 1, SHOOTS.get());
                        OracleTransformAudit.requireConfig("tacz.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return java.util.Map.of("beforeAdd", 0, "afterAdd", SHOOTS.get() - before);
                    } finally {
                        ClientCombatScene.disconnect();
                        world.close();
                    }
                });
    }

    @Test
    void failedNativeDeliveryDoesNotCommitTheDispatchDedupe() {
        AnimSyncOracleRunner.run(
                "tacz",
                "fx-client-carrier-retry",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        long now = 5_060 * MS;
                        ExactSceneOracleFixture.prime(world, now);
                        var event =
                                carried(
                                        world.capsule(112, 112 * 256 + 1, 0, now),
                                        ServerMessageGunShoot.TYPE.id(),
                                        encode(
                                                ServerMessageGunShoot.STREAM_CODEC,
                                                new ServerMessageGunShoot(
                                                        ENTITY, new ItemStack(Items.STICK))));
                        int before = SHOOTS.get();
                        var minecraft = Minecraft.getInstance();
                        doReturn(null).when(minecraft).getConnection();
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), now + MS);
                        assertEquals(
                                before, SHOOTS.get(), "delivery lacks native codec registries");
                        attach((ClientLevel) world.driven.level());
                        ClientCombatScene.frameEntry(now + 2 * MS);
                        assertEquals(
                                before + 1,
                                SHOOTS.get(),
                                "retry was not deduplicated before delivery");
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), now + 3 * MS);
                        assertEquals(
                                before + 1, SHOOTS.get(), "successful delivery is deduplicated");
                        OracleTransformAudit.requireConfig("tacz.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return java.util.Map.of(
                                "failedDeliveryRuns",
                                0,
                                "successfulNativeRuns",
                                SHOOTS.get() - before);
                    } finally {
                        ClientCombatScene.disconnect();
                        world.close();
                    }
                });
    }

    @Test
    void oldLifeCarrierCannotDispatchAgainstASameIdReplacement() {
        AnimSyncOracleRunner.run(
                "tacz",
                "fx-client-carrier-respawn",
                () -> {
                    ClientCombatScene.disconnect();
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        long now = 5_060 * MS;
                        ExactSceneOracleFixture.prime(world, now);
                        var old = world.identity;
                        var identity =
                                new com.lunares.blockfield.common.combat.AnimationState.Identity(
                                        old.connectionId(),
                                        old.observerStreamEpoch(),
                                        old.sceneGeneration(),
                                        old.entityId(),
                                        old.kind(),
                                        old.subscriptionGeneration() + 1,
                                        old.targetEpoch() + 1,
                                        old.seatGeneration(),
                                        old.teleportGeneration(),
                                        old.resourceGeneration(),
                                        old.policyGeneration(),
                                        old.compatibilityDigest(),
                                        old.manifestDigest());
                        var state = world.frames.get(112L).checkpoint();
                        var checkpoint =
                                new com.lunares.blockfield.common.combat.AnimationState(
                                        identity,
                                        state.serverTick(),
                                        state.sampleRevision(),
                                        state.eventWatermark(),
                                        state.placement(),
                                        state.vanilla(),
                                        state.stance(),
                                        state.equipment(),
                                        state.tacz(),
                                        state.lr(),
                                        state.graph(),
                                        state.parcool(),
                                        state.create(),
                                        state.sbw(),
                                        state.decisions());
                        var seed =
                                new AnimationEvent.ObserverSeed(
                                        identity,
                                        new AnimationResources.SeedRef(
                                                0,
                                                identity.targetEpoch(),
                                                112 * 256 + 1,
                                                new AnimationResources.Digest(112, 1, 2, 3)),
                                        null,
                                        checkpoint,
                                        checkpoint.graph(),
                                        112,
                                        checkpoint.eventWatermark(),
                                        checkpoint.eventWatermark(),
                                        List.of(),
                                        List.of(),
                                        com.lunares.blockfield.common.combat.ObserverReducer
                                                .DIGEST);
                        var refs =
                                new java.util.HashSet<>(
                                        com.lunares.blockfield.common.combat.AnimationSchema
                                                .references(seed));
                        var definitions =
                                refs.stream()
                                        .map(
                                                ref ->
                                                        ClientCombatScene.retainedDefinition(ref)
                                                                        != null
                                                                ? ClientCombatScene
                                                                        .retainedDefinition(ref)
                                                                : new AnimationResources.Definition(
                                                                        ref,
                                                                        new AnimationResources
                                                                                .Bytes(new byte[0]),
                                                                        List.of()))
                                        .toList();
                        ClientCombatScene.accept(
                                com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket
                                        .animation(
                                                17,
                                                112,
                                                112,
                                                2,
                                                6,
                                                10,
                                                500,
                                                new com.lunares.blockfield.common.combat
                                                        .AnimationRecipe.Batch(
                                                        3,
                                                        1,
                                                        List.of(
                                                                new com.lunares.blockfield.common
                                                                        .combat.AnimationRecipe
                                                                        .SnapshotRecord(
                                                                        identity,
                                                                        9,
                                                                        checkpoint.placement(),
                                                                        checkpoint)),
                                                        definitions,
                                                        List.of(seed))),
                                (now + MS) / (double) MS);
                        assertEquals(
                                112L,
                                ClientCombatScene.debugSnapshot().get("receivedHigh"),
                                "new life snapshot applied before testing native pairing");
                        assertEquals(
                                0L,
                                ClientCombatScene.debugSnapshot().get("protocolDefects"),
                                "pairing test requires a valid stream");
                        var event =
                                carried(
                                        world.capsule(112, 112 * 256 + 2, 0, now),
                                        ServerMessageGunShoot.TYPE.id(),
                                        encode(
                                                ServerMessageGunShoot.STREAM_CODEC,
                                                new ServerMessageGunShoot(
                                                        ENTITY, new ItemStack(Items.STICK))));
                        int before = SHOOTS.get();
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), now + 2 * MS);
                        assertEquals(
                                before,
                                SHOOTS.get(),
                                "new snapshot cannot pair an old native object");
                        ClientCombatScene.remove(ENTITY);
                        var replacement = SceneTimingOracleFixture.player(ENTITY);
                        ExactSceneOracleFixture.sceneEntity(replacement);
                        var level = (ClientLevel) world.driven.level();
                        doReturn(level).when(replacement).level();
                        doReturn(replacement).when(level).getEntity(ENTITY);
                        ClientCombatScene.add(replacement);
                        ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), now + 3 * MS);
                        ClientCombatScene.frameEntry(now + 200 * MS);
                        assertEquals(
                                before, SHOOTS.get(), "old-life event never reaches replacement");
                        com.lunares.blockfield.client.combat.DrivenPresentation.forget(replacement);
                        OracleTransformAudit.requireConfig("tacz.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return java.util.Map.of(
                                "oldLifeNativeRuns",
                                SHOOTS.get() - before,
                                "replacementTargetEpoch",
                                identity.targetEpoch());
                    } finally {
                        ClientCombatScene.disconnect();
                        world.close();
                    }
                });
    }

    /** {@code event} carrying a server-encoded LR attack broadcast for {@code entity}. */
    static AnimationEvent lrCarried(AnimationEvent event, int entity) throws Exception {
        Class<?> sync = Class.forName("me.xjqsh.lrtactical.network.message.SMeleeAnimationSync");
        Class<?> actionType = Class.forName("me.xjqsh.lrtactical.api.melee.MeleeAction");
        Object message =
                sync.getConstructor(int.class, actionType, int.class, ResourceLocation.class)
                        .newInstance(
                                entity,
                                actionType.getEnumConstants()[0],
                                2,
                                ResourceLocation.parse("lrtactical:melee/baseball_bat"));
        @SuppressWarnings("unchecked")
        var codec =
                (StreamCodec<RegistryFriendlyByteBuf, Object>)
                        sync.getField("STREAM_CODEC").get(null);
        var id =
                ((net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<?>)
                                sync.getField("TYPE").get(null))
                        .id();
        return carried(event, id, encode(codec, message));
    }

    /** The client the native bodies read: its level and connection registries, a local player. */
    static void attach(ClientLevel level) throws Exception {
        var minecraft = Minecraft.getInstance();
        var levelField =
                OracleReflection.field(
                        Minecraft.class, "level", "Lnet/minecraft/client/multiplayer/ClientLevel;");
        levelField.setAccessible(true);
        levelField.set(minecraft, level);
        var local = mock(LocalPlayer.class);
        doReturn(1).when(local).getId();
        var playerField =
                OracleReflection.field(
                        Minecraft.class, "player", "Lnet/minecraft/client/player/LocalPlayer;");
        playerField.setAccessible(true);
        playerField.set(minecraft, local);
        var connection = mock(ClientPacketListener.class);
        doReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
                .when(connection)
                .registryAccess();
        doReturn(connection).when(minecraft).getConnection();
    }

    private static <T> byte[] encode(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        var buf =
                new RegistryFriendlyByteBuf(
                        Unpooled.buffer(),
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            codec.encode(buf, value);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** The server delivery's shape: the carrier definition and the payload bytes (U8b). */
    private static AnimationEvent carried(
            AnimationEvent event, ResourceLocation type, byte[] bytes) {
        byte[] marker = ("native.payload:" + type).getBytes(StandardCharsets.UTF_8);
        var hash = AnimationResources.Digest.hash(marker);
        var ref = new AnimationResources.DefinitionRef(0, 0, hash.a() & Long.MAX_VALUE, hash);
        var dependencies = new ArrayList<AnimationResources.Definition>();
        for (var dependency : event.dependencies())
            if (!dependency.ref().equals(event.nativePayload().payloadType()))
                dependencies.add(dependency);
        dependencies.add(
                new AnimationResources.Definition(
                        ref, new AnimationResources.Bytes(marker), List.of()));
        return new AnimationEvent(
                event.identity(),
                event.id(),
                event.predecessorEventSequence(),
                event.family(),
                event.kind(),
                event.broadcastStage(),
                event.acceptedNs(),
                event.broadcastNs(),
                event.seed(),
                event.patch(),
                dependencies,
                new AnimationEvent.NativePayload(ref, new AnimationResources.Bytes(bytes)));
    }

    private static AnimationEvent withFamily(AnimationEvent event, NativeFamily family) {
        return new AnimationEvent(
                event.identity(),
                event.id(),
                event.predecessorEventSequence(),
                family,
                com.lunares.blockfield.common.combat.AnimationTypes.EventKind.LR_ATTACK,
                event.broadcastStage(),
                event.acceptedNs(),
                event.broadcastNs(),
                event.seed(),
                event.patch(),
                event.dependencies(),
                event.nativePayload());
    }
}
