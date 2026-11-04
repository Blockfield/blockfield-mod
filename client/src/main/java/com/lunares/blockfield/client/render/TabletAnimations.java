package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.gui.tablet.TabletProjection;
import com.lunares.blockfield.client.gui.tablet.TabletScreenTarget;
import com.lunares.blockfield.common.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import com.tacz.guns.client.model.IFunctionalRenderer;
import com.tacz.guns.client.renderer.other.HandRenderer;
import com.tacz.guns.compat.iris.IrisCompat;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The command tablet in both hands: draw, inspect and a raise to the face while the Blockfield menu
 * is open. Opened with another item in hand, that item drops out of view, a virtual tablet comes up
 * for the menu and goes back down before the item returns.
 */
public final class TabletAnimations {
    private static final long ITEM_OUT_NS = 150_000_000L;
    private static final long ITEM_BACK_NS = 220_000_000L;
    // Matches the "lower" clip in dev/assets/equipment-3d/anims.py.
    private static final long TABLET_LOWER_NS = 350_000_000L;
    private static final ItemStack VIRTUAL = new ItemStack(ModItems.COMMAND_TABLET);

    private enum Phase {
        NONE,
        ITEM_OUT,
        TABLET,
        TABLET_DOWN,
        ITEM_BACK
    }

    // The map glass of the item model in "item_screen" bone units (1/16 block), see anims.py.
    private static final float GLASS_W = 10.55f * 1.35f / 16;
    private static final float GLASS_H = 6.8f * 1.35f / 16;
    private static final float GLASS_Z = 0.005f;

    private static Renderer renderer;
    private static TabletProjection glass;
    private static boolean glassDrawn;
    private static long glassAt = Long.MIN_VALUE / 2;
    private static Phase phase = Phase.NONE;
    private static long phaseStart;

    private TabletAnimations() {}

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("tacz")) return;
        renderer = new Renderer();
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.COMMAND_TABLET, renderer);
        BedrockItemRenderer.reloadWith("command_tablet", renderer);
    }

    /** The held tablet is drawn by its animated renderer through TaCZ's hand event. */
    public static boolean animates(ItemStack stack) {
        return renderer != null && stack.is(ModItems.COMMAND_TABLET);
    }

    /** Starts the held tablet's inspect clip; false when the tablet is not held. */
    public static boolean inspect(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (renderer == null || !held.is(ModItems.COMMAND_TABLET)) return false;
        var machine = renderer.getStateMachine(held);
        if (machine != null && machine.isInitialized()) machine.trigger("inspect");
        return true;
    }

    /**
     * The menu opens: returns how long the screen should wait before fading in, so the tablet is up
     * in front of the face first.
     */
    public static long open(LocalPlayer player) {
        if (renderer == null || player == null) return 0;
        long now = System.nanoTime();
        // The "open" clip glides to the eyes in 0.4 s; the glass boots meanwhile.
        if (player.getMainHandItem().is(ModItems.COMMAND_TABLET)) {
            TabletScreenTarget.powerOn(now);
            return 400_000_000L;
        }
        // Boot once the tablet is coming into view, so the splash is seen.
        TabletScreenTarget.powerOn(now + ITEM_OUT_NS + 150_000_000L);
        switch (phase) {
            case NONE -> start(Phase.ITEM_OUT, now);
            case ITEM_BACK -> {
                // Reverse from where the item is, so it never jumps.
                long done = Math.min(now - phaseStart, ITEM_BACK_NS);
                phase = Phase.ITEM_OUT;
                phaseStart = now - (long) ((1 - done / (double) ITEM_BACK_NS) * ITEM_OUT_NS);
            }
            case TABLET_DOWN -> {
                renderer.endVirtual(VIRTUAL);
                start(Phase.TABLET, now);
            }
            default -> {}
        }
        // Item down (ITEM_OUT_NS), then the 0.45 s "raise" clip.
        return 600_000_000L;
    }

    /** Projection of the glass drawn in this frame; absent in third person or with hidden hands. */
    public static TabletProjection glassProjection() {
        return glass;
    }

    public static boolean hasGlass() {
        return glassDrawn;
    }

    /** Redraws the interactive glass texture before the world pass, as required by Iris. */
    public static void refreshScreen() {
        glass = null;
        glassDrawn = false;
        if (renderer == null) return;
        long now = System.nanoTime();
        // Every frame, hands drawn or not (third person, hidden GUI), so nothing stalls mid-way.
        advance(now);
        if (!Tablet.isShown()) TabletScreenTarget.powerOff(now);
        TabletScreenTarget.settle(now);
        if (!Tablet.isShown() && now - glassAt > 250_000_000L) return;
        TabletScreenTarget.update();
    }

    /** The menu on the glass: a lit quad on the "item_screen" bone, drawn after the model. */
    private static void drawGlass(Matrix4f surface, Vector3f normal) {
        long now = System.nanoTime();
        glassAt = now;
        if (!TabletScreenTarget.available()) return;
        MultiBufferSource.BufferSource buffers =
                Minecraft.getInstance().renderBuffers().bufferSource();
        // Opaque: TaCZ draws hands in the solid pass only, so shader packs drop translucent types.
        RenderType type = RenderType.entitySolid(TabletScreenTarget.texture());
        VertexConsumer quad = buffers.getBuffer(type);
        corner(quad, surface, normal, TabletScreenTarget.WIDTH, 0);
        corner(quad, surface, normal, 0, 0);
        corner(quad, surface, normal, 0, TabletScreenTarget.HEIGHT);
        corner(quad, surface, normal, TabletScreenTarget.WIDTH, TabletScreenTarget.HEIGHT);
        // Snapshot the vertex pose and GL state before flushing can restore the hand pass.
        Matrix4f clip =
                new Matrix4f(RenderSystem.getProjectionMatrix())
                        .mul(RenderSystem.getModelViewMatrix())
                        .mul(surface);
        var window = Minecraft.getInstance().getWindow();
        TabletProjection projection =
                TabletProjection.glass(
                        clip, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        // Flush everything, as TaCZ does for the hands: under Iris a single-type flush is deferred.
        buffers.endBatch();
        glassDrawn = true;
        glass = projection;
    }

    private static void corner(
            VertexConsumer quad, Matrix4f surface, Vector3f normal, float x, float y) {
        quad.addVertex(surface, x, y, 0)
                .setColor(255, 255, 255, 255)
                // Framebuffer V runs bottom-up; layout pixels run top-down.
                .setUv(x / TabletScreenTarget.WIDTH, 1 - y / TabletScreenTarget.HEIGHT)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(normal.x, normal.y, normal.z);
    }

    private static final class GlassRender implements IFunctionalRenderer {
        private final BedrockAnimatedModel model;

        GlassRender(BedrockAnimatedModel model) {
            this.model = model;
        }

        @Override
        public void render(
                PoseStack pose,
                VertexConsumer consumer,
                ItemDisplayContext context,
                int light,
                int overlay) {
            if (!context.firstPerson()) return;
            Matrix4f surface =
                    TabletProjection.glassPose(pose.last().pose(), GLASS_W, GLASS_H, GLASS_Z);
            Vector3f normal = pose.last().normal().transform(new Vector3f(0, 0, 1));
            model.delegateRender((p, c, ctx, l, o) -> drawGlass(surface, normal));
        }
    }

    /**
     * Replaces the hand pass while the virtual tablet is up; returns false to draw the held item,
     * already moved by its drop-out progress.
     */
    public static boolean renderHands(
            LocalPlayer player,
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            int light,
            float partialTick) {
        if (renderer == null) return false;
        long now = System.nanoTime();
        advance(now);
        switch (phase) {
            case TABLET, TABLET_DOWN -> {
                // The same hand pass TaCZ uses for held models, so shader packs see it too.
                IrisCompat.endBatch(buffers);
                Minecraft mc = Minecraft.getInstance();
                HandRenderer.INSTANCE.renderSolid(
                        own ->
                                renderer.renderVirtual(
                                        player,
                                        VIRTUAL,
                                        own != null ? own : pose,
                                        buffers,
                                        light,
                                        partialTick),
                        partialTick,
                        mc.gameRenderer.getMainCamera(),
                        mc.gameRenderer);
                return true;
            }
            case ITEM_OUT -> drop(pose, smooth((now - phaseStart) / (float) ITEM_OUT_NS));
            case ITEM_BACK -> drop(pose, 1 - smooth((now - phaseStart) / (float) ITEM_BACK_NS));
            default -> {}
        }
        return false;
    }

    /** Moves the virtual tablet through its phases by time and the menu state. */
    private static void advance(long now) {
        if (phase == Phase.ITEM_OUT && now - phaseStart >= ITEM_OUT_NS) start(Phase.TABLET, now);
        if (phase == Phase.TABLET && !Tablet.isShown()) start(Phase.TABLET_DOWN, now);
        if (phase == Phase.TABLET_DOWN && now - phaseStart >= TABLET_LOWER_NS) {
            renderer.endVirtual(VIRTUAL);
            start(Phase.ITEM_BACK, now);
        }
        if (phase == Phase.ITEM_BACK && now - phaseStart >= ITEM_BACK_NS) phase = Phase.NONE;
    }

    private static void start(Phase next, long now) {
        phase = next;
        phaseStart = now;
    }

    static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** The held item dips down and away, as if lowered to the side to free both hands. */
    static void drop(PoseStack pose, float amount) {
        pose.translate(0.12 * amount, -0.65 * amount, 0.1 * amount);
        pose.mulPose(Axis.XP.rotationDegrees(-30 * amount));
    }

    private static final class Renderer extends BedrockItemRenderer {
        private static final ResourceLocation TEXTURE =
                ResourceLocation.fromNamespaceAndPath(
                        "blockfield", "textures/item/command_tablet_body.png");

        Renderer() {
            super(
                    ResourceLocation.fromNamespaceAndPath("blockfield", "equipment/command_tablet"),
                    ResourceLocation.fromNamespaceAndPath("blockfield", "equipment/command_tablet"),
                    ResourceLocation.fromNamespaceAndPath("blockfield", "tablet"));
        }

        @Override
        protected boolean isLinked(ItemStack stack) {
            // Keep the physical tablet raised through the menu's closing delay.
            return Tablet.isShown();
        }

        @Override
        protected boolean steady(ItemStack stack) {
            return Tablet.isShown();
        }

        @Override
        protected void configure(BedrockAnimatedModel model) {
            model.setFunctionalRenderer("item_screen", part -> new GlassRender(model));
        }

        @Override
        public ResourceLocation getTextureLocation(ItemStack stack) {
            return TEXTURE;
        }

        @Override
        public long getPutAwayTime(ItemStack stack) {
            // Fresh deploy: the kit replaced the inventory, so this draw must not charge the
            // tablet put-away (nor hold ADS on it) while the server draw runs its own cooldown.
            if (Tablet.freshDeploy(System.nanoTime())) return 0;
            return 500;
        }

        @Override
        public void doExtraTransforms(PoseStack pose, BedrockAnimatedModel model, ItemStack stack) {
            super.doExtraTransforms(pose, model, stack);
            // Same view offset as the other equipment rigs.
            pose.translate(0, 0.02, -0.25);
        }
    }
}
