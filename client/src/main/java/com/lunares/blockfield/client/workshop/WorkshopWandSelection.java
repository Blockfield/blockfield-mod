package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.render.WeaponHud;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * WorldEdit wand selection outline, client-only: no server code, no new dependency.
 *
 * <p>The server runs WorldEdit (Fabric), which registers {@code worldedit:cui} both ways with a raw
 * UTF-8 payload and, after the {@code v|4} handshake, pushes {@code typeId|param|...} selection
 * events on every selection change. This class reuses WorldEdit's payload when installed, or
 * registers its own client codec otherwise, answers the handshake while a Workshop tool is held,
 * and draws the selection with the same line renderer the AI preview uses, so it stays visible with
 * Sodium and Iris shader packs (lines are the AI preview's shader fallback).
 */
public final class WorkshopWandSelection {
    static final String HANDSHAKE = "v|4";
    static final int MAX_TEXT = 2048;
    private static final int MAX_PARTS = 257;
    private static final int MAX_POLY_POINTS = 128;
    private static final int COORD_LIMIT = 30_000_000;
    private static final int HEIGHT_LIMIT = 2048;
    private static final double RENDER_DISTANCE_SQR = 128 * 128;

    // Amber/pink belong to the AI preview ghost; the wand selection is cyan with green/violet
    // corners.
    private static final float[] OUTLINE = {0.25f, 0.85f, 1.0f};
    private static final float[] FIRST = {0.4f, 1.0f, 0.4f};
    private static final float[] SECOND = {0.75f, 0.45f, 1.0f};
    private static final float ALPHA = 0.9f;
    // White band on the Shift+wheel face: amber/pink belong to the AI preview ghost.
    private static final float[] FACE = {1.0f, 1.0f, 1.0f};
    private static final float FACE_ALPHA = 0.75f;

    /** Raw UTF-8 bytes with no length prefix, mirroring WorldEdit 7.3.8 {@code CuiPacket}. */
    public record CuiPayload(String text) implements CustomPacketPayload {
        public static final Type<CuiPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("worldedit", "cui"));
        public static final StreamCodec<FriendlyByteBuf, CuiPayload> CODEC =
                CustomPacketPayload.codec(CuiPayload::write, CuiPayload::new);

        public CuiPayload {
            if (text == null || text.length() > MAX_TEXT)
                throw new IllegalArgumentException("Invalid WorldEdit CUI message");
        }

        private CuiPayload(FriendlyByteBuf buf) {
            this(readRaw(buf));
        }

        private static String readRaw(FriendlyByteBuf buf) {
            String text =
                    buf.readCharSequence(
                                    Math.min(buf.readableBytes(), MAX_TEXT), StandardCharsets.UTF_8)
                            .toString();
            buf.skipBytes(buf.readableBytes());
            return text;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeCharSequence(text, StandardCharsets.UTF_8);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static String shape = "cuboid";
    private static BlockPos pos0, pos1, ellipsoidCenter, ellipsoidRadii, cylCenter;
    private static double cylRx, cylRz;
    private static boolean hasCyl;
    private static Integer yMin, yMax;
    private static final List<BlockPos> poly = new ArrayList<>();
    private static ResourceKey<Level> dimension;
    private static boolean handshakeSent;
    private static WorkshopWorldEditCui worldEditCui;

    // Two-click wand gesture: RMB sets corner 1, RMB again fixes corner 2, LMB cancels.
    // WorldEdit stays the single source of truth: clicks only send its own commands (/pos1,
    // /pos2, /desel, same leading-slash style as the wheel's /expand), and the committed box
    // always comes back through CUI. The anchor/pending states below are optimistic display
    // only: the live box while selecting, and the finished box until CUI catches up.
    static final double CURSOR_REACH = 64.0;
    // ponytail: air fallback is a fixed 8-block preview point, not a selection; revisit if
    // builders want the fallback distance configurable.
    static final double CURSOR_FALLBACK_DIST = 8.0;
    static final int MAX_SIDE = 128;

    enum WandPhase {
        IDLE,
        SELECTING
    }

    record WandUse(WandPhase phase, String command) {}

    record WandAttack(WandPhase phase, String command) {}

    private static WandPhase phase = WandPhase.IDLE;
    private static BlockPos anchor;
    private static int[] pendingBox;

    private WorkshopWandSelection() {}

    public static void register() {
        if (registerOwnType(FabricLoader.getInstance().isModLoaded("worldedit"))) {
            PayloadTypeRegistry.playC2S().register(CuiPayload.TYPE, CuiPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(CuiPayload.TYPE, CuiPayload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(
                    CuiPayload.TYPE, (payload, context) -> accept(payload.text()));
        } else {
            // Fabric runs every main entrypoint before client entrypoints: WorldEdit owns the
            // codec.
            worldEditCui = new WorkshopWorldEditCui();
            ClientPlayNetworking.registerGlobalReceiver(
                    worldEditCui.type(), (payload, context) -> accept(worldEditCui.text(payload)));
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(WorkshopWandSelection::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(WorkshopWandSelection::render);
        HudRenderCallback.EVENT.register((graphics, delta) -> renderLabel(graphics));
    }

    static boolean registerOwnType(boolean worldEditLoaded) {
        return !worldEditLoaded;
    }

    static void clear() {
        shape = "cuboid";
        pos0 = pos1 = ellipsoidCenter = ellipsoidRadii = cylCenter = null;
        hasCyl = false;
        yMin = yMax = null;
        poly.clear();
        dimension = null;
        handshakeSent = false;
        phase = WandPhase.IDLE;
        anchor = null;
        pendingBox = null;
    }

    /** The box is shown in selection mode and while the AI sheet may use it as the volume. */
    static boolean showsSelection(Minecraft mc) {
        return WorkshopToolsClient.selectionMode()
                || WorkshopToolsClient.holdsAi(mc)
                || mc.screen instanceof WorkshopAiScreen;
    }

    static boolean visible(boolean inWorkshop, boolean holdsTool, boolean hasBox) {
        return inWorkshop && holdsTool && hasBox;
    }

    static WandPhase wandPhase() {
        return phase;
    }

    static int[] pending() {
        return pendingBox == null ? null : pendingBox.clone();
    }

    /**
     * Pure two-click transition: Shift+use keeps the clipboard path and air clicks do nothing, so
     * only a use on a block starts or finishes. Commands are explicit WorldEdit coordinates, shared
     * with the displayed corners.
     */
    static WandUse useStep(WandPhase current, BlockPos target, boolean shiftDown) {
        if (shiftDown || target == null) return new WandUse(current, null);
        return current == WandPhase.IDLE
                ? new WandUse(WandPhase.SELECTING, positionCommand("/pos1", target))
                : new WandUse(WandPhase.IDLE, positionCommand("/pos2", target));
    }

    private static String positionCommand(String command, BlockPos target) {
        return command + " " + target.getX() + "," + target.getY() + "," + target.getZ();
    }

    /**
     * Pure cancel/clear transition: attack while selecting cancels the gesture, attack while idle
     * clears an existing selection, and attack with nothing selected sends nothing (the wand still
     * never breaks blocks; the caller always consumes the click).
     */
    static WandAttack attackStep(WandPhase current, boolean hasSelection) {
        if (current == WandPhase.SELECTING || hasSelection)
            return new WandAttack(WandPhase.IDLE, "/desel");
        return new WandAttack(WandPhase.IDLE, null);
    }

    static void beginSelect(BlockPos clicked) {
        phase = WandPhase.SELECTING;
        anchor = clicked;
        pendingBox = null;
    }

    static void finishSelect(BlockPos cursor) {
        if (anchor != null && cursor != null) pendingBox = liveBox(anchor, cursor);
        phase = WandPhase.IDLE;
        anchor = null;
    }

    static void cancelSelect() {
        phase = WandPhase.IDLE;
        anchor = null;
        pendingBox = null;
    }

    static int[] liveBox(BlockPos first, BlockPos second) {
        if (first == null || second == null) return null;
        return bounds(
                first.getX(),
                first.getY(),
                first.getZ(),
                second.getX(),
                second.getY(),
                second.getZ());
    }

    static int[] dimensions(int[] box) {
        return new int[] {box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1};
    }

    static long blockCount(int[] box) {
        int[] size = dimensions(box);
        return (long) size[0] * size[1] * size[2];
    }

    static String dims(int[] box) {
        int[] size = dimensions(box);
        return size[0] + "×" + size[1] + "×" + size[2];
    }

    static boolean tooBig(int[] box) {
        int[] size = dimensions(box);
        return size[0] > MAX_SIDE || size[1] > MAX_SIDE || size[2] > MAX_SIDE;
    }

    /** Thin slab hugging the looked-at face, so the Shift+wheel target is obvious. */
    static double[] faceSlab(int[] box, String dir) {
        double x0 = box[0], y0 = box[1], z0 = box[2];
        double x1 = box[3] + 1, y1 = box[4] + 1, z1 = box[5] + 1;
        double pad = 0.06;
        return switch (dir) {
            case "up" -> new double[] {x0, y1 - pad, z0, x1, y1 + pad, z1};
            case "down" -> new double[] {x0, y0 - pad, z0, x1, y0 + pad, z1};
            case "east" -> new double[] {x1 - pad, y0, z0, x1 + pad, y1, z1};
            case "west" -> new double[] {x0 - pad, y0, z0, x0 + pad, y1, z1};
            case "south" -> new double[] {x0, y0, z1 - pad, x1, y1, z1 + pad};
            default -> new double[] {x0, y0, z0 - pad, x1, y1, z0 + pad};
        };
    }

    /** Display priority: live gesture, then the optimistic finished box, then the CUI truth. */
    static int[] effectiveBox(boolean selecting, int[] live, int[] cui, int[] pending) {
        if (selecting && live != null) return live;
        if (!selecting && pending != null) return pending;
        return cui;
    }

    /** View-space projection to scaled-screen pixels, or null behind the camera. */
    static double[] project(
            double vx,
            double vy,
            double vz,
            double tanHalfFov,
            double aspect,
            double width,
            double height) {
        if (vz <= 0.1) return null;
        double nx = vx / (vz * tanHalfFov * aspect);
        double ny = vy / (vz * tanHalfFov);
        if (nx < -1.2 || nx > 1.2 || ny < -1.2 || ny > 1.2) return null;
        return new double[] {(nx * 0.5 + 0.5) * width, (0.5 - ny * 0.5) * height};
    }

    static List<String> hintKeys(boolean selecting, boolean hasBox) {
        var keys = new ArrayList<String>();
        keys.add(selecting ? "wand_selecting" : "wand_select");
        if (!selecting && hasBox) keys.add("wand_clear");
        keys.add("wand_expand");
        return List.copyOf(keys);
    }

    private static Component wandText(String key, Object... args) {
        return Component.translatable("blockfield.workshop.tools." + key, args);
    }

    /**
     * Live wand hints with the real bound key names, so rebinding is reflected. Shown in the shared
     * workshop controls panel.
     */
    public static List<Component> hints(Minecraft mc) {
        Component use = mc.options.keyUse.getTranslatedKeyMessage();
        Component attack = mc.options.keyAttack.getTranslatedKeyMessage();
        Component shift = mc.options.keyShift.getTranslatedKeyMessage();
        boolean selecting = phase == WandPhase.SELECTING;
        var out = new ArrayList<Component>();
        for (String key : hintKeys(selecting, box() != null || pendingBox != null)) {
            out.add(
                    switch (key) {
                        case "wand_select" -> wandText(key, use);
                        case "wand_selecting" -> wandText(key, use, attack);
                        case "wand_clear" -> wandText(key, attack);
                        default -> wandText(key, shift);
                    });
        }
        return List.copyOf(out);
    }

    /**
     * Crosshair target for the live box: the hit block within reach, else a fixed point along the
     * look. Clicks still require a real block (useStep ignores air), so this preview-only cursor
     * can never send a corner the server would resolve differently.
     */
    static BlockPos cursorTarget(Minecraft mc) {
        BlockPos block = extendedBlock(mc);
        if (block != null) return block;
        if (mc.player == null) return null;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        return BlockPos.containing(eye.add(look.scale(CURSOR_FALLBACK_DIST)));
    }

    /**
     * Block for a wand click: the vanilla hit inside reach, else the same extended ray the cursor
     * above uses, up to {@link #CURSOR_REACH}. Air stays null so a click into the sky sends
     * nothing; holding RMB still yields one point per press through the caller's used gate.
     */
    static BlockPos extendedBlock(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        BlockPos vanilla =
                mc.hitResult instanceof BlockHitResult block
                                && block.getType() == HitResult.Type.BLOCK
                        ? block.getBlockPos()
                        : null;
        if (vanilla != null) return vanilla;
        var hit =
                mc.level.clip(
                        new ClipContext(
                                mc.player.getEyePosition(),
                                mc.player
                                        .getEyePosition()
                                        .add(mc.player.getLookAngle().scale(CURSOR_REACH)),
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                mc.player));
        BlockPos far =
                hit.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) hit).getBlockPos() : null;
        return resolveClick(vanilla, far);
    }

    /**
     * Pure click choice: a vanilla hit inside reach wins, otherwise the extended-ray block;
     * negative coordinates pass through untouched for the explicit WorldEdit commands.
     */
    static BlockPos resolveClick(BlockPos vanilla, BlockPos extended) {
        return vanilla != null ? vanilla : extended;
    }

    static void accept(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || text == null || text.length() > MAX_TEXT) return;
        if (dimension != client.level.dimension()) clear();
        dimension = client.level.dimension();
        parse(text);
    }

    static void parse(String text) {
        String[] parts = text.split("\\|", -1);
        if (parts.length < 1 || parts.length > MAX_PARTS) return;
        switch (parts[0]) {
            case "s" -> {
                if (parts.length == 2 && parts[1].length() <= 32) {
                    clearPoints();
                    shape = parts[1].toLowerCase(Locale.ROOT);
                }
            }
            case "p" -> {
                if (parts.length < 6) return;
                BlockPos point = point(parts[2], parts[3], parts[4]);
                Integer id = integer(parts[1]);
                if (point == null || id == null) return;
                if (shape.equals("cuboid") || shape.equals("extend")) {
                    if (id == 0) pos0 = point;
                    else if (id == 1) pos1 = point;
                } else if (poly.size() < MAX_POLY_POINTS) {
                    poly.add(point);
                }
            }
            case "e" -> {
                if (parts.length < 5) return;
                BlockPos point = point(parts[2], parts[3], parts[4]);
                Integer id = integer(parts[1]);
                if (point == null || id == null) return;
                if (id == 0) ellipsoidCenter = point;
                else if (id == 1) ellipsoidRadii = point;
            }
            case "cyl" -> {
                if (parts.length < 6) return;
                BlockPos center = point(parts[1], parts[2], parts[3]);
                Double rx = decimal(parts[4]);
                Double rz = decimal(parts[5]);
                if (center == null || rx == null || rz == null) return;
                cylCenter = center;
                cylRx = rx;
                cylRz = rz;
                hasCyl = true;
            }
            case "mm" -> {
                if (parts.length < 3) return;
                Integer min = integer(parts[1]);
                Integer max = integer(parts[2]);
                if (min == null
                        || max == null
                        || Math.abs((long) min) > HEIGHT_LIMIT
                        || Math.abs((long) max) > HEIGHT_LIMIT) return;
                yMin = min;
                yMax = max;
            }
            default -> {}
        }
        // The finished gesture's optimistic box retires once CUI confirms the same bounds.
        // Intermediate bursts (cleared or half-rebuilt points) never match, so the display holds
        // steady instead of flickering; a rejected /pos2 keeps showing until the next gesture,
        // cancel, or disconnect clears it.
        if (pendingBox != null && Arrays.equals(pendingBox, box())) pendingBox = null;
    }

    private static void clearPoints() {
        pos0 = pos1 = ellipsoidCenter = ellipsoidRadii = cylCenter = null;
        hasCyl = false;
        yMin = yMax = null;
        poly.clear();
    }

    private static Integer integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Double decimal(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && Math.abs(parsed) <= COORD_LIMIT ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static BlockPos point(String x, String y, String z) {
        Integer px = integer(x);
        Integer py = integer(y);
        Integer pz = integer(z);
        if (px == null
                || py == null
                || pz == null
                || Math.abs((long) px) > COORD_LIMIT
                || Math.abs((long) pz) > COORD_LIMIT
                || Math.abs((long) py) > HEIGHT_LIMIT) return null;
        return new BlockPos(px, py, pz);
    }

    /**
     * Inclusive selection bounds {@code {minX, minY, minZ, maxX, maxY, maxZ}}, a one-block box when
     * only one cuboid corner is set, or null when nothing showable is selected. Non-cuboid shapes
     * fall back to the bounding box of their points ({@code mm} overrides the Y range); {@code
     * poly} carries triangle indices rather than coordinates, so its vertices (already sent as
     * {@code p} events) are what counts.
     */
    static int[] box() {
        if (shape.equals("ellipsoid") && ellipsoidCenter != null && ellipsoidRadii != null) {
            return bounds(
                    ellipsoidCenter.getX() - Math.abs(ellipsoidRadii.getX()),
                    ellipsoidCenter.getY() - Math.abs(ellipsoidRadii.getY()),
                    ellipsoidCenter.getZ() - Math.abs(ellipsoidRadii.getZ()),
                    ellipsoidCenter.getX() + Math.abs(ellipsoidRadii.getX()),
                    ellipsoidCenter.getY() + Math.abs(ellipsoidRadii.getY()),
                    ellipsoidCenter.getZ() + Math.abs(ellipsoidRadii.getZ()));
        }
        if (shape.equals("cylinder") && hasCyl && yMin != null && yMax != null) {
            return bounds(
                    (int) Math.floor(cylCenter.getX() - Math.abs(cylRx)),
                    yMin,
                    (int) Math.floor(cylCenter.getZ() - Math.abs(cylRz)),
                    (int) Math.ceil(cylCenter.getX() + Math.abs(cylRx)),
                    yMax,
                    (int) Math.ceil(cylCenter.getZ() + Math.abs(cylRz)));
        }
        if (shape.equals("cuboid") || shape.equals("extend")) {
            if (pos0 != null && pos1 != null)
                return bounds(
                        pos0.getX(),
                        pos0.getY(),
                        pos0.getZ(),
                        pos1.getX(),
                        pos1.getY(),
                        pos1.getZ());
            BlockPos single = pos0 != null ? pos0 : pos1;
            return single == null
                    ? null
                    : bounds(
                            single.getX(),
                            single.getY(),
                            single.getZ(),
                            single.getX(),
                            single.getY(),
                            single.getZ());
        }
        if (poly.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos point : poly) {
            x0 = Math.min(x0, point.getX());
            y0 = Math.min(y0, point.getY());
            z0 = Math.min(z0, point.getZ());
            x1 = Math.max(x1, point.getX());
            y1 = Math.max(y1, point.getY());
            z1 = Math.max(z1, point.getZ());
        }
        if (yMin != null && yMax != null) {
            y0 = yMin;
            y1 = yMax;
        }
        return bounds(x0, y0, z0, x1, y1, z1);
    }

    private static int[] bounds(int ax, int ay, int az, int bx, int by, int bz) {
        return new int[] {
            Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
            Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz)
        };
    }

    private static void tick(Minecraft client) {
        if (handshakeSent || client.player == null) return;
        if (!WeaponHud.inWorkshop() || !showsSelection(client)) return;
        var type = worldEditCui == null ? CuiPayload.TYPE : worldEditCui.type();
        if (!ClientPlayNetworking.canSend(type)) return;
        ClientPlayNetworking.send(
                worldEditCui == null ? new CuiPayload(HANDSHAKE) : worldEditCui.payload(HANDSHAKE));
        handshakeSent = true;
    }

    private static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (dimension != client.level.dimension()) {
            clear();
            return;
        }
        boolean selecting = phase == WandPhase.SELECTING;
        int[] cui = box();
        int[] live = selecting ? liveBox(anchor, cursorTarget(client)) : null;
        int[] shown = effectiveBox(selecting, live, cui, pendingBox);
        if (!visible(WeaponHud.inWorkshop(), showsSelection(client), shown != null)
                || context.consumers() == null
                || context.matrixStack() == null) return;
        Vec3 camera = context.camera().getPosition();
        if (new AABB(shown[0], shown[1], shown[2], shown[3] + 1, shown[4] + 1, shown[5] + 1)
                        .distanceToSqr(camera)
                > RENDER_DISTANCE_SQR) return;
        var lines = context.consumers().getBuffer(RenderType.lines());
        var pose = context.matrixStack();
        LevelRenderer.renderLineBox(
                pose,
                lines,
                shown[0] - camera.x,
                shown[1] - camera.y,
                shown[2] - camera.z,
                shown[3] + 1 - camera.x,
                shown[4] + 1 - camera.y,
                shown[5] + 1 - camera.z,
                OUTLINE[0],
                OUTLINE[1],
                OUTLINE[2],
                ALPHA);
        // The Shift+wheel face glows white so the fine-adjust target is obvious.
        var look = client.player.getLookAngle();
        double[] slab =
                faceSlab(shown, WorkshopToolsClient.expandDirection(look.x, look.y, look.z));
        LevelRenderer.renderLineBox(
                pose,
                lines,
                slab[0] - camera.x,
                slab[1] - camera.y,
                slab[2] - camera.z,
                slab[3] - camera.x,
                slab[4] - camera.y,
                slab[5] - camera.z,
                FACE[0],
                FACE[1],
                FACE[2],
                FACE_ALPHA);
        if (selecting) {
            if (anchor != null) marker(pose, lines, camera, anchor, FIRST);
        } else if (shape.equals("cuboid") || shape.equals("extend")) {
            if (pos0 != null) marker(pose, lines, camera, pos0, FIRST);
            if (pos1 != null) marker(pose, lines, camera, pos1, SECOND);
        }
    }

    /** Size label projected above the box top-center; hidden behind the camera or off-screen. */
    private static void renderLabel(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.level == null
                || mc.screen != null
                || mc.options.hideGui
                || !WeaponHud.inWorkshop()) return;
        if (!showsSelection(mc)) return;
        boolean selecting = phase == WandPhase.SELECTING;
        int[] live = selecting ? liveBox(anchor, cursorTarget(mc)) : null;
        int[] shown = effectiveBox(selecting, live, box(), pendingBox);
        if (shown == null) return;
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 rel =
                new Vec3(
                                (shown[0] + shown[3] + 1) / 2.0,
                                shown[4] + 1 + 0.6,
                                (shown[2] + shown[5] + 1) / 2.0)
                        .subtract(camera.getPosition());
        Vec3 forward = new Vec3(camera.getLookVector());
        Vec3 upHint =
                Math.abs(forward.dot(new Vec3(0, 1, 0))) > 0.999
                        ? new Vec3(0, 0, 1)
                        : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(upHint).normalize();
        Vec3 up = right.cross(forward).normalize();
        double width = mc.getWindow().getGuiScaledWidth();
        double height = mc.getWindow().getGuiScaledHeight();
        double[] screen =
                project(
                        rel.dot(right),
                        rel.dot(up),
                        -rel.dot(forward),
                        Math.tan(Math.toRadians(mc.options.fov().get() * 0.5)),
                        width / height,
                        width,
                        height);
        if (screen == null) return;
        int[] size = dimensions(shown);
        String text =
                wandText("wand_size", size[0], size[1], size[2], blockCount(shown)).getString();
        if (tooBig(shown)) text += wandText("wand_too_big", MAX_SIDE).getString();
        var font = BfTheme.font();
        int x =
                (int)
                        Math.clamp(
                                screen[0] - font.width(text) / 2.0,
                                2,
                                width - font.width(text) - 2);
        graphics.drawString(font, text, x, (int) screen[1], 0xFFFFFF, true);
    }

    private static void marker(
            PoseStack pose, VertexConsumer lines, Vec3 camera, BlockPos corner, float[] color) {
        LevelRenderer.renderLineBox(
                pose,
                lines,
                corner.getX() + 0.3 - camera.x,
                corner.getY() + 0.3 - camera.y,
                corner.getZ() + 0.3 - camera.z,
                corner.getX() + 0.7 - camera.x,
                corner.getY() + 0.7 - camera.y,
                corner.getZ() + 0.7 - camera.z,
                color[0],
                color[1],
                color[2],
                ALPHA);
    }
}
