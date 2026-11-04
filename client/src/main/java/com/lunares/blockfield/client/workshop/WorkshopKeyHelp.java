package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.ClientConfig;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.render.MinimapHud;
import com.lunares.blockfield.client.render.WeaponHud;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explains every workshop hotkey with the player's real current binding, so builders never guess.
 * Pure table/session logic stays headless-testable; only {@link Card} and the live resolvers touch
 * Minecraft.
 */
public final class WorkshopKeyHelp {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkshopKeyHelp.class);

    /** Client-local action behind the radial «Клавиши» entry and its (unbound) key. */
    public static final String KEYS_ACTION = "keys";

    public static final String GROUP_MENU = "menu";
    public static final String GROUP_SELECTION = "selection";
    public static final String GROUP_BUFFER = "buffer";
    public static final String GROUP_SKETCH = "sketch";
    public static final String GROUP_WORLD = "world";

    public static final List<String> GROUPS =
            List.of(GROUP_MENU, GROUP_SELECTION, GROUP_BUFFER, GROUP_SKETCH, GROUP_WORLD);

    private static final Map<String, List<String>> GROUP_ACTIONS = new LinkedHashMap<>();

    static {
        GROUP_ACTIONS.put(GROUP_MENU, List.of("radial", "library", KEYS_ACTION));
        GROUP_ACTIONS.put(GROUP_SELECTION, List.of("selection"));
        GROUP_ACTIONS.put(GROUP_BUFFER, List.of("copy", "paste", "undo", "redo"));
        GROUP_ACTIONS.put(GROUP_SKETCH, List.of("ai", "rotate", "extend", "chain"));
        GROUP_ACTIONS.put(GROUP_WORLD, List.of("ghost", "maps", "submit"));
    }

    static final String HIDE_KEY = "workshop.keys.hide_on_enter";

    /** One card row: its group, binding action and fully formatted display text. */
    public record Row(String group, String action, String text) {}

    private WorkshopKeyHelp() {}

    public static List<String> actionsIn(String group) {
        return GROUP_ACTIONS.getOrDefault(group, List.of());
    }

    public static String groupOf(String action) {
        for (var entry : GROUP_ACTIONS.entrySet())
            if (entry.getValue().contains(action)) return entry.getKey();
        return null;
    }

    public static String groupTitleKey(String group) {
        return "blockfield.workshop.keys.group." + group;
    }

    /**
     * Server menu action to the workshop binding shown next to its label, or null when no hotkey
     * triggers it (cut/cancel/AI-undo have no direct key; showing B on AI-undo would lie).
     */
    static String bindingAction(String serverAction) {
        if (serverAction == null) return null;
        if (serverAction.startsWith("workshop:map:") || serverAction.startsWith("workshop:tpxz:"))
            return "maps";
        return switch (serverAction) {
            case "ai", "workshop:ai" -> "ai";
            case "library", "workshop:library" -> "library";
            case "keys" -> KEYS_ACTION;
            case "selection", "workshop:selection" -> "selection";
            case "copy", "workshop:build:copy", "workshop:tool:copy" -> "copy";
            case "paste", "workshop:build:paste", "workshop:tool:paste" -> "paste";
            case "undo", "workshop:build:undo", "workshop:tool:undo" -> "undo";
            case "redo", "workshop:build:redo", "workshop:tool:redo" -> "redo";
            case "rotate", "workshop:build:rotate", "workshop:tool:rotate" -> "rotate";
            case "extend", "workshop:extend" -> "extend";
            case "chain", "workshop:chain" -> "chain";
            case "ghost", "workshop:ghost" -> "ghost";
            case "maps", "workshop:hub" -> "maps";
            case "submit", "workshop:mk:submit" -> "submit";
            case "radial", "menu" -> "radial";
            default -> null;
        };
    }

    /** Live display name of the key bound to a workshop action, or null when unbound. */
    public static String liveKeyName(String action) {
        if (bindingAction(action) == null) return null;
        var key = WorkshopControls.key(bindingAction(action));
        return key.isUnbound() ? null : key.getTranslatedKeyMessage().getString();
    }

    /** Label with its hotkey when one is bound; unbound actions keep the bare label. */
    public static String withKey(String label, String keyName) {
        return keyName == null ? label : label + " · " + keyName;
    }

    /**
     * Every card row in group order. Resolvers are injected so tests can use a fake binding table;
     * the screen passes the live {@link Component} resolvers.
     */
    static List<Row> rows(
            Function<String, String> labelOf,
            Function<String, String> keyNameOf,
            String unboundText) {
        var rows = new ArrayList<Row>();
        for (String group : GROUPS)
            for (String action : actionsIn(group)) {
                String key = keyNameOf.apply(action);
                rows.add(
                        new Row(
                                group,
                                action,
                                key == null
                                        ? labelOf.apply(action) + " · " + unboundText
                                        : withKey(labelOf.apply(action), key)));
            }
        return List.copyOf(rows);
    }

    /** Card box for a measured text width and content line count. */
    static int[] cardSize(int textWidth, int lines) {
        return new int[] {textWidth + 16, 52 + lines * 10};
    }

    /** Rows that fit between the title and the buttons of a (possibly clamped) card. */
    static int visibleLines(int cardHeight) {
        return Math.max(1, (cardHeight - 52) / 10);
    }

    /** First shown row; the last row is always reachable (D6: 427×240 clamps the card). */
    static int clampScroll(int scroll, int lines, int visible) {
        return Math.clamp(scroll, 0, Math.max(0, lines - visible));
    }

    /**
     * Card placement reusing the round-3 clearance rules; falls back to a clamped centre box on
     * tiny GUI scales rather than hiding the card.
     */
    static int[] placeCard(int screenW, int screenH, int width, int height) {
        int[] at =
                WorkshopToolsClient.placePanel(
                        screenW,
                        screenH,
                        width,
                        height,
                        MinimapHud.rect(),
                        WorkshopChatArea.rect());
        if (at != null) return at;
        int w = Math.min(width, Math.max(12, screenW - 8));
        int h = Math.min(height, Math.max(12, screenH - 8));
        return new int[] {(screenW - w) / 2, (screenH - h) / 2, w, h};
    }

    // Session state: the card auto-shows on the first workshop entry, dismissal lasts the session,
    // the opt-out persists client-side.
    private static boolean seenWorkshop;
    private static boolean dismissed;

    interface HideStore {
        boolean get();

        void set(boolean hide);
    }

    private static HideStore store = defaultStore();

    private static HideStore defaultStore() {
        return new HideStore() {
            @Override
            public boolean get() {
                return ClientConfig.USER.getBoolean(HIDE_KEY, false);
            }

            @Override
            public void set(boolean hide) {
                try {
                    ClientConfig.USER.set(HIDE_KEY, Boolean.toString(hide));
                } catch (IOException e) {
                    LOGGER.warn("Cannot save workshop key card opt-out", e);
                }
            }
        };
    }

    static void store(HideStore replacement) {
        store = replacement;
    }

    static void resetStore() {
        store = defaultStore();
    }

    static boolean seenWorkshop() {
        return seenWorkshop;
    }

    static boolean dismissed() {
        return dismissed;
    }

    static void resetSession() {
        seenWorkshop = false;
        dismissed = false;
    }

    static void dismiss() {
        dismissed = true;
    }

    static boolean suppressAuto() {
        return store.get();
    }

    static void setSuppressAuto(boolean hide) {
        store.set(hide);
    }

    static boolean shouldAutoShow(
            boolean inWorkshop,
            boolean screenOpen,
            boolean hideGui,
            boolean seen,
            boolean noShow,
            boolean suppress) {
        return inWorkshop && !screenOpen && !hideGui && !seen && !noShow && !suppress;
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> resetSession());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> resetSession());
        ClientTickEvents.END_CLIENT_TICK.register(WorkshopKeyHelp::tick);
    }

    private static void tick(Minecraft mc) {
        if (!WeaponHud.inWorkshop() || seenWorkshop) return;
        if (mc.screen != null || mc.options.hideGui) return;
        seenWorkshop = true;
        if (!dismissed && !suppressAuto()) open(mc);
    }

    public static void open(Minecraft mc) {
        if (!(mc.screen instanceof Card)) mc.setScreen(new Card());
    }

    /** Compact overlay card: the world stays visible, nothing is fullscreen. */
    public static final class Card extends Screen {
        private record Line(String text, int color) {}

        private final List<Line> lines = new ArrayList<>();
        private int cardX, cardY, cardW, cardH;
        private int[] closeButton = new int[4];
        private int[] hideButton = new int[4];
        private int scroll;

        Card() {
            super(Component.translatable("blockfield.workshop.keys.title"));
        }

        @Override
        protected void init() {
            font = BfTheme.font();
            lines.clear();
            String unbound = Component.translatable("blockfield.workshop.keys.unbound").getString();
            for (String group : GROUPS) {
                lines.add(
                        new Line(
                                Component.translatable(groupTitleKey(group)).getString(),
                                BfTheme.AMBER));
                for (Row row :
                        rows(
                                action ->
                                        Component.translatable(
                                                        WorkshopControls.key(action).getName())
                                                .getString(),
                                WorkshopKeyHelp::liveKeyName,
                                unbound))
                    if (row.group().equals(group)) lines.add(new Line(row.text(), BfTheme.TEXT));
            }
            layout();
        }

        private void layout() {
            int textW = font.width(title.getString());
            for (Line line : lines) textW = Math.max(textW, font.width(line.text()));
            int[] size = cardSize(textW, lines.size());
            int[] at = placeCard(width, height, size[0], size[1]);
            cardX = at[0];
            cardY = at[1];
            cardW = at[2];
            cardH = at[3];
            int bw = (cardW - 24) / 2;
            closeButton = new int[] {cardX + 8, cardY + cardH - 26, bw, 18};
            hideButton = new int[] {cardX + 16 + bw, cardY + cardH - 26, bw, 18};
            scroll = clampScroll(scroll, lines.size(), visibleLines(cardH));
        }

        private String hideLabel() {
            return (suppressAuto() ? "[x] " : "[ ] ")
                    + Component.translatable("blockfield.workshop.keys.hide").getString();
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            layout();
            g.fill(cardX, cardY, cardX + cardW, cardY + cardH, BfTheme.alpha(BfTheme.BG, 0xE8));
            BfTheme.outline(g, cardX, cardY, cardW, cardH, BfTheme.BORDER);
            g.drawString(font, title.getString(), cardX + 8, cardY + 8, BfTheme.TEXT, false);
            int visible = visibleLines(cardH);
            for (int i = 0; i < visible && scroll + i < lines.size(); i++) {
                Line line = lines.get(scroll + i);
                g.drawString(
                        font,
                        BfTheme.ellipsize(font, line.text(), cardW - 16),
                        cardX + 8,
                        cardY + 24 + i * 10,
                        line.color(),
                        false);
            }
            if (lines.size() > visible) {
                int track = visible * 10, x = cardX + cardW - 4;
                int thumb = Math.max(6, track * visible / lines.size());
                int top = cardY + 24 + (track - thumb) * scroll / (lines.size() - visible);
                g.fill(x, cardY + 24, x + 2, cardY + 24 + track, BfTheme.BORDER);
                g.fill(x, top, x + 2, top + thumb, BfTheme.AMBER_DIM);
            }
            String close = Component.translatable("blockfield.workshop.keys.close").getString();
            BfTheme.button(
                    g,
                    font,
                    closeButton[0],
                    closeButton[1],
                    closeButton[2],
                    closeButton[3],
                    BfTheme.ellipsize(font, close, closeButton[2] - 8),
                    BfTheme.hit(
                            mx, my, closeButton[0], closeButton[1], closeButton[2], closeButton[3]),
                    true,
                    false);
            BfTheme.button(
                    g,
                    font,
                    hideButton[0],
                    hideButton[1],
                    hideButton[2],
                    hideButton[3],
                    BfTheme.ellipsize(font, hideLabel(), hideButton[2] - 8),
                    BfTheme.hit(mx, my, hideButton[0], hideButton[1], hideButton[2], hideButton[3]),
                    true,
                    suppressAuto());
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0) return false;
            if (BfTheme.hit(
                    (int) mx,
                    (int) my,
                    closeButton[0],
                    closeButton[1],
                    closeButton[2],
                    closeButton[3])) {
                dismiss();
                minecraft.setScreen(null);
                return true;
            }
            if (BfTheme.hit(
                    (int) mx,
                    (int) my,
                    hideButton[0],
                    hideButton[1],
                    hideButton[2],
                    hideButton[3])) {
                setSuppressAuto(!suppressAuto());
                return true;
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            scroll = clampScroll(scroll - (int) Math.signum(dy), lines.size(), visibleLines(cardH));
            return true;
        }

        @Override
        public boolean keyPressed(int code, int scan, int modifiers) {
            if (code == GLFW.GLFW_KEY_UP || code == GLFW.GLFW_KEY_DOWN) {
                scroll =
                        clampScroll(
                                scroll + (code == GLFW.GLFW_KEY_UP ? -1 : 1),
                                lines.size(),
                                visibleLines(cardH));
                return true;
            }
            if (code == GLFW.GLFW_KEY_ESCAPE || WorkshopControls.matches(KEYS_ACTION, code, scan)) {
                dismiss();
                minecraft.setScreen(null);
                return true;
            }
            return super.keyPressed(code, scan, modifiers);
        }

        @Override
        public void removed() {
            dismiss();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
