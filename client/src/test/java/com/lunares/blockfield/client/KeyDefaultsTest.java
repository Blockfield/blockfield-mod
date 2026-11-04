package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

class KeyDefaultsTest {
    @Test
    void parkourStaysBoundWithoutStealingAimOrReload() {
        for (String action :
                new String[] {
                    "Crawl",
                    "Breakfall",
                    "ClingToCliff",
                    "RideZipline",
                    "WallSlide",
                    "Vault",
                    "HangDown",
                    "Dodge",
                    "HorizontalWallRun",
                    "HideInBlock"
                }) {
            var key = KeyDefaults.DEFAULTS.get("key.parcool." + action);
            assertNotNull(key, action);
            assertNotEquals(InputConstants.UNKNOWN, key, action);
            assertNotEquals("key.mouse.right", key.getName(), action);
            assertNotEquals("key.keyboard.r", key.getName(), action);
        }
    }

    @Test
    void fastRunHasNoIndependentBinding() {
        assertEquals(InputConstants.UNKNOWN, KeyDefaults.DEFAULTS.get("key.parcool.FastRun"));
        assertEquals("key.keyboard.left.shift", KeyDefaults.DEFAULTS.get("key.sprint").getName());
    }

    @Test
    void heldGripCanBeCombinedWithAnotherJump() {
        for (String action : new String[] {"ClingToCliff", "HangDown", "RideZipline"}) {
            assertNotEquals(
                    "key.keyboard.space",
                    KeyDefaults.DEFAULTS.get("key.parcool." + action).getName());
        }
    }

    @Test
    void unrelatedCombatAndVoiceActionsUseDifferentKeys() {
        assertDifferent("key.tacz.melee.desc", "key.tacz.zoom.desc");
        assertDifferent("key.parcool.Crawl", "key.tacz.refit.desc");
        assertDifferent("key.push_to_talk", "key.blockfield.radio");
        assertDifferent("key.push_to_talk", "key.superbwarfare.dismount");
        assertDifferent("key.superbwarfare.interact", "key.superbwarfare.dismount");
        assertDifferent("key.superbwarfare.release_decoy", "key.tacz.melee.desc");
    }

    @Test
    void fInspectsAndGunMeleeIsUnbound() {
        assertEquals("key.keyboard.f", KeyDefaults.DEFAULTS.get("key.tacz.inspect.desc").getName());
        assertEquals(InputConstants.UNKNOWN, KeyDefaults.DEFAULTS.get("key.tacz.melee.desc"));
        assertEquals(InputConstants.UNKNOWN, KeyDefaults.DEFAULTS.get("key.superbwarfare.melee"));
        KeyDefaults.DEFAULTS.forEach(
                (name, key) -> {
                    if (!name.equals("key.tacz.inspect.desc"))
                        assertNotEquals("key.keyboard.f", key.getName(), name);
                });
    }

    @Test
    void previousMeleeOnFMovesOnlyWhenUntouched() {
        KeyDefaultHistory history = new KeyDefaultHistory();
        history.record("key.tacz.melee.desc", "key.keyboard.f");
        history.record("key.tacz.inspect.desc", InputConstants.UNKNOWN.getName());
        assertTrue(
                history.shouldUpdate(
                        "key.tacz.melee.desc",
                        "key.keyboard.f",
                        "key.keyboard.v",
                        "key.keyboard.v"));
        assertFalse(
                history.shouldUpdate(
                        "key.tacz.melee.desc",
                        "key.keyboard.g",
                        "key.keyboard.v",
                        "key.keyboard.v"));
        assertTrue(
                history.shouldUpdate(
                        "key.tacz.inspect.desc",
                        InputConstants.UNKNOWN.getName(),
                        "key.keyboard.h",
                        null));
        assertFalse(
                history.shouldUpdate(
                        "key.tacz.inspect.desc", "key.keyboard.i", "key.keyboard.h", null));
    }

    @Test
    void mOpensOnlyTheTablet() {
        assertEquals("key.keyboard.m", KeyDefaults.DEFAULTS.get("key.blockfield.tablet").getName());
        KeyDefaults.DEFAULTS.forEach(
                (name, key) -> {
                    if (!name.equals("key.blockfield.tablet"))
                        assertNotEquals("key.keyboard.m", key.getName(), name);
                });
    }

    private void assertDifferent(String first, String second) {
        assertNotEquals(
                KeyDefaults.DEFAULTS.get(first),
                KeyDefaults.DEFAULTS.get(second),
                first + " / " + second);
    }
}
