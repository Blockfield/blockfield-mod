package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.LdcInsnNode;

class CombatWireIdsTest {
    static List<String> constants(org.objectweb.asm.tree.ClassNode n) {
        var out = new java.util.ArrayList<String>();
        for (var m : n.methods)
            for (var ins : m.instructions)
                if (ins instanceof LdcInsnNode ldc && ldc.cst instanceof String text) out.add(text);
        return out;
    }

    @Test
    void idsMatchPinnedBf8Bf24AndBf2C2sClasses() throws Exception {
        try (var sbw = new ZipFile(System.getProperty("blockfield.test.sbwJar"));
                var tacz = new ZipFile(System.getProperty("blockfield.test.taczJar"));
                var lr = new ZipFile(System.getProperty("blockfield.test.lrJar"))) {
            // bf24 NetworkRegistryKt.payloadType strips Message then per-uppercase snake_case,
            // namespace Mod.MODID=superbwarfare. r8 §3.2; these are the actual C2S subclasses.
            for (var pair :
                    Map.of(
                                    "ShootMessage",
                                    "shoot",
                                    "VehicleFireMessage",
                                    "vehicle_fire",
                                    "MeleeAttackMessage",
                                    "melee_attack",
                                    "FireKeyMessage",
                                    "fire_key",
                                    "DroneFireMessage",
                                    "drone_fire",
                                    "ArtilleryIndicatorFireMessage",
                                    "artillery_indicator_fire",
                                    "LungeMineAttackMessage",
                                    "lunge_mine_attack",
                                    "InteractMessage",
                                    "interact")
                            .entrySet()) {
                var cls =
                        CombatPinnedWritersTest.pinned(
                                sbw,
                                "com.atsuishio.superbwarfare.network.message.send."
                                        + pair.getKey());
                assertEquals(
                        "com/atsuishio/superbwarfare/network/ServerPacketPayload", cls.superName);
                assertTrue(
                        CombatAttackSender.CUSTOM_IDS.contains("superbwarfare:" + pair.getValue()));
            }
            var registry =
                    CombatPinnedWritersTest.pinned(
                            sbw, "com.atsuishio.superbwarfare.network.NetworkRegistryKt");
            assertTrue(constants(registry).contains("Message"));
            assertTrue(registry.methods.stream().anyMatch(m -> m.name.equals("payloadType")));
            for (var pair :
                    Map.of("Shoot", "client_player_shoot", "Melee", "client_player_melee")
                            .entrySet()) {
                var cls =
                        CombatPinnedWritersTest.pinned(
                                tacz,
                                "com.tacz.guns.network.message.ClientMessagePlayer"
                                        + pair.getKey());
                assertTrue(constants(cls).contains(pair.getValue()));
                assertTrue(constants(cls).contains("tacz"));
            }
            for (var pair :
                    Map.of(
                                    "CMeleeAttackRequest",
                                    "melee_attack_request",
                                    "CPrepareMeleeAttack",
                                    "prepare_melee_attack")
                            .entrySet()) {
                var cls =
                        CombatPinnedWritersTest.pinned(
                                lr, "me.xjqsh.lrtactical.network.message." + pair.getKey());
                assertTrue(constants(cls).contains(pair.getValue()));
            }
        }
    }
}
