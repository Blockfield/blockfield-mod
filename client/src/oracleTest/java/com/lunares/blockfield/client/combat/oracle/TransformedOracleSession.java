package com.lunares.blockfield.client.combat.oracle;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs inside the transformed Knot class loader (loaded by {@link TransformedOracleMain} after
 * {@code Knot.init} registers the production configs). Verifies the environment, installs fragment
 * provenance and executes every registered fixture class.
 *
 * <p>Fixture classes use plain JUnit 5 annotations ({@code @BeforeAll}/{@code @Test}) so family
 * units write ordinary tests and a future loader-provided JUnit runner can adopt them unchanged;
 * this session invokes them reflectively without a JUnit engine. Registration is an explicit closed
 * list: a new fixture is one line in {@link #FIXTURES} plus its class, and nothing else can
 * silently join the exact cohort.
 */
public final class TransformedOracleSession {
    private TransformedOracleSession() {}

    /** Fixture classes executed by the client transformed oracle, in order. */
    static final List<Class<?>> FIXTURES =
            List.of(
                    AnimSyncFixtureRegistryTest.class,
                    WireReportsOracleFixture.class,
                    TaczReadClosureOracleFixture.class,
                    VanillaSetupAnimOracleFixture.class,
                    VanillaAnimSyncOracleFixture.class,
                    StanceEaseOracleFixture.class,
                    ParCoolReducerOracleFixture.class,
                    ParCoolCheckpointOracleFixture.class,
                    ParCoolProjectionOracleFixture.class,
                    ParCoolAttachmentOracleFixture.class,
                    ParCoolZiplineOracleFixture.class,
                    ParCoolYawOracleFixture.class,
                    ParCoolReadLayoutOracleFixture.class,
                    ParCoolPolicyOracleFixture.class,
                    ParCoolStagesOracleFixture.class,
                    ParCoolAcceptedOracleFixture.class,
                    ParCoolPresentationOracleFixture.class,
                    VehicleOracleFixture.class,
                    CreateHangingOracleFixture.class,
                    BlockfieldMixinOracleFixture.class,
                    ThirdPartyTransformOracleFixture.class,
                    PaGraphOracleFixture.class,
                    PaObserverOracleFixture.class,
                    LrTransitionOracleFixture.class,
                    TaczRemoteOracleFixture.class,
                    LrSelectorOracleFixture.class,
                    AnimationPolicyOracleFixture.class,
                    AnimationResourcesOracleFixture.class,
                    SceneTimingOracleFixture.class,
                    FrameEntryOracleFixture.class,
                    ExactSceneOracleFixture.class,
                    FxClientWireOracleFixture.class,
                    AnimationWireOracleFixture.class,
                    DrivenInputsOracleFixture.class,
                    NativeCapsuleOracleFixture.class,
                    MixedRendererOracleFixture.class,
                    LrMixedRendererOracleFixture.class,
                    ModelTargetAuditFixture.class);

    /** Called reflectively from {@link TransformedOracleMain}; returns the failure count. */
    public static int run() {
        com.lunares.blockfield.oracle.OracleTransformAudit.install();
        System.setProperty(AnimSyncOracleRunner.TRANSFORMED_MARKER, "true");
        var failures = new ArrayList<String>();
        try {
            checkEnvironment();
            AnimSyncOracleRunner.installProvenance(collectProvenance());
        } catch (AssertionError fatal) {
            System.err.println("transformed oracle environment check failed: " + fatal);
            fatal.printStackTrace(System.err);
            return 99;
        }
        // -PanimSyncBench: the CPU benchmark instead of the fixtures (never part of the gate).
        if (System.getProperty("blockfield.animSync.bench") != null)
            return DrivenPresentationBench.run();
        // Iteration aid only: -PoracleOnly=Name1,Name2 runs a subset; the gate runs every fixture.
        var only = System.getProperty("blockfield.oracle.only", "");
        requireKnownOnly(only);
        for (var fixture : FIXTURES) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(fixture.getSimpleName())) {
                System.out.println(
                        "transformed oracle skipped (oracleOnly): " + fixture.getSimpleName());
                continue;
            }
            try {
                runBeforeAll(fixture);
            } catch (Exception failure) {
                failures.add(fixture.getSimpleName() + "#BeforeAll");
                failure.printStackTrace(System.err);
                continue;
            }
            for (var method : fixture.getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) == null) continue;
                String label = fixture.getSimpleName() + "#" + method.getName();
                try {
                    Object instance =
                            Modifier.isStatic(method.getModifiers())
                                    ? null
                                    : fixture.getDeclaredConstructor().newInstance();
                    method.setAccessible(true);
                    method.invoke(instance);
                    System.out.println("transformed oracle pass: " + label);
                } catch (Exception failure) {
                    failures.add(label);
                    System.err.println("transformed oracle FAIL: " + label + ": " + failure);
                    var cause = failure.getCause() != null ? failure.getCause() : failure;
                    cause.printStackTrace(System.err);
                }
            }
        }
        return failures.size();
    }

    /**
     * Fail closed on a misspelled subset: without this, a typo in {@code blockfield.oracle.only}
     * silently runs zero fixtures and passes.
     */
    static void requireKnownOnly(String only) {
        if (only.isEmpty()) return;
        var known = new ArrayList<String>();
        for (var fixture : FIXTURES) known.add(fixture.getSimpleName());
        for (var name : only.split(",")) {
            if (!known.contains(name))
                throw new AssertionError("unknown fixture in blockfield.oracle.only: " + name);
        }
    }

    private static void runBeforeAll(Class<?> fixture) throws Exception {
        for (var method : fixture.getDeclaredMethods()) {
            if (method.getAnnotation(BeforeAll.class) == null) continue;
            method.setAccessible(true);
            method.invoke(null);
        }
    }

    static void checkEnvironment() {
        if (org.joml.Options.FASTMATH || org.joml.Options.USE_MATH_FMA)
            throw new AssertionError(
                    "Oracle requires joml.fastmath=false and joml.useMathFma=false");
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT)
            throw new AssertionError(
                    "client oracle runs in the CLIENT environment, got: "
                            + FabricLoader.getInstance().getEnvironmentType());
        var loaded = new LinkedHashMap<String, String>();
        for (var mod : FabricLoader.getInstance().getAllMods())
            loaded.putIfAbsent(
                    mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        for (var id : TransformedOracleMain.requiredModIds())
            if (!loaded.containsKey(id))
                throw new AssertionError(
                        "required oracle mod is not loaded: "
                                + id
                                + "; loaded: "
                                + loaded.keySet());
        ClassLoader knot = TransformedOracleSession.class.getClassLoader();
        for (var config : TransformedOracleMain.requiredMixinConfigs())
            if (knot.getResource(config) == null)
                throw new AssertionError(
                        "required production mixin config is not visible in the transformed JVM: "
                                + config);
        if (!AnimSyncOracleRunner.mixinsAppliedInJvm())
            throw new AssertionError(
                    "Knot booted but the Blockfield driven-walk mixin did not apply:"
                            + " LivingEntity.blockfield$clearInterpolation is missing");
    }

    static AnimSyncOracleRunner.Provenance collectProvenance() {
        var mods = com.lunares.blockfield.oracle.OracleProvenance.mods();
        ClassLoader knot = TransformedOracleSession.class.getClassLoader();
        var configs = new ArrayList<Map<String, Object>>();
        for (var entry : modMixinConfigs(knot).entrySet()) {
            for (var config : entry.getValue()) {
                var info = new LinkedHashMap<String, Object>();
                info.put("modId", entry.getKey());
                info.put("config", config);
                info.put("resourcePresent", knot.getResource(config) != null);
                configs.add(info);
            }
        }
        configs.sort(
                (a, b) ->
                        (a.get("modId") + "/" + a.get("config"))
                                .compareTo(b.get("modId") + "/" + b.get("config")));
        return new AnimSyncOracleRunner.Provenance(
                List.copyOf(mods), List.copyOf(configs), ModelTargetAuditFixture.scan(knot));
    }

    /**
     * Every {@code fabric.mod.json} visible to the transformed class loader, mapped to the mixin
     * configs it declares. Nested (JiJ) jars surface their own descriptors through the Knot class
     * loader, so TaCZ-bundled CCA and friends are included automatically.
     */
    static Map<String, List<String>> modMixinConfigs(ClassLoader knot) {
        var out = new LinkedHashMap<String, List<String>>();
        try {
            var descriptors = knot.getResources("fabric.mod.json");
            while (descriptors.hasMoreElements()) {
                var url = descriptors.nextElement();
                try (var in = url.openStream()) {
                    var root =
                            com.google.gson.JsonParser.parseString(
                                    new String(
                                            in.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8));
                    if (!root.isJsonObject()) continue;
                    var json = root.getAsJsonObject();
                    if (!json.has("id")) continue;
                    String id = json.get("id").getAsString();
                    var configs = new ArrayList<String>();
                    if (json.has("mixins")) {
                        for (var entry : json.getAsJsonArray("mixins")) {
                            if (entry.isJsonPrimitive()) configs.add(entry.getAsString());
                            else if (entry.isJsonObject() && entry.getAsJsonObject().has("config"))
                                configs.add(entry.getAsJsonObject().get("config").getAsString());
                        }
                    }
                    out.merge(
                            id,
                            configs,
                            (a, b) -> {
                                var both = new ArrayList<>(a);
                                both.addAll(b);
                                return both;
                            });
                } catch (Exception unreadable) {
                    out.merge("unreadable:" + url, List.of(), (a, b) -> a);
                }
            }
        } catch (Exception missing) {
            throw new AssertionError("cannot enumerate fabric.mod.json descriptors", missing);
        }
        return out;
    }

    static List<String> mixinEntryTargets(ClassLoader knot, String mixinClass) {
        try {
            var reader =
                    new org.objectweb.asm.ClassReader(
                            knot.getResourceAsStream(mixinClass.replace('.', '/') + ".class"));
            var targets = new ArrayList<String>();
            reader.accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String descriptor, boolean visible) {
                            if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                                return super.visitAnnotation(descriptor, visible);
                            return new org.objectweb.asm.AnnotationVisitor(
                                    org.objectweb.asm.Opcodes.ASM9) {
                                @Override
                                public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                                    if (!name.equals("value") && !name.equals("targets"))
                                        return super.visitArray(name);
                                    boolean strings = name.equals("targets");
                                    return new org.objectweb.asm.AnnotationVisitor(
                                            org.objectweb.asm.Opcodes.ASM9) {
                                        @Override
                                        public void visit(String ignored, Object value) {
                                            if (value instanceof String text && strings)
                                                targets.add(text);
                                            else if (value instanceof org.objectweb.asm.Type type)
                                                targets.add(type.getClassName());
                                        }
                                    };
                                }
                            };
                        }
                    },
                    org.objectweb.asm.ClassReader.SKIP_CODE
                            | org.objectweb.asm.ClassReader.SKIP_DEBUG
                            | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return targets;
        } catch (Exception unreadable) {
            return List.of("unreadable:" + unreadable);
        }
    }
}
