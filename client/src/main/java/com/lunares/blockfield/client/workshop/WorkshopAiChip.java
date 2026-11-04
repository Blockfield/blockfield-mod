package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Top-centre HUD chip mirroring the AI panel when it is hidden: stage dot, title, time, a 2 px bar
 * and keys only when they apply. Results and AI notices land here for 8 s instead of the action
 * bar, which stays with the selection wand (D10). Sounds play once per job transition.
 */
final class WorkshopAiChip {
    static final long NOTICE_NS = 8_000_000_000L;
    // 400, not the spec's 300: the Russian hand keys need ~370 px to stay on one line (B9).
    static final int MAX_WIDTH = 400;
    static final int MAX_LINES = 2;

    /** What the chip shows; {@code bar} < 0 hides the bar, {@code compact} is the fallback text. */
    record View(
            int dot,
            boolean pulse,
            String title,
            int titleColor,
            String meta,
            int metaColor,
            float bar,
            int barColor,
            String keys,
            String compact) {}

    private static View notice;
    private static long noticeAt;
    private static String lastTransition = "";
    private static long lastLimitSound;

    private WorkshopAiChip() {}

    private static Component tools(String key, Object... args) {
        return Component.translatable("blockfield.workshop.tools." + key, args);
    }

    static void clear() {
        notice = null;
        lastTransition = "";
    }

    /** An AI line for the player (refusal, saved sketch …) shown for 8 s. */
    static void message(Component text, int color) {
        show(
                new View(color, false, text.getString(), BfTheme.TEXT, "", 0, -1, 0, "", ""),
                System.nanoTime());
    }

    private static void show(View view, long now) {
        notice = view;
        noticeAt = now;
    }

    static View notice(long now) {
        return notice != null && now - noticeAt <= NOTICE_NS ? notice : null;
    }

    /** Once per job and stage: sound, plus a result notice when the panel is not open. */
    static void transition(WorkshopAiStateS2CPacket previous, WorkshopAiStateS2CPacket next) {
        String key = next.jobId() + ":" + next.stage();
        // «disabled» is the empty pre-join state: the first status reply is not a fresh result.
        if (key.equals(lastTransition)
                || previous.stage().equals("disabled")
                || previous.stage().equals(next.stage()) && previous.jobId().equals(next.jobId()))
            return;
        lastTransition = key;
        Minecraft mc = Minecraft.getInstance();
        switch (next.stage()) {
            case "preview" -> play(SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 0.5f);
            case "complete" -> play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f, 0.4f);
            case "error" -> play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 0.5f);
            default -> {}
        }
        if (mc.screen instanceof WorkshopAiScreen) return;
        View result = result(next, mc);
        if (result != null) show(result, System.nanoTime());
    }

    private static View result(WorkshopAiStateS2CPacket state, Minecraft mc) {
        return switch (state.stage()) {
            case "complete" ->
                    state.canUndo()
                            ? new View(
                                    BfTheme.OK,
                                    false,
                                    tools(
                                                    "panel.done",
                                                    mc.options.keyShift.getTranslatedKeyMessage(),
                                                    mc.options.keyAttack.getTranslatedKeyMessage())
                                            .getString(),
                                    BfTheme.OK,
                                    "",
                                    0,
                                    -1,
                                    0,
                                    "",
                                    "")
                            : null;
            case "cancelled" ->
                    new View(
                            BfTheme.MUTED,
                            false,
                            tools("chip.cancelled").getString(),
                            BfTheme.TEXT,
                            "",
                            0,
                            -1,
                            0,
                            "",
                            "");
            case "error" ->
                    new View(
                            BfTheme.DANGER,
                            false,
                            tools(
                                            "chip.error",
                                            WorkshopControls.key("ai").getTranslatedKeyMessage())
                                    .getString(),
                            BfTheme.DANGER,
                            "",
                            0,
                            -1,
                            0,
                            "",
                            "");
            default -> null;
        };
    }

    static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance()
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** The «упор» click when the hand sketch hits its height limit; at most every 300 ms. */
    static void heightLimit(long nowMs) {
        if (nowMs - lastLimitSound < 300) return;
        lastLimitSound = nowMs;
        play(SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 0.3f);
    }

    /** Current chip, or null when the AI has nothing to show (the key panel takes the slot). */
    static View current(Minecraft mc, long now) {
        var state = WorkshopAiClient.state();
        String use = mc.options.keyUse.getTranslatedKeyMessage().getString();
        String attack = mc.options.keyAttack.getTranslatedKeyMessage().getString();
        String rotate = WorkshopToolsClient.SKETCH_ROTATE_KEY.getTranslatedKeyMessage().getString();
        if (WorkshopSketchPlacement.active())
            return switch (WorkshopSketchPlacement.phase()) {
                case HELD -> {
                    int dy = WorkshopSketchPlacement.height();
                    String height = (dy > 0 ? "+" : "") + dy;
                    boolean limit = Math.abs(dy) >= WorkshopSketchPlacement.MAX_HEIGHT;
                    yield new View(
                            BfTheme.AMBER_BRIGHT,
                            false,
                            tools("panel.hand_state", WorkshopSketchPlacement.rotation() * 90)
                                    .getString(),
                            BfTheme.TEXT,
                            tools(limit ? "panel.height_max" : "panel.height", height).getString(),
                            limit ? BfTheme.WARN : BfTheme.TEXT_SECONDARY,
                            -1,
                            0,
                            tools("panel.hand", use, attack, rotate).getString(),
                            WorkshopSketchPlacement.rotation() * 90 + "°");
                }
                case AWAITING ->
                        new View(
                                BfTheme.AMBER,
                                true,
                                tools("panel.awaiting").getString(),
                                BfTheme.TEXT,
                                "",
                                0,
                                -1,
                                0,
                                "",
                                "");
                case FIXED ->
                        new View(
                                BfTheme.AMBER_BRIGHT,
                                false,
                                tools("panel.fixed_title").getString(),
                                BfTheme.TEXT,
                                "",
                                0,
                                -1,
                                0,
                                tools("panel.fixed", use, attack).getString(),
                                "");
            };
        String stage = WorkshopAiClient.uploading() ? "transcribing" : state.stage();
        if (stage.equals("preview"))
            return new View(
                    BfTheme.AMBER_BRIGHT,
                    false,
                    tools("status.preview").getString(),
                    BfTheme.TEXT,
                    "",
                    0,
                    -1,
                    0,
                    tools("panel.preview", use, attack).getString(),
                    "");
        if (WorkshopToolsClient.busy(stage)) return working(state, stage, attack, now);
        return notice(now);
    }

    private static View working(
            WorkshopAiStateS2CPacket state, String stage, String attack, long now) {
        if (stage.equals("applying") || stage.equals("undoing")) {
            float share =
                    state.totalBlocks() > 0
                            ? state.completedBlocks() / (float) state.totalBlocks()
                            : 0;
            String percent = Math.round(share * 100) + "%";
            return new View(
                    BfTheme.AMBER,
                    true,
                    tools("status." + stage).getString(),
                    BfTheme.TEXT,
                    percent,
                    BfTheme.TEXT_SECONDARY,
                    share,
                    BfTheme.AMBER,
                    "",
                    percent);
        }
        var progress = WorkshopAiClient.progress();
        boolean tracked =
                progress != null
                        && progress.busy()
                        && (progress.jobId().equals(state.jobId()) || WorkshopAiClient.uploading());
        String keys =
                stage.equals("awaiting") ? tools("panel.queue_cancel", attack).getString() : "";
        if (tracked && progress.overdue(now)) {
            String elapsed = progress.elapsed(now);
            return new View(
                    BfTheme.WARN,
                    true,
                    Component.translatable("blockfield.workshop.ai.progress.longer").getString(),
                    BfTheme.WARN,
                    elapsed,
                    BfTheme.WARN,
                    progress.fraction(now),
                    BfTheme.WARN,
                    keys,
                    elapsed);
        }
        Component eta = WorkshopAiEtaBar.label();
        String meta = !tracked ? "" : eta != null ? eta.getString() : progress.elapsed(now);
        return new View(
                BfTheme.AMBER,
                true,
                tools("status." + stage).getString(),
                BfTheme.TEXT,
                meta,
                BfTheme.TEXT_SECONDARY,
                WorkshopAiEtaBar.fraction(),
                BfTheme.AMBER,
                keys,
                meta);
    }

    /** Text wrapping seam, so the two-line budget is testable without a font. */
    interface Wrap {
        BfTheme.Wrapped apply(String text, int width, int maxLines);
    }

    record Lines(List<String> head, boolean headCut, List<String> keys) {
        int size() {
            return head.size() + keys.size();
        }
    }

    static String head(View view) {
        return view.title() + (view.meta().isEmpty() ? "" : " · " + view.meta());
    }

    /** Chip width: text + 12, at most min(screen − 16, {@link #MAX_WIDTH}). */
    static int width(int headWidth, int keysWidth, int screenWidth) {
        return Math.min(
                Math.min(screenWidth - 16, MAX_WIDTH), Math.max(headWidth + 8, keysWidth) + 12);
    }

    /**
     * At most {@link #MAX_LINES} text lines in total: the head may wrap only when there are no
     * keys; keys get the lines the head leaves. Text past the budget ends with «…» (only on GUIs
     * narrower than the key line, i.e. below 427 px).
     */
    static Lines lines(View view, int width, Wrap wrap) {
        var head = wrap.apply(head(view), width - 8, view.keys().isEmpty() ? MAX_LINES : 1);
        List<String> keys =
                view.keys().isEmpty()
                        ? List.of()
                        : wrap.apply(view.keys(), width, MAX_LINES - head.lines().size()).lines();
        return new Lines(head.lines(), head.overflow() || head.lines().size() > 1, keys);
    }

    /** Draws the chip, falling back to a compact chip when the full one has no free slot. */
    static void render(GuiGraphics g, Minecraft mc, View view, int[] minimap, int[] chat) {
        Font font = BfTheme.font();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int w =
                width(
                        font.width(head(view)),
                        view.keys().isEmpty() ? 0 : font.width(view.keys()),
                        sw);
        var lines = lines(view, w - 12, (text, width, max) -> BfTheme.wrap(font, text, width, max));
        int bar = view.bar() >= 0 ? 3 : 0;
        int h = 6 + lines.size() * 10 + bar;
        int[] at = WorkshopToolsClient.placePanel(sw, sh, w, h, minimap, chat);
        if (at == null) {
            // Never run a job without visible UI (D14): compact chip, else over the minimap.
            String compact = view.compact();
            int cw = 6 + 8 + font.width(compact) + 6;
            at = WorkshopToolsClient.placePanel(sw, sh, cw, 16 + bar, minimap, chat);
            if (at == null) at = new int[] {(sw - cw) / 2, 4, cw, 16 + bar};
            frame(g, at);
            dot(g, view, at[0] + 6, at[1] + 6);
            g.drawString(
                    font,
                    compact,
                    at[0] + 14,
                    at[1] + 4,
                    view.metaColor() == 0 ? BfTheme.TEXT : view.metaColor(),
                    false);
            barLine(g, view, at);
            return;
        }
        frame(g, at);
        dot(g, view, at[0] + 6, at[1] + 6);
        int y = at[1] + 4;
        for (String line : lines.head()) {
            if (!lines.headCut() && !view.meta().isEmpty()) {
                g.drawString(font, view.title(), at[0] + 14, y, view.titleColor(), false);
                g.drawString(
                        font,
                        " · " + view.meta(),
                        at[0] + 14 + font.width(view.title()),
                        y,
                        view.metaColor(),
                        false);
            } else g.drawString(font, line, at[0] + 14, y, view.titleColor(), false);
            y += 10;
        }
        for (String line : lines.keys()) {
            g.drawString(font, line, at[0] + 6, y, BfTheme.MUTED, false);
            y += 10;
        }
        barLine(g, view, at);
    }

    private static void frame(GuiGraphics g, int[] at) {
        g.fill(at[0], at[1], at[0] + at[2], at[1] + at[3], BfTheme.alpha(BfTheme.BG, 0xB0));
        BfTheme.outline(g, at[0], at[1], at[2], at[3], BfTheme.BORDER);
    }

    private static void dot(GuiGraphics g, View view, int x, int y) {
        int color = view.dot();
        if (view.pulse() && net.minecraft.Util.getMillis() / 500 % 2 == 1)
            color = BfTheme.alpha(color, 0x60);
        BfTheme.statusDot(g, x, y, color);
    }

    private static void barLine(GuiGraphics g, View view, int[] at) {
        if (view.bar() < 0) return;
        int y = at[1] + at[3] - 3;
        g.fill(at[0] + 1, y, at[0] + at[2] - 1, y + 2, BfTheme.BORDER);
        int fill = Math.round(Math.clamp(view.bar(), 0f, 1f) * (at[2] - 2));
        if (fill > 0) g.fill(at[0] + 1, y, at[0] + 1 + fill, y + 2, view.barColor());
    }
}
