package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.alrex.parcool.client.animation.AnimatorList;
import com.alrex.parcool.client.animation.PassiveCustomAnimation;
import com.alrex.parcool.client.animation.impl.DiveAnimationHostAnimator;
import com.alrex.parcool.common.action.Actions;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Native read closure for U1 fidelity P2-1/6/7; not native model-output acceptance. */
class ParCoolReadLayoutOracleFixture {
    private static final String PREFIX = "com/alrex/parcool/";
    private static final Set<String> GEOMETRY =
            Set.of(
                    "animatePre",
                    "animatePost",
                    "rotatePre",
                    "rotatePost",
                    "onRenderTick",
                    "shouldRemoved");

    @Test
    void omittedPhysicsFieldsAreOutsideNativeGeometryReadClosure() {
        AnimSyncOracleRunner.run(
                "parcool",
                "native-action-read-closure",
                () -> {
                    var scan = new ReadClosure();
                    for (var animator : AnimatorList.ANIMATORS) scan.roots(animator, GEOMETRY);
                    scan.roots(DiveAnimationHostAnimator.DiveAnimator.class, GEOMETRY);
                    scan.roots(DiveAnimationHostAnimator.SkyDiveAnimator.class, GEOMETRY);
                    scan.roots(PassiveCustomAnimation.class, Set.of("animate", "rotate", "tick"));
                    for (var action : Actions.LIST) scan.roots(action, Set.of("onRenderTick"));
                    scan.run();
                    var omitted =
                            Set.of(
                                    "HorizontalWallRun.coolTime",
                                    "ChargeJump.coolTimeTick",
                                    "Dodge.coolTime",
                                    "Dodge.successivelyCount",
                                    "Dodge.successivelyCoolTick",
                                    "Roll.creativeCoolTime",
                                    "Roll.startRequired",
                                    "Tap.startRequired",
                                    "FastRun.speedModifier",
                                    "FastRun.toggleStatus",
                                    "FastRun.lastDashTick",
                                    "Crawl.toggleStatus",
                                    "WallSlide.particleSpawnCoolTime",
                                    "WallSlide.damageCount",
                                    "WallSlide.takenDamageCount",
                                    "WallSlide.damageCoolTime",
                                    "Vault.stepHeight",
                                    "Vault.stepDirection",
                                    "Vault.currentAnimation");
                    for (String slot : omitted)
                        assertFalse(
                                scan.actionFields.contains(slot),
                                "Unrepresented native geometry read: " + slot);
                    // These ARE geometry. AcceptedStart maps direction=startAngle,
                    // flag=turnRightward.
                    assertTrue(scan.actionFields.contains("QuickTurn.startAngle"));
                    assertTrue(scan.actionFields.contains("QuickTurn.turnRightward"));
                    assertTrue(scan.actionFields.contains("ClingToCliff.clingWallDirection"));
                    assertTrue(scan.actionFields.contains("VerticalWallRun.wallDirection"));
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "checkedOmittedFields",
                            new TreeSet<>(omitted),
                            "nativeActionReadFields",
                            scan.actionFields,
                            "visitedNativeMethods",
                            scan.visited.size(),
                            "boundary",
                            "native animator/model/root/action-render paths; physics selectors and production event listener catalogue are separate",
                            "quickTurnMapping",
                            "AcceptedStart.direction=startAngle; flag=server-owned turnRightward; scalar unused; native start payload contains only x/z",
                            "vaultMapping",
                            "AcceptedStart.variant=AnimationType byte; DurationInput.duration=Vault.MAX_TICK; stepHeight/stepDirection/currentAnimation are physics/constructor selection, not geometry reads");
                });
    }

    @Test
    void deadAnimatorHasNoNativeConstructionAndDiveConstantsRemainPinned() {
        AnimSyncOracleRunner.run(
                "parcool",
                "dead-animator-and-dive-constants",
                () -> {
                    assertEquals(26, AnimatorList.ANIMATORS.size());
                    assertTrue(
                            AnimatorList.ANIMATORS.stream()
                                    .noneMatch(
                                            type ->
                                                    type.getSimpleName()
                                                            .equals(
                                                                    "ExaggeratedSideDodgeAnimator")));
                    int scanned = 0;
                    String dead = PREFIX + "client/animation/impl/ExaggeratedSideDodgeAnimator";
                    for (var root :
                            FabricLoader.getInstance()
                                    .getModContainer("parcool")
                                    .orElseThrow()
                                    .getRootPaths()) {
                        try (var files = Files.walk(root)) {
                            for (var file :
                                    files.filter(path -> path.toString().endsWith(".class"))
                                            .toList()) {
                                var node = new ClassNode();
                                new ClassReader(Files.readAllBytes(file))
                                        .accept(
                                                node,
                                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                                scanned++;
                                for (var method : node.methods)
                                    for (var insn : method.instructions)
                                        if (insn instanceof TypeInsnNode allocation
                                                && allocation.getOpcode() == Opcodes.NEW)
                                            assertNotEquals(
                                                    dead,
                                                    allocation.desc,
                                                    "Declared-only animator was wired into "
                                                            + node.name
                                                            + "."
                                                            + method.name);
                            }
                        }
                    }
                    var sky = new DiveAnimationHostAnimator.SkyDiveAnimator(37);
                    var max =
                            DiveAnimationHostAnimator.SkyDiveAnimator.class.getDeclaredField(
                                    "maxCount");
                    max.setAccessible(true);
                    assertEquals(8, max.getInt(sky));
                    var passiveFields = new TreeSet<String>();
                    for (var field : PassiveCustomAnimation.class.getDeclaredFields())
                        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                            passiveFields.add(field.getName());
                    assertEquals(
                            Set.of(
                                    "fallingAnimationTick",
                                    "flyingAnimationLevelOld",
                                    "flyingAnimationLevel"),
                            passiveFields);
                    assertTrue(
                            scanned > 100,
                            "whole ParCool jar, including the declared-only class itself");
                    OracleTransformAudit.requireConfig("parcool.mixins.json");
                    return Map.of(
                            "registered",
                            26,
                            "scannedNativeClasses",
                            scanned,
                            "deadAnimatorNewSites",
                            0,
                            "skyDiveMaxCount",
                            8,
                            "passiveFields",
                            passiveFields);
                });
    }

    private record Method(String owner, String name, String descriptor) {}

    private static final class ReadClosure {
        final Map<String, ClassNode> classes = new HashMap<>();
        final ArrayDeque<Method> pending = new ArrayDeque<>();
        final Set<Method> visited = new HashSet<>();
        final TreeSet<String> actionFields = new TreeSet<>();

        ClassNode read(String owner) throws IOException {
            if (classes.containsKey(owner)) return classes.get(owner);
            try (var input = getClass().getClassLoader().getResourceAsStream(owner + ".class")) {
                if (input == null)
                    throw new AssertionError("Native read-layout class missing: " + owner);
                var node = new ClassNode();
                new ClassReader(input)
                        .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.put(owner, node);
                return node;
            }
        }

        void roots(Class<?> type, Set<String> names) throws IOException {
            var node = read(type.getName().replace('.', '/'));
            for (var method : node.methods)
                if (names.contains(method.name))
                    pending.add(new Method(node.name, method.name, method.desc));
        }

        void run() throws IOException {
            while (!pending.isEmpty()) {
                var key = pending.remove();
                if (!visited.add(key)) continue;
                ClassNode owner = read(key.owner());
                var method =
                        owner.methods.stream()
                                .filter(
                                        candidate ->
                                                candidate.name.equals(key.name())
                                                        && candidate.desc.equals(key.descriptor()))
                                .findFirst()
                                .orElse(null);
                if (method == null) {
                    if (owner.superName.startsWith(PREFIX))
                        pending.add(new Method(owner.superName, key.name(), key.descriptor()));
                    continue;
                }
                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.startsWith(PREFIX))
                        pending.add(new Method(call.owner, call.name, call.desc));
                    if (insn instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.startsWith(PREFIX + "common/action/")) {
                        String name = field.owner.substring(field.owner.lastIndexOf('/') + 1);
                        actionFields.add(name + "." + field.name);
                    }
                }
            }
        }
    }
}
