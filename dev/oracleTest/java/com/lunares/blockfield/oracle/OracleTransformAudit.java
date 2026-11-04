package com.lunares.blockfield.oracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Observes successful production transformations without changing a config or target. */
public final class OracleTransformAudit implements IExtension {
    private static final Map<String, List<Map<String, String>>> APPLIED = new TreeMap<>();

    // Merged-handler call order per method of the six-box pipeline classes (P2-9 hook order).
    private static final Map<String, Map<String, List<String>>> HOOKS = new TreeMap<>();
    private static final java.util.Set<String> HOOK_TARGETS = new java.util.HashSet<>();
    private static final Map<String, String> CONFIGS = new TreeMap<>();
    private static final Map<String, String> CONFIG_PACKAGES = new TreeMap<>();

    public static void install() {
        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            var descriptor = mod.findPath("fabric.mod.json");
            if (descriptor.isEmpty()) continue;
            try (var reader = java.nio.file.Files.newBufferedReader(descriptor.get())) {
                var metadata = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (!metadata.has("mixins")) continue;
                for (var entry : metadata.getAsJsonArray("mixins")) {
                    String config =
                            entry.isJsonPrimitive()
                                    ? entry.getAsString()
                                    : entry.getAsJsonObject().get("config").getAsString();
                    var path = mod.findPath(config).orElseThrow();
                    try (var configReader = java.nio.file.Files.newBufferedReader(path)) {
                        var json =
                                com.google.gson.JsonParser.parseReader(configReader)
                                        .getAsJsonObject();
                        String pkg =
                                json.has("package") ? json.get("package").getAsString() + "." : "";
                        if (!pkg.isEmpty()) CONFIG_PACKAGES.put(pkg, config);
                        for (String section : List.of("mixins", "client", "server")) {
                            if (!json.has(section)) continue;
                            for (var mixin : json.getAsJsonArray(section))
                                if (!mixin.isJsonNull())
                                    CONFIGS.put(pkg + mixin.getAsString(), config);
                        }
                    }
                }
            } catch (Exception failure) {
                throw new AssertionError(
                        "Cannot audit production mixin descriptor " + mod.getMetadata().getId(),
                        failure);
            }
        }
        var mappings = net.fabricmc.loader.api.FabricLoader.getInstance().getMappingResolver();
        for (var named :
                List.of(
                        "net.minecraft.client.model.HumanoidModel",
                        "net.minecraft.client.model.PlayerModel",
                        "net.minecraft.client.renderer.entity.LivingEntityRenderer",
                        "net.minecraft.client.renderer.entity.player.PlayerRenderer",
                        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
                        "net.minecraft.client.renderer.LevelRenderer"))
            HOOK_TARGETS.add(mappings.mapClassName("named", named));
        var env = MixinEnvironment.getCurrentEnvironment();
        var transformer = (IMixinTransformer) env.getActiveTransformer();
        var extensions = (Extensions) transformer.getExtensions();
        extensions.add(new OracleTransformAudit());
        extensions.select(env);
    }

    public static synchronized List<Map<String, Object>> applied() {
        var rows = new ArrayList<Map<String, Object>>();
        APPLIED.forEach(
                (target, mixins) -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("target", target);
                    row.put("mixins", List.copyOf(mixins));
                    rows.add(row);
                });
        return rows;
    }

    /**
     * For each audited pipeline class (runtime name), method {@code name+descriptor} to the merged
     * mixin handler calls in bytecode (execution) order.
     */
    public static synchronized Map<String, Map<String, List<String>>> hookOrder() {
        var out = new TreeMap<String, Map<String, List<String>>>();
        HOOKS.forEach((target, methods) -> out.put(target, new TreeMap<>(methods)));
        return out;
    }

    public static synchronized void requireConfig(String name) {
        if (APPLIED.values().stream()
                .flatMap(List::stream)
                .noneMatch(row -> name.equals(row.get("config"))))
            throw new AssertionError(
                    "Required production config did not transform an exercised target: " + name);
    }

    private static String configFor(String mixin) {
        String declared = CONFIGS.get(mixin);
        if (declared != null) return declared;
        var matches =
                CONFIG_PACKAGES.entrySet().stream()
                        .filter(entry -> mixin.startsWith(entry.getKey()))
                        .toList();
        if (matches.size() != 1)
            throw new AssertionError(
                    "Cannot attribute plugin-selected mixin to one config: " + mixin);
        return matches.getFirst().getValue();
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {}

    @Override
    public void postApply(ITargetClassContext context) {
        String name = context.getClassNode().name.replace('/', '.');
        var mixins = new ArrayList<Map<String, String>>();
        var seen = new java.util.TreeSet<String>();
        for (var method : context.getClassNode().methods) {
            var annotations = new ArrayList<org.objectweb.asm.tree.AnnotationNode>();
            if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
            if (method.invisibleAnnotations != null)
                annotations.addAll(method.invisibleAnnotations);
            for (var annotation : annotations) {
                if (!annotation.desc.equals(
                        "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) continue;
                for (int i = 0; i < annotation.values.size(); i += 2) {
                    if (!annotation.values.get(i).equals("mixin")) continue;
                    String mixin = annotation.values.get(i + 1).toString();
                    if (seen.add(mixin))
                        mixins.add(Map.of("mixin", mixin, "config", configFor(mixin)));
                }
            }
        }
        var hooks = new TreeMap<String, List<String>>();
        if (HOOK_TARGETS.contains(name))
            for (var method : context.getClassNode().methods) {
                var calls = new ArrayList<String>();
                for (var insn : method.instructions)
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                            && call.owner.equals(context.getClassNode().name)
                            && call.name.contains("$")) calls.add(call.name);
                if (!calls.isEmpty()) hooks.put(method.name + method.desc, List.copyOf(calls));
            }
        synchronized (OracleTransformAudit.class) {
            APPLIED.put(name, List.copyOf(mixins));
            if (!hooks.isEmpty()) HOOKS.put(name, hooks);
        }
    }

    @Override
    public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}
}
