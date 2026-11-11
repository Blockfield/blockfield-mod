package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.screen.MenuScreen;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;

/**
 * Round 5 (tablet + vote onto shared blocks): the tablet and the vote menu consume
 * BfTheme/BfIcon/UiMotion instead of private copies. Vote tables ellipsize through the shared
 * helper, so Cyrillic cells truncate the same way everywhere.
 */
class TabletSharedBlocksTest {
    /** Fixed 7px advance per codepoint, like HudTopBarTest: deterministic without a font reload. */
    static final Font METRICS =
            new Font(face -> null, false) {
                private final StringSplitter splitter =
                        new StringSplitter((codepoint, style) -> 7f);

                @Override
                public int width(String text) {
                    return text.length() * 7;
                }

                @Override
                public String plainSubstrByWidth(String text, int width) {
                    int n = Math.max(0, Math.min(text.length(), width / 7));
                    return text.substring(0, n);
                }

                @Override
                public StringSplitter getSplitter() {
                    return splitter;
                }
            };

    @Test
    void voteTablesUseSharedEllipsize() {
        assertThrows(
                NoSuchMethodException.class,
                () -> MenuScreen.class.getDeclaredMethod("ellipsize", String.class, int.class),
                "MenuScreen must not keep a private ellipsize copy; use BfTheme.ellipsize");
    }

    @Test
    void sharedEllipsizeKeepsVoteCellsReadable() {
        assertEquals("Фраги", BfTheme.ellipsize(METRICS, "Фраги", 200));
        String cut = BfTheme.ellipsize(METRICS, "ДлинноеНазваниеКарты", 70);
        assertTrue(cut.endsWith("…"), "truncated cell ends with an ellipsis: " + cut);
        assertTrue(METRICS.width(cut) <= 70, "truncated cell fits its column: " + cut);
    }
}
