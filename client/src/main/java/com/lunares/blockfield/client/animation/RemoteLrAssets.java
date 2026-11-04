package com.lunares.blockfield.client.animation;

import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.PaGraph;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/**
 * U9 calls this on each staged native effective LR melee display before resource activation. It
 * reads the native Gson config and the shared clip file exactly as LR's selector would, keeping
 * private copies; nothing here is consulted while sampling.
 */
public final class RemoteLrAssets {
    private RemoteLrAssets() {}

    /** {@code display} is the native {@code MeleeDisplayInstance} LR resolved for {@code id}. */
    public static LrAssets.Display retain(LrAssets assets, String id, Object display) {
        Object config = invoke(display, "getThirdPersonAnimation");
        if (config == null) return assets.register(id, false, null, Map.of(), Map.of(), Map.of());
        String path = (String) field(config, "animationPath");
        // Native getAnimationPath() parses on every call; an unparsable path cannot load.
        ResourceLocation animation = path == null ? null : ResourceLocation.parse(path);
        Map<String, KeyframeAnimation> clips = Map.of();
        if (animation != null) {
            Map<String, KeyframeAnimation> file = files().get(animation);
            if (file != null) clips = file;
        }
        return assets.register(
                id,
                true,
                animation == null ? null : animation.toString(),
                section(field(config, "upper")),
                section(field(config, "lower")),
                clips);
    }

    /** Gson {@code Map<String,Object>}: String, or an index-preserving list; others absent. */
    private static Map<String, List<String>> section(Object value) {
        if (value == null) throw new PaGraph.Unsupported("Null LR config section");
        var result = new TreeMap<String, List<String>>();
        ((Map<?, ?>) value)
                .forEach(
                        (key, entry) -> {
                            if (entry instanceof String name)
                                result.put((String) key, List.of(name));
                            else if (entry instanceof List<?> list && !list.isEmpty()) {
                                var names = new ArrayList<String>();
                                for (Object element : list)
                                    names.add(element instanceof String name ? name : null);
                                result.put((String) key, names);
                            }
                        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, Map<String, KeyframeAnimation>> files() {
        try {
            var field = PlayerAnimatorAssetManager.class.getDeclaredField("animations");
            field.setAccessible(true);
            return (Map<ResourceLocation, Map<String, KeyframeAnimation>>)
                    field.get(PlayerAnimatorAssetManager.get());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object invoke(Object owner, String method) {
        try {
            return owner.getClass().getMethod(method).invoke(owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Pinned LR display accessor changed: " + method, failure);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned LR config field changed: " + name, failure);
        }
    }
}
