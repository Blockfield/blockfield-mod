package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.render.TabletAnimations;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * Input for the menu textured onto the 3D tablet. Without a visible tablet (third person or no
 * TaCZ), the same surface is drawn as a centred menu. WASD still moves the player.
 */
public final class TabletScreen extends Screen {
    private boolean panning;
    // Input uses the last presented glass, rather than a different transform from the next frame.
    private TabletProjection projection;

    public TabletScreen() {
        super(Component.literal("Blockfield"));
        TabletMapTab.resetZoom();
    }

    @Override
    protected void init() {
        projection = null;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        TabletAnim anim = Tablet.anim();
        float progress = anim.progress(now);
        if (!Tablet.isOpen() && !anim.visible(now)) {
            Tablet.finishClosing(this.minecraft);
            return;
        }
        projection = TabletAnimations.glassProjection();
        // The world hand pass already drew the framebuffer with native perspective correction.
        // An edge-on glass can have no usable inverse; it still must not become a flat overlay.
        if (TabletAnimations.hasGlass()) return;
        if (progress <= 0f && Tablet.isOpen()) return;
        float[] r = fallbackRect();
        float rise = Tablet.rise(now) * this.height;
        projection = TabletProjection.flat(r[0], r[1] + rise, r[2], r[3]);
        // Dim the world around the glass, not the glass itself: it shows the same menu.
        int scrim = BfTheme.alpha(BfTheme.SCRIM, (int) (0xB0 * progress));
        int x0 = (int) r[0], y0 = (int) (r[1] + rise), x1 = (int) (r[0] + r[2]);
        int y1 = (int) (r[1] + rise + r[3]);
        g.fill(0, 0, this.width, Math.max(0, y0), scrim);
        g.fill(0, Math.min(this.height, y1), this.width, this.height, scrim);
        g.fill(0, y0, Math.max(0, x0), y1, scrim);
        g.fill(Math.min(this.width, x1), y0, this.width, y1, scrim);
        g.flush();

        g.pose().pushPose();
        g.pose().translate(r[0], r[1] + rise, 0);
        g.pose().scale(r[2] / TabletScreenTarget.WIDTH, r[3] / TabletScreenTarget.HEIGHT, 1f);
        RenderSystem.setShaderColor(1f, 1f, 1f, progress);
        try {
            Vector2f mouse = surfaceMouse(mouseX, mouseY);
            int sx = (int) mouse.x, sy = (int) mouse.y;
            if (TabletScreenTarget.powered()) {
                // Same picture as the glass, boot included.
                TabletScreenTarget.draw(g, this.minecraft, sx, sy, partialTick);
            } else {
                g.fill(0, 0, TabletScreenTarget.WIDTH, TabletScreenTarget.HEIGHT, BfTheme.BG);
                Tablet.renderSurface(
                        g, this.minecraft, TabletScreenTarget.frame(), sx, sy, partialTick);
            }
            g.flush();
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.pose().popPose();
        }

        if (!Tablet.isOpen() && progress <= 0f) Tablet.finishClosing(this.minecraft);
    }

    private float[] fallbackRect() {
        float h = this.height * 0.82f;
        float w =
                Math.min(
                        this.width * 0.94f,
                        h * TabletScreenTarget.WIDTH / TabletScreenTarget.HEIGHT);
        h = w * TabletScreenTarget.HEIGHT / TabletScreenTarget.WIDTH;
        return new float[] {(this.width - w) / 2, (this.height - h) / 2, w, h};
    }

    Vector2f surfaceMouse(double mouseX, double mouseY) {
        Vector2f p = projection == null ? null : projection.surface(mouseX, mouseY);
        return TabletProjection.contains(p) ? p : new Vector2f(-1, -1);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (TabletScreenTarget.booting(System.nanoTime())) return true;
        TabletFrame f = TabletScreenTarget.frame();
        Vector2f mouse = surfaceMouse(mouseX, mouseY);
        double x = mouse.x, y = mouse.y;
        panning =
                Tablet.isOpen()
                        && Tablet.tab() == Tablet.Tab.MAP
                        && button == 0
                        && TabletMapTab.beginPan(x, y, f);
        if (panning) return true;
        return Tablet.isOpen() && Tablet.mouseClicked(this.minecraft, f, x, y, button)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (panning && button == 0 && Tablet.isOpen() && Tablet.tab() == Tablet.Tab.MAP) {
            if (projection == null) return true;
            Vector2f next = projection.surface(mouseX, mouseY);
            Vector2f previous = projection.surface(mouseX - dx, mouseY - dy);
            if (next == null || previous == null) return true;
            return TabletMapTab.pan(
                    next.x - previous.x, next.y - previous.y, TabletScreenTarget.frame());
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && panning) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (TabletScreenTarget.booting(System.nanoTime())) return true;
        Vector2f mouse = surfaceMouse(mouseX, mouseY);
        return Tablet.isOpen()
                        && Tablet.scroll(TabletScreenTarget.frame(), mouse.x, mouse.y, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || Tablet.isTabletKey(keyCode)) {
            Tablet.requestClose(this.minecraft);
            return true;
        }
        if (TabletScreenTarget.booting(System.nanoTime())) return true;
        if (keyCode == GLFW.GLFW_KEY_TAB
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && Tablet.visibleTabs().size() > 1) {
            Tablet.nextTab();
            return true;
        }
        return Tablet.keyPressed(keyCode, scanCode, modifiers)
                || Tablet.zoomKey(TabletScreenTarget.frame(), keyCode)
                || super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Ванильная пауза на Esc не нужна: планшет не останавливает бой, и мы уже обработали Esc выше.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Tablet.requestClose(this.minecraft);
    }
}
