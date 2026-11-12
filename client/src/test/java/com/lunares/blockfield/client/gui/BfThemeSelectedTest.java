package com.lunares.blockfield.client.gui;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class BfThemeSelectedTest {
    private static GuiGraphics graphics() {
        return mock(GuiGraphics.class, Answers.RETURNS_DEEP_STUBS);
    }

    private static Font font() {
        return mock(Font.class, Answers.RETURNS_DEEP_STUBS);
    }

    @Test
    void selectedSurfaceDrawsFullWidthUnderline() {
        GuiGraphics g = graphics();
        BfTheme.selectSurface(g, 10, 20, 100, 30, true, true, 0f, false);
        verify(g, atLeastOnce()).fill(eq(10), eq(49), eq(110), eq(50), eq(BfTheme.AMBER));
    }

    @Test
    void unselectedSurfaceDrawsNoAmber() {
        GuiGraphics g = graphics();
        BfTheme.selectSurface(g, 10, 20, 100, 30, true, false, 0f, false);
        verify(g, never()).fill(anyInt(), anyInt(), anyInt(), anyInt(), eq(BfTheme.AMBER));
    }

    @Test
    void menuButtonSelectedDrawsFullWidthUnderline() {
        GuiGraphics g = graphics();
        BfTheme.menuButton(
                g,
                font(),
                10,
                20,
                100,
                30,
                BfIcon.ARROW,
                "Да",
                "",
                false,
                60,
                true,
                false,
                false,
                0f,
                true);
        verify(g, atLeastOnce()).fill(eq(10), eq(49), eq(110), eq(50), eq(BfTheme.AMBER));
    }

    @Test
    void menuButtonUnselectedDrawsNoAmber() {
        GuiGraphics g = graphics();
        BfTheme.menuButton(
                g,
                font(),
                10,
                20,
                100,
                30,
                BfIcon.ARROW,
                "Да",
                "",
                false,
                60,
                true,
                false,
                false,
                0f,
                false);
        verify(g, never()).fill(anyInt(), anyInt(), anyInt(), anyInt(), eq(BfTheme.AMBER));
    }

    @Test
    void cardSelectedDrawsFullWidthUnderline() {
        GuiGraphics g = graphics();
        BfTheme.card(
                g,
                font(),
                10,
                20,
                100,
                60,
                BfIcon.ARROW,
                "Карта",
                "Подсказка",
                1f,
                8,
                false,
                true,
                0f,
                true);
        verify(g, atLeastOnce()).fill(eq(10), eq(79), eq(110), eq(80), eq(BfTheme.AMBER));
    }

    @Test
    void cardUnselectedDrawsNoAmber() {
        GuiGraphics g = graphics();
        BfTheme.card(
                g,
                font(),
                10,
                20,
                100,
                60,
                BfIcon.ARROW,
                "Карта",
                "Подсказка",
                1f,
                8,
                false,
                true,
                0f,
                false);
        verify(g, never()).fill(anyInt(), anyInt(), anyInt(), anyInt(), eq(BfTheme.AMBER));
    }
}
