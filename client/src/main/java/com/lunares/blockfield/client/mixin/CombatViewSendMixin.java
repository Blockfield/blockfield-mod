package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.combat.ClientCombatScene;
import com.lunares.blockfield.client.combat.CombatAttackSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class CombatViewSendMixin
        implements com.lunares.blockfield.client.combat.CombatViewSender {
    @Override
    public CombatAttackSender blockfield$combatSender() {
        return blockfield$combat;
    }

    @Unique private final CombatAttackSender blockfield$combat = new CombatAttackSender();

    @WrapMethod(method = "send", require = 1)
    private void blockfield$ticketBeforeAttack(Packet<?> packet, Operation<Void> original) {
        blockfield$combat.send(
                packet,
                Minecraft.getInstance().player,
                () -> ClientCombatScene.nextTicket(ClientCombatScene.nowMs()),
                p -> original.call(p));
    }
}
