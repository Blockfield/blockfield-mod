package com.lunares.blockfield.client.render;

import java.util.Set;

/** The stock shaders expect camera-relative positions, while display buffers are section-local. */
public final class DisplayBatchFog {
    private static final Set<String> SHADERS =
            Set.of(
                    "rendertype_entity_solid",
                    "rendertype_entity_cutout",
                    "rendertype_entity_cutout_no_cull",
                    "rendertype_entity_cutout_no_cull_z_offset",
                    "rendertype_entity_smooth_cutout",
                    "rendertype_entity_no_outline",
                    "rendertype_entity_translucent",
                    "rendertype_entity_translucent_cull",
                    "rendertype_item_entity_translucent_cull",
                    "rendertype_text",
                    "rendertype_text_intensity",
                    "rendertype_text_background");
    private static final String FOG = "fog_distance(Position, FogShape)";

    private DisplayBatchFog() {}

    public interface Flag {
        boolean blockfield$batchFog();

        void blockfield$setBatchFog(boolean supported);
    }

    public static boolean supports(Object program) {
        return program instanceof Flag flag && flag.blockfield$batchFog();
    }

    public static String patch(String name, String source) {
        if (!SHADERS.contains(name)
                || !source.contains("uniform mat4 ModelViewMat;")
                || !source.contains("uniform int FogShape;")
                || source.indexOf(FOG) < 0
                || source.indexOf(FOG) != source.lastIndexOf(FOG)) return source;
        // Shapes 0/1 retain vanilla fog. The batch's 2/3 recover world-axis camera offsets,
        // not view-axis positions: rotating cylinder fog by camera pitch changes its distance.
        return source.replace(
                FOG,
                "fog_distance(Position + (FogShape >= 2 ? "
                        + "transpose(mat3(ModelViewMat)) * ModelViewMat[3].xyz : vec3(0.0)), "
                        + "FogShape % 2)");
    }
}
