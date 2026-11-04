package com.lunares.blockfield.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Иконка окна/панели задач: ваниль 1.21.1 читает icon_*.png только из ванильного пака
 * (getRootResource), поэтому ставим свои PNG сами через glfwSetWindowIcon, как Window.setIcon.
 * macOS и Wayland игнорируют иконку окна — не ошибка.
 */
public final class WindowIcon {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowIcon.class);
    private static final String[] SIZES = {"16x16", "32x32", "48x48", "128x128", "256x256"};

    private WindowIcon() {}

    public static void apply() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        if (window == null || GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return;

        List<NativeImage> images = new ArrayList<>();
        List<ByteBuffer> pixelBuffers = new ArrayList<>();
        try {
            for (String size : SIZES) {
                var resource =
                        minecraft
                                .getResourceManager()
                                .getResource(
                                        ResourceLocation.fromNamespaceAndPath(
                                                "blockfield", "icons/icon_" + size + ".png"));
                if (resource.isEmpty()) continue;
                try (InputStream in = resource.get().open()) {
                    images.add(NativeImage.read(in));
                }
            }
            if (images.isEmpty()) return;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWImage.Buffer icons = GLFWImage.malloc(images.size(), stack);
                for (int i = 0; i < images.size(); i++) {
                    NativeImage image = images.get(i);
                    ByteBuffer pixels =
                            MemoryUtil.memAlloc(image.getWidth() * image.getHeight() * 4);
                    pixels.asIntBuffer().put(image.getPixelsRGBA());
                    pixelBuffers.add(pixels);
                    icons.position(i);
                    icons.width(image.getWidth());
                    icons.height(image.getHeight());
                    icons.pixels(pixels);
                }
                icons.position(0);
                GLFW.glfwSetWindowIcon(window.getWindow(), icons);
            }
        } catch (Exception e) {
            LOGGER.warn("Не установить иконку окна Blockfield", e);
        } finally {
            pixelBuffers.forEach(MemoryUtil::memFree);
            images.forEach(NativeImage::close);
        }
    }
}
