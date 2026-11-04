package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.AnimationWireReceiver;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientCommonPacketListenerImpl.class)
public interface AnimationWireListenerAccessor extends AnimationWireReceiver.ListenerAccess {
    @Accessor("connection")
    Connection blockfield$wireConnection();
}
