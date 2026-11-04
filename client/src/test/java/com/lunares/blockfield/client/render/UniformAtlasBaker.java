package com.lunares.blockfield.client.render;

import static com.lunares.blockfield.client.render.UniformModel.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.common.item.UniformItem.Role;
import com.lunares.blockfield.common.item.UniformItem.Side;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Bakes every kit's texture and UV layout: packs one island per cube, paints materials and details
 * at PX (12) texels per model unit, ray-traces ambient occlusion against the kit's own geometry and
 * pads islands for mipmaps. Runs only with BF_UNIFORM_BAKE=1 (from the client module): writes
 * src/main/resources/assets/blockfield/{uniforms, textures/models/uniform, textures/item}.
 */
class UniformAtlasBaker {
    static final List<String> KITS =
            List.of(
                    "infantry_red",
                    "infantry_blue",
                    "engineer_red",
                    "engineer_blue",
                    "engineer_neutral",
                    "sniper_red",
                    "sniper_blue",
                    "sniper_neutral",
                    "airborne_red",
                    "airborne_blue",
                    "airborne_neutral",
                    "drone_red",
                    "drone_blue",
                    "drone_neutral",
                    "dm_neutral");
    static final int PX = 12, WIDTH = 128, GAP = 1;
    private static final String[] BONES = {
        "head", "body", "right_arm", "left_arm", "right_leg", "left_leg"
    };
    // cloth, armor, pouch, dark, metal, mark, webbing, glass, edge, rubber, hardware, leather,
    // scrim, scrim dark,
    // shemagh, jute, brass, hazard
    static final Map<Side, int[]> PALETTES =
            Map.of(
                    Side.RED,
                            rgb(
                                    "4b4935 6d6b50 77745a 2c2d29 d8d6cc a3302e 5e5c47 343d40 85836a 3d423f 8a8e88 30302c 7b7a55 55553b 62624c 8f8a60 c9a13f d9ae2c"),
                    Side.BLUE,
                            rgb(
                                    "bcad8f 625c47 6b644e 33342e e4e1d6 2a50b8 5f5a47 343d40 9c917c 3d423f 8a8e88 36352f c4b28a 9a8764 b3a68a ddcfa8 c9a13f d9ae2c"),
                    Side.NEUTRAL,
                            rgb(
                                    "5a5a58 3e403d 4a4c48 232729 dcdad5 c1632a 454744 343d40 706f6f 3d423f 8a8e88 2c2e2e 787876 4d4d4b 5d5d59 8f8f8a b8963c d9ae2c"));

    private static int[] rgb(String hex) {
        return Arrays.stream(hex.split(" ")).mapToInt(h -> Integer.parseInt(h, 16)).toArray();
    }

    @Test
    void bake() throws Exception {
        Assumptions.assumeTrue(System.getenv("BF_UNIFORM_BAKE") != null);
        var assets = Path.of("src/main/resources/assets/blockfield");
        Files.createDirectories(assets.resolve("uniforms"));
        Files.createDirectories(assets.resolve("textures/models/uniform"));
        for (String kit : KITS) {
            var role =
                    Role.valueOf(kit.substring(0, kit.lastIndexOf('_')).toUpperCase(Locale.ROOT));
            var side =
                    Side.valueOf(kit.substring(kit.lastIndexOf('_') + 1).toUpperCase(Locale.ROOT));
            var layout = pack(role, side);
            var json = new JsonObject();
            json.addProperty("width", layout.width());
            json.addProperty("height", layout.height());
            var uv = new JsonObject();
            new TreeMap<>(layout.uv())
                    .forEach(
                            (path, origin) -> {
                                var pair = new JsonArray();
                                pair.add(origin[0]);
                                pair.add(origin[1]);
                                uv.add(path, pair);
                            });
            json.add("uv", uv);
            Files.writeString(
                    assets.resolve("uniforms/" + kit + ".json"),
                    new GsonBuilder().create().toJson(json) + "\n");
            ImageIO.write(
                    new Painter(role, side, layout).paint(),
                    "png",
                    assets.resolve("textures/models/uniform/" + kit + ".png").toFile());
        }
        for (var side : Side.values())
            ImageIO.write(
                    icon(PALETTES.get(side)),
                    "png",
                    assets.resolve(
                                    "textures/item/uniform_"
                                            + side.name().toLowerCase(Locale.ROOT)
                                            + ".png")
                            .toFile());
    }

    /** One MC box-UV island per cube, shelf-packed tallest first with a one-unit mip gap. */
    static Layout pack(Role role, Side side) {
        var materials = UniformModel.materials(role, side);
        var dummy = new HashMap<String, int[]>();
        materials.keySet().forEach(path -> dummy.put(path, new int[2]));
        var cubes = new ArrayList<Map.Entry<String, int[]>>();
        forEachCube(
                new UniformModel(role, side, new Layout(WIDTH, WIDTH, dummy)),
                (bone, path, pose, cube) -> {
                    float w = cube.maxX - cube.minX,
                            h = cube.maxY - cube.minY,
                            d = cube.maxZ - cube.minZ;
                    cubes.add(
                            Map.entry(
                                    path,
                                    new int[] {
                                        (int) Math.ceil(2 * d + 2 * w) + GAP,
                                        (int) Math.ceil(d + h) + GAP
                                    }));
                });
        cubes.sort(
                (a, b) ->
                        b.getValue()[1] != a.getValue()[1]
                                ? b.getValue()[1] - a.getValue()[1]
                                : b.getValue()[0] - a.getValue()[0]);
        var uv = new HashMap<String, int[]>();
        int x = GAP, y = GAP, row = 0;
        for (var cube : cubes) {
            int[] size = cube.getValue();
            if (x + size[0] > WIDTH) {
                x = GAP;
                y += row;
                row = 0;
            }
            uv.put(cube.getKey(), new int[] {x, y});
            x += size[0];
            row = Math.max(row, size[1]);
        }
        int height = (y + row + 3) / 4 * 4;
        return new Layout(WIDTH, height, Map.copyOf(uv));
    }

    interface CubeVisitor {
        void visit(String bone, String path, PoseStack.Pose pose, ModelPart.Cube cube);
    }

    static void forEachCube(UniformModel model, CubeVisitor visitor) {
        ModelPart[] parts = {
            model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg
        };
        for (int i = 0; i < parts.length; i++) {
            String bone = BONES[i];
            parts[i].visit(
                    new PoseStack(),
                    (pose, path, index, cube) -> visitor.visit(bone, bone + path, pose, cube));
        }
    }

    static ModelPart.Polygon[] polygons(ModelPart.Cube cube) {
        try {
            var field = ModelPart.Cube.class.getDeclaredField("polygons");
            field.setAccessible(true);
            return (ModelPart.Polygon[]) field.get(cube);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Oriented occluder in model units: world → cube-local affine plus the cube's local box. */
    private record Occluder(
            String bone,
            double[] inverse,
            float[] box,
            double cx,
            double cy,
            double cz,
            double radius) {
        /** Distance to the first hit along a unit ray, or +inf. */
        double hit(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
            double[] m = inverse;
            double lx = m[0] * ox + m[1] * oy + m[2] * oz + m[3],
                    ly = m[4] * ox + m[5] * oy + m[6] * oz + m[7],
                    lz = m[8] * ox + m[9] * oy + m[10] * oz + m[11];
            double ex = m[0] * dx + m[1] * dy + m[2] * dz,
                    ey = m[4] * dx + m[5] * dy + m[6] * dz,
                    ez = m[8] * dx + m[9] * dy + m[10] * dz;
            double near = 0, far = max;
            double[] o = {lx, ly, lz}, d = {ex, ey, ez};
            for (int axis = 0; axis < 3; axis++) {
                double lo = box[axis], hi = box[axis + 3];
                if (Math.abs(d[axis]) < 1e-9) {
                    if (o[axis] < lo || o[axis] > hi) return Double.POSITIVE_INFINITY;
                    continue;
                }
                double t1 = (lo - o[axis]) / d[axis], t2 = (hi - o[axis]) / d[axis];
                if (t1 > t2) {
                    double t = t1;
                    t1 = t2;
                    t2 = t;
                }
                near = Math.max(near, t1);
                far = Math.min(far, t2);
                if (near > far) return Double.POSITIVE_INFINITY;
            }
            return near > 1e-4 ? near : Double.POSITIVE_INFINITY;
        }
    }

    enum Face {
        FRONT,
        BACK,
        XNEG,
        XPOS,
        TOP,
        BOTTOM
    }

    /** What the painter knows about one texel. */
    record Texel(
            String path,
            String name,
            int material,
            Face face,
            double s,
            double t,
            double fw,
            double fh,
            double x,
            double y,
            double z,
            int tx,
            int ty,
            int cube) {
        double edge() {
            return Math.min(Math.min(s, fw - s), Math.min(t, fh - t));
        }

        boolean named(String prefix) {
            return name.startsWith(prefix);
        }
    }

    static final class Painter {
        private static final double AO_RANGE = 3, AO_STEP = .25;
        private static final int AO_RAYS = 48;
        private final Role role;
        private final Side side;
        private final Layout layout;
        private final int[] palette;
        private final int width, height;
        private final int[] argb;
        private final boolean[] covered;
        private final List<Occluder> occluders = new ArrayList<>();

        Painter(Role role, Side side, Layout layout) {
            this.role = role;
            this.side = side;
            this.layout = layout;
            this.palette = PALETTES.get(side);
            width = layout.width() * PX;
            height = layout.height() * PX;
            argb = new int[width * height];
            covered = new boolean[width * height];
        }

        BufferedImage paint() {
            var materials = UniformModel.materials(role, side);
            var model = new UniformModel(role, side, layout);
            forEachCube(
                    model, (bone, path, pose, cube) -> occluders.add(occluder(bone, pose, cube)));
            int[] index = {0};
            forEachCube(
                    model,
                    (bone, path, pose, cube) -> {
                        for (var polygon : polygons(cube))
                            face(bone, path, materials.get(path), pose, cube, polygon, index[0]);
                        index[0]++;
                    });
            dilate();
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, argb, 0, width);
            return image;
        }

        private static Occluder occluder(String bone, PoseStack.Pose pose, ModelPart.Cube cube) {
            var inverse = new Matrix4f(pose.pose()).invert();
            // Pose works in blocks; everything here is in model units (1/16 block).
            double[] m = {
                inverse.m00(),
                inverse.m10(),
                inverse.m20(),
                inverse.m30() * 16,
                inverse.m01(),
                inverse.m11(),
                inverse.m21(),
                inverse.m31() * 16,
                inverse.m02(),
                inverse.m12(),
                inverse.m22(),
                inverse.m32() * 16
            };
            var center =
                    pose.pose()
                            .transformPosition(
                                    (cube.minX + cube.maxX) / 32,
                                    (cube.minY + cube.maxY) / 32,
                                    (cube.minZ + cube.maxZ) / 32,
                                    new Vector3f())
                            .mul(16);
            double radius =
                    Math.sqrt(
                                    sq(cube.maxX - cube.minX)
                                            + sq(cube.maxY - cube.minY)
                                            + sq(cube.maxZ - cube.minZ))
                            / 2;
            return new Occluder(
                    bone,
                    m,
                    new float[] {cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ},
                    center.x,
                    center.y,
                    center.z,
                    radius);
        }

        private void face(
                String bone,
                String path,
                int material,
                PoseStack.Pose pose,
                ModelPart.Cube cube,
                ModelPart.Polygon polygon,
                int cubeIndex) {
            var v = polygon.vertices;
            double[][] local = new double[4][], world = new double[4][], tex = new double[4][];
            for (int i = 0; i < 4; i++) {
                local[i] = new double[] {v[i].pos.x(), v[i].pos.y(), v[i].pos.z()};
                var w =
                        pose.pose()
                                .transformPosition(
                                        v[i].pos.x() / 16,
                                        v[i].pos.y() / 16,
                                        v[i].pos.z() / 16,
                                        new Vector3f())
                                .mul(16);
                world[i] = new double[] {w.x, w.y, w.z};
                tex[i] = new double[] {v[i].u * width, v[i].v * height};
            }
            // Outward local normal from the cube centre; cubes are axis-aligned in their own part.
            double[] centre = {
                (cube.minX + cube.maxX) / 2,
                (cube.minY + cube.maxY) / 2,
                (cube.minZ + cube.maxZ) / 2
            };
            double[] fc = new double[3];
            for (int i = 0; i < 4; i++) for (int k = 0; k < 3; k++) fc[k] += local[i][k] / 4;
            int axis = 0;
            double best = -1;
            for (int k = 0; k < 3; k++) {
                double extent =
                        Math.abs(fc[k] - centre[k])
                                / Math.max(
                                        1e-6,
                                        (k == 0
                                                ? cube.maxX - cube.minX
                                                : k == 1
                                                        ? cube.maxY - cube.minY
                                                        : cube.maxZ - cube.minZ));
                if (extent > best) {
                    best = extent;
                    axis = k;
                }
            }
            double sign = Math.signum(fc[axis] - centre[axis]);
            Face face =
                    axis == 2
                            ? (sign < 0 ? Face.FRONT : Face.BACK)
                            : axis == 0
                                    ? (sign < 0 ? Face.XNEG : Face.XPOS)
                                    : (sign < 0 ? Face.TOP : Face.BOTTOM);
            var normalLocal =
                    new Vector3f(
                            axis == 0 ? (float) sign : 0,
                            axis == 1 ? (float) sign : 0,
                            axis == 2 ? (float) sign : 0);
            var n = pose.normal().transform(new Vector3f(normalLocal)).normalize();
            // Face frame: s to the viewer's right, t downwards (model y grows down); top/bottom: s
            // = x, t = z.
            double[] right =
                    switch (face) {
                        case FRONT -> new double[] {1, 0, 0};
                        case BACK -> new double[] {-1, 0, 0};
                        case XNEG -> new double[] {0, 0, -1};
                        case XPOS -> new double[] {0, 0, 1};
                        default -> new double[] {1, 0, 0};
                    };
            double[] down =
                    face == Face.TOP || face == Face.BOTTOM
                            ? new double[] {0, 0, 1}
                            : new double[] {0, 1, 0};
            double sMin = Double.MAX_VALUE,
                    sMax = -Double.MAX_VALUE,
                    tMin = Double.MAX_VALUE,
                    tMax = -Double.MAX_VALUE;
            for (var p : local) {
                double s = dot(p, right), t = dot(p, down);
                sMin = Math.min(sMin, s);
                sMax = Math.max(sMax, s);
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
            }
            // Affine texel → local/world from three corners.
            double[] du = sub(tex[1], tex[0]), dv = sub(tex[3], tex[0]);
            double det = du[0] * dv[1] - du[1] * dv[0];
            if (Math.abs(det) < 1e-9) return;
            int u0 = (int) Math.floor(min(tex, 0)), u1 = (int) Math.ceil(max(tex, 0));
            int v0 = (int) Math.floor(min(tex, 1)), v1 = (int) Math.ceil(max(tex, 1));
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.equals("cloth")
                    || name.equals("webbing")
                    || name.equals("adjuster")
                    || name.equals("jute"))
                name =
                        path.substring(path.lastIndexOf('/', path.lastIndexOf('/') - 1) + 1)
                                .replace('/', ':');
            // Ambient occlusion on a coarse grid in texel space, bilinearly sampled per texel.
            int gw = Math.max(2, (int) Math.ceil((u1 - u0) / (AO_STEP * PX)) + 1),
                    gh = Math.max(2, (int) Math.ceil((v1 - v0) / (AO_STEP * PX)) + 1);
            double[] ao = new double[gw * gh];
            var nearby = new ArrayList<Occluder>();
            double[] mid = mix(world, .5, .5);
            double faceRadius = Math.sqrt(sq(sMax - sMin) + sq(tMax - tMin)) / 2;
            for (var o : occluders)
                if (o.bone().equals(bone)
                        && Math.sqrt(
                                        sq(o.cx() - mid[0])
                                                + sq(o.cy() - mid[1])
                                                + sq(o.cz() - mid[2]))
                                < AO_RANGE + o.radius() + faceRadius) nearby.add(o);
            var random = new Random(path.hashCode() * 31L + face.ordinal());
            for (int gy = 0; gy < gh; gy++)
                for (int gx = 0; gx < gw; gx++) {
                    double tu = u0 + (u1 - u0) * gx / (double) (gw - 1),
                            tv = v0 + (v1 - v0) * gy / (double) (gh - 1);
                    double a = ((tu - tex[0][0]) * dv[1] - (tv - tex[0][1]) * dv[0]) / det,
                            b = ((tv - tex[0][1]) * du[0] - (tu - tex[0][0]) * du[1]) / det;
                    a = clamp(a, 0, 1);
                    b = clamp(b, 0, 1);
                    double[] p = new double[3];
                    for (int k = 0; k < 3; k++)
                        p[k] =
                                world[0][k]
                                        + a * (world[1][k] - world[0][k])
                                        + b * (world[3][k] - world[0][k])
                                        + n.get(k) * .02;
                    ao[gy * gw + gx] = occlusion(p, n, nearby, random);
                }
            for (int ty = Math.max(0, v0); ty < Math.min(height, v1); ty++)
                for (int tx = Math.max(0, u0); tx < Math.min(width, u1); tx++) {
                    double cu = tx + .5, cv = ty + .5;
                    double a = ((cu - tex[0][0]) * dv[1] - (cv - tex[0][1]) * dv[0]) / det,
                            b = ((cv - tex[0][1]) * du[0] - (cu - tex[0][0]) * du[1]) / det;
                    if (a < 0 || a > 1 || b < 0 || b > 1) continue;
                    double[] pl = new double[3], pw = new double[3];
                    for (int k = 0; k < 3; k++) {
                        pl[k] =
                                local[0][k]
                                        + a * (local[1][k] - local[0][k])
                                        + b * (local[3][k] - local[0][k]);
                        pw[k] =
                                world[0][k]
                                        + a * (world[1][k] - world[0][k])
                                        + b * (world[3][k] - world[0][k]);
                    }
                    var texel =
                            new Texel(
                                    path,
                                    name,
                                    material,
                                    face,
                                    dot(pl, right) - sMin,
                                    dot(pl, down) - tMin,
                                    sMax - sMin,
                                    tMax - tMin,
                                    pw[0],
                                    pw[1],
                                    pw[2],
                                    tx,
                                    ty,
                                    cubeIndex);
                    double gx = (cu - u0) / (u1 - u0) * (gw - 1),
                            gy = (cv - v0) / (v1 - v0) * (gh - 1);
                    int ix = Math.min(gw - 2, (int) gx), iy = Math.min(gh - 2, (int) gy);
                    double fx = gx - ix, fy = gy - iy;
                    double occlusion =
                            lerp(
                                    lerp(ao[iy * gw + ix], ao[iy * gw + ix + 1], fx),
                                    lerp(ao[(iy + 1) * gw + ix], ao[(iy + 1) * gw + ix + 1], fx),
                                    fy);
                    int color = Surfaces.paint(texel, role, palette, n);
                    int alpha = color >>> 24;
                    double light = (.4 + .6 * occlusion) * Surfaces.light(texel);
                    argb[ty * width + tx] = alpha << 24 | shade(color, light);
                    covered[ty * width + tx] = true;
                }
        }

        private static double occlusion(
                double[] p, Vector3f n, List<Occluder> nearby, Random random) {
            if (nearby.isEmpty()) return 1;
            // Tangent frame for a cosine-weighted hemisphere.
            double nx = n.x, ny = n.y, nz = n.z;
            double[] tangent =
                    Math.abs(nx) < .9
                            ? cross(new double[] {nx, ny, nz}, new double[] {1, 0, 0})
                            : cross(new double[] {nx, ny, nz}, new double[] {0, 1, 0});
            normalize(tangent);
            double[] bitangent = cross(new double[] {nx, ny, nz}, tangent);
            double blocked = 0;
            for (int i = 0; i < AO_RAYS; i++) {
                double r1 = (i + random.nextDouble()) / AO_RAYS, r2 = random.nextDouble();
                double r = Math.sqrt(r1), phi = 2 * Math.PI * r2, h = Math.sqrt(1 - r1);
                double dx =
                        tangent[0] * r * Math.cos(phi) + bitangent[0] * r * Math.sin(phi) + nx * h;
                double dy =
                        tangent[1] * r * Math.cos(phi) + bitangent[1] * r * Math.sin(phi) + ny * h;
                double dz =
                        tangent[2] * r * Math.cos(phi) + bitangent[2] * r * Math.sin(phi) + nz * h;
                double nearest = Double.POSITIVE_INFINITY;
                for (var o : nearby)
                    nearest = Math.min(nearest, o.hit(p[0], p[1], p[2], dx, dy, dz, AO_RANGE));
                if (nearest < AO_RANGE) blocked += 1 - nearest / AO_RANGE * .6;
            }
            return clamp(1 - 1.15 * blocked / AO_RAYS, 0, 1);
        }

        /**
         * Pads every island with its edge colours so mip levels never average in foreign islands.
         */
        private void dilate() {
            boolean[] filled = covered.clone();
            for (int pass = 0; pass < PX; pass++) {
                boolean[] next = filled.clone();
                int[] result = argb.clone();
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        int i = y * width + x;
                        if (filled[i]) continue;
                        int r = 0, g = 0, b = 0, count = 0;
                        for (int k = 0; k < 4; k++) {
                            int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0),
                                    ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                            if (nx < 0
                                    || ny < 0
                                    || nx >= width
                                    || ny >= height
                                    || !filled[ny * width + nx]) continue;
                            int c = argb[ny * width + nx];
                            r += c >> 16 & 255;
                            g += c >> 8 & 255;
                            b += c & 255;
                            count++;
                        }
                        if (count == 0) continue;
                        result[i] = 255 << 24 | r / count << 16 | g / count << 8 | b / count;
                        next[i] = true;
                    }
                System.arraycopy(result, 0, argb, 0, argb.length);
                filled = next;
            }
            for (int i = 0; i < argb.length; i++)
                if (!filled[i]) argb[i] = 0xff000000 | palette[DARK];
        }
    }

    static BufferedImage icon(int[] colors) {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 15; y++)
            for (int x = 2; x < 14; x++) {
                if (y < 5 && 6 <= x && x <= 9 || y > 7 && (x < 4 || x > 11)) continue;
                int material =
                        y < 6 && (x < 5 || x > 10) ? MARK : 5 <= x && x <= 10 ? ARMOR : CLOTH;
                image.setRGB(x, y, 0xff000000 | colors[material]);
            }
        return image;
    }

    static int shade(int color, double k) {
        int r = (int) clamp(Math.round((color >> 16 & 255) * k), 0, 255);
        int g = (int) clamp(Math.round((color >> 8 & 255) * k), 0, 255);
        int b = (int) clamp(Math.round((color & 255) * k), 0, 255);
        return r << 16 | g << 8 | b;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1]};
    }

    private static double min(double[][] p, int k) {
        return Math.min(Math.min(p[0][k], p[1][k]), Math.min(p[2][k], p[3][k]));
    }

    private static double max(double[][] p, int k) {
        return Math.max(Math.max(p[0][k], p[1][k]), Math.max(p[2][k], p[3][k]));
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        };
    }

    private static void normalize(double[] v) {
        double l = Math.sqrt(dot(v, v));
        for (int k = 0; k < 3; k++) v[k] /= l;
    }

    private static double[] mix(double[][] p, double a, double b) {
        double[] r = new double[3];
        for (int k = 0; k < 3; k++)
            r[k] = p[0][k] + a * (p[1][k] - p[0][k]) + b * (p[3][k] - p[0][k]);
        return r;
    }
}
