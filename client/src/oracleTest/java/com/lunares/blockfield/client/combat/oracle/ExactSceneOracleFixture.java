package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.animation.RemoteLrCompat;
import com.lunares.blockfield.client.combat.*;
import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.network.packet.*;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Complete scene/ticket boundary using real transformed PA registration and install adapters. */
class ExactSceneOracleFixture {
    @BeforeAll
    static void bootstrap() throws Exception {
        SceneTimingOracleFixture.bootstrap();
    }

    /** Native Add and complete server-shaped snapshots, through the production scene intake. */
    static void prime(SceneTimingOracleFixture.World world, long nowNs) throws Exception {
        prime(world, nowNs, true);
    }

    /**
     * The fixture-built worlds of the native carrier and timing fixtures. Their seeds are not
     * server-issued, so their equipment/input/seed blobs get empty definitions here; the production
     * closure delivery is proven on the recorded production stream ({@link
     * FxClientWireOracleFixture}).
     */
    static void prime(SceneTimingOracleFixture.World world, long nowNs, boolean add)
            throws Exception {
        var level = (net.minecraft.client.multiplayer.ClientLevel) world.driven.level();
        NativeCapsuleOracleFixture.attach(level);
        sceneEntity(world.driven);
        int entityId = world.driven.getId();
        doReturn(add ? world.driven : null).when(level).getEntity(entityId);
        if (add) ClientCombatScene.add(world.driven);
        for (long tick = 100; tick <= 111; tick++) {
            long at = nowNs - (111 - tick) * 50_000_000L;
            var state = world.frames.get(tick).checkpoint();
            var seeds = tick == 100 ? List.of(world.seed) : List.<AnimationEvent.ObserverSeed>of();
            var definitions =
                    new LinkedHashMap<
                            AnimationResources.ResourceRef, AnimationResources.Definition>();
            var mainHand =
                    com.lunares.blockfield.oracle.FxClientCases.definition(state.identity(), null);
            definitions.put(mainHand.ref(), mainHand);
            for (var definition : world.catalog.definitions())
                definitions.put(definition.ref(), definition);
            var refs = new HashSet<>(AnimationSchema.references(state));
            for (var seed : seeds) refs.addAll(AnimationSchema.references(seed));
            // These fixture-only equipment/input/seed blobs except the canonical main hand have no
            // native readers. Their exact
            // refs still pass the common closure checks; clip values use the real catalog above.
            for (var ref : refs) {
                definitions.putIfAbsent(
                        ref,
                        new AnimationResources.Definition(
                                ref, new AnimationResources.Bytes(new byte[0]), List.of()));
            }
            if (tick > 100) definitions.remove(mainHand.ref());
            ClientCombatScene.probe(
                    new AnimationProbeS2CPacket(tick, tick * 50_000_000L, tick, tick * 50_000_000L),
                    at);
            ClientCombatScene.accept(
                    PlayerSnapshotS2CPacket.animation(
                            17,
                            tick == 100 ? 0 : tick,
                            tick,
                            2,
                            6,
                            10,
                            500,
                            new AnimationRecipe.Batch(
                                    3,
                                    1,
                                    List.of(
                                            new AnimationRecipe.SnapshotRecord(
                                                    state.identity(),
                                                    tick == 100 ? 9 : 0,
                                                    state.placement(),
                                                    state)),
                                    List.copyOf(definitions.values()),
                                    seeds)),
                    at / 1_000_000.0);
        }
        ClientCombatScene.frameEntry(nowNs);
        assertNotNull(
                ClientCombatScene.retainedDefinition(
                        world.frames.get(100L).checkpoint().equipment().mainHandRef()),
                "definition received once remains retained after later batches");
    }

    static void sceneEntity(net.minecraft.client.player.AbstractClientPlayer player)
            throws Exception {
        var position =
                com.lunares.blockfield.oracle.OracleReflection.field(
                        Entity.class, "position", "Lnet/minecraft/world/phys/Vec3;");
        position.setAccessible(true);
        position.set(player, net.minecraft.world.phys.Vec3.ZERO);
        // No level construction/physics in this fixture; the scene's position setter updates the
        // actual native input read by old-position capture, while install/render hooks stay real.
        doAnswer(
                        call -> {
                            position.set(
                                    player,
                                    new net.minecraft.world.phys.Vec3(
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2)));
                            return null;
                        })
                .when(player)
                .setPos(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void retainedItemClassLookupRejectsMissingMalformedAndUnknownItems() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "fx-client-retained-main-hand-validation",
                () -> {
                    var world = new SceneTimingOracleFixture.World();
                    try {
                        var state = world.frames.get(100L).checkpoint();
                        doReturn(
                                        net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                                                net.minecraft.core.registries.BuiltInRegistries
                                                        .REGISTRY))
                                .when(world.driven)
                                .registryAccess();
                        var method =
                                Class.forName("com.lunares.blockfield.client.combat.DrivenSbw")
                                        .getDeclaredMethod(
                                                "mainHand",
                                                net.minecraft.client.player.AbstractClientPlayer
                                                        .class,
                                                AnimationState.class,
                                                java.util.function.Function.class);
                        method.setAccessible(true);
                        java.util.function.Function<
                                        AnimationResources.ResourceRef,
                                        AnimationResources.Definition>
                                missing = ref -> null;
                        var error =
                                assertThrows(
                                        java.lang.reflect.InvocationTargetException.class,
                                        () -> method.invoke(null, world.driven, state, missing));
                        assertEquals("sbw_main_hand_unavailable", error.getCause().getMessage());
                        for (String text :
                                List.of(
                                        "",
                                        "{id:\"bad space\"}",
                                        "{id:\"blockfield:unregistered_fixture_item\"}")) {
                            var definition =
                                    new AnimationResources.Definition(
                                            state.equipment().mainHandRef(),
                                            new AnimationResources.Bytes(
                                                    text.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)),
                                            List.of());
                            java.util.function.Function<
                                            AnimationResources.ResourceRef,
                                            AnimationResources.Definition>
                                    lookup = ref -> definition;
                            error =
                                    assertThrows(
                                            java.lang.reflect.InvocationTargetException.class,
                                            () -> method.invoke(null, world.driven, state, lookup));
                            assertInstanceOf(IllegalArgumentException.class, error.getCause());
                        }
                        var empty =
                                com.lunares.blockfield.oracle.FxClientCases.definition(
                                        state.identity(), null);
                        java.util.function.Function<
                                        AnimationResources.ResourceRef,
                                        AnimationResources.Definition>
                                lookup = ref -> empty;
                        assertSame(
                                net.minecraft.world.item.ItemStack.EMPTY,
                                method.invoke(null, world.driven, state, lookup));
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "rejectedMissingOrInvalidItems",
                                4,
                                "nativeEmptyItem",
                                true,
                                "equalitySamples",
                                0);
                    } finally {
                        world.close();
                    }
                });
    }

    @Test
    void exactTicketRequiresEveryRemoteToHaveAnInstalledPresentation() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "fx-client-ticket-install",
                () -> {
                    try (var replay = replay()) {
                        var world = replay.world;
                        long now = replay.now;
                        assertTrue(
                                DrivenPresentation.driven(world.driven),
                                String.valueOf(ClientCombatScene.debugSnapshot()));
                        var good = ClientCombatScene.nextTicket(now / 1_000_000.0);
                        assertNotNull(good, String.valueOf(ClientCombatScene.debugSnapshot()));
                        assertEquals(1, good.animation().starts().victims().size());
                        // Existing compatibility-loss boundary forces the real install to refuse.
                        RemoteLrCompat.detach(world.driven);
                        ClientCombatScene.frameEntry(now + 1_000_000L);
                        assertFalse(DrivenPresentation.driven(world.driven));
                        // Protocol 9: an incomplete report still dates the shot (R without
                        // victim rows) so the server rewinds retained poses to R.
                        var rOnly = ClientCombatScene.nextTicket((now + 1_000_000L) / 1_000_000.0);
                        assertEquals(ClientCombatScene.receipt().time(), rOnly.renderTime(), 1e-9);
                        assertNull(rOnly.animation().starts());
                        assertNull(ClientCombatScene.receipt().exact().report());
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                        return Map.of(
                                "successfulInstallVictims",
                                1,
                                "failedInstallTicket",
                                "unavailable",
                                "stream",
                                "recorded production S2C");
                    }
                });
    }

    @Test
    void excludedCoPassengerInvalidatesTheWholeReport() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "fx-client-ticket-copassenger",
                () -> {
                    try (var replay = replay()) {
                        var world = replay.world;
                        long now = replay.now;
                        assertTrue(DrivenPresentation.driven(world.driven));
                        assertNotNull(ClientCombatScene.nextTicket(now / 1_000_000.0));
                        var vehicle = mock(Entity.class);
                        doReturn(vehicle).when(world.driven).getRootVehicle();
                        var local = Minecraft.getInstance().player;
                        doReturn(vehicle).when(local).getRootVehicle();
                        ClientCombatScene.frameEntry(now + 1_000_000L);
                        assertFalse(DrivenPresentation.driven(world.driven));
                        // Protocol 9: R-only ticket, no victim rows.
                        var rOnly = ClientCombatScene.nextTicket((now + 1_000_000L) / 1_000_000.0);
                        assertEquals(ClientCombatScene.receipt().time(), rOnly.renderTime(), 1e-9);
                        assertNull(rOnly.animation().starts());
                        assertNull(
                                ClientCombatScene.receipt().exact().report(),
                                "the required victim cannot silently disappear from the table");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of("coPassenger", "native", "completeReport", "unavailable");
                    }
                });
    }

    @Test
    void aMissingServerIssuedSeedDefinitionIsExplicitlyUnavailable() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "fx-client-missing-seed-definition",
                () -> {
                    var vector = FxClientWireOracleFixture.vector();
                    try (var replay =
                            new FxClientWireOracleFixture.Replay(
                                    vector.getAsJsonObject("server"))) {
                        // The recorded production stream minus the SeedRef definitions it sent.
                        replay.play(
                                FxClientWireOracleFixture::withoutSeedDefinitions,
                                replay.end() + 4 * FxClientWireOracleFixture.FRAME,
                                at -> {});
                        // Protocol 9: R-only ticket, no victim rows.
                        var rOnly = ClientCombatScene.nextTicket(replay.now / 1_000_000.0);
                        assertEquals(ClientCombatScene.receipt().time(), rOnly.renderTime(), 1e-9);
                        assertNull(rOnly.animation().starts());
                        assertFalse(DrivenPresentation.driven(replay.world.driven));
                        assertTrue(
                                String.valueOf(
                                                ClientCombatScene.debugSnapshot()
                                                        .get("reportUnavailable"))
                                        .contains("Missing animation dependency"),
                                String.valueOf(ClientCombatScene.debugSnapshot()));
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "coverageKind",
                                "expected_unavailability",
                                "equalitySamples",
                                0,
                                "missing",
                                "server-issued SeedRef definitions of the production stream");
                    }
                });
    }

    @Test
    void generationsRetireOnlyAfterBufferedSeedAndReceiptReferencesExpire() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "fx-client-generation-retirement",
                () -> {
                    try (var replay = replay()) {
                        var world = replay.world;
                        long now = replay.now;
                        var base = replay.identity;
                        var identities = new ArrayList<AnimationState.Identity>();
                        identities.add(base);
                        for (long generation = base.resourceGeneration() + 1;
                                generation <= base.resourceGeneration() + 2;
                                generation++) {
                            var catalog = new PaGraph.Catalog(generation);
                            ClientAnimationAssets.install(
                                    new ClientAnimationAssets.Generation(
                                            generation,
                                            FxClientWireOracleFixture.POLICY,
                                            null,
                                            new LrAssets(catalog)),
                                    base.compatibilityDigest(),
                                    base.manifestDigest());
                            identities.add(
                                    new AnimationState.Identity(
                                            base.connectionId(),
                                            base.observerStreamEpoch(),
                                            base.sceneGeneration(),
                                            base.entityId(),
                                            base.kind(),
                                            base.subscriptionGeneration(),
                                            base.targetEpoch(),
                                            base.seatGeneration(),
                                            base.teleportGeneration(),
                                            generation,
                                            base.policyGeneration(),
                                            base.compatibilityDigest(),
                                            base.manifestDigest()));
                        }
                        ClientCombatScene.clientTick(now / 1_000_000.0);
                        assertNotNull(
                                ClientAnimationAssets.get(identities.get(0)),
                                "live old seed/record/receipt");
                        assertNull(
                                ClientAnimationAssets.get(identities.get(1)),
                                "unused intermediate generation");
                        assertNotNull(
                                ClientAnimationAssets.get(identities.get(2)),
                                "latest READY generation");
                        ClientCombatScene.remove(world.driven.getId());
                        var level =
                                (net.minecraft.client.multiplayer.ClientLevel) world.driven.level();
                        int entityId = world.driven.getId();
                        doReturn(null).when(level).getEntity(entityId);
                        var last = replay.lastSnapshot();
                        for (long tick = last.serverTick() + 1;
                                tick <= last.serverTick() + 41;
                                tick++) {
                            long at = now + (tick - last.serverTick()) * 50_000_000L;
                            ClientCombatScene.accept(
                                    PlayerSnapshotS2CPacket.animation(
                                            last.observerStreamEpoch(),
                                            tick,
                                            tick,
                                            last.interpMin(),
                                            last.interpMax(),
                                            last.dilationPercent(),
                                            last.holdToSnapMs(),
                                            new AnimationRecipe.Batch(
                                                    last.animation().connectionId(),
                                                    last.animation().sceneGeneration(),
                                                    List.of(),
                                                    List.of(),
                                                    List.of())),
                                    at / 1_000_000.0);
                            ClientCombatScene.frameEntry(at);
                        }
                        ClientCombatScene.clientTick((now + 3_000_000_000L) / 1_000_000.0);
                        assertNull(
                                ClientAnimationAssets.get(identities.get(0)),
                                "old generation expires within this connection, without clearing all assets");
                        assertNotNull(ClientAnimationAssets.get(identities.get(2)));
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "generations", 3, "retainedWhileLive", 2, "retainedAfterExpiry", 1);
                    }
                });
    }

    /** The recorded production stream, played into the scene through its last arrival. */
    private static FxClientWireOracleFixture.Replay replay() throws Exception {
        var replay =
                new FxClientWireOracleFixture.Replay(
                        FxClientWireOracleFixture.vector().getAsJsonObject("server"));
        replay.play(
                java.util.function.UnaryOperator.identity(),
                replay.end() + 4 * FxClientWireOracleFixture.FRAME,
                at -> {});
        return replay;
    }
}
