package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Fail-loud pins for the oracle scaffold. Each test fails when its injection target or native
 * signature changes, so a silently untransformed or repinned codebase cannot pass as verified.
 */
class OracleInjectionTargetTest {
    static ClassNode bytes(String name) throws Exception {
        try (var in =
                OracleInjectionTargetTest.class.getResourceAsStream(
                        "/" + name.replace('.', '/') + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }

    @Test
    void animationWireIsInstalledOnConnectionActivationBeforePacketHandler() throws Exception {
        var mixin = bytes("com.lunares.blockfield.client.mixin.AnimationWireConnectionMixin");
        var entry =
                mixin.methods.stream()
                        .filter(m -> m.name.equals("blockfield$wireStage"))
                        .findFirst()
                        .orElseThrow();
        var annotations = new ArrayList<AnnotationNode>();
        if (entry.visibleAnnotations != null) annotations.addAll(entry.visibleAnnotations);
        if (entry.invisibleAnnotations != null) annotations.addAll(entry.invisibleAnnotations);
        var inject =
                annotations.stream()
                        .filter(a -> a.desc.endsWith("/Inject;"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of("channelActive"), value(inject, "method"));
        assertEquals(1, value(inject, "require"));
        @SuppressWarnings("unchecked")
        var at = (List<AnnotationNode>) value(inject, "at");
        assertEquals("TAIL", value(at.getFirst(), "value"));
        assertNotNull(
                net.minecraft.network.Connection.class.getMethod(
                        "channelActive", io.netty.channel.ChannelHandlerContext.class));
    }

    @Test
    void drivenWalkInjectionTargetIsPinned() throws Exception {
        var mixin = bytes("com.lunares.blockfield.client.mixin.CombatLivingInterpolationMixin");
        var injects = new ArrayList<AnnotationNode>();
        mixin.methods.forEach(
                method -> {
                    if (method.visibleAnnotations != null)
                        method.visibleAnnotations.stream()
                                .filter(a -> a.desc.endsWith("/Inject;"))
                                .forEach(injects::add);
                    if (method.invisibleAnnotations != null)
                        method.invisibleAnnotations.stream()
                                .filter(a -> a.desc.endsWith("/Inject;"))
                                .forEach(injects::add);
                });
        assertEquals(1, injects.size(), "exactly one driven-walk injection is reviewed");
        var inject = injects.getFirst();
        assertEquals(List.of("calculateEntityAnimation"), value(inject, "method"));
        assertEquals(1, value(inject, "require"));
        assertEquals(true, value(inject, "cancellable"));
        // The target must resolve in the pinned runtime classpath with the pinned descriptor;
        // a Mojang signature change fails here instead of silently detaching the mixin.
        var target = LivingEntity.class.getMethod("calculateEntityAnimation", boolean.class);
        assertEquals(void.class, target.getReturnType());
    }

    @Test
    void captureValidityResetsAtRenderEntryEvenWithoutRenderLevel() throws Exception {
        var mixin = bytes("com.lunares.blockfield.client.mixin.CombatCaptureFrameMixin");
        var entry =
                mixin.methods.stream()
                        .filter(m -> m.name.equals("blockfield$captureFrame"))
                        .findFirst()
                        .orElseThrow();
        var annotations = new ArrayList<AnnotationNode>();
        if (entry.visibleAnnotations != null) annotations.addAll(entry.visibleAnnotations);
        if (entry.invisibleAnnotations != null) annotations.addAll(entry.invisibleAnnotations);
        var inject =
                annotations.stream()
                        .filter(a -> a.desc.endsWith("/Inject;"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of("render"), value(inject, "method"));
        assertEquals(1, value(inject, "require"));
        @SuppressWarnings("unchecked")
        var at = (List<AnnotationNode>) value(inject, "at");
        assertEquals(
                "HEAD",
                value(at.getFirst(), "value"),
                "renderLevel can be skipped; its callsite cannot be the capture reset");
    }

    @Test
    void humanoidSetupAnimDescriptorIsPinned() throws Exception {
        var model = bytes("net.minecraft.client.model.HumanoidModel");
        var found =
                model.methods.stream()
                        .filter(
                                method ->
                                        method.name.equals("setupAnim")
                                                && method.desc.equals(
                                                        "(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V"))
                        .count();
        assertEquals(
                1,
                found,
                "pinned setupAnim descriptor changed: the oracle fixture would call the wrong"
                        + " method");
        // Same check through reflection: the fixture's call target must exist, not just the bytes.
        var call =
                HumanoidModel.class.getMethod(
                        "setupAnim",
                        LivingEntity.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class);
        assertEquals(void.class, call.getReturnType());
    }

    @Test
    void productionMixinConfigIsOnTheClasspath() {
        assertNotNull(
                OracleInjectionTargetTest.class.getResource("/blockfield-client.mixins.json"),
                "production mixin config must be visible to oracle fixtures");
    }

    @Test
    void plainJvmCannotMasqueradeAsTransformedOracle() {
        assertThrows(
                NoSuchMethodException.class,
                () -> LivingEntity.class.getDeclaredMethod("blockfield$clearInterpolation"));
        assertFalse(Boolean.getBoolean("blockfield.oracle.transformed"));
    }

    @Test
    void oracleSnapshotMatchesAvailableWorkspacePacks() throws Exception {
        String workspace = System.getProperty("blockfield.test.combatPackRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                workspace != null, "standalone mod checkout uses the committed pack snapshot");
        var snapshot =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../dev/oracle-mod-pins.gradle"));
        for (String side : List.of("client", "server")) {
            try (var manifests =
                    java.nio.file.Files.list(java.nio.file.Path.of(workspace, side, "mods"))) {
                for (var manifest :
                        manifests.filter(path -> path.toString().endsWith(".pw.toml")).toList()) {
                    String text = java.nio.file.Files.readString(manifest);
                    var file =
                            java.util.regex.Pattern.compile("(?m)^filename = \"([^\"]+)\"")
                                    .matcher(text);
                    var hash =
                            java.util.regex.Pattern.compile("(?m)^hash = \"([^\"]+)\"")
                                    .matcher(text);
                    assertTrue(file.find(), manifest.toString());
                    if (file.group(1).startsWith("blockfield-client-")
                            || file.group(1).startsWith("blockfield-server-")) continue;
                    assertTrue(hash.find(), manifest.toString());
                    assertTrue(snapshot.contains("'" + file.group(1) + "'"), manifest.toString());
                    assertTrue(
                            snapshot.contains("hash: '" + hash.group(1) + "'"),
                            manifest.toString());
                }
            }
        }
    }

    /** U8b driven render inputs: every pinned getter and renderer stage the scope overrides. */
    @Test
    void drivenInputTargetsArePinned() throws Exception {
        var living = bytes("net.minecraft.world.entity.LivingEntity");
        var getters =
                java.util.Map.ofEntries(
                        java.util.Map.entry("isUsingItem", "()Z"),
                        java.util.Map.entry(
                                "getUsedItemHand", "()Lnet/minecraft/world/InteractionHand;"),
                        java.util.Map.entry("getUseItemRemainingTicks", "()I"),
                        java.util.Map.entry("getTicksUsingItem", "()I"),
                        java.util.Map.entry("isFallFlying", "()Z"),
                        java.util.Map.entry("isAutoSpinAttack", "()Z"),
                        java.util.Map.entry("onClimbable", "()Z"),
                        java.util.Map.entry(
                                "getBedOrientation", "()Lnet/minecraft/core/Direction;"),
                        java.util.Map.entry("getScale", "()F"),
                        java.util.Map.entry("isAlive", "()Z"),
                        java.util.Map.entry("isBaby", "()Z"));
        getters.forEach((name, desc) -> assertEquals(1, count(living, name, desc), name));
        var entity = bytes("net.minecraft.world.entity.Entity");
        java.util.Map.of(
                        "isPassenger", "()Z",
                        "isFullyFrozen", "()Z",
                        "getTicksFrozen", "()I",
                        "getEyeHeight", "(Lnet/minecraft/world/entity/Pose;)F",
                        "isInWater", "()Z",
                        "getBbHeight", "()F",
                        "getDeltaMovement", "()Lnet/minecraft/world/phys/Vec3;")
                .forEach((name, desc) -> assertEquals(1, count(entity, name, desc), name));
        // No player subclass overrides a pinned getter, so the base injection sees every call.
        for (String type :
                new String[] {
                    "net.minecraft.world.entity.player.Player",
                    "net.minecraft.client.player.AbstractClientPlayer",
                    "net.minecraft.client.player.RemotePlayer"
                }) {
            var node = bytes(type);
            for (String name : getters.keySet())
                assertEquals(0, count(node, name, getters.get(name)), type + "." + name);
            assertEquals(0, count(node, "isPassenger", "()Z"), type);
            assertEquals(0, count(node, "isInWater", "()Z"), type);
            assertEquals(0, count(node, "getBbHeight", "()F"), type);
            assertEquals(
                    0, count(node, "getDeltaMovement", "()Lnet/minecraft/world/phys/Vec3;"), type);
        }
        assertEquals(
                1,
                count(
                        bytes("net.minecraft.client.renderer.entity.player.PlayerRenderer"),
                        "setModelProperties",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;)V"));
        assertEquals(
                1,
                count(
                        bytes("net.minecraft.client.renderer.entity.LivingEntityRenderer"),
                        "isEntityUpsideDown",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Z"));
        var utils = bytes("net.minecraft.client.model.AnimationUtils");
        var charge =
                utils.methods.stream()
                        .filter(m -> m.name.equals("animateCrossbowCharge"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(
                                        charge.instructions.iterator(), 0),
                                false)
                        .anyMatch(
                                insn ->
                                        insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                                                && call.owner.equals(
                                                        "net/minecraft/world/item/CrossbowItem")
                                                && call.name.equals("getChargeDuration")),
                "the charge duration call the driven scope replaces");
    }

    private static long count(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .count();
    }

    /** U3 driven-remote leaves: every TaCZ PA geometry change and PA's partial entry point. */
    @Test
    void remotePlayerAnimatorTargetsArePinned() throws Exception {
        var manager = bytes("com.tacz.guns.compat.playeranimator.animation.AnimationManager");
        var expected =
                java.util.Map.of(
                        "playLoopAnimation",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;"
                                + "Lcom/tacz/guns/client/resource/GunDisplayInstance;"
                                + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V",
                        "playOnceAnimation",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;"
                                + "Lcom/tacz/guns/client/resource/GunDisplayInstance;"
                                + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V",
                        "playRotationAnimation",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;"
                                + "Lcom/tacz/guns/client/resource/GunDisplayInstance;)V",
                        "stopAnimation",
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;"
                                + "Lnet/minecraft/resources/ResourceLocation;I)V");
        expected.forEach(
                (name, desc) ->
                        assertEquals(
                                1,
                                manager.methods.stream()
                                        .filter(m -> m.name.equals(name))
                                        .filter(m -> m.desc.equals(desc))
                                        .count(),
                                name));
        // Unique names: the remap=false selectors cannot pick an overload by accident.
        expected.keySet()
                .forEach(
                        name ->
                                assertEquals(
                                        1,
                                        manager.methods.stream()
                                                .filter(m -> m.name.equals(name))
                                                .count(),
                                        name));
        var third = bytes("com.tacz.guns.client.animation.third.InnerThirdPersonManager");
        assertEquals(
                1,
                third.methods.stream().filter(m -> m.name.equals("playVanillaAnimation")).count());
        var adjustment =
                bytes("com.tacz.guns.compat.playeranimator.animation.AdjustmentYRotModifier");
        assertEquals(
                1,
                adjustment.methods.stream()
                        .filter(
                                m ->
                                        m.name.equals("apply")
                                                && m.desc.equals(
                                                        "(Ljava/lang/String;)Ljava/util/Optional;"))
                        .count());
        assertTrue(
                adjustment.fields.stream()
                        .anyMatch(
                                f ->
                                        f.name.equals("player")
                                                && f.desc.equals(
                                                        "Lnet/minecraft/world/entity/player/Player;")));
        assertEquals(
                1,
                third.methods.stream()
                        .filter(
                                m ->
                                        m.name.equals("setRotationAnglesHead")
                                                && m.desc.equals(
                                                        "(Lnet/minecraft/world/entity/LivingEntity;"
                                                                + "Lnet/minecraft/client/model/geom/ModelPart;"
                                                                        .repeat(4)
                                                                + "F)V"))
                        .count());
        var stack = bytes("dev.kosmx.playerAnim.api.layered.AnimationStack");
        assertEquals(
                1,
                stack.methods.stream()
                        .filter(m -> m.name.equals("tick") && m.desc.equals("()V"))
                        .count());
        var processor = bytes("dev.kosmx.playerAnim.core.impl.AnimationProcessor");
        assertEquals(
                1,
                processor.methods.stream()
                        .filter(m -> m.name.equals("setTickDelta") && m.desc.equals("(F)V"))
                        .count());
    }
}
