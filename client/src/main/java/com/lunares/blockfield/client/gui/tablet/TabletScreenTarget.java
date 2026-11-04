package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.client.gui.BfTheme;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 3D tablet's glass as a real texture, rendered into an offscreen framebuffer once per frame
 * before the world (switching targets inside world passes breaks Iris). Powering on shows the
 * Blockfield splash, then the menu.
 */
public final class TabletScreenTarget {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlockField-TabletScreen");

    /** Layout size of the glass in GUI units; 1.55:1 like the map glass of the item model. */
    public static final int WIDTH = 590;

    public static final int HEIGHT = 380;
    private static final int SCALE = 2;
    // Boot timeline in ms: a cold start wakes the glass, fills the loading bar, then the menu wipes
    // in; a recent standby only wipes. Shutdown collapses the picture into a line.
    private static final int WAKE_COLD = 150;
    private static final int LOADED_COLD = 600;
    private static final int REVEAL_COLD = 850;
    private static final int LOADED_WAKE = 120;
    private static final int REVEAL_WAKE = 330;
    private static final int OFF = 260;
    private static final long COLD_AFTER_NS = 15_000_000_000L;
    // In standby the glass keeps a dim mark, like a sleeping device.
    private static final float STANDBY = 0.18f;
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("blockfield", "tablet_screen");
    private static final ResourceLocation LOGO =
            ResourceLocation.fromNamespaceAndPath("blockfield", "textures/gui/logo.png");

    private static RenderTarget target;
    private static boolean failed;
    private static boolean rendering;
    private static long poweredAt = Long.MIN_VALUE;
    private static long offAt = Long.MIN_VALUE;
    private static long standbySince = Long.MIN_VALUE / 2;
    private static boolean cold;

    private TabletScreenTarget() {}

    public static ResourceLocation texture() {
        return TEXTURE;
    }

    public static boolean available() {
        return target != null && !failed;
    }

    /** True while the glass is being drawn; scissor rectangles then map to the framebuffer. */
    public static boolean rendering() {
        return rendering;
    }

    public static int pixelHeight() {
        return HEIGHT * SCALE;
    }

    public static double pixelScale() {
        return SCALE;
    }

    /** The surface the glass and the flat menu share: frame inside the bezel. */
    public static TabletFrame frame() {
        return TabletFrame.inset(WIDTH, HEIGHT, 8);
    }

    /** The screen switches on at `at` (boot plays from then); a recent standby wakes quickly. */
    public static void powerOn(long at) {
        offAt = Long.MIN_VALUE;
        if (poweredAt != Long.MIN_VALUE) return;
        poweredAt = at;
        cold = at - standbySince > COLD_AFTER_NS;
    }

    /** The menu has gone: the picture collapses like a CRT and the glass sleeps. */
    public static void powerOff(long now) {
        if (poweredAt != Long.MIN_VALUE && offAt == Long.MIN_VALUE) offAt = now;
    }

    public static boolean powered() {
        return poweredAt != Long.MIN_VALUE;
    }

    /** Still loading: the menu is not on the glass yet, so input waits. */
    public static boolean booting(long now) {
        return poweredAt != Long.MIN_VALUE
                && now - poweredAt < ms(cold ? REVEAL_COLD : REVEAL_WAKE);
    }

    /** After the shutdown collapse the glass sleeps; runs whether or not the glass is drawn. */
    public static void settle(long now) {
        if (offAt != Long.MIN_VALUE && now - offAt > ms(OFF)) {
            poweredAt = Long.MIN_VALUE;
            offAt = Long.MIN_VALUE;
            standbySince = now;
        }
    }

    /** Redraws the glass texture before the frame. */
    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (failed || mc.getWindow() == null) return;
        if (target == null && !create(mc)) return;
        RenderTarget main = mc.getMainRenderTarget();
        // At frame HEAD the previous GUI may have disabled depth writes; glClear honours that mask.
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        RenderSystem.depthMask(true);
        target.setClearColor(0.02f, 0.02f, 0.015f, 1f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0f, WIDTH, HEIGHT, 0f, 1000f, 21000f),
                VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        modelView.translate(0f, 0f, -11000f);
        RenderSystem.applyModelViewMatrix();
        rendering = true;
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            var mouse =
                    mc.screen instanceof TabletScreen screen
                            ? screen.surfaceMouse(
                                    mc.mouseHandler.xpos()
                                            * mc.getWindow().getGuiScaledWidth()
                                            / mc.getWindow().getScreenWidth(),
                                    mc.mouseHandler.ypos()
                                            * mc.getWindow().getGuiScaledHeight()
                                            / mc.getWindow().getScreenHeight())
                            : new org.joml.Vector2f(-1, -1);
            draw(
                    g,
                    mc,
                    (int) mouse.x,
                    (int) mouse.y,
                    mc.getTimer().getGameTimeDeltaPartialTick(false));
            g.flush();
        } catch (RuntimeException e) {
            LOGGER.error("tablet screen render failed", e);
            failed = true;
        } finally {
            rendering = false;
            // The framebuffer's local scissor stack is discarded on render failure, too.
            RenderSystem.disableScissor();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.depthMask(depthWrite);
            main.bindWrite(true);
        }
    }

    /**
     * What the glass shows now, in its {@link #WIDTH} x {@link #HEIGHT} layout: standby, the boot
     * (splash, loading bar, the menu wiping in under a scan line), the menu, or the CRT collapse.
     * The centred fallback uses the same layout and boot timeline.
     */
    public static void draw(
            GuiGraphics g, Minecraft mc, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        g.fill(0, 0, WIDTH, HEIGHT, 0xFF050403);
        if (poweredAt == Long.MIN_VALUE) {
            splash(g, STANDBY, -1, 0);
            return;
        }
        float t = (now - poweredAt) / 1_000_000f;
        float collapse = offAt == Long.MIN_VALUE ? 0f : ease((now - offAt) / (float) ms(OFF));
        g.pose().pushPose();
        if (collapse > 0f) {
            float sy = Math.max(0.004f, 1f - collapse);
            float sx = 1f - 0.15f * Math.max(0f, collapse - 0.7f) / 0.3f;
            g.pose().translate(WIDTH / 2f, HEIGHT / 2f, 0);
            g.pose().scale(sx, sy, 1f);
            g.pose().translate(-WIDTH / 2f, -HEIGHT / 2f, 0);
        }
        int wake = cold ? WAKE_COLD : 0;
        int loaded = cold ? LOADED_COLD : LOADED_WAKE;
        int revealed = cold ? REVEAL_COLD : REVEAL_WAKE;
        float reveal = ease((t - loaded) / (revealed - loaded));
        if (reveal < 1f) {
            float logo = cold ? ease(t / wake) : 1f - ease((t - 60) / 120f) * 0.4f;
            float bar = cold ? ease((t - wake) / (loaded - wake)) : -1f;
            splash(g, logo * (1f - reveal), bar, cold && t < wake ? 1f - t / wake : 0f);
        }
        if (reveal > 0f) {
            int line = Math.round(HEIGHT * reveal);
            Tablet.withScissorShift(() -> g.enableScissor(0, 0, WIDTH, line));
            g.fill(0, 0, WIDTH, line, BfTheme.BG);
            Tablet.renderSurface(g, mc, frame(), mouseX, mouseY, partialTick);
            g.flush();
            Tablet.withScissorShift(g::disableScissor);
            if (reveal < 1f) {
                int glow = Math.round(255 * (1f - reveal * reveal));
                g.fill(0, line - 6, WIDTH, line, (glow / 5) << 24 | 0xF5A524);
                g.fill(0, line - 1, WIDTH, line + 1, glow << 24 | 0xFFC861);
            }
        }
        g.flush();
        g.pose().popPose();
        if (collapse > 0.6f) {
            int a = Math.round(255 * (1f - collapse) / 0.4f);
            g.fill(
                    WIDTH / 2 - 160,
                    HEIGHT / 2 - 1,
                    WIDTH / 2 + 160,
                    HEIGHT / 2 + 1,
                    a << 24 | 0xF3E7D0);
        }
    }

    /**
     * The Blockfield mark and name; `bar` >= 0 draws the loading bar, `flicker` wakes the glass.
     */
    private static void splash(GuiGraphics g, float alpha, float bar, float flicker) {
        if (flicker > 0f) {
            var random = new java.util.SplittableRandom(System.nanoTime() / 33_000_000L);
            for (int y = 0; y < HEIGHT; y += 4) {
                if (random.nextInt(4) == 0) {
                    int a = Math.round(40 * flicker * random.nextFloat());
                    g.fill(0, y, WIDTH, y + 2, a << 24 | 0xF3E7D0);
                }
            }
        }
        if (alpha <= 0.01f) return;
        int size = 110;
        float grow = 0.94f + 0.06f * alpha;
        g.pose().pushPose();
        g.pose().translate(WIDTH / 2f, HEIGHT / 2f - 34, 0);
        g.pose().scale(grow, grow, 1f);
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        g.blit(LOGO, -size / 2, -size / 2, 0, 0, size, size, size, size);
        g.flush();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        g.pose().popPose();
        var font = BfTheme.font();
        int ink = Math.max(4, Math.round(255 * alpha)) << 24;
        g.pose().pushPose();
        g.pose().translate(WIDTH / 2f, HEIGHT / 2f + 34, 0);
        g.pose().scale(2f, 2f, 1f);
        g.drawString(font, "BLOCKFIELD", -font.width("BLOCKFIELD") / 2, 0, ink | 0xF3E7D0, false);
        g.pose().popPose();
        if (bar < 0f) return;
        int x = WIDTH / 2 - 100, y = HEIGHT / 2 + 68;
        g.fill(x, y, x + 200, y + 3, ink | 0x2A2218);
        g.fill(x, y, x + Math.round(200 * bar), y + 3, ink | 0xF5A524);
        String status = "ЗАГРУЗКА  " + Math.round(100 * bar) + "%";
        g.drawString(
                font, status, WIDTH / 2 - font.width(status) / 2, y + 9, ink | 0xA89373, false);
    }

    private static float ease(float t) {
        t = clamp01(t);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static long ms(int value) {
        return value * 1_000_000L;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static boolean create(Minecraft mc) {
        try {
            target = new TextureTarget(WIDTH * SCALE, HEIGHT * SCALE, true, Minecraft.ON_OSX);
            target.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            mc.getTextureManager().register(TEXTURE, new TargetTexture());
            return true;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("tablet screen framebuffer unavailable", e);
            failed = true;
            target = null;
            return false;
        }
    }

    /** The framebuffer as a texture: its id belongs to the target, the manager must not free it. */
    private static final class TargetTexture extends AbstractTexture {
        @Override
        public void load(ResourceManager manager) {}

        @Override
        public int getId() {
            return target == null ? 0 : target.getColorTextureId();
        }

        @Override
        public void releaseId() {}

        @Override
        public void close() {}
    }
}
