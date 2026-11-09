package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.List;
import java.util.Objects;

public final class AnimationResources {
    private AnimationResources() {}

    public record Digest(long a, long b, long c, long d) {
        public static Digest of(byte[] bytes) {
            if (bytes.length != 32) throw new IllegalArgumentException("Digest requires 32 bytes");
            var b = java.nio.ByteBuffer.wrap(bytes);
            return new Digest(b.getLong(), b.getLong(), b.getLong(), b.getLong());
        }

        public byte[] bytes() {
            return java.nio.ByteBuffer.allocate(32)
                    .putLong(a)
                    .putLong(b)
                    .putLong(c)
                    .putLong(d)
                    .array();
        }

        public static Digest hash(byte[] bytes) {
            try {
                return of(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }
    }

    public static final class Bytes {
        private final byte[] value;

        public Bytes(byte[] value) {
            this.value = value.clone();
        }

        public byte[] value() {
            return value.clone();
        }

        public int size() {
            return value.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes b && java.util.Arrays.equals(value, b.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }
    }

    public sealed interface ResourceRef
            permits EquipmentRef,
                    ItemRef,
                    ClipRef,
                    ConfigRef,
                    DefinitionRef,
                    AttachmentRef,
                    FirstPersonRef,
                    MetadataRef,
                    InputRef,
                    VehicleRef,
                    FunctionRef,
                    SeedRef {
        long resourceGeneration();

        long targetEpoch();

        long contentId();

        Digest dependencyDigest();
    }

    public record EquipmentRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public EquipmentRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record ItemRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public ItemRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record ClipRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public ClipRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record ConfigRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public ConfigRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record DefinitionRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public DefinitionRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record AttachmentRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public AttachmentRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record FirstPersonRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public FirstPersonRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record MetadataRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public MetadataRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record InputRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public InputRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record VehicleRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public VehicleRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record FunctionRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public FunctionRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record SeedRef(
            long resourceGeneration, long targetEpoch, long contentId, Digest dependencyDigest)
            implements ResourceRef {
        public SeedRef {
            Objects.requireNonNull(dependencyDigest, "dependencyDigest");
        }
    }

    public record Definition(ResourceRef ref, Bytes value, List<ResourceRef> dependencies) {
        public Definition {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(value, "value");
            dependencies = List.copyOf(dependencies);
            AnimationSchema.count(dependencies.size(), AnimationSchema.MAX_NODES, "dependencies");
        }
    }

    /**
     * Generation-keyed immutable definitions; staged publication never exposes a partial closure.
     */
    public static final class Store {
        private final java.util.Map<ResourceRef, Definition> definitions =
                new java.util.HashMap<>();
        private final java.util.Map<ResourceRef, Integer> pins = new java.util.HashMap<>();

        /**
         * Validates the additions against the store plus the additions, then publishes them
         * together; a refused batch leaves the store unchanged (only the new entries are staged).
         */
        public void installAll(List<Definition> additions) {
            var staged = new java.util.HashMap<ResourceRef, Definition>();
            for (Definition definition : additions) {
                AnimationSchema.validateFinite(definition);
                Definition previous = definitions.get(definition.ref());
                if (previous == null) previous = staged.putIfAbsent(definition.ref(), definition);
                if (previous != null && !previous.equals(definition))
                    throw new IllegalArgumentException("Resource ref changed value");
            }
            java.util.function.Function<ResourceRef, Definition> view =
                    ref -> {
                        Definition known = definitions.get(ref);
                        return known != null ? known : staged.get(ref);
                    };
            for (Definition definition : additions)
                closure(
                        definition.ref(),
                        view,
                        new java.util.HashSet<>(),
                        new java.util.HashSet<>(),
                        0);
            definitions.putAll(staged);
        }

        public Definition get(ResourceRef ref) {
            Definition value = definitions.get(ref);
            if (value == null) throw new IllegalArgumentException("Missing animation resource");
            return value;
        }

        public int size() {
            return definitions.size();
        }

        public long retainedBytes() {
            long size = 0;
            for (Definition d : definitions.values()) size = Math.addExact(size, d.value().size());
            return size;
        }

        public Lease pin(List<ResourceRef> roots) {
            var all = new java.util.HashSet<ResourceRef>();
            for (ResourceRef ref : roots)
                closure(ref, definitions::get, all, new java.util.HashSet<>(), 0);
            for (ResourceRef ref : all)
                if (pins.getOrDefault(ref, 0) == Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Reference pin overflow");
            for (ResourceRef ref : all) pins.merge(ref, 1, Integer::sum);
            return new Lease(this, java.util.Set.copyOf(all));
        }

        public void retireUnpinned(long generation) {
            retireUnpinned(generation, ref -> false);
        }

        /** {@link #retireUnpinned(long)} that keeps the unpinned definitions {@code keep} names. */
        public void retireUnpinned(
                long generation, java.util.function.Predicate<ResourceRef> keep) {
            definitions
                    .keySet()
                    .removeIf(
                            ref ->
                                    ref.resourceGeneration() == generation
                                            && !pins.containsKey(ref)
                                            && !keep.test(ref));
        }

        /**
         * Single pass over every definition: the caller pins the live closure first, so one scan
         * retires exactly what the per-generation loop would, without re-scanning the whole store
         * for each retained generation.
         */
        public void retireUnpinned() {
            definitions.keySet().removeIf(ref -> !pins.containsKey(ref));
        }

        private static void closure(
                ResourceRef ref,
                java.util.function.Function<ResourceRef, Definition> values,
                java.util.Set<ResourceRef> done,
                java.util.Set<ResourceRef> active,
                int depth) {
            AnimationSchema.count(depth, AnimationSchema.MAX_DEPTH, "resource depth");
            if (active.contains(ref))
                throw new IllegalArgumentException("Resource dependency cycle");
            if (done.contains(ref)) return;
            Definition definition = values.apply(ref);
            if (definition == null)
                throw new IllegalArgumentException("Missing animation dependency: " + ref);
            active.add(ref);
            for (ResourceRef dependency : definition.dependencies()) {
                // Explicit older-generation refs remain pinned instead of resolving through a newer
                // cache.
                closure(dependency, values, done, active, depth + 1);
            }
            active.remove(ref);
            done.add(ref);
        }
    }

    public static final class Lease implements AutoCloseable {
        private Store store;
        private final java.util.Set<ResourceRef> refs;

        private Lease(Store store, java.util.Set<ResourceRef> refs) {
            this.store = store;
            this.refs = refs;
        }

        @Override
        public void close() {
            if (store == null) return;
            for (ResourceRef ref : refs) {
                int n = store.pins.get(ref);
                if (n == 1) store.pins.remove(ref);
                else store.pins.put(ref, n - 1);
            }
            store = null;
        }
    }
}
