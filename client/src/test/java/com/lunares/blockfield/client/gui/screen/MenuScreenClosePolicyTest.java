package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class MenuScreenClosePolicyTest {

    @Test
    void defaultsToFalseWhenUnspecified() {
        JsonObject menu = new JsonObject();
        JsonObject btn = new JsonObject();
        assertFalse(MenuScreen.shouldCloseOnClick(btn, menu));
        assertFalse(MenuScreen.shouldCloseOnClick(null, null));
    }

    @Test
    void inheritsMenuCloseOnClick() {
        JsonObject menu = new JsonObject();
        menu.addProperty("closeOnClick", true);
        JsonObject btn = new JsonObject();
        assertTrue(MenuScreen.shouldCloseOnClick(btn, menu));

        menu.addProperty("closeOnClick", false);
        assertFalse(MenuScreen.shouldCloseOnClick(btn, menu));
    }

    @Test
    void buttonOverridesMenuSetting() {
        JsonObject menu = new JsonObject();
        menu.addProperty("closeOnClick", true);
        JsonObject btn = new JsonObject();
        btn.addProperty("closeOnClick", false);
        assertFalse(MenuScreen.shouldCloseOnClick(btn, menu));

        menu.addProperty("closeOnClick", false);
        btn.addProperty("closeOnClick", true);
        assertTrue(MenuScreen.shouldCloseOnClick(btn, menu));
    }

    private static final Font METRICS =
            new Font(face -> null, false) {
                private final StringSplitter splitter =
                        new StringSplitter((codepoint, style) -> codepoint == ' ' ? 3f : 7f);

                @Override
                public int width(String text) {
                    return (int) Math.ceil(splitter.stringWidth(text));
                }

                @Override
                public StringSplitter getSplitter() {
                    return splitter;
                }
            };

    @Test
    void wrappedCompactCardsFitWithoutSwitchingToTheLargeCardStyle() {
        String label = "Название длинной кампании ".repeat(5);
        String hint = "Подробная подсказка голосования ".repeat(10);
        int room = 400 - 16;
        int labels = METRICS.getSplitter().splitLines(label, room, Style.EMPTY).size();
        int hints = METRICS.getSplitter().splitLines(hint, room, Style.EMPTY).size();
        int labelBottom = 8 + 27 + (labels - 1) * 11 + METRICS.lineHeight;
        int height = labelBottom + 4 + (hints - 1) * 11 + 8 + 8;
        assertTrue(height > MenuLayout.CARD_H);

        // Four whole rows, their gaps, and the native one-line header/footer viewport margins.
        MenuScreen screen = cardScreen(label, hint, 4 * height + 3 * MenuLayout.CARD_GAP + 60);
        assertEquals(0, screen.overflowHeight());
    }

    @Test
    void moreWrappedHintLinesIncreaseTheActualMenuHeight() {
        String label = "Название длинной кампании ".repeat(5);
        String hint = "Подробная подсказка голосования ".repeat(10);
        MenuScreen screen = cardScreen(label, hint, 800);
        assertEquals(0, screen.overflowHeight());
        screen.update(cardData(label, hint.repeat(3)).toString());
        assertTrue(screen.overflowHeight() > 0);
    }

    private static MenuScreen cardScreen(String label, String hint, int screenHeight) {
        return new MenuScreen(cardData(label, hint).toString()) {
            {
                font = METRICS;
                width = 432;
                height = screenHeight;
            }
        };
    }

    private static JsonObject cardData(String label, String hint) {
        JsonObject card = new JsonObject();
        card.addProperty("label", label);
        card.addProperty("hint", hint);
        JsonArray buttons = new JsonArray();
        for (int i = 0; i < 4; i++) buttons.add(card);
        JsonObject section = new JsonObject();
        section.addProperty("style", "cards");
        section.add("buttons", buttons);
        JsonArray sections = new JsonArray();
        sections.add(section);
        JsonObject data = new JsonObject();
        data.addProperty("title", "T");
        data.add("sections", sections);
        return data;
    }
}
