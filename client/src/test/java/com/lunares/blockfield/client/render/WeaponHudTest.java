package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WeaponHudTest {
    @Test
    void chatAndDelayedMessageControlStayAboveTheScaledBottomHud() {
        for (int width : new int[] {320, 360, 480, 640, 854, 1280, 1920}) {
            int inset = WeaponHud.chatBottomInset(width);
            assertTrue(inset >= 40);
            assertTrue(inset - 9 >= Math.ceil(65 * WeaponHud.hudScale(width)) + 4);
        }
    }

    @Test
    void hudMotionSnapsOnEntryAndKeepsRapidChangesContinuous() {
        var motion = new com.lunares.blockfield.client.gui.UiMotion();
        long duration = com.lunares.blockfield.client.gui.UiMotion.DURATION_NS;
        assertEquals(1f, WeaponHud.animate(motion, 1f, 0, true));
        assertEquals(1f, WeaponHud.animate(motion, 0f, 0, false));
        float middle = WeaponHud.animate(motion, 0f, duration / 2, false);
        assertTrue(middle > 0 && middle < 1);
        assertEquals(middle, WeaponHud.animate(motion, 1f, duration / 2, false));
        assertEquals(1f, WeaponHud.animate(motion, 1f, duration * 2, false));
        assertEquals(0.4f, WeaponHud.animate(motion, 0.4f, duration * 2, true));
    }

    @Test
    void vectorSilhouettesKeepDetailAndAntialiasedEdges() throws java.io.IOException {
        var sheet =
                new java.awt.image.BufferedImage(
                        320, 20 * 192, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = sheet.createGraphics();
        int row = 0;
        for (String kind :
                new String[] {
                    "knife",
                    "smoke",
                    "frag",
                    "flash",
                    "molotov",
                    "medkit",
                    "plate",
                    "drone",
                    "scout_drone",
                    "monitor",
                    "tablet",
                    "shield",
                    "bullet",
                    "round_pistol",
                    "round_rifle",
                    "round_heavy",
                    "round_sniper",
                    "shell",
                    "rocket",
                    "runner"
                }) {
            boolean ammunition =
                    kind.startsWith("round_")
                            || kind.equals("bullet")
                            || kind.equals("shell")
                            || kind.equals("rocket");
            var image =
                    ammunition
                            ? WeaponHudSilhouettes.ammunitionRaster(kind, 320, 192)
                            : WeaponHudSilhouettes.raster(kind, 320, 192);
            int[] bounds =
                    ItemSilhouette.bounds(image.getWidth(), image.getHeight(), image::getRGB);
            assertTrue(bounds[2] > (kind.startsWith("round_") ? 35 : 50) && bounds[3] > 40, kind);
            boolean edge = false;
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    edge |= alpha > 0 && alpha < 255;
                }
            assertTrue(edge, kind);
            graphics.drawImage(image, 0, row++ * 192, null);
        }
        graphics.dispose();
        // The medkit's cross is cut out: (79, 60) of its 28..131 x 19..85 shape, scaled by 192/66.
        assertEquals(
                0,
                WeaponHudSilhouettes.raster("medkit", 320, 192)
                                .getRGB(
                                        (int) ((79 - 28) * 192 / 66.0),
                                        (int) ((60 - 19) * 192 / 66.0))
                        >>> 24);
        var file = new java.io.File("build/reports/hud-silhouettes.png");
        file.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(sheet, "PNG", file);
    }

    @Test
    void silhouetteUsesVisibleBoundsAndAnAmberToPaperGradient() {
        assertArrayEquals(
                new int[] {2, 1, 2, 3},
                ItemSilhouette.bounds(
                        8, 8, (x, y) -> x >= 2 && x <= 3 && y >= 1 && y <= 3 ? 0xFF000000 : 0));
        assertArrayEquals(new int[] {0, 0, 1, 1}, ItemSilhouette.bounds(8, 8, (x, y) -> 0));
        assertEquals(0x24A5F5, ItemSilhouette.gradient(0, 4));
        assertEquals(0xD0E7F3, ItemSilhouette.gradient(3, 4));
        assertEquals(ItemSilhouette.gradient(0, 4), ItemSilhouette.gradient(0, 1));
    }

    @Test
    void ammunitionFamiliesFollowCaliberIdsAndUnknownRoundsKeepTheDefault() {
        assertEquals("round_pistol", WeaponHudSilhouettes.ammunition("tacz:9mm"));
        assertEquals("round_pistol", WeaponHudSilhouettes.ammunition("50ae"));
        assertEquals("round_rifle", WeaponHudSilhouettes.ammunition("tacz:556x45"));
        assertEquals("round_rifle", WeaponHudSilhouettes.ammunition("third_pack:762x39"));
        assertEquals("round_heavy", WeaponHudSilhouettes.ammunition("tacz:30_06"));
        assertEquals("round_sniper", WeaponHudSilhouettes.ammunition("tacz:338"));
        assertEquals("shell", WeaponHudSilhouettes.ammunition("tacz:12g"));
        assertEquals(
                "rocket", WeaponHudSilhouettes.ammunition("superbwarfare:rpg_rocket_standard"));
        assertEquals("bullet", WeaponHudSilhouettes.ammunition("third_pack:plasma"));
        assertEquals("bullet", WeaponHudSilhouettes.ammunition(""));
        assertEquals("bullet", WeaponHudSilhouettes.ammunition(null));
    }

    @Test
    void caliberPipsFitTheirRowsWithAVisibleGapAndDifferentProportions() {
        float scale = 3 * WeaponHud.hudScale(640);
        for (String shape :
                new String[] {
                    "bullet",
                    "round_pistol",
                    "round_rifle",
                    "round_heavy",
                    "round_sniper",
                    "shell",
                    "rocket"
                }) {
            for (int size : new int[] {1, 8, 17, 30, 60, 61, 100, 200, 1000}) {
                float pitch = WeaponHud.pipPitch(shape, size),
                        width = WeaponHud.pipWidth(shape, size);
                assertTrue(width > 0, shape);
                assertTrue(pitch - width >= 0.66f, shape);
                assertTrue(WeaponHud.magazineWidth(shape, size) <= 119, shape);
                if (size == 30 && !shape.equals("rocket"))
                    assertTrue(WeaponHud.magazineWidth(shape, size) <= 110, shape);
                var icon =
                        WeaponHudSilhouettes.ammunitionRaster(
                                shape,
                                Math.round(width * scale),
                                Math.round(WeaponHud.pipHeight(shape, size) * scale));
                assertTrue(icon.getWidth() <= Math.round(width * scale), shape);
                assertTrue(
                        icon.getHeight() <= Math.round(WeaponHud.pipHeight(shape, size) * scale),
                        shape);
            }
            assertEquals(0, WeaponHud.magazineWidth(shape, 0));
            assertTrue(WeaponHud.pipHeight(shape, 100) >= 10, shape);
        }
        assertTrue(WeaponHud.pipHeight("round_pistol") < WeaponHud.pipHeight("round_rifle"));
        assertTrue(WeaponHud.pipHeight("round_sniper") > WeaponHud.pipHeight("round_rifle"));
        assertTrue(WeaponHud.pipWidth("shell", 8) > WeaponHud.pipWidth("round_rifle", 30));
    }

    @Test
    void chamberRoundHasItsOwnPipWithoutClippingTheNinthBenelliShell() {
        var bolt = com.tacz.guns.resource.pojo.data.gun.Bolt.MANUAL_ACTION;
        int chamber = WeaponHud.chamberRound(bolt, true);
        int capacity = WeaponHud.loadedCapacity(8, chamber);
        var display = new WeaponHud.AmmoDisplay();
        display.update("0:benelli", 8 + chamber, 20, false);
        assertEquals(9, display.loaded());
        assertEquals(9, capacity);
        assertEquals(9f, display.pips(capacity, 9, -1, 0));
        assertEquals(8f, WeaponHud.magazineRounds(display.pips(capacity, 9, -1, 0), chamber));
        assertEquals(0f, WeaponHud.magazineRounds(1, chamber));
        assertEquals(8f, WeaponHud.magazineRounds(8, WeaponHud.chamberRound(bolt, false)));
        assertEquals(
                1,
                WeaponHud.chamberRound(
                        com.tacz.guns.resource.pojo.data.gun.Bolt.CLOSED_BOLT, true));
        int open =
                WeaponHud.chamberRound(com.tacz.guns.resource.pojo.data.gun.Bolt.OPEN_BOLT, true);
        assertEquals(-1, open);
        assertEquals(8, WeaponHud.loadedCapacity(8, open));
        assertEquals(8f, WeaponHud.magazineRounds(8, open));
        assertEquals(0, WeaponHud.chamberOffset("shell", 8, open));
        assertTrue(WeaponHud.chamberOffset("shell", 8, chamber) > WeaponHud.pipWidth("shell", 8));
        assertEquals(60, WeaponHud.pipCount(60));
        assertEquals(20, WeaponHud.pipCount(100));
    }

    @Test
    void largeMagazineGroupsKeepExactPartialProgressAndAtMostTwentyCartridges() {
        assertEquals(1, WeaponHud.roundsPerPip(60));
        assertEquals(60, WeaponHud.pipCount(60));
        assertEquals(5, WeaponHud.roundsPerPip(100));
        assertEquals(20, WeaponHud.pipCount(100));
        assertEquals(5, WeaponHud.roundsPerPip(75));
        assertEquals(15, WeaponHud.pipCount(75));
        assertEquals(10, WeaponHud.roundsPerPip(150));
        assertEquals(15, WeaponHud.pipCount(150));
        assertEquals(10, WeaponHud.roundsPerPip(200));
        assertEquals(20, WeaponHud.pipCount(200));
        assertEquals(25, WeaponHud.roundsPerPip(250));
        assertEquals(10, WeaponHud.pipCount(250));
        assertEquals(0f, WeaponHud.pipFill(0, 100, 0));
        assertEquals(0.5f, WeaponHud.pipFill(2.5f, 100, 0));
        assertEquals(1f, WeaponHud.pipFill(50, 100, 9));
        assertEquals(0f, WeaponHud.pipFill(50, 100, 10));
        assertEquals(0.4f, WeaponHud.pipFill(52, 100, 10));
        assertEquals(1f, WeaponHud.pipFill(100, 100, 19));
        assertEquals(0.5f, WeaponHud.pipFill(65.5f, 66, 13));
        assertEquals(1f, WeaponHud.pipFill(66, 66, 13));
        for (int size : new int[] {61, 66, 75, 100, 150, 200, 250, 500, 1000, 2000}) {
            assertTrue(WeaponHud.pipCount(size) <= 20);
            assertEquals(1f, WeaponHud.pipFill(size, size, WeaponHud.pipCount(size) - 1));
        }
        assertEquals(92, WeaponHud.magazineWidth("round_rifle", 100));
        assertEquals(96, WeaponHud.magazineWidth("round_rifle", 200));
    }

    @Test
    void ammunitionHasAContinuousBodyWithCreamProjectilesAndBrassCasings() {
        for (String kind :
                new String[] {
                    "bullet",
                    "round_pistol",
                    "round_rifle",
                    "round_heavy",
                    "round_sniper",
                    "shell",
                    "rocket"
                }) {
            var image = WeaponHudSilhouettes.ammunitionRaster(kind, 80, 240);
            int x = image.getWidth() / 2;
            for (int y = image.getHeight() / 6; y < image.getHeight() - 1; y++)
                assertTrue(image.getRGB(x, y) >>> 24 > 0, kind);
            int top = image.getRGB(x, image.getHeight() / 5),
                    bottom = image.getRGB(x, image.getHeight() * 9 / 10);
            if (kind.equals("shell") || kind.equals("rocket"))
                assertNotEquals(top & 0xFFFFFF, bottom & 0xFFFFFF, kind);
            else {
                assertTrue((top >> 16 & 255) - (top & 255) < 60, kind);
                assertTrue((bottom >> 16 & 255) - (bottom & 255) > 60, kind);
            }
        }
        assertEquals(0x80D0E7F3, ItemSilhouette.abgr(0x80F3E7D0));
        assertEquals(0x8068AACC, ItemSilhouette.abgr(0x80CCAA68));
    }

    @Test
    void sbwRocketUsesNativeReloadTimeAndResetsItsSmoothFillOnCancelSwapOrExit() {
        assertEquals(0f, WeaponHud.reloadTimerProgress(102, 100));
        assertEquals(0f, WeaponHud.reloadTimerProgress(101, 100));
        assertEquals(0.5f, WeaponHud.reloadTimerProgress(51, 100));
        assertEquals(0.5f, WeaponHud.reloadTimerProgress(21, 40));
        assertEquals(1f, WeaponHud.reloadTimerProgress(1, 100));
        assertEquals(0f, WeaponHud.reloadTimerProgress(1, 0));
        var fill = new WeaponHud.RocketFill();
        long duration = com.lunares.blockfield.client.gui.UiMotion.DURATION_NS;
        assertEquals(0f, fill.value("3:rpg", 0, false, 0, 0));
        assertEquals(0f, fill.value("3:rpg", 0, true, 0.5f, 0));
        float middle = fill.value("3:rpg", 0, true, 0.5f, duration / 2);
        assertTrue(middle > 0 && middle < 0.5f);
        assertEquals(0.5f, fill.value("3:rpg", 0, true, 0.4f, duration));
        assertEquals(1f, fill.value("3:rpg", 1, false, 1, duration + 1));
        assertEquals(0f, fill.value("3:rpg", 0, true, 0.5f, duration + 2));
        assertEquals(0f, fill.value("3:rpg", 0, false, 0.5f, duration + 3));
        assertEquals(0f, fill.value("4:rpg", 0, true, 0.5f, duration + 4));
        fill.clear();
        assertEquals(0f, fill.value("4:rpg", 0, true, 0.5f, duration + 5));
    }

    @Test
    void countsOnlyTheSelectedThrowableTypeDespiteSharingOneBaseItem() {
        assertFalse(
                WeaponHud.sameSupply(
                        "lrtactical:throwable",
                        "lrtactical:m67",
                        "lrtactical:throwable",
                        "lrtactical:molotov"));
        assertTrue(
                WeaponHud.sameSupply(
                        "lrtactical:throwable",
                        "lrtactical:m67",
                        "lrtactical:throwable",
                        "lrtactical:m67"));
        assertFalse(
                WeaponHud.sameSupply("superbwarfare:drone", "", "superbwarfare:scout_drone", ""));
    }

    @Test
    void boundsCounterValuesAndPreservesAnExplicitInfiniteSupply() {
        assertEquals("0", WeaponHud.amount(-3, false));
        assertEquals("36", WeaponHud.amount(36, false));
        assertEquals("9999", WeaponHud.amount(Integer.MAX_VALUE, false));
        assertEquals("∞", WeaponHud.amount(0, true));
    }

    @Test
    void monitorDoesNotPresentAnAbsentOrMalformedDroneAsLinked() {
        String drone = "495b4f05-9c65-4603-93e5-c5821f7348b2";
        assertTrue(WeaponHud.monitorLinked(true, drone));
        assertFalse(WeaponHud.monitorLinked(false, drone));
        assertFalse(WeaponHud.monitorLinked(true, ""));
        assertFalse(WeaponHud.monitorLinked(true, "none"));
        assertFalse(WeaponHud.monitorLinked(true, "1-1-1-1-1"));
    }

    @Test
    void linesKeepOneWholePixelWidthAtHotbarSize() {
        // The vest's insert outline, across its middle: both sides cut the same two pixels.
        var plate = WeaponHudSilhouettes.raster("plate", 63, 33);
        int y = plate.getHeight() * 3 / 4;
        var runs = new java.util.ArrayList<Integer>();
        boolean inside = false;
        int run = 0;
        for (int x = 0; x < plate.getWidth(); x++) {
            boolean solid = plate.getRGB(x, y) >>> 24 > 127;
            if (solid) inside = true;
            else if (inside) run++;
            if (solid && run > 0) {
                runs.add(run);
                run = 0;
            }
        }
        assertEquals(java.util.List.of(2, 2), runs);
    }

    @Test
    void parsesTheMaterialRunnerPath() {
        var box = WeaponHudSilhouettes.svg("M10 10h10v10H10Zm20 0q5 0 5 5t-5 5Z").getBounds2D();
        assertEquals(new java.awt.geom.Rectangle2D.Double(10, 10, 25, 10), box);
    }

    @Test
    void vectorSilhouettesFitTheRequestedBoxAtFullSize() {
        var knife = WeaponHudSilhouettes.raster("knife", 120, 40);
        assertTrue(knife.getWidth() == 120 || knife.getHeight() == 40);
        assertTrue(knife.getWidth() <= 120 && knife.getHeight() <= 40);
        assertArrayEquals(new int[] {120, 30}, ItemSilhouette.fit(40, 10, 120, 40));
        assertArrayEquals(new int[] {20, 40}, ItemSilhouette.fit(10, 20, 120, 40));
    }

    @Test
    void utilityItemsUseSmallerBoxesWithoutShrinkingWeaponSilhouettes() {
        assertEquals(1, WeaponHudSilhouettes.size(null));
        assertEquals(1, WeaponHudSilhouettes.size("rpg"));
        assertEquals(0.72f, WeaponHudSilhouettes.size("knife"));
        assertEquals(0.72f, WeaponHudSilhouettes.size("medkit"));
        assertEquals(0.78f, WeaponHudSilhouettes.size("plate"));
    }

    @Test
    void silhouetteRestPoseDoesNotCaptureOrAlterLiveWeaponAnimation() {
        var root = new com.tacz.guns.client.model.bedrock.BedrockPart("root");
        var magazine = new com.tacz.guns.client.model.bedrock.BedrockPart("additional_magazine");
        root.addChild(magazine);
        root.offsetX = 2;
        root.offsetY = -3;
        root.offsetZ = 4;
        root.xScale = 0.5f;
        root.additionalQuaternion.rotateY(0.4f);
        var rotation = new org.joml.Quaternionf(root.additionalQuaternion);
        assertThrows(
                IllegalStateException.class,
                () ->
                        ItemSilhouette.restPose(
                                java.util.List.of(root),
                                () -> {
                                    assertEquals(0, root.offsetX);
                                    assertEquals(0, root.offsetY);
                                    assertEquals(0, root.offsetZ);
                                    assertEquals(1, root.xScale);
                                    assertEquals(
                                            new org.joml.Quaternionf(), root.additionalQuaternion);
                                    assertFalse(magazine.visible);
                                    root.visible = false;
                                    throw new IllegalStateException("offscreen render failed");
                                }));
        assertEquals(2, root.offsetX);
        assertEquals(-3, root.offsetY);
        assertEquals(4, root.offsetZ);
        assertEquals(0.5f, root.xScale);
        assertEquals(rotation, root.additionalQuaternion);
        assertTrue(root.visible);
        assertTrue(magazine.visible);
    }

    @Test
    void reloadCountersChangeTogetherOnlyAfterCompletionAndResetOnSwapOrDisconnect() {
        var display = new WeaponHud.AmmoDisplay();
        display.update("0:ak", 17, 90, false);
        display.update("0:ak", 17, 90, true);
        display.update("0:ak", 31, 76, true);
        assertEquals(17, display.loaded());
        assertEquals(90, display.reserve());
        display.update("0:ak", 31, 76, false);
        assertEquals(31, display.loaded());
        assertEquals(76, display.reserve());
        display.update("1:ak", 5, 76, true);
        assertEquals(5, display.loaded());
        display.clear();
        display.update("1:ak", 30, 50, true);
        assertEquals(30, display.loaded());
        assertEquals(50, display.reserve());
    }

    @Test
    void interruptedShellReloadReleasesOnlyTheRoundsActuallyFed() {
        var display = new WeaponHud.AmmoDisplay();
        display.update("0:shotgun", 1, 20, false);
        display.update("0:shotgun", 2, 19, true);
        display.update("0:shotgun", 3, 18, true);
        assertEquals(1, display.loaded());
        display.update("0:shotgun", 3, 18, false);
        assertEquals(3, display.loaded());
        assertEquals(18, display.reserve());
    }

    @Test
    void reloadPipsFillFromZeroWithoutExposingFedAmmoAndResetOnCancelOrSwap() {
        var display = new WeaponHud.AmmoDisplay();
        display.update("0:ak", 17, 90, false);
        display.update("0:ak", 17, 90, true);
        assertEquals(0f, display.pips(30, 107, 0, 0));
        assertEquals(7.5f, display.pips(30, 107, 0.25f, 1));
        display.update("0:ak", 31, 76, true);
        assertEquals(15f, display.pips(30, 107, 0.5f, 2));
        assertEquals(17, display.loaded());
        assertEquals(90, display.reserve());
        assertEquals(15f, display.pips(30, 107, 0.4f, 3));
        assertEquals(30f, display.pips(30, 107, 1, 4));
        display.update("0:ak", 31, 76, false);
        assertEquals(30f, display.pips(30, 107, -1, 5));
        assertEquals(31, display.loaded());
        display.update("0:ak", 17, 90, false);
        display.update("0:ak", 17, 90, true);
        assertEquals(15f, display.pips(30, 107, 0.5f, 6));
        display.update("0:ak", 17, 90, false);
        assertEquals(17f, display.pips(30, 107, -1, 7));
        display.update("1:pistol", 3, 40, true);
        assertEquals(0f, display.pips(17, 43, 0, 8));
        assertEquals(8.5f, display.pips(17, 43, 0.5f, 9));
        display.clear();
        display.update("1:pistol", 4, 39, true);
        assertEquals(0f, display.pips(17, 43, 0, 10));
    }

    @Test
    void reloadPipsRespectAvailableRoundsAndMagazineCapacity() {
        var display = new WeaponHud.AmmoDisplay();
        display.update("0:ak", 0, 4, false);
        display.update("0:ak", 0, 4, true);
        assertEquals(0f, display.pips(30, 4, 0, 0));
        assertEquals(2f, display.pips(30, 4, 0.5f, 1));
        assertEquals(4f, display.pips(30, 4, 1, 2));
        display.update("0:ak", 17, 3, false);
        display.update("0:ak", 17, 3, true);
        assertEquals(0f, display.pips(30, 20, 0, 3));
        assertEquals(10f, display.pips(30, 20, 0.5f, 4));
        assertEquals(20f, display.pips(30, 20, 1, 5));
        assertEquals(17, display.loaded());
        assertEquals(3, display.reserve());
        display.update("0:ak", 0, 90, false);
        display.update("0:ak", 0, 90, true);
        assertEquals(15f, display.pips(30, 90, 0.5f, 6));
        display.update("0:ak", 31, 59, false);
        assertEquals(31, display.loaded());
        assertEquals(30f, display.pips(30, 90, -1, 7));
    }

    @Test
    void segmentedReloadPipsSmoothActualFedRoundsAndCancelImmediately() {
        var display = new WeaponHud.AmmoDisplay();
        long duration = com.lunares.blockfield.client.gui.UiMotion.DURATION_NS;
        display.update("0:shotgun", 1, 20, false);
        display.update("0:shotgun", 3, 18, true);
        assertEquals(1f, display.pips(8, 3, -1, 0));
        float middle = display.pips(8, 3, -1, duration / 2);
        assertTrue(middle > 1 && middle < 3);
        assertEquals(3f, display.pips(8, 3, -1, duration));
        assertEquals(1, display.loaded());
        display.update("0:shotgun", 3, 18, false);
        assertEquals(3f, display.pips(8, 3, -1, duration + 1));
        assertEquals(3, display.loaded());
    }

    @Test
    void reloadFillUsesEachClipClockAndIncomingTransitionButNotShellSegments() {
        var tactical = reloadRunner("reload_tactical", 4);
        tactical.setProgressNs(1_000_000_000L);
        assertEquals(0.25f, WeaponHud.reloadProgress(tactical));
        tactical.pause();
        assertEquals(0.25f, WeaponHud.reloadProgress(tactical));
        var empty = reloadRunner("reload_empty", 6);
        empty.setProgressNs(3_000_000_000L);
        assertEquals(0.5f, WeaponHud.reloadProgress(empty));
        var idle = reloadRunner("idle", 4);
        idle.transition(empty, 100_000_000);
        assertEquals(0.5f, WeaponHud.reloadProgress(idle));
        idle.stopTransition();
        assertEquals(-1f, WeaponHud.reloadProgress(idle));
        var intro = reloadRunner("reload_intro_empty", 1);
        intro.setProgressNs(900_000_000);
        intro.transition(empty, 100_000_000);
        assertEquals(0.5f, WeaponHud.reloadProgress(intro));
        for (String name :
                new String[] {
                    "reload_intro_empty",
                    "reload_empty_intro",
                    "reload_start",
                    "reload_loop_2",
                    "reload_end",
                    "reload_finish"
                }) {
            var segment = reloadRunner(name, 1);
            segment.setProgressNs(500_000_000);
            assertTrue(WeaponHud.activeReloadClip(segment), name);
            assertEquals(-1f, WeaponHud.reloadProgress(segment), name);
        }
        empty.getAnimation().playType =
                com.tacz.guns.api.client.animation.ObjectAnimation.PlayType.LOOP;
        assertEquals(-1f, WeaponHud.reloadProgress(empty));
        tactical.hold();
        assertEquals(-1f, WeaponHud.reloadProgress(tactical));
    }

    private static com.tacz.guns.api.client.animation.ObjectAnimationRunner reloadRunner(
            String name, float duration) {
        var runner =
                new com.tacz.guns.api.client.animation.ObjectAnimationRunner(
                        new com.tacz.guns.api.client.animation.ObjectAnimation(name) {
                            @Override
                            public float getMaxEndTimeS() {
                                return duration;
                            }
                        });
        runner.run();
        return runner;
    }

    @Test
    void reloadClipIncludesTransitionsButExcludesCompletedOrUnstartedRunners() {
        var reload =
                new com.tacz.guns.api.client.animation.ObjectAnimationRunner(
                        new com.tacz.guns.api.client.animation.ObjectAnimation("reload_empty") {});
        var idle =
                new com.tacz.guns.api.client.animation.ObjectAnimationRunner(
                        new com.tacz.guns.api.client.animation.ObjectAnimation("idle") {});
        assertFalse(WeaponHud.activeReloadClip(null));
        assertFalse(WeaponHud.activeReloadClip(reload));
        reload.run();
        assertTrue(WeaponHud.activeReloadClip(reload));
        reload.pause();
        assertTrue(WeaponHud.activeReloadClip(reload));
        reload.hold();
        assertFalse(WeaponHud.activeReloadClip(reload));
        reload.reset();
        reload.run();
        idle.transition(reload, 100_000_000);
        assertTrue(WeaponHud.activeReloadClip(idle));
        idle.stopTransition();
        assertFalse(WeaponHud.activeReloadClip(idle));
        reload.stop();
        assertFalse(WeaponHud.activeReloadClip(reload));
    }
}
