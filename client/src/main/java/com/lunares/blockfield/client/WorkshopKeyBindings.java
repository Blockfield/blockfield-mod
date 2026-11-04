package com.lunares.blockfield.client;

import com.lunares.blockfield.client.render.WeaponHud;
import com.lunares.blockfield.client.workshop.WorkshopControls;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Workshop vanilla controls are temporary; user bindings and saved options keep their meaning. */
public final class WorkshopKeyBindings {
    private static final Map<String, InputConstants.Key> VANILLA =
            Map.ofEntries(
                    Map.entry("key.inventory", keyboard(GLFW.GLFW_KEY_E)),
                    Map.entry("key.sprint", keyboard(GLFW.GLFW_KEY_LEFT_CONTROL)),
                    Map.entry("key.sneak", keyboard(GLFW.GLFW_KEY_LEFT_SHIFT)),
                    Map.entry("key.drop", keyboard(GLFW.GLFW_KEY_Q)),
                    Map.entry("key.swapOffhand", keyboard(GLFW.GLFW_KEY_F)),
                    Map.entry("key.pickItem", InputConstants.Type.MOUSE.getOrCreate(2)),
                    Map.entry("key.togglePerspective", keyboard(GLFW.GLFW_KEY_F5)),
                    Map.entry("key.advancements", keyboard(GLFW.GLFW_KEY_L)),
                    Map.entry("key.socialInteractions", keyboard(GLFW.GLFW_KEY_P)),
                    Map.entry("key.loadToolbarActivator", keyboard(GLFW.GLFW_KEY_X)),
                    Map.entry("key.saveToolbarActivator", keyboard(GLFW.GLFW_KEY_C)));
    private static final IdentityHashMap<KeyMapping, Saved> SAVED = new IdentityHashMap<>();
    private static boolean active;

    private record Saved(InputConstants.Key previous, InputConstants.Key temporary) {}

    private WorkshopKeyBindings() {}

    private static InputConstants.Key keyboard(int key) {
        return InputConstants.Type.KEYSYM.getOrCreate(key);
    }

    private static InputConstants.Key current(KeyMapping mapping) {
        return InputConstants.getKey(mapping.saveString());
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(
                mc -> {
                    if (WeaponHud.inWorkshop() && !active) enter(mc.options.keyMappings);
                    else if (!WeaponHud.inWorkshop() && active) leave();
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> leave());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> leave());
    }

    static void enter(KeyMapping[] mappings) {
        if (active) return;
        active = true;
        for (KeyMapping mapping : mappings) {
            String name = mapping.getName();
            InputConstants.Key prior = current(mapping);
            InputConstants.Key pack = KeyDefaults.DEFAULTS.get(name);
            boolean combat =
                    name.startsWith("key.parcool.")
                            || name.startsWith("key.tacz.")
                            || name.startsWith("key.superbwarfare.")
                            || name.equals("key.pingwheel.ping_location");
            InputConstants.Key workshop = WorkshopControls.workshopDefault(name);
            // Mod-native defaults count as untouched too (TaCZ reload R would shadow sketch R).
            if (pack == null && (combat || workshop != null)) pack = mapping.getDefaultKey();
            if (pack == null || !pack.equals(prior)) continue;
            InputConstants.Key want = VANILLA.get(name);
            if (want == null) want = workshop;
            if (want == null && combat) want = InputConstants.UNKNOWN;
            if (want == null || want.equals(prior)) continue;
            SAVED.put(mapping, new Saved(prior, want));
            mapping.setDown(false);
            mapping.setKey(want);
        }
        KeyMapping.resetMapping();
    }

    static void leave() {
        for (var entry : SAVED.entrySet()) {
            KeyMapping mapping = entry.getKey();
            if (!current(mapping).equals(entry.getValue().temporary())) continue;
            mapping.setDown(false);
            mapping.setKey(entry.getValue().previous());
        }
        SAVED.clear();
        active = false;
        KeyMapping.resetMapping();
    }

    /**
     * Options saves may happen for video/audio settings while the temporary controls are active.
     */
    public static void withPersistentBindings(Runnable save) {
        IdentityHashMap<KeyMapping, Saved> swapped = new IdentityHashMap<>();
        for (var entry : SAVED.entrySet()) {
            KeyMapping mapping = entry.getKey();
            if (!current(mapping).equals(entry.getValue().temporary())) continue;
            swapped.put(mapping, entry.getValue());
            mapping.setKey(entry.getValue().previous());
        }
        if (!swapped.isEmpty()) KeyMapping.resetMapping();
        try {
            save.run();
        } finally {
            for (var entry : swapped.entrySet())
                if (current(entry.getKey()).equals(entry.getValue().previous()))
                    entry.getKey().setKey(entry.getValue().temporary());
            if (!swapped.isEmpty()) KeyMapping.resetMapping();
        }
    }
}
