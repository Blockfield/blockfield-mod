package com.lunares.blockfield.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 12 texels per model unit alias badly at range: vanilla never mipmaps entity textures, so uniforms
 * load their own mip chain and render with a mipmapped copy of armorCutoutNoCull.
 */
final class UniformTexture extends AbstractTexture {
    private static final int MIP_LEVELS = 4;
    private final ResourceLocation location;

    private UniformTexture(ResourceLocation location) {
        this.location = location;
    }

    static RenderType renderType(String kit) {
        var location =
                ResourceLocation.fromNamespaceAndPath(
                        "blockfield", "textures/models/uniform/" + kit + ".png");
        Minecraft.getInstance()
                .getTextureManager()
                .register(location, new UniformTexture(location));
        return RenderType.create(
                "blockfield_uniform_" + kit,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                true,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_ARMOR_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(
                                new RenderStateShard.TextureStateShard(location, false, true))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(true));
    }

    @Override
    public void load(ResourceManager resources) throws IOException {
        NativeImage image;
        try (var stream = resources.open(location)) {
            image = NativeImage.read(stream);
        }
        NativeImage[] mips =
                MipmapGenerator.generateMipLevels(new NativeImage[] {image}, MIP_LEVELS);
        if (RenderSystem.isOnRenderThreadOrInit()) upload(mips);
        else RenderSystem.recordRenderCall(() -> upload(mips));
    }

    private void upload(NativeImage[] mips) {
        TextureUtil.prepareImage(getId(), MIP_LEVELS, mips[0].getWidth(), mips[0].getHeight());
        for (int level = 0; level < mips.length; level++)
            mips[level].upload(
                    level,
                    0,
                    0,
                    0,
                    0,
                    mips[level].getWidth(),
                    mips[level].getHeight(),
                    false,
                    false,
                    true,
                    true);
    }
}
