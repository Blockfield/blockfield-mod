package com.lunares.blockfield.common.combat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;

/**
 * Authoritative body geometry: exactly the six vanilla inner-skin cubes of {@code
 * PlayerModel.createMesh(NONE, false)} (wide model), identical for every player. No armor, no
 * uniform, no skin outer layers (hat/jacket/sleeves/pants), no slim/wide difference: armor does not
 * change the hitbox (owner decision 2026-10-13). All values are model pixels, bone-local; divide by
 * 16 for metres.
 */
public final class BodyGeometry {
    public static final int HEAD = 0,
            TORSO = 1,
            LEFT_ARM = 2,
            RIGHT_ARM = 3,
            LEFT_LEG = 4,
            RIGHT_LEG = 5;

    public enum Skin {
        WIDE,
        SLIM
    }

    /**
     * Id per the shared scheme: {@code "skin:inner"} for the six hit boxes, {@code
     * "uniform:&lt;bone&gt;/&lt;child path&gt;"} for the rendering-only uniform list.
     */
    public record Box(
            String id,
            int bone,
            org.joml.Matrix4fc local,
            float x,
            float y,
            float z,
            float w,
            float h,
            float d) {}

    private BodyGeometry() {}

    /**
     * The six vanilla {@code PlayerModel.createMesh(NONE, false)} inner cubes with the wide-model
     * part pivots, for every player whatever their skin model, customisation, uniform, armor or
     * curios.
     */
    public static List<Box> skin() {
        return List.of(
                new Box("skin:inner", HEAD, new Matrix4f(), -4, -8, -4, 8, 8, 8),
                new Box("skin:inner", TORSO, new Matrix4f(), -4, 0, -2, 8, 12, 4),
                new Box("skin:inner", RIGHT_ARM, new Matrix4f(), -3, -2, -2, 4, 12, 4),
                new Box("skin:inner", LEFT_ARM, new Matrix4f(), -1, -2, -2, 4, 12, 4),
                new Box("skin:inner", RIGHT_LEG, new Matrix4f(), -2, 0, -2, 4, 12, 4),
                new Box("skin:inner", LEFT_LEG, new Matrix4f(), -2, 0, -2, 4, 12, 4));
    }

    /**
     * Every uniform cube of the kit, with the child-path transform baked into {@code local}.
     * Rendering only (the {@code UniformModel} oracle): never hit geometry, never in {@link
     * #DIGEST}.
     */
    public static List<Box> uniform(
            com.lunares.blockfield.common.item.UniformItem.Role role,
            com.lunares.blockfield.common.item.UniformItem.Side side) {
        var boxes = new ArrayList<Box>();
        Map<String, Matrix4f> frames = new HashMap<>();
        frames.put("", new Matrix4f());
        UniformGeometry.build(
                role,
                side,
                new UniformGeometry.Sink() {
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
                        // Top-level children are the bones themselves: their pivot belongs to the
                        // rig (BodyRig.bones applies it), so their frame stays identity and only
                        // sub-bone nesting composes into local.
                        Matrix4f frame = new Matrix4f();
                        if (!parent.isEmpty()) {
                            frame.set(frames.get(parent)).translate(x / 16, y / 16, z / 16);
                            if (xRot != 0 || yRot != 0 || zRot != 0)
                                frame.rotate(
                                        new org.joml.Quaternionf().rotationZYX(zRot, yRot, xRot));
                        }
                        frames.put(parent.isEmpty() ? name : parent + "/" + name, frame);
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
                        String top =
                                parent.contains("/")
                                        ? parent.substring(0, parent.indexOf('/'))
                                        : parent;
                        boxes.add(
                                new Box(
                                        "uniform:" + parent + "/" + name,
                                        boneOf(top),
                                        new Matrix4f(frames.get(parent)),
                                        x,
                                        y,
                                        z,
                                        w,
                                        h,
                                        d));
                    }
                });
        return List.copyOf(boxes);
    }

    private static int boneOf(String bone) {
        return switch (bone) {
            case "head" -> HEAD;
            case "body" -> TORSO;
            case "left_arm" -> LEFT_ARM;
            case "right_arm" -> RIGHT_ARM;
            case "left_leg" -> LEFT_LEG;
            case "right_leg" -> RIGHT_LEG;
            default -> throw new IllegalArgumentException("Not a bone: " + bone);
        };
    }

    /** Vanilla {@code DefaultPlayerSkin} table: 18 entries, indices 0-8 slim. */
    public static boolean slimByDefault(java.util.UUID id) {
        return Math.floorMod(id.hashCode(), 18) < 9;
    }

    /** SHA-256 over the canonical six-box list in emission order. */
    public static final String DIGEST = digest();

    private static String digest() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            writeBoxes(out, skin());
            out.flush();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            var hex = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBoxes(DataOutputStream out, List<Box> boxes) throws IOException {
        float[] matrix = new float[16];
        for (Box box : boxes) {
            byte[] id = box.id().getBytes(StandardCharsets.UTF_8);
            out.writeInt(id.length);
            out.write(id);
            out.writeInt(box.bone());
            box.local().get(matrix);
            for (float value : matrix) out.writeInt(Float.floatToIntBits(value));
            out.writeInt(Float.floatToIntBits(box.x()));
            out.writeInt(Float.floatToIntBits(box.y()));
            out.writeInt(Float.floatToIntBits(box.z()));
            out.writeInt(Float.floatToIntBits(box.w()));
            out.writeInt(Float.floatToIntBits(box.h()));
            out.writeInt(Float.floatToIntBits(box.d()));
        }
    }
}
