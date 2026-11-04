package com.lunares.blockfield.client.combat.oracle;

import com.google.gson.GsonBuilder;
import com.lunares.blockfield.client.combat.ClientAnimationAssets;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.client.combat.SnapshotResolver;
import com.lunares.blockfield.client.combat.SnapshotTransform;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.lunares.blockfield.common.combat.AnimationTiming;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.ObserverReducer;
import com.lunares.blockfield.oracle.MixedRendererCases;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.server.Bootstrap;

/**
 * Animation-sync client CPU benchmark (UOPT): N driven remote players in the transformed production
 * client JVM (every pack mixin, the U10 mixed-renderer environment), each on its own mixed case
 * (vanilla, TaCZ fire, Create hanging, ParCool, LR, medkit), every main frame at 144 FPS: the
 * driven presentation of each remote ({@link SnapshotResolver#exact}, {@link
 * ClientCombatScene#write}, {@link DrivenPresentation#install}) and, report-only, one MAIN render
 * pass through the production renderer. The observer reduction a frame presents is advanced once
 * per new bucket (the journal's work, measured by the server benchmark's observer emulation on the
 * same common reducer) and not counted here. Run with {@code -PanimSyncBench} on {@code
 * :client:oracleTest}; see {@code docs/review/anim-sync-perf.md}.
 */
final class DrivenPresentationBench {
    private DrivenPresentationBench() {}

    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final float AMBIENT = .37f;

    /**
     * One driven remote: the presented player (a real {@code RemotePlayer} when it can be
     * constructed here, else the U10 test double), the double the report-only render pass draws,
     * its case, decoded frames, assets and the presentation per bucket.
     */
    private record Remote(
            RemotePlayer player,
            RemotePlayer rendered,
            MixedRendererCases.Case value,
            TreeMap<Long, SnapshotTransform> frames,
            ClientAnimationAssets.Generation assets,
            Map<Long, ObserverReducer.Result> buckets) {}

    static int run() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            SceneTimingOracleFixture.bootstrap();
            var spec = System.getProperty("blockfield.animSync.bench", "all");
            int seconds = Integer.getInteger("blockfield.animSync.benchSeconds", 4);
            var sizes = new ArrayList<Integer>();
            for (String cohort : (spec.equals("all") ? "2,10,30" : spec).split(","))
                sizes.add(Integer.parseInt(cohort.split(":")[0]));
            var results = new LinkedHashMap<String, Object>();
            results.put("fps", 144);
            results.put("secondsPerCohort", seconds);
            var cohorts = new LinkedHashMap<String, Object>();
            for (int size : sizes) {
                System.out.println("anim-sync client bench cohort " + size);
                cohorts.put(size + "-remotes", cohort(size, seconds));
            }
            results.put("cohorts", cohorts);
            var out =
                    Path.of(System.getProperty("blockfield.animSync.benchOut", "anim-sync-bench"));
            Files.createDirectories(out);
            Files.writeString(
                    out.resolve("client.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results));
            Files.writeString(out.resolve("client.md"), markdown(cohorts));
            System.out.println(markdown(cohorts));
            return 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            return 1;
        }
    }

    private static Map<String, Object> cohort(int size, int seconds) throws Exception {
        var cases = new ArrayList<MixedRendererCases.Case>();
        for (var value : MixedRendererCases.cases())
            if (value.unavailable() == null) cases.add(value);
        try (var env = new MixedRendererOracleFixture.Env()) {
            var remotes = new ArrayList<Remote>();
            for (int i = 0; i < size; i++) {
                var value = cases.get(i % cases.size());
                var frames = new TreeMap<Long, SnapshotTransform>();
                value.states()
                        .forEach(
                                (tick, state) ->
                                        frames.put(
                                                tick,
                                                SnapshotTransform.exact(
                                                        tick,
                                                        value.identity(),
                                                        0,
                                                        state.placement(),
                                                        state)));
                // The same generation the mixed-renderer fixture installs for this case.
                var assets =
                        new ClientAnimationAssets.Generation(
                                0,
                                MixedRendererCases.POLICY,
                                value.tacz() != null
                                        ? value.tacz()
                                        : com.lunares.blockfield.oracle.FxPoseCases.taczAssets(
                                                value),
                                value.lr());
                ClientAnimationAssets.install(assets);
                var rendered =
                        MixedRendererOracleFixture.player(
                                MixedRendererCases.ENTITY + i, env.level, value.slim());
                fastSetPos(rendered);
                // The fixture's remote doubles carry no profile; a native fallback names them.
                profile(rendered, MixedRendererCases.ENTITY + i);
                var player = real(env, MixedRendererCases.ENTITY + 1000 + i);
                if (player == null) player = rendered;
                else realPlayers++;
                remotes.add(new Remote(player, rendered, value, frames, assets, new HashMap<>()));
            }
            var schedule = MixedRendererCases.frames(144);
            int frames = 144 * seconds;
            int warmup = Math.min(frames, 288);
            var install = new Series();
            var installBytes = new Series();
            var render = new Series();
            var perFrame = new Series();
            var perFrameBytes = new Series();
            var unavailable = new java.util.TreeMap<String, Integer>();
            var recording = new jdk.jfr.Recording();
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            for (int f = 0; f < warmup + frames; f++) {
                boolean measure = f >= warmup;
                if (f == warmup) recording.start();
                var frame = schedule.get(f % schedule.size());
                long frameCpu = 0, frameBytes = 0;
                BodyPartHitboxRenderer.frameEntry();
                for (var remote : remotes) {
                    var reduced =
                            remote.buckets()
                                    .computeIfAbsent(
                                            frame.a().tick(),
                                            tick -> remote.value().presentation().apply(frame.a()));
                    long bytes0 = MX.getCurrentThreadAllocatedBytes();
                    long cpu0 = MX.getCurrentThreadCpuTime();
                    String why = present(remote, remote.player(), frame.r(), frame.a(), reduced);
                    long cpu = MX.getCurrentThreadCpuTime() - cpu0;
                    long bytes = MX.getCurrentThreadAllocatedBytes() - bytes0;
                    if (why != null) unavailable.merge(why, 1, Integer::sum);
                    if (measure) {
                        install.add(cpu);
                        installBytes.add(bytes);
                        frameCpu += cpu;
                        frameBytes += bytes;
                    }
                }
                if (measure) {
                    perFrame.add(frameCpu);
                    perFrameBytes.add(frameBytes);
                }
            }
            recording.stop();
            // Report-only, after the measured phase (the doubles never sit in the presentation's
            // installed set while it is timed): the native MAIN pass through the production
            // renderer, on the U10 doubles that carry the renderer's skin and player info.
            for (int f = 0; f < Math.min(frames, 144); f++) {
                var frame = schedule.get(f % schedule.size());
                BodyPartHitboxRenderer.frameEntry();
                for (var remote : remotes) {
                    var reduced = remote.buckets().get(frame.a().tick());
                    if (reduced == null) continue;
                    present(remote, remote.rendered(), frame.r(), frame.a(), reduced);
                    env.player = remote.rendered();
                    env.camera(remote.rendered());
                    BodyPartHitboxRenderer.levelPass(true);
                    long r0 = MX.getCurrentThreadCpuTime();
                    try {
                        env.render(AMBIENT);
                    } finally {
                        BodyPartHitboxRenderer.levelPass(false);
                    }
                    render.add(MX.getCurrentThreadCpuTime() - r0);
                }
            }
            var out =
                    Path.of(System.getProperty("blockfield.animSync.benchOut", "anim-sync-bench"));
            Files.createDirectories(out);
            var file = out.resolve("client-" + size + ".jfr");
            recording.dump(file);
            recording.close();
            for (var remote : remotes) {
                DrivenPresentation.forget(remote.player());
                DrivenPresentation.forget(remote.rendered());
            }
            env.player = null;
            ClientAnimationAssets.clear();
            var result = new LinkedHashMap<String, Object>();
            result.put("remotes", size);
            result.put("realRemotePlayers", realPlayers);
            realPlayers = 0;
            result.put("frames", frames);
            result.put("cases", remotes.stream().map(r -> r.value().name()).toList());
            result.put("presentationPerRemoteCpuNanos", install.stats());
            result.put("presentationPerRemoteAllocatedBytes", installBytes.stats());
            result.put("presentationPerFrameCpuNanos", perFrame.stats());
            result.put("presentationPerFrameAllocatedBytes", perFrameBytes.stats());
            result.put("mainRenderPassPerRemoteCpuNanosReportOnly", render.stats());
            result.put("unavailable", unavailable);
            result.put("hotMethods", hot(file));
            return result;
        }
    }

    private static int realPlayers;

    /**
     * A real remote player, constructed as the client does (every constructor mixin runs), so its
     * getters/setters cost what production pays; null when this environment cannot construct one.
     */
    private static RemotePlayer real(MixedRendererOracleFixture.Env env, int id) {
        try {
            org.mockito.Mockito.doReturn(net.minecraft.core.BlockPos.ZERO)
                    .when(env.level)
                    .getSharedSpawnPos();
            org.mockito.Mockito.doReturn(0f).when(env.level).getSharedSpawnAngle();
            var uuid = new java.util.UUID(0x62656e6368L, id);
            var profile = new com.mojang.authlib.GameProfile(uuid, "b" + id);
            var player = new RemotePlayer(env.level, profile);
            player.setId(id);
            // The connection's tab-list entry (game mode, skin lookup), as a joined remote has.
            var info =
                    com.lunares.blockfield.oracle.OracleReflection.field(
                            net.minecraft.client.player.AbstractClientPlayer.class,
                            "playerInfo",
                            "Lnet/minecraft/client/multiplayer/PlayerInfo;");
            info.setAccessible(true);
            info.set(player, new net.minecraft.client.multiplayer.PlayerInfo(profile, false));
            return player;
        } catch (Throwable unavailable) {
            System.out.println("client bench: real RemotePlayer unavailable: " + unavailable);
            return null;
        }
    }

    private static void profile(RemotePlayer player, int id) throws Exception {
        var field =
                com.lunares.blockfield.oracle.OracleReflection.field(
                        net.minecraft.world.entity.player.Player.class,
                        "gameProfile",
                        "Lcom/mojang/authlib/GameProfile;");
        field.setAccessible(true);
        field.set(
                player,
                new com.mojang.authlib.GameProfile(new java.util.UUID(0x726e64L, id), "r" + id));
    }

    private static java.lang.reflect.Field position, blockPosition, bb;

    /**
     * The fixture double's {@code setPos} answer with its fields resolved once: the fixture
     * resolves them through the mapping tree on every call, a harness cost production never has
     * ({@code Entity.setPos} writes these fields).
     */
    private static void fastSetPos(RemotePlayer player) throws Exception {
        if (position == null) {
            position =
                    com.lunares.blockfield.oracle.OracleReflection.field(
                            net.minecraft.world.entity.Entity.class,
                            "position",
                            "Lnet/minecraft/world/phys/Vec3;");
            blockPosition =
                    com.lunares.blockfield.oracle.OracleReflection.field(
                            net.minecraft.world.entity.Entity.class,
                            "blockPosition",
                            "Lnet/minecraft/core/BlockPos;");
            bb =
                    com.lunares.blockfield.oracle.OracleReflection.field(
                            net.minecraft.world.entity.Entity.class,
                            "bb",
                            "Lnet/minecraft/world/phys/AABB;");
            position.setAccessible(true);
            blockPosition.setAccessible(true);
            bb.setAccessible(true);
        }
        // Entity.isRemoved is removalReason != null: never removed here, without the double
        // resolving the real method on each of the presentation's per-install sweeps.
        org.mockito.Mockito.doReturn(false).when(player).isRemoved();
        org.mockito.Mockito.doAnswer(
                        call -> {
                            double x = call.getArgument(0), y = call.getArgument(1);
                            double z = call.getArgument(2);
                            position.set(player, new net.minecraft.world.phys.Vec3(x, y, z));
                            blockPosition.set(
                                    player, net.minecraft.core.BlockPos.containing(x, y, z));
                            bb.set(
                                    player,
                                    new net.minecraft.world.phys.AABB(
                                            x - .3, y, z - .3, x + .3, y + 1.8, z + .3));
                            return null;
                        })
                .when(player)
                .setPos(
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble());
    }

    /** The driven presentation of one remote for one main frame, as ClientCombatScene does it. */
    private static String present(
            Remote remote, RemotePlayer player, Time r, Time a, ObserverReducer.Result reduced) {
        var placement = SnapshotResolver.exact(remote.frames(), r);
        ClientCombatScene.write(player, placement);
        var end = AnimationTiming.endpoint(a);
        var record = remote.frames().get(end.tick());
        return DrivenPresentation.install(
                player,
                placement.before(),
                (float) placement.fraction(),
                record,
                end.partial(),
                reduced,
                remote.assets(),
                a,
                r.fraction());
    }

    /** Self-time hot methods of the presentation samples (the render pass excluded). */
    private static List<String> hot(Path file) throws Exception {
        var self = new HashMap<String, Integer>();
        int total = 0;
        for (var event : jdk.jfr.consumer.RecordingFile.readAllEvents(file)) {
            if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
            var stack = event.getStackTrace();
            if (stack == null || stack.getFrames().isEmpty()) continue;
            boolean presenting = false;
            for (var frame : stack.getFrames())
                if (frame.getMethod().getName().equals("present")
                        && frame.getMethod()
                                .getType()
                                .getName()
                                .endsWith("DrivenPresentationBench")) {
                    presenting = true;
                    break;
                }
            if (!presenting) continue;
            total++;
            var top = stack.getFrames().getFirst().getMethod();
            String type = top.getType().getName();
            self.merge(
                    type.substring(type.lastIndexOf('.') + 1) + "." + top.getName(),
                    1,
                    Integer::sum);
        }
        var rows = new ArrayList<>(self.entrySet());
        rows.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        var out = new ArrayList<String>();
        out.add("samples " + total);
        for (var row : rows.subList(0, Math.min(25, rows.size())))
            out.add(
                    String.format(
                            Locale.ROOT,
                            "%5.1f%% %s",
                            100.0 * row.getValue() / Math.max(1, total),
                            row.getKey()));
        return out;
    }

    /** Growable sample series with mean/p50/p99/max. */
    static final class Series {
        private long[] values = new long[256];
        private int size;

        void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        Map<String, Object> stats() {
            var result = new LinkedHashMap<String, Object>();
            result.put("count", size);
            if (size == 0) return result;
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            double sum = 0;
            for (long value : sorted) sum += value;
            result.put("mean", sum / size);
            result.put("p50", sorted[size / 2]);
            result.put("p99", sorted[(int) Math.min(size - 1, Math.floor(size * .99))]);
            result.put("max", sorted[size - 1]);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private static String markdown(Map<String, Object> cohorts) {
        var text = new StringBuilder();
        text.append(
                "| remotes | presentation per frame mean / p50 / p99 (µs) | per remote mean / p99"
                        + " (µs) | alloc per frame (KB) | MAIN pass per remote, report-only (µs) |\n");
        text.append("| --- | --- | --- | --- | --- |\n");
        cohorts.forEach(
                (name, value) -> {
                    var cohort = (Map<String, Object>) value;
                    var frame = (Map<String, Object>) cohort.get("presentationPerFrameCpuNanos");
                    var remote = (Map<String, Object>) cohort.get("presentationPerRemoteCpuNanos");
                    var bytes =
                            (Map<String, Object>) cohort.get("presentationPerFrameAllocatedBytes");
                    var pass =
                            (Map<String, Object>)
                                    cohort.get("mainRenderPassPerRemoteCpuNanosReportOnly");
                    text.append(
                            String.format(
                                    Locale.ROOT,
                                    "| %s | %.0f / %.0f / %.0f | %.1f / %.1f | %.1f | %.0f |%n",
                                    cohort.get("remotes"),
                                    num(frame, "mean") / 1e3,
                                    num(frame, "p50") / 1e3,
                                    num(frame, "p99") / 1e3,
                                    num(remote, "mean") / 1e3,
                                    num(remote, "p99") / 1e3,
                                    num(bytes, "mean") / 1024,
                                    num(pass, "mean") / 1e3));
                });
        return text.toString();
    }

    private static double num(Map<String, Object> stats, String key) {
        var value = stats.get(key);
        return value == null ? Double.NaN : ((Number) value).doubleValue();
    }
}
