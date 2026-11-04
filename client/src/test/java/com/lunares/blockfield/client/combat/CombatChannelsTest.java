package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.mixin.CombatRaknetChannelMixin;
import io.netty.buffer.Unpooled;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

class CombatChannelsTest {
    @Test
    void effectiveChannelsAgainstChecksumMatchedRaknetifyBf3() throws Exception {
        Path jar = Path.of(System.getProperty("blockfield.test.raknetifyJar"));
        assertEquals(
                "c7ed7782a940b1e880c1c1d1639a2cc27a176c228ec2a7c99435d5a13edbc741",
                java.util.HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(jar))));
        String packRoot = System.getProperty("blockfield.test.combatPackRoot");
        if (packRoot != null) {
            String manifest = Files.readString(Path.of(packRoot, "client/mods/raknetify.pw.toml"));
            assertTrue(manifest.contains("raknetify-fabric-0.1.0+alpha.5.1-bf3-all.jar"));
            assertTrue(
                    manifest.contains(
                            "0448dc7581463587f5d51fbfcaf7a9f970b55b7d234b85116c278278533dbe8d81e99ba687f3bee20001e4f533377c81f79bc78710f0e0ab4ee5c4e79cc87875"));
        }
        // r8 §12 and pack raknetify.pw.toml SHA512. These intermediary ids come from
        // vanilla 1.21.1 mappings.tiny, not the implementation's raw class predicate.
        Map<String, Class<?>> classes =
                Map.of(
                        "net.minecraft.class_2868",
                        ServerboundSetCarriedItemPacket.class,
                        "net.minecraft.class_2838",
                        ServerboundPickItemPacket.class,
                        "net.minecraft.class_2813",
                        ServerboundContainerClickPacket.class,
                        "net.minecraft.class_2848",
                        ServerboundPlayerCommandPacket.class,
                        "net.minecraft.class_2846",
                        ServerboundPlayerActionPacket.class,
                        "net.minecraft.class_2824",
                        ServerboundInteractPacket.class,
                        "net.minecraft.class_2885",
                        ServerboundUseItemOnPacket.class,
                        "net.minecraft.class_2886",
                        ServerboundUseItemPacket.class,
                        "net.minecraft.class_2817",
                        ServerboundCustomPayloadPacket.class,
                        "net.minecraft.class_2658",
                        net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.class);
        var resolver = mock(MappingResolver.class);
        when(resolver.getCurrentRuntimeNamespace()).thenReturn("named");
        when(resolver.mapClassName(eq("intermediary"), anyString()))
                .thenAnswer(
                        call -> {
                            Class<?> c = classes.get(call.<String>getArgument(1));
                            return c == null
                                    ? "unavailable." + call.<String>getArgument(1)
                                    : c.getName();
                        });
        var fabric = mock(FabricLoader.class);
        when(fabric.getMappingResolver()).thenReturn(resolver);
        try (var api = mockStatic(FabricLoader.class);
                var loader =
                        new URLClassLoader(
                                new java.net.URL[] {jar.toUri().toURL()},
                                getClass().getClassLoader())) {
            api.when(FabricLoader::getInstance).thenReturn(fabric);
            var channels =
                    loader.loadClass(
                            "com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel");
            @SuppressWarnings("unchecked")
            var map = (Map<String, Integer>) channels.getField("identifier2channel").get(null);
            CombatChannels.install(map);
            var handlerType =
                    loader.loadClass(
                            "com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel$OverrideHandler");
            var handler =
                    handlerType
                            .getConstructor(IntPredicate.class)
                            .newInstance((IntPredicate) id -> id == 77);
            var override =
                    handlerType.getMethod(
                            "getChannelOverride", io.netty.buffer.ByteBuf.class, boolean.class);
            var ids = new java.util.ArrayList<>(CombatAttackSenderTest.IDS);
            ids.add("blockfield:combat_view");
            ids.add("blockfield:anim_wire_c2s");
            ids.add("blockfield:animation_applied");
            for (String id : ids) {
                var bytes = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    bytes.writeVarInt(77);
                    bytes.writeUtf(id);
                    assertEquals(2, override.invoke(handler, bytes, false), id);
                } finally {
                    bytes.release();
                }
            }
            var raw =
                    loader.loadClass(
                            "com.ishland.raknetify.fabric.common.connection.RakNetMultiChannel");
            var nativeChannel =
                    raw.getMethod("getPacketChannelOverride", Class.class, boolean.class);
            var mixin =
                    CombatRaknetChannelMixin.class.getDeclaredMethod(
                            "blockfield$combatChannel",
                            Class.class,
                            boolean.class,
                            CallbackInfoReturnable.class);
            mixin.setAccessible(true);
            for (Class<?> type :
                    java.util.List.of(
                            ServerboundInteractPacket.class,
                            ServerboundUseItemOnPacket.class,
                            ServerboundUseItemPacket.class,
                            ServerboundPlayerActionPacket.class,
                            ServerboundSetCarriedItemPacket.class,
                            ServerboundPickItemPacket.class,
                            ServerboundContainerClickPacket.class,
                            ServerboundPlayerCommandPacket.class,
                            ServerboundCustomPayloadPacket.class)) {
                assertEquals(
                        type == ServerboundPlayerActionPacket.class
                                        || type == ServerboundContainerClickPacket.class
                                        || type == ServerboundPlayerCommandPacket.class
                                ? 1
                                : type == ServerboundSetCarriedItemPacket.class
                                                || type == ServerboundPickItemPacket.class
                                                || type == ServerboundCustomPayloadPacket.class
                                        ? 3
                                        : 2,
                        nativeChannel.invoke(null, type, true),
                        type.getName());
                var result =
                        new CallbackInfoReturnable<Integer>(
                                "test", true, (Integer) nativeChannel.invoke(null, type, true));
                mixin.invoke(null, type, true, result);
                assertTrue(result.isCancelled());
                assertEquals(2, result.getReturnValueI());
            }
            // Identifier overrides run first; zero means class fallback, now C2S channel 2.
            for (String id :
                    java.util.List.of(
                            "tacz:client_player_aim",
                            "superbwarfare:mouse_move",
                            "superbwarfare:switch_vehicle_weapon",
                            "other:gameplay",
                            "blockfield:workshop_ai_audio")) {
                var bytes = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    bytes.writeVarInt(77);
                    bytes.writeUtf(id);
                    assertEquals(
                            id.equals("blockfield:workshop_ai_audio") ? 3 : 0,
                            override.invoke(handler, bytes, false),
                            id);
                } finally {
                    bytes.release();
                }
            }
            assertEquals(
                    3,
                    nativeChannel.invoke(
                            null,
                            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
                                    .class,
                            true));
        }
    }

    @Test
    void gameplayDefaultsToCombatChannelAndOnlyAudioIsExplicitBulkException() {
        var map = new HashMap<String, Integer>();
        map.put("other:packet", 7);
        CombatChannels.install(map);
        assertEquals(7, map.get("other:packet"));
        assertTrue(CombatChannels.rawCombatClass(ServerboundCustomPayloadPacket.class));
        assertEquals(3, map.get("blockfield:workshop_ai_audio"));
    }
}
