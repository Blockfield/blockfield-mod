package com.lunares.blockfield.client.render;

import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.BodyGeometry;
import com.lunares.blockfield.common.combat.BodyRig;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * ADR4 §8.3 capture and its F3+B view. Every armed {@code LivingEntityRenderer.render} of a player
 * (or named range Husk) produces one immutable {@link RenderCapture} at the final {@code
 * renderToBuffer}, tagged with its pass and presentation identity. Main validity is reset at every
 * main-frame entry: a culled or skipped main draw leaves the entity {@code not_rendered} for that
 * frame instead of reusing an older capture, and shadow/other passes never replace MAIN. F3+B draws
 * the current frame's MAIN capture; evidence readers consume the same record.
 */
public final class BodyPartHitboxRenderer {
    public static final String NO_FRAME_TEXT = "no rendered frame";
    public static final String NOT_RENDERED = "not_rendered";

    /** One colour per part: HEAD, TORSO, left arm, right arm, left leg, right leg. */
    static final float[][] PART_COLORS = {
        {1, 1, 0},
        {0, 1, 0},
        {1, 0.5f, 0},
        {1, 0, 0},
        {0, 1, 1},
        {0, 0.5f, 1}
    };

    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7},
        {0, 2}, {1, 3}, {4, 6}, {5, 7},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private static final List<String> PART_NAMES =
            List.of("head", "torso", "left_arm", "right_arm", "left_leg", "right_leg");

    /** World corners (origin applied) of every box of one part, with emission-order ids. */
    public record PartBoxes(int part, List<String> ids, List<double[]> corners) {}

    private static final Map<Integer, RenderCapture> MAIN = new ConcurrentHashMap<>();
    private static final Map<Integer, RenderCapture> SHADOW = new ConcurrentHashMap<>();
    private static final Map<Integer, Map<String, Object>> DRAWN = new ConcurrentHashMap<>();
    private static volatile long renderFrame;
    private static volatile long invocations;
    private static boolean inLevel;
    private static boolean subscribed;
    private static volatile boolean evidence;
    private static java.lang.reflect.Method irisInstance, irisShadow;
    private static boolean irisBroken;

    private BodyPartHitboxRenderer() {}

    /** Players suppress the vanilla AABB; every other entity keeps the vanilla hitbox. */
    public static boolean isPlayerOverlay(Class<?> entityClass) {
        return Player.class.isAssignableFrom(entityClass);
    }

    /** Renderer entries that capture: players and named Husks (range dummies drawn as players). */
    public static boolean captures(Entity entity) {
        return entity instanceof Player || entity instanceof Husk && entity.hasCustomName();
    }

    /**
     * Range dummies are drawn as players by {@code RangeHuskRenderer} (a synced custom name is the
     * only signal the client can see, as entity tags never reach it), so they draw the same six
     * boxes. A named Husk without a capture this frame (any name-tagged mob the range renderer does
     * not draw as a player) keeps the vanilla AABB like every other non-player.
     */
    static boolean isRangeOverlay(Entity entity) {
        return entity instanceof Husk && entity.hasCustomName() && MAIN.containsKey(entity.getId());
    }

    // --- frame and pass identity (render thread) ---

    /** Main-frame entry: every previous capture stops being this frame's evidence. */
    public static void frameEntry() {
        renderFrame++;
        MAIN.clear();
        SHADOW.clear();
        DRAWN.clear();
    }

    /** Bracket of the main {@code renderLevel} call; outside it renders are OTHER (GUI). */
    public static void levelPass(boolean active) {
        inLevel = active;
    }

    public static long renderFrame() {
        return renderFrame;
    }

    /** The pass a renderer invocation starting now belongs to. */
    public static RenderCapture.Pass pass() {
        if (irisShadowPass()) return RenderCapture.Pass.SHADOW;
        return inLevel ? RenderCapture.Pass.MAIN : RenderCapture.Pass.OTHER;
    }

    public static long nextInvocation() {
        return ++invocations;
    }

    /** Iris renders its shadow map inside renderLevel; its public API names that pass. */
    private static boolean irisShadowPass() {
        if (irisBroken) return false;
        try {
            if (irisShadow == null) {
                if (!FabricLoader.getInstance().isModLoaded("iris")) {
                    irisBroken = true;
                    return false;
                }
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisInstance = api.getMethod("getInstance");
                irisShadow = api.getMethod("isRenderingShadowPass");
            }
            return (boolean) irisShadow.invoke(irisInstance.invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unknown) {
            irisBroken = true;
            return false;
        }
    }

    // --- capture ---

    /**
     * Captures the posed model at {@code renderToBuffer}. {@code entry} is the pose at the
     * renderer's entry (the dispatcher's translation), {@code pose} the current one; {@code origin}
     * is the lerped feet render position, {@code offset} the renderer offset and {@code camera} the
     * dispatcher camera (null when unknown).
     */
    public static RenderCapture capture(
            LivingEntity entity,
            HumanoidModel<?> model,
            Matrix4fc entry,
            Matrix4fc pose,
            RenderCapture.Pass pass,
            long invocation,
            Vec3 origin,
            Vec3 offset,
            Vec3 camera) {
        boolean rebase = camera != null && translationOnly(entry, origin, offset, camera);
        var root = new Matrix4f(pose);
        if (rebase) {
            root.m30(pose.m30() - (float) (origin.x - camera.x));
            root.m31(pose.m31() - (float) (origin.y - camera.y));
            root.m32(pose.m32() - (float) (origin.z - camera.z));
        } else {
            root =
                    new Matrix4f()
                            .translation((float) offset.x, (float) offset.y, (float) offset.z)
                            .mul(new Matrix4f(entry).invert())
                            .mul(pose);
        }
        var parts =
                new ModelPart[] {
                    model.head,
                    model.body,
                    model.leftArm,
                    model.rightArm,
                    model.leftLeg,
                    model.rightLeg
                };
        float[] params = new float[36];
        for (int i = 0; i < 6; i++) {
            var p = parts[i];
            params[i * 6] = p.x;
            params[i * 6 + 1] = p.y;
            params[i * 6 + 2] = p.z;
            params[i * 6 + 3] = p.xRot;
            params[i * 6 + 4] = p.yRot;
            params[i * 6 + 5] = p.zRot;
        }
        var boxes = BodyGeometry.skin();
        var bones = new Matrix4f[6];
        for (int i = 0; i < 6; i++) bones[i] = new Matrix4f();
        BodyRig.bones(root, params, bones);
        var ids = new ArrayList<String>(boxes.size());
        float[] corners = new float[boxes.size() * 24];
        var eight = new Vector3f[8];
        for (int i = 0; i < 8; i++) eight[i] = new Vector3f();
        int at = 0;
        for (var box : boxes) {
            BodyRig.corners(bones[box.bone()], box, eight);
            ids.add(PART_NAMES.get(box.bone()) + ":" + box.id());
            for (var c : eight) {
                corners[at++] = c.x;
                corners[at++] = c.y;
                corners[at++] = c.z;
            }
        }
        var capture =
                new RenderCapture(
                        renderFrame,
                        pass,
                        invocation,
                        entity.getId(),
                        identity(entity),
                        new double[] {origin.x, origin.y, origin.z},
                        new double[] {offset.x, offset.y, offset.z},
                        camera == null
                                ? new double[] {Double.NaN, Double.NaN, Double.NaN}
                                : new double[] {camera.x, camera.y, camera.z},
                        rebase,
                        root.get(new float[16]),
                        params,
                        ids,
                        corners);
        // OTHER (GUI) captures are returned to the caller only; shadows never replace MAIN.
        if (pass == RenderCapture.Pass.MAIN) MAIN.put(entity.getId(), capture);
        else if (pass == RenderCapture.Pass.SHADOW) SHADOW.put(entity.getId(), capture);
        return capture;
    }

    /** The dispatcher translated by exactly {@code (float) ((origin - camera) + offset)}. */
    private static boolean translationOnly(Matrix4fc entry, Vec3 origin, Vec3 offset, Vec3 camera) {
        return entry.m00() == 1
                && entry.m01() == 0
                && entry.m02() == 0
                && entry.m03() == 0
                && entry.m10() == 0
                && entry.m11() == 1
                && entry.m12() == 0
                && entry.m13() == 0
                && entry.m20() == 0
                && entry.m21() == 0
                && entry.m22() == 1
                && entry.m23() == 0
                && entry.m33() == 1
                && entry.m30() == (float) (origin.x - camera.x + offset.x)
                && entry.m31() == (float) (origin.y - camera.y + offset.y)
                && entry.m32() == (float) (origin.z - camera.z + offset.z);
    }

    private static RenderCapture.Identity identity(LivingEntity entity) {
        var receipt = com.lunares.blockfield.client.combat.ClientCombatScene.receipt();
        var sample = receipt == null ? null : receipt.entities().get(entity.getId());
        if (receipt == null || sample == null) return RenderCapture.Identity.NATIVE;
        var exact = receipt.exact();
        var record = sample.before().identity();
        com.lunares.blockfield.common.combat.AnimationStartReport.Victim row = null;
        if (exact != null && exact.report() != null)
            for (var victim : exact.report().victims())
                if (victim.entityId() == entity.getId()) row = victim;
        return new RenderCapture.Identity(
                exact == null ? 0 : exact.connectionId(),
                receipt.observerStreamEpoch(),
                exact == null ? 0 : exact.sceneGeneration(),
                exact == null ? 0 : exact.frameId(),
                exact == null ? 0 : exact.revision(),
                receipt.high(),
                receipt.tick(),
                receipt.alpha(),
                exact == null ? 0 : exact.a().tick(),
                exact == null ? 0 : exact.a().alpha(),
                sample.before().epoch(),
                sample.before().seat(),
                sample.before().teleport(),
                record == null ? 0 : record.subscriptionGeneration(),
                record == null ? 0 : record.resourceGeneration(),
                record == null ? 0 : record.policyGeneration(),
                record == null ? "" : hex(record.compatibilityDigest()),
                record == null ? "" : hex(record.manifestDigest()),
                sample.before().tick(),
                sample.after().tick(),
                sample.fraction(),
                sample.mode(),
                row == null ? 0 : row.seedRef(),
                row == null ? 0 : row.appliedEventSequence(),
                row == null ? "" : hex(row.eventPrefixDigest()),
                row == null ? "" : hex(row.presentationDigest()),
                com.lunares.blockfield.client.combat.DrivenPresentation.driven(entity),
                exact == null || exact.unavailable() == null ? "" : exact.unavailable());
    }

    private static String hex(AnimationResources.Digest digest) {
        return digest == null ? "" : HexFormat.of().formatHex(digest.bytes());
    }

    /** This frame's MAIN capture of the entity, or null ({@link #NOT_RENDERED}). */
    public static RenderCapture mainCapture(int entityId) {
        var capture = MAIN.get(entityId);
        return capture != null && capture.renderFrame() == renderFrame ? capture : null;
    }

    /** This frame's latest shadow-pass capture (diagnostic; never evidence for a shot). */
    public static RenderCapture shadowCapture(int entityId) {
        var capture = SHADOW.get(entityId);
        return capture != null && capture.renderFrame() == renderFrame ? capture : null;
    }

    /**
     * Null when the entity has this frame's MAIN capture; otherwise the label the overlay shows.
     */
    public static String statusFor(int entityId) {
        return mainCapture(entityId) == null ? NO_FRAME_TEXT : null;
    }

    /** World corners of the capture's boxes grouped by part, in BodyGeometry emission order. */
    public static List<PartBoxes> boxesFor(RenderCapture capture) {
        var world = capture.worldCorners();
        var ids = new ArrayList<List<String>>();
        var corners = new ArrayList<List<double[]>>();
        for (int i = 0; i < 6; i++) {
            ids.add(new ArrayList<>());
            corners.add(new ArrayList<>());
        }
        var boxes = BodyGeometry.skin();
        for (int b = 0; b < boxes.size(); b++) {
            int part = boxes.get(b).bone();
            ids.get(part).add(boxes.get(b).id());
            var packed = new double[24];
            System.arraycopy(world, b * 24, packed, 0, 24);
            corners.get(part).add(packed);
        }
        var out = new ArrayList<PartBoxes>(6);
        for (int part = 0; part < 6; part++)
            out.add(
                    new PartBoxes(
                            part, List.copyOf(ids.get(part)), List.copyOf(corners.get(part))));
        return out;
    }

    // --- F3+B ---

    /**
     * Mixin entry: true suppresses the vanilla {@code renderHitbox} (so the player AABB is never
     * drawn). In the main pass it draws this frame's MAIN capture with the effective eye point and
     * view vector, or only the missing label without one; other passes draw nothing.
     */
    public static boolean handle(
            Entity entity,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,
            float partialTick) {
        if (!isPlayerOverlay(entity.getClass()) && !isRangeOverlay(entity)) return false;
        if (pass() != RenderCapture.Pass.MAIN) return true;
        RenderCapture capture = mainCapture(entity.getId());
        if (capture == null) {
            drawMissingLabel(entity, poseStack);
            return true;
        }
        drawCapture(
                poseStack,
                consumer,
                capture,
                headAnchorFeetRelative(entity, capture),
                entity.getViewVector(partialTick),
                entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ()),
                entity.level().getGameTime());
        return true;
    }

    /**
     * Effective eye on the animated head, feet-relative for the capture frame: the head box point
     * nearest the vanilla eye (the vanilla eye itself in ordinary poses), else the vanilla eye.
     */
    static Vec3 headAnchorFeetRelative(Entity entity, RenderCapture capture) {
        Vec3 vanilla = entity.getEyePosition();
        Vec3 feet = entity.position();
        Vec3 legacy = new Vec3(0, entity.getEyeHeight(), 0);
        if (vanilla == null || feet == null) return legacy;
        try {
            var head = boxesFor(capture).get(BodyGeometry.HEAD).corners();
            if (head.isEmpty()) return vanilla.subtract(feet);
            Vec3 anchor =
                    com.lunares.blockfield.common.combat.EffectiveEye.boxFromCorners(head.get(0))
                            .anchor(vanilla);
            return anchor.subtract(feet);
        } catch (RuntimeException | AssertionError broken) {
            return vanilla.subtract(feet);
        }
    }

    /**
     * Draws every box as 12 line edges in the part colour, then the effective eye line and view
     * vector. The hitbox pose stack sits at the capture's origin in the same pass, so the local
     * float corners are drawn directly (no world round trip). Returns the drawn box count.
     */
    static int drawCapture(
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,
            RenderCapture capture,
            Vec3 eye,
            Vec3 view,
            AABB relativeBox,
            long tick) {
        var local = capture.corners();
        var boxes = BodyGeometry.skin();
        for (int b = 0; b < boxes.size(); b++) {
            var box = new float[24];
            System.arraycopy(local, b * 24, box, 0, 24);
            drawEdges(poseStack, consumer, box, PART_COLORS[boxes.get(b).bone()]);
        }
        LevelRenderer.renderLineBox(
                poseStack,
                consumer,
                relativeBox.minX,
                eye.y - 0.01,
                relativeBox.minZ,
                relativeBox.maxX,
                eye.y + 0.01,
                relativeBox.maxZ,
                1,
                0,
                0,
                1);
        renderVector(
                poseStack,
                consumer,
                new Vector3f((float) eye.x, (float) eye.y, (float) eye.z),
                view.scale(2),
                0xFF0000FF);
        var recorded = snapshot(capture);
        recorded.put("tick", tick);
        recorded.put("source", "rendered_entity");
        recorded.put("drawn", true);
        DRAWN.put(capture.entityId(), recorded);
        return boxes.size();
    }

    /**
     * The testbot receipt of one capture: {@code serverTick} (the presentation's R tick, or the
     * level time when native), {@code shapeKey}, {@code boxes} (part name to boxes to 24 world
     * doubles, {@code origin + local}) and {@code capture}, the complete §8.3 identity with local
     * corners, double origin/offset/camera, root and part parameters.
     */
    static Map<String, Object> snapshot(RenderCapture capture) {
        var boxes = new LinkedHashMap<String, List<List<Double>>>();
        for (var name : PART_NAMES) boxes.put(name, new ArrayList<>());
        for (PartBoxes part : boxesFor(capture))
            for (double[] corners : part.corners()) {
                var world = new ArrayList<Double>(24);
                for (double value : corners) world.add(value);
                boxes.get(PART_NAMES.get(part.part())).add(List.copyOf(world));
            }
        var id = capture.identity();
        var out = new LinkedHashMap<String, Object>();
        out.put("serverTick", id.rTick() != 0 || id.high() != 0 ? id.rTick() : levelTime());
        out.put("shapeKey", "six");
        out.put("status", "rendered");
        out.put("boxes", boxes);
        out.put("capture", describe(capture));
        return out;
    }

    private static long levelTime() {
        var client = Minecraft.getInstance();
        return client == null || client.level == null ? 0 : client.level.getGameTime();
    }

    /** Machine-readable §8.3 record (stable keys; floats as raw values and bits). */
    public static Map<String, Object> describe(RenderCapture capture) {
        var id = capture.identity();
        var out = new LinkedHashMap<String, Object>();
        out.put("schema", "blockfield.rendercapture.v1");
        out.put("renderFrame", capture.renderFrame());
        out.put("pass", capture.pass().name());
        out.put("invocation", capture.invocation());
        out.put("entityId", capture.entityId());
        out.put("connectionId", id.connectionId());
        out.put("observerStreamEpoch", id.observerStreamEpoch());
        out.put("sceneGeneration", id.sceneGeneration());
        out.put("frameId", id.frameId());
        out.put("presentationRevision", id.revision());
        out.put("H", id.high());
        out.put("R", List.of(id.rTick(), id.rAlpha()));
        out.put("A", List.of(id.aTick(), id.aAlpha()));
        out.put("targetEpoch", id.targetEpoch());
        out.put("seatGeneration", id.seatGeneration());
        out.put("teleportGeneration", id.teleportGeneration());
        out.put("subscriptionGeneration", id.subscriptionGeneration());
        out.put("resourceGeneration", id.resourceGeneration());
        out.put("policyGeneration", id.policyGeneration());
        out.put("compatibilityDigest", id.compatibilityDigest());
        out.put("manifestDigest", id.manifestDigest());
        out.put("placementBefore", id.placementBefore());
        out.put("placementAfter", id.placementAfter());
        out.put("placementFraction", id.placementFraction());
        out.put("sampleMode", id.sampleMode());
        out.put("seedRef", id.seedRef());
        out.put("appliedEventSequence", id.appliedEventSequence());
        out.put("eventPrefixDigest", id.eventPrefixDigest());
        out.put("presentationDigest", id.presentationDigest());
        out.put("driven", id.driven());
        out.put("unavailable", id.unavailable());
        out.put("origin", doubles(capture.origin()));
        out.put("rendererOffset", doubles(capture.offset()));
        out.put("camera", doubles(capture.camera()));
        out.put("rebaseExact", capture.rebaseExact());
        out.put("rootBits", bits(capture.root()));
        out.put("partBits", bits(capture.parts()));
        out.put("boxIds", capture.boxIds());
        out.put("localCornerBits", bits(capture.corners()));
        return out;
    }

    private static List<Double> doubles(double[] values) {
        var out = new ArrayList<Double>(values.length);
        for (double value : values) out.add(value);
        return out;
    }

    private static List<Integer> bits(float[] values) {
        var out = new ArrayList<Integer>(values.length);
        for (float value : values) out.add(Float.floatToRawIntBits(value));
        return out;
    }

    private static void drawEdges(
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,
            float[] local,
            float[] color) {
        var pose = poseStack.last();
        for (int[] edge : EDGES) {
            float ax = local[edge[0] * 3];
            float ay = local[edge[0] * 3 + 1];
            float az = local[edge[0] * 3 + 2];
            float bx = local[edge[1] * 3];
            float by = local[edge[1] * 3 + 1];
            float bz = local[edge[1] * 3 + 2];
            float nx = bx - ax;
            float ny = by - ay;
            float nz = bz - az;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1e-9f) {
                nx /= length;
                ny /= length;
                nz /= length;
            }
            consumer.addVertex(pose, ax, ay, az)
                    .setColor(color[0], color[1], color[2], 1)
                    .setNormal(pose, nx, ny, nz);
            consumer.addVertex(pose, bx, by, bz)
                    .setColor(color[0], color[1], color[2], 1)
                    .setNormal(pose, nx, ny, nz);
        }
    }

    /** Vanilla {@code EntityRenderDispatcher.renderVector}: a line from origin along the vector. */
    private static void renderVector(
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,
            Vector3f origin,
            Vec3 vector,
            int color) {
        var pose = poseStack.last();
        consumer.addVertex(pose, origin)
                .setColor(color)
                .setNormal(pose, (float) vector.x, (float) vector.y, (float) vector.z);
        consumer.addVertex(
                        pose,
                        (float) (origin.x() + vector.x),
                        (float) (origin.y() + vector.y),
                        (float) (origin.z() + vector.z))
                .setColor(color)
                .setNormal(pose, (float) vector.x, (float) vector.y, (float) vector.z);
    }

    /**
     * Floating label above the head; touches the client font, so only the no-frame path calls it.
     */
    private static void drawMissingLabel(
            Entity entity, com.mojang.blaze3d.vertex.PoseStack poseStack) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null || client.gameRenderer == null) return;
        var camera = client.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) return;
        poseStack.pushPose();
        try {
            poseStack.translate(0, entity.getBbHeight() + 0.5, 0);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            var buffer = client.renderBuffers().bufferSource();
            client.font.drawInBatch(
                    NO_FRAME_TEXT,
                    -client.font.width(NO_FRAME_TEXT) / 2f,
                    0,
                    0xFFFF5555,
                    false,
                    poseStack.last().pose(),
                    buffer,
                    Font.DisplayMode.NORMAL,
                    0,
                    15728880);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * The testbot receipt (§9.1(10)) of the entity: the boxes F3+B last drew, else this frame's
     * MAIN capture undrawn, else null (not rendered this frame). Read reflectively so the testbot
     * keeps no compile dependency on this mod.
     */
    public static Map<String, Object> debugSnapshot(int entityId) {
        var current = mainCapture(entityId);
        var drawn = DRAWN.get(entityId);
        if (drawn != null
                && current != null
                && drawn.get("capture") instanceof Map<?, ?> described
                && described.get("invocation") instanceof Long invocation
                && invocation == current.invocation()) return drawn;
        if (current == null) return null;
        var out = snapshot(current);
        out.put("tick", levelTime());
        out.put("source", "rendered_entity");
        out.put("drawn", false);
        return out;
    }

    /**
     * The current frame's evidence for one entity: the MAIN capture's record, or {@code status:
     * not_rendered} (culled, skipped, invisible or not armed) with this frame's id. Never an older
     * frame's capture.
     */
    public static Map<String, Object> captureSnapshot(int entityId) {
        var current = mainCapture(entityId);
        if (current != null) {
            var out = describe(current);
            out.put("status", "rendered");
            return out;
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("schema", "blockfield.rendercapture.v1");
        out.put("status", NOT_RENDERED);
        out.put("renderFrame", renderFrame);
        out.put("entityId", entityId);
        out.put("armed", wantsCapture());
        var shadow = shadowCapture(entityId);
        if (shadow != null) out.put("shadowInvocation", shadow.invocation());
        return out;
    }

    /** Capture runs while F3+B shows hitboxes or an evidence reader armed it. */
    public static boolean wantsCapture() {
        return subscribed || evidence || com.lunares.blockfield.common.combat.ShotDebug.enabled();
    }

    /** Arms capture without F3+B (testbot evidence); off by default for ordinary clients. */
    public static void evidence(boolean on) {
        evidence = on;
        if (!wantsCapture()) clear();
    }

    public static java.util.Set<Long> resourceGenerations() {
        var generations = new java.util.HashSet<Long>();
        for (var capture : MAIN.values())
            if (capture.identity().driven())
                generations.add(capture.identity().resourceGeneration());
        for (var capture : SHADOW.values())
            if (capture.identity().driven())
                generations.add(capture.identity().resourceGeneration());
        return generations;
    }

    public static void forget(int id) {
        MAIN.remove(id);
        SHADOW.remove(id);
        DRAWN.remove(id);
    }

    /** Capture is local; showing F3+B never subscribes to a server bone stream. */
    public static void pollSubscription(Minecraft client) {
        boolean want =
                client.player != null
                        && client.getEntityRenderDispatcher() != null
                        && client.getEntityRenderDispatcher().shouldRenderHitBoxes();
        if (want == subscribed) return;
        subscribed = want;
        if (!wantsCapture()) clear();
    }

    public static void onDisconnect() {
        subscribed = false;
        evidence = false;
        clear();
    }

    private static void clear() {
        MAIN.clear();
        SHADOW.clear();
        DRAWN.clear();
    }

    static void resetForTests() {
        onDisconnect();
        inLevel = false;
    }
}
