package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.util.RenderDistance;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * HUD silhouettes of items, rendered offscreen from the item's own model at the exact pixel size
 * they are shown, so they stay sharp at every GUI scale and show what is installed. Guns are posed
 * from their TaCZ model, muzzle left; kit items keep their drawn vector shapes, rasterised at that
 * size; the RPG uses SBW's horizontal gun icon; anything else comes from its inventory pose (GUI).
 * Each (item, size, style) is one cached texture.
 */
public final class ItemSilhouette {
    /** A cached silhouette texture of {@code width} x {@code height} screen pixels. */
    public record Icon(ResourceLocation texture, int width, int height) {}

    /**
     * A flat white tint mask, amber-to-cream gradient, or the cartridge's own casing/projectile
     * colors.
     */
    public enum Style {
        FLAT,
        GRADIENT,
        AMMUNITION
    }

    private static final int SAMPLES = 4, COARSE = 256, CACHE_SIZE = 64;
    // Coarse-pass half-extents in blocks: TaCZ models are in bedrock pixels around a far origin,
    // item models stay within about a block.
    private static final float GUN_REACH = 8, ITEM_REACH = 2;
    private static final Map<String, Icon> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Icon> eldest) {
                    if (size() <= CACHE_SIZE) return false;
                    Minecraft.getInstance()
                            .getTextureManager()
                            .release(eldest.getValue().texture());
                    return true;
                }
            };
    private static final boolean TACZ =
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("tacz");
    private static int serial;

    private ItemSilhouette() {}

    /** Gun models and item models change with resource packs. */
    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public ResourceLocation getFabricId() {
                                return ResourceLocation.fromNamespaceAndPath(
                                        "blockfield", "hud_silhouette");
                            }

                            @Override
                            public void onResourceManagerReload(ResourceManager manager) {
                                clear();
                            }
                        });
    }

    public static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Icon icon : CACHE.values()) textures.release(icon.texture());
        CACHE.clear();
    }

    /**
     * Draws the silhouette of {@code stack} fitted into the GUI box, anchored by {@code alignX} (0
     * left, 0.5 centre, 1 right) and its bottom. Returns false when the item has none.
     */
    public static boolean draw(
            GuiGraphics graphics,
            ItemStack stack,
            Style style,
            float x,
            float y,
            float width,
            float height,
            float alignX) {
        if (stack.isEmpty()) return false;
        float size = WeaponHudSilhouettes.size(WeaponHudSilhouettes.kind(stack));
        return draw(
                graphics,
                key(stack),
                (maxWidth, maxHeight) -> render(stack, maxWidth, maxHeight),
                style,
                x + width * (1 - size) * alignX,
                y + height * (1 - size),
                width * size,
                height * size,
                alignX);
    }

    /** Draws a drawn shape of WeaponHudSilhouettes as {@link #draw} draws an item. */
    public static void draw(
            GuiGraphics graphics,
            String shape,
            Style style,
            float x,
            float y,
            float width,
            float height,
            float alignX) {
        draw(
                graphics,
                "shape:" + shape,
                (maxWidth, maxHeight) ->
                        vector(shape, maxWidth, maxHeight, style == Style.AMMUNITION),
                style,
                x,
                y,
                width,
                height,
                alignX);
    }

    private interface Renderer {
        NativeImage render(int maxWidth, int maxHeight);
    }

    private static boolean draw(
            GuiGraphics graphics,
            String key,
            Renderer renderer,
            Style style,
            float x,
            float y,
            float width,
            float height,
            float alignX) {
        var pose = graphics.pose();
        var matrix = pose.last().pose();
        double gui = Minecraft.getInstance().getWindow().getGuiScale();
        // Screen pixels per GUI unit here, including any scale the caller has applied.
        double scale = gui * matrix.m00();
        int maxWidth = (int) Math.round(width * scale),
                maxHeight = (int) Math.round(height * scale);
        if (maxWidth < 2 || maxHeight < 2) return false;
        String cacheKey = key + "|" + maxWidth + "x" + maxHeight + "|" + style;
        if (!CACHE.containsKey(cacheKey)) {
            // Offscreen rendering switches framebuffers: draw what the GUI has batched first.
            graphics.flush();
            try (NativeImage mask = renderer.render(maxWidth, maxHeight)) {
                CACHE.put(cacheKey, mask == null ? null : upload(mask, style));
            }
        }
        Icon icon = CACHE.get(cacheKey);
        if (icon == null) return false;
        float w = (float) (icon.width() / scale), h = (float) (icon.height() / scale);
        // On a whole screen pixel: half a pixel off, linear filtering blurs every edge and line.
        double left = Math.round((matrix.m00() * (x + (width - w) * alignX) + matrix.m30()) * gui),
                top = Math.round((matrix.m11() * (y + height - h) + matrix.m31()) * gui);
        pose.pushPose();
        pose.translate(
                (float) ((left / gui - matrix.m30()) / matrix.m00()),
                (float) ((top / gui - matrix.m31()) / matrix.m11()),
                0);
        pose.scale((float) (1 / scale), (float) (1 / scale), 1);
        // GuiGraphics' ordinary texture blit inherits blending from the held-item renderer.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(
                icon.texture(),
                -1,
                -1,
                0,
                0,
                icon.width() + 2,
                icon.height() + 2,
                icon.width() + 2,
                icon.height() + 2);
        RenderSystem.disableBlend();
        pose.popPose();
        return true;
    }

    static String key(ItemStack stack) {
        var key = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        IGun gun = TACZ ? IGun.getIGunOrNull(stack) : null;
        if (gun == null)
            return key.append(':').append(GrenadeThrowSupport.throwableId(stack)).toString();
        key.append(':').append(gun.getGunId(stack)).append(':').append(gun.getGunDisplayId(stack));
        for (AttachmentType type : AttachmentType.values())
            if (type != AttachmentType.NONE)
                key.append(':').append(gun.getAttachmentId(stack, type));
        return key.toString();
    }

    private static NativeImage render(ItemStack stack, int maxWidth, int maxHeight) {
        // Kit items keep their drawn shapes: as model outlines a medkit, plate or monitor turns
        // into
        // a blob, the cut-outs (cross, insert, screen) are what make them readable.
        String kind = WeaponHudSilhouettes.kind(stack);
        if ("rpg".equals(kind)) {
            NativeImage icon =
                    sprite(
                            ResourceLocation.fromNamespaceAndPath(
                                    "superbwarfare", "textures/gun_icon/rpg_standard_icon.png"),
                            maxWidth,
                            maxHeight);
            if (icon != null) return icon;
            kind = null;
        }
        if (kind != null) return vector(kind, maxWidth, maxHeight, false);
        Minecraft mc = Minecraft.getInstance();
        Consumer<PoseStack> draw;
        float reach;
        IGun gun = TACZ ? IGun.getIGunOrNull(stack) : null;
        GunDisplayInstance display =
                gun == null ? null : TimelessAPI.getGunDisplay(stack).orElse(null);
        BedrockGunModel model = display == null ? null : display.getGunModel();
        if (model != null) {
            reach = GUN_REACH;
            draw =
                    pose -> {
                        // Bedrock gun models point the muzzle along -Z upside down, as TaCZ's
                        // first-person pass flips them; a quarter turn about Y puts it on the left.
                        pose.mulPose(Axis.YP.rotationDegrees(90));
                        pose.mulPose(Axis.ZP.rotationDegrees(180));
                        restPose(
                                model.getShouldRender(),
                                () ->
                                        model.render(
                                                pose,
                                                stack,
                                                ItemDisplayContext.FIXED,
                                                RenderType.entityCutout(display.getModelTexture()),
                                                LightTexture.FULL_BRIGHT,
                                                OverlayTexture.NO_OVERLAY));
                    };
        } else {
            reach = ITEM_REACH;
            var buffers = mc.renderBuffers().bufferSource();
            draw =
                    pose -> {
                        // The inventory pose is the one modellers make readable.
                        mc.getItemRenderer()
                                .renderStatic(
                                        stack,
                                        ItemDisplayContext.GUI,
                                        LightTexture.FULL_BRIGHT,
                                        OverlayTexture.NO_OVERLAY,
                                        pose,
                                        buffers,
                                        null,
                                        0);
                        buffers.endBatch();
                    };
        }
        float unit = 2 * reach / COARSE;
        int[] box;
        try (var coarse = pass(mc, draw, -reach, reach, -reach, reach, COARSE, COARSE)) {
            box = bounds(COARSE, COARSE, coarse::getPixelRGBA);
        }
        if (box[2] == 1 && box[3] == 1) return null;
        float left = -reach + (box[0] - 1) * unit,
                right = -reach + (box[0] + box[2] + 1) * unit,
                top = reach - (box[1] - 1) * unit,
                bottom = reach - (box[1] + box[3] + 1) * unit;
        int[] size = fit(right - left, top - bottom, maxWidth, maxHeight);
        try (var raw =
                pass(mc, draw, left, right, bottom, top, size[0] * SAMPLES, size[1] * SAMPLES)) {
            var image = new NativeImage(size[0], size[1], true);
            for (int y = 0; y < size[1]; y++)
                for (int x = 0; x < size[0]; x++) {
                    int alpha = 0;
                    for (int dy = 0; dy < SAMPLES; dy++)
                        for (int dx = 0; dx < SAMPLES; dx++)
                            alpha += raw.getPixelRGBA(x * SAMPLES + dx, y * SAMPLES + dy) >>> 24;
                    image.setPixelRGBA(x, y, alpha / (SAMPLES * SAMPLES) << 24 | 0xFFFFFF);
                }
            return image;
        }
    }

    // TaCZ shares this model with the hands; cache its rest pose without changing live animation.
    static void restPose(List<BedrockPart> roots, Runnable render) {
        var saved = new ArrayList<PartPose>();
        for (BedrockPart root : roots) savePose(root, saved);
        try {
            for (PartPose pose : saved) {
                BedrockPart part = pose.part();
                part.offsetX = part.offsetY = part.offsetZ = 0;
                part.additionalQuaternion.identity();
                part.xScale = part.yScale = part.zScale = 1;
                if ("additional_magazine".equals(part.name)) part.visible = false;
            }
            render.run();
        } finally {
            for (PartPose pose : saved) pose.restore();
        }
    }

    private static void savePose(BedrockPart part, List<PartPose> saved) {
        saved.add(
                new PartPose(
                        part,
                        part.offsetX,
                        part.offsetY,
                        part.offsetZ,
                        new Quaternionf(part.additionalQuaternion),
                        part.xScale,
                        part.yScale,
                        part.zScale,
                        part.visible));
        for (BedrockPart child : part.children) savePose(child, saved);
    }

    private record PartPose(
            BedrockPart part,
            float x,
            float y,
            float z,
            Quaternionf rotation,
            float scaleX,
            float scaleY,
            float scaleZ,
            boolean visible) {
        void restore() {
            part.offsetX = x;
            part.offsetY = y;
            part.offsetZ = z;
            part.additionalQuaternion.set(rotation);
            part.xScale = scaleX;
            part.yScale = scaleY;
            part.zScale = scaleZ;
            part.visible = visible;
        }
    }

    /** Pixel size of a {@code width} x {@code height} shape fitted into the box. */
    static int[] fit(float width, float height, int maxWidth, int maxHeight) {
        float scale = Math.min(maxWidth / width, maxHeight / height);
        return new int[] {
            Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))
        };
    }

    private static NativeImage vector(
            String kind, int maxWidth, int maxHeight, boolean ammunition) {
        var bitmap =
                ammunition
                        ? WeaponHudSilhouettes.ammunitionRaster(kind, maxWidth, maxHeight)
                        : WeaponHudSilhouettes.raster(kind, maxWidth, maxHeight);
        var image = new NativeImage(bitmap.getWidth(), bitmap.getHeight(), true);
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++)
                image.setPixelRGBA(
                        x,
                        y,
                        ammunition
                                ? abgr(bitmap.getRGB(x, y))
                                : bitmap.getRGB(x, y) & 0xFF000000 | 0xFFFFFF);
        return image;
    }

    static int abgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 255) << 16 | argb >> 16 & 255;
    }

    private static NativeImage sprite(ResourceLocation texture, int maxWidth, int maxHeight) {
        try (var stream = Minecraft.getInstance().getResourceManager().open(texture);
                var source = NativeImage.read(stream)) {
            int[] box = bounds(source.getWidth(), source.getHeight(), source::getPixelRGBA);
            int[] size = fit(box[2], box[3], maxWidth, maxHeight);
            var image = new NativeImage(size[0], size[1], true);
            source.resizeSubRectTo(box[0], box[1], box[2], box[3], image);
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static NativeImage pass(
            Minecraft mc,
            Consumer<PoseStack> draw,
            float left,
            float right,
            float bottom,
            float top,
            int width,
            int height) {
        var target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        var view = RenderSystem.getModelViewStack();
        try {
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(left, right, bottom, top, -100, 100),
                    VertexSorting.ORTHOGRAPHIC_Z);
            view.pushMatrix();
            view.identity();
            RenderSystem.applyModelViewMatrix();
            RenderDistance.markGuiRenderTimestamp();
            Lighting.setupForFlatItems();
            draw.accept(new PoseStack());
            Lighting.setupFor3DItems();
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            var image = new NativeImage(width, height, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            return image;
        } finally {
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static Icon upload(NativeImage mask, Style style) {
        int width = mask.getWidth(), height = mask.getHeight();
        // A transparent border keeps linear filtering from wrapping one edge onto the other.
        var image = new NativeImage(width + 2, height + 2, true);
        for (int y = 0; y < height; y++) {
            int color = style == Style.GRADIENT ? gradient(y, height) : 0xFFFFFF;
            for (int x = 0; x < width; x++)
                image.setPixelRGBA(
                        x + 1,
                        y + 1,
                        style == Style.AMMUNITION
                                ? mask.getPixelRGBA(x, y)
                                : mask.getPixelRGBA(x, y) & 0xFF000000 | color);
        }
        var texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        var id = ResourceLocation.fromNamespaceAndPath("blockfield", "hud_silhouette/" + serial++);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new Icon(id, width, height);
    }

    static int[] bounds(int width, int height, IntBinaryOperator pixel) {
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if ((pixel.applyAsInt(x, y) >>> 24) != 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
        return right < left
                ? new int[] {0, 0, 1, 1}
                : new int[] {left, top, right - left + 1, bottom - top + 1};
    }

    /** Amber at the top to paper at the bottom, as ABGR for NativeImage. */
    static int gradient(int y, int height) {
        float t = height <= 1 ? 0 : y / (float) (height - 1);
        int color = BfTheme.mix(BfTheme.AMBER, BfTheme.TEXT, t);
        return (color & 255) << 16 | color & 0xFF00 | color >> 16 & 255;
    }
}
