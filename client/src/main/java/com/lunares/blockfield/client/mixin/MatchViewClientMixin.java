package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.MatchViewClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A match map needs its whole radius drawn, not just the player's render distance: while the server
 * announces more (MatchView), raise it, and restore the player's value on the way out.
 */
@Mixin(ClientPacketListener.class)
public abstract class MatchViewClientMixin {
    @Inject(method = "handleSetChunkCacheRadius", at = @At("TAIL"))
    private void blockfield$matchRenderDistance(
            ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
        var renderDistance = Minecraft.getInstance().options.renderDistance();
        int next = MatchViewClient.onRadius(packet.getRadius(), renderDistance.get());
        if (next != renderDistance.get()) renderDistance.set(next);
    }

    /**
     * Leaving restores the player's value: `close` is on every disconnect path
     * (`Minecraft.disconnect`, kicks included); a world change (match to hub) restores before the
     * server re-announces the new level's radius on arrival, which raises it again in a match.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void blockfield$restoreOnDisconnect(CallbackInfo ci) {
        blockfield$restore();
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void blockfield$restoreOnWorldChange(ClientboundRespawnPacket packet, CallbackInfo ci) {
        blockfield$restore();
    }

    @Unique
    private static void blockfield$restore() {
        var renderDistance = Minecraft.getInstance().options.renderDistance();
        int next = MatchViewClient.onLeave(renderDistance.get());
        if (next != renderDistance.get()) renderDistance.set(next);
    }
}
