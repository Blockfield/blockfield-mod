package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.workshop.WorkshopGhostClient;
import net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The F3+F4 switcher only sends its command with permission level 2; a workshop builder may send
 * the modes the server accepts (creative, spectator). Anything else the server refuses with a hint
 * — the switcher UI itself is vanilla and still lists all four.
 */
@Mixin(GameModeSwitcherScreen.class)
public abstract class WorkshopGameModeSwitcherMixin {
    @Redirect(
            method =
                    "switchToHoveredGameMode(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/debug/GameModeSwitcherScreen$GameModeIcon;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;hasPermissions(I)Z"))
    private static boolean blockfield$workshopSwitch(LocalPlayer player, int level) {
        return player.hasPermissions(level) || WorkshopGhostClient.mayDebugSwitch();
    }
}
