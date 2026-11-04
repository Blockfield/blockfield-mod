package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.render.WeaponHud;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** One context router for the radial, rebindable shortcuts and screen close keys. */
public final class WorkshopControls {
    public static final String CATEGORY = "key.categories.blockfield.workshop";

    /**
     * {@code workshop} is applied only while inside the workshop ({@link
     * com.lunares.blockfield.client.WorkshopKeyBindings}): vanilla KeyMapping routes one mapping
     * per key, so a global Z/V/G/B default would take those keys from ParCool/TaCZ/SBW in matches.
     */
    public record Binding(String action, KeyMapping key, InputConstants.Key workshop) {}

    private static Binding bind(String action, int code) {
        boolean rotate = action.equals("rotate");
        return new Binding(
                action,
                new KeyMapping(
                        rotate
                                ? "key.blockfield.workshop_sketch.rotate"
                                : "key.blockfield.workshop." + action,
                        InputConstants.Type.KEYSYM,
                        rotate ? code : GLFW.GLFW_KEY_UNKNOWN,
                        CATEGORY),
                InputConstants.Type.KEYSYM.getOrCreate(code));
    }

    /** Temporary workshop key for a mapping name, or null when it is not a workshop action. */
    public static InputConstants.Key workshopDefault(String name) {
        for (var binding : BINDINGS)
            if (binding.key().getName().equals(name)
                    && !binding.action().equals("rotate")
                    && binding.workshop().getValue() != GLFW.GLFW_KEY_UNKNOWN)
                return binding.workshop();
        return null;
    }

    public static final List<Binding> BINDINGS =
            List.of(
                    bind("radial", GLFW.GLFW_KEY_Z),
                    bind("ai", GLFW.GLFW_KEY_V),
                    bind("selection", GLFW.GLFW_KEY_G),
                    bind("copy", GLFW.GLFW_KEY_GRAVE_ACCENT),
                    bind("paste", GLFW.GLFW_KEY_Y),
                    bind("undo", GLFW.GLFW_KEY_B),
                    bind("redo", GLFW.GLFW_KEY_H),
                    bind("rotate", GLFW.GLFW_KEY_R),
                    bind("extend", GLFW.GLFW_KEY_N),
                    // J (the first proposal) opens the chest rig, which stays bound here.
                    bind("chain", GLFW.GLFW_KEY_K),
                    bind("library", GLFW.GLFW_KEY_UNKNOWN),
                    bind("ghost", GLFW.GLFW_KEY_UNKNOWN),
                    bind("maps", GLFW.GLFW_KEY_UNKNOWN),
                    bind("submit", GLFW.GLFW_KEY_UNKNOWN),
                    bind("keys", GLFW.GLFW_KEY_UNKNOWN));
    private static net.minecraft.client.gui.screens.Screen pendingFrom;
    private static String pendingAction;

    static boolean panelKey(Minecraft mc, int code, int scan) {
        for (String action : new String[] {"ai", "library", "keys"}) {
            if (!matches(action, code, scan)) continue;
            pendingFrom = mc.screen;
            pendingAction = action;
            return true;
        }
        return false;
    }

    private WorkshopControls() {}

    public static KeyMapping key(String action) {
        return BINDINGS.stream()
                .filter(b -> b.action().equals(action))
                .findFirst()
                .orElseThrow()
                .key();
    }

    public static void register() {
        for (var b : BINDINGS) KeyBindingHelper.registerKeyBinding(b.key());
    }

    public static boolean matches(String action, int code, int scan) {
        return key(action).matches(code, scan);
    }

    static boolean physicallyDown(Minecraft mc, KeyMapping mapping) {
        if (mapping.isUnbound()) return false;
        var key = KeyBindingHelper.getBoundKeyOf(mapping);
        long window = mc.getWindow().getWindow();
        return key.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS
                : InputConstants.isKeyDown(window, key.getValue());
    }

    public static void tick(Minecraft mc) {
        // Queue until after GLFW's char callback so an opening letter never enters the new draft.
        if (pendingAction != null) {
            String action = pendingAction;
            var from = pendingFrom;
            pendingAction = null;
            pendingFrom = null;
            if (mc.screen == from) execute(mc, action);
        }
        for (var binding : BINDINGS) {
            while (binding.key().consumeClick()) {
                if (!WeaponHud.inWorkshop() || mc.player == null || mc.screen != null) continue;
                if (mc.getWindow() != null
                        && InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_F3))
                    continue;
                if (binding.action().equals("radial")) WorkshopRadialScreen.open(mc, true);
                else execute(mc, binding.action());
            }
        }
    }

    static String route(
            String action,
            String stage,
            boolean selection,
            boolean hand,
            boolean preview,
            boolean panel) {
        if (action.equals("ai")
                || action.equals("library")
                || action.equals("radial")
                || action.equals(WorkshopKeyHelp.KEYS_ACTION)) return action;
        if (panel) return "blocked";
        if (action.equals("rotate") && hand && !selection) return "sketch:rotate";
        // Smart fill runs on the selection, so selection mode stays on while it is used.
        if (action.equals("extend")) return "fill";
        if (action.equals("chain"))
            return !selection && (preview || stage.equals("complete")) ? "sketch:chain" : "blocked";
        if (action.equals("selection")) return "selection";
        return switch (action) {
            case "copy", "paste", "rotate", "undo", "redo" -> "workshop:tool:" + action;
            case "ghost" -> "workshop:ghost";
            case "maps" -> "maps";
            case "submit" -> "confirm:submit";
            default -> action;
        };
    }

    public static void execute(Minecraft mc, String action) {
        if (!WeaponHud.inWorkshop() || mc.player == null) return;
        var state = WorkshopAiClient.state();
        String route =
                route(
                        action,
                        state.stage(),
                        WorkshopToolsClient.selectionMode(),
                        WorkshopSketchPlacement.held(),
                        state.stage().equals("preview"),
                        false);
        switch (route) {
            case "ai", "workshop:ai" -> {
                if (mc.screen instanceof WorkshopAiScreen) mc.screen.onClose();
                else mc.setScreen(new WorkshopAiScreen());
            }
            case "library", "workshop:library" -> {
                if (mc.screen instanceof WorkshopAiLibraryScreen) mc.screen.onClose();
                else mc.setScreen(new WorkshopAiLibraryScreen());
            }
            case "confirm:submit" -> WorkshopRadialScreen.confirmSubmit(mc);
            case "keys" -> WorkshopKeyHelp.open(mc);
            case "maps" -> WorkshopRadialScreen.maps();
            case "selection", "workshop:selection" -> WorkshopToolsClient.toggleSelection();
            case "sketch:rotate" -> WorkshopSketchPlacement.rotate();
            case "fill", "workshop:extend" -> WorkshopAiClient.fill(WorkshopAiClient.fillHint());
            case "sketch:chain", "workshop:chain" ->
                    WorkshopSketchPlacement.extend(mc.player.getLookAngle());
            case "workshop:ai_undo" -> {
                if (state.canUndo() && !WorkshopToolsClient.busy(state.stage()))
                    WorkshopAiClient.request("undo", "");
            }
            case "workshop:cancel" -> {
                WorkshopAiClient.cancelTranscription();
                WorkshopAiClient.request("cancel", "");
            }
            case "blocked" -> {}
            default -> send(route);
        }
    }

    public static void send(String action) {
        if (ClientPlayNetworking.canSend(MenuActionC2SPacket.TYPE))
            ClientPlayNetworking.send(new MenuActionC2SPacket(action));
    }
}
