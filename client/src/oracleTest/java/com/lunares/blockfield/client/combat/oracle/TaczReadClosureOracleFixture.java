package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.*;

/** Fidelity audit P2-2: inspect the successfully transformed third-person method closure. */
class TaczReadClosureOracleFixture {
    private static final Map<String, ClassNode> TRANSFORMED = new TreeMap<>();
    private static final String MANAGER =
            "com/tacz/guns/compat/playeranimator/animation/AnimationManager";
    private static final String INNER =
            "com/tacz/guns/client/animation/third/InnerThirdPersonManager";
    private static final String ADJUST =
            "com/tacz/guns/compat/playeranimator/animation/AdjustmentYRotModifier";

    @BeforeAll
    static void observe() {
        var env = MixinEnvironment.getCurrentEnvironment();
        var extensions =
                (Extensions) ((IMixinTransformer) env.getActiveTransformer()).getExtensions();
        extensions.add(
                new IExtension() {
                    public boolean checkActive(MixinEnvironment ignored) {
                        return true;
                    }

                    public void preApply(ITargetClassContext ignored) {}

                    public void postApply(ITargetClassContext context) {
                        var node = context.getClassNode();
                        if (!node.name.startsWith("com/tacz/guns/")) return;
                        var copy = new ClassNode();
                        node.accept(copy);
                        TRANSFORMED.put(node.name, copy);
                    }

                    public void export(
                            MixinEnvironment env, String name, boolean force, ClassNode node) {}
                });
        extensions.select(env);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private record Method(String owner, String name, String descriptor) {}

    private static ClassNode bytes(String name) throws Exception {
        Class.forName(
                name.replace('/', '.'), false, TaczReadClosureOracleFixture.class.getClassLoader());
        if (TRANSFORMED.containsKey(name)) return TRANSFORMED.get(name);
        try (var input =
                TaczReadClosureOracleFixture.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @Test
    void transformedThirdPersonHasNoFirstPersonStateMachineReads() {
        AnimSyncOracleRunner.run(
                "tacz",
                "transformed-third-person-read-closure",
                () -> {
                    var pending = new ArrayDeque<Method>();
                    for (String owner :
                            List.of(
                                    INNER,
                                    MANAGER,
                                    ADJUST,
                                    "com/tacz/guns/compat/playeranimator/PlayerAnimatorCompat"))
                        for (var method : bytes(owner).methods)
                            if (!method.name.equals("<init>")
                                    && !method.name.equals("<clinit>")
                                    && !method.name.contains("loadAnimation")
                                    && !method.name.equals("init")
                                    && !method.name.equals("registerReloadListener"))
                                pending.add(new Method(owner, method.name, method.desc));
                    assertTrue(
                            TRANSFORMED.containsKey(INNER),
                            "Production entry mixin must be observed");
                    assertTrue(
                            TRANSFORMED.containsKey(MANAGER),
                            "Production event leaves must be observed");
                    assertTrue(
                            TRANSFORMED.containsKey(ADJUST),
                            "Production fixed-hand mixin must be observed");
                    var seen = new HashSet<Method>();
                    var reads = new TreeSet<String>();
                    var boundaries = new TreeSet<String>();
                    while (!pending.isEmpty()) {
                        var key = pending.remove();
                        if (!seen.add(key)) continue;
                        var owner = bytes(key.owner());
                        for (var method : owner.methods) {
                            if (!method.name.equals(key.name())
                                    || !method.desc.equals(key.descriptor())) continue;
                            for (var instruction : method.instructions) {
                                if (instruction instanceof FieldInsnNode field
                                        && (field.getOpcode() == Opcodes.GETFIELD
                                                || field.getOpcode() == Opcodes.GETSTATIC)) {
                                    String read = field.owner + "." + field.name + ":" + field.desc;
                                    assertFalse(
                                            read.contains("GunAnimationStateContext")
                                                    || read.contains("AnimationStateMachine")
                                                    || read.contains("DiscreteTrackArray")
                                                    || field.name.equals("walkDistAnchor"),
                                            read);
                                    reads.add(read);
                                }
                                var calls = new ArrayList<Handle>();
                                if (instruction instanceof MethodInsnNode call)
                                    calls.add(
                                            new Handle(
                                                    Opcodes.H_INVOKESTATIC,
                                                    call.owner,
                                                    call.name,
                                                    call.desc,
                                                    call.itf));
                                if (instruction instanceof InvokeDynamicInsnNode dynamic)
                                    for (Object arg : dynamic.bsmArgs)
                                        if (arg instanceof Handle handle) calls.add(handle);
                                for (var call : calls) {
                                    String name = call.getOwner();
                                    assertFalse(
                                            name.contains("GunAnimationStateContext")
                                                    || name.contains("AnimationStateMachine")
                                                    || name.contains("DiscreteTrackArray"),
                                            key + " -> " + call);
                                    if (!name.startsWith("com/tacz/guns/")) continue;
                                    // Native loaders construct GPU/first-person assets. Body
                                    // evaluation consumes
                                    // only this immutable effective-resource boundary; U9 audits
                                    // its precedence.
                                    if (name.equals("com/tacz/guns/api/TimelessAPI")
                                            || name.startsWith("com/tacz/guns/client/resource/")) {
                                        boundaries.add(call.toString());
                                        continue;
                                    }
                                    if (!call.getName().startsWith("<"))
                                        pending.add(
                                                new Method(name, call.getName(), call.getDesc()));
                                }
                            }
                        }
                        assertTrue(seen.size() < 1000, "Unexpected third-person closure expansion");
                    }
                    assertTrue(
                            seen.size() >= 30,
                            "Must traverse the native lambdas and all geometry leaves");
                    OracleTransformAudit.requireConfig("blockfield-client-tacz.mixins.json");
                    net.minecraft.world.entity.player.Player.class.getDeclaredMethods();
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    return Map.of(
                            "methodCount",
                            seen.size(),
                            "fieldReads",
                            List.copyOf(reads),
                            "immutableResourceBoundaries",
                            List.copyOf(boundaries),
                            "transformedTargets",
                            List.copyOf(TRANSFORMED.keySet()),
                            "excludedFirstPersonFieldsRead",
                            0,
                            "sixBoxEquality",
                            false);
                });
    }
}
