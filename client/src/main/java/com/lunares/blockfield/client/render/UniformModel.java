package com.lunares.blockfield.client.render;

import com.lunares.blockfield.common.combat.UniformGeometry;
import com.lunares.blockfield.common.item.UniformItem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.world.entity.LivingEntity;

/**
 * Cubes follow vanilla bones, including TaCZ/ParCool poses and first-person sleeves. Every cube of
 * every kit owns a UV island in that kit's texture; the layout and texture come from the test-side
 * UniformAtlasBaker.
 */
final class UniformModel extends HumanoidModel<LivingEntity> {
    static final int CLOTH = UniformGeometry.CLOTH,
            ARMOR = UniformGeometry.ARMOR,
            POUCH = UniformGeometry.POUCH,
            DARK = UniformGeometry.DARK,
            METAL = UniformGeometry.METAL,
            MARK = UniformGeometry.MARK,
            WEBBING = UniformGeometry.WEBBING,
            GLASS = UniformGeometry.GLASS,
            EDGE = UniformGeometry.EDGE,
            RUBBER = UniformGeometry.RUBBER,
            HARDWARE = UniformGeometry.HARDWARE,
            LEATHER = UniformGeometry.LEATHER,
            SCRIM = UniformGeometry.SCRIM,
            SCRIM_DARK = UniformGeometry.SCRIM_DARK,
            SHEMAGH = UniformGeometry.SHEMAGH,
            JUTE = UniformGeometry.JUTE,
            BRASS = UniformGeometry.BRASS,
            HAZARD = UniformGeometry.HAZARD;

    /**
     * Texture size in model units and each cube's texOffs, keyed by part path such as {@code
     * body/flap_0}.
     */
    record Layout(int width, int height, Map<String, int[]> uv) {}

    private static final Map<String, Layout> LAYOUTS = new ConcurrentHashMap<>();

    static String kit(UniformItem.Role role, UniformItem.Side side) {
        return (role + "_" + side).toLowerCase(java.util.Locale.ROOT);
    }

    static Layout layout(String kit) {
        return LAYOUTS.computeIfAbsent(kit, UniformModel::readLayout);
    }

    private static Layout readLayout(String kit) {
        try (var stream =
                UniformModel.class.getResourceAsStream(
                        "/assets/blockfield/uniforms/" + kit + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing uniform layout " + kit);
            var json =
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(
                                            stream, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var uv = new java.util.HashMap<String, int[]>();
            json.getAsJsonObject("uv")
                    .entrySet()
                    .forEach(
                            entry -> {
                                var origin = entry.getValue().getAsJsonArray();
                                uv.put(
                                        entry.getKey(),
                                        new int[] {
                                            origin.get(0).getAsInt(), origin.get(1).getAsInt()
                                        });
                            });
            return new Layout(
                    json.get("width").getAsInt(), json.get("height").getAsInt(), Map.copyOf(uv));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read uniform layout " + kit, e);
        }
    }

    UniformModel(UniformItem.Role role, UniformItem.Side side) {
        this(role, side, layout(kit(role, side)));
    }

    UniformModel(UniformItem.Role role, UniformItem.Side side, Layout layout) {
        super(new Geometry(layout).mesh(role, side).bakeRoot());
    }

    /** Part path to material for every cube, in build order; the baker paints and packs from it. */
    static Map<String, Integer> materials(UniformItem.Role role, UniformItem.Side side) {
        var geometry = new Geometry(null);
        geometry.mesh(role, side);
        return geometry.materials;
    }

    /** Replays {@link UniformGeometry} events into a {@link MeshDefinition}; UV stays here. */
    private static final class Geometry implements UniformGeometry.Sink {
        private final Layout layout;
        private final Map<String, Integer> materials = new LinkedHashMap<>();
        private final MeshDefinition mesh = new MeshDefinition();
        private final Map<String, PartDefinition> defs = new HashMap<>();

        private Geometry(Layout layout) {
            this.layout = layout;
            defs.put("", mesh.getRoot());
        }

        private LayerDefinition mesh(UniformItem.Role role, UniformItem.Side side) {
            UniformGeometry.build(role, side, this);
            return LayerDefinition.create(
                    mesh,
                    layout == null ? 256 : layout.width(),
                    layout == null ? 512 : layout.height());
        }

        @Override
        public void child(
                String parent,
                String name,
                float x,
                float y,
                float z,
                float xRot,
                float yRot,
                float zRot) {
            PartPose pose =
                    x == 0 && y == 0 && z == 0 && xRot == 0 && yRot == 0 && zRot == 0
                            ? PartPose.ZERO
                            : PartPose.offsetAndRotation(x, y, z, xRot, yRot, zRot);
            PartDefinition def =
                    defs.get(parent).addOrReplaceChild(name, CubeListBuilder.create(), pose);
            defs.put(parent.isEmpty() ? name : parent + "/" + name, def);
        }

        @Override
        public void box(
                String parent,
                String name,
                int material,
                float x,
                float y,
                float z,
                float w,
                float h,
                float d) {
            String path = parent + "/" + name;
            materials.put(path, material);
            int[] uv = layout == null ? new int[2] : layout.uv().get(path);
            if (uv == null)
                throw new IllegalStateException(
                        "No UV island for " + path + "; rerun UniformAtlasBaker");
            defs.get(parent)
                    .addOrReplaceChild(
                            name,
                            CubeListBuilder.create().texOffs(uv[0], uv[1]).addBox(x, y, z, w, h, d),
                            PartPose.ZERO);
        }
    }
}
