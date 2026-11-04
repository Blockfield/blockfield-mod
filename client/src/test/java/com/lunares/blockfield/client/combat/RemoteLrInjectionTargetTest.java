package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.jar.JarFile;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Production injection targets of the U4 LR compatibility view, pinned against the LR bf2 bytes:
 * every LR layer access must pass through the two redirected sites, or a later pin could write a
 * driven remote's geometry (or skip the view) silently. Complements the transformed fixture.
 */
class RemoteLrInjectionTargetTest {
    private static final String INTEGRATION =
            "me/xjqsh/lrtactical/compat/player_animator/PlayerAnimatorIntegration";
    private static final String ASSOCIATED =
            "dev/kosmx/playerAnim/minecraftApi/PlayerAnimationAccess$PlayerAssociatedAnimationData";

    private static Map<String, ClassNode> lr() throws Exception {
        var classes = new TreeMap<String, ClassNode>();
        try (var jar = new JarFile(System.getProperty("blockfield.test.lrJar"))) {
            for (var entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                var node = new ClassNode();
                new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
                classes.put(node.name, node);
            }
        }
        return classes;
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + name + desc));
    }

    @Test
    void everyLrLayerAccessPassesTheRedirectedSites() throws Exception {
        var classes = lr();
        var integration = classes.get(INTEGRATION);
        var getLayer =
                method(
                        integration,
                        "getLayer",
                        "(Lnet/minecraft/class_742;L"
                                + INTEGRATION
                                + "$AnimationLayer;)Ldev/kosmx/playerAnim/api/layered/ModifierLayer;");
        assertTrue((getLayer.access & Opcodes.ACC_STATIC) != 0);
        var rotation =
                method(
                        integration,
                        "enableRotationModifier",
                        "(Lnet/minecraft/class_742;Lnet/minecraft/class_2960;I)V");
        assertEquals(
                1,
                count(
                        rotation,
                        ASSOCIATED,
                        "get",
                        "(Lnet/minecraft/class_2960;)Ldev/kosmx/playerAnim/api/layered/IAnimation;"));
        var layer = classes.get(INTEGRATION + "$AnimationLayer");
        assertTrue(
                layer.fields.stream()
                        .anyMatch(
                                f ->
                                        f.name.equals("id")
                                                && f.desc.equals("Lnet/minecraft/class_2960;")));
        // The only associated-data lookups in the whole pinned jar are the two redirected sites.
        var sites = new TreeSet<String>();
        for (var node : classes.values())
            for (var m : node.methods)
                for (var insn : m.instructions)
                    if (insn instanceof MethodInsnNode call
                            && (call.name.equals("getPlayerAssociatedData")
                                    || call.name.equals("playerAnimator_getAnimation")
                                    || call.name.equals("getAnimationStack")))
                        sites.add(
                                node.name.substring(node.name.lastIndexOf('/') + 1) + "." + m.name);
        assertEquals(
                Set.of(
                        "PlayerAnimatorIntegration.getLayer",
                        "PlayerAnimatorIntegration.enableRotationModifier"),
                sites);
    }

    @Test
    void compatibilityMixinsNameTheirPinnedTargets() throws Exception {
        var layer = bytes("com.lunares.blockfield.client.mixin.lrtactical.RemoteLrLayerMixin");
        var targets = annotation(layer.invisibleAnnotations, layer.visibleAnnotations, "/Mixin;");
        assertEquals(
                List.of(INTEGRATION.replace('/', '.')),
                OracleInjectionTargetTest.value(targets, "targets"));
        var injected = new TreeMap<String, AnnotationNode>();
        for (var m : layer.methods) {
            var inject = annotation(m.invisibleAnnotations, m.visibleAnnotations, "/Inject;");
            if (inject == null)
                inject =
                        annotation(m.invisibleAnnotations, m.visibleAnnotations, "/WrapOperation;");
            if (inject == null)
                inject = annotation(m.invisibleAnnotations, m.visibleAnnotations, "/ModifyArg;");
            if (inject != null) injected.put(m.name, inject);
        }
        assertEquals(
                Set.of(
                        "blockfield$compatLayer",
                        "blockfield$compatRotation",
                        "blockfield$stanceFade",
                        "blockfield$sampleStance"),
                injected.keySet());
        assertEquals(
                List.of("getLayer"),
                OracleInjectionTargetTest.value(injected.get("blockfield$compatLayer"), "method"));
        var wrap = injected.get("blockfield$compatRotation");
        assertEquals(
                List.of("enableRotationModifier"), OracleInjectionTargetTest.value(wrap, "method"));
        @SuppressWarnings("unchecked")
        var at = ((List<AnnotationNode>) OracleInjectionTargetTest.value(wrap, "at")).getFirst();
        assertEquals(
                "L"
                        + ASSOCIATED
                        + ";get(Lnet/minecraft/class_2960;)Ldev/kosmx/playerAnim/api/layered/IAnimation;",
                OracleInjectionTargetTest.value(at, "target"));
        // STANCE-EASE: both stance hooks target the idle selector so the shared coefficient,
        // not a separate fade clock, drives LR sneak on driven remotes.
        assertEquals(
                List.of("playIdleAnimation"),
                OracleInjectionTargetTest.value(injected.get("blockfield$stanceFade"), "method"));
        assertEquals(
                List.of("playIdleAnimation"),
                OracleInjectionTargetTest.value(injected.get("blockfield$sampleStance"), "method"));

        var tick = bytes("com.lunares.blockfield.client.mixin.lrtactical.RemoteLrCompatTickMixin");
        var tickInject =
                tick.methods.stream()
                        .map(
                                m ->
                                        annotation(
                                                m.invisibleAnnotations,
                                                m.visibleAnnotations,
                                                "/Inject;"))
                        .filter(Objects::nonNull)
                        .toList();
        assertEquals(1, tickInject.size());
        assertEquals(
                List.of("tick"), OracleInjectionTargetTest.value(tickInject.getFirst(), "method"));
        // PlayerAnimator ticks a player's stack at the HEAD of this exact method.
        assertEquals(void.class, Player.class.getMethod("tick").getReturnType());
        try (var config =
                getClass().getResourceAsStream("/blockfield-client-lrtactical.mixins.json")) {
            assertNotNull(config);
            String json =
                    new String(config.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("\"RemoteLrLayerMixin\""));
            assertTrue(json.contains("\"RemoteLrCompatTickMixin\""));
            assertTrue(json.contains("\"required\": true"));
        }
    }

    private static ClassNode bytes(String name) throws Exception {
        return OracleInjectionTargetTest.bytes(name);
    }

    private static AnnotationNode annotation(
            List<AnnotationNode> invisible, List<AnnotationNode> visible, String suffix) {
        for (var list : Arrays.asList(invisible, visible))
            if (list != null)
                for (var annotation : list) if (annotation.desc.endsWith(suffix)) return annotation;
        return null;
    }

    private static int count(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (var insn : method.instructions)
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) count++;
        return count;
    }
}
