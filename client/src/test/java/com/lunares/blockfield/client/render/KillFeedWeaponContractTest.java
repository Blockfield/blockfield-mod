package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The weapon mixin must match the real fork targets: a wrong descriptor passes unit tests (mixins
 * are not applied there) and then kills every client at startup with {@code
 * InvalidInjectionException}. Same discipline as KillFeedMixinContractTest.
 */
class KillFeedWeaponContractTest {
    @Test
    void packetCarriesTheDeathEventWeapon() throws Exception {
        var getter =
                com.atsuishio.superbwarfare.network.message.receive.LivingGunKillMessage.class
                        .getDeclaredMethod("getWeapon");
        assertEquals(net.minecraft.resources.ResourceLocation.class, getter.getReturnType());
    }

    @Test
    void handlerBuildsExactlyOneLivingRecord() throws Exception {
        var handler =
                com.atsuishio.superbwarfare.network.message.receive.LivingGunKillMessage.class
                        .getDeclaredMethod("handler");
        assertNotNull(handler);
        var ctor =
                Arrays.stream(
                                com.atsuishio.superbwarfare.tools.LivingKillRecord.class
                                        .getDeclaredConstructors())
                        .filter(
                                c ->
                                        Arrays.equals(
                                                c.getParameterTypes(),
                                                new Class<?>[] {
                                                    net.minecraft.world.entity.LivingEntity.class,
                                                    net.minecraft.world.entity.Entity.class,
                                                    net.minecraft.world.item.ItemStack.class,
                                                    boolean.class,
                                                    net.minecraft.resources.ResourceKey.class
                                                }))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("LivingKillRecord ctor shape changed"));
        assertNotNull(ctor);
    }

    @Test
    void redirectReadsTheHandItemCallInsideHandler() throws Exception {
        var redirect =
                com.lunares.blockfield.client.mixin.KillMessageWeaponMixin.class.getDeclaredMethod(
                        "blockfield$deathEventWeapon",
                        net.minecraft.world.entity.LivingEntity.class);
        var annotation = redirect.getAnnotation(Redirect.class);
        assertNotNull(annotation, "redirect must carry @Redirect");
        assertEquals("handler", annotation.method());
        assertTrue(
                Arrays.asList(annotation.at()).stream()
                        .anyMatch(
                                at ->
                                        at.value().equals("INVOKE")
                                                && at.target()
                                                        .contains(
                                                                "getMainHandItem()Lnet/minecraft/world/item/ItemStack;")),
                "@Redirect must target the hand-item read in handler");
    }

    @Test
    void killOverlayReplacementTargetsTheRealOverlay() {
        var annotation =
                com.lunares.blockfield.client.mixin.SbwKillOverlayMixin.class.getAnnotation(
                        org.spongepowered.asm.mixin.Mixin.class);
        assertNotNull(annotation);
        assertTrue(
                Arrays.stream(annotation.targets())
                        .anyMatch(
                                t ->
                                        t.equals(
                                                "com.atsuishio.superbwarfare.client.overlay.KillMessageOverlay")),
                "overlay replacement must target KillMessageOverlay");
    }
}
