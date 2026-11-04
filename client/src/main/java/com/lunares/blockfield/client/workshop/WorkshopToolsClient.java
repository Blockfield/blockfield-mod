package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.render.MinimapHud;
import com.lunares.blockfield.client.render.WeaponHud;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Use/attack remain ordinary Minecraft inputs; only deliberate text entry takes the cursor. */
public final class WorkshopToolsClient {
    public static final KeyMapping SKETCH_ROTATE_KEY = WorkshopControls.key("rotate");
    private static boolean selectionMode;
    private static Object selectionLevel;

    public static boolean selectionMode() {
        return selectionMode;
    }

    public static void toggleSelection() {
        Minecraft mc = Minecraft.getInstance();
        if (!WorkshopGhostClient.canBuild() || mc.level == null) return;
        selectionMode = !selectionMode;
        WorkshopWandSelection.cancelSelect();
    }

    private static boolean used;

    static final long WAND_FLUSH_MS = 175;
    // Vanilla charges 20 points/command and drains 20/s at 20 TPS: leave 10 ticks of headroom.
    static final long WAND_SEND_MS = 1500;
    static final int WAND_MAX_BATCHES = 128;
    static final int WAND_MAX_STEPS = 4096;

    private record WandBatch(int steps, String dir, long lastMs) {}

    private static final ArrayDeque<WandBatch> wandQueue = new ArrayDeque<>();
    private static int wandQueuedSteps;
    private static long wandNextMs;
    private static ClientLevel wheelLevel;
    private static final WheelSteps wheel = new WheelSteps();

    /** Gesture-local version of vanilla onScroll's sensitivity, sign reset and d2i remainder. */
    static final class WheelSteps {
        private String consumer;
        private double remainder;

        void select(String next) {
            if (!Objects.equals(consumer, next)) {
                consumer = next;
                remainder = 0;
            }
        }

        int steps(String next, double raw, boolean discrete, double sensitivity) {
            select(next);
            if (next == null) return 0;
            double delta = (discrete ? Math.signum(raw) : raw) * sensitivity;
            if (remainder != 0 && Math.signum(delta) != Math.signum(remainder)) remainder = 0;
            remainder += delta;
            int steps = (int) remainder;
            remainder -= steps;
            return steps;
        }
    }

    private WorkshopToolsClient() {}

    /**
     * Scroll-mixin entry: with a sketch in hand the wheel only adjusts its height and never reaches
     * the hotbar; with the wand held Shift+wheel resizes the WorldEdit selection instead; otherwise
     * the scroll passes through to the hotbar.
     */
    public static boolean nudgeGrabbed(double scrollY) {
        Minecraft mc = Minecraft.getInstance();
        updateWheelContext(mc);
        String consumer = wheelConsumer(mc);
        wheel.select(consumer);
        if (scrollY == 0 || consumer == null) return false;
        int steps =
                wheel.steps(
                        consumer,
                        scrollY,
                        mc.options.discreteMouseScroll().get(),
                        mc.options.mouseWheelSensitivity().get());
        if (consumer.equals("hand")) {
            if (steps != 0) WorkshopSketchPlacement.nudgeHeight(steps);
        } else {
            var look = mc.player.getLookAngle();
            accumulateWand(steps, expandDirection(look.x, look.y, look.z), Util.getMillis());
        }
        // Consume fractional callbacks too; vanilla must not accumulate them into hotbar input.
        return true;
    }

    private static String wheelConsumer(Minecraft mc) {
        if (mc.player == null || mc.screen != null || mc.getOverlay() != null) return null;
        if (WorkshopSketchPlacement.held() && holdsAi(mc)) return "hand";
        var look = mc.player.getLookAngle();
        if (wandConsumes(
                false, holdsWand(mc), mc.player.isShiftKeyDown(), WeaponHud.inWorkshop(), false, 1))
            return "wand:" + expandDirection(look.x, look.y, look.z);
        return null;
    }

    /**
     * Dominant look axis as a WorldEdit direction token. Explicit, snapshotted at scroll time: the
     * server-side {@code me} would resolve from the facing at command execution, after the
     * coalescing delay, so a turn mid-scroll could send the selection the wrong way.
     */
    static String expandDirection(double x, double y, double z) {
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (ay >= ax && ay >= az) return y >= 0 ? "up" : "down";
        if (ax >= az) return x >= 0 ? "east" : "west";
        return z >= 0 ? "south" : "north";
    }

    /**
     * One WorldEdit command for the accumulated notches (sign picks expand vs contract).
     * WorldEdit's {@code //contract N dir} moves the face opposite to {@code dir}
     * (CuboidRegion.contract), so the looked-at face is pulled back by contracting toward the
     * opposite direction.
     */
    static String wandCommand(int pending, String dir) {
        if (pending == 0 || dir == null) return null;
        return pending > 0
                ? "/expand " + pending + " " + dir
                : "/contract " + -pending + " " + opposite(dir);
    }

    private static String opposite(String dir) {
        return switch (dir) {
            case "up" -> "down";
            case "down" -> "up";
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    /**
     * Pure scroll-consume decision: wand+Shift in the Workshop takes the wheel; the sketch hand
     * wins and anything else (plain wheel, other items, open screen) keeps the hotbar.
     */
    static boolean wandConsumes(
            boolean handConsumes,
            boolean holdsWand,
            boolean shiftDown,
            boolean inWorkshop,
            boolean screenOpen,
            double scrollY) {
        return !handConsumes && holdsWand && shiftDown && inWorkshop && !screenOpen && scrollY != 0;
    }

    /** Direction/sign changes append a batch; only the tick sender may drain the FIFO. */
    static String accumulateWand(int steps, String dir, long now) {
        int amount = steps;
        if (amount == 0 || dir == null) return null;
        WandBatch tail = wandQueue.peekLast();
        boolean merge =
                tail != null
                        && dir.equals(tail.dir())
                        && Integer.signum(amount) == Integer.signum(tail.steps());
        // shortcut: reject newest input at the queue ceiling; revisit if real wheel use reaches it.
        if ((long) wandQueuedSteps + Math.abs((long) amount) > WAND_MAX_STEPS
                || !merge && wandQueue.size() >= WAND_MAX_BATCHES) return null;
        wandQueuedSteps += Math.abs(amount);
        if (merge) {
            wandQueue.removeLast();
            amount += tail.steps();
        }
        wandQueue.addLast(new WandBatch(amount, dir, now));
        return null;
    }

    /** Force only bypasses coalescing, never pacing; release/item-switch keep accepted input. */
    static String drainWand(long now, boolean force) {
        WandBatch head = wandQueue.peekFirst();
        if (head == null || now < wandNextMs) return null;
        if (!force && wandQueue.size() == 1 && now - head.lastMs() < WAND_FLUSH_MS) return null;
        wandQueue.removeFirst();
        wandQueuedSteps -= Math.abs(head.steps());
        wandNextMs = now + WAND_SEND_MS;
        return wandCommand(head.steps(), head.dir());
    }

    static void clearWand() {
        wandQueue.clear();
        wandQueuedSteps = 0;
    }

    static void resetWheelSession() {
        clearWand();
        used = false;
        selectionMode = false;
        selectionLevel = null;
        wandNextMs = 0;
        wheelLevel = null;
        wheel.select(null);
    }

    private static void updateWheelContext(Minecraft mc) {
        if (mc.level != wheelLevel) {
            clearWand();
            wheel.select(null);
            wheelLevel = mc.level;
        }
        if (mc.player == null
                || mc.screen != null
                || mc.getOverlay() != null
                || mc.getConnection() == null
                || !WeaponHud.inWorkshop()) {
            clearWand();
            wheel.select(null);
        }
    }

    private static void flushWandTick(Minecraft mc, long now) {
        updateWheelContext(mc);
        String consumer = wheelConsumer(mc);
        wheel.select(consumer);
        String command = drainWand(now, consumer == null || !consumer.startsWith("wand:"));
        if (command != null && mc.getConnection() != null) mc.getConnection().sendCommand(command);
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> resetWheelSession());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> resetWheelSession());
        WorkshopControls.register();
        ClientTickEvents.END_CLIENT_TICK.register(
                mc ->
                        releaseUse(
                                mc.player != null
                                        && mc.options.keyUse.isDown()
                                        && (holdsAi(mc) || holdsWand(mc))));
        ClientTickEvents.END_CLIENT_TICK.register(WorkshopToolsClient::tick);
        HudRenderCallback.EVENT.register((graphics, delta) -> render(graphics));
    }

    public static void releaseUse(boolean downWithTool) {
        if (!downWithTool) used = false;
    }

    public static boolean holdsWand(Minecraft mc) {
        return mc.player != null && WeaponHud.inWorkshop() && selectionMode;
    }

    /** Intercept before Fabric/WorldEdit and vanilla can send their own interaction packets. */
    public static boolean useWand(Minecraft mc) {
        return useWand(mc, WorkshopWandSelection.extendedBlock(mc));
    }

    public static boolean useWand(Minecraft mc, BlockPos clicked) {
        if (mc.player == null || mc.screen != null || !holdsWand(mc)) return false;
        if (used) return true;

        used = true;
        var step = WorkshopWandSelection.useStep(WorkshopWandSelection.wandPhase(), clicked, false);
        if (step.command() == null || mc.getConnection() == null) return true;
        mc.getConnection().sendCommand(step.command());
        if (step.phase() == WorkshopWandSelection.WandPhase.SELECTING) {
            WorkshopWandSelection.beginSelect(clicked);
            mc.player.displayClientMessage(wandText("wand_first"), true);
        } else {
            WorkshopWandSelection.finishSelect(clicked);
            int[] done = WorkshopWandSelection.pending();
            mc.player.displayClientMessage(
                    wandText("wand_done", done == null ? "?" : WorkshopWandSelection.dims(done)),
                    true);
        }
        return true;
    }

    /**
     * The wand never breaks blocks: attack while selecting cancels the gesture, attack while idle
     * clears an existing selection, and anything else is silently consumed.
     */
    public static boolean attackWand(Minecraft mc) {
        if (mc.player == null || mc.screen != null || !holdsWand(mc)) return false;
        boolean wasSelecting =
                WorkshopWandSelection.wandPhase() == WorkshopWandSelection.WandPhase.SELECTING;
        boolean hadBox = WorkshopWandSelection.box() != null;
        var step = WorkshopWandSelection.attackStep(WorkshopWandSelection.wandPhase(), hadBox);
        if (step.command() != null && mc.getConnection() != null)
            mc.getConnection().sendCommand(step.command());
        WorkshopWandSelection.cancelSelect();
        if (wasSelecting) mc.player.displayClientMessage(wandText("wand_cancelled"), true);
        else if (hadBox) mc.player.displayClientMessage(wandText("wand_cleared"), true);
        return true;
    }

    private static Component wandText(String key, Object... args) {
        return Component.translatable("blockfield.workshop.tools." + key, args);
    }

    public static boolean useSketch(Minecraft mc) {
        if (mc.screen != null || !holdsAi(mc)) return false;
        useAi(mc);
        return true;
    }

    private static void useAi(Minecraft mc) {
        if (used || mc.player == null || mc.screen != null) return;
        used = true;
        wheel.select(null);
        var state = WorkshopAiClient.state();
        switch (routeAiUse(
                WorkshopAiClient.available(),
                WorkshopSketchPlacement.active(),
                WorkshopSketchPlacement.phase(),
                mc.options.keyShift.isDown(),
                state.stage(),
                WorkshopAiEffects.hasPreview(state.ownerId(), state.jobId()),
                WorkshopAiClient.uploading())) {
            case UNAVAILABLE -> WorkshopAiChip.message(text("unavailable"), BfTheme.MUTED);
            case PICKUP_FIXED, PICKUP_PREVIEW -> pickupIntoHand(mc, state.ownerId(), state.jobId());
            case RECALL_AWAITING -> {
                if (!WorkshopSketchPlacement.sendRecall())
                    WorkshopAiChip.message(text("positioning"), BfTheme.AMBER);
            }
            case USE_ACTIVE -> {
                if (!WorkshopSketchPlacement.use())
                    WorkshopAiChip.message(text("positioning"), BfTheme.AMBER);
            }
            case PREVIEW_MISSING ->
                    WorkshopAiChip.message(
                            Component.translatable("blockfield.workshop.ai.preview_missing"),
                            BfTheme.WARN);
            case APPLY -> {
                if (!WorkshopAiClient.request("apply", ""))
                    WorkshopAiChip.message(text("positioning"), BfTheme.AMBER);
            }
            case OPEN_SCREEN -> {
                Tablet.requestClose(mc);
                mc.setScreen(new WorkshopAiScreen());
            }
            case BUSY -> {}
        }
    }

    /** Success needs no line: the chip switches to the hand state with its keys. */
    private static void pickupIntoHand(Minecraft mc, java.util.UUID ownerId, java.util.UUID jobId) {
        if (!WorkshopSketchPlacement.pickupPreview(ownerId, jobId))
            WorkshopAiChip.message(
                    Component.translatable("blockfield.workshop.ai.preview_missing"), BfTheme.WARN);
    }

    /** Where one RMB with the AI tool goes; Shift+RMB on a placed or placing sketch picks it up. */
    enum AiUse {
        UNAVAILABLE,
        PICKUP_FIXED,
        RECALL_AWAITING,
        USE_ACTIVE,
        PREVIEW_MISSING,
        PICKUP_PREVIEW,
        APPLY,
        OPEN_SCREEN,
        BUSY
    }

    /**
     * Pure input routing for the test above: the sneak branch wins over apply whenever a sketch is
     * placed (FIXED) or still placing (AWAITING); plain RMB on FIXED still builds.
     */
    static AiUse routeAiUse(
            boolean available,
            boolean handActive,
            WorkshopSketchPlacement.Phase phase,
            boolean sneak,
            String stage,
            boolean hasPreview,
            boolean uploading) {
        if (!available) return AiUse.UNAVAILABLE;
        if (handActive) {
            if (sneak && phase == WorkshopSketchPlacement.Phase.FIXED) return AiUse.PICKUP_FIXED;
            if (sneak && phase == WorkshopSketchPlacement.Phase.AWAITING)
                return AiUse.RECALL_AWAITING;
            return AiUse.USE_ACTIVE;
        }
        if (stage.equals("preview")) {
            if (!hasPreview) return AiUse.PREVIEW_MISSING;
            if (sneak) return AiUse.PICKUP_PREVIEW;
            return AiUse.APPLY;
        }
        if (!busy(stage) && !uploading) return AiUse.OPEN_SCREEN;
        return AiUse.BUSY;
    }

    private static void tick(Minecraft mc) {
        flushWandTick(mc, Util.getMillis());
        if (selectionLevel != mc.level) {
            selectionLevel = mc.level;
            selectionMode = false;
        }
        if (!WeaponHud.inWorkshop() || !WorkshopGhostClient.canBuild()) selectionMode = false;
        WorkshopControls.tick(mc);
    }

    static boolean busy(String stage) {
        return switch (stage) {
            case "transcribing", "planning", "awaiting", "applying", "undoing" -> true;
            default -> false;
        };
    }

    static String leftAction(String stage, boolean canUndo, boolean crouching) {
        if (busy(stage) || stage.equals("preview")) return "cancel";
        return canUndo && crouching ? "undo" : null;
    }

    /**
     * Which contextual controls are valid now; the HUD panel shows exactly these. A sketch in hand
     * wins over every job stage; otherwise the job stage decides.
     */
    static String panelKind(String stage, boolean hand, boolean canUndo) {
        if (hand)
            return switch (WorkshopSketchPlacement.phase()) {
                case HELD -> "hand";
                case AWAITING -> "awaiting";
                case FIXED -> "fixed";
            };
        return switch (stage) {
            case "transcribing", "planning", "awaiting" -> "working";
            case "preview" -> "preview";
            case "applying", "undoing" -> "progress";
            case "complete", "cancelled", "error" -> canUndo ? "done" : "idle";
            default -> "idle";
        };
    }

    public static boolean holdsAi(Minecraft mc) {
        return mc.player != null
                && WeaponHud.inWorkshop()
                && !holdsWand(mc)
                && (WorkshopSketchPlacement.active()
                        || WorkshopAiClient.state().stage().equals("preview"));
    }

    public static boolean attack(Minecraft mc) {
        if (!holdsAi(mc) || mc.screen != null) return false;
        if (WorkshopSketchPlacement.active()) {
            wheel.select(null);
            WorkshopSketchPlacement.attack();
            return true;
        }
        var state = WorkshopAiClient.state();
        String action = leftAction(state.stage(), state.canUndo(), mc.player.isShiftKeyDown());
        if (action != null) WorkshopAiClient.request(action, "");
        return true;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("blockfield.workshop.tools." + key, args);
    }

    /**
     * Top-centre HUD: the AI chip while a job, sketch or result is worth showing, otherwise the
     * contextual key lines (wand, menu, buffer) with live key names, clear of hotbar/chat/minimap.
     */
    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !WeaponHud.inWorkshop()) return;
        boolean chat = mc.screen instanceof ChatScreen;
        boolean screen = mc.screen != null;
        var chip = WorkshopAiChip.current(mc, System.nanoTime());
        // A running job keeps its chip over the chat (S5/D14); other screens and F1 still hide
        // it, and the idle key lines never show over any screen. The chat/input reservation
        // steers the chip to the top, so it never covers the input line.
        if (chip != null && chipVisible(screen, chat, mc.options.hideGui, true)) {
            WorkshopAiChip.render(graphics, mc, chip, MinimapHud.rect(), WorkshopChatArea.rect());
            return;
        }
        if (!panelVisible(screen, false)) return;
        var state = WorkshopAiClient.state();
        List<Component> lines = new ArrayList<>();
        if (holdsWand(mc)) {
            lines.add(
                    Component.translatable(
                            "blockfield.workshop.radial.selection_active",
                            WorkshopControls.key("selection").getTranslatedKeyMessage()));
            lines.addAll(WorkshopWandSelection.hints(mc));
        }
        if (WorkshopGhostClient.canBuild())
            lines.add(
                    Component.translatable(
                            "blockfield.workshop.radial.keys",
                            WorkshopControls.key("radial").getTranslatedKeyMessage(),
                            WorkshopControls.key("ai").getTranslatedKeyMessage(),
                            WorkshopControls.key("selection").getTranslatedKeyMessage()));
        else
            lines.add(
                    Component.translatable(
                            "blockfield.workshop.radial.menu_key",
                            WorkshopControls.key("radial").getTranslatedKeyMessage()));
        if (WorkshopGhostClient.canBuild() && !WorkshopToolsClient.busy(state.stage()))
            lines.add(
                    Component.translatable(
                            "blockfield.workshop.radial.buffer_keys",
                            WorkshopControls.key("copy").getTranslatedKeyMessage(),
                            WorkshopControls.key("paste").getTranslatedKeyMessage(),
                            WorkshopControls.key("undo").getTranslatedKeyMessage(),
                            WorkshopControls.key("redo").getTranslatedKeyMessage()));
        if (WorkshopToolsClient.selectionMode() && WorkshopAiClient.canFill())
            lines.add(
                    Component.translatable(
                            "blockfield.workshop.radial.extend_key",
                            WorkshopControls.key("extend").getTranslatedKeyMessage()));
        panel(graphics, mc, lines);
    }

    /** Draws the top-centre controls panel and returns its {x, y, width, height}. */
    private static int[] panel(GuiGraphics graphics, Minecraft mc, List<Component> lines) {
        var font = BfTheme.font();
        int textW = 0;
        for (Component line : lines) textW = Math.max(textW, font.width(line.getString()));
        int[] at =
                placePanel(
                        mc.getWindow().getGuiScaledWidth(),
                        mc.getWindow().getGuiScaledHeight(),
                        textW + 12,
                        5 + lines.size() * 10,
                        MinimapHud.rect(),
                        WorkshopChatArea.rect());
        if (at == null) return null;
        graphics.fill(at[0], at[1], at[0] + at[2], at[1] + at[3], BfTheme.alpha(BfTheme.BG, 0xB0));
        for (int i = 0; i < lines.size(); i++)
            graphics.drawString(
                    font,
                    BfTheme.ellipsize(font, lines.get(i).getString(), at[2] - 12),
                    at[0] + 6,
                    at[1] + 3 + i * 10,
                    BfTheme.TEXT,
                    false);
        return at;
    }

    /** Finds a clear top-first slot; hides rather than cover chat, minimap or hotbar. */
    static int[] placePanel(
            int screenW, int screenH, int width, int height, int[] minimap, int[] chat) {
        int w = Math.min(width, Math.max(0, screenW - 16));
        if (w < 12 || height > screenH - 8) return null;
        int[] hotbar = hotbarRect(screenW, screenH);
        int[] xs = {(screenW - w) / 2, 8, screenW - w - 8};
        for (int y = 4; y + height <= screenH - 4; y++)
            for (int x : xs) {
                if (minimap != null && intersects(x, y, w, height, minimap)
                        || chat != null && intersects(x, y, w, height, chat)
                        || intersects(x, y, w, height, hotbar)) continue;
                return new int[] {x, y, w, height};
            }
        return null;
    }

    static boolean intersects(int x, int y, int w, int h, int[] r) {
        return x < r[0] + r[2] && r[0] < x + w && y < r[1] + r[3] && r[1] < y + h;
    }

    /** Vanilla hotbar chrome the panel must stay clear of: 182×22 centred at the bottom. */
    static int[] hotbarRect(int screenW, int screenH) {
        return new int[] {screenW / 2 - 91, screenH - 22, 182, 22};
    }

    /** The panel hides with any screen: an open chat (ChatScreen) takes the whole HUD with it. */
    static boolean panelVisible(boolean screenOpen, boolean hideGui) {
        return !screenOpen && !hideGui;
    }

    /**
     * The AI chip stays over the chat while it has something to show (S5/D14: no job without
     * visible UI). Inventory, menus and every other full screen still hide it, and F1 ({@code
     * hideGui}) always wins. The non-workshop HUD keeps its vanilla behaviour and may stay hidden;
     * only this chip is exempt.
     */
    static boolean chipVisible(
            boolean screenOpen, boolean chatOpen, boolean hideGui, boolean active) {
        if (hideGui || !active) return false;
        return !screenOpen || chatOpen;
    }
}
