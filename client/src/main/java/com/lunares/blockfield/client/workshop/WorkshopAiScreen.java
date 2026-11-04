package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.mixin.MultiLineEditBoxAccessor;
import com.lunares.blockfield.client.render.WeaponHud;
import com.lunares.blockfield.network.packet.WorkshopAiRequestC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AI panel stays open while the server works: progress and ETA are shown where the request was
 * made, «Скрыть» leaves the same progress in the HUD chip. Layout and states follow the redesign
 * spec (sheet {@link WorkshopAiLayout}, states {@link WorkshopAiPanelState}).
 */
public final class WorkshopAiScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkshopAiScreen.class);
    static final long PENDING_NS = 15_000_000_000L;
    static final int PROMPT_WARN = 3800;
    // Session choices: the panel reopens the way the player left it.
    private static boolean libraryTarget;
    private static boolean useSelection = true;
    private static String sizeW = "16", sizeH = "8", sizeD = "16";

    private WorkshopAiLayout layout;
    private Prompt prompt;
    private WorkshopSegmented target;
    private WorkshopSegmented speed;
    private WorkshopSegmented sizeMode;
    private EditBox[] sizes;
    private WorkshopButton voice;
    private WorkshopStatusBlock status;
    private WorkshopButton undo;
    private WorkshopButton primary;
    private WorkshopButton secondary;
    private WorkshopButton tertiary;
    private WorkshopAiPanelState view;
    private WorkshopAiStateS2CPacket shown;
    private String notice = "";
    private boolean noticeWarn;
    private boolean waiting;
    private long pendingUntil;
    private int heldKey = -1;
    private boolean mouseHeld;
    private boolean focusPrompt;

    public WorkshopAiScreen() {
        super(text("title"));
    }

    /** «Новый эскиз» from the library: target the library and type right away. */
    static WorkshopAiScreen forLibrary() {
        libraryTarget = true;
        var screen = new WorkshopAiScreen();
        screen.focusPrompt = true;
        return screen;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("blockfield.workshop.ai." + key, args);
    }

    /**
     * Field without the vanilla counter (D3) and with a themed scrollbar; read-only keeps scroll.
     */
    private final class Prompt extends MultiLineEditBox {
        boolean editable = true;

        Prompt(int x, int y, int w, int h) {
            super(font, x, y, w, h, text("prompt_hint"), text("prompt"));
        }

        @Override
        protected void renderBackground(GuiGraphics g) {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), BfTheme.BG_ELEVATED);
            BfTheme.outline(
                    g,
                    getX(),
                    getY(),
                    getWidth(),
                    getHeight(),
                    isFocused() && editable ? BfTheme.AMBER : BfTheme.BORDER_HOVER);
        }

        @Override
        protected void renderDecorations(GuiGraphics g) {
            int max = getMaxScrollAmount();
            if (max <= 0) return;
            int track = getHeight() - 2;
            int thumb = Math.max(8, track * track / (getInnerHeight() + totalInnerPadding()));
            int top = getY() + 1 + (int) ((track - thumb) * (scrollAmount() / max));
            int x = getX() + getWidth() - 3;
            g.fill(x, getY() + 1, x + 2, getY() + 1 + track, BfTheme.BORDER);
            g.fill(x, top, x + 2, top + thumb, BfTheme.AMBER_DIM);
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            if (!editable
                    && !(Screen.isCopy(key) || Screen.isSelectAll(key))
                    && (key < GLFW.GLFW_KEY_RIGHT || key > GLFW.GLFW_KEY_END)) return false;
            return super.keyPressed(key, scan, modifiers);
        }

        @Override
        public boolean charTyped(char character, int modifiers) {
            return editable && super.charTyped(character, modifiers);
        }

        void selectAll() {
            var field = ((MultiLineEditBoxAccessor) (Object) this).blockfield$textField();
            field.setSelecting(false);
            field.seekCursor(Whence.ABSOLUTE, 0);
            field.setSelecting(true);
            field.seekCursor(Whence.END, 0);
            field.setSelecting(false);
        }
    }

    /** Themed 36×20 side field; vanilla's bordered sprite does not match the sheet. */
    private final class SizeBox extends EditBox {
        SizeBox(int x, int y) {
            super(font, x, y, 36, 20, text("size.title"));
            setBordered(false);
            setTextColor(BfTheme.TEXT);
            setTextColorUneditable(BfTheme.MUTED_DARK);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(getX(), getY(), getX() + width, getY() + height, BfTheme.BG_ELEVATED);
            BfTheme.outline(
                    g,
                    getX(),
                    getY(),
                    width,
                    height,
                    !active
                            ? BfTheme.BORDER_SOFT
                            : isFocused() ? BfTheme.AMBER : BfTheme.BORDER_HOVER);
            g.pose().pushPose();
            g.pose().translate(4, 6, 0);
            super.renderWidget(g, mouseX - 4, mouseY - 6, partialTick);
            g.pose().popPose();
        }
    }

    @Override
    protected void init() {
        layout = WorkshopAiLayout.fit(width, height);
        font = BfTheme.font();
        WorkshopVoiceCapture.openScreen();
        heldKey = -1;
        mouseHeld = false;
        int m = WorkshopAiLayout.MARGIN, inner = layout.inner();
        prompt = addRenderableWidget(new Prompt(m, layout.promptY(), inner, layout.promptHeight()));
        prompt.setCharacterLimit(WorkshopAiRequestC2SPacket.MAX_PROMPT);
        prompt.setValue(WorkshopAiClient.draft());
        prompt.setValueListener(WorkshopAiClient::draft);
        target =
                addRenderableWidget(
                        new WorkshopSegmented(
                                m,
                                layout.targetY(),
                                inner,
                                text("target.caption"),
                                new Component[] {text("target.here"), text("target.library")},
                                new Component[] {
                                    text("target.here_hint"), text("target.library_hint")
                                },
                                index -> {
                                    libraryTarget = index == 1;
                                    notice = "";
                                    updateControls();
                                }));
        target.select(libraryTarget ? 1 : 0);
        speed =
                addRenderableWidget(
                        new WorkshopSegmented(
                                m,
                                layout.speedY(),
                                layout.speedWidth(true),
                                text("speed_caption"),
                                new Component[] {text("speed_fast"), text("speed_quality")},
                                new Component[] {
                                    text("speed_fast_hint"), text("speed_quality_hint")
                                },
                                this::chooseSpeed));
        speed.select(WorkshopAiSpeedChoice.fast() ? 0 : 1);
        voice =
                addRenderableWidget(
                        new WorkshopButton(
                                layout.voiceX(),
                                layout.speedY(),
                                layout.voiceWidth(),
                                BfTheme.Style.SECONDARY,
                                this::startVoice));
        // Hold-to-talk: the red «Запись» state is not a destructive action to confirm.
        voice.confirmless();
        status = addRenderableWidget(new WorkshopStatusBlock(m, layout.statusY(), inner));
        int sizeY = layout.statusY() + 12, sx = m + 8;
        sizeMode =
                addRenderableWidget(
                        new WorkshopSegmented(
                                sx,
                                sizeY,
                                140,
                                text("size.title"),
                                new Component[] {text("size.selection"), text("size.custom")},
                                new Component[] {text("size.selection"), text("size.custom")},
                                index -> {
                                    useSelection = index == 0;
                                    notice = "";
                                }));
        sizeMode.select(useSelection ? 0 : 1);
        String[] values = {sizeW, sizeH, sizeD};
        sizes = new EditBox[3];
        for (int i = 0; i < 3; i++) {
            int axis = i;
            sizes[i] = addRenderableWidget(new SizeBox(sx + 144 + i * 44, sizeY));
            sizes[i].setMaxLength(3);
            sizes[i].setValue(values[i]);
            sizes[i].setFilter(value -> value.matches("[0-9]{0,3}"));
            sizes[i].setResponder(
                    value -> {
                        switch (axis) {
                            case 0 -> sizeW = value;
                            case 1 -> sizeH = value;
                            default -> sizeD = value;
                        }
                    });
        }
        undo =
                addRenderableWidget(
                        new WorkshopButton(
                                m + inner - 72,
                                layout.statusY() + 15,
                                72,
                                BfTheme.Style.DESTRUCTIVE,
                                () -> submit("undo", "")));
        undo.set(text("undo"), BfTheme.Style.DESTRUCTIVE, true, () -> submit("undo", ""));
        primary =
                addRenderableWidget(
                        new WorkshopButton(
                                m,
                                layout.actionsY(),
                                layout.primaryWidth(),
                                BfTheme.Style.PRIMARY,
                                null));
        secondary =
                addRenderableWidget(
                        new WorkshopButton(
                                layout.secondaryX(),
                                layout.actionsY(),
                                80,
                                BfTheme.Style.SECONDARY,
                                null));
        tertiary =
                addRenderableWidget(
                        new WorkshopButton(
                                layout.tertiaryX(),
                                layout.actionsY(),
                                56,
                                BfTheme.Style.TERTIARY,
                                null));
        shown = WorkshopAiClient.state();
        WorkshopAiClient.request("status", "");
        WorkshopAiClient.refreshLibrary();
        updateControls();
        if (!view.form()) setInitialFocus(primary.visible ? primary : tertiary);
        else if (view.focusPrimary() && !focusPrompt) setInitialFocus(primary);
        else setInitialFocus(prompt);
    }

    private void chooseSpeed(int index) {
        if ((index == 0) == WorkshopAiSpeedChoice.fast()) return;
        try {
            WorkshopAiSpeedChoice.toggle();
        } catch (IOException exception) {
            LOGGER.warn("Cannot save workshop speed preference", exception);
            notice = text("settings_error").getString();
            noticeWarn = true;
            speed.select(WorkshopAiSpeedChoice.fast() ? 0 : 1);
        }
    }

    /** The workshop lobby: AI and tools are available to hub builders, inside the sandbox. */
    static boolean hub() {
        var mc = Minecraft.getInstance();
        return mc != null
                && mc.level != null
                && WeaponHud.inWorkshop()
                && mc.level.dimension().location().getPath().equals("hub");
    }

    private boolean pending() {
        return System.nanoTime() < pendingUntil;
    }

    private WorkshopAiPanelState.Input input() {
        var state = WorkshopAiClient.state();
        String text = prompt.getValue().strip();
        return new WorkshopAiPanelState.Input(
                state.stage(),
                WorkshopAiClient.available(),
                hub(),
                WorkshopVoiceCapture.recording(),
                WorkshopAiClient.uploading(),
                pending() || WorkshopAiClient.throttled(),
                state.canUndo(),
                WorkshopAiEffects.hasPreview(state.ownerId(), state.jobId()),
                text.isEmpty(),
                !text.equals(WorkshopAiClient.generated()),
                libraryTarget,
                FabricLoader.getInstance().isModLoaded("voicechat"),
                state.canVoice());
    }

    private void apply(WorkshopButton button, WorkshopAiPanelState.Slot slot) {
        button.visible = slot != null;
        if (slot == null) return;
        Component label =
                switch (slot.key()) {
                    case "sketches" -> text("sketches", WorkshopAiClient.library().size());
                    case "recording" ->
                            text(
                                    "recording",
                                    WorkshopAiProgressState.duration(
                                            WorkshopVoiceCapture.seconds()));
                    default -> text(slot.key());
                };
        button.set(label, slot.style(), slot.enabled(), () -> run(slot.action()))
                .tooltip(slot.tooltip() == null ? null : tooltipText(slot.tooltip()));
    }

    private static Component tooltipText(String key) {
        return key.equals("preview_missing")
                ? text(key)
                : text(key, WorkshopControls.key("ai").getTranslatedKeyMessage());
    }

    private void updateControls() {
        view = WorkshopAiPanelState.of(input());
        boolean form = view.form();
        prompt.visible = form;
        prompt.editable = view.promptEditable();
        target.visible = speed.visible = status.visible = form;
        target.active = speed.active = view.rowsActive();
        speed.setWidth(layout.speedWidth(view.voice() != null));
        apply(voice, view.voice());
        boolean sizing =
                form
                        && libraryTarget
                        && view.rowsActive()
                        && sizing(WorkshopAiClient.state().stage());
        sizeMode.visible = sizing;
        for (EditBox box : sizes) {
            box.visible = sizing;
            box.active = sizing && !useSelection;
            box.setEditable(box.active);
        }
        undo.visible = form && view.undoSlot();
        undo.active = !pending();
        apply(primary, view.primary());
        apply(secondary, view.secondary());
        apply(tertiary, view.tertiary());
        if (form) status.show(status(), undo.visible);
    }

    private void run(WorkshopAiPanelState.Action action) {
        switch (action) {
            case GENERATE -> generate();
            case APPLY -> submit("apply", "");
            case DISCARD, STOP -> stop();
            case PLACE_HELD -> onClose();
            case REMOVE_HELD -> {
                if (WorkshopSketchPlacement.active()) WorkshopSketchPlacement.attack();
                else stop();
            }
            case LIBRARY -> minecraft.setScreen(new WorkshopAiLibraryScreen());
            case CLOSE -> onClose();
            case OPEN_MAPS -> {
                onClose();
                WorkshopControls.execute(minecraft, "maps");
            }
            case VOICE -> startVoice();
            case NONE -> {}
        }
        updateControls();
    }

    private void generate() {
        String text = prompt.getValue().strip();
        if (!libraryTarget) {
            submit("generate", text);
            return;
        }
        int sx = 0, sy = 0, sz = 0;
        if (!useSelection) {
            sx = parsedSize(sizeW);
            sy = parsedSize(sizeH);
            sz = parsedSize(sizeD);
            if (sx < 0 || sy < 0 || sz < 0) {
                notice = text("invalid_size").getString();
                noticeWarn = true;
                return;
            }
        }
        if (!WorkshopAiClient.generateLibrary(text, sx, sy, sz)) return;
        sent();
    }

    static int parsedSize(String value) {
        try {
            int side = Integer.parseInt(value);
            return side >= 1 && side <= 128 ? side : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private void sent() {
        pendingUntil = System.nanoTime() + PENDING_NS;
        notice = "";
        waiting = false;
    }

    private void submit(String action, String value) {
        if (pending() || !WorkshopAiClient.request(action, value)) return;
        sent();
    }

    private void stop() {
        WorkshopAiClient.discardUpload();
        if (WorkshopAiClient.request("cancel", "")) sent();
    }

    private void startVoice() {
        if (!voice.active || WorkshopVoiceCapture.recording()) return;
        WorkshopVoiceCapture.start();
        notice = "";
        updateControls();
    }

    private void finishVoice() {
        heldKey = -1;
        mouseHeld = false;
        byte[] wav = WorkshopVoiceCapture.finish();
        boolean sent = WorkshopAiClient.transcribe(wav);
        if (!sent && wav != null) java.util.Arrays.fill(wav, (byte) 0);
        notice = sent ? "" : text("voice_empty").getString();
        noticeWarn = !sent;
        if (sent) sent();
        updateControls();
    }

    private void discardRecording() {
        WorkshopVoiceCapture.discard();
        heldKey = -1;
        mouseHeld = false;
        notice = text("recording_cancelled").getString();
        noticeWarn = false;
        updateControls();
    }

    /** Status block contents for the current stage (table «c»). */
    private WorkshopStatusBlock.Status status() {
        var state = WorkshopAiClient.state();
        String stage = WorkshopAiClient.uploading() ? "transcribing" : state.stage();
        String message = state.message();
        int accent = view.accent();
        WorkshopStatusBlock.Status s;
        if (WorkshopVoiceCapture.recording()) {
            s =
                    WorkshopStatusBlock.Status.of(
                            accent, text("recording_hint").getString(), BfTheme.TEXT);
        } else if (WorkshopAiPanelState.working(stage)) {
            s = working(state, stage, accent);
        } else
            s =
                    switch (stage) {
                        case "preview" -> {
                            boolean shownInWorld =
                                    WorkshopAiEffects.hasPreview(state.ownerId(), state.jobId());
                            var p =
                                    WorkshopStatusBlock.Status.of(
                                                    accent,
                                                    shownInWorld
                                                            ? orStage(message, "preview")
                                                            : text("preview_missing").getString(),
                                                    BfTheme.TEXT)
                                            .withTime(bounds(state), BfTheme.TEXT_SECONDARY)
                                            .withHint(
                                                    text("preview_hint").getString(),
                                                    BfTheme.MUTED);
                            yield state.totalBlocks() > WorkshopBlueprint.MAX_GHOSTS
                                    ? p.withWaiting(text("simplified_hint").getString())
                                    : p;
                        }
                        case "held" ->
                                WorkshopStatusBlock.Status.of(
                                        accent, orStage(message, "held"), BfTheme.TEXT);
                        case "complete" ->
                                WorkshopStatusBlock.Status.of(
                                        accent, orStage(message, "complete"), BfTheme.OK);
                        case "cancelled" ->
                                WorkshopStatusBlock.Status.of(
                                        accent, orStage(message, "cancelled"), BfTheme.TEXT);
                        case "error" ->
                                WorkshopStatusBlock.Status.of(
                                                accent,
                                                orStage(message, "error"),
                                                BfTheme.TEXT_SECONDARY)
                                        .withTitle(text("error_title").getString(), BfTheme.DANGER);
                        default -> idle(message, accent);
                    };
        if (libraryTarget && java.util.Set.of("complete", "cancelled", "error").contains(stage))
            s = libraryResult(s, accent);
        if (!notice.isEmpty() && !libraryTarget)
            s =
                    WorkshopStatusBlock.Status.of(
                            noticeWarn ? BfTheme.WARN : accent,
                            notice,
                            noticeWarn ? BfTheme.WARN : BfTheme.TEXT_SECONDARY);
        int length = prompt.getValue().length();
        if (length > PROMPT_WARN && view.promptEditable())
            s =
                    s.withHint(
                            text("prompt_left", WorkshopAiRequestC2SPacket.MAX_PROMPT - length)
                                    .getString(),
                            BfTheme.WARN);
        if (waiting) s = s.withWaiting(text("waiting").getString());
        return s;
    }

    private WorkshopStatusBlock.Status idle(String message, int accent) {
        var idle =
                WorkshopAiPanelState.idle(
                        libraryTarget,
                        message,
                        useSelection && WorkshopWandSelection.box() == null);
        String hint = text(idle.hintKey()).getString();
        int color = BfTheme.MUTED;
        if (libraryTarget && !notice.isEmpty()) {
            hint = notice;
            color = noticeWarn ? BfTheme.WARN : BfTheme.TEXT_SECONDARY;
        }
        if (!libraryTarget && idle.message().isBlank())
            return WorkshopStatusBlock.Status.of(accent, hint, color);
        // Library: the size row sits between the caption and the bottom hint.
        return WorkshopStatusBlock.Status.of(accent, idle.message(), BfTheme.TEXT)
                .withTitle(
                        libraryTarget ? text("size.title").getString() : "", BfTheme.TEXT_SECONDARY)
                .withHint(hint, color);
    }

    /**
     * In library mode the size picker stays usable after a result, so the result moves to the title
     * row (cut text opens the tooltip) and the picker keeps its place.
     */
    private WorkshopStatusBlock.Status libraryResult(
            WorkshopStatusBlock.Status result, int accent) {
        var plain = idle("", accent);
        String line =
                result.title().isEmpty()
                        ? result.message()
                        : result.title() + ": " + result.message();
        int color = result.title().isEmpty() ? result.messageColor() : result.titleColor();
        return plain.withTitle(line, color);
    }

    /** Stages whose status leaves room for the library size picker. */
    private static boolean sizing(String stage) {
        return !WorkshopAiPanelState.working(stage)
                && !stage.equals("preview")
                && !stage.equals("held");
    }

    private WorkshopStatusBlock.Status working(
            WorkshopAiStateS2CPacket state, String stage, int accent) {
        long now = System.nanoTime();
        var progress = WorkshopAiClient.progress();
        boolean tracked =
                progress != null
                        && progress.busy()
                        && (progress.jobId().equals(state.jobId()) || WorkshopAiClient.uploading());
        if (stage.equals("applying") || stage.equals("undoing")) {
            int total = Math.max(1, state.totalBlocks());
            String line =
                    text(
                                    stage.equals("applying") ? "progress.built" : "progress.undone",
                                    WorkshopAiFormat.count(state.completedBlocks()),
                                    WorkshopAiFormat.count(state.totalBlocks()))
                            .getString();
            return WorkshopStatusBlock.Status.of(accent, line, BfTheme.TEXT)
                    .withBar(state.completedBlocks() / (float) total, BfTheme.AMBER)
                    .withTime(area(state), BfTheme.TEXT_SECONDARY)
                    .withHint(text("hide_progress_hint").getString(), BfTheme.MUTED);
        }
        if (stage.equals("transcribing"))
            return WorkshopStatusBlock.Status.of(
                            accent, text("transcribing").getString(), BfTheme.TEXT)
                    .withHint(text("hide_progress_hint").getString(), BfTheme.MUTED);
        String title = tracked ? Component.translatable(progress.phaseKey()).getString() : "";
        if (title.isBlank()) title = text("stage." + stage).getString();
        if (tracked && progress.progress().partsTotal() > 0)
            title +=
                    " · "
                            + text(
                                            "progress.parts_of",
                                            progress.progress().partsDone(),
                                            progress.progress().partsTotal())
                                    .getString();
        boolean longer = tracked && progress.overdue(now);
        var s =
                WorkshopStatusBlock.Status.of(longer ? BfTheme.WARN : accent, title, BfTheme.TEXT)
                        .withHint(
                                text(longer ? "progress.longer_hint" : "hide_progress_hint")
                                        .getString(),
                                BfTheme.MUTED);
        if (tracked) {
            Component eta = WorkshopAiEtaBar.label();
            String time =
                    progress.elapsedLabel(now).getString()
                            + " · "
                            + (eta != null ? eta : progress.remainingLabel(now)).getString();
            s =
                    s.withTime(time, longer ? BfTheme.WARN : BfTheme.TEXT_SECONDARY)
                            .withBar(
                                    WorkshopAiEtaBar.fraction(),
                                    longer ? BfTheme.WARN : BfTheme.AMBER);
        }
        if (stage.equals("awaiting") && !state.message().isBlank())
            s = s.withHint(state.message(), BfTheme.TEXT_SECONDARY);
        else if (libraryTarget && !useSelection && !longer)
            s = s.withHint(text("size.fixed", sizeW, sizeH, sizeD).getString(), BfTheme.MUTED);
        return s;
    }

    private static String orStage(String message, String stage) {
        return message.isBlank() ? stageText(stage) : message;
    }

    /** Stage text with a fallback: a stage the client has no key for never shows a raw key (D1). */
    static String stageText(String stage) {
        String key = "blockfield.workshop.ai.stage." + stage;
        return net.minecraft.client.resources.language.I18n.exists(key)
                ? Component.translatable(key).getString()
                : text("stage.idle").getString();
    }

    private static String bounds(WorkshopAiStateS2CPacket state) {
        if (state.totalBlocks() <= 0) return "";
        return text(
                        "bounds",
                        WorkshopAiFormat.count(state.totalBlocks()),
                        state.max().getX() - state.min().getX() + 1,
                        state.max().getY() - state.min().getY() + 1,
                        state.max().getZ() - state.min().getZ() + 1)
                .getString();
    }

    private static String area(WorkshopAiStateS2CPacket state) {
        return text(
                        "area",
                        state.max().getX() - state.min().getX() + 1,
                        state.max().getY() - state.min().getY() + 1,
                        state.max().getZ() - state.min().getZ() + 1)
                .getString();
    }

    @Override
    public void tick() {
        var state = WorkshopAiClient.state();
        if (state != shown) {
            boolean finished =
                    !shown.stage().equals(state.stage()) && state.stage().equals("complete");
            shown = state;
            pendingUntil = 0;
            waiting = false;
            notice = "";
            WorkshopAiClient.refreshLibrary();
            if (state.stage().equals("idle") && !state.prompt().isBlank()) {
                prompt.setValue(state.prompt());
                setInitialFocus(prompt);
            }
            if (finished) {
                prompt.selectAll();
                setInitialFocus(prompt);
            }
        }
        if (!WorkshopAiClient.available()) {
            WorkshopVoiceCapture.discard();
            heldKey = -1;
            mouseHeld = false;
        }
        // Release also on focus loss or a lost mouse-up callback; no recording survives alt-tab.
        if (WorkshopVoiceCapture.recording()) {
            if (!minecraft.isWindowActive()) discardRecording();
            else if (WorkshopVoiceCapture.timedOut()) finishVoice();
            else if (mouseHeld
                    && GLFW.glfwGetMouseButton(
                                    minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                            != GLFW.GLFW_PRESS) finishVoice();
        }
        if (pendingUntil != 0 && System.nanoTime() >= pendingUntil) {
            pendingUntil = 0;
            waiting = true;
        }
        updateControls();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render invokes this after our labels; the sheet already paints its own backdrop.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateControls();
        int w = layout.width(), h = layout.height(), m = WorkshopAiLayout.MARGIN;
        double lx = layout.localX(mouseX), ly = layout.localY(mouseY);
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale(layout.scale(), layout.scale(), 1);
        BfTheme.panel(graphics, 0, 0, w, h, true);
        BfTheme.ticks(graphics, 0, 0, w, h, 6, BfTheme.AMBER);
        int dot = view.badgeColor();
        if (view.pulse() && Util.getMillis() / 500 % 2 == 1) dot = BfTheme.alpha(dot, 0x60);
        BfTheme.header(
                graphics,
                font,
                m,
                12,
                layout.inner(),
                title.getString(),
                text("badge." + view.badge()).getString(),
                view.badgeColor(),
                dot);
        if (!view.form()) renderDisabled(graphics);
        else if (prompt.getValue().isEmpty() && WorkshopAiClient.uploading())
            graphics.drawString(
                    font,
                    stageText("transcribing"),
                    m + 4,
                    layout.promptY() + 4,
                    BfTheme.MUTED_DARK,
                    false);
        Tablet.withScissorShift(() -> super.render(graphics, (int) lx, (int) ly, partialTick));
        if (status.visible && libraryTarget && sizeMode.visible)
            for (int i = 0; i < 2; i++)
                graphics.drawString(
                        font, "×", sizes[i].getX() + 38, sizes[i].getY() + 6, BfTheme.MUTED, false);
        graphics.pose().popPose();
        List<Component> tip = tooltip(lx);
        if (!tip.isEmpty()) BfTheme.tooltip(graphics, font, tip, mouseX, mouseY, width, height);
    }

    /**
     * The mouse wins over keyboard focus: a hovered disabled «Голос» explains itself even while a
     * segment keeps focus (A7). Focus tooltips show only when nothing is hovered.
     */
    private List<Component> tooltip(double lx) {
        for (boolean focused : new boolean[] {false, true}) {
            if (!status.tooltip(focused).isEmpty()) return status.tooltip(focused);
            for (var segmented : new WorkshopSegmented[] {target, speed}) {
                Component hint = segmented.tooltip(lx, focused);
                if (hint != null) return wrapped(hint);
            }
            for (var button : new WorkshopButton[] {voice, primary, secondary, tertiary})
                if (button.visible
                        && button.tooltip() != null
                        && (focused ? button.isFocused() : button.isHovered()))
                    return wrapped(button.tooltip());
        }
        return List.of();
    }

    private List<Component> wrapped(Component text) {
        var lines = new ArrayList<Component>();
        for (String line :
                BfTheme.wrap(font, text.getString(), WorkshopStatusBlock.TOOLTIP_WIDTH, 8).lines())
            lines.add(Component.literal(line));
        return lines;
    }

    private void renderDisabled(GuiGraphics graphics) {
        int m = WorkshopAiLayout.MARGIN, inner = layout.inner();
        String title, body;
        if (hub()) {
            title = text("disabled.hub_title").getString();
            body =
                    text("disabled.hub_body", WorkshopControls.key("ai").getTranslatedKeyMessage())
                            .getString();
        } else {
            title = stageText("disabled");
            body = WorkshopAiClient.state().message();
        }
        var lines = BfTheme.wrap(font, body, inner - 16, 4).lines();
        int y = (layout.actionsY() - 12 - lines.size() * 10) / 2 + 8;
        graphics.drawCenteredString(font, title, m + inner / 2, y, BfTheme.TEXT);
        for (int i = 0; i < lines.size(); i++)
            graphics.drawCenteredString(
                    font, lines.get(i), m + inner / 2, y + 14 + i * 10, BfTheme.TEXT_SECONDARY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (WorkshopControls.key("ai").matchesMouse(button)
                || WorkshopControls.key("radial").matchesMouse(button)) {
            onClose();
            return true;
        }
        double x = layout.localX(mouseX), y = layout.localY(mouseY);
        boolean starts = button == 0 && voice.visible && voice.active && voice.isMouseOver(x, y);
        boolean result = super.mouseClicked(x, y, button);
        if (starts && WorkshopVoiceCapture.recording()) mouseHeld = true;
        return result;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseHeld) finishVoice();
        return super.mouseReleased(layout.localX(mouseX), layout.localY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return super.mouseDragged(
                layout.localX(mouseX),
                layout.localY(mouseY),
                button,
                dx / layout.scale(),
                dy / layout.scale());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(layout.localX(mouseX), layout.localY(mouseY), scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((getFocused() != prompt || !prompt.editable)
                && !(getFocused() instanceof EditBox)
                && (WorkshopControls.matches("ai", keyCode, scanCode)
                        || WorkshopControls.matches("radial", keyCode, scanCode))) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && WorkshopVoiceCapture.recording()) {
            discardRecording();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (primary.visible && primary.active) primary.onPress();
            return true;
        }
        boolean starts =
                voice.visible
                        && voice.active
                        && getFocused() == voice
                        && (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER);
        boolean result = super.keyPressed(keyCode, scanCode, modifiers);
        if (starts && WorkshopVoiceCapture.recording()) heldKey = keyCode;
        return result;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (heldKey == keyCode) {
            finishVoice();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        WorkshopVoiceCapture.closeScreen();
        minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        WorkshopVoiceCapture.closeScreen();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
