package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.network.packet.WorkshopAiAudioC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiFillC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiLibraryC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiLibraryS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiPlanChunkS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiPreviewS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiProgressS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiRequestC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiTransformC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiV2S2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiV3S2CPacket;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Server-owned job state stays available when the player closes the editing screen. */
public final class WorkshopAiClient {
    private static boolean available;
    private static String draft = "";
    private static String generated = "";
    private static WorkshopAiStateS2CPacket state = empty();
    private static UUID lastNoticeId;
    private static String lastNoticeMessage = "";
    private static final Set<UUID> stampIds = new LinkedHashSet<>();
    private static byte[] upload;
    private static UUID uploadId;
    private static UUID transcriptionId;
    private static int uploadIndex;
    private static int uploadChunks;

    /** Latest server-sent sketch summaries; empty until the AI screen asks for them. */
    private static List<WorkshopAiLibraryS2CPacket.Entry> library = List.of();

    private static final Set<UUID> cancelledTranscriptions = new LinkedHashSet<>();
    // Give the server debounce time to expire before sending another non-cancel action.
    static final long SPACING_NS = 250_000_000L;
    private static long lastRequest = Long.MIN_VALUE;

    private WorkshopAiClient() {}

    private static WorkshopAiStateS2CPacket empty() {
        return new WorkshopAiStateS2CPacket(
                WorkshopAiRequestC2SPacket.ZERO_ID,
                WorkshopAiRequestC2SPacket.ZERO_ID,
                "disabled",
                "",
                "",
                BlockPos.ZERO,
                BlockPos.ZERO,
                0,
                0,
                false,
                false);
    }

    private static final WorkshopAiPlanTransfer transfers = new WorkshopAiPlanTransfer();

    private static WorkshopAiProgressState progress;

    /** Unit 8 renders this immutable snapshot; null means the server has no progress capability. */
    public static WorkshopAiProgressState progress() {
        return progress;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiProgressS2CPacket.TYPE,
                (packet, context) -> {
                    if (context.client().player != null
                            && context.client().player.getUUID().equals(packet.ownerId()))
                        progress = WorkshopAiProgressState.decode(packet, System.nanoTime());
                });
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiPlanChunkS2CPacket.TYPE,
                (packet, context) -> {
                    var complete = transfers.accept(packet, System.currentTimeMillis());
                    if (complete == null) return;
                    var h = complete.header();
                    if (h.hand()) {
                        if (context.client().player == null
                                || !context.client().player.getUUID().equals(h.ownerId())) return;
                        WorkshopSketchPlacement.accept(
                                new WorkshopAiSketchPlanS2CPacket(
                                        h.jobId(),
                                        h.name(),
                                        h.sx(),
                                        h.sy(),
                                        h.sz(),
                                        complete.plan(),
                                        h.rotY(),
                                        h.mirror()));
                    } else
                        WorkshopAiEffects.acceptPreview(
                                new WorkshopAiPreviewS2CPacket(
                                        h.ownerId(),
                                        h.jobId(),
                                        h.origin(),
                                        complete.plan(),
                                        h.sx(),
                                        h.sy(),
                                        h.sz(),
                                        h.rotY(),
                                        h.mirror()));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiStateS2CPacket.TYPE, (packet, context) -> accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiPreviewS2CPacket.TYPE,
                (packet, context) -> WorkshopAiEffects.acceptPreview(packet));
        // Capability advertisement only: the payload is never sent, but registering tells
        // the server this client parses v2 plans (old clients keep v1 generation).
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiV2S2CPacket.TYPE, (packet, context) -> {});
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiV3S2CPacket.TYPE, (packet, context) -> {});
        // Same pattern for the sketch library: registering tells the server this client
        // renders the list and the hand ghost; old clients get neither.
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiLibraryS2CPacket.TYPE,
                (packet, context) -> library = List.copyOf(packet.entries()));
        ClientPlayNetworking.registerGlobalReceiver(
                WorkshopAiSketchPlanS2CPacket.TYPE,
                (packet, context) -> WorkshopSketchPlacement.accept(packet));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> uploadTick());
        WorkshopAiEffects.register();
        WorkshopSketchPlacement.register();
    }

    private static void accept(WorkshopAiStateS2CPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        WorkshopAiEffects.accept(packet);
        var previous = state;
        accept(
                packet,
                client.player.getUUID(),
                message -> WorkshopAiChip.message(Component.literal(message), BfTheme.MUTED));
        if (state == packet) WorkshopAiChip.transition(previous, packet);
    }

    static void accept(WorkshopAiStateS2CPacket packet, UUID owner, Consumer<String> notice) {
        if (!packet.ownerId().equals(owner)) return;
        if (cancelledTranscriptions.contains(packet.jobId()) && !packet.prompt().isBlank()) return;
        // Stamp packets must keep the base id for actions, but their feedback still reaches HUD.
        if (!WorkshopToolsClient.busy(packet.stage())
                && !packet.message().isBlank()
                && (!packet.jobId().equals(lastNoticeId)
                        || !packet.message().equals(lastNoticeMessage))) {
            notice.accept(packet.message());
            lastNoticeId = packet.jobId();
            lastNoticeMessage = packet.message();
        }
        boolean placement =
                WorkshopSketchPlacement.placementReply(packet)
                        || WorkshopSketchPlacement.chainReply(packet);
        if (!placement && isStampProgress(state, packet)) {
            stampIds.add(packet.jobId());
            return;
        }
        if (!placement && stampIds.contains(packet.jobId())) return;
        if (!packet.jobId().equals(state.jobId())
                && !packet.jobId().equals(WorkshopAiRequestC2SPacket.ZERO_ID)) stampIds.clear();
        WorkshopAiEffects.ownerState(packet, owner);
        WorkshopSketchPlacement.acceptState(packet);
        state = packet;
        if (packet.jobId().equals(transcriptionId) && !packet.stage().equals("transcribing"))
            transcriptionId = null;
        if (packet.stage().equals("disabled")) available = false;
        if (packet.stage().equals("idle") && !packet.prompt().isBlank()) draft = packet.prompt();
    }

    /** Stamps never take over the active base job, even on a non-empty terminal reply. */
    static boolean isStampProgress(
            WorkshopAiStateS2CPacket current, WorkshopAiStateS2CPacket next) {
        if (next.jobId().equals(current.jobId())
                || next.jobId().equals(WorkshopAiRequestC2SPacket.ZERO_ID)) return false;
        return current.stage().equals("preview") || current.stage().equals("planning");
    }

    public static void availability(boolean enabled) {
        available = enabled;
        if (!enabled) {
            state = empty();
            lastNoticeId = null;
            lastNoticeMessage = "";
            stampIds.clear();
            discardUpload();
            transcriptionId = null;
            library = List.of();
            WorkshopSketchPlacement.clear();
            WorkshopAiEffects.clear();
            WorkshopVoiceCapture.discard();
        }
    }

    public static boolean available() {
        return available && ClientPlayNetworking.canSend(WorkshopAiRequestC2SPacket.TYPE);
    }

    public static WorkshopAiStateS2CPacket state() {
        return state;
    }

    public static String draft() {
        return draft;
    }

    /**
     * Smart-fill hint: the AI panel draft unless it is the prompt already sent for generation, so a
     * leftover description never turns a free «Продлить» into a paid extend.
     */
    static String fillHint() {
        return fillHint(draft, generated);
    }

    static String fillHint(String draft, String generated) {
        String hint = draft.strip();
        return hint.equals(generated) ? "" : hint;
    }

    static boolean fillSupported() {
        return ClientPlayNetworking.canSend(WorkshopAiFillC2SPacket.TYPE);
    }

    /** The server registers the fill channel; no task may be running. */
    static boolean canFill() {
        return fillSupported() && !WorkshopToolsClient.busy(state.stage());
    }

    /** «Продлить»: smart fill of the current WorldEdit selection, free unless the hint needs AI. */
    static boolean fill(String hint) {
        if (!canFill() || throttled()) return false;
        lastRequest = System.nanoTime();
        ClientPlayNetworking.send(new WorkshopAiFillC2SPacket(hint));
        // The WorldEdit selection stays; leaving the mode frees RMB to build the coming preview.
        if (WorkshopToolsClient.selectionMode()) WorkshopToolsClient.toggleSelection();
        return true;
    }

    /**
     * The prompt last sent for generation; the error state offers «Повторить» while it is
     * unchanged.
     */
    static String generated() {
        return generated;
    }

    public static void draft(String value) {
        draft = value;
    }

    /** True while the server would ignore the next non-cancel action. */
    public static boolean throttled() {
        return throttled(lastRequest, System.nanoTime());
    }

    static boolean throttled(long last, long now) {
        return last != Long.MIN_VALUE && now - last < SPACING_NS;
    }

    /** False when nothing was sent, so callers do not wait for a reply that never comes. */
    public static boolean request(String action, String prompt) {
        if (!available()) return false;
        if (!action.equals("cancel")) {
            if (throttled()) return false;
            lastRequest = System.nanoTime();
        }
        UUID id =
                action.equals("cancel") && transcriptionId != null
                        ? transcriptionId
                        : state.jobId();
        if (action.equals("cancel") && transcriptionId != null) {
            if (cancelledTranscriptions.size() >= 8)
                cancelledTranscriptions.remove(cancelledTranscriptions.iterator().next());
            cancelledTranscriptions.add(transcriptionId);
        }
        ClientPlayNetworking.send(
                new WorkshopAiRequestC2SPacket(
                        action,
                        id,
                        prompt,
                        action.equals("generate") && WorkshopAiSpeedChoice.fast()));
        if (action.equals("generate")) generated = prompt.strip();
        if (action.equals("cancel")) {
            discardUpload();
            transcriptionId = null;
        }
        return true;
    }

    /**
     * Reposition/stamp send sharing the 250 ms action spacing with requests: the server debounces
     * both channels together, so an apply sent sooner would be dropped unread.
     */
    public static boolean sendTransform(WorkshopAiTransformC2SPacket packet) {
        if (!available() || !ClientPlayNetworking.canSend(WorkshopAiTransformC2SPacket.TYPE))
            return false;
        if (throttled()) return false;
        lastRequest = System.nanoTime();
        ClientPlayNetworking.send(packet);
        return true;
    }

    public static boolean uploading() {
        return upload != null;
    }

    public static boolean transcribe(byte[] wav) {
        if (!available()
                || uploading()
                || wav == null
                || wav.length > WorkshopAiAudioC2SPacket.MAX_AUDIO_BYTES
                || !ClientPlayNetworking.canSend(WorkshopAiAudioC2SPacket.TYPE)) return false;
        upload = wav;
        uploadId = UUID.randomUUID();
        transcriptionId = uploadId;
        uploadIndex = 0;
        uploadChunks =
                (wav.length + WorkshopAiAudioC2SPacket.MAX_CHUNK_BYTES - 1)
                        / WorkshopAiAudioC2SPacket.MAX_CHUNK_BYTES;
        return true;
    }

    private static void uploadTick() {
        if (!uploading()) return;
        if (!available() || !ClientPlayNetworking.canSend(WorkshopAiAudioC2SPacket.TYPE)) {
            discardUpload();
            return;
        }
        // Two packets per tick keep bounded voice uploads from flooding one client frame.
        for (int sent = 0; sent < 2 && uploading(); sent++) {
            int from = uploadIndex * WorkshopAiAudioC2SPacket.MAX_CHUNK_BYTES;
            int to = Math.min(upload.length, from + WorkshopAiAudioC2SPacket.MAX_CHUNK_BYTES);
            ClientPlayNetworking.send(
                    new WorkshopAiAudioC2SPacket(
                            uploadId,
                            uploadIndex,
                            uploadChunks,
                            Arrays.copyOfRange(upload, from, to)));
            if (++uploadIndex == uploadChunks) discardUpload();
        }
    }

    public static void discardUpload() {
        if (upload != null) Arrays.fill(upload, (byte) 0);
        upload = null;
        uploadId = null;
        uploadIndex = uploadChunks = 0;
    }

    public static void cancelTranscription() {
        if (transcriptionId != null) request("cancel", "");
        discardUpload();
    }

    private static void reset() {
        progress = null;
        WorkshopAiChip.clear();
        transfers.clear();
        availability(false);
        WorkshopVoiceCapture.closeScreen();
        WorkshopAiEffects.clear();
        cancelledTranscriptions.clear();
        draft = "";
        generated = "";
    }

    public static List<WorkshopAiLibraryS2CPacket.Entry> library() {
        return library;
    }

    static boolean librarySupported() {
        return ClientPlayNetworking.canSend(WorkshopAiLibraryC2SPacket.TYPE);
    }

    private static boolean sendLibrary(
            String action, UUID sketchId, UUID jobId, String prompt, int sx, int sy, int sz) {
        if (!available() || !librarySupported() || throttled()) return false;
        lastRequest = System.nanoTime();
        ClientPlayNetworking.send(
                new WorkshopAiLibraryC2SPacket(
                        action,
                        sketchId,
                        jobId,
                        prompt,
                        sx,
                        sy,
                        sz,
                        BlockPos.ZERO,
                        0,
                        false,
                        action.equals("generate") && WorkshopAiSpeedChoice.fast()));
        return true;
    }

    /** Asks the server to resend the list; the AI screen calls this on open. */
    public static void refreshLibrary() {
        sendLibrary(
                "list",
                WorkshopAiRequestC2SPacket.ZERO_ID,
                WorkshopAiRequestC2SPacket.ZERO_ID,
                "",
                0,
                0,
                0);
    }

    /** Starts a model generation straight into the library; 0 sides take the selection size. */
    public static boolean generateLibrary(String prompt, int sx, int sy, int sz) {
        generated = prompt.strip();
        return sendLibrary(
                "generate",
                WorkshopAiRequestC2SPacket.ZERO_ID,
                WorkshopAiRequestC2SPacket.ZERO_ID,
                prompt,
                sx,
                sy,
                sz);
    }

    public static boolean takeSketch(UUID sketchId) {
        if (!sendLibrary("take", sketchId, WorkshopAiRequestC2SPacket.ZERO_ID, "", 0, 0, 0))
            return false;
        WorkshopSketchPlacement.takingLibrary();
        return true;
    }

    static boolean takePreview(UUID jobId) {
        return sendLibrary("take", WorkshopAiRequestC2SPacket.ZERO_ID, jobId, "", 0, 0, 0);
    }

    static boolean cancelSketchJob(UUID jobId) {
        if (!ClientPlayNetworking.canSend(WorkshopAiRequestC2SPacket.TYPE)) return false;
        ClientPlayNetworking.send(new WorkshopAiRequestC2SPacket("cancel", jobId, ""));
        return true;
    }

    static boolean recallSketch(UUID sketchId, UUID jobId) {
        if (!available()
                || !librarySupported()
                || !ClientPlayNetworking.canSend(WorkshopAiV3S2CPacket.TYPE)) return false;
        ClientPlayNetworking.send(
                new WorkshopAiLibraryC2SPacket(
                        "recall", sketchId, jobId, "", 0, 0, 0, BlockPos.ZERO, 0, false));
        return true;
    }

    public static boolean deleteSketch(UUID sketchId) {
        return sendLibrary("delete", sketchId, WorkshopAiRequestC2SPacket.ZERO_ID, "", 0, 0, 0);
    }

    static boolean extendSketch(UUID job, BlockPos origin, int rotation, boolean mirror) {
        if (!available() || !librarySupported() || throttled()) return false;
        lastRequest = System.nanoTime();
        ClientPlayNetworking.send(
                new WorkshopAiLibraryC2SPacket(
                        "extend",
                        WorkshopAiRequestC2SPacket.ZERO_ID,
                        job,
                        "",
                        0,
                        0,
                        0,
                        origin,
                        rotation,
                        mirror));
        return true;
    }

    /** Fixes the hand ghost at the crosshair anchor: the server snapshots it into a preview. */
    public static boolean placeSketch(
            UUID sketchId, UUID jobId, BlockPos origin, int rotY, boolean mirror) {
        boolean recalledPlacement = jobId.equals(state.jobId()) && state.stage().equals("held");
        if (!available() || !librarySupported() || !recalledPlacement && throttled()) return false;
        lastRequest = System.nanoTime();
        ClientPlayNetworking.send(
                new WorkshopAiLibraryC2SPacket(
                        "place", sketchId, jobId, "", 0, 0, 0, origin, rotY, mirror));
        return true;
    }
}
