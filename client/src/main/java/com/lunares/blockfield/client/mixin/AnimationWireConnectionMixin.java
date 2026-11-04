package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.AnimationWireReceiver;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Install once on the connection, rather than on a protocol-specific listener. */
@Mixin(Connection.class)
public abstract class AnimationWireConnectionMixin implements AnimationWireReceiver.Access {
    @Unique private AnimationWireReceiver blockfield$wire;

    @Inject(method = "channelActive", at = @At("TAIL"), require = 1)
    private void blockfield$wireStage(ChannelHandlerContext context, CallbackInfo ci) {
        if (((Connection) (Object) this).getReceiving() != PacketFlow.CLIENTBOUND) return;
        blockfield$wire = new AnimationWireReceiver();
        context.pipeline().addBefore("packet_handler", AnimationWireReceiver.NAME, blockfield$wire);
    }

    @Override
    public AnimationWireReceiver blockfield$animationWire() {
        return blockfield$wire;
    }
}
