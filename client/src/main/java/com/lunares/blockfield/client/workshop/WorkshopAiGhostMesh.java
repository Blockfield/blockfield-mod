package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * Real block textures for every visible planned face, baked once per plan revision into one static
 * buffer. Faces are culled against the plan and, at bake time only, against the live world.
 */
final class WorkshopAiGhostMesh {
    // ponytail: 131072 quads (~16 MB) nearest the bake-time viewer; the rest stays outline only.
    // Rebake around the camera if players need far parts of maximum plans textured.
    static final int MAX_QUADS = 131_072;
    static final long BAKE_NS = 6_000_000L;
    static final int BAKE_CELLS = 256;
    static final byte SKIP = 0, CLEAR = 1, SOLID = 2, PARTIAL = 3;
    static final WorkshopAiGhostMesh FAILED = new WorkshopAiGhostMesh(null);
    private static final Direction[] SIDES = Direction.values();
    private static final float ALPHA = 0.55f;

    private static Method irisInstance, irisShaders;
    private static boolean irisBroken;

    record Face(int x, int y, int z, Direction side, int block) {}

    @FunctionalInterface
    interface Occlusion {
        boolean solid(int x, int y, int z);
    }

    private VertexBuffer buffer;

    private WorkshopAiGhostMesh(VertexBuffer buffer) {
        this.buffer = buffer;
    }

    boolean failed() {
        return this == FAILED;
    }

    /**
     * Visible faces nearest to the focus first. {@code kinds[block]} says whether a block is drawn
     * and whether it hides neighbours; {@code world} answers for cells outside the plan.
     */
    static List<Face> faces(
            WorkshopBlueprint plan,
            byte[] kinds,
            Occlusion world,
            int fx,
            int fy,
            int fz,
            int cap) {
        PriorityQueue<Face> faces = faceQueue(fx, fy, fz);
        var entries = plan.cells().int2IntEntrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            cellFaces(plan, kinds, world, entry.getIntKey(), entry.getIntValue(), faces::add);
        }
        List<Face> visible = new ArrayList<>();
        while (!faces.isEmpty() && visible.size() < cap) visible.add(faces.remove());
        return visible;
    }

    private static PriorityQueue<Face> faceQueue(int fx, int fy, int fz) {
        return new PriorityQueue<>(
                Comparator.comparingLong((Face face) -> distance(face, fx, fy, fz))
                        .thenComparingInt(
                                face -> WorkshopBlueprint.index(face.x(), face.y(), face.z()))
                        .thenComparingInt(face -> face.side().ordinal()));
    }

    private static void cellFaces(
            WorkshopBlueprint plan,
            byte[] kinds,
            Occlusion world,
            int key,
            int block,
            Consumer<Face> add) {
        if (kinds[block] == SKIP) return;
        int x = WorkshopBlueprint.x(key),
                y = WorkshopBlueprint.y(key),
                z = WorkshopBlueprint.z(key);
        for (Direction side : SIDES) {
            int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
            int neighbour = plan.block(nx, ny, nz);
            boolean hidden =
                    neighbour < 0
                            ? world.solid(nx, ny, nz)
                            : kinds[neighbour] == SOLID
                                    || neighbour == block && kinds[block] == CLEAR;
            if (!hidden) add.accept(new Face(x, y, z, side, block));
        }
    }

    /** One step scans a cell or emits one face; both work and elapsed time bound each frame. */
    static final class BakeLoop {
        private final BooleanSupplier step;
        private boolean done;
        private boolean cancelled;

        BakeLoop(BooleanSupplier step) {
            this.step = step;
        }

        int advance(int budget, long deadline, LongSupplier clock) {
            int count = 0;
            while (!done && !cancelled && count < budget && clock.getAsLong() < deadline) {
                done = !step.getAsBoolean();
                count++;
            }
            return count;
        }

        boolean done() {
            return done;
        }

        void cancel() {
            cancelled = true;
        }
    }

    private static long distance(Face face, int x, int y, int z) {
        long dx = face.x() - x, dy = face.y() - y, dz = face.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean enabled(boolean quiet) {
        return !quiet
                && ClientConfig.USER.getBoolean("workshop.texturedPreview", true)
                && !shaderPackInUse();
    }

    /**
     * Shader packs replace the pipeline this buffer draws through; an unreadable Iris API also
     * falls back.
     */
    private static boolean shaderPackInUse() {
        if (irisBroken) return true;
        if (!FabricLoader.getInstance().isModLoaded("iris")) return false;
        try {
            if (irisShaders == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisInstance = api.getMethod("getInstance");
                irisShaders = api.getMethod("isShaderPackInUse");
            }
            return (boolean) irisShaders.invoke(irisInstance.invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unknown) {
            irisBroken = true;
            return true;
        }
    }

    static final class Bake implements AutoCloseable {
        private final Minecraft client;
        private final ClientLevel level;
        private final WorkshopBlueprint plan;
        private final BlockPos origin;
        private final BlockState[] states;
        private final java.util.Map<BlockPos, BlockState> shaped = new java.util.HashMap<>();
        private final byte[] kinds;
        private final ObjectIterator<Int2IntMap.Entry> cells;
        private final PriorityQueue<Face> faces;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private final RandomSource random = RandomSource.create(0);
        private final IntOpenHashSet started = new IntOpenHashSet();
        private final PoseStack pose = new PoseStack();
        private final BakeLoop loop = new BakeLoop(this::step);
        private ByteBufferBuilder bytes;
        private BufferBuilder builder;
        private WorkshopAiGhostMesh result;
        private int palette, quads;

        Bake(Minecraft client, WorkshopBlueprint plan, BlockPos origin, BlockPos viewer) {
            this.client = client;
            this.level = client.level;
            this.plan = plan;
            this.origin = origin;
            states = new BlockState[plan.blocks().size()];
            kinds = new byte[states.length];
            cells = plan.cells().int2IntEntrySet().iterator();
            int fx = viewer.getX() - origin.getX(),
                    fy = viewer.getY() - origin.getY(),
                    fz = viewer.getZ() - origin.getZ();
            // Heap insertion and extraction avoid a whole-plan sort on any one frame.
            faces = faceQueue(fx, fy, fz);
        }

        WorkshopAiGhostMesh advance(long deadline) {
            loop.advance(BAKE_CELLS, deadline, System::nanoTime);
            return loop.done() ? result : null;
        }

        private boolean step() {
            if (palette < states.length) {
                int i = palette++;
                BlockState state =
                        plan.erase(i)
                                        || !BuiltInRegistries.BLOCK.containsKey(
                                                ResourceLocation.parse(
                                                        com.lunares.blockfield.workshop
                                                                .WorkshopBlockStates.id(
                                                                plan.blocks().get(i))))
                                ? null
                                : com.lunares.blockfield.workshop.WorkshopBlockStates.parse(
                                        plan.blocks().get(i));
                if (state != null && state.getRenderShape() == RenderShape.MODEL) {
                    states[i] = state;
                    kinds[i] =
                            state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                                    ? SOLID
                                    : state.isCollisionShapeFullBlock(
                                                    EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                                            ? CLEAR
                                            : PARTIAL;
                }
                return true;
            }
            if (cells.hasNext()) {
                var entry = cells.next();
                cellFaces(
                        plan,
                        kinds,
                        (x, y, z) -> {
                            at.setWithOffset(origin, x, y, z);
                            return level.getBlockState(at).isSolidRender(level, at);
                        },
                        entry.getIntKey(),
                        entry.getIntValue(),
                        faces::add);
                return true;
            }
            if (!faces.isEmpty() && quads < MAX_QUADS) {
                if (bytes == null) {
                    bytes = new ByteBufferBuilder(256 * 1024);
                    builder =
                            new BufferBuilder(
                                    bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                }
                Face face = faces.remove();
                BlockState state = states[face.block()];
                state = stateAt(origin.offset(face.x(), face.y(), face.z()));
                at.setWithOffset(origin, face.x(), face.y(), face.z());
                var model = client.getBlockRenderer().getBlockModel(state);
                boolean replace = !level.getBlockState(at).isAir();
                pose.pushPose();
                pose.translate(face.x(), face.y(), face.z());
                random.setSeed(state.getSeed(at));
                emit(state, model.getQuads(state, face.side(), random), replace);
                if (started.add(WorkshopBlueprint.index(face.x(), face.y(), face.z()))) {
                    random.setSeed(state.getSeed(at));
                    emit(state, model.getQuads(state, null, random), replace);
                }
                pose.popPose();
                return true;
            }
            MeshData mesh = builder == null ? null : builder.build();
            if (mesh == null) result = new WorkshopAiGhostMesh(null);
            else {
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                try {
                    buffer.bind();
                    buffer.upload(mesh);
                    result = new WorkshopAiGhostMesh(buffer);
                } catch (RuntimeException | OutOfMemoryError failure) {
                    buffer.close();
                    throw failure;
                } finally {
                    VertexBuffer.unbind();
                }
            }
            close();
            return false;
        }

        private BlockState stateAt(BlockPos pos) {
            int index =
                    plan.block(
                            pos.getX() - origin.getX(),
                            pos.getY() - origin.getY(),
                            pos.getZ() - origin.getZ());
            if (index < 0) return level.getBlockState(pos);
            BlockState state = states[index];
            if (state == null)
                return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            if (!com.lunares.blockfield.workshop.WorkshopBlockStates.connects(state)) return state;
            BlockState cached = shaped.get(pos);
            if (cached != null) return cached;
            // Stair/fence queries use raw neighbours; only a wall's upper shape is recursive.
            var view =
                    com.lunares.blockfield.workshop.WorkshopBlockStates.view(
                            level,
                            next -> {
                                if (next.equals(pos.above())) return stateAt(next);
                                int i =
                                        plan.block(
                                                next.getX() - origin.getX(),
                                                next.getY() - origin.getY(),
                                                next.getZ() - origin.getZ());
                                return i < 0
                                        ? level.getBlockState(next)
                                        : states[i] == null
                                                ? net.minecraft.world.level.block.Blocks.AIR
                                                        .defaultBlockState()
                                                : states[i];
                            });
            BlockState result =
                    com.lunares.blockfield.workshop.WorkshopBlockStates.shape(state, view, pos);
            shaped.put(pos.immutable(), result);
            return result;
        }

        private void emit(BlockState state, List<BakedQuad> group, boolean replace) {
            for (BakedQuad quad : group) {
                if (quads >= MAX_QUADS) break;
                int color =
                        quad.isTinted()
                                ? client.getBlockColors()
                                        .getColor(state, level, at, quad.getTintIndex())
                                : -1;
                float shade = level.getShade(quad.getDirection(), quad.isShade());
                builder.putBulkData(
                        pose.last(),
                        quad,
                        (color >> 16 & 255) / 255f * shade,
                        (color >> 8 & 255) / 255f * shade * (replace ? 0.75f : 1),
                        (color & 255) / 255f * shade * (replace ? 0.35f : 1),
                        ALPHA,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
                quads++;
            }
        }

        @Override
        public void close() {
            loop.cancel();
            faces.clear();
            if (bytes != null) {
                bytes.close();
                bytes = null;
            }
        }
    }

    /**
     * {@code pose} is the event's camera-relative pose; {@code ox..oz} the plan origin minus
     * camera.
     */
    void draw(Minecraft client, Matrix4f pose, double ox, double oy, double oz) {
        if (buffer == null) return;
        var shader = GameRenderer.getRendertypeTranslucentShader();
        if (shader == null) return;
        // Not RenderType.translucent(): under Fabulous its target is cleared after AFTER_ENTITIES.
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        client.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(false);
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1f, -2f);
        // Sodium never resets the vanilla terrain shader's per-section offset.
        if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0f, 0f, 0f);
        buffer.bind();
        // Vanilla already pushed the view rotation onto the model-view stack before entities.
        buffer.drawWithShader(
                new Matrix4f(RenderSystem.getModelViewMatrix())
                        .mul(pose)
                        .translate((float) ox, (float) oy, (float) oz),
                RenderSystem.getProjectionMatrix(),
                shader);
        VertexBuffer.unbind();
        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        client.gameRenderer.lightTexture().turnOffLightLayer();
    }

    void close() {
        VertexBuffer closing = buffer;
        buffer = null;
        if (closing == null) return;
        if (RenderSystem.isOnRenderThread()) closing.close();
        else RenderSystem.recordRenderCall(closing::close);
    }
}
