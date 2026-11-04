package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.network.packet.WorkshopAiLibraryC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiRequestC2SPacket;
import com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Sketch in hand, using the source footprint and transform supplied by the server. */
public final class WorkshopSketchPlacement {
    /** Reach of the crosshair anchor; without a hit the ghost floats a fixed distance ahead. */
    static final double REACH = 64;

    static final double FALLBACK_AHEAD = 8;
    static final int MAX_HEIGHT = WorkshopAiLibraryC2SPacket.MAX_SIDE;

    record Hand(
            UUID sketchId,
            UUID jobId,
            String name,
            WorkshopBlueprint blueprint,
            int sx,
            int sy,
            int sz,
            int rotY,
            boolean mirror,
            int dy) {
        Hand rotated() {
            return new Hand(
                    sketchId, jobId, name, blueprint, sx, sy, sz, (rotY + 1) & 3, mirror, dy);
        }

        Hand heightened(int steps) {
            return new Hand(
                    sketchId,
                    jobId,
                    name,
                    blueprint,
                    sx,
                    sy,
                    sz,
                    rotY,
                    mirror,
                    (int) Math.clamp((long) dy + steps, -MAX_HEIGHT, MAX_HEIGHT));
        }
    }

    enum Phase {
        HELD,
        AWAITING,
        FIXED
    }

    private static Phase phase = Phase.HELD;
    private static UUID pickupJob;
    private static UUID beforePlace;
    private static UUID placedJob;
    private static boolean sawPlanning;
    private static boolean awaitingBuild;
    private static boolean recalled;
    private static boolean discarding;
    private static UUID discardedJob;
    private static Hand hand;
    private static BlockPos anchor;
    private static Object lastLevel;

    private static com.lunares.blockfield.network.packet.WorkshopAiPreviewS2CPacket chainSource;
    private static UUID chainBefore;
    private static UUID chainJob;
    private static long chainUntil;

    static void rememberPreview(
            com.lunares.blockfield.network.packet.WorkshopAiPreviewS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null
                && packet.ownerId().equals(mc.player.getUUID())
                && packet.jobId().equals(WorkshopAiClient.state().jobId())) chainSource = packet;
    }

    static boolean chainReply(WorkshopAiStateS2CPacket packet) {
        if (chainBefore == null || System.nanoTime() > chainUntil) return false;
        if (packet.jobId().equals(chainBefore)) return false;
        if (chainJob == null && packet.stage().equals("planning")) chainJob = packet.jobId();
        return packet.jobId().equals(chainJob);
    }

    static boolean canExtend() {
        var state = WorkshopAiClient.state();
        return !WorkshopToolsClient.selectionMode()
                && chainSource != null
                && chainSource.jobId().equals(state.jobId())
                && (state.stage().equals("preview") || state.stage().equals("complete"));
    }

    static boolean extend(Vec3 look) {
        var state = WorkshopAiClient.state();
        if (!canExtend()) return false;
        var source = chainSource;
        BlockPos next =
                com.lunares.blockfield.workshop.WorkshopChainPlacement.next(
                        source.origin(), source.sx(), source.sz(), source.rotY(), look.x, look.z);
        if (!WorkshopAiClient.extendSketch(source.jobId(), next, source.rotY(), source.mirror()))
            return false;
        chainBefore = source.jobId();
        chainJob = null;
        chainUntil = System.nanoTime() + 30_000_000_000L;
        hand = null;
        anchor = null;
        return true;
    }

    private WorkshopSketchPlacement() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(WorkshopSketchPlacement::render);
        ClientTickEvents.END_CLIENT_TICK.register(WorkshopSketchPlacement::tick);
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    clear();
                    chainSource = null;
                    chainBefore = chainJob = null;
                });
    }

    public static void clear() {
        hand = null;
        anchor = null;
        phase = Phase.HELD;
        pickupJob = beforePlace = placedJob = null;
        sawPlanning = false;
        awaitingBuild = false;
        recalled = discarding = false;
    }

    public static boolean active() {
        return hand != null && !discarding;
    }

    static Phase phase() {
        return phase;
    }

    static boolean held() {
        return hand != null && !discarding && phase == Phase.HELD;
    }

    static Hand hand() {
        return hand;
    }

    static BlockPos anchor() {
        return anchor;
    }

    /** Footprint after rotation; rotation only permutes the horizontal axes. */
    static int[] rotatedSides(int sx, int sy, int sz, int rotY) {
        if (((rotY & 3) & 1) == 1) return new int[] {sz, sy, sx};
        return new int[] {sx, sy, sz};
    }

    /** Same integer transform the server applies (mirror on X first, then clockwise turns). */
    static int[] mapCell(int x, int y, int z, int sx, int sz, int rotY, boolean mirror) {
        int cx = mirror ? sx - 1 - x : x;
        int cz = z;
        int csx = sx;
        int csz = sz;
        for (int i = 0, turns = rotY & 3; i < turns; i++) {
            int nx = csz - 1 - cz;
            cz = cx;
            cx = nx;
            int swapped = csx;
            csx = csz;
            csz = swapped;
        }
        return new int[] {cx, y, cz};
    }

    /**
     * Bottom-centre of the footprint on the hit face: even sides bias toward the minimum corner.
     */
    static BlockPos anchorOnFace(BlockPos hit, Direction face, int rx, int rz, int dy) {
        BlockPos cell = hit.relative(face);
        return cell.offset(-rx / 2, dy, -rz / 2);
    }

    static BlockPos fallbackAhead(Vec3 eye, Vec3 look, double dist, int rx, int rz, int dy) {
        BlockPos cell = BlockPos.containing(eye.add(look.scale(dist)));
        return cell.offset(-rx / 2, dy, -rz / 2);
    }

    /** Rotated ghost geometry at the anchor, reusing the preview ghost renderer shapes. */
    static View view(Hand held, BlockPos at) {
        var cells = new ArrayList<WorkshopBlueprint.Cell>(held.blueprint.ghosts().size());
        for (var cell : held.blueprint.ghosts()) {
            int[] p =
                    mapCell(cell.x(), cell.y(), cell.z(), held.sx, held.sz, held.rotY, held.mirror);
            cells.add(new WorkshopBlueprint.Cell(p[0], p[1], p[2], cell.erase(), cell.water()));
        }
        var boxes = new ArrayList<WorkshopBlueprint.Box>(held.blueprint.boxes().size());
        for (var box : held.blueprint.boxes()) {
            int[] a =
                    mapCell(box.x0(), box.y0(), box.z0(), held.sx, held.sz, held.rotY, held.mirror);
            int[] b =
                    mapCell(
                            box.x1() - 1,
                            box.y1() - 1,
                            box.z1() - 1,
                            held.sx,
                            held.sz,
                            held.rotY,
                            held.mirror);
            boxes.add(
                    new WorkshopBlueprint.Box(
                            Math.min(a[0], b[0]),
                            Math.min(a[1], b[1]),
                            Math.min(a[2], b[2]),
                            Math.max(a[0], b[0]) + 1,
                            Math.max(a[1], b[1]) + 1,
                            Math.max(a[2], b[2]) + 1,
                            box.erase()));
        }
        return new View(at, List.copyOf(cells), List.copyOf(boxes));
    }

    record View(
            BlockPos origin,
            List<WorkshopBlueprint.Cell> cells,
            List<WorkshopBlueprint.Box> boxes) {}

    static void accept(WorkshopAiSketchPlanS2CPacket packet) {
        // A late source reply must not recreate a hand dropped during recall.
        if (packet.sketchId().equals(discardedJob)) return;
        if (packet.sketchId().equals(pickupJob)
                && (!WorkshopAiClient.state().jobId().equals(pickupJob)
                        || !WorkshopAiClient.state().stage().equals("preview")
                                && !WorkshopAiClient.state().stage().equals("held"))) {
            pickupJob = null;
            return;
        }
        try {
            WorkshopBlueprint blueprint =
                    WorkshopBlueprint.parse(
                            packet.plan(),
                            packet.sx() / 2,
                            packet.sy() / 2,
                            packet.sz() / 2,
                            packet.sx(),
                            packet.sy(),
                            packet.sz());
            if (hand != null
                    && pickupJob == null
                    && (packet.sketchId().equals(placedJob)
                            || packet.sketchId().equals(hand.jobId))) {
                hand =
                        new Hand(
                                WorkshopAiRequestC2SPacket.ZERO_ID,
                                packet.sketchId(),
                                packet.name(),
                                blueprint,
                                packet.sx(),
                                packet.sy(),
                                packet.sz(),
                                hand.rotY,
                                hand.mirror,
                                hand.dy);
                return;
            }
            boolean live =
                    packet.sketchId().equals(pickupJob)
                            || packet.sketchId().equals(WorkshopAiClient.state().jobId())
                                    && WorkshopAiClient.state().stage().equals("held");
            hand =
                    new Hand(
                            live ? WorkshopAiRequestC2SPacket.ZERO_ID : packet.sketchId(),
                            live ? packet.sketchId() : WorkshopAiRequestC2SPacket.ZERO_ID,
                            packet.name(),
                            blueprint,
                            packet.sx(),
                            packet.sy(),
                            packet.sz(),
                            packet.rotY(),
                            packet.mirror(),
                            0);
            phase = Phase.HELD;
            recalled = awaitingBuild = false;
            beforePlace = placedJob = pickupJob = null;
            anchor = null;
        } catch (RuntimeException invalid) {
            clear();
        }
    }

    /** Fetch source sides and plan; occupied extents cannot define the rotation pivot. */
    static boolean pickupPreview(UUID ownerId, UUID jobId) {
        if (!WorkshopAiClient.state().ownerId().equals(ownerId)) return false;
        if (!WorkshopAiClient.takePreview(jobId)) return false;
        pickupJob = jobId;
        return true;
    }

    static void takingLibrary() {
        pickupJob = null;
    }

    static void rotate() {
        if (held()) hand = hand.rotated();
    }

    static int rotation() {
        return hand == null ? 0 : hand.rotY();
    }

    /** Height offset of the hand ghost; ±{@link #MAX_HEIGHT} is the limit the chip warns about. */
    static int height() {
        return hand == null ? 0 : hand.dy();
    }

    static void nudgeHeight(int steps) {
        if (!held() || steps == 0) return;
        int before = hand.dy();
        hand = hand.heightened(steps);
        if (hand.dy() == before) WorkshopAiChip.heightLimit(net.minecraft.Util.getMillis());
    }

    /** A successful send waits for server acknowledgement before offering Build. */
    static boolean place() {
        if (!held() || anchor == null || recalled) return false;
        UUID previous = WorkshopAiClient.state().jobId();
        if (!WorkshopAiClient.placeSketch(
                hand.sketchId, hand.jobId, anchor, hand.rotY, hand.mirror)) return false;
        awaiting(previous);
        return true;
    }

    static void awaiting(UUID previous) {
        beforePlace = previous;
        placedJob = null;
        sawPlanning = false;
        awaitingBuild = false;
        phase = Phase.AWAITING;
    }

    /** Recall retains the local transform; a server acknowledgement attaches the pending job. */
    static boolean recall() {
        if (hand == null || phase != Phase.AWAITING || awaitingBuild) return false;
        phase = Phase.HELD;
        recalled = true;
        return true;
    }

    static boolean sendRecall() {
        if (hand == null || awaitingBuild) return false;
        if (!WorkshopAiClient.recallSketch(hand.sketchId, hand.jobId)) return false;
        return recall();
    }

    static boolean use() {
        return switch (phase) {
            case HELD -> place();
            case AWAITING -> true;
            case FIXED -> {
                boolean sent = WorkshopAiClient.request("apply", "");
                if (sent) {
                    awaiting(placedJob);
                    awaitingBuild = true;
                }
                yield sent;
            }
        };
    }

    static boolean placementReply(WorkshopAiStateS2CPacket packet) {
        if (hand == null || phase != Phase.AWAITING && !recalled) return false;
        return hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)
                ? !packet.jobId().equals(beforePlace)
                        && !packet.jobId().equals(WorkshopAiRequestC2SPacket.ZERO_ID)
                : packet.jobId().equals(hand.jobId);
    }

    static void acceptState(WorkshopAiStateS2CPacket packet) {
        if (hand == null) return;
        if (discarding && placementReply(packet)) {
            discardedJob = packet.jobId();
            WorkshopAiClient.cancelSketchJob(discardedJob);
            clear();
            return;
        }
        if (recalled && placementReply(packet)) {
            if (packet.stage().equals("planning")
                    || packet.stage().equals("preview")
                    || packet.stage().equals("held")) {
                placedJob = packet.jobId();
                hand =
                        new Hand(
                                WorkshopAiRequestC2SPacket.ZERO_ID,
                                packet.jobId(),
                                hand.name,
                                hand.blueprint,
                                hand.sx,
                                hand.sy,
                                hand.sz,
                                hand.rotY,
                                hand.mirror,
                                hand.dy);
                if (packet.stage().equals("held")) recalled = false;
                return;
            }
            clear();
            return;
        }
        if (phase == Phase.AWAITING) {
            if (awaitingBuild) {
                if (!packet.jobId().equals(beforePlace)) return;
                if (packet.stage().equals("awaiting") || packet.stage().equals("planning")) return;
                clear();
                return;
            }
            if (placementReply(packet)) {
                if (packet.stage().equals("planning")) {
                    placedJob = packet.jobId();
                    sawPlanning = true;
                } else if (packet.stage().equals("preview")
                        && (hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID) || sawPlanning)) {
                    placedJob = packet.jobId();
                    phase = Phase.FIXED;
                    anchor = null;
                } else {
                    // A preview reply without re-snapshot planning is a refused reposition.
                    phase = Phase.HELD;
                }
            } else if (!packet.stage().equals("planning")) {
                phase = Phase.HELD;
            }
        } else if (phase == Phase.FIXED
                && (!packet.jobId().equals(placedJob)
                        || !packet.stage().equals("preview")
                                && !packet.stage().equals("held")
                                && !packet.stage().equals("planning"))) {
            clear();
        } else if (phase == Phase.HELD
                && !hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)
                && (!packet.jobId().equals(hand.jobId)
                        || !packet.stage().equals("preview")
                                && !packet.stage().equals("held")
                                && !packet.stage().equals("planning"))) {
            clear();
        }
    }

    static void attack() {
        if (phase == Phase.AWAITING) {
            if (awaitingBuild && WorkshopAiClient.request("cancel", "")) {
                awaitingBuild = false;
                if (hand != null && hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)) {
                    phase = Phase.HELD;
                    beforePlace = placedJob = null;
                    anchor = null;
                } else clear();
            }
            return;
        }
        if (phase == Phase.HELD) {
            if (recalled && hand != null && hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)) {
                discarding = true;
                anchor = null;
                return;
            }
            if (hand != null && !hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)) {
                discardedJob = hand.jobId;
                WorkshopAiClient.cancelSketchJob(discardedJob);
            }
            clear();
            return;
        }
        if (!WorkshopAiClient.request("cancel", "")) return;
        if (hand != null && hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)) {
            phase = Phase.HELD;
            beforePlace = placedJob = null;
            anchor = null;
        } else clear();
    }

    private static void tick(Minecraft mc) {
        // The server forgets jobs silently on a scope change, so no state packet would ever
        // release an AWAITING hand: a level change drops the hand like a disconnect.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            chainSource = null;
            chainBefore = chainJob = null;
            clear();
        }
        if (!held()) return;
        if (mc.player == null
                || mc.level == null
                || mc.screen != null
                || !WorkshopToolsClient.holdsAi(mc)) {
            anchor = null;
            return;
        }
        if (!hand.jobId.equals(WorkshopAiRequestC2SPacket.ZERO_ID)) {
            var state = WorkshopAiClient.state();
            if (!state.jobId().equals(hand.jobId)
                    || !state.stage().equals("preview")
                            && !state.stage().equals("planning")
                            && !state.stage().equals("held")) {
                clear();
                return;
            }
        }
        int[] sides = rotatedSides(hand.sx, hand.sy, hand.sz, hand.rotY);
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            // Same ray the crosshair uses when it finds no block within reach.
            hit =
                    mc.level.clip(
                            new ClipContext(
                                    mc.player.getEyePosition(),
                                    mc.player
                                            .getEyePosition()
                                            .add(mc.player.getLookAngle().scale(REACH)),
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.NONE,
                                    mc.player));
        }
        anchor =
                anchorForHit(
                        hit,
                        mc.player.getEyePosition(),
                        mc.player.getLookAngle(),
                        sides[0],
                        sides[2],
                        hand.dy);
    }

    static BlockPos anchorForHit(HitResult hit, Vec3 eye, Vec3 look, int rx, int rz, int dy) {
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK)
            return anchorOnFace(block.getBlockPos(), block.getDirection(), rx, rz, dy);
        return fallbackAhead(eye, look, FALLBACK_AHEAD, rx, rz, dy);
    }

    private static void render(WorldRenderContext context) {
        if (!held() || anchor == null || context.consumers() == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !WorkshopToolsClient.holdsAi(mc) || mc.screen != null) return;
        Vec3 camera = context.camera().getPosition();
        View placed = view(hand, anchor);
        double ox = placed.origin().getX() - camera.x;
        double oy = placed.origin().getY() - camera.y;
        double oz = placed.origin().getZ() - camera.z;
        var fill =
                context.consumers()
                        .getBuffer(net.minecraft.client.renderer.RenderType.debugFilledBox());
        for (var cell : placed.cells()) {
            LevelRenderer.addChainedFilledBoxVertices(
                    context.matrixStack(),
                    fill,
                    ox + cell.x() + 0.03,
                    oy + cell.y() + 0.03,
                    oz + cell.z() + 0.03,
                    ox + cell.x() + 0.97,
                    oy + cell.y() + 0.97,
                    oz + cell.z() + 0.97,
                    cell.water() ? 0.15f : 1f,
                    cell.erase() ? 0.25f : cell.water() ? 0.55f : 0.78f,
                    cell.erase() ? 0.25f : cell.water() ? 1f : 0.38f,
                    0.16f);
        }
        var lines = context.consumers().getBuffer(net.minecraft.client.renderer.RenderType.lines());
        for (var box : placed.boxes()) {
            LevelRenderer.renderLineBox(
                    context.matrixStack(),
                    lines,
                    ox + box.x0(),
                    oy + box.y0(),
                    oz + box.z0(),
                    ox + box.x1(),
                    oy + box.y1(),
                    oz + box.z1(),
                    1f,
                    box.erase() ? 0.25f : 0.78f,
                    box.erase() ? 0.25f : 0.38f,
                    0.5f);
        }
    }
}
