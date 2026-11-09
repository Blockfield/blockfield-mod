package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonObject;
import com.lunares.blockfield.network.packet.MapImageS2CPacket;
import com.lunares.blockfield.network.packet.MapSnapshotS2CPacket;
import com.lunares.blockfield.network.packet.MapTerrainS2CPacket;
import com.lunares.blockfield.network.packet.MapTileS2CPacket;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourceReloadListenerKeys;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;

public final class MapSnapshots {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("blockfield", "map_snapshot");
    private static final ResourceLocation MINIMAP =
            ResourceLocation.fromNamespaceAndPath("blockfield", "minimap_snapshot");
    private static MapTileS2CPacket current;
    private static MapTileAssembly.MapImage currentImage;
    // The image whose textures are registered; trails current while an upload is prepared.
    private static MapTileS2CPacket shown;
    // Live GL textures per id: a refresh registers a new one, so the replaced one must close.
    private static DynamicTexture textureInstance, minimapInstance;
    private static int generation;
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BlockField-MapSnapshots");
    private static MapTileAssembly pending;

    private MapSnapshots() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                MapSnapshotS2CPacket.TYPE,
                (packet, context) -> {
                    if (!packet.valid()) return;
                    accept(
                            new MapImageS2CPacket(
                                    packet.dimension(),
                                    packet.mapId(),
                                    packet.minX(),
                                    packet.maxX(),
                                    packet.minZ(),
                                    packet.maxZ(),
                                    256,
                                    256,
                                    packet.rgb()));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                MapImageS2CPacket.TYPE,
                (packet, context) -> {
                    if (!packet.valid()) return;
                    accept(packet);
                });
        ClientPlayNetworking.registerGlobalReceiver(
                MapTileS2CPacket.TYPE,
                (packet, context) -> {
                    if (!packet.valid()) return;
                    if (current != null && current.sameImage(packet)) return;
                    if (pending == null || !pending.header.sameImage(packet)) {
                        if (packet.index() != 0) return;
                        pending = new MapTileAssembly(packet);
                    }
                    if (pending.accept(packet)) {
                        upload(pending.image(false));
                        pending = null;
                    }
                });
        ClientPlayNetworking.registerGlobalReceiver(
                MapTerrainS2CPacket.TYPE,
                (packet, context) -> {
                    if (!packet.valid()) return;
                    accept(packet);
                });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public ResourceLocation getFabricId() {
                                return ResourceLocation.fromNamespaceAndPath(
                                        "blockfield", "map_colors");
                            }

                            @Override
                            public Collection<ResourceLocation> getFabricDependencies() {
                                return List.of(
                                        ResourceReloadListenerKeys.MODELS,
                                        ResourceReloadListenerKeys.TEXTURES);
                            }

                            @Override
                            public void onResourceManagerReload(ResourceManager manager) {
                                recolor();
                            }
                        });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    releaseTextures(client.getTextureManager());
                    current = null;
                    currentImage = null;
                    shown = null;
                    generation++;
                    pending = null;
                    MapColors.clear();
                    MapDetail.clear();
                });
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    releaseTextures(client.getTextureManager());
                    current = null;
                    currentImage = null;
                    shown = null;
                    generation++;
                    pending = null;
                    MapColors.clear();
                    MapDetail.clear();
                });
    }

    private static void accept(MapImageS2CPacket packet) {
        pending = null;
        MapTileS2CPacket header =
                new MapTileS2CPacket(
                        packet.dimension(),
                        packet.mapId(),
                        packet.minX(),
                        packet.maxX(),
                        packet.minZ(),
                        packet.maxZ(),
                        packet.width(),
                        packet.height(),
                        0,
                        0,
                        new byte
                                [Math.min(MapTileS2CPacket.SIZE, packet.width())
                                        * Math.min(MapTileS2CPacket.SIZE, packet.height())
                                        * 3]);
        upload(new MapTileAssembly.MapImage(header, packet.rgb(), null, null, null));
    }

    private static void accept(MapTerrainS2CPacket packet) {
        int pixels = packet.light().length;
        // Validated by the packet; the RGB is filled below once the palette is known.
        MapTileS2CPacket tile = packet.tile(new byte[pixels * 3]);
        if (current != null && current.sameImage(tile)) return;
        if (pending == null || !pending.header.sameImage(tile)) {
            MapTileAssembly.MapImage base = null;
            if (packet.base() != 0) {
                // A patch applies only to the exact complete image the server diffed against.
                if (current == null
                        || currentImage.palette() == null
                        || current.revision() != packet.base()
                        || !current.sameMap(tile)) return;
                base = currentImage;
            }
            pending = new MapTileAssembly(tile, base, packet.count());
        }
        MapPalette palette = pending.palette;
        int[] global = new int[packet.states().length];
        for (int i = 0; i < global.length; i++)
            global[i] = palette.add(packet.states()[i], packet.colors()[i]);
        int[] entries = new int[pixels];
        for (int p = 0; p < pixels; p++) {
            entries[p] = global[packet.entry(p)];
            paint(tile.rgb(), p, palette.flat(entries[p], MapColors::color), packet.light()[p]);
        }
        if (pending.accept(tile, entries, packet.light())) {
            upload(pending.image(true));
            pending = null;
        }
    }

    private static void paint(byte[] rgb, int pixel, int flat, byte light) {
        int color = MapTerrainS2CPacket.lit(flat, light & 255);
        rgb[pixel * 3] = (byte) (color >> 16);
        rgb[pixel * 3 + 1] = (byte) (color >> 8);
        rgb[pixel * 3 + 2] = (byte) color;
    }

    /** Resource packs changed: recolor the current image from its kept surfaces. */
    private static void recolor() {
        MapColors.clear();
        MapDetail.clear();
        var image = currentImage;
        if (image == null || image.palette() == null) return;
        image.palette().recolor();
        byte[] rgb = new byte[image.rgb().length];
        for (int p = 0; p < image.entries().length; p++)
            paint(
                    rgb,
                    p,
                    image.palette().flat(image.entries()[p], MapColors::color),
                    image.light()[p]);
        upload(
                new MapTileAssembly.MapImage(
                        image.header(), rgb, image.palette(), image.entries(), image.light()));
    }

    /** Surfaces of the shown map image for close-zoom detail, or null. */
    static MapTileAssembly.MapImage terrain(Minecraft mc, JsonObject map) {
        var image = currentImage;
        return image != null
                        && image.palette() != null
                        && shown != null
                        && shown.sameImage(image.header())
                        && matches(mc, map, image.header())
                ? image
                : null;
    }

    private static void upload(MapTileAssembly.MapImage image) {
        MapTileS2CPacket header = image.header();
        byte[] rgb = image.rgb();
        current = header;
        currentImage = image;
        int ticket = ++generation;
        int w = header.width(), h = header.height();
        int scale = MinimapTerrain.scale(w, h);
        // Filtering, upscaling and mips run off-thread: in-match refreshes must not stall a frame.
        CompletableFuture.supplyAsync(
                        () ->
                                new NativeImage[][] {
                                    mips(w, h, rgb, scale),
                                    mips(w, h, MinimapTerrain.filter(rgb, w), scale)
                                },
                        Util.backgroundExecutor())
                .thenAcceptAsync(
                        images -> {
                            if (ticket != generation) {
                                for (NativeImage[] mips : images)
                                    for (NativeImage mip : mips) mip.close();
                                return;
                            }
                            register(TEXTURE, images[0], true, scale == 1);
                            register(MINIMAP, images[1], true, scale == 1);
                            shown = header;
                        },
                        Minecraft.getInstance())
                .exceptionally(
                        error -> {
                            LOGGER.warn("Cannot upload map image {}", header.mapId(), error);
                            return null;
                        });
    }

    private static NativeImage[] mips(int w, int h, byte[] rgb, int scale) {
        int width = w * scale, height = h * scale;
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int p = y / scale * w + x / scale;
                int color =
                        0xFF000000
                                | (rgb[p * 3 + 2] & 255) << 16
                                | (rgb[p * 3 + 1] & 255) << 8
                                | (rgb[p * 3] & 255);
                image.setPixelRGBA(x, y, color);
            }
        // Odd-sized maps stop before either mip dimension reaches zero.
        int levels = Math.min(5, 31 - Integer.numberOfLeadingZeros(Math.min(width, height)));
        return MipmapGenerator.generateMipLevels(new NativeImage[] {image}, levels);
    }

    static void register(
            ResourceLocation target, NativeImage[] mips, boolean blur, boolean nearest) {
        DynamicTexture texture = new DynamicTexture(mips[0]);
        int levels = mips.length - 1;
        TextureUtil.prepareImage(texture.getId(), levels, mips[0].getWidth(), mips[0].getHeight());
        for (int level = 0; level < mips.length; level++) {
            mips[level].upload(level, 0, 0, false);
            if (level > 0) mips[level].close();
        }
        texture.setFilter(blur, levels > 0);
        // Keep mipmapped minification, but do not blur individual blocks when zoomed in. Upscaled
        // images keep linear magnification: edges stay sharp but antialiased at any zoom.
        if (nearest)
            GlStateManager._texParameter(
                    GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        swap(target, texture);
    }

    /** Registers the uploaded texture and closes the one it replaces (no GL here). */
    static void swap(ResourceLocation target, DynamicTexture texture) {
        DynamicTexture previous =
                TEXTURE.equals(target)
                        ? textureInstance
                        : MINIMAP.equals(target) ? minimapInstance : null;
        Minecraft.getInstance().getTextureManager().register(target, texture);
        if (TEXTURE.equals(target)) textureInstance = texture;
        else if (MINIMAP.equals(target)) minimapInstance = texture;
        // Close after registering: the new texture already owns a fresh GL id.
        if (previous != null) previous.close();
    }

    /** JOIN/DISCONNECT: unmap both ids and free their native/GL memory. */
    static void releaseTextures(net.minecraft.client.renderer.texture.TextureManager manager) {
        if (textureInstance != null) {
            textureInstance.close();
            textureInstance = null;
        }
        if (minimapInstance != null) {
            minimapInstance.close();
            minimapInstance = null;
        }
        manager.release(TEXTURE);
        manager.release(MINIMAP);
    }

    public static ResourceLocation minimapTexture(Minecraft mc, JsonObject map) {
        return texture(mc, map) == null ? null : MINIMAP;
    }

    public static ResourceLocation texture(Minecraft mc, JsonObject map) {
        if (shown == null || mc.level == null || map == null || !map.has("id")) return null;
        return matches(mc, map, shown) ? TEXTURE : null;
    }

    public static float progress(Minecraft mc, JsonObject map) {
        return pending != null && matches(mc, map, pending.header) ? pending.progress() : -1f;
    }

    private static boolean matches(Minecraft mc, JsonObject map, MapTileS2CPacket header) {
        if (mc.level == null || map == null || !map.has("id")) return false;
        // Измерение берём из пакета карты: убитый ждёт высадки вне мира боя, а карта ему нужна.
        String dimension =
                map.has("dimension")
                        ? map.get("dimension").getAsString()
                        : mc.level.dimension().location().toString();
        return dimension.equals(header.dimension().toString())
                && map.get("id").getAsString().equals(header.mapId())
                && map.get("minX").getAsDouble() == header.minX()
                && map.get("maxX").getAsDouble() == header.maxX()
                && map.get("minZ").getAsDouble() == header.minZ()
                && map.get("maxZ").getAsDouble() == header.maxZ();
    }
}
