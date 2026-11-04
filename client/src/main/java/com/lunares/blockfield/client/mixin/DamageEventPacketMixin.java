package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.network.PositionedDamageSource;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientboundDamageEventPacket.class)
public abstract class DamageEventPacketMixin {
    @Inject(method = "getSource", at = @At("HEAD"), cancellable = true)
    private void blockfield$preserveDamageEntities(
            Level level, CallbackInfoReturnable<DamageSource> cir) {
        var packet = (ClientboundDamageEventPacket) (Object) this;
        if (packet.sourcePosition().isEmpty()) return;
        Vec3 position = packet.sourcePosition().get();
        // Vanilla drops both entity IDs when a packet includes an explicit position.
        cir.setReturnValue(
                new PositionedDamageSource(
                        packet.sourceType(),
                        level.getEntity(packet.sourceDirectId()),
                        level.getEntity(packet.sourceCauseId()),
                        position));
    }
}
