package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.mixin.ClientLevelEntitiesAccessor;
import com.lunares.blockfield.client.mixin.DisplayAccessor;
import com.lunares.blockfield.client.mixin.EntityLevelCallbackAccessor;
import com.lunares.blockfield.client.mixin.EntitySectionManagerAccessor;
import com.lunares.blockfield.client.mixin.SectionCallbackAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Static block_display and text_display of the maps, baked into vertex buffers per 16x16x16
 * section.
 *
 * <p>Vanilla renders every display entity every frame through EntityRenderDispatcher: model lookup,
 * lighting and vertex building per block. Overpass is built from ~14k block displays and ~3k texts;
 * on the rig its spawn view ran at 135 FPS with them and 564 without. Here each static display is
 * rendered once, by its own vanilla renderer (biome tint of BlockDisplayBiomeTintMixin included),
 * into a VertexBuffer of its section; frames draw those buffers and EntityRenderDispatcher skips
 * the baked entities (DisplayBatchHideMixin).
 *
 * <p>Only what cannot change on screen is baked: billboard FIXED, no transformation interpolation
 * running, no glow, entity data unchanged for 2 s (its arrival is no change). A change of data
 * (DisplayChangeMixin), position, rotation or light hands the whole section back to vanilla until
 * it is rebuilt. When its displays leave (a respawn, an unloaded chunk) the section's buffers are
 * parked and taken up again if the same displays return unchanged. Of item_display only heads are
 * baked (225 on Overpass), once their profile and skin have loaded; the rest stays vanilla (flags
 * and signs of ItemDisplayBlockEntityMixin animate). Under an Iris shader pack the buffers are
 * baked in its extended vertex format, drawn with the pack's entity programs and, through
 * IrisShadowDisplaysMixin, into its shadow map; a new pack rebakes them. {@code /bfdisplays on|off}
 * toggles it and prints counts; {@code -Dblockfield.displayBatching=false} starts it off.
 */
public final class DisplayBatcher {
    private static final int STABLE_TICKS = 40;
    private static final int SCAN_EVERY = 10;
    private static final int REBUILDS_PER_FRAME = 4;

    private static boolean enabled =
            !"false".equals(System.getProperty("blockfield.displayBatching"));
    private static ClientLevel level;
    private static long ticks;
    private static boolean invalidateAll;
    private static GraphicsStatus graphics;

    /** Iris shader pack the buffers were baked for: its vertex format and entity ids. */
    private static Object pack;

    /** Displays that may be baked -> tick of their last change. */
    private static final Map<Display, Long> candidates = new IdentityHashMap<>();

    /**
     * Not bakeable as of their last change (billboard, interpolation...): not retried until they
     * change again.
     */
    private static final Set<Display> rejected = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Baked display -> what it was baked with; its section key is in the snapshot. */
    private static final Map<Display, Snapshot> baked = new IdentityHashMap<>();

    /** Matching arrivals hidden briefly while the rest of their parked section is validated. */
    private static final Map<Display, Long> returning = new IdentityHashMap<>();

    private static final Map<Long, Section> sections = new HashMap<>();

    /** Whether the batch can fog a RenderType's shader (DisplayBatchFog), by type. */
    private static final Map<RenderType, Boolean> fogged = new HashMap<>();

    private static final Set<Long> dirty = new LinkedHashSet<>();

    /**
     * Sections whose displays left with their level or chunk, by dimension, kept for their return.
     * Every spawn on a map is a trip through the respawn dimension: the server sends all ~16k
     * displays of Overpass again as new entities, and baking them anew took seconds at 1-10 FPS.
     */
    private static final Map<ResourceKey<Level>, Map<Long, Section>> parked = new LinkedHashMap<>();

    /** Tick of the latest display arrival. */
    private static long lastArrival;

    /**
     * Light read in the first seconds after displays arrive is not theirs yet: the client applies
     * the light of new chunks over several frames. A parked section keeps the light it was baked
     * with, and a light check before then would only bake it again with the wrong one.
     */
    private static boolean settled() {
        return ticks - lastArrival >= STABLE_TICKS;
    }

    public static boolean restoringArrivals() {
        Minecraft client = Minecraft.getInstance();
        return active()
                && level != null
                && client.level == level
                && ticks - lastArrival <= 1
                && (!sections.isEmpty() || parked.containsKey(level.dimension()));
    }

    /** The map and the respawn room; an older dimension is dropped. */
    private static final int PARKED_DIMENSIONS = 2;

    private record Snapshot(
            long section, double x, double y, double z, float yRot, float xRot, int light) {}

    /** What a baked display looked like, to recognise it when the server sends it again. */
    private record Member(Snapshot snapshot, List<SynchedEntityData.DataValue<?>> data) {
        boolean matches(Display d) {
            return snapshot.x() == d.getX()
                    && snapshot.y() == d.getY()
                    && snapshot.z() == d.getZ()
                    && snapshot.yRot() == d.getYRot()
                    && snapshot.xRot() == d.getXRot()
                    && sameData(data, d.getEntityData().getNonDefaultValues());
        }
    }

    static boolean sameData(
            List<SynchedEntityData.DataValue<?>> a, List<SynchedEntityData.DataValue<?>> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i);
            var y = b.get(i);
            if (x.id() != y.id()) return false;
            if (!sameValue(x.value(), y.value())) return false;
        }
        return true;
    }

    /**
     * Rotations and scales come back from the server's save and load of the map a few ulps off (or
     * as -0); ItemStack has no value equality.
     */
    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Quaternionf q) return b instanceof Quaternionf r && q.equals(r, 1e-5f);
        if (a instanceof Vector3f v) return b instanceof Vector3f w && v.equals(w, 1e-5f);
        if (a instanceof ItemStack s) return b instanceof ItemStack t && ItemStack.matches(s, t);
        return Objects.equals(a, b);
    }

    private static final class Section {
        final long key;
        final double originX, originY, originZ;
        final Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
        final List<Display> members = new ArrayList<>();

        /**
         * UUID -> baked member, and those the bake left to vanilla on their own. Not the entity id:
         * the server reloads the map's entities while no player is there, under new ids.
         */
        final Map<UUID, Member> kept = new HashMap<>();

        final Set<UUID> skipped = new HashSet<>();

        long parkedAt;
        AABB bounds;
        double range;

        Section(long key) {
            this.key = key;
            originX = SectionPos.sectionToBlockCoord(SectionPos.x(key));
            originY = SectionPos.sectionToBlockCoord(SectionPos.y(key));
            originZ = SectionPos.sectionToBlockCoord(SectionPos.z(key));
        }

        void free() {
            buffers.values().forEach(VertexBuffer::close);
            buffers.clear();
        }
    }

    private DisplayBatcher() {}

    public static void register() {
        ClientEntityEvents.ENTITY_LOAD.register(DisplayBatcher::onLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> onUnload(entity));
        ClientTickEvents.START_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> reset(false)));
        WorldRenderEvents.AFTER_ENTITIES.register(DisplayBatcher::render);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public ResourceLocation getFabricId() {
                                return ResourceLocation.fromNamespaceAndPath(
                                        "blockfield", "display_batcher");
                            }

                            @Override
                            public void onResourceManagerReload(ResourceManager manager) {
                                invalidateAll =
                                        true; // atlas and font UVs baked into the buffers are stale
                            }
                        });
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registry) ->
                        dispatcher.register(
                                ClientCommandManager.literal("bfdisplays")
                                        .executes(
                                                c -> {
                                                    c.getSource()
                                                            .sendFeedback(
                                                                    Component.literal(stats()));
                                                    return 1;
                                                })
                                        .then(
                                                ClientCommandManager.literal("on")
                                                        .executes(c -> toggle(c.getSource(), true)))
                                        .then(
                                                ClientCommandManager.literal("off")
                                                        .executes(
                                                                c ->
                                                                        toggle(
                                                                                c.getSource(),
                                                                                false)))));
    }

    private static int toggle(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
            boolean on) {
        enabled = on;
        invalidateAll = true;
        source.sendFeedback(Component.literal(stats()));
        return 1;
    }

    private static String stats() {
        int buffers = sections.values().stream().mapToInt(s -> s.buffers.size()).sum();
        return "displays: "
                + (active() ? "baked" : "vanilla")
                + ", candidates "
                + candidates.size()
                + ", baked "
                + baked.size()
                + " in "
                + sections.size()
                + " sections ("
                + buffers
                + " buffers), rejected "
                + rejected.size()
                + ", parked "
                + parked.values().stream().mapToInt(Map::size).sum()
                + " sections ("
                + adopted
                + " taken up)";
    }

    /** Parked sections taken up since the start, for {@code /bfdisplays}. */
    private static int adopted;

    /**
     * Set on Display by DisplayChangeMixin: read per entity per frame, cheaper than a map lookup.
     */
    public interface Flag {
        boolean blockfield$baked();

        void blockfield$setBaked(boolean baked);
    }

    /** DisplayBatchHideMixin: baked geometry, or a bounded gap while its section returns. */
    public static boolean hides(Entity entity) {
        if (entity instanceof Flag flag && flag.blockfield$baked()) return true;
        if (!(entity instanceof Display display)) return false;
        Long key = returning.get(display);
        if (key == null
                || !active()
                || level == null
                || display.level() != level
                || stable(display)) return false;
        Map<Long, Section> here = parked.get(level.dimension());
        return here != null && here.containsKey(key);
    }

    /**
     * DisplayChangeMixin: the server moved or turned a display; a detached one does not tick, so
     * hand it back first.
     */
    public static void moved(
            Display display, double x, double y, double z, float yRot, float xRot) {
        if (hides(display)
                && (x != display.getX()
                        || y != display.getY()
                        || z != display.getZ()
                        || yRot != display.getYRot()
                        || xRot != display.getXRot())) {
            changed(display);
        }
    }

    // ------------------------------------------------------------------ detached

    /**
     * A baked display tree (a display riding nothing and the displays riding it, all baked) is also
     * taken out of the level's entity lookup, its sections and the tick list. Hiding it from
     * rendering is not enough: with ~17k of them on Overpass, every loop over the level's entities
     * (ticking, the render list Iris sorts, section queries of SuperbWarfare and damage indicators
     * every frame or tick) cost more than the rest of the frame. Nothing about it changes while it
     * is detached: a packet addressing it (ClientLevel.getEntity/removeEntity, DisplayDetachMixin)
     * puts its tree back first, and anything that unbakes one of them does too.
     */
    private static final Int2ObjectOpenHashMap<Display> detached = new Int2ObjectOpenHashMap<>();

    /**
     * Detaches the display's whole riding tree once every display in it is baked (riders follow
     * their vehicle).
     */
    private static void detach(Display member) {
        if (!(member.getRootVehicle() instanceof Display root)
                || detached.containsKey(root.getId())) return;
        List<Display> tree = new ArrayList<>();
        if (!collect(root, tree)) return;
        for (Display d : tree) {
            if (sectionCallback(d) == null) return;
        }
        ClientLevelEntitiesAccessor world = (ClientLevelEntitiesAccessor) root.level();
        EntitySectionManagerAccessor storage =
                (EntitySectionManagerAccessor) world.blockfield$entityStorage();
        for (Display d : tree) {
            storage.blockfield$lookup().remove(d);
            sectionCallback(d).blockfield$section().remove(d);
            world.blockfield$tickingEntities().remove(d);
            detached.put(d.getId(), d);
        }
    }

    private static boolean collect(Entity entity, List<Display> tree) {
        if (!(entity instanceof Display d) || !baked.containsKey(d) || d.isRemoved()) return false;
        tree.add(d);
        for (Entity passenger : d.getPassengers()) {
            if (!collect(passenger, tree)) return false;
        }
        return true;
    }

    /**
     * Puts a detached tree back where vanilla keeps it; it stays baked until something unbakes it.
     */
    private static void attach(Display member) {
        if (!detached.containsKey(member.getId())) return;
        Entity root = member.getRootVehicle();
        List<Display> tree = new ArrayList<>();
        tree.add((Display) root);
        for (Entity passenger : root.getIndirectPassengers()) tree.add((Display) passenger);
        for (Display d : tree) attachOne(d);
    }

    /**
     * Puts a detached display back where vanilla keeps it; it stays baked until something unbakes
     * it.
     */
    private static void attachOne(Display d) {
        if (detached.remove(d.getId()) == null) return;
        ClientLevelEntitiesAccessor world = (ClientLevelEntitiesAccessor) d.level();
        EntitySectionManagerAccessor storage =
                (EntitySectionManagerAccessor) world.blockfield$entityStorage();
        storage.blockfield$lookup().add(d);
        SectionCallbackAccessor callback = sectionCallback(d);
        // Its old section may have been dropped as empty meanwhile: take the live one for that
        // position.
        EntitySection<Entity> section =
                storage.blockfield$sections().getOrCreateSection(callback.blockfield$sectionKey());
        callback.blockfield$setSection(section);
        section.add(d);
        if (section.getStatus().isTicking()) world.blockfield$tickingEntities().add(d);
    }

    private static final Map<Class<?>, Field> WRAPPED = new HashMap<>();

    /**
     * The client section callback of an entity, under wrappers other mods put around it
     * (Architectury).
     */
    private static SectionCallbackAccessor sectionCallback(Entity entity) {
        Object callback = ((EntityLevelCallbackAccessor) entity).blockfield$levelCallback();
        for (int depth = 0; depth < 4 && callback != null; depth++) {
            if (callback instanceof SectionCallbackAccessor section) return section;
            Field inner =
                    WRAPPED.computeIfAbsent(
                            callback.getClass(),
                            c -> {
                                for (Field f : c.getDeclaredFields()) {
                                    if (EntityInLevelCallback.class.isAssignableFrom(f.getType())
                                            && !Modifier.isStatic(f.getModifiers())) {
                                        f.setAccessible(true);
                                        return f;
                                    }
                                }
                                return null;
                            });
            try {
                callback = inner == null ? null : inner.get(callback);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    /** DisplayDetachMixin: a packet addresses this id. */
    public static Entity attach(int id) {
        Display d = detached.get(id);
        if (d != null) attach(d);
        return d;
    }

    private static void bakedPut(Display display, Snapshot snapshot) {
        returning.remove(display);
        baked.put(display, snapshot);
        ((Flag) display).blockfield$setBaked(true);
    }

    private static void bakedRemove(Display display) {
        if (baked.remove(display) != null) ((Flag) display).blockfield$setBaked(false);
        attach(display);
    }

    /** DisplayChangeMixin: synced data arrived (text, block, transformation, brightness...). */
    public static void changed(Display display) {
        returning.remove(display);
        DisplayLight.invalidate(display);
        if (!candidates.containsKey(display)) return;
        candidates.put(display, ticks);
        rejected.remove(display);
        Snapshot snapshot = baked.get(display);
        if (snapshot != null) unbake(snapshot.section());
    }

    private static boolean kind(Entity entity) {
        return entity instanceof Display.BlockDisplay
                || entity instanceof Display.TextDisplay
                || entity instanceof Display.ItemDisplay;
    }

    /** A head item display, which may be baked once {@link #skinLoaded} holds. */
    private static boolean head(Display.ItemDisplay display) {
        var state = display.itemRenderState();
        return state != null
                && state.itemStack().getItem() instanceof BlockItem item
                && item.getBlock() instanceof AbstractSkullBlock;
    }

    /**
     * A player head draws the default skin until its profile resolves and its skin downloads; baked
     * earlier, it would keep that skin.
     */
    private static boolean skinLoaded(Display d) {
        if (!(d instanceof Display.ItemDisplay item) || !head(item)) return true;
        ResolvableProfile profile = item.itemRenderState().itemStack().get(DataComponents.PROFILE);
        return profile == null
                || profile.isResolved()
                        && Minecraft.getInstance()
                                .getSkinManager()
                                .getOrLoad(profile.gameProfile())
                                .isDone();
    }

    private static void onLoad(Entity entity, ClientLevel world) {
        if (world != level) reset(true);
        level = world;
        if (kind(entity)) {
            candidates.put((Display) entity, ticks);
            lastArrival = ticks;
        }
    }

    private static void onUnload(Entity entity) {
        if (!(entity instanceof Display display)) return;
        returning.remove(display);
        if (candidates.remove(display) == null) return;
        rejected.remove(display);
        Snapshot snapshot = baked.get(display);
        if (snapshot != null) unbake(snapshot.section(), true);
    }

    /**
     * Hands the section's members back to vanilla and frees its buffers, or parks them ({@code
     * keep}) for the same displays coming back; it is rebuilt when they are stable.
     */
    private static void unbake(long key, boolean keep) {
        Section section = sections.remove(key);
        if (section == null) return;
        if (keep) park(section);
        else section.free();
        for (Display member : section.members) bakedRemove(member);
        if (keep) section.members.clear();
        dirty.add(key);
    }

    private static void unbake(long key) {
        unbake(key, false);
    }

    private static void park(Section section) {
        Map<Long, Section> here = parked.remove(level.dimension());
        if (here == null) here = new HashMap<>();
        parked.put(level.dimension(), here); // most recent last
        section.parkedAt = ticks;
        Section old = here.put(section.key, section);
        if (old != null) old.free();
        while (parked.size() > PARKED_DIMENSIONS) {
            Iterator<Map<Long, Section>> eldest = parked.values().iterator();
            eldest.next().values().forEach(Section::free);
            eldest.remove();
        }
    }

    private static void freeParked() {
        parked.values().forEach(here -> here.values().forEach(Section::free));
        parked.clear();
        returning.clear();
    }

    /** {@code keep}: the level is replaced (a respawn, another map) rather than left. */
    private static void reset(boolean keep) {
        fogged.clear();
        for (Section section : sections.values()) {
            if (keep && level != null) park(section);
            else section.free();
        }
        if (!keep) freeParked();
        for (Display display : baked.keySet()) ((Flag) display).blockfield$setBaked(false);
        baked.clear();
        detached.clear();
        returning.clear();
        for (Section section : sections.values()) section.members.clear();
        sections.clear();
        candidates.clear();
        rejected.clear();
        dirty.clear();
        level = null;
    }

    private static void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (level != null) reset(false);
            return;
        }
        if (graphics != mc.options.graphicsMode().get()) {
            graphics = mc.options.graphicsMode().get(); // leaves switch between cutout and solid
            invalidateAll = true;
        }
        if (!active()) return;
        // Arrivals are scheduled at once: their parked section is taken up before vanilla has to
        // draw thousands of them. Baking anew still waits until they are stable.
        boolean arrived = ticks - lastArrival <= 1;
        if (ticks % SCAN_EVERY == 0) unbakeStale();
        else if (!arrived) return;
        schedule(arrived);
        if (arrived && !invalidateAll && irisPack() == pack) rebuild(0);
    }

    /** Baked ones that moved, turned or got other light go back to vanilla until rebuilt. */
    private static void unbakeStale() {
        List<Long> stale = new ArrayList<>();
        for (Map.Entry<Display, Snapshot> e : baked.entrySet()) {
            Display d = e.getKey();
            Snapshot s = e.getValue();
            if (d.isRemoved()
                    || d.getX() != s.x()
                    || d.getY() != s.y()
                    || d.getZ() != s.z()
                    || d.getYRot() != s.yRot()
                    || d.getXRot() != s.xRot()
                    || settled() && light(d) != s.light()) {
                stale.add(s.section());
            }
        }
        stale.forEach(DisplayBatcher::unbake);
    }

    /** Sections of stable displays (all with {@code arrived}) not yet baked, not rejected. */
    private static void schedule(boolean arrived) {
        for (Map.Entry<Display, Long> e : candidates.entrySet()) {
            Display d = e.getKey();
            if ((arrived || stable(d)) && !baked.containsKey(d) && !rejected.contains(d)) {
                dirty.add(sectionKey(d));
            }
        }
    }

    private static long sectionKey(Display display) {
        return SectionPos.asLong(display.blockPosition());
    }

    private static boolean active() {
        return enabled;
    }

    /** Bakeable but for its 2 s of stability ({@link #stable}). */
    private static boolean drawable(Display d) {
        if (d instanceof Display.BlockDisplay block) {
            var blockState = block.blockRenderState();
            // ponytail: translucent blocks stay vanilla; add camera-dependent sorting only if
            // their measured render cost justifies it.
            if (blockState == null
                    || ItemBlockRenderTypes.getChunkRenderType(blockState.blockState())
                            == RenderType.translucent()) return false;
        }
        if (d instanceof Display.ItemDisplay item && !head(item)) return false;
        Display.RenderState state = d.renderState();
        return state != null
                && !d.isRemoved()
                && state.billboardConstraints() == Display.BillboardConstraints.FIXED
                && d.calculateInterpolationProgress(1.0f) >= 1.0f
                && state.glowColorOverride() == -1
                && !d.isCurrentlyGlowing();
    }

    private static boolean stable(Display d) {
        return ticks - candidates.getOrDefault(d, ticks) >= STABLE_TICKS;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int light(Display d) {
        EntityRenderer renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(d);
        return renderer.getPackedLightCoords(d, 1.0f);
    }

    private static void render(WorldRenderContext context) {
        Object current = irisPack();
        if (current != pack) {
            pack = current;
            invalidateAll = true;
        }
        if (!active()) {
            if (!sections.isEmpty() || !baked.isEmpty() || !parked.isEmpty()) {
                for (Long key : new ArrayList<>(sections.keySet())) unbake(key);
                freeParked();
                dirty.clear();
            }
            return;
        }
        if (invalidateAll) {
            invalidateAll = false;
            for (Long key : new ArrayList<>(sections.keySet())) unbake(key);
            freeParked();
            rejected.clear();
        }
        rebuild(REBUILDS_PER_FRAME);
        draw(context);
    }

    /**
     * Takes up parked sections and bakes up to {@code bakes} others. The tick takes them up too (no
     * GL needed): before the frame, which would otherwise draw and query thousands of arrivals.
     */
    private static void rebuild(int bakes) {
        if (dirty.isEmpty()) return;
        Map<Long, List<Display>> wanted = new LinkedHashMap<>();
        for (Long key : dirty) wanted.put(key, new ArrayList<>());
        for (Display d : candidates.keySet()) {
            List<Display> list = wanted.get(sectionKey(d));
            if (list != null) list.add(d);
        }
        for (Map.Entry<Long, List<Display>> e : wanted.entrySet()) {
            long key = e.getKey();
            if (adopt(key, e.getValue())) {
                dirty.remove(key);
            } else if (e.getValue().stream().noneMatch(DisplayBatcher::stable)) {
                dirty.remove(key); // arrived with nothing parked for it: baked once stable
            } else if (bakes > 0) {
                bakes--;
                dirty.remove(key);
                bake(key, e.getValue());
            }
        }
    }

    /**
     * Takes up the parked section of {@code key} if its displays are back unchanged. A section
     * whose displays have not arrived yet stays parked; a changed one is freed and baked anew.
     */
    private static boolean adopt(long key, List<Display> displays) {
        Map<Long, Section> here = parked.get(level.dimension());
        Section section = here == null ? null : here.get(key);
        if (section == null) return false;
        if (displays.isEmpty()) return true;
        List<Display> members = new ArrayList<>();
        List<Display> skipped = new ArrayList<>();
        Map<Display, Snapshot> snapshots = new IdentityHashMap<>();
        boolean pending = false;
        for (Display d : displays) {
            Member member = section.kept.get(d.getUUID());
            // Matching metadata was already baked; detach before the first entity tick builds
            // thousands of render states and transformation matrices again.
            if (member == null && !drawable(d)) continue;
            if (member == null && section.skipped.contains(d.getUUID())) {
                skipped.add(d);
                continue;
            }
            if (d.isRemoved() || member == null || !member.matches(d)) {
                returning.remove(d);
                // Head profile metadata may still be resolving after the spawn packets arrive.
                if (!d.isRemoved() && member != null && !stable(d)) {
                    pending = true;
                    continue;
                }
                here.remove(key);
                section.free();
                return false;
            }
            members.add(d);
            snapshots.put(d, member.snapshot());
        }
        if (pending || members.size() != section.kept.size()) {
            // The server removes and sends displays a packet at a time over several frames:
            // a section half gone or half back waits for the rest instead of a bake of the half.
            if (pending
                    || members.size() < section.kept.size()
                            && (ticks - section.parkedAt < STABLE_TICKS || !settled())) {
                for (Display member : members) returning.put(member, key);
                return true;
            }
            here.remove(key);
            section.free();
            return false;
        }
        here.remove(key);
        section.members.clear();
        section.members.addAll(members);
        rejected.addAll(skipped);
        snapshots.forEach(DisplayBatcher::bakedPut);
        sections.put(key, section);
        for (Display member : members) detach(member);
        adopted++;
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bake(long key, List<Display> displays) {
        Section old = sections.remove(key);
        if (old != null) {
            old.free();
            for (Display member : old.members) bakedRemove(member);
        }
        Section section = new Section(key);
        Capture capture = new Capture();
        Types types = new Types();
        PoseStack pose = new PoseStack();
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        double range = 0;
        for (Display d : displays) {
            // Not rejected: the section is scheduled again until the skin is there.
            if (!skinLoaded(d)) continue;
            if (!stable(d)) continue;
            // Adopted arrivals can be detached before their first tick; unbaking resumes it.
            if (d.renderState() == null) continue;
            if (!drawable(d)) {
                rejected.add(d);
                continue;
            }
            EntityRenderer renderer = dispatcher.getRenderer(d);
            int light = renderer.getPackedLightCoords(d, 1.0f);
            pose.pushPose();
            pose.translate(
                    d.getX() - section.originX,
                    d.getY() - section.originY,
                    d.getZ() - section.originZ);
            // A display drawing with a shader the batch cannot fog stays vanilla on its own; it
            // used to reject its whole section (two thirds of Overpass).
            if (!shaderPack()) {
                types.used.clear();
                renderer.render(d, d.getYRot(), 1.0f, pose, types, light);
                if (!types.used.stream().allMatch(DisplayBatcher::fogged)) {
                    pose.popPose();
                    rejected.add(d);
                    section.skipped.add(d.getUUID());
                    continue;
                }
            }
            renderer.render(d, d.getYRot(), 1.0f, pose, capture, light);
            pose.popPose();
            section.members.add(d);
            Snapshot snapshot =
                    new Snapshot(
                            key, d.getX(), d.getY(), d.getZ(), d.getYRot(), d.getXRot(), light);
            bakedPut(d, snapshot);
            section.kept.put(
                    d.getUUID(), new Member(snapshot, d.getEntityData().getNonDefaultValues()));
            range = Math.max(range, ((DisplayAccessor) d).blockfield$viewRange());
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Map.Entry<RenderType, BufferBuilder> e : capture.builders.entrySet()) {
            MeshData mesh = e.getValue().build();
            if (mesh == null) continue;
            ByteBuffer vertices = mesh.vertexBuffer();
            int stride = mesh.drawState().format().getVertexSize();
            int offset = mesh.drawState().format().getOffset(VertexFormatElement.POSITION);
            for (int i = 0; i < mesh.drawState().vertexCount(); i++) {
                int at = i * stride + offset;
                float x = vertices.getFloat(at),
                        y = vertices.getFloat(at + 4),
                        z = vertices.getFloat(at + 8);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            section.buffers.put(e.getKey(), buffer);
        }
        VertexBuffer.unbind();
        capture.close();
        if (section.buffers.isEmpty()) {
            for (Display member : section.members) bakedRemove(member);
            return;
        }
        section.bounds =
                new AABB(minX, minY, minZ, maxX, maxY, maxZ)
                        .move(section.originX, section.originY, section.originZ);
        section.range = range * 64.0;
        sections.put(key, section);
        for (Display member : section.members) detach(member);
    }

    private static void draw(WorldRenderContext context) {
        Vec3 camera = context.camera().getPosition();
        drawSections(
                context.positionMatrix(),
                context.projectionMatrix(),
                context.frustum(),
                camera.x,
                camera.y,
                camera.z,
                false);
    }

    /**
     * IrisShadowDisplaysMixin: the pack's shadow entity pass, where the hidden and detached baked
     * displays would otherwise cast no shadow. Iris has bound its shadow target and programs.
     */
    public static void drawShadow(
            Matrix4f modelView,
            Matrix4f projection,
            Frustum frustum,
            double x,
            double y,
            double z) {
        if (active() && pack != null) drawSections(modelView, projection, frustum, x, y, z, true);
    }

    /**
     * {@code shadow}: Iris draws its whole shadow pass without face culling; its shadow distortion,
     * centred on the player, flips the winding of faces as the player moves. A no-cull RenderType
     * turns culling back on when cleared, which culled our faces (and what Iris drew after them) by
     * where the player stood: shadows under the Overpass highway moved with the player.
     */
    private static void drawSections(
            Matrix4f base,
            Matrix4f projection,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ,
            boolean shadow) {
        double scale = Entity.getViewScale();
        Map<RenderType, List<Section>> byType = new LinkedHashMap<>();
        List<RenderType> translucent = new ArrayList<>();
        for (Section section : sections.values()) {
            double reach = section.range * scale;
            double dx =
                    Math.max(
                            0,
                            Math.max(section.bounds.minX - cameraX, cameraX - section.bounds.maxX));
            double dy =
                    Math.max(
                            0,
                            Math.max(section.bounds.minY - cameraY, cameraY - section.bounds.maxY));
            double dz =
                    Math.max(
                            0,
                            Math.max(section.bounds.minZ - cameraZ, cameraZ - section.bounds.maxZ));
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            if (frustum != null && !frustum.isVisible(section.bounds)) continue;
            for (RenderType type : section.buffers.keySet()) {
                byType.computeIfAbsent(
                                type,
                                t -> {
                                    if (t.sortOnUpload()) translucent.add(t);
                                    return new ArrayList<>();
                                })
                        .add(section);
            }
        }
        if (byType.isEmpty()) return;
        List<RenderType> order = new ArrayList<>(byType.keySet());
        order.removeAll(translucent);
        order.addAll(translucent); // blended layers after the opaque ones, as vanilla batches them
        boolean iris = pack != null;
        for (RenderType type : order) {
            type.setupRenderState();
            if (shadow) RenderSystem.disableCull();
            ShaderInstance shader = RenderSystem.getShader();
            // Iris substitutes its ExtendedShader only while it renders the level; a vanilla
            // program here means it does not, and the buffer's Iris format would not match it.
            boolean usable = iris ? irisShader(shader) : supportsFog(shader);
            if (usable) {
                for (Section section : byType.get(type)) {
                    if (sections.get(section.key) != section) continue;
                    float x = (float) (section.originX - cameraX),
                            y = (float) (section.originY - cameraY),
                            z = (float) (section.originZ - cameraZ);
                    boolean chunkOffset = iris && shader.CHUNK_OFFSET != null;
                    Matrix4f modelView = batchModelView(base, x, y, z, chunkOffset);
                    VertexBuffer buffer = section.buffers.get(type);
                    buffer.bind();
                    shader.setDefaultUniforms(
                            type.mode(),
                            modelView,
                            projection,
                            Minecraft.getInstance().getWindow());
                    // Iris reconstructs world positions with ModelViewMatInverse. Keep that
                    // matrix camera-only and translate section-local vertices through its offset.
                    if (chunkOffset) shader.CHUNK_OFFSET.set(x, y, z);
                    // Iris programs fog from the model-view position themselves.
                    int fogShape = iris ? -1 : RenderSystem.getShaderFogShape().getIndex();
                    if (!iris) shader.FOG_SHAPE.set(fogShape + 2);
                    try {
                        shader.apply();
                        buffer.draw();
                    } finally {
                        // setDefaultUniforms does not clear the offset for later entity draws.
                        if (chunkOffset) shader.CHUNK_OFFSET.set(0f, 0f, 0f);
                        if (!iris) shader.FOG_SHAPE.set(fogShape);
                        shader.clear();
                    }
                }
            } else if (!iris) {
                for (Section section : byType.get(type)) {
                    if (sections.get(section.key) != section) continue;
                    unbake(section.key);
                    rejected.addAll(section.members);
                    dirty.remove(section.key);
                }
            }
            type.clearRenderState();
            if (shadow) RenderSystem.disableCull();
        }
        VertexBuffer.unbind();
    }

    static Matrix4f batchModelView(Matrix4f base, float x, float y, float z, boolean chunkOffset) {
        Matrix4f result = new Matrix4f(base);
        return chunkOffset ? result : result.translate(x, y, z);
    }

    private static boolean irisShader(ShaderInstance shader) {
        return shader != null && shader.getClass() != ShaderInstance.class;
    }

    private static boolean fogged(RenderType type) {
        return fogged.computeIfAbsent(
                type,
                t -> {
                    t.setupRenderState();
                    boolean supported = supportsFog(RenderSystem.getShader());
                    t.clearRenderState();
                    return supported;
                });
    }

    private static boolean supportsFog(ShaderInstance shader) {
        return shader != null
                && shader.FOG_SHAPE != null
                && DisplayBatchFog.supports(shader.getVertexProgram());
    }

    /** Records which RenderTypes a renderer asks for and drops the vertices. */
    private static final class Types implements MultiBufferSource, VertexConsumer {
        final Set<RenderType> used = new HashSet<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            used.add(type);
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /** Collects what a renderer writes, one BufferBuilder per RenderType, in first-use order. */
    private static final class Capture implements MultiBufferSource, AutoCloseable {
        final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        final List<ByteBufferBuilder> memory = new ArrayList<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return builders.computeIfAbsent(
                    type,
                    t -> {
                        ByteBufferBuilder bytes = new ByteBufferBuilder(t.bufferSize());
                        memory.add(bytes);
                        return new BufferBuilder(bytes, t.mode(), t.format());
                    });
        }

        @Override
        public void close() {
            memory.forEach(ByteBufferBuilder::close);
        }
    }

    // ------------------------------------------------------------------ Iris

    private static final Method IRIS_INSTANCE, IRIS_IN_USE;

    static {
        Method instance = null, inUse = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                instance = api.getMethod("getInstance");
                inUse = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException ignored) {
                // Iris without its public API: treat as no shader pack.
            }
        }
        IRIS_INSTANCE = instance;
        IRIS_IN_USE = inUse;
    }

    private static final Method IRIS_PACK;

    static {
        Method current = null;
        if (IRIS_INSTANCE != null) {
            try {
                current = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack");
            } catch (ReflectiveOperationException ignored) {
                // An Iris without this internal: one key per shader pack state.
            }
        }
        IRIS_PACK = current;
    }

    /**
     * Identity of the loaded Iris shader pack, null without one. Switching packs or their settings
     * loads a new pack with other entity ids. Not the pipeline: Iris builds a new one on every
     * dimension change, which is every spawn, from the same pack and ids.
     */
    private static Object irisPack() {
        if (!shaderPack()) return null;
        if (IRIS_PACK == null) return Boolean.TRUE;
        try {
            Optional<?> current = (Optional<?>) IRIS_PACK.invoke(null);
            return current.isPresent() ? current.get() : Boolean.TRUE;
        } catch (ReflectiveOperationException e) {
            return Boolean.TRUE;
        }
    }

    private static boolean shaderPack() {
        if (IRIS_INSTANCE == null) return false;
        try {
            return (boolean) IRIS_IN_USE.invoke(IRIS_INSTANCE.invoke(null));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
