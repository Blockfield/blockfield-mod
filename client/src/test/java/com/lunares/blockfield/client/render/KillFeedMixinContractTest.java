package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * The level-clear mixin must match the real {@code Minecraft.setLevel} target: a wrong descriptor
 * passes unit tests (mixins are not applied there) and then kills every client at startup with
 * {@code InvalidInjectionException}.
 */
class KillFeedMixinContractTest {
    @Test
    void levelClearHandlerMatchesTheRealSetLevelTarget() throws Exception {
        var target =
                Arrays.stream(net.minecraft.client.Minecraft.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("setLevel"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Minecraft.setLevel is missing"));
        assertEquals(
                java.util.List.of(
                        net.minecraft.client.multiplayer.ClientLevel.class,
                        net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason.class),
                java.util.List.of(target.getParameterTypes()),
                "Minecraft.setLevel target signature");
        var handler =
                com.lunares.blockfield.client.mixin.KillFeedLevelMixin.class.getDeclaredMethod(
                        "blockfield$clearKillFeed",
                        net.minecraft.client.multiplayer.ClientLevel.class,
                        net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason.class,
                        org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        var inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "handler must carry @Inject");
        assertTrue(
                Arrays.asList(inject.method()).contains("setLevel"),
                "@Inject must target setLevel");
    }
}
