package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.ClientConfig;
import com.lunares.blockfield.network.packet.WorkshopAiPreviewS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bounded outline and build front per server job; never predicts placements. World blocks are
 * read only by {@link WorkshopAiGhostMesh}, once per plan revision.
 */
public final class WorkshopAiEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkshopAiEffects.class);
    private static final int MAX_JOBS = 6;
    private static final long STALE_NS = 30_000_000_000L;
    private static final long PREVIEW_NS = 120_000_000_000L;
    private static final long FINISH_NS = 900_000_000L;

    private record EffectKey(UUID ownerId, UUID jobId) {}

    private static final Map<EffectKey, Effect> jobs = new LinkedHashMap<>();

    private static final class Effect {
        WorkshopAiStateS2CPacket packet;
        long updated;
        double from;
        double target;
        int sparks;
        WorkshopBlueprint blueprint;
        BlockPos origin;
        String plan;
        int transform;
        WorkshopAiGhostMesh mesh;
        WorkshopAiGhostMesh.Bake bake;

        double front(long now) {
            double t = Math.clamp((now - updated) / 170_000_000.0, 0, 1);
            return from + (target - from) * (1 - Math.pow(1 - t, 3));
        }

        /** Holding the AI tool shows the whole plan textured; others only see it in preview. */
        boolean textured(boolean holdingAi) {
            return blueprint != null
                    && (holdingAi
                            || packet.stage().equals("preview")
                            || packet.stage().equals("awaiting"));
        }

        void closeMesh() {
            if (bake != null) bake.close();
            bake = null;
            if (mesh != null) mesh.close();
            mesh = null;
        }
    }

    private static ResourceKey<Level> dimension;
    private static UUID localOwner;
    private static int ticks;

    record PreviewView(BlockPos origin, WorkshopBlueprint blueprint) {}

    static PreviewView previewView(UUID ownerId, UUID jobId) {
        Effect effect = jobs.get(new EffectKey(ownerId, jobId));
        if (effect == null || effect.blueprint == null || effect.origin == null) return null;
        return new PreviewView(effect.origin, effect.blueprint);
    }

    private WorkshopAiEffects() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(WorkshopAiEffects::render);
        ClientTickEvents.END_CLIENT_TICK.register(WorkshopAiEffects::tick);
    }

    public static void clear() {
        jobs.values().forEach(Effect::closeMesh);
        jobs.clear();
        dimension = null;
    }

    public static boolean quiet() {
        return ClientConfig.USER.getBoolean("workshop.quietEffects", false);
    }

    public static boolean hasPreview(UUID ownerId, UUID jobId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return false;
        return hasPreview(ownerId, jobId, client.level.dimension(), client.player.position());
    }

    static boolean hasPreview(UUID ownerId, UUID jobId, ResourceKey<Level> world, Vec3 viewer) {
        Effect effect = jobs.get(new EffectKey(ownerId, jobId));
        return effect != null
                && effect.blueprint != null
                && world == dimension
                && withinRenderDistance(effect.packet, viewer);
    }

    static boolean withinRenderDistance(WorkshopAiStateS2CPacket packet, Vec3 viewer) {
        return new AABB(
                                packet.min().getX(),
                                packet.min().getY(),
                                packet.min().getZ(),
                                packet.max().getX() + 1.0,
                                packet.max().getY() + 1.0,
                                packet.max().getZ() + 1.0)
                        .distanceToSqr(viewer)
                <= 128 * 128;
    }

    public static void accept(WorkshopAiStateS2CPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        localOwner = client.player == null ? null : client.player.getUUID();
        accept(packet, client.level.dimension(), System.nanoTime());
    }

    static void accept(WorkshopAiStateS2CPacket packet, ResourceKey<Level> world, long now) {
        EffectKey key = new EffectKey(packet.ownerId(), packet.jobId());
        boolean visible =
                switch (packet.stage()) {
                    case "preview", "awaiting", "applying", "undoing", "complete", "cancelled" ->
                            true;
                    default -> false;
                };
        if (!visible || packet.totalBlocks() <= 0 || !validBounds(packet)) {
            remove(key);
            return;
        }
        if (dimension != world) clear();
        dimension = world;
        Effect effect = jobs.get(key);
        if (effect == null) {
            if (packet.stage().equals("complete") || packet.stage().equals("cancelled")) return;
            if (jobs.size() >= MAX_JOBS) evict();
            effect = new Effect();
            jobs.put(key, effect);
        }
        int previous = effect.packet == null ? 0 : effect.packet.completedBlocks();
        effect.from = effect.front(now);
        effect.target = Math.clamp(packet.completedBlocks() / (double) packet.totalBlocks(), 0, 1);
        effect.sparks = Math.min(8, Math.max(0, packet.completedBlocks() - previous));
        effect.packet = packet;
        if (packet.stage().equals("cancelled") || packet.stage().equals("complete")) {
            effect.blueprint = null;
            effect.closeMesh();
        }
        effect.updated = now;
    }

    public static void acceptPreview(WorkshopAiPreviewS2CPacket packet) {
        WorkshopSketchPlacement.rememberPreview(packet);
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        localOwner = client.player.getUUID();
        acceptPreview(
                packet, client.level.dimension(), client.player.blockPosition(), System.nanoTime());
    }

    static void acceptPreview(
            WorkshopAiPreviewS2CPacket packet,
            ResourceKey<Level> world,
            BlockPos viewer,
            long now) {
        EffectKey key = new EffectKey(packet.ownerId(), packet.jobId());
        if (Math.abs((long) packet.origin().getX()) > 30_000_000
                || Math.abs((long) packet.origin().getZ()) > 30_000_000
                || Math.abs((long) packet.origin().getY()) > 2048) return;
        try {
            WorkshopBlueprint blueprint =
                    WorkshopBlueprint.parse(
                            packet.plan(),
                            viewer.getX() - packet.origin().getX(),
                            viewer.getY() - packet.origin().getY(),
                            viewer.getZ() - packet.origin().getZ(),
                            packet.sx(),
                            packet.sy(),
                            packet.sz(),
                            packet.rotY(),
                            packet.mirror());
            if (dimension != world) clear();
            dimension = world;
            Effect effect = jobs.get(key);
            if (effect == null) {
                if (jobs.size() >= MAX_JOBS) evict();
                effect = new Effect();
                effect.packet = previewState(packet, blueprint);
                jobs.put(key, effect);
            }
            // The mesh is keyed by job, plan and origin; a resent identical plan keeps it.
            int transform = packet.rotY() + (packet.mirror() ? 4 : 0);
            if (!packet.plan().equals(effect.plan)
                    || !packet.origin().equals(effect.origin)
                    || effect.transform != transform) effect.closeMesh();
            effect.transform = transform;
            effect.blueprint = blueprint;
            effect.origin = packet.origin();
            effect.plan = packet.plan();
            effect.updated = now;
        } catch (RuntimeException invalid) {
            remove(key);
        }
    }

    private static void evict() {
        for (var key : jobs.keySet()) {
            if (!key.ownerId().equals(localOwner)) {
                remove(key);
                return;
            }
        }
        remove(jobs.keySet().iterator().next());
    }

    private static void remove(EffectKey key) {
        Effect effect = jobs.remove(key);
        if (effect != null) effect.closeMesh();
    }

    static WorkshopAiStateS2CPacket previewState(
            WorkshopAiPreviewS2CPacket packet, WorkshopBlueprint blueprint) {
        int maxX = 0, maxY = 0, maxZ = 0;
        for (var box : blueprint.boxes()) {
            maxX = Math.max(maxX, box.x1() - 1);
            maxY = Math.max(maxY, box.y1() - 1);
            maxZ = Math.max(maxZ, box.z1() - 1);
        }
        return new WorkshopAiStateS2CPacket(
                packet.ownerId(),
                packet.jobId(),
                "preview",
                "",
                "",
                packet.origin(),
                packet.origin().offset(maxX, maxY, maxZ),
                blueprint.touched(),
                0,
                false,
                false);
    }

    static boolean validBounds(WorkshopAiStateS2CPacket packet) {
        for (long delta :
                new long[] {
                    (long) packet.max().getX() - packet.min().getX(),
                    (long) packet.max().getY() - packet.min().getY(),
                    (long) packet.max().getZ() - packet.min().getZ()
                }) if (delta < 0 || delta >= WorkshopAiPreviewS2CPacket.MAX_SIDE) return false;
        return Math.abs((long) packet.min().getX()) <= 30_000_000
                && Math.abs((long) packet.min().getZ()) <= 30_000_000
                && Math.abs((long) packet.min().getY()) <= 2048;
    }

    static void ownerState(WorkshopAiStateS2CPacket packet, UUID owner) {
        localOwner = owner;
        for (var key : java.util.List.copyOf(jobs.keySet())) {
            if (key.ownerId().equals(owner)
                    && (!key.jobId().equals(packet.jobId())
                            || !packet.stage().equals("preview")
                                    && !packet.stage().equals("awaiting"))) {
                Effect effect = jobs.get(key);
                // A superseded queued ghost must go with the preview: neither has a time expiry
                // while owned, so the job change itself is the lifecycle bound.
                if (effect.packet.stage().equals("preview")
                        || effect.packet.stage().equals("awaiting")) remove(key);
            }
        }
    }

    private static void expire(Minecraft client, long now) {
        if (client.level == null || client.level.dimension() != dimension) {
            clear();
            return;
        }
        expire(
                client.level.dimension(),
                client.player == null ? null : client.player.getUUID(),
                now);
    }

    static void expire(ResourceKey<Level> world, UUID owner, long now) {
        if (world != dimension) {
            clear();
            return;
        }
        for (Iterator<Effect> it = jobs.values().iterator(); it.hasNext(); ) {
            Effect effect = it.next();
            String stage = effect.packet.stage();
            // The owner's queued ghost lives for the whole queue wait like a preview, not just
            // the generic stale window: the queue timeout (120 s) is the lifecycle bound.
            if (effect.packet.ownerId().equals(owner)
                    && (stage.equals("preview") || stage.equals("awaiting"))) continue;
            long ttl =
                    stage.equals("complete") || stage.equals("cancelled")
                            ? FINISH_NS
                            : stage.equals("preview") ? PREVIEW_NS : STALE_NS;
            if (now - effect.updated <= ttl) continue;
            effect.closeMesh();
            it.remove();
        }
    }

    private static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        long now = System.nanoTime();
        expire(client, now);
        if (jobs.isEmpty() || context.consumers() == null || context.matrixStack() == null) return;
        Vec3 camera = context.camera().getPosition();
        var pose = context.matrixStack();
        boolean holdingAi = WorkshopToolsClient.holdsAi(client);
        boolean textured = WorkshopAiGhostMesh.enabled(quiet());
        if (textured) renderMeshes(context, client, camera, holdingAi);
        renderGhosts(context, camera, now, textured, holdingAi);
        var lines = context.consumers().getBuffer(RenderType.lines());
        for (Effect effect : jobs.values()) {
            var p = effect.packet;
            if (!withinRenderDistance(p, camera)) continue;
            float alpha =
                    p.stage().equals("complete") || p.stage().equals("cancelled")
                            ? (float) Math.max(0, 1 - (now - effect.updated) / (double) FINISH_NS)
                            : 0.65f;
            double x0 = p.min().getX() - camera.x;
            double y0 = p.min().getY() - camera.y;
            double z0 = p.min().getZ() - camera.z;
            double x1 = p.max().getX() + 1 - camera.x;
            double y1 = p.max().getY() + 1 - camera.y;
            double z1 = p.max().getZ() + 1 - camera.z;
            LevelRenderer.renderLineBox(
                    pose, lines, x0, y0, z0, x1, y1, z1, 0.96f, 0.65f, 0.14f, alpha);
            if (effect.blueprint != null) {
                double ox = effect.origin.getX() - camera.x;
                double oy = effect.origin.getY() - camera.y;
                double oz = effect.origin.getZ() - camera.z;
                double height = p.max().getY() - effect.origin.getY() + 1;
                double front = p.stage().equals("applying") ? height * effect.front(now) : -1;
                for (var box : effect.blueprint.boxes()) {
                    if (box.y1() < front) continue;
                    LevelRenderer.renderLineBox(
                            pose,
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
                            alpha * 0.45f);
                }
            }
            if (!quiet() && (p.stage().equals("applying") || p.stage().equals("undoing"))) {
                double y =
                        p.stage().equals("undoing")
                                ? y1 - (y1 - y0) * effect.front(now)
                                : y0 + (y1 - y0) * effect.front(now);
                LevelRenderer.renderLineBox(
                        pose, lines, x0, y, z0, x1, y + 0.03, z1, 1f, 0.78f, 0.38f, alpha);
            }
        }
    }

    private static void renderMeshes(
            WorldRenderContext context, Minecraft client, Vec3 camera, boolean holdingAi) {
        long deadline = System.nanoTime() + WorkshopAiGhostMesh.BAKE_NS;
        for (Effect effect : jobs.values()) {
            if (!effect.textured(holdingAi) || !withinRenderDistance(effect.packet, camera))
                continue;
            if (effect.mesh == null) {
                try {
                    if (System.nanoTime() < deadline) {
                        if (effect.bake == null)
                            effect.bake =
                                    new WorkshopAiGhostMesh.Bake(
                                            client,
                                            effect.blueprint,
                                            effect.origin,
                                            BlockPos.containing(camera));
                        effect.mesh = effect.bake.advance(deadline);
                        if (effect.mesh != null) effect.bake = null;
                    }
                } catch (RuntimeException | OutOfMemoryError failure) {
                    LOGGER.warn("Textured AI preview unavailable, using outlines", failure);
                    effect.closeMesh();
                    effect.mesh = WorkshopAiGhostMesh.FAILED;
                }
            }
            if (effect.mesh == null) continue;
            effect.mesh.draw(
                    client,
                    context.matrixStack().last().pose(),
                    effect.origin.getX() - camera.x,
                    effect.origin.getY() - camera.y,
                    effect.origin.getZ() - camera.z);
        }
    }

    private static void renderGhosts(
            WorldRenderContext context,
            Vec3 camera,
            long now,
            boolean textured,
            boolean holdingAi) {
        // Complete each render type before switching: shared buffers can invalidate old consumers.
        var fill = context.consumers().getBuffer(RenderType.debugFilledBox());
        int budget = quiet() ? 128 : WorkshopBlueprint.MAX_GHOSTS;
        int rendered = 0;
        for (Effect effect : jobs.values()) {
            if (effect.blueprint == null) continue;
            var p = effect.packet;
            if (!withinRenderDistance(p, camera)) continue;
            double ox = effect.origin.getX() - camera.x;
            double oy = effect.origin.getY() - camera.y;
            double oz = effect.origin.getZ() - camera.z;
            double height = p.max().getY() - effect.origin.getY() + 1;
            double front = p.stage().equals("applying") ? height * effect.front(now) : -1;
            // A drawn mesh already shows placed blocks; only demolished cells still need ghosts.
            boolean meshed =
                    textured
                            && effect.textured(holdingAi)
                            && effect.mesh != null
                            && !effect.mesh.failed();
            for (var cell : effect.blueprint.ghosts()) {
                if (cell.y() + 1 < front || meshed && !cell.erase() && !cell.water()) continue;
                if (rendered++ >= budget) return;
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
                        0.12f);
            }
        }
    }

    private static void tick(Minecraft client) {
        long now = System.nanoTime();
        expire(client, now);
        if (quiet() || client.level == null || client.player == null || ++ticks % 2 != 0) return;
        for (Effect effect : jobs.values()) {
            var p = effect.packet;
            if (!p.stage().equals("applying")
                    || client.player.distanceToSqr(Vec3.atCenterOf(p.min())) > 64 * 64) continue;
            int count = Math.min(effect.sparks, 4);
            effect.sparks -= count;
            for (int i = 0; i < count; i++) {
                double x =
                        p.min().getX()
                                + (p.max().getX() - p.min().getX() + 1)
                                        * client.level.random.nextDouble();
                double z =
                        p.min().getZ()
                                + (p.max().getZ() - p.min().getZ() + 1)
                                        * client.level.random.nextDouble();
                double y =
                        p.min().getY() + (p.max().getY() - p.min().getY() + 1) * effect.front(now);
                client.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.025, 0);
            }
        }
    }
}
