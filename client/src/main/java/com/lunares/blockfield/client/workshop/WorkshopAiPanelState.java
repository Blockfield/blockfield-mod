package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;

/**
 * Table «c» of the AI panel as a pure function: stage and client flags in, labels, styles,
 * availability and the header badge out. Keys are relative to {@code blockfield.workshop.ai.}.
 */
record WorkshopAiPanelState(
        String badge,
        int badgeColor,
        boolean pulse,
        int accent,
        boolean form,
        boolean promptEditable,
        boolean rowsActive,
        Slot voice,
        Slot primary,
        Slot secondary,
        Slot tertiary,
        boolean undoSlot,
        boolean focusPrimary) {

    enum Action {
        GENERATE,
        APPLY,
        PLACE_HELD,
        REMOVE_HELD,
        DISCARD,
        STOP,
        LIBRARY,
        CLOSE,
        OPEN_MAPS,
        VOICE,
        NONE
    }

    /** A button slot; {@code tooltip} explains a disabled slot (or describes an enabled one). */
    record Slot(String key, BfTheme.Style style, boolean enabled, String tooltip, Action action) {}

    record Input(
            String stage,
            boolean available,
            boolean hub,
            boolean recording,
            boolean uploading,
            boolean pending,
            boolean canUndo,
            boolean previewShown,
            boolean promptBlank,
            boolean promptChanged,
            boolean library,
            boolean voiceMod,
            boolean canVoice) {}

    /** Idle status text: the world-safety hint always stays; a server reply only adds a line. */
    record Idle(String message, String hintKey) {}

    static Idle idle(boolean library, String serverMessage, boolean needsSelection) {
        if (library)
            // The size picker fills the block; the generic idle reply has no room and adds nothing.
            return new Idle("", needsSelection ? "size.no_selection" : "idle_library_hint");
        return new Idle(serverMessage, "idle_hint");
    }

    static boolean working(String stage) {
        return WorkshopToolsClient.busy(stage);
    }

    static WorkshopAiPanelState of(Input in) {
        var close = new Slot("close", BfTheme.Style.TERTIARY, true, null, Action.CLOSE);
        var hide = new Slot("hide", BfTheme.Style.TERTIARY, true, "hide_hint", Action.CLOSE);
        if (!in.available()) {
            var maps =
                    in.hub()
                            ? new Slot(
                                    "open_maps",
                                    BfTheme.Style.PRIMARY,
                                    true,
                                    null,
                                    Action.OPEN_MAPS)
                            : null;
            return new WorkshopAiPanelState(
                    "off",
                    BfTheme.MUTED_DARK,
                    false,
                    BfTheme.MUTED_DARK,
                    false,
                    false,
                    false,
                    null,
                    maps,
                    null,
                    close,
                    false,
                    in.hub());
        }
        String stage = in.uploading() ? "transcribing" : in.stage();
        boolean ready = !in.pending();
        var sketches = new Slot("sketches", BfTheme.Style.SECONDARY, true, null, Action.LIBRARY);
        var stop = new Slot("cancel", BfTheme.Style.SECONDARY, true, null, Action.STOP);
        Slot voice = voice(in, stage);
        if (in.recording()) {
            return new WorkshopAiPanelState(
                    "voice",
                    BfTheme.DANGER,
                    false,
                    BfTheme.DANGER,
                    true,
                    true,
                    true,
                    voice,
                    generate(in, false),
                    sketches,
                    close,
                    false,
                    false);
        }
        if (working(stage)) {
            String label =
                    switch (stage) {
                        case "planning", "awaiting" -> "working";
                        case "applying" -> "building";
                        case "undoing" -> "undoing_short";
                        default -> "generate";
                    };
            return new WorkshopAiPanelState(
                    "working",
                    BfTheme.AMBER,
                    true,
                    BfTheme.AMBER,
                    true,
                    false,
                    false,
                    voice,
                    new Slot(label, BfTheme.Style.PRIMARY, false, null, Action.NONE),
                    stop,
                    hide,
                    false,
                    false);
        }
        switch (stage) {
            case "preview" -> {
                return new WorkshopAiPanelState(
                        "sketch",
                        BfTheme.AMBER_BRIGHT,
                        false,
                        BfTheme.AMBER,
                        true,
                        false,
                        false,
                        voice,
                        new Slot(
                                "apply",
                                BfTheme.Style.PRIMARY,
                                ready && in.previewShown(),
                                in.previewShown() ? null : "preview_missing",
                                Action.APPLY),
                        new Slot("discard", BfTheme.Style.DESTRUCTIVE, true, null, Action.DISCARD),
                        hide,
                        false,
                        true);
            }
            case "held" -> {
                return new WorkshopAiPanelState(
                        "sketch",
                        BfTheme.AMBER_BRIGHT,
                        false,
                        BfTheme.AMBER,
                        true,
                        false,
                        false,
                        voice,
                        new Slot(
                                "place_held", BfTheme.Style.PRIMARY, true, null, Action.PLACE_HELD),
                        new Slot(
                                "remove_held",
                                BfTheme.Style.SECONDARY,
                                true,
                                null,
                                Action.REMOVE_HELD),
                        hide,
                        false,
                        true);
            }
            case "error" -> {
                return new WorkshopAiPanelState(
                        "error",
                        BfTheme.DANGER,
                        false,
                        BfTheme.DANGER,
                        true,
                        true,
                        true,
                        voice,
                        generate(in, !in.promptChanged()),
                        sketches,
                        close,
                        false,
                        false);
            }
            default -> {
                boolean done = stage.equals("complete") && in.canUndo();
                boolean cancelled = stage.equals("cancelled");
                return new WorkshopAiPanelState(
                        done ? "done" : "ready",
                        done ? BfTheme.OK : BfTheme.MUTED,
                        false,
                        stage.equals("complete") ? BfTheme.OK : BfTheme.MUTED,
                        true,
                        true,
                        true,
                        voice,
                        generate(in, false),
                        sketches,
                        close,
                        (done || cancelled) && in.canUndo(),
                        false);
            }
        }
    }

    private static Slot generate(Input in, boolean retry) {
        boolean enabled = !in.recording() && !in.pending() && !in.promptBlank();
        return new Slot(
                retry ? "retry" : "generate",
                BfTheme.Style.PRIMARY,
                enabled,
                in.promptBlank() ? "generate_blank" : null,
                Action.GENERATE);
    }

    /** «Голос» exists only for «Строить здесь»; its tooltip names why it is off (D18). */
    private static Slot voice(Input in, String stage) {
        if (in.library()) return null;
        if (in.recording())
            return new Slot(
                    "recording", BfTheme.Style.DESTRUCTIVE, true, "recording_hint", Action.VOICE);
        if (stage.equals("transcribing"))
            return new Slot(
                    "transcribing_short", BfTheme.Style.SECONDARY, false, null, Action.VOICE);
        String why = !in.voiceMod() ? "voice_no_mod" : !in.canVoice() ? "voice_unavailable" : null;
        boolean idle = !working(stage) && !stage.equals("preview") && !stage.equals("held");
        return new Slot(
                "voice",
                BfTheme.Style.SECONDARY,
                why == null && idle && !in.pending(),
                why == null ? "voice_hint" : why,
                Action.VOICE);
    }
}
