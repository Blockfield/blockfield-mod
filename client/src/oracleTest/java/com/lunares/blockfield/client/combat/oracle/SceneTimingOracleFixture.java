package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.animation.RemoteLrCompat;
import com.lunares.blockfield.client.animation.RemotePaPresentation;
import com.lunares.blockfield.client.combat.ClientAnimationAssets;
import com.lunares.blockfield.client.combat.ClientAnimationJournal;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.client.combat.SnapshotTransform;
import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTiming;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.ObserverReducer;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.lunares.blockfield.oracle.PaGraphNativeCases;
import com.lunares.blockfield.oracle.PaObserverNativeCases;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U8 native receive/task/main-frame timing oracle (ADR4 §9) in production Knot: the capsule bytes
 * go through the real codec, the main-thread task applies them to the observer journal, zero or
 * more client ticks run the pinned PA stack tick (with the production driven-stack mixin), and the
 * main frame installs the presentation and runs the transformed {@code PlayerModel}
 * preparation/setup. A native twin receives the same once clip through PA's native layer operation
 * at the same task and renders at the frame's ambient partial.
 *
 * <p>The Minecraft run loop, GL and the network stack are not started: the native schedule (task,
 * ticks, frame) is executed in its real order by this fixture, not by {@code Minecraft.runTick}.
 * The A law itself is a pure trace (U1); frames here use explicit A/R values.
 */
class SceneTimingOracleFixture {
    private static final AnimationPolicy POLICY =
            new AnimationPolicy(
                    0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    private static final long MS = 1_000_000;
    private static final int ENTITY = 610;
    private static final int[] FPS = {30, 60, 144, 240};
    private static final int[] TICKS = {0, 1, 3};

    private static LayerTag winner;
    private static KeyframeAnimation clip;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (((IAnimatedPlayer) player(1)).getAnimationStack() == null
                || ((IAnimatedPlayer) player(1))
                                .playerAnimator_getAnimation(PlayerAnimatorCompat.LOWER_ANIMATION)
                        == null) {
            Class.forName("me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration")
                    .getMethod("init")
                    .invoke(null);
            PlayerAnimatorCompat.init();
        }
        winner = rotationWinner(player(1));
        clip = PaGraphNativeCases.clip("fixture_fire", false);
    }

    /** One observer world: server-shaped stream, journal, a driven remote and its native twin. */
    static final class World {
        final PaGraph.Catalog catalog = new PaGraph.Catalog(0);
        final ClientAnimationAssets.Generation assets;
        final ClientAnimationJournal journal = new ClientAnimationJournal();
        final AnimationEvent.ObserverSeed seed;
        final TreeMap<Long, SnapshotTransform> frames = new TreeMap<>();
        final AbstractClientPlayer driven, twin;
        final AnimationState.Identity identity = identity();
        final org.mockito.MockedStatic<net.minecraft.client.Minecraft> singleton;
        long frame, revision, probed = 97;
        ClientAnimationJournal.Stamp stamp;

        World() throws Exception {
            this(java.util.function.UnaryOperator.identity());
        }

        /** {@code vanilla} rewrites the A-domain vanilla checkpoint of every S[t]. */
        World(java.util.function.UnaryOperator<AnimationState.Vanilla> vanilla) throws Exception {
            this(catalog -> null, (tick, state) -> state, vanilla);
        }

        /**
         * {@code tacz} builds the generation's TaCZ assets on the shared catalog (null: none);
         * {@code rewrite} replaces S[tick] after the vanilla rewrite (decisions, TaCZ state).
         */
        World(
                java.util.function.Function<
                                PaGraph.Catalog, com.lunares.blockfield.common.combat.TaczAssets>
                        tacz,
                java.util.function.BiFunction<Long, AnimationState, AnimationState> rewrite,
                java.util.function.UnaryOperator<AnimationState.Vanilla> vanilla)
                throws Exception {
            singleton = minecraft();
            assets =
                    new ClientAnimationAssets.Generation(
                            0, POLICY, tacz.apply(catalog), new LrAssets(catalog));
            ClientAnimationAssets.install(assets);
            var graph =
                    PaGraph.Stack.create(catalog, POLICY, PaGraphNativeCases.SOURCES, winner, 1)
                            .freeze(AnimationGraphState.neutral());
            for (long tick = 98; tick <= 130; tick++) {
                var checkpoint = rewrite.apply(tick, checkpoint(identity, tick, graph, vanilla));
                frames.put(
                        tick,
                        SnapshotTransform.exact(
                                tick, identity, 0, checkpoint.placement(), checkpoint));
            }
            seed = seed(frames.get(100L).checkpoint(), 100 * 256 + 1);
            until(4_995 * MS); // its receipt is mapped, so the activation proof can be exact
            journal.seed(seed, client(4_995 * MS));
            FxPoseLive.definitions(
                    List.of(
                            com.lunares.blockfield.oracle.FxClientCases.definition(
                                    identity, null)));
            driven = player(ENTITY);
            twin = player(ENTITY + 1);
            // ParCool's model hook reads its client attachment through the player's level.
            var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
            doReturn(true).when(level).isClientSide();
            doAnswer(call -> List.of(driven, twin)).when(level).players();
            for (var player : List.of(driven, twin)) doReturn(level).when(player).level();
        }

        /** Probe n leaves at tick n's boundary and is applied 1 ms later, in time order. */
        void until(long serverNs) {
            while ((probed + 1) * 50 * MS + MS <= serverNs) {
                long tick = ++probed;
                journal.probe(
                        tick, tick * 50 * MS, tick, tick * 50 * MS, client(tick * 50 * MS + MS));
            }
        }

        AnimationEvent capsule(long tick, long sequence, long predecessor, long acceptedNs) {
            return SceneTimingOracleFixture.capsule(
                    catalog, identity, tick, sequence, predecessor, acceptedNs);
        }

        /** A Create membership capsule (enter or exit) at {@code tick} (U6/U7b shape). */
        AnimationEvent create(
                long tick, long sequence, long predecessor, long acceptedNs, boolean enter) {
            return SceneTimingOracleFixture.create(
                    identity, tick, sequence, predecessor, acceptedNs, enter);
        }

        boolean apply(AnimationEvent event, long serverNs) {
            until(serverNs);
            return journal.capsule(decode(event), client(serverNs));
        }

        /** A frame at explicit clocks (the real CombatSceneClock), on the client's time base. */
        ClientAnimationJournal.Stamp stamp(Time r, Time a, long h, long clientNs) {
            until(clientNs - 3_000 * MS);
            frame++;
            revision++;
            return new ClientAnimationJournal.Stamp(frame, revision, r, a, h, clientNs);
        }

        ClientAnimationJournal.Stamp stamp(Time a, long serverNs) {
            until(serverNs);
            frame++;
            revision++;
            var r = new Time(a.tick() - 2, a.alpha());
            return new ClientAnimationJournal.Stamp(
                    frame, revision, r, a, a.tick() + 1, client(serverNs));
        }

        /** The production frame-entry path of ClientCombatScene.present for one remote. */
        String present(ClientAnimationJournal.Stamp stamp) {
            journal.frame(stamp, id -> id == ENTITY ? frames : new TreeMap<>());
            var reduced = journal.presentation(ENTITY);
            if (reduced == null) return journal.unavailable(ENTITY);
            var rEnd = AnimationTiming.endpoint(stamp.r());
            var aEnd = AnimationTiming.endpoint(stamp.a());
            return DrivenPresentation.install(
                    driven,
                    frames.get(rEnd.tick()),
                    rEnd.partial(),
                    frames.get(aEnd.tick()),
                    aEnd.partial(),
                    reduced,
                    assets,
                    stamp.a(),
                    stamp.r().fraction());
        }

        void close() {
            DrivenPresentation.forget(driven);
            RemoteLrCompat.detach(driven);
            ClientAnimationAssets.clear();
            singleton.close();
        }
    }

    private static long client(long serverNs) {
        return serverNs + 3_000 * MS;
    }

    @Test
    void foldAdoptionPreservesUnsentNativeApplications() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "fx-client-fold-unsent-application",
                () -> {
                    var world = new World();
                    try {
                        var first = world.stamp(new Time(100, 16384), 5_020 * MS);
                        assertNull(world.present(first));
                        var activation = world.journal.drainUnreported().getFirst().activation();
                        var boundaries = new HashMap<Long, AnimationState>();
                        for (long n = 101; n <= 102; n++)
                            boundaries.put(n, world.frames.get(n).checkpoint());
                        var reduced =
                                ObserverReducer.reduce(
                                        world.seed,
                                        activation,
                                        102,
                                        boundaries,
                                        List.of(),
                                        world.assets.tacz(),
                                        world.assets.lr(),
                                        POLICY);
                        var fold =
                                new AnimationEvent.ObserverSeed(
                                        world.identity,
                                        new AnimationResources.SeedRef(
                                                0,
                                                world.identity.targetEpoch(),
                                                104 * 256 + 9,
                                                digest(91)),
                                        world.seed.ref(),
                                        com.lunares.blockfield.common.combat.ParCoolProjection
                                                .withFrame(
                                                        world.frames.get(102L).checkpoint(),
                                                        reduced.parcoolFrame()),
                                        reduced.pa().graph(),
                                        102,
                                        world.seed.eventPrefix(),
                                        world.frames.get(102L).checkpoint().eventWatermark(),
                                        reduced.starts(),
                                        List.of(),
                                        ObserverReducer.DIGEST);
                        var enter = world.create(104, 104 * 256 + 1, 0, 5_165 * MS, true);
                        assertTrue(world.apply(enter, 5_166 * MS));
                        world.journal.seed(fold, client(5_168 * MS));
                        // Adopted once probe 104 brings the boundary after the fold's receipt.
                        var stamp = world.stamp(new Time(103, 24576), 5_205 * MS);
                        assertNull(world.present(stamp));
                        assertEquals(1, world.journal.counters().get("foldsAdopted"));
                        var reports = world.journal.drainUnreported();
                        assertEquals(2, reports.size());
                        assertEquals(world.seed.ref().contentId(), reports.getFirst().seedRef());
                        var application = reports.getFirst().applications().getFirst();
                        assertEquals(enter.id().eventSequence(), application.eventSequence());
                        assertEquals(stamp.frameId(), application.proof().applyFrameId());
                        assertEquals(stamp.revision(), application.proof().applyRevision());
                        assertEquals(fold.ref().contentId(), reports.getLast().seedRef());
                        assertTrue(reports.getLast().applications().isEmpty());
                        assertTrue(world.journal.drainUnreported().isEmpty());
                        var bits = model(world.driven, stamp.a().fraction());
                        assertArrayEquals(bits, model(world.driven, stamp.a().fraction()));
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        OracleTransformAudit.requireConfig("playerAnimator-common.mixins.json");
                        return Map.of(
                                "reportSeedOrder",
                                reports.stream()
                                        .map(ClientAnimationJournal.Report::seedRef)
                                        .toList(),
                                "application",
                                application,
                                "nativeModelBits",
                                bits);
                    } finally {
                        world.close();
                    }
                });
    }

    /**
     * FX-TRUST handoff: canonical TaCZ decisions raise the row prefix to their journal ids (the
     * lower clip selected at S[105], its no-gun stop fade at S[110]); once that fade completes no
     * start names either, yet the next row keeps the prefix (the server rejects a prefix below its
     * accepted high-water) and still replays exactly.
     */
    @Test
    void anEndedCanonicalStartKeepsThePrefixHighWater() {
        AnimSyncOracleRunner.run(
                "tacz",
                "fx-client-prefix-high-water",
                () -> {
                    var holder = new AnimationState.Tacz[1];
                    var world =
                            new World(
                                    catalog -> {
                                        var assets =
                                                new com.lunares.blockfield.common.combat.TaczAssets(
                                                        catalog,
                                                        Map.of("fixture:gun", "fixture:display"));
                                        var display =
                                                assets.register(
                                                        "fixture:display",
                                                        "fixture:animation",
                                                        false,
                                                        "default",
                                                        true,
                                                        Map.of(
                                                                "hold_lower",
                                                                PaGraphNativeCases.clip(
                                                                        "hold_lower", false)));
                                        holder[0] = gun(catalog, display.ref());
                                        return assets;
                                    },
                                    (tick, state) ->
                                            tick < 105 || tick > 110
                                                    ? state
                                                    : tick == 105
                                                            ? decided(state, holder[0])
                                                            : tick == 110
                                                                    ? decided(state, null)
                                                                    : withTacz(state, holder[0]),
                                    java.util.function.UnaryOperator.identity());
                    try {
                        long selected = 105 * 256 - 8, stopped = 110 * 256 - 8;
                        var during = row(world, 106.25);
                        assertEquals(selected, during.appliedEventSequence());
                        replay(world, during, world.stamp.a(), List.of());
                        var fading = row(world, 111.25);
                        assertTrue(
                                fading.starts().stream()
                                        .anyMatch(
                                                s ->
                                                        s.eventSequence() == stopped
                                                                && s.startKind()
                                                                        == StartKind.STOP_FADE),
                                "the stop fade contributes");
                        assertEquals(stopped, fading.appliedEventSequence());
                        replay(world, fading, world.stamp.a(), List.of());
                        var after = row(world, 125.5);
                        long recomputed = after.seedRef();
                        for (var start : after.starts())
                            recomputed = Math.max(recomputed, start.eventSequence());
                        assertTrue(recomputed < selected, "no start names either decision now");
                        assertEquals(stopped, after.appliedEventSequence(), "high-water kept");
                        replay(world, after, world.stamp.a(), List.of());
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                        return Map.of(
                                "prefixes",
                                List.of(
                                        during.appliedEventSequence(),
                                        fading.appliedEventSequence(),
                                        after.appliedEventSequence()),
                                "startsAfter",
                                after.starts().size(),
                                "recomputedWithoutHighWater",
                                recomputed,
                                "nativeModelBits",
                                model(world.driven, world.stamp.a().fraction()));
                    } finally {
                        world.close();
                    }
                });
    }

    /** One installed frame at A = {@code a} (R two ticks behind, H = A + 1) and its exact row. */
    private static com.lunares.blockfield.common.combat.AnimationStartReport.Victim row(
            World world, double a) {
        var stamp = world.stamp(time(a), 5_000 * MS + (long) ((a - 102) * 50 * MS));
        world.stamp = stamp;
        assertNull(world.present(stamp), "driven presentation");
        var row = world.journal.row(world.frames.get(stamp.h()), stamp);
        assertNull(row.unavailable(), row.unavailable());
        return row.victim();
    }

    private static AnimationState.Tacz gun(
            PaGraph.Catalog catalog, AnimationResources.DefinitionRef display) {
        return new AnimationState.Tacz(
                EvaluatorKind.PA,
                catalog.named("tacz.gun", "fixture:gun"),
                null,
                display,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                Outcome.NO_CHANGE);
    }

    /** S[t] still holding the gun, without a new selection. */
    private static AnimationState withTacz(AnimationState state, AnimationState.Tacz tacz) {
        return new AnimationState(
                state.identity(),
                state.serverTick(),
                state.sampleRevision(),
                state.eventWatermark(),
                state.placement(),
                state.vanilla(),
                state.stance(),
                state.equipment(),
                tacz,
                state.lr(),
                state.graph(),
                state.parcool(),
                state.create(),
                state.sbw(),
                state.decisions());
    }

    @Test
    void capsuleInstallsInItsApplicationFrameWithZeroClientTicks() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "u8-zero-tick-capsule-install",
                () -> {
                    var cases = new ArrayList<Map<String, Object>>();
                    int comparisons = 0;
                    for (int fps : FPS) {
                        int[] reference = null;
                        for (int ticks : TICKS) {
                            var result = timeline(fps, ticks);
                            comparisons += (int) result.get("comparedFrames");
                            // Client ticks between the task and the frame never move the
                            // driven presentation: the frame shows the same revision bits.
                            var bits = (int[]) result.remove("applicationFrameBits");
                            if (reference == null) reference = bits;
                            else assertArrayEquals(reference, bits, "client ticks moved A state");
                            cases.add(result);
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    var out = new LinkedHashMap<String, Object>();
                    out.put("cases", cases);
                    out.put("comparedFrames", comparisons);
                    out.put(
                            "boundary",
                            "native order executed by the fixture; Minecraft.runTick, GL and"
                                    + " the network stack are not started");
                    return out;
                });
    }

    /**
     * One FPS/client-tick case: frames at a fixed rate from a random phase, the capsule applied in
     * the task drain before frame k, then {@code ticks} client ticks before that frame.
     */
    private static Map<String, Object> timeline(int fps, int ticks) throws Exception {
        var world = new World();
        try {
            long period = 1_000_000_000L / fps;
            long start = 5_011 * MS + (fps * 7919L % 13) * MS;
            double ticksPerNs = 20.0 / 1_000_000_000L;
            // A trails the frame clock by two ticks (well past the seed at tick 100).
            int applicationFrame = Math.max(3, fps / 10);
            int frames = applicationFrame + fps / 4 + 4;
            int[] drivenBaseline = null, nativeBaseline = null, applicationBits = null;
            int drivenFirst = -1, nativeFirst = -1;
            long installBucket = -1;
            AnimationEvent event = null;
            var driven = world.driven;
            var twin = world.twin;
            var twinLayer = layer(twin, LayerTag.PA_95);
            for (int f = 0; f < frames; f++) {
                long serverNs = start + f * period;
                double at = 100 + (serverNs - 5_000 * MS) * ticksPerNs;
                var a = time(at);
                if (f == applicationFrame) {
                    // An event two ticks above A: legal, installed now (ADR4 §2.2).
                    long eventTick = a.tick() + 2;
                    event = world.capsule(eventTick, eventTick * 256 + 1, 0, start);
                    // Main-thread task drain: the capsule packet and the native twin's play method.
                    assertTrue(world.apply(event, serverNs - period / 2));
                    nativeInstall(twinLayer, clip);
                    for (int t = 0; t < ticks; t++) {
                        // Client ticks between the task and the frame: the native stack advances;
                        // the driven stack's tick is suppressed by the production mixin.
                        ((IAnimatedPlayer) driven).getAnimationStack().tick();
                        ((IAnimatedPlayer) twin).getAnimationStack().tick();
                    }
                }
                var stamp = world.stamp(a, serverNs);
                String why = world.present(stamp);
                assertNull(why, "driven presentation: " + why);
                if (f == applicationFrame) {
                    long sequence = event.id().eventSequence();
                    installBucket =
                            world.journal.presentation(ENTITY).starts().stream()
                                    .filter(s -> s.eventSequence() == sequence)
                                    .mapToLong(s -> s.phaseAnchor())
                                    .findFirst()
                                    .orElse(-1);
                    assertTrue(
                            installBucket < event.id().serverTick(),
                            "the start's bucket lies below its event tick");
                }
                float nativePartial = (float) (at - Math.floor(at));
                int[] drivenModel = model(driven, stamp.a().fraction());
                assertArrayEquals(
                        drivenModel,
                        model(driven, stamp.a().fraction()),
                        "a second pass of one revision is fresh scratch with equal bits");
                int[] nativeModel = model(twin, nativePartial);
                if (f == applicationFrame - 1) {
                    drivenBaseline = drivenModel;
                    nativeBaseline = nativeModel;
                }
                if (f == applicationFrame) applicationBits = drivenModel;
                if (f >= applicationFrame) {
                    if (drivenFirst < 0 && !Arrays.equals(drivenModel, drivenBaseline))
                        drivenFirst = f - applicationFrame;
                    if (nativeFirst < 0 && !Arrays.equals(nativeModel, nativeBaseline))
                        nativeFirst = f - applicationFrame;
                }
            }
            assertEquals(
                    (long)
                            Math.floor(
                                    100
                                            + (start + applicationFrame * period - 5_000 * MS)
                                                    * ticksPerNs),
                    installBucket,
                    "installed at the application frame's whole A bucket, before the event tick");
            assertEquals(0, drivenFirst, "zero intentionally added frames before it is visible");
            assertTrue(nativeFirst >= 0, "the native twin shows the same clip");
            var out = new LinkedHashMap<String, Object>();
            out.put("fps", fps);
            out.put("clientTicksBetweenApplyAndFrame", ticks);
            out.put("applicationBucket", installBucket);
            out.put("eventTick", event.id().serverTick());
            out.put("drivenFirstVisibleFrame", drivenFirst);
            out.put("nativeFirstVisibleFrame", nativeFirst);
            out.put("comparedFrames", frames);
            out.put("applicationFrameBits", applicationBits);
            return out;
        } finally {
            world.close();
        }
    }

    @Test
    void anApplicationAfterADrawIsANewRevisionNeverARetroactiveRewrite() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "u8-no-retroactive-revision",
                () -> {
                    var world = new World();
                    try {
                        var first = world.stamp(new Time(101, 0x4000), 5_070 * MS);
                        assertNull(world.present(first));
                        int[] drawn = model(world.driven, first.a().fraction());
                        var rowBefore = world.journal.row(world.frames.get(102L), first);
                        assertNull(rowBefore.unavailable(), rowBefore.unavailable());
                        // The capsule lands while this frame is active: same clocks, revision + 1.
                        var event = world.capsule(102, 102 * 256 + 1, 0, 5_060 * MS);
                        assertTrue(world.apply(event, 5_072 * MS));
                        world.revision++;
                        var revised =
                                new ClientAnimationJournal.Stamp(
                                        first.frameId(),
                                        world.revision,
                                        first.r(),
                                        first.a(),
                                        first.h(),
                                        client(5_072 * MS));
                        assertNull(world.present(revised));
                        int[] after = model(world.driven, revised.a().fraction());
                        assertEquals(
                                "application_pending",
                                world.journal.row(world.frames.get(102L), revised).unavailable(),
                                "its sW waits for boundary 102");
                        var exact = world.stamp(new Time(101, 0xC000), 5_102 * MS);
                        assertNull(world.present(exact));
                        var rowAfter = world.journal.row(world.frames.get(102L), exact);
                        assertNull(rowAfter.unavailable(), rowAfter.unavailable());
                        assertTrue(rowBefore.victim().applications().isEmpty(), "drawn row kept");
                        assertEquals(1, rowAfter.victim().applications().size());
                        var proof = rowAfter.victim().applications().getFirst().proof();
                        assertEquals(first.frameId(), proof.applyFrameId(), "same frame");
                        assertEquals(revised.revision(), proof.applyRevision(), "new revision");
                        assertEquals(first.a(), proof.applyA(), "A is not advanced to stamp it");
                        assertFalse(Arrays.equals(drawn, after), "the new revision shows it");
                        var out = new LinkedHashMap<String, Object>();
                        out.put("frame", first.frameId());
                        out.put("drawnRevision", first.revision());
                        out.put("appliedRevision", revised.revision());
                        out.put("applyA", proof.applyA().toString());
                        return out;
                    } finally {
                        world.close();
                    }
                });
    }

    @Test
    void reorderedAndDuplicatedCapsulesApplyOnceInCausalOrder() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "u8-capsule-causal-dedupe",
                () -> {
                    var world = new World();
                    try {
                        assertNull(world.present(world.stamp(new Time(101, 0), 5_050 * MS)));
                        var first = world.capsule(102, 102 * 256 + 1, 0, 5_060 * MS);
                        var second = world.capsule(103, 103 * 256 + 1, 102 * 256 + 1, 5_110 * MS);
                        // The successor arrives first (another transport order): it waits.
                        world.apply(second, 5_120 * MS);
                        assertNull(world.present(world.stamp(new Time(101, 0x8000), 5_121 * MS)));
                        assertTrue(
                                world.journal.presentation(ENTITY).starts().isEmpty(),
                                "no start before its predecessor");
                        world.apply(first, 5_172 * MS);
                        assertFalse(
                                world.apply(first, 5_173 * MS),
                                "a duplicate native/snapshot delivery is idempotent");
                        var stamp = world.stamp(new Time(101, 0x9000), 5_224 * MS);
                        assertNull(world.present(stamp));
                        var row = world.journal.row(world.frames.get(102L), stamp);
                        assertNull(row.unavailable(), row.unavailable());
                        assertEquals(
                                List.of(102L * 256 + 1, 103L * 256 + 1),
                                row.victim().applications().stream()
                                        .map(x -> x.eventSequence())
                                        .toList());
                        for (var application : row.victim().applications())
                            assertEquals(
                                    103, application.proof().mappingId(), "causal release mapping");
                        replay(world, row.victim(), stamp.a(), List.of(first, second));
                        return Map.of(
                                "applications",
                                row.victim().applications().size(),
                                "heldUntilPredecessor",
                                true);
                    } finally {
                        world.close();
                    }
                });
    }

    @Test
    void lrCompatibilityViewFollowsTheDrivenLifecycle() {
        AnimSyncOracleRunner.run(
                "lr",
                "u8-lr-compat-capsule-lifecycle",
                () -> {
                    var world = new World();
                    try {
                        var player = world.driven;
                        var upper = layer(player, LayerTag.PA_40);
                        assertNull(RemoteLrCompat.layer(player, RemoteLrCompat.UPPER));
                        // First presentation: the view is attached before the first install.
                        assertNull(world.present(world.stamp(new Time(101, 0), 5_050 * MS)));
                        assertTrue(RemotePaPresentation.driven(player));
                        assertFalse(RemoteLrCompat.unseeded(player));
                        assertNotNull(RemoteLrCompat.layer(player, RemoteLrCompat.UPPER));
                        // A capsule through the real lifecycle keeps the view attached.
                        var event = world.capsule(102, 102 * 256 + 1, 0, 5_060 * MS);
                        assertTrue(world.apply(event, 5_070 * MS));
                        assertNull(world.present(world.stamp(new Time(101, 0x4000), 5_071 * MS)));
                        assertFalse(RemoteLrCompat.unseeded(player));
                        // Undriven again: native layers back, view detached, native LR resumes.
                        DrivenPresentation.clear(player);
                        assertFalse(RemotePaPresentation.driven(player));
                        assertNull(RemoteLrCompat.layer(player, RemoteLrCompat.UPPER));
                        assertSame(upper, layer(player, LayerTag.PA_40));
                        // Re-driving attaches a fresh view from the native state.
                        assertNull(world.present(world.stamp(new Time(101, 0x8000), 5_080 * MS)));
                        assertFalse(RemoteLrCompat.unseeded(player));
                        // A lost view is labelled and the remote returns to native, never
                        // retried with an empty history.
                        RemoteLrCompat.clearAll();
                        assertTrue(RemoteLrCompat.unseeded(player));
                        var why = world.present(world.stamp(new Time(101, 0xC000), 5_090 * MS));
                        assertNotNull(why);
                        assertTrue(why.contains("view lost"), why);
                        assertFalse(RemotePaPresentation.driven(player));
                        assertEquals(
                                "lr_view_lost",
                                world.present(world.stamp(new Time(102, 0), 5_100 * MS)),
                                "no retry with an empty view while this entity object lives");
                        OracleTransformAudit.requireConfig(
                                "blockfield-client-lrtactical.mixins.json");
                        return Map.of(
                                "attachBeforeFirstInstall",
                                true,
                                "detachAfterClear",
                                true,
                                "lostView",
                                why);
                    } finally {
                        world.close();
                    }
                });
    }

    // -- U8b: the remaining ADR4 §9 cases -------------------------------------------------------

    /**
     * A 300 ms stall and a timeout on the production clock ({@code CombatSceneClock}): A holds at H
     * while snapshots stop, a capsule applied during the hold installs at the held bucket in its
     * application frame and keeps that anchor when A resumes after the burst; past the hold window
     * the clock times out (no frame, A frozen) and a later stream never lowers A.
     */
    @Test
    void aStallHoldsAAtHAndATimeoutFreezesIt() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "u8b-stall-hold-timeout",
                () -> {
                    var cases = new ArrayList<Map<String, Object>>();
                    for (int fps : FPS) cases.add(stall(fps));
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var out = new LinkedHashMap<String, Object>();
                    out.put("cases", cases);
                    out.put(
                            "boundary",
                            "production CombatSceneClock on fixture-ordered arrivals and frames;"
                                    + " native free-running stall behavior is characterization"
                                    + " (nativeAdvancedDuringHold), not equality");
                    return out;
                });
    }

    private static Map<String, Object> stall(int fps) throws Exception {
        var world = new World();
        try {
            var clock = new com.lunares.blockfield.client.combat.CombatSceneClock();
            long period = 1_000_000_000L / fps;
            // Client time base: tick k leaves at k * 50 ms and arrives 30 ms later (+3 s offset).
            var arrivals = new TreeMap<Long, List<Long>>();
            for (long k = 100; k <= 110; k++) arrive(arrivals, k, k);
            // A 300 ms stall: 111..116 are held and arrive as one burst with 116's arrival.
            for (long k = 111; k <= 116; k++) arrive(arrivals, k, 116);
            for (long k = 117; k <= 121; k++) arrive(arrivals, k, k);
            long last = client(121 * 50 * MS + 30 * MS);
            long begin = client(103 * 50 * MS) + (fps * 7919L % 13) * MS;
            long end = last + 800 * MS;
            Time previous = null;
            int held = 0, applicationFrame = -1, visibleFrame = -1, timedOutFrames = 0;
            long anchor = -1, sequence = 0;
            int[] heldBits = null, nativeAtApply = null;
            boolean nativeAdvanced = false;
            var twinLayer = layer(world.twin, LayerTag.PA_95);
            long h = -1;
            int f = 0;
            for (long now = begin; now <= end; now += period, f++) {
                for (var batch : arrivals.headMap(now, true).entrySet())
                    for (long k : batch.getValue()) {
                        clock.arrival(
                                com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket
                                        .animation(
                                                17,
                                                k - 1,
                                                k,
                                                2,
                                                6,
                                                10,
                                                500,
                                                new com.lunares.blockfield.common.combat
                                                        .AnimationRecipe.Batch(
                                                        0, 0, List.of(), List.of(), List.of())),
                                batch.getKey() / 1e6);
                        h = Math.max(h, k);
                    }
                arrivals.headMap(now, true).clear();
                if (!clock.frameNs(now)) {
                    if (now > last) {
                        assertTrue(clock.timedOut(now / 1e6), "past the hold window");
                        assertEquals(previous, clock.a(), "a timeout freezes A");
                        timedOutFrames++;
                    }
                    continue;
                }
                var r = clock.r();
                var a = clock.a();
                if (!clock.animationReady()) continue;
                assertTrue(r.compareTo(a) <= 0 && a.compareTo(Time.whole(h)) <= 0, "R <= A <= H");
                if (previous != null) assertTrue(previous.compareTo(a) <= 0, "A never decreases");
                boolean hold = previous != null && a.equals(previous) && a.equals(Time.whole(h));
                if (hold && h == 110 && sequence == 0 && ++held >= 2) {
                    // Applied in the task drain before this frame, while A holds at H.
                    long tick = h + 1;
                    sequence = tick * 256 + 1;
                    var event = world.capsule(tick, sequence, 0, now - 3_000 * MS - period);
                    assertTrue(world.apply(event, now - 3_000 * MS - period / 2));
                    nativeInstall(twinLayer, clip);
                    applicationFrame = f;
                }
                var stamp = world.stamp(r, a, h, now);
                assertNull(world.present(stamp), "driven presentation");
                int[] bits = model(world.driven, a.fraction());
                final long id = sequence;
                if (f == applicationFrame) {
                    anchor =
                            world.journal.presentation(ENTITY).starts().stream()
                                    .filter(x -> x.eventSequence() == id)
                                    .mapToLong(x -> x.phaseAnchor())
                                    .findFirst()
                                    .orElse(-1);
                    assertEquals(110, anchor, "installed at the held bucket in its frame");
                    heldBits = bits;
                    nativeAtApply = model(world.twin, .5f);
                } else if (applicationFrame >= 0 && visibleFrame < 0) {
                    if (a.equals(Time.whole(110)))
                        assertArrayEquals(heldBits, bits, "A holds: the held frame repeats");
                    else if (!java.util.Arrays.equals(heldBits, bits)) visibleFrame = f;
                    if (!java.util.Arrays.equals(nativeAtApply, model(world.twin, .5f)))
                        nativeAdvanced = true;
                }
                if (applicationFrame >= 0 && f > applicationFrame) {
                    assertEquals(
                            anchor,
                            world.journal.presentation(ENTITY).starts().stream()
                                    .filter(x -> x.eventSequence() == id)
                                    .mapToLong(x -> x.phaseAnchor())
                                    .findFirst()
                                    .orElse(anchor),
                            "resume never re-anchors the held start");
                }
                if (a.compareTo(Time.whole(110)) > 0 && applicationFrame < 0)
                    throw new AssertionError(fps + " FPS: A left the stall before a held frame");
                previous = a;
            }
            assertTrue(applicationFrame >= 0, "a capsule applied during the hold");
            assertTrue(visibleFrame > applicationFrame, "visible once A resumes past the anchor");
            assertTrue(timedOutFrames > 0, "the stream timed out");
            // A later stream restarts presentation; A keeps its connection high-water.
            var restarted = clock.restarted();
            assertEquals(previous, restarted.a());
            var out = new LinkedHashMap<String, Object>();
            out.put("fps", fps);
            out.put("heldFramesBeforeApply", held);
            out.put("applicationFrame", applicationFrame);
            out.put("anchor", anchor);
            out.put("firstVisibleFrameAfterApply", visibleFrame - applicationFrame);
            out.put("timedOutFrames", timedOutFrames);
            out.put("finalA", String.valueOf(previous));
            out.put("nativeAdvancedDuringHold", nativeAdvanced);
            return out;
        } finally {
            world.close();
        }
    }

    /** Snapshot {@code tick} arrives with tick {@code at}'s send, 30 ms after it leaves. */
    private static void arrive(TreeMap<Long, List<Long>> arrivals, long tick, long at) {
        arrivals.computeIfAbsent(client(at * 50 * MS + 30 * MS), k -> new ArrayList<>()).add(tick);
    }

    /**
     * A late event while an incumbent and a nested fade are mid-flight: a fire, a reload two ticks
     * later (the fire's fade-in still running) and a stop two ticks after that (the reload's fade
     * still running). Each installs in its application frame at its own bucket; every contributing
     * start keeps its first anchor; the server's replay of every frame's row reproduces the
     * presentation graph, starts and digests; client ticks never move the driven bits.
     */
    @Test
    void aLateEventDuringNestedFadesKeepsEveryContributingStart() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "u8b-nested-fade-late-event",
                () -> {
                    var cases = new ArrayList<Map<String, Object>>();
                    for (int fps : FPS) {
                        int[] reference = null;
                        for (int ticks : TICKS) {
                            var result = nested(fps, ticks);
                            var bits = (int[]) result.remove("bits");
                            if (reference == null) reference = bits;
                            else assertArrayEquals(reference, bits, "client ticks moved A state");
                            cases.add(result);
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return Map.of("cases", cases);
                });
    }

    private static Map<String, Object> nested(int fps, int ticks) throws Exception {
        var world = new World();
        try {
            long period = 1_000_000_000L / fps;
            long start = 5_011 * MS + (fps * 7919L % 13) * MS;
            double ticksPerNs = 20.0 / 1_000_000_000L;
            int perTick = Math.max(1, fps / 20);
            int[] at = new int[3];
            at[0] = Math.max(3, fps / 10);
            at[1] = at[0] + 2 * perTick;
            at[2] = at[1] + 2 * perTick;
            int frames = at[2] + 12 * perTick + 4;
            var reload = PaGraphNativeCases.clip("fixture_reload", false);
            var delivered = new ArrayList<AnimationEvent>();
            var twinLayer = layer(world.twin, LayerTag.PA_95);
            var anchors = new LinkedHashMap<String, Long>();
            long predecessor = 0;
            long[] ids = new long[3];
            int maxStarts = 0, compared = 0, pending = 0;
            int[] trace = new int[0];
            for (int f = 0; f < frames; f++) {
                long serverNs = start + f * period;
                var a = time(100 + (serverNs - 5_000 * MS) * ticksPerNs);
                for (int k = 0; k < 3; k++) {
                    if (f != at[k]) continue;
                    long tick = a.tick() + 2;
                    ids[k] = tick * 256 + 1;
                    var event =
                            k == 0
                                    ? world.capsule(tick, ids[k], predecessor, serverNs - period)
                                    : k == 1
                                            ? capsule(
                                                    world.catalog,
                                                    world.identity,
                                                    tick,
                                                    ids[k],
                                                    predecessor,
                                                    serverNs - period,
                                                    LayerTag.PA_95,
                                                    reload,
                                                    NativeFamily.TACZ,
                                                    EventKind.TACZ_RELOAD,
                                                    StartKind.REPLACE)
                                            : capsule(
                                                    world.catalog,
                                                    world.identity,
                                                    tick,
                                                    ids[k],
                                                    predecessor,
                                                    serverNs - period,
                                                    LayerTag.PA_95,
                                                    null,
                                                    NativeFamily.TACZ,
                                                    EventKind.TACZ_DRAW,
                                                    StartKind.STOP_FADE);
                    assertTrue(world.apply(event, serverNs - 1));
                    delivered.add(event);
                    predecessor = ids[k];
                    if (k == 0) nativeInstall(twinLayer, clip);
                    else if (k == 1) nativeInstall(twinLayer, reload);
                    else
                        twinLayer.replaceAnimationWithFade(
                                AbstractFadeModifier.standardFadeIn(
                                        PaGraph.TACZ_FADE,
                                        dev.kosmx.playerAnim.core.util.Ease.INOUTSINE),
                                null,
                                false);
                    for (int t = 0; t < ticks; t++) {
                        ((IAnimatedPlayer) world.driven).getAnimationStack().tick();
                        ((IAnimatedPlayer) world.twin).getAnimationStack().tick();
                    }
                }
                var stamp = world.stamp(a, serverNs);
                assertNull(world.present(stamp), "driven presentation");
                var reduced = world.journal.presentation(ENTITY);
                for (int k = 0; k < 3; k++)
                    if (f == at[k]) {
                        long id = ids[k];
                        assertEquals(
                                a.tick(),
                                reduced.starts().stream()
                                        .filter(x -> x.eventSequence() == id)
                                        .mapToLong(x -> x.phaseAnchor())
                                        .findFirst()
                                        .orElse(-1),
                                "event " + k + " installs at its application bucket");
                    }
                for (var x : reduced.starts()) {
                    var first =
                            anchors.putIfAbsent(
                                    x.eventSequence() + ":" + x.nodeId(), x.phaseAnchor());
                    if (first != null)
                        assertEquals(first, x.phaseAnchor(), "an incumbent keeps its anchor");
                }
                maxStarts = Math.max(maxStarts, reduced.starts().size());
                var row = world.journal.row(world.frames.get(stamp.h()), stamp);
                // An application's proof is sent once the probe after it records its boundary.
                if ("application_pending".equals(row.unavailable())) pending++;
                else {
                    assertNull(row.unavailable(), row.unavailable());
                    var replayed = replay(world, row.victim(), stamp.a(), delivered);
                    assertArrayEquals(
                            AnimationSchema.encode(reduced.pa().graph()),
                            AnimationSchema.encode(replayed.pa().graph()),
                            "server replay of frame " + f);
                    compared++;
                }
                int[] bits = model(world.driven, stamp.a().fraction());
                trace = concat(trace, bits);
            }
            assertTrue(maxStarts >= 3, "fire, reload and stop fades contribute together");
            assertTrue(
                    pending <= 3 * (perTick + 2), "each proof waits at most one probe: " + pending);
            var out = new LinkedHashMap<String, Object>();
            out.put("fps", fps);
            out.put("clientTicksBetweenApplyAndFrame", ticks);
            out.put("applicationFrames", java.util.Arrays.toString(at));
            out.put("maxContributingStarts", maxStarts);
            out.put("serverReplayFrames", compared);
            out.put("bits", trace);
            return out;
        } finally {
            world.close();
        }
    }

    /**
     * An accepted LR attack with its carried native {@code SMeleeAnimationSync}: the native body
     * runs once in the delivery task, the attack installs on layer 40 at the application frame's
     * bucket (below its event tick) and is visible in that frame whatever the client ticks.
     */
    @Test
    void anLrAttackInstallsInItsApplicationFrameWithItsNativeBodyOnce() {
        AnimSyncOracleRunner.run(
                "lr",
                "u8b-lr-attack-zero-tick",
                () -> {
                    var cases = new ArrayList<Map<String, Object>>();
                    for (int fps : FPS) {
                        int[] reference = null;
                        for (int ticks : TICKS) {
                            var result = lrAttack(fps, ticks);
                            var bits = (int[]) result.remove("applicationFrameBits");
                            if (reference == null) reference = bits;
                            else assertArrayEquals(reference, bits, "client ticks moved A state");
                            cases.add(result);
                        }
                    }
                    OracleTransformAudit.requireConfig("blockfield-client-lrtactical.mixins.json");
                    return Map.of("cases", cases);
                });
    }

    private static Map<String, Object> lrAttack(int fps, int ticks) throws Exception {
        com.lunares.blockfield.client.combat.ClientCombatScene.disconnect();
        var world = new World();
        try {
            NativeCapsuleOracleFixture.attach(
                    (net.minecraft.client.multiplayer.ClientLevel) world.driven.level());
            long period = 1_000_000_000L / fps;
            long start = 5_011 * MS + (fps * 7919L % 13) * MS;
            double ticksPerNs = 20.0 / 1_000_000_000L;
            int applicationFrame = Math.max(3, fps / 10);
            int frames = applicationFrame + fps / 4 + 4;
            Class<?> pre = Class.forName("me.xjqsh.lrtactical.api.event.MeleePreAttackEvent");
            int[] baseline = null, applicationBits = null;
            int visible = -1;
            long bucket = -1, eventTick = -1;
            int runs;
            ExactSceneOracleFixture.prime(world, client(start - 50 * MS));
            try (var constructed = mockConstruction(pre)) {
                for (int f = 0; f < frames; f++) {
                    long serverNs = start + f * period;
                    var a = time(100 + (serverNs - 5_000 * MS) * ticksPerNs);
                    if (f == applicationFrame) {
                        eventTick = a.tick() + 2;
                        var event =
                                NativeCapsuleOracleFixture.lrCarried(
                                        capsule(
                                                world.catalog,
                                                world.identity,
                                                eventTick,
                                                eventTick * 256 + 1,
                                                0,
                                                serverNs - period,
                                                LayerTag.PA_40,
                                                clip,
                                                NativeFamily.LR,
                                                EventKind.LR_ATTACK,
                                                StartKind.NEW),
                                        ENTITY);
                        // The delivery task: the carried native body, then the capsule.
                        com.lunares.blockfield.client.combat.ClientCombatScene.capsules(
                                new AnimationEventS2CPacket(List.of(event)), client(serverNs));
                        assertTrue(world.apply(event, serverNs - 1));
                        for (int t = 0; t < ticks; t++)
                            ((IAnimatedPlayer) world.driven).getAnimationStack().tick();
                    }
                    var stamp = world.stamp(a, serverNs);
                    assertNull(world.present(stamp), "driven presentation");
                    int[] bits = model(world.driven, stamp.a().fraction());
                    if (f == applicationFrame - 1) baseline = bits;
                    if (f == applicationFrame) {
                        applicationBits = bits;
                        long id = eventTick * 256 + 1;
                        bucket =
                                world.journal.presentation(ENTITY).starts().stream()
                                        .filter(x -> x.eventSequence() == id)
                                        .mapToLong(x -> x.phaseAnchor())
                                        .findFirst()
                                        .orElse(-1);
                        if (!java.util.Arrays.equals(bits, baseline)) visible = 0;
                    }
                }
                runs = constructed.constructed().size();
            }
            assertEquals(1, runs, "the native LR body ran once, in the delivery task");
            assertTrue(bucket >= 0 && bucket < eventTick, "installed below its event tick");
            assertEquals(0, visible, "visible in its application frame");
            var out = new LinkedHashMap<String, Object>();
            out.put("fps", fps);
            out.put("clientTicksBetweenApplyAndFrame", ticks);
            out.put("applicationBucket", bucket);
            out.put("eventTick", eventTick);
            out.put("nativeBodyRuns", runs);
            out.put("applicationFrameBits", applicationBits);
            return out;
        } finally {
            world.close();
            com.lunares.blockfield.client.combat.ClientCombatScene.disconnect();
        }
    }

    /**
     * Create accepted membership on the A grid: an enter capsule hangs the driven remote in its
     * application frame at phase {@code (floor(A) - sA) + frac(A)}, equal to the server port's
     * mirror at every following frame, and an exit returns it to the native pose in its frame.
     */
    @Test
    void createMembershipStartsAndEndsInItsApplicationFrames() {
        AnimSyncOracleRunner.run(
                "create",
                "u8b-create-membership-zero-tick",
                () -> {
                    var cases = new ArrayList<Map<String, Object>>();
                    for (int fps : FPS) {
                        int[] reference = null;
                        for (int ticks : TICKS) {
                            var result = createMembership(fps, ticks);
                            var bits = (int[]) result.remove("enterFrameBits");
                            if (reference == null) reference = bits;
                            else assertArrayEquals(reference, bits, "client ticks moved A state");
                            cases.add(result);
                        }
                    }
                    OracleTransformAudit.requireConfig("create.mixins.json");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    return Map.of("cases", cases);
                });
    }

    private static Map<String, Object> createMembership(int fps, int ticks) throws Exception {
        var world = new World();
        try {
            long period = 1_000_000_000L / fps;
            long start = 5_011 * MS + (fps * 7919L % 13) * MS;
            double ticksPerNs = 20.0 / 1_000_000_000L;
            int enterFrame = Math.max(3, fps / 10);
            int exitFrame = enterFrame + 3 * Math.max(1, fps / 20) + 1;
            int frames = exitFrame + 3;
            var pivots = DrivenInputsOracleFixture.pivots();
            int[] enterBits = null;
            long anchor = -1, enterId = -1;
            int hanging = 0;
            for (int f = 0; f < frames; f++) {
                long serverNs = start + f * period;
                var a = time(100 + (serverNs - 5_000 * MS) * ticksPerNs);
                if (f == enterFrame || f == exitFrame) {
                    long tick = a.tick() + 2;
                    long id = tick * 256 + 1;
                    var event =
                            world.create(
                                    tick,
                                    id,
                                    f == enterFrame ? 0 : enterId,
                                    serverNs - period,
                                    f == enterFrame);
                    if (f == enterFrame) enterId = id;
                    assertTrue(world.apply(event, serverNs - 1));
                    for (int t = 0; t < ticks; t++)
                        ((IAnimatedPlayer) world.driven).getAnimationStack().tick();
                }
                var stamp = world.stamp(a, serverNs);
                assertNull(world.present(stamp), "driven presentation");
                int[] bits = model(world.driven, stamp.a().fraction());
                var membership = world.journal.presentation(ENTITY).create();
                if (f < enterFrame) {
                    assertNull(membership);
                } else if (f < exitFrame) {
                    assertNotNull(membership, "hanging from its application frame");
                    if (f == enterFrame) {
                        anchor = membership.phaseAnchor();
                        assertEquals(a.tick(), anchor, "anchored at the application bucket");
                        enterBits = bits;
                    }
                    assertEquals(anchor, membership.phaseAnchor(), "never re-anchored");
                    float phase =
                            com.lunares.blockfield.common.combat.CreateHanging.phase(
                                    membership.u0(),
                                    membership.phaseAnchor(),
                                    a.tick(),
                                    a.fraction());
                    assertArrayEquals(
                            CreateHangingOracleFixture.expectedBits(pivots, phase),
                            bits,
                            "native setHangingPose at the server port's phase, frame " + f);
                    hanging++;
                } else {
                    assertNull(membership, "the exit ends it in its application frame");
                    try (var scope = DrivenPresentation.scope(world.driven)) {
                        assertSame(
                                DrivenPresentation.NOT_HANGING,
                                DrivenPresentation.hanging(world.driven),
                                "the native renderer sees a non-member");
                    }
                    float phase =
                            com.lunares.blockfield.common.combat.CreateHanging.phase(
                                    0, anchor, a.tick(), a.fraction());
                    assertFalse(
                            java.util.Arrays.equals(
                                    CreateHangingOracleFixture.expectedBits(pivots, phase), bits),
                            "no hanging pose after the exit");
                }
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("fps", fps);
            out.put("clientTicksBetweenApplyAndFrame", ticks);
            out.put("anchor", anchor);
            out.put("hangingFrames", hanging);
            out.put("enterFrameBits", enterBits);
            return out;
        } finally {
            world.close();
        }
    }

    /**
     * Packetless TaCZ decisions (ADR4 §3.2): a canonical lower-loop selection in S[105], received
     * long before A gets there, installs only when floor(A) reaches 105 (CANONICAL_TICK, anchor
     * 105) and is visible in that frame; with an observer-applied finite once actor owning the
     * layer, the same decisions start the loop as PROJECTED_SUCCESSOR at the first boundary the
     * actor has ended on the observer's own timeline. Client ticks never move it.
     */
    @Test
    void packetlessDecisionsBecomeEligibleAtTheirBoundary() {
        AnimSyncOracleRunner.run(
                "tacz",
                "u8b-packetless-decision-eligibility",
                () -> {
                    var cases = new ArrayList<Map<String, Object>>();
                    for (int fps : FPS)
                        for (boolean successor : new boolean[] {false, true}) {
                            int[] reference = null;
                            for (int ticks : new int[] {0, 3}) {
                                var result = packetless(fps, ticks, successor);
                                var bits = (int[]) result.remove("trace");
                                if (reference == null) reference = bits;
                                else assertArrayEquals(reference, bits, "client ticks moved it");
                                cases.add(result);
                            }
                        }
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    return Map.of("cases", cases);
                });
    }

    private static Map<String, Object> packetless(int fps, int ticks, boolean successor)
            throws Exception {
        // The successor variant's display names the finite clip its lower loop: the observer once
        // of that clip keeps the layer (same clip, no change) until it ends on the observer's
        // timeline, where the next boundary's decision restarts it as PROJECTED_SUCCESSOR.
        var finite = PaGraphNativeCases.clip("hold_lower", false);
        var loop = successor ? finite : PaGraphNativeCases.clip("hold_lower", true);
        var holder = new Object[1];
        var world =
                new World(
                        catalog -> {
                            var assets =
                                    new com.lunares.blockfield.common.combat.TaczAssets(
                                            catalog, Map.of("fixture:gun", "fixture:display"));
                            var display =
                                    assets.register(
                                            "fixture:display",
                                            "fixture:animation",
                                            false,
                                            "default",
                                            true,
                                            Map.of("hold_lower", loop));
                            holder[0] =
                                    new AnimationState.Tacz(
                                            EvaluatorKind.PA,
                                            catalog.named("tacz.gun", "fixture:gun"),
                                            null,
                                            display.ref(),
                                            false,
                                            0,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            false,
                                            null,
                                            Outcome.NO_CHANGE);
                            return assets;
                        },
                        (tick, state) ->
                                tick < 105
                                        ? state
                                        : decided(state, (AnimationState.Tacz) holder[0]),
                        java.util.function.UnaryOperator.identity());
        try {
            long period = 1_000_000_000L / fps;
            long start = 5_011 * MS + (fps * 7919L % 13) * MS;
            double ticksPerNs = 20.0 / 1_000_000_000L;
            // The fixture stream holds S[98..130]: stay below 130 so ceil(A) has its record.
            int frames = (int) ((successor ? 26.5 : 8.0) * fps / 20);
            int onceFrame = Math.max(3, fps / 10);
            long onceId = -1, loopAnchor = -1;
            AnchorClass loopClass = null;
            int firstLoopFrame = -1;
            int[] previous = null, trace = new int[0];
            for (int f = 0; f < frames; f++) {
                long serverNs = start + f * period;
                var a = time(102 + (serverNs - 5_000 * MS) * ticksPerNs);
                if (successor && f == onceFrame) {
                    long tick = a.tick() + 2;
                    onceId = tick * 256 + 1;
                    var event =
                            capsule(
                                    world.catalog,
                                    world.identity,
                                    tick,
                                    onceId,
                                    0,
                                    serverNs - period,
                                    LayerTag.PA_93,
                                    finite,
                                    NativeFamily.TACZ,
                                    EventKind.TACZ_FIRE,
                                    StartKind.NEW);
                    assertTrue(world.apply(event, serverNs - 1));
                    for (int t = 0; t < ticks; t++)
                        ((IAnimatedPlayer) world.driven).getAnimationStack().tick();
                }
                var stamp = world.stamp(a, serverNs);
                assertNull(world.present(stamp), "driven presentation");
                int[] bits = model(world.driven, stamp.a().fraction());
                trace = concat(trace, bits);
                final long once = onceId;
                var loopStart =
                        world.journal.presentation(ENTITY).starts().stream()
                                .filter(
                                        x ->
                                                x.layerTag() == LayerTag.PA_93
                                                        && x.eventSequence() != once)
                                .filter(x -> x.anchorClass() != AnchorClass.OBSERVER_APPLY)
                                .findFirst()
                                .orElse(null);
                if (loopStart == null) {
                    assertTrue(
                            successor || a.tick() < 105,
                            "the received S[105] decision waits for A to reach 105");
                } else if (firstLoopFrame < 0) {
                    firstLoopFrame = f;
                    loopAnchor = loopStart.phaseAnchor();
                    loopClass = loopStart.anchorClass();
                    assertEquals(a.tick(), loopAnchor, "eligible at the frame's boundary");
                    if (!successor) {
                        assertEquals(105, loopAnchor);
                        assertEquals(AnchorClass.CANONICAL_TICK, loopClass);
                        assertFalse(
                                java.util.Arrays.equals(previous, bits), "visible in that frame");
                    } else assertEquals(AnchorClass.PROJECTED_SUCCESSOR, loopClass);
                } else assertEquals(loopAnchor, loopStart.phaseAnchor(), "never re-anchored later");
                previous = bits;
            }
            assertTrue(firstLoopFrame >= 0, "the selected loop installed");
            var out = new LinkedHashMap<String, Object>();
            out.put("fps", fps);
            out.put("clientTicksBetweenFrames", ticks);
            out.put("successorCase", successor);
            out.put("loopAnchor", loopAnchor);
            out.put("loopAnchorClass", String.valueOf(loopClass));
            out.put("trace", trace);
            return out;
        } finally {
            world.close();
        }
    }

    /** S[t] with a gun held and its POST_NATIVE_TICK lower-loop decision before the marker. */
    private static AnimationState decided(AnimationState state, AnimationState.Tacz tacz) {
        long tick = state.serverTick();
        var input =
                new AnimationResources.InputRef(
                        state.identity().resourceGeneration(),
                        state.identity().targetEpoch(),
                        tick,
                        AnimationResources.Digest.hash(AnimationSchema.encode(state.placement())));
        var patch =
                new AnimationEvent.Patch(
                        LayerTag.PA_93,
                        StartKind.NEW,
                        Outcome.NO_CHANGE,
                        new AnimationGraphState(
                                List.of(new AnimationGraphState.Root(93, LayerTag.PA_93, null)),
                                List.of()),
                        List.of(),
                        null,
                        null,
                        tacz,
                        null,
                        null,
                        null,
                        state.vanilla(),
                        null);
        var decision =
                new AnimationEvent.Decision(
                        new AnimationEvent.EventId(
                                tick, tick * 256 - 8, NativeStage.POST_NATIVE_TICK),
                        LayerTag.PA_93,
                        StartKind.NEW,
                        Outcome.NO_CHANGE,
                        0,
                        null,
                        input,
                        patch,
                        0);
        var decisions = new ArrayList<AnimationEvent.Decision>();
        decisions.add(decision);
        decisions.addAll(state.decisions());
        return new AnimationState(
                state.identity(),
                state.serverTick(),
                state.sampleRevision(),
                state.eventWatermark(),
                state.placement(),
                state.vanilla(),
                state.stance(),
                state.equipment(),
                tacz,
                state.lr(),
                state.graph(),
                state.parcool(),
                state.create(),
                state.sbw(),
                decisions);
    }

    /**
     * The server's reconstruction of one row (as {@code AnimSyncValidation}): the activation it
     * names, the templates of the delivered capsules its applications report, the shared reducer
     * and both digests, which must equal the client's.
     */
    private static ObserverReducer.Result replay(
            World world,
            com.lunares.blockfield.common.combat.AnimationStartReport.Victim row,
            Time a,
            List<AnimationEvent> delivered) {
        com.lunares.blockfield.common.combat.AnimationStartReport.validate(
                new com.lunares.blockfield.common.combat.AnimationStartReport(List.of(row)));
        com.lunares.blockfield.common.combat.AnimationStartReport.Start activation = null;
        for (var start : row.starts())
            if (start.startKind() == StartKind.SEED_CONTINUE
                    && (activation == null || start.nodeId() < activation.nodeId()))
                activation = start;
        var events = new HashMap<Long, AnimationEvent>();
        for (var event : delivered) events.put(event.id().eventSequence(), event);
        var applied = new ArrayList<ObserverReducer.Applied>();
        for (var application : row.applications()) {
            var event = events.get(application.eventSequence());
            applied.add(
                    new ObserverReducer.Applied(
                            event, ObserverReducer.template(event, application.proof(), POLICY)));
        }
        var ordered = ObserverReducer.ordered(applied);
        var boundaries = new HashMap<Long, AnimationState>();
        for (long n = world.seed.checkpoint().serverTick() + 1; n <= a.tick(); n++)
            boundaries.put(n, world.frames.get(n).checkpoint());
        var reduced =
                ObserverReducer.reduce(
                        world.seed,
                        activation,
                        a.tick(),
                        boundaries,
                        ordered,
                        world.assets.tacz(),
                        world.assets.lr(),
                        POLICY);
        assertEquals(reduced.starts(), row.starts(), "start_mismatch");
        assertEquals(
                ObserverReducer.prefixDigest(row.seedRef(), ordered),
                row.eventPrefixDigest(),
                "event_prefix");
        assertEquals(
                com.lunares.blockfield.common.combat.AnimationResolver.presentationDigest(
                        world.seed, reduced.pa().graph(), row),
                row.presentationDigest(),
                "presentation_digest");
        return reduced;
    }

    private static int[] concat(int[] a, int[] b) {
        var out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // -- server-shaped values -----------------------------------------------------------------

    private static AnimationState.Identity identity() {
        return new AnimationState.Identity(
                3,
                17,
                1,
                ENTITY,
                0,
                5,
                9,
                0,
                0,
                0,
                0,
                AnimationSchema.layoutDigest(),
                new AnimationResources.Digest(0, 0, 0, 0));
    }

    private static AnimationState checkpoint(
            AnimationState.Identity identity,
            long tick,
            AnimationGraphState graph,
            java.util.function.UnaryOperator<AnimationState.Vanilla> rewrite) {
        var vanilla = rewrite.apply(PaObserverNativeCases.vanilla(Pose.STANDING, false, false));
        var base = sample(AnimationState.class);
        var placement =
                new AnimationState.Placement(
                        new AnimationState.Vec3(tick - .25, 64, 0),
                        new AnimationState.Vec3(tick, 64, 0),
                        new AnimationState.Angles(0, 0, 0, 0, 0, 0, 0, 0),
                        new AnimationState.Walk(0, 0, 0),
                        new AnimationState.Vec3(0, 0, 0),
                        new AnimationState.Vec3(0, 0, 0),
                        new AnimationState.VehicleAngles(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        List.of());
        return new AnimationState(
                identity,
                tick,
                0,
                tick * 256,
                placement,
                vanilla,
                base.stance(),
                new AnimationState.Equipment(
                        base.equipment().equipmentRef(),
                        (AnimationResources.ItemRef)
                                com.lunares.blockfield.oracle.FxClientCases.definition(
                                                identity, null)
                                        .ref(),
                        base.equipment().offHandRef(),
                        base.equipment().useItemRef()),
                null,
                base.lr(),
                graph,
                com.lunares.blockfield.common.combat.ParCoolCheckpoints.neutral(
                        identity.resourceGeneration(), identity.targetEpoch()),
                new AnimationState.Create(false, null, 0, 0, AnchorClass.CANONICAL_TICK),
                base.sbw(),
                // U7c: every S[t] closes its journal with the ParCool boundary marker.
                List.of(
                        com.lunares.blockfield.common.combat.ParCoolCheckpoints.marker(
                                tick,
                                tick * 256,
                                new AnimationResources.InputRef(
                                        identity.resourceGeneration(),
                                        identity.targetEpoch(),
                                        tick,
                                        AnimationResources.Digest.hash(
                                                AnimationSchema.encode(placement))))));
    }

    private static AnimationEvent.ObserverSeed seed(AnimationState checkpoint, long id) {
        var identity = checkpoint.identity();
        return new AnimationEvent.ObserverSeed(
                identity,
                new AnimationResources.SeedRef(0, identity.targetEpoch(), id, digest(id)),
                null,
                checkpoint,
                checkpoint.graph(),
                checkpoint.serverTick(),
                checkpoint.eventWatermark(),
                checkpoint.eventWatermark(),
                List.of(),
                List.of(),
                ObserverReducer.DIGEST);
    }

    /** A TaCZ once (fire) capsule on layer 95 with its SELECTED fragment, payload unwrapped. */
    private static AnimationEvent capsule(
            PaGraph.Catalog catalog,
            AnimationState.Identity identity,
            long tick,
            long sequence,
            long predecessor,
            long acceptedNs) {
        return capsule(
                catalog,
                identity,
                tick,
                sequence,
                predecessor,
                acceptedNs,
                LayerTag.PA_95,
                clip,
                NativeFamily.TACZ,
                EventKind.TACZ_FIRE,
                StartKind.NEW);
    }

    /**
     * One PA capsule, payload unwrapped: a once install of {@code once} on {@code layer} (its
     * SELECTED fragment), or a stop fade when {@code once} is null.
     */
    static AnimationEvent capsule(
            PaGraph.Catalog catalog,
            AnimationState.Identity identity,
            long tick,
            long sequence,
            long predecessor,
            long acceptedNs,
            LayerTag layer,
            KeyframeAnimation once,
            NativeFamily family,
            EventKind kind,
            StartKind startKind) {
        var author = PaGraph.Stack.create(catalog, POLICY, PaGraphNativeCases.SOURCES, winner, 1);
        if (once != null) author.once(layer, once, false, sequence);
        var patch =
                new AnimationEvent.Patch(
                        layer,
                        startKind,
                        once == null ? Outcome.STOPPED : Outcome.SELECTED,
                        once == null
                                ? new AnimationGraphState(
                                        List.of(
                                                new AnimationGraphState.Root(
                                                        AnimationGraphState.priority(layer),
                                                        layer,
                                                        null)),
                                        List.of())
                                : author.fragment(layer),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        byte[] marker = "native.unwrapped".getBytes(StandardCharsets.UTF_8);
        var hash = AnimationResources.Digest.hash(marker);
        var payload = new AnimationResources.DefinitionRef(0, 0, hash.a() & Long.MAX_VALUE, hash);
        return new AnimationEvent(
                identity,
                new AnimationEvent.EventId(tick, sequence, NativeStage.INTER_TICK_ACCEPT),
                predecessor,
                family,
                kind,
                NativeStage.END_COMMIT,
                acceptedNs,
                acceptedNs + 2 * MS,
                new AnimationResources.SeedRef(0, identity.targetEpoch(), tick - 1, digest(0)),
                patch,
                List.of(
                        new AnimationResources.Definition(
                                payload, new AnimationResources.Bytes(marker), List.of())),
                new AnimationEvent.NativePayload(
                        payload, new AnimationResources.Bytes(new byte[0])));
    }

    /** The server's Create enter/exit capsule shape (AnimSyncCapture.create), payload unwrapped. */
    static AnimationEvent create(
            AnimationState.Identity identity,
            long tick,
            long sequence,
            long predecessor,
            long acceptedNs,
            boolean enter) {
        var membership =
                enter
                        ? new AnimationState.Create(
                                true,
                                new com.lunares.blockfield.common.combat.AnimationStartReport
                                        .StartKey(sequence, 1),
                                tick,
                                0,
                                AnchorClass.OBSERVER_APPLY)
                        : new AnimationState.Create(false, null, 0, 0, AnchorClass.CANONICAL_TICK);
        var patch =
                new AnimationEvent.Patch(
                        LayerTag.CREATE_HANGING,
                        enter ? StartKind.NEW : StartKind.STOP_FADE,
                        enter ? Outcome.SELECTED : Outcome.STOPPED,
                        new AnimationGraphState(
                                List.of(
                                        new AnimationGraphState.Root(
                                                0, LayerTag.CREATE_HANGING, null)),
                                List.of()),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        membership,
                        null,
                        null,
                        null);
        byte[] marker = "native.unwrapped".getBytes(StandardCharsets.UTF_8);
        var hash = AnimationResources.Digest.hash(marker);
        var payload = new AnimationResources.DefinitionRef(0, 0, hash.a() & Long.MAX_VALUE, hash);
        return new AnimationEvent(
                identity,
                new AnimationEvent.EventId(tick, sequence, NativeStage.INTER_TICK_ACCEPT),
                predecessor,
                NativeFamily.CREATE,
                enter ? EventKind.CREATE_ENTER : EventKind.CREATE_EXIT,
                NativeStage.END_COMMIT,
                acceptedNs,
                acceptedNs + 2 * MS,
                new AnimationResources.SeedRef(0, identity.targetEpoch(), tick - 1, digest(0)),
                patch,
                List.of(
                        new AnimationResources.Definition(
                                payload, new AnimationResources.Bytes(marker), List.of())),
                new AnimationEvent.NativePayload(
                        payload, new AnimationResources.Bytes(new byte[0])));
    }

    /** Receive: the capsule's bytes through the registered codec, as the client decodes them. */
    private static AnimationEvent decode(AnimationEvent event) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AnimationEventS2CPacket.CODEC.encode(buf, new AnimationEventS2CPacket(List.of(event)));
            return AnimationEventS2CPacket.CODEC.decode(buf).events().getFirst();
        } finally {
            buf.release();
        }
    }

    private static AnimationResources.Digest digest(long value) {
        return new AnimationResources.Digest(value, 1, 2, 3);
    }

    private static Time time(double at) {
        long tick = (long) Math.floor(at);
        return new Time(tick, (int) Math.floor((at - tick) * 65536));
    }

    // -- native side ---------------------------------------------------------------------------

    /**
     * The client singleton the transformed model path consults (TaCZ's keeping-item renderer, the
     * global delta tracker); no Minecraft constructor runs.
     */
    static org.mockito.MockedStatic<net.minecraft.client.Minecraft> minecraft() {
        var singleton = mockStatic(net.minecraft.client.Minecraft.class);
        var minecraft = mock(net.minecraft.client.Minecraft.class);
        var dispatcher = mock(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class);
        var hands = mock(net.minecraft.client.renderer.ItemInHandRenderer.class);
        doReturn(ItemStack.EMPTY)
                .when((com.tacz.guns.api.client.other.KeepingItemRenderer) hands)
                .getCurrentItem();
        doReturn(hands).when(dispatcher).getItemInHandRenderer();
        doReturn(dispatcher).when(minecraft).getEntityRenderDispatcher();
        var timer = mock(net.minecraft.client.DeltaTracker.class);
        doReturn(timer).when(minecraft).getTimer();
        doReturn(.5f).when(timer).getGameTimeDeltaPartialTick(anyBoolean());
        singleton.when(net.minecraft.client.Minecraft::getInstance).thenReturn(minecraft);
        return singleton;
    }

    /** TaCZ playOnceAnimation's native layer operation on an undriven player. */
    private static void nativeInstall(ModifierLayer<IAnimation> layer, KeyframeAnimation clip) {
        layer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(
                        PaGraph.TACZ_FADE, dev.kosmx.playerAnim.core.util.Ease.INOUTSINE),
                new KeyframeAnimationPlayer(clip.copy()),
                false);
    }

    /** Transformed PlayerModel preparation and setup at {@code fraction}; six parts' raw bits. */
    static int[] model(AbstractClientPlayer player, float fraction) {
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        var root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        var model = new PlayerModel<AbstractClientPlayer>(root, false);
        ((IAnimatedPlayer) player).playerAnimator_getAnimation().setTickDelta(fraction);
        // A ParCool-driven remote renders inside its invocation scratch, as the production
        // EntityRenderDispatcher scope opens it (U7c ParCoolRenderScopeMixin).
        try (var scope =
                        com.lunares.blockfield.client.animation.RemoteParCoolPresentation.driven(
                                        player)
                                ? com.lunares.blockfield.client.animation.RemoteParCoolPresentation
                                        .scope(player)
                                : null;
                var inputs = DrivenPresentation.scope(player)) {
            model.prepareMobModel(player, 0, 0, fraction);
            model.setupAnim(player, 0, 0, fraction, 0, 0);
        }
        var result = new int[36];
        int i = 0;
        for (var part :
                List.of(
                        model.head,
                        model.body,
                        model.leftArm,
                        model.rightArm,
                        model.leftLeg,
                        model.rightLeg))
            for (float value :
                    new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot}) {
                assertTrue(Float.isFinite(value), "nonfinite model result");
                result[i++] = Float.floatToRawIntBits(value);
            }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> layer(AbstractClientPlayer player, LayerTag tag)
            throws Exception {
        var field = AnimationStack.class.getDeclaredField("layers");
        field.setAccessible(true);
        for (var entry :
                (List<dev.kosmx.playerAnim.core.util.Pair<Integer, IAnimation>>)
                        field.get(((IAnimatedPlayer) player).getAnimationStack()))
            if (entry.getLeft() == AnimationGraphState.priority(tag))
                return (ModifierLayer<IAnimation>) entry.getRight();
        throw new AssertionError("no native layer " + tag);
    }

    private static LayerTag rotationWinner(AbstractClientPlayer player) throws Exception {
        var rotation =
                ((IAnimatedPlayer) player).playerAnimator_getAnimation(RemoteLrCompat.ROTATION);
        return rotation == layer(player, LayerTag.PA_42) ? LayerTag.PA_42 : LayerTag.PA_96;
    }

    /** A factory-registered remote player (PA mixin fields bound, entity data writes stubbed). */
    static RemotePlayer player(int id) throws Exception {
        var player = mock(RemotePlayer.class, CALLS_REAL_METHODS);
        var stack = new AnimationStack();
        var data = new HashMap<ResourceLocation, IAnimation>();
        int bound = 0;
        for (Field field : Player.class.getDeclaredFields()) {
            if (field.getType() == AnimationStack.class) {
                field.setAccessible(true);
                field.set(player, stack);
                bound++;
            } else if (field.getType() == AnimationApplier.class) {
                field.setAccessible(true);
                field.set(player, new AnimationApplier(stack));
                bound++;
            } else if (field.getName().equals("modAnimationData")) {
                field.setAccessible(true);
                field.set(player, data);
                bound++;
            }
        }
        assertEquals(3, bound, "PA player mixin fields");
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.prepareAnimations(player, stack, data);
        var walk =
                com.lunares.blockfield.oracle.OracleReflection.field(
                        net.minecraft.world.entity.LivingEntity.class,
                        "walkAnimation",
                        "Lnet/minecraft/world/entity/WalkAnimationState;");
        walk.setAccessible(true);
        walk.set(player, new net.minecraft.world.entity.WalkAnimationState());
        doReturn(id).when(player).getId();
        doReturn(ItemStack.EMPTY).when(player).getMainHandItem();
        doReturn(ItemStack.EMPTY).when(player).getOffhandItem();
        doReturn(ItemStack.EMPTY).when(player).getItemBySlot(any());
        doReturn(net.minecraft.world.entity.HumanoidArm.RIGHT).when(player).getMainArm();
        doReturn(false).when(player).isBaby();
        doReturn(false).when(player).isSpectator();
        doReturn(false).when(player).isUsingItem();
        doReturn(net.minecraft.world.InteractionHand.MAIN_HAND).when(player).getUsedItemHand();
        doReturn(ItemStack.EMPTY).when(player).getUseItem();
        doReturn(false).when(player).isPassenger();
        doReturn(false).when(player).isFallFlying();
        doReturn(false).when(player).isVisuallySwimming();
        doReturn(false).when(player).isSwimming();
        doReturn(false).when(player).isAutoSpinAttack();
        doReturn(false).when(player).isCrouching();
        doReturn(net.minecraft.world.entity.Pose.STANDING).when(player).getPose();
        doReturn(0).when(player).getFallFlyingTicks();
        doReturn(0f).when(player).getSwimAmount(anyFloat());
        doReturn(0f).when(player).getAttackAnim(anyFloat());
        // Entity data writes need a constructed entity; the oracle checks PA timing only.
        doNothing().when(player).setPose(any());
        doNothing().when(player).setMainArm(any());
        doNothing().when(player).setSwimming(anyBoolean());
        return player;
    }

    @SuppressWarnings("unchecked")
    private static <T> T sample(Class<T> type) {
        try {
            return (T) value(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object value(java.lang.reflect.Type type) throws ReflectiveOperationException {
        if (type instanceof java.lang.reflect.ParameterizedType) return List.of();
        Class<?> c = (Class<?>) type;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == float.class) return 0f;
        if (c == double.class) return 0d;
        if (c == boolean.class) return false;
        if (c == String.class) return "fixture";
        if (c == AnimationResources.Bytes.class)
            return new AnimationResources.Bytes(new byte[] {1});
        if (c == AnimationPolicy.class) return POLICY;
        if (c == AnimationGraphState.class) return AnimationGraphState.neutral();
        // An unseated player as the server captures it (-1: no vehicle, no seat); the driven SBW
        // install check refuses a checkpoint naming a vehicle the client entity does not ride.
        if (c == AnimationState.Sbw.class)
            return new AnimationState.Sbw(
                    false,
                    false,
                    null,
                    -1,
                    -1,
                    -1,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    1,
                    Visibility.VISIBLE);
        if (c.isEnum()) return c.getEnumConstants()[0];
        if (c.isInterface()) return value(c.getPermittedSubclasses()[0]);
        var fields = c.getRecordComponents();
        var args = new Object[fields.length];
        var types = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType();
            args[i] =
                    fields[i].isAnnotationPresent(AnimationSchema.Nullable.class)
                            ? null
                            : value(fields[i].getGenericType());
        }
        return c.getConstructor(types).newInstance(args);
    }
}
