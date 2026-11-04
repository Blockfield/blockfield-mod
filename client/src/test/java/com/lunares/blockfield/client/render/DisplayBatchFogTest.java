package com.lunares.blockfield.client.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DisplayBatchFogTest {
    @Test
    void stockShadersPreserveVanillaAndRecoverWorldAxisBatchOffsets() throws IOException {
        main(new String[0]);
    }

    public static void main(String[] args) throws IOException {
        for (String name :
                new String[] {
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
                    "rendertype_text_background"
                }) {
            String source;
            try (var stream =
                    DisplayBatchFogTest.class.getResourceAsStream(
                            "/assets/minecraft/shaders/core/" + name + ".vsh")) {
                check(stream != null, "Missing stock shader: " + name);
                source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            String patched = DisplayBatchFog.patch(name, source);
            check(patched != source, "Stock shader was rejected: " + name);
            check(
                    patched.contains(
                            "FogShape >= 2 ? transpose(mat3(ModelViewMat)) * ModelViewMat[3].xyz :"
                                    + " vec3(0.0)"),
                    "Vanilla fog or world-axis correction changed: " + name);
            check(patched.contains("FogShape % 2"), "Fog shape was not decoded: " + name);
            check(
                    DisplayBatchFog.patch("custom_shader", source) == source,
                    "Unknown shaders must remain vanilla");
            String noFog = source.replace("fog_distance(Position, FogShape)", "length(Position)");
            check(
                    DisplayBatchFog.patch(name, noFog) == noFog,
                    "An unsupported resource-pack shader was accepted");
            String ambiguous = source + source;
            check(
                    DisplayBatchFog.patch(name, ambiguous) == ambiguous,
                    "An ambiguous fog expression was accepted");
        }
        check(!DisplayBatchFog.supports(new Object()), "Unknown programs must remain vanilla");

        Vector3f vertex = new Vector3f(12.5981f, 10.529053f, 12.615662f);
        for (float yaw : new float[] {-180, -91, 0, 37, 179}) {
            for (float pitch : new float[] {-89, -60, 0, 41, 89}) {
                for (Vector3f offset :
                        new Vector3f[] {
                            new Vector3f(12.81f, -13, -8.06f),
                            new Vector3f(12.81f, -14.62f, -40),
                            new Vector3f(-500, 65, 277)
                        }) {
                    Matrix4f modelView =
                            new Matrix4f()
                                    .rotationYXZ(
                                            (float) Math.toRadians(yaw),
                                            (float) Math.toRadians(pitch),
                                            0)
                                    .translate(offset);
                    Vector3f recovered =
                            new Matrix3f(modelView)
                                    .transpose()
                                    .transform(modelView.getTranslation(new Vector3f()));
                    check(
                            recovered.distance(offset) < 0.001f,
                            "Section offset rotated with camera");
                    for (int shape = 0; shape <= 1; shape++) {
                        check(
                                Math.abs(fog(vertex, shape) - patchedFog(vertex, recovered, shape))
                                        < 0.001,
                                "Vanilla fog changed");
                        check(
                                Math.abs(
                                                fog(new Vector3f(vertex).add(offset), shape)
                                                        - patchedFog(vertex, recovered, shape + 2))
                                        < 0.001,
                                "Batch fog differs from vanilla camera-relative fog");
                    }
                }
            }
        }
        Vector3f world = new Vector3f(0, 10, 20);
        Vector3f view =
                new Matrix3f().rotationX((float) Math.toRadians(60)).transform(new Vector3f(world));
        check(
                Math.abs(fog(world, 1) - fog(view, 1)) > 1,
                "The scenario must catch camera-rotated cylinder fog");
    }

    private static double patchedFog(Vector3f vertex, Vector3f offset, int shape) {
        return fog(shape >= 2 ? new Vector3f(vertex).add(offset) : vertex, shape % 2);
    }

    private static double fog(Vector3f position, int shape) {
        return shape == 0
                ? position.length()
                : Math.max(Math.hypot(position.x, position.z), Math.abs(position.y));
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
