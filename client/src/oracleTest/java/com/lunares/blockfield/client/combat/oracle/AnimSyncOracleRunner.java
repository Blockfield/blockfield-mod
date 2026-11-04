package com.lunares.blockfield.client.combat.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.opentest4j.TestAbortedException;

/**
 * Fixture runner for anim-sync native oracles (ADR4 sections 8-10, U10 slice).
 *
 * <p>Every fixture executes the real pinned native classes WITH the production mixin transformation
 * applied: the oracle task boots the production mod list through Fabric Loader's Knot (loader
 * 0.19.3, the same version the production pack runs) and invokes fixtures with the transformed Knot
 * class loader. No live game, stand or desktop is started.
 *
 * <p>{@link #run} refuses to execute unless the transformed marker is set (see {@link
 * TransformedOracleSession}); a fixture that somehow runs on a plain JVM fails loudly instead of
 * producing an untransformed fragment. Every fragment records {@code mixinsAppliedInJvm} (probed,
 * not hardcoded), the loaded mod list with versions/sha256 and the required mixin configs.
 * Fragments distinguish {@code pass}, {@code fail} and {@code unavailable} (aborted assumption). A
 * fallback comparison is never equality evidence; label it {@code unavailable} and keep it out of
 * equality statistics.
 *
 * <p>Each run writes one machine-readable fragment JSON under {@code
 * build/anim-sync-oracle/fragments/} (override with {@code -Dblockfield.test.oracleDir=...}); U10
 * aggregation merges fragments into the unit result manifest every section-10 gate requires.
 */
public final class AnimSyncOracleRunner {
    static final int MANIFEST_SCHEMA_VERSION = 2;
    static final String UNIT = "u10b";

    /** Set by {@link TransformedOracleSession} once Knot has applied production mixins. */
    static final String TRANSFORMED_MARKER = "blockfield.oracle.transformed";

    /** Fixture body returning JSON-serializable measurements for the manifest fragment. */
    public interface FixtureBody {
        Map<String, Object> run() throws Exception;
    }

    /** Provenance collected inside the transformed JVM; attached to every fragment. */
    public record Provenance(
            List<Map<String, Object>> mods,
            List<Map<String, Object>> mixinConfigs,
            List<Map<String, Object>> modelTargets) {}

    private static volatile Provenance provenance = new Provenance(List.of(), List.of(), List.of());

    private static final Map<String, String> DIGEST_CACHE = new ConcurrentHashMap<>();

    private AnimSyncOracleRunner() {}

    static void installProvenance(Provenance value) {
        provenance = value;
    }

    /**
     * Runs one fixture, writes its manifest fragment, and returns the measurements. A failed body
     * still writes a {@code fail} fragment before the error propagates, so the run fails AND the
     * manifest explains why. An aborted body (unsupported assumption) writes {@code unavailable}.
     */
    public static Map<String, Object> run(String family, String fixtureId, FixtureBody body) {
        AnimSyncFixtureRegistry.requireKnownFamily(family);
        AnimSyncFixtureRegistry.requireFixtureId(fixtureId);
        requireTransformed();
        requireProductionClasspath();
        if (!mixinsAppliedInJvm())
            throw new AssertionError(
                    "production mixins are not applied in this JVM: refusing to write"
                            + " an untransformed fragment for "
                            + family
                            + "."
                            + fixtureId);
        long start = System.nanoTime();
        try {
            Map<String, Object> measurements = body.run();
            writeFragment(family, fixtureId, "pass", "", elapsedMs(start), measurements);
            return measurements;
        } catch (TestAbortedException aborted) {
            writeFragment(
                    family,
                    fixtureId,
                    "unavailable",
                    String.valueOf(aborted.getMessage()),
                    elapsedMs(start),
                    Map.of());
            throw aborted;
        } catch (AssertionError | Exception failure) {
            writeFragment(
                    family, fixtureId, "fail", failure.toString(), elapsedMs(start), Map.of());
            throw failure instanceof AssertionError error ? error : new AssertionError(failure);
        }
    }

    static void requireTransformed() {
        if (!Boolean.getBoolean(TRANSFORMED_MARKER))
            throw new AssertionError(
                    "oracle fixtures must run inside the transformed Knot JVM"
                            + " (TransformedOracleSession was not reached): refusing untransformed execution");
    }

    static void requireProductionClasspath() {
        for (var name :
                List.of(
                        net.minecraft.client.Minecraft.class.getName(),
                        net.minecraft.server.dedicated.DedicatedServer.class.getName())) {
            try {
                Class.forName(name, false, AnimSyncOracleRunner.class.getClassLoader());
            } catch (ClassNotFoundException missing) {
                throw new AssertionError(
                        "oracle classpath is missing "
                                + name
                                + ": fixtures must run with client AND dedicated-server"
                                + " availability as in the real game",
                        missing);
            }
        }
        if (AnimSyncOracleRunner.class.getResource("/blockfield-client.mixins.json") == null)
            throw new AssertionError(
                    "production mixin config blockfield-client.mixins.json is not on the"
                            + " test classpath");
    }

    /** True once Knot has applied the production mixins in this JVM. */
    public static boolean mixinsAppliedInJvm() {
        try {
            Class<?> living =
                    Class.forName(
                            net.minecraft.world.entity.LivingEntity.class.getName(),
                            false,
                            AnimSyncOracleRunner.class.getClassLoader());
            living.getDeclaredMethod("blockfield$clearInterpolation");
            return true;
        } catch (NoSuchMethodException | ClassNotFoundException expected) {
            return false;
        }
    }

    private static long elapsedMs(long startNs) {
        return Math.max(0, (System.nanoTime() - startNs) / 1_000_000);
    }

    private static void writeFragment(
            String family,
            String fixtureId,
            String status,
            String message,
            long durationMs,
            Map<String, Object> measurements) {
        var fragment = new LinkedHashMap<String, Object>();
        fragment.put("schemaVersion", MANIFEST_SCHEMA_VERSION);
        fragment.put("unit", UNIT);
        fragment.put("family", family);
        fragment.put("fixture", fixtureId);
        fragment.put("status", status);
        fragment.put("message", message);
        fragment.put("durationMs", durationMs);
        fragment.put("generatedAt", Instant.now().toString());
        fragment.put("runtime", com.lunares.blockfield.oracle.OracleProvenance.runtime());
        var classpath = new LinkedHashMap<String, Object>();
        classpath.put("mixinsAppliedInJvm", mixinsAppliedInJvm());
        classpath.put(
                "productionMixinConfigPresent",
                AnimSyncOracleRunner.class.getResource("/blockfield-client.mixins.json") != null);
        classpath.put("clientAndDedicatedServerAvailable", true);
        classpath.put(
                "minecraftMerged",
                codeSourceDigest(net.minecraft.world.entity.LivingEntity.class.getName()));
        classpath.put("pinnedJars", pinnedJarDigests());
        fragment.put("classpath", classpath);
        Provenance seen = provenance;
        fragment.put("mods", seen.mods());
        fragment.put("mixinConfigs", seen.mixinConfigs());
        fragment.put("modelTargets", seen.modelTargets());
        fragment.put(
                "originalArtifacts",
                com.lunares.blockfield.oracle.OracleProvenance.input(
                        "blockfield.oracle.artifacts"));
        fragment.put(
                "unavailable",
                com.lunares.blockfield.oracle.OracleProvenance.input(
                        "blockfield.oracle.unavailable"));
        fragment.put(
                "appliedTransformations",
                com.lunares.blockfield.oracle.OracleTransformAudit.applied());
        fragment.put("measurements", measurements);
        Path dir =
                Paths.get(
                        System.getProperty(
                                "blockfield.test.oracleDir", "build/anim-sync-oracle/fragments"));
        try {
            Files.createDirectories(dir);
            Files.write(
                    dir.resolve(family + "." + fixtureId + ".json"),
                    toJson(fragment).getBytes(StandardCharsets.UTF_8));
        } catch (IOException io) {
            throw new UncheckedIOException("cannot write oracle manifest fragment", io);
        }
    }

    private static Map<String, Object> codeSourceDigest(String className) {
        try {
            var location =
                    Class.forName(className).getProtectionDomain().getCodeSource().getLocation();
            var info = new LinkedHashMap<String, Object>();
            info.put("path", String.valueOf(location));
            info.put("sha256", digestOf(Path.of(location.toURI())));
            return info;
        } catch (Exception failed) {
            return Map.of("error", failed.toString());
        }
    }

    private static List<Map<String, Object>> pinnedJarDigests() {
        var jars = new ArrayList<Map<String, Object>>();
        for (var key :
                List.of(
                        "blockfield.test.sbwJar",
                        "blockfield.test.taczJar",
                        "blockfield.test.lrJar",
                        "blockfield.test.raknetifyJar",
                        "blockfield.test.raknetifyVelocityJar")) {
            String path = System.getProperty(key);
            if (path == null) continue;
            var info = new LinkedHashMap<String, Object>();
            info.put("artifact", key);
            info.put("path", path);
            try {
                info.put("sha256", digestOf(Paths.get(path)));
            } catch (Exception failed) {
                info.put("error", failed.toString());
            }
            jars.add(info);
        }
        return jars;
    }

    static String digestOf(Path path) throws Exception {
        return DIGEST_CACHE.computeIfAbsent(
                path.toString(),
                ignored -> {
                    try {
                        if (!Files.isRegularFile(path)) return "not-a-file";
                        var sha = MessageDigest.getInstance("SHA-256");
                        try (var in = Files.newInputStream(path)) {
                            byte[] chunk = new byte[1 << 20];
                            int read;
                            while ((read = in.read(chunk)) >= 0) sha.update(chunk, 0, read);
                        }
                        return HexFormat.of().formatHex(sha.digest());
                    } catch (Exception failed) {
                        return "error:" + failed;
                    }
                });
    }

    static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();
            map.forEach(
                    (key, entry) -> parts.add(quote(String.valueOf(key)) + ":" + toJson(entry)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof List<?> list) {
            var parts = new ArrayList<String>();
            list.forEach(entry -> parts.add(toJson(entry)));
            return "[" + String.join(",", parts) + "]";
        }
        if (value instanceof int[] ints) {
            var parts = new ArrayList<String>();
            for (int i : ints) parts.add(Integer.toString(i));
            return "[" + String.join(",", parts) + "]";
        }
        if (value instanceof float[] floats) {
            var parts = new ArrayList<String>();
            for (float f : floats) parts.add(Float.toString(f));
            return "[" + String.join(",", parts) + "]";
        }
        return quote(value.toString());
    }

    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
