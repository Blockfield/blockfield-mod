package com.lunares.blockfield.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Reports loaded artifacts, including Loader-selected JiJ children, without best-effort hashes. */
public final class OracleProvenance {
    private OracleProvenance() {}

    private static List<Map<String, Object>> cached;

    public static synchronized List<Map<String, Object>> mods() {
        if (cached != null) return cached;
        var rows = new ArrayList<Map<String, Object>>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", mod.getMetadata().getId());
            row.put("version", mod.getMetadata().getVersion().getFriendlyString());
            var origin = mod.getOrigin();
            row.put("originKind", origin.getKind().name());
            if (origin.getKind() == ModOrigin.Kind.PATH) {
                var files = new ArrayList<Map<String, String>>();
                for (var path : origin.getPaths()) {
                    if (Files.isDirectory(path) && mod.getMetadata().getId().equals("java"))
                        path = path.resolve("release");
                    files.add(Map.of("path", path.toString(), "sha256", digest(path)));
                }
                row.put("artifacts", files);
            } else if (origin.getKind() == ModOrigin.Kind.NESTED) {
                row.put("parentMod", origin.getParentModId());
                row.put("subLocation", origin.getParentSubLocation());
                // Loader extracts nested libraries into immutable jar roots. Hash their exact
                // bytes.
                var root = mod.getRootPaths().getFirst();
                var uri = root.toUri();
                String location = uri.toString();
                if (!location.startsWith("jar:") || !location.endsWith("!/"))
                    throw new AssertionError("Unexpected nested mod root: " + location);
                row.put(
                        "sha256",
                        digest(
                                Path.of(
                                        java.net.URI.create(
                                                location.substring(4, location.length() - 2)))));
            } else {
                row.put("builtin", true);
            }
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing(row -> row.get("id").toString()));
        cached = List.copyOf(rows);
        return cached;
    }

    /**
     * The supported-runtime matrix row of this JVM (ADR4 §8.2): JDK build, CPU, architecture and
     * the effective JOML options. Only rows actually executed are claimed.
     */
    public static Map<String, Object> runtime() {
        var out = new LinkedHashMap<String, Object>();
        out.put("javaVersion", System.getProperty("java.version", "?"));
        out.put("javaRuntimeVersion", System.getProperty("java.runtime.version", "?"));
        out.put("javaVendor", System.getProperty("java.vendor", "?"));
        out.put("javaVmVersion", System.getProperty("java.vm.version", "?"));
        out.put(
                "os",
                System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?"));
        out.put("arch", System.getProperty("os.arch", "?"));
        out.put("cpu", cpu());
        out.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        out.put("jomlFastmath", org.joml.Options.FASTMATH);
        out.put("jomlUseMathFma", org.joml.Options.USE_MATH_FMA);
        out.put("jomlSinLookup", org.joml.Options.SIN_LOOKUP);
        return out;
    }

    private static String cpu() {
        try {
            var info = Path.of("/proc/cpuinfo");
            if (Files.isReadable(info))
                for (var line : Files.readAllLines(info))
                    if (line.startsWith("model name"))
                        return line.substring(line.indexOf(':') + 1).trim();
        } catch (Exception unreadable) {
            return "unreadable:" + unreadable.getClass().getSimpleName();
        }
        var windows = System.getenv("PROCESSOR_IDENTIFIER");
        return windows == null ? "?" : windows;
    }

    public static Object input(String property) {
        try (var reader = Files.newBufferedReader(Path.of(System.getProperty(property)))) {
            return new com.google.gson.Gson().fromJson(reader, Object.class);
        } catch (Exception failure) {
            throw new AssertionError("Cannot read oracle provenance " + property, failure);
        }
    }

    private static String digest(Path path) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] bytes = new byte[65536];
                int n;
                while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError("Cannot hash loaded mod " + path, failure);
        }
    }
}
