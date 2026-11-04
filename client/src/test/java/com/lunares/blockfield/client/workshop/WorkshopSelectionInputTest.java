package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.mixin.TabletInputMixin;
import com.lunares.blockfield.client.render.WeaponHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

class WorkshopSelectionInputTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void selectionInterceptsUseAttackAndHeldAttackBeforeAnyVanillaPacketWithAnyItem()
            throws Exception {
        var mc = mock(Minecraft.class);
        var player = mock(LocalPlayer.class);
        mc.player = player;
        var connection = mock(ClientPacketListener.class);
        when(mc.getConnection()).thenReturn(connection);
        var selection = WorkshopToolsClient.class.getDeclaredField("selectionMode");
        selection.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class);
                var hud = mockStatic(WeaponHud.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mc);
            hud.when(WeaponHud::inWorkshop).thenReturn(true);
            WorkshopAiClient.availability(false);
            selection.setBoolean(null, true);
            var mixin = new TabletInputMixin() {};
            for (ItemStack item :
                    new ItemStack[] {
                        ItemStack.EMPTY,
                        new ItemStack(Items.STONE),
                        new ItemStack(Items.DIAMOND_PICKAXE)
                    }) {
                when(player.getMainHandItem()).thenReturn(item);
                WorkshopToolsClient.releaseUse(false);
                assertTrue(WorkshopToolsClient.useWand(mc, new BlockPos(2, 64, 3)));
                var use = new CallbackInfo("startUseItem", true);
                var method =
                        TabletInputMixin.class.getDeclaredMethod(
                                "blockfield$tabletBlocksUse", CallbackInfo.class);
                method.setAccessible(true);
                method.invoke(mixin, use);
                assertTrue(use.isCancelled());
                var attack = new CallbackInfoReturnable<Boolean>("startAttack", true);
                method =
                        TabletInputMixin.class.getDeclaredMethod(
                                "blockfield$tabletBlocksAttack", CallbackInfoReturnable.class);
                method.setAccessible(true);
                method.invoke(mixin, attack);
                assertTrue(attack.isCancelled());
                assertFalse(attack.getReturnValue());
                var held = new CallbackInfo("continueAttack", true);
                method =
                        TabletInputMixin.class.getDeclaredMethod(
                                "blockfield$tabletBlocksContinueAttack",
                                boolean.class,
                                CallbackInfo.class);
                method.setAccessible(true);
                method.invoke(mixin, true, held);
                assertTrue(held.isCancelled());
            }
            verify(connection, atLeastOnce()).sendCommand(anyString());
        } finally {
            selection.setBoolean(null, false);
            WorkshopToolsClient.resetWheelSession();
            WorkshopWandSelection.cancelSelect();
        }
    }
}
