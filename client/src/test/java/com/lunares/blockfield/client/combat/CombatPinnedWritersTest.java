package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Pinned vanilla 1.21.1 and bf24 writer/target witnesses; no stand or desktop is started. */
class CombatPinnedWritersTest {
    static ClassNode bytes(String name) throws Exception {
        try (var in =
                CombatPinnedWritersTest.class.getResourceAsStream(
                        "/" + name.replace('.', '/') + ".class")) {
            assertNotNull(in, name);
            var n = new ClassNode();
            new ClassReader(in).accept(n, 0);
            return n;
        }
    }

    static ClassNode pinned(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name.replace('.', '/') + ".class");
        assertNotNull(entry, name);
        try (var in = jar.getInputStream(entry)) {
            var n = new ClassNode();
            new ClassReader(in).accept(n, 0);
            return n;
        }
    }

    static List<String> calls(ClassNode n, String method) {
        var result = new ArrayList<String>();
        n.methods.stream()
                .filter(m -> m.name.equals(method))
                .forEach(
                        m -> {
                            for (var ins : m.instructions)
                                if (ins instanceof MethodInsnNode call)
                                    result.add(call.owner + "." + call.name + call.desc);
                        });
        return result;
    }

    @Test
    void nativeWriterTargetsAndTheirCodecSideEffectsArePinned() throws Exception {
        var listener = bytes("net.minecraft.client.multiplayer.ClientPacketListener");
        var move = calls(listener, "handleMoveEntity");
        assertEquals(2, move.stream().filter(c -> c.endsWith("Entity.lerpTo(DDDFFI)V")).count());
        int decode = index(move, "VecDeltaCodec.decode"),
                base = index(move, "VecDeltaCodec.setBase"),
                lerp = index(move, "Entity.lerpTo"),
                ground = index(move, "Entity.setOnGround");
        assertTrue(decode < base && base < lerp && lerp < ground);
        var teleport = calls(listener, "handleTeleportEntity");
        assertTrue(
                index(teleport, "Entity.syncPacketPositionCodec")
                        < index(teleport, "Entity.lerpTo"));
        assertTrue(index(teleport, "Entity.lerpTo") < index(teleport, "Entity.setOnGround"));
        assertTrue(
                calls(listener, "handleRotateMob").stream()
                        .anyMatch(c -> c.endsWith("Entity.lerpHeadTo(FI)V")));
        assertTrue(
                calls(listener, "handleSetEntityMotion").stream()
                        .anyMatch(c -> c.endsWith("Entity.lerpMotion(DDD)V")));
    }

    static int index(List<String> values, String part) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).contains(part)) return i;
        fail(part);
        return -1;
    }

    @Test
    void mainFrameWriteIsAfterNativeTicksBeforePickAndShadowCannotAdvanceClock() throws Exception {
        var renderer = bytes("net.minecraft.client.renderer.GameRenderer");
        assertEquals(
                1,
                calls(renderer, "render").stream()
                        .filter(c -> c.contains("GameRenderer.renderLevel("))
                        .count());
        var level = calls(renderer, "renderLevel");
        assertTrue(index(level, "GameRenderer.pick(") < index(level, "LevelRenderer.renderLevel("));
        var hook = bytes("com.lunares.blockfield.client.mixin.CombatSceneRenderMixin");
        assertEquals(
                1,
                calls(hook, "blockfield$sampleScene").stream()
                        .filter(c -> c.contains("ClientCombatScene.frameEntry"))
                        .count());
        assertFalse(
                calls(bytes("net.minecraft.client.renderer.LevelRenderer"), "renderLevel").stream()
                        .anyMatch(c -> c.contains("ClientCombatScene.frameEntry")));
        assertTrue(
                calls(bytes("net.minecraft.world.entity.Entity"), "rideTick").stream()
                        .anyMatch(c -> c.contains("positionRider")));
    }

    // Why driven walk animation comes from the sampled chord: the tick sees no movement.
    @Test
    void remoteWalkAnimationMeasuresMovementInsideTheTick() throws Exception {
        var level =
                calls(bytes("net.minecraft.client.multiplayer.ClientLevel"), "tickNonPassenger");
        assertTrue(index(level, "Entity.setOldPosAndRot") < index(level, "Entity.tick"));
        assertTrue(
                calls(bytes("net.minecraft.client.player.RemotePlayer"), "tick").stream()
                        .anyMatch(c -> c.endsWith("calculateEntityAnimation(Z)V")));
        var living = bytes("net.minecraft.world.entity.LivingEntity");
        var reads = new ArrayList<String>();
        living.methods.stream()
                .filter(m -> m.name.equals("calculateEntityAnimation"))
                .forEach(
                        m -> {
                            for (var ins : m.instructions)
                                if (ins instanceof org.objectweb.asm.tree.FieldInsnNode f)
                                    reads.add(f.name);
                        });
        assertEquals(List.of("xo", "yo", "zo"), reads);
        assertTrue(
                calls(living, "calculateEntityAnimation").stream()
                        .anyMatch(c -> c.endsWith("updateWalkAnimation(F)V")));
    }

    @Test
    void boatMinecartAndRemoteHaveDistinctPendingStateAndRequiredTargets() throws Exception {
        for (String name : List.of("Boat", "AbstractMinecart")) {
            var n = bytes("net.minecraft.world.entity.vehicle." + name);
            assertTrue(
                    n.fields.stream()
                            .anyMatch(f -> f.name.equals("lerpSteps") && f.desc.equals("I")));
            assertTrue(
                    n.methods.stream()
                            .anyMatch(m -> m.name.equals("lerpTo") && m.desc.equals("(DDDFFI)V")));
        }
        var boat = bytes("net.minecraft.world.entity.vehicle.Boat");
        assertTrue(boat.methods.stream().anyMatch(m -> m.name.equals("tickLerp")));
        var remote = bytes("net.minecraft.client.player.RemotePlayer");
        assertTrue(remote.fields.stream().anyMatch(f -> f.name.equals("lerpDeltaMovementSteps")));
        var cart =
                calls(bytes("net.minecraft.world.entity.vehicle.AbstractMinecart"), "lerpMotion");
        assertTrue(cart.stream().anyMatch(c -> c.contains("setDeltaMovement")));
    }

    @Test
    void bf24SbwClassFieldsMotionDispatchAndConcreteSubclassWritersMatchAdapter() throws Exception {
        try (var jar = new ZipFile(System.getProperty("blockfield.test.sbwJar"))) {
            var vehicle =
                    pinned(jar, "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity");
            for (String setter :
                    List.of(
                            "setInterpolationSteps",
                            "setRoll",
                            "setPrevRoll",
                            "setTurretYRot",
                            "setTurretXRot",
                            "setTurretYRotO",
                            "setTurretXRotO",
                            "setGunYRot",
                            "setGunXRot",
                            "setGunYRotO",
                            "setGunXRotO"))
                assertTrue(vehicle.methods.stream().anyMatch(m -> m.name.equals(setter)), setter);
            // bf24 VehicleEntity.kt:2706 baseTick->handleClientSync, 4322-4365 ten-step targets.
            assertTrue(
                    calls(vehicle, "method_5670").stream()
                            .anyMatch(c -> c.contains("handleClientSync")));
            var sync =
                    pinned(
                            jar,
                            "com.atsuishio.superbwarfare.network.message.receive.ClientMotionSyncMessage");
            assertTrue(
                    calls(sync, "handler").stream()
                            .anyMatch(
                                    c -> c.contains("net/minecraft/class_1297.method_5750(DDD)V")));
            for (String name : List.of("Ju87Entity", "Ztz99aEntity")) {
                var cls = pinned(jar, "com.atsuishio.superbwarfare.entity.vehicle." + name);
                for (var method : cls.methods) {
                    if (!method.name.matches("set(Turret[XY]RotO?)")) continue;
                    long writes = 0;
                    for (var ins : method.instructions) {
                        if (ins instanceof MethodInsnNode)
                            fail(
                                    "Pinned property override gained side effects: "
                                            + name
                                            + "."
                                            + method.name);
                        if (ins.getOpcode() == Opcodes.PUTFIELD) writes++;
                    }
                    assertEquals(1, writes, name + "." + method.name);
                }
            }
            int concrete = 0;
            for (var entry : java.util.Collections.list(jar.entries())) {
                if (!entry.getName().startsWith("com/atsuishio/superbwarfare/entity/vehicle/")
                        || !entry.getName().endsWith("Entity.class")) continue;
                var n =
                        pinned(
                                jar,
                                entry.getName()
                                        .substring(0, entry.getName().length() - 6)
                                        .replace('/', '.'));
                if (n.superName.equals(vehicle.name) && (n.access & Opcodes.ACC_ABSTRACT) == 0) {
                    concrete++;
                    assertFalse(
                            n.methods.stream()
                                    .anyMatch(
                                            m ->
                                                    m.name.equals("handleClientSync")
                                                            || m.name.equals("method_5759")),
                            n.name);
                }
            }
            assertTrue(concrete >= 10, "Audited concrete SBW vehicle set unexpectedly changed");
        }
    }
}
