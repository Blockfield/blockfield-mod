package com.lunares.blockfield.client.render;

import java.util.List;

/**
 * ADR4 §8.3: one actual transformed {@code LivingEntityRenderer.render} invocation, captured at its
 * {@code renderToBuffer} call after every pose-affecting mixin. Immutable: arrays are copied in and
 * out. World positions are never folded into float matrices: {@link #origin} (the feet render
 * position the dispatcher lerped), {@link #offset} (renderer offset) and {@link #camera} stay
 * doubles, {@link #root} is the feet-relative float root and {@link #corners} the 48 local float
 * corners of the six {@code BodyGeometry} boxes. A world corner is {@code origin + (double) local}.
 *
 * <p>{@code rebaseExact}: the pose at the renderer's entry was exactly the dispatcher's float
 * translation {@code (float) ((origin - camera) + offset)}; the root then keeps the native rotation
 * bits and only the translation column subtracts {@code (float) (origin - camera)} (bit-exact when
 * the camera sits at the feet). Otherwise (GUI, foreign matrices) the root comes from the inverted
 * entry pose and is diagnostic.
 */
public record RenderCapture(
        long renderFrame,
        Pass pass,
        long invocation,
        int entityId,
        Identity identity,
        double[] origin,
        double[] offset,
        double[] camera,
        boolean rebaseExact,
        float[] root,
        float[] parts,
        List<String> boxIds,
        float[] corners) {
    /** Render pass of one invocation; only MAIN feeds F3+B and evidence. */
    public enum Pass {
        MAIN,
        SHADOW,
        OTHER
    }

    /**
     * The presentation the invocation rendered: connection/stream/scene, frame/revision, H/R/A,
     * target life/seat/teleport/subscription generations, resource/policy/compatibility/manifest
     * identity, the placement endpoints and sample decision, and the victim row's seed, applied
     * prefix and digests. Zeros/empty strings when the entity is not on an exact stream (native).
     */
    public record Identity(
            long connectionId,
            long observerStreamEpoch,
            long sceneGeneration,
            long frameId,
            long revision,
            long high,
            long rTick,
            int rAlpha,
            long aTick,
            int aAlpha,
            long targetEpoch,
            long seatGeneration,
            long teleportGeneration,
            long subscriptionGeneration,
            long resourceGeneration,
            long policyGeneration,
            String compatibilityDigest,
            String manifestDigest,
            long placementBefore,
            long placementAfter,
            double placementFraction,
            String sampleMode,
            long seedRef,
            long appliedEventSequence,
            String eventPrefixDigest,
            String presentationDigest,
            boolean driven,
            String unavailable) {
        public static final Identity NATIVE =
                new Identity(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", 0, 0, 0, "native",
                        0, 0, "", "", false, "");
    }

    public RenderCapture {
        if (origin.length != 3 || offset.length != 3 || camera.length != 3)
            throw new IllegalArgumentException("capture vectors are three doubles");
        if (root.length != 16 || parts.length != 36 || corners.length != boxIds.size() * 24)
            throw new IllegalArgumentException("capture layout");
        origin = origin.clone();
        offset = offset.clone();
        camera = camera.clone();
        root = root.clone();
        parts = parts.clone();
        boxIds = List.copyOf(boxIds);
        corners = corners.clone();
    }

    @Override
    public double[] origin() {
        return origin.clone();
    }

    @Override
    public double[] offset() {
        return offset.clone();
    }

    @Override
    public double[] camera() {
        return camera.clone();
    }

    @Override
    public float[] root() {
        return root.clone();
    }

    @Override
    public float[] parts() {
        return parts.clone();
    }

    @Override
    public float[] corners() {
        return corners.clone();
    }

    /** {@code origin + (double) local} for every corner, in box order. */
    public double[] worldCorners() {
        var out = new double[corners.length];
        for (int i = 0; i < corners.length; i++) out[i] = origin[i % 3] + (double) corners[i];
        return out;
    }
}
