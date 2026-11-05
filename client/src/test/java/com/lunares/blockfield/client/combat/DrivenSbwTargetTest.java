package com.lunares.blockfield.client.combat;

import static com.lunares.blockfield.client.combat.CombatPinnedWritersTest.calls;
import static com.lunares.blockfield.client.combat.CombatPinnedWritersTest.pinned;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Client review P0-3: the bf26 readers the driven SBW scope redirects or checks at install, pinned
 * against the SBW jar (intermediary names), so a repinned SBW cannot silently read past them.
 */
class DrivenSbwTargetTest {
    private static final String SBW = "com/atsuishio/superbwarfare/";
    private static final String VEHICLE = SBW + "entity/vehicle/base/VehicleEntity";
    private static final String TURRET_POS = "()Lnet/minecraft/class_243;";
    private static final String SEATS =
            SBW + "data/vehicle/DefaultVehicleData.seats()Ljava/util/List;";

    @Test
    void redirectedSbwMethodsAreUniqueAndNeverOverridden() throws Exception {
        try (var jar = new ZipFile(System.getProperty("blockfield.test.sbwJar"))) {
            var companion =
                    pinned(jar, "com.atsuishio.superbwarfare.item.curio.ParachuteItem$Companion");
            assertEquals(1, named(companion, "isParachuteOpen", "(Lnet/minecraft/class_1309;)Z"));
            assertEquals(1, named(companion, "isParachuteOpen", null));
            // The static bridge SBW's mixins call routes through the redirected companion method.
            assertTrue(
                    calls(
                                    pinned(
                                            jar,
                                            "com.atsuishio.superbwarfare.item.curio.ParachuteItem"),
                                    "isParachuteOpen")
                            .contains(
                                    SBW
                                            + "item/curio/ParachuteItem$Companion.isParachuteOpen(Lnet/minecraft/class_1309;)Z"));
            var vehicle = pinned(jar, VEHICLE.replace('/', '.'));
            assertEquals(1, named(vehicle, "getSeatIndex", "(Lnet/minecraft/class_1297;)I"));
            assertEquals(1, named(vehicle, "getSeatIndex", null));
            assertEquals(1, named(vehicle, "getPassengerRenderScale", "()F"));
            assertEquals(1, named(vehicle, "getPassengerRenderScale", null));
            assertEquals(1, named(vehicle, "getRotateOffsetHeight", "()D"));
            assertEquals(1, named(vehicle, "getRotateOffsetHeight", null));
            assertEquals(1, named(vehicle, "getTurretPos", TURRET_POS));
            assertEquals(1, named(vehicle, "getTurretPos", null));
            for (String getter :
                    List.of(
                            "getBarrelPosition",
                            "getPassengerWeaponStationPosition",
                            "getPassengerWeaponStationBarrelPosition")) {
                assertEquals(1, named(vehicle, getter, TURRET_POS));
                assertEquals(1, named(vehicle, getter, null));
                assertTrue(
                        only(vehicle, getter).invisibleAnnotations.stream()
                                .anyMatch(
                                        a -> a.desc.equals("Lorg/jetbrains/annotations/Nullable;")),
                        getter);
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement().getName();
                if (!entry.startsWith(SBW + "entity/") || !entry.endsWith(".class")) continue;
                var node = pinned(jar, entry.substring(0, entry.length() - 6).replace('/', '.'));
                if (node.name.equals(VEHICLE)) continue;
                assertEquals(
                        0, named(node, "getSeatIndex", "(Lnet/minecraft/class_1297;)I"), entry);
                assertEquals(0, named(node, "getPassengerRenderScale", "()F"), entry);
                assertEquals(0, named(node, "getRotateOffsetHeight", "()D"), entry);
                assertEquals(0, named(node, "getTurretPos", TURRET_POS), entry);
                for (String getter :
                        List.of(
                                "getBarrelPosition",
                                "getPassengerWeaponStationPosition",
                                "getPassengerWeaponStationBarrelPosition"))
                    assertEquals(0, named(node, getter, TURRET_POS), entry);
            }
        }
    }

    @Test
    void modelTailReadsArePinned() throws Exception {
        try (var jar = new ZipFile(System.getProperty("blockfield.test.sbwJar"))) {
            var mixin = pinned(jar, "com.atsuishio.superbwarfare.mixins.HumanoidModelMixin");
            // The merged handler name DrivenPresentation.SBW_MODEL_TAIL recognises.
            var tail = only(mixin, "setupAnim");
            var calls = calls(mixin, "setupAnim");
            assertTrue(
                    calls.contains(
                            SBW
                                    + "item/curio/ParachuteItem.isParachuteOpen(Lnet/minecraft/class_1309;)Z"));
            assertTrue(
                    calls.contains(
                            "net/minecraft/class_1309.method_5854()Lnet/minecraft/class_1297;"));
            assertTrue(calls.contains(VEHICLE + ".getSeatIndex(Lnet/minecraft/class_1297;)I"));
            assertTrue(
                    calls.contains(
                            VEHICLE + ".computed()L" + SBW + "data/vehicle/DefaultVehicleData;"));
            assertTrue(calls.contains(SEATS));
            assertTrue(fields(tail).contains(SBW + "data/vehicle/subdata/SeatInfo.pose"));
            assertFalse(fields(tail).contains(SBW + "data/vehicle/subdata/SeatInfo.transform"));
            assertTrue(
                    calls.contains(
                            "net/minecraft/class_1309.method_18376()Lnet/minecraft/class_4050;"));
            assertTrue(calls.contains("net/minecraft/class_1309.method_5681()Z"));
            // The main hand is read once, only for getItem() instanceof GunItem: an empty stack
            // presents a retained no-gun exactly.
            int reads = 0;
            for (var insn : tail.instructions)
                if (insn instanceof MethodInsnNode call && call.name.equals("method_6047")) {
                    reads++;
                    var item = (MethodInsnNode) call.getNext();
                    assertEquals("method_7909", item.name);
                    var check = (TypeInsnNode) item.getNext();
                    assertEquals(Opcodes.INSTANCEOF, check.getOpcode());
                    assertEquals(SBW + "item/gun/GunItem", check.desc);
                }
            assertEquals(1, reads);
        }
    }

    @Test
    void customArmHooksReadTheRedirectedMainHand() throws Exception {
        try (var jar = new ZipFile(System.getProperty("blockfield.test.sbwJar"))) {
            var mixin = pinned(jar, "com.atsuishio.superbwarfare.mixins.HumanoidModelMixin");
            for (String handler :
                    List.of("superbwarfare$poseRightArm", "superbwarfare$poseLeftArm"))
                assertTrue(
                        calls(mixin, handler)
                                .contains(
                                        SBW
                                                + "init/ModEnumExtensions$Client.applyCustomArmPose(Lnet/minecraft/class_572;Lnet/minecraft/class_1309;Lnet/minecraft/class_1306;)Z"),
                        handler);
            var arms = pinned(jar, "com.atsuishio.superbwarfare.init.ModEnumExtensions$Client");
            assertTrue(
                    calls(arms, "applyCustomArmPose")
                            .contains(
                                    "net/minecraft/class_1309.method_5998(Lnet/minecraft/class_1268;)Lnet/minecraft/class_1799;"));
            var checks = new java.util.ArrayList<String>();
            for (var instruction : only(arms, "applyCustomArmPose").instructions)
                if (instruction instanceof TypeInsnNode check
                        && check.getOpcode() == Opcodes.INSTANCEOF) checks.add(check.desc);
            assertTrue(
                    checks.containsAll(
                            List.of(
                                    SBW + "item/LungeMine",
                                    SBW + "item/gun/machinegun/MinigunItem",
                                    SBW + "item/gun/machinegun/M2HBItem",
                                    SBW + "item/gun/launcher/SuperStarShooterItem")));
        }
    }

    @Test
    void seatRootReadsArePinned() throws Exception {
        try (var jar = new ZipFile(System.getProperty("blockfield.test.sbwJar"))) {
            var mixin = pinned(jar, "com.atsuishio.superbwarfare.mixins.LivingEntityRendererMixin");
            var root = only(mixin, "setupRotations");
            var calls = calls(mixin, "setupRotations");
            for (String call :
                    List.of(
                            SBW
                                    + "entity/vehicle/utils/VehicleMounts.rootVehicleOrNull(Lnet/minecraft/class_1297;)Lnet/minecraft/class_1297;",
                            SBW
                                    + "data/vehicle/VehicleData.compute(L"
                                    + VEHICLE
                                    + ";)L"
                                    + SBW
                                    + "data/vehicle/DefaultVehicleData;",
                            SEATS,
                            VEHICLE + ".getSeatIndex(Lnet/minecraft/class_1297;)I",
                            VEHICLE
                                    + ".getTransformDirectionNoOrientation(FLnet/minecraft/class_1297;)Lnet/minecraft/class_243;",
                            VEHICLE
                                    + ".getRotationFromString(Ljava/lang/String;F)Lorg/joml/Quaterniond;",
                            VEHICLE + ".getPassengerRenderScale()F"))
                assertTrue(calls.contains(call), call);
            assertEquals(
                    List.of(SBW + "data/vehicle/subdata/SeatInfo.transform"),
                    fields(root).stream().filter(f -> f.contains("SeatInfo")).toList());
            // The seat direction query re-reads the same redirected index and checked transform.
            var direction =
                    calls(
                            pinned(jar, VEHICLE.replace('/', '.')),
                            "getTransformDirectionNoOrientation");
            assertTrue(direction.contains(VEHICLE + ".getSeatIndex(Lnet/minecraft/class_1297;)I"));
            assertTrue(direction.contains(SEATS));
            // FX-SEAT: its named matrices read the redirected offset height and turret position.
            var utils = pinned(jar, SBW.replace('/', '.') + "entity.vehicle.utils.VehicleVecUtils");
            assertTrue(
                    calls(utils, "getVehicleYOffsetTransform")
                            .contains(VEHICLE + ".getRotateOffsetHeight()D"));
            assertTrue(
                    calls(utils, "getTurretTransform")
                            .contains(VEHICLE + ".getTurretPos" + TURRET_POS));
            for (var path :
                    java.util.Map.of(
                                    "getBarrelTransform",
                                    "getBarrelPosition",
                                    "getGunTransform",
                                    "getPassengerWeaponStationPosition",
                                    "getPassengerWeaponStationBarrelTransform",
                                    "getPassengerWeaponStationBarrelPosition")
                            .entrySet())
                assertTrue(
                        calls(utils, path.getKey())
                                .contains(VEHICLE + "." + path.getValue() + TURRET_POS));
            for (String matrix :
                    List.of("getVehicleTransform", "getVehicleTransformWithCustomPitch"))
                assertTrue(
                        calls(pinned(jar, VEHICLE.replace('/', '.')), matrix)
                                .contains(VEHICLE + ".getRotateOffsetHeight()D"),
                        matrix);
        }
    }

    @Test
    void vanillaMainHandGetterIsNotOverriddenForPlayers() throws Exception {
        var desc = "()Lnet/minecraft/world/item/ItemStack;";
        assertEquals(
                1,
                named(
                        CombatPinnedWritersTest.bytes("net.minecraft.world.entity.LivingEntity"),
                        "getMainHandItem",
                        desc));
        for (String type :
                List.of(
                        "net.minecraft.world.entity.player.Player",
                        "net.minecraft.client.player.AbstractClientPlayer",
                        "net.minecraft.client.player.RemotePlayer"))
            assertEquals(
                    0, named(CombatPinnedWritersTest.bytes(type), "getMainHandItem", desc), type);
    }

    private static long named(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))
                .count();
    }

    private static MethodNode only(ClassNode node, String name) {
        var found = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, found.size(), name);
        return found.getFirst();
    }

    private static List<String> fields(MethodNode method) {
        var out = new java.util.ArrayList<String>();
        for (var insn : method.instructions)
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD)
                out.add(field.owner + "." + field.name);
        return out;
    }
}
