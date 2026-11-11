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
 * InvalidInjectionException}. Same discipline as KillFeedMixinContractTest: pin our annotation
 * strings and the loadable target shapes. SBW classes stay unloaded (their kotlinx dependency is
 * not on the unit-test classpath) and {@code @Mixin} itself is CLASS-retention, so only
 * method-level annotations are visible here.
 */
class KillFeedWeaponContractTest {
    @Test
    void redirectReadsTheHandItemCallInsideHandler() throws Exception {
        var redirect =
                com.lunares.blockfield.client.mixin.KillMessageWeaponMixin.class.getDeclaredMethod(
                        "blockfield$deathEventWeapon",
                        net.minecraft.world.entity.LivingEntity.class);
        assertEquals(net.minecraft.world.item.ItemStack.class, redirect.getReturnType());
        var annotation = redirect.getAnnotation(Redirect.class);
        assertNotNull(annotation, "redirect must carry @Redirect");
        assertTrue(
                Arrays.asList(annotation.method()).contains("handler"),
                "@Redirect must target handler");
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
    void handItemReadExistsOnTheRealTarget() throws Exception {
        var read =
                net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("getMainHandItem");
        assertEquals(net.minecraft.world.item.ItemStack.class, read.getReturnType());
    }

    @Test
    void killOverlayReplacementTargetsShouldRender() throws Exception {
        var handler =
                com.lunares.blockfield.client.mixin.SbwKillOverlayMixin.class.getDeclaredMethod(
                        "blockfield$replacedByKillfeedHud",
                        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
                                .class);
        var inject = handler.getAnnotation(org.spongepowered.asm.mixin.injection.Inject.class);
        assertNotNull(inject, "handler must carry @Inject");
        assertTrue(
                Arrays.asList(inject.method()).contains("shouldRender"),
                "@Inject must target shouldRender");
    }
}
