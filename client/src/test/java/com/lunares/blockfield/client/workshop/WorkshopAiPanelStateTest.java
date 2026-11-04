package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.workshop.WorkshopAiPanelState.Action;
import com.lunares.blockfield.client.workshop.WorkshopAiPanelState.Input;
import com.lunares.blockfield.client.workshop.WorkshopAiPanelState.Slot;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One case per row of the redesign's state table «c». */
class WorkshopAiPanelStateTest {
    /** Idle «Строить здесь» with text, voice available, nothing pending. */
    private static Input in(String stage) {
        return new Input(
                stage, true, false, false, false, false, false, true, false, false, false, true,
                true);
    }

    private static Input with(Input b, String field) {
        return new Input(
                b.stage(),
                !field.equals("unavailable") && b.available(),
                field.equals("hub") || b.hub(),
                field.equals("recording") || b.recording(),
                field.equals("uploading") || b.uploading(),
                field.equals("pending") || b.pending(),
                field.equals("canUndo") || b.canUndo(),
                !field.equals("noPreview") && b.previewShown(),
                field.equals("blank") || b.promptBlank(),
                field.equals("changed") || b.promptChanged(),
                field.equals("library") || b.library(),
                !field.equals("noMod") && b.voiceMod(),
                !field.equals("noVoice") && b.canVoice());
    }

    private static void slot(
            Slot slot, String key, BfTheme.Style style, boolean enabled, Action action) {
        assertNotNull(slot, key);
        assertEquals(key, slot.key());
        assertEquals(style, slot.style(), key);
        assertEquals(enabled, slot.enabled(), key);
        assertEquals(action, slot.action(), key);
    }

    @Test
    void idleHereAndLibrary() {
        var idle = WorkshopAiPanelState.of(in("idle"));
        assertEquals("ready", idle.badge());
        assertTrue(idle.form() && idle.promptEditable() && idle.rowsActive());
        slot(idle.primary(), "generate", BfTheme.Style.PRIMARY, true, Action.GENERATE);
        slot(idle.secondary(), "sketches", BfTheme.Style.SECONDARY, true, Action.LIBRARY);
        slot(idle.tertiary(), "close", BfTheme.Style.TERTIARY, true, Action.CLOSE);
        slot(idle.voice(), "voice", BfTheme.Style.SECONDARY, true, Action.VOICE);
        var blank = WorkshopAiPanelState.of(with(in("idle"), "blank"));
        assertFalse(blank.primary().enabled());
        assertEquals("generate_blank", blank.primary().tooltip());
        var library = WorkshopAiPanelState.of(with(in("idle"), "library"));
        assertNull(library.voice());
        slot(library.primary(), "generate", BfTheme.Style.PRIMARY, true, Action.GENERATE);
    }

    @Test
    void recordingDisablesCreateAndPaintsVoiceRed() {
        var rec = WorkshopAiPanelState.of(with(in("idle"), "recording"));
        assertEquals("voice", rec.badge());
        assertEquals(BfTheme.DANGER, rec.badgeColor());
        assertFalse(rec.primary().enabled());
        slot(rec.voice(), "recording", BfTheme.Style.DESTRUCTIVE, true, Action.VOICE);
        assertEquals("recording_hint", rec.voice().tooltip());
        assertTrue(rec.promptEditable());
    }

    @Test
    void transcribingPlanningAwaitingApplyingUndoing() {
        var upload = WorkshopAiPanelState.of(with(in("idle"), "uploading"));
        slot(upload.primary(), "generate", BfTheme.Style.PRIMARY, false, Action.NONE);
        slot(upload.voice(), "transcribing_short", BfTheme.Style.SECONDARY, false, Action.VOICE);
        String[][] rows = {
            {"transcribing", "generate"},
            {"planning", "working"},
            {"awaiting", "working"},
            {"applying", "building"},
            {"undoing", "undoing_short"}
        };
        for (String[] row : rows) {
            var s = WorkshopAiPanelState.of(in(row[0]));
            assertEquals("working", s.badge(), row[0]);
            assertTrue(s.pulse(), row[0]);
            assertFalse(s.promptEditable() || s.rowsActive(), row[0]);
            slot(s.primary(), row[1], BfTheme.Style.PRIMARY, false, Action.NONE);
            slot(s.secondary(), "cancel", BfTheme.Style.SECONDARY, true, Action.STOP);
            slot(s.tertiary(), "hide", BfTheme.Style.TERTIARY, true, Action.CLOSE);
        }
    }

    @Test
    void previewWithAndWithoutTheGhost() {
        var preview = WorkshopAiPanelState.of(in("preview"));
        assertEquals("sketch", preview.badge());
        assertTrue(preview.focusPrimary());
        assertFalse(preview.promptEditable());
        slot(preview.primary(), "apply", BfTheme.Style.PRIMARY, true, Action.APPLY);
        slot(preview.secondary(), "discard", BfTheme.Style.DESTRUCTIVE, true, Action.DISCARD);
        slot(preview.tertiary(), "hide", BfTheme.Style.TERTIARY, true, Action.CLOSE);
        assertFalse(preview.voice().enabled());
        var far = WorkshopAiPanelState.of(with(in("preview"), "noPreview"));
        assertFalse(far.primary().enabled());
        assertEquals("preview_missing", far.primary().tooltip());
    }

    @Test
    void heldOffersPlaceAndRemove() {
        var held = WorkshopAiPanelState.of(in("held"));
        assertEquals("sketch", held.badge());
        slot(held.primary(), "place_held", BfTheme.Style.PRIMARY, true, Action.PLACE_HELD);
        slot(held.secondary(), "remove_held", BfTheme.Style.SECONDARY, true, Action.REMOVE_HELD);
    }

    @Test
    void completeAndCancelledOfferUndoOnlyWhenPossible() {
        var done = WorkshopAiPanelState.of(with(in("complete"), "canUndo"));
        assertEquals("done", done.badge());
        assertEquals(BfTheme.OK, done.accent());
        assertTrue(done.undoSlot() && done.promptEditable());
        slot(done.primary(), "generate", BfTheme.Style.PRIMARY, true, Action.GENERATE);
        var doneNoUndo = WorkshopAiPanelState.of(in("complete"));
        assertEquals("ready", doneNoUndo.badge());
        assertFalse(doneNoUndo.undoSlot());
        assertTrue(WorkshopAiPanelState.of(with(in("cancelled"), "canUndo")).undoSlot());
        assertFalse(WorkshopAiPanelState.of(in("cancelled")).undoSlot());
    }

    @Test
    void errorRetriesTheSameTextUntilItChanges() {
        var error = WorkshopAiPanelState.of(in("error"));
        assertEquals("error", error.badge());
        assertTrue(error.promptEditable());
        slot(error.primary(), "retry", BfTheme.Style.PRIMARY, true, Action.GENERATE);
        slot(error.secondary(), "sketches", BfTheme.Style.SECONDARY, true, Action.LIBRARY);
        var edited = WorkshopAiPanelState.of(with(in("error"), "changed"));
        assertEquals("generate", edited.primary().key());
        // A pending request (e.g. a rate limit retry right after) keeps the button off.
        assertFalse(WorkshopAiPanelState.of(with(in("error"), "pending")).primary().enabled());
    }

    @Test
    void disabledHubOffersMapsDisabledMapOnlyClose() {
        var hub = WorkshopAiPanelState.of(with(with(in("disabled"), "unavailable"), "hub"));
        assertFalse(hub.form());
        assertEquals("off", hub.badge());
        slot(hub.primary(), "open_maps", BfTheme.Style.PRIMARY, true, Action.OPEN_MAPS);
        assertNull(hub.secondary());
        slot(hub.tertiary(), "close", BfTheme.Style.TERTIARY, true, Action.CLOSE);
        var map = WorkshopAiPanelState.of(with(in("disabled"), "unavailable"));
        assertNull(map.primary());
        assertNull(map.secondary());
    }

    @Test
    void voiceExplainsWhyItIsOff() {
        assertEquals(
                "voice_no_mod",
                WorkshopAiPanelState.of(with(in("idle"), "noMod")).voice().tooltip());
        var server = WorkshopAiPanelState.of(with(in("idle"), "noVoice")).voice();
        assertFalse(server.enabled());
        assertEquals("voice_unavailable", server.tooltip());
    }

    /** Every label, tooltip and badge the table can produce exists in both client languages. */
    @Test
    void everyProducedKeyIsTranslated() throws Exception {
        var keys = new HashSet<String>();
        String[] stages = {
            "idle",
            "transcribing",
            "planning",
            "awaiting",
            "preview",
            "held",
            "applying",
            "undoing",
            "complete",
            "cancelled",
            "error",
            "disabled"
        };
        String[] flags = {
            "",
            "unavailable",
            "hub",
            "recording",
            "uploading",
            "pending",
            "canUndo",
            "noPreview",
            "blank",
            "changed",
            "library",
            "noMod",
            "noVoice"
        };
        for (String stage : stages)
            for (String flag : flags) {
                var s = WorkshopAiPanelState.of(with(in(stage), flag));
                keys.add("badge." + s.badge());
                for (Slot slot : new Slot[] {s.voice(), s.primary(), s.secondary(), s.tertiary()}) {
                    if (slot == null) continue;
                    keys.add(slot.key());
                    if (slot.tooltip() != null) keys.add(slot.tooltip());
                }
            }
        for (String language : Set.of("ru_ru", "en_us")) {
            JsonObject table;
            try (var stream =
                    getClass()
                            .getResourceAsStream("/assets/blockfield/lang/" + language + ".json")) {
                table =
                        JsonParser.parseReader(
                                        new InputStreamReader(stream, StandardCharsets.UTF_8))
                                .getAsJsonObject();
            }
            for (String key : keys)
                assertTrue(table.has("blockfield.workshop.ai." + key), language + " " + key);
        }
    }

    @Test
    void serverIdleReplyNeverReplacesTheSafetyHint() {
        // A1: «Опиши постройку.» from the server joins the world-safety line, it does not replace
        // it.
        var here = WorkshopAiPanelState.idle(false, "Опиши постройку.", false);
        assertEquals("idle_hint", here.hintKey());
        assertEquals("Опиши постройку.", here.message());
        // A4: library mode keeps its selection/library hint whatever the server says.
        assertEquals(
                "size.no_selection",
                WorkshopAiPanelState.idle(true, "Опиши постройку.", true).hintKey());
        var library = WorkshopAiPanelState.idle(true, "Опиши постройку.", false);
        assertEquals("idle_library_hint", library.hintKey());
        assertEquals("", library.message());
    }

    @Test
    void aFocusedSegmentDoesNotAnswerForAHoveredWidget() {
        // A7: the hover pass must not return the focused segment's hint.
        var segment =
                new WorkshopSegmented(
                        12,
                        0,
                        276,
                        net.minecraft.network.chat.Component.literal("t"),
                        new net.minecraft.network.chat.Component[] {
                            net.minecraft.network.chat.Component.literal("a"),
                            net.minecraft.network.chat.Component.literal("b")
                        },
                        new net.minecraft.network.chat.Component[] {
                            net.minecraft.network.chat.Component.literal("hint a"),
                            net.minecraft.network.chat.Component.literal("hint b")
                        },
                        index -> {});
        segment.setFocused(true);
        assertNull(segment.tooltip(20, false));
        assertEquals("hint a", segment.tooltip(20, true).getString());
    }
}
