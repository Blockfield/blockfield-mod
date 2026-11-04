package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.mixin.DisplayAccessor;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DisplayBatcherTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void irisMainAndShadowInverseKeepDisplayPositionsFixedWhenCameraMoves() {
        Vector3f origin = new Vector3f(-64, 80, -16);
        Vector3f local = new Vector3f(9.5f, 15.5f, 12);
        Vector3f world = new Vector3f(origin).add(local);
        for (Matrix4f base :
                new Matrix4f[] {
                    new Matrix4f().rotateX(0.15f).rotateY(0.6f),
                    new Matrix4f().translate(0, 0, -160).rotateX(0.9f).rotateZ(0.4f)
                }) {
            Matrix4f original = new Matrix4f(base);
            for (Vector3f camera :
                    new Vector3f[] {new Vector3f(-55, 90, 5), new Vector3f(-39, 92, 13)}) {
                Vector3f offset = new Vector3f(origin).sub(camera);
                Matrix4f iris =
                        DisplayBatcher.batchModelView(base, offset.x, offset.y, offset.z, true);
                Vector3f drawn = iris.transformPosition(new Vector3f(local).add(offset));
                Matrix4f vanilla =
                        DisplayBatcher.batchModelView(base, offset.x, offset.y, offset.z, false);
                assertTrue(drawn.equals(vanilla.transformPosition(new Vector3f(local)), 1e-4f));
                Vector3f reconstructed =
                        iris.invert(new Matrix4f())
                                .transformPosition(new Vector3f(drawn))
                                .add(camera);
                assertTrue(world.equals(reconstructed, 1e-4f), reconstructed.toString());
                assertEquals(original, base);
            }
        }
    }

    private static List<DataValue<?>> data(float scale, ItemStack item) {
        // Serializers need the bootstrap, so no static accessors.
        var scaleData = new EntityDataAccessor<>(12, EntityDataSerializers.VECTOR3);
        var itemData = new EntityDataAccessor<>(23, EntityDataSerializers.ITEM_STACK);
        return List.of(
                DataValue.create(scaleData, new Vector3f(scale)), DataValue.create(itemData, item));
    }

    @Test
    void displaySentAgainWithTheSameDataIsRecognised() {
        assertTrue(
                DisplayBatcher.sameData(
                        data(2, new ItemStack(Items.PLAYER_HEAD)),
                        data(2, new ItemStack(Items.PLAYER_HEAD))));
        assertTrue(DisplayBatcher.sameData(null, null));
    }

    @Test
    void rotationBackFromTheServerReloadIsTheSame() {
        var rotation = new EntityDataAccessor<>(13, EntityDataSerializers.QUATERNION);
        assertTrue(
                DisplayBatcher.sameData(
                        List.of(
                                DataValue.create(
                                        rotation, new Quaternionf(0, 0.1305f, 0, 0.9914f))),
                        List.of(
                                DataValue.create(
                                        rotation, new Quaternionf(-0f, 0.13050001f, 0, 0.9914f)))));
        assertFalse(
                DisplayBatcher.sameData(
                        List.of(
                                DataValue.create(
                                        rotation, new Quaternionf(0, 0.1305f, 0, 0.9914f))),
                        List.of(DataValue.create(rotation, new Quaternionf(0, 0.14f, 0, 0.99f)))));
    }

    @Test
    void anyChangedValueIsNotTheSameDisplay() {
        assertFalse(
                DisplayBatcher.sameData(
                        data(2, new ItemStack(Items.PLAYER_HEAD)),
                        data(3, new ItemStack(Items.PLAYER_HEAD))));
        assertFalse(
                DisplayBatcher.sameData(
                        data(2, new ItemStack(Items.PLAYER_HEAD)),
                        data(2, new ItemStack(Items.SKELETON_SKULL))));
        assertFalse(
                DisplayBatcher.sameData(
                        data(2, ItemStack.EMPTY), data(2, ItemStack.EMPTY).subList(0, 1)));
        assertFalse(DisplayBatcher.sameData(null, data(2, ItemStack.EMPTY)));
    }

    @Test
    void headProfileMatchesCachedMetadataAfterResolution() {
        var resolved = new ResolvableProfile(new GameProfile(new UUID(0, 1), "MapHead"));
        var bakedHead = new ItemStack(Items.PLAYER_HEAD);
        bakedHead.set(DataComponents.PROFILE, resolved);
        var snapshot = data(2, bakedHead);
        var arrivingHead = new ItemStack(Items.PLAYER_HEAD);
        var unresolved =
                new ResolvableProfile(Optional.of("MapHead"), Optional.empty(), new PropertyMap());
        arrivingHead.set(DataComponents.PROFILE, unresolved);
        assertFalse(unresolved.isResolved());
        assertFalse(DisplayBatcher.sameData(snapshot, data(2, arrivingHead)));

        arrivingHead.remove(DataComponents.PROFILE);
        assertFalse(DisplayBatcher.sameData(snapshot, data(2, arrivingHead)));

        arrivingHead.set(DataComponents.PROFILE, resolved);
        assertTrue(resolved.isResolved());
        assertTrue(DisplayBatcher.sameData(snapshot, data(2, arrivingHead)));

        arrivingHead.set(
                DataComponents.PROFILE,
                new ResolvableProfile(new GameProfile(new UUID(0, 2), "OtherHead")));
        assertFalse(DisplayBatcher.sameData(snapshot, data(2, arrivingHead)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adoptedDisplayRebakedBeforeItsFirstTickIsRetriedAfterRenderStateArrives()
            throws Exception {
        var world = mock(ClientLevel.class);
        var clientSide = Level.class.getField("isClientSide");
        clientSide.setAccessible(true);
        clientSide.setBoolean(world, true);
        class ReturningDisplay extends Display.TextDisplay
                implements DisplayBatcher.Flag, DisplayAccessor {
            boolean baked = true;

            ReturningDisplay() {
                super(EntityType.TEXT_DISPLAY, world);
                updateRenderState = true; // Spawn metadata is pending its first entity tick.
            }

            @Override
            public boolean blockfield$baked() {
                return baked;
            }

            @Override
            public void blockfield$setBaked(boolean value) {
                baked = value;
            }

            @Override
            public float blockfield$viewRange() {
                return 1;
            }
        }
        var display = new ReturningDisplay();
        var candidates = (Map<Display, Long>) batcherField("candidates").get(null);
        var rejected = (Set<Display>) batcherField("rejected").get(null);
        var dirty = (Set<Long>) batcherField("dirty").get(null);
        var sections = (Map<Long, Object>) batcherField("sections").get(null);
        var baked = (Map<Display, Object>) batcherField("baked").get(null);
        var sectionType = Class.forName(DisplayBatcher.class.getName() + "$Section");
        var constructor = sectionType.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        var section = constructor.newInstance(0L);
        var members = sectionType.getDeclaredField("members");
        members.setAccessible(true);
        ((List<Display>) members.get(section)).add(display);
        var snapshotType = Class.forName(DisplayBatcher.class.getName() + "$Snapshot");
        var snapshot = snapshotType.getDeclaredConstructors()[0];
        snapshot.setAccessible(true);
        var bake = DisplayBatcher.class.getDeclaredMethod("bake", long.class, List.class);
        bake.setAccessible(true);
        var schedule = DisplayBatcher.class.getDeclaredMethod("schedule", boolean.class);
        schedule.setAccessible(true);
        var reset = DisplayBatcher.class.getDeclaredMethod("reset", boolean.class);
        reset.setAccessible(true);
        var mc = mock(Minecraft.class);
        var dispatcher = mock(EntityRenderDispatcher.class);
        EntityRenderer renderer = mock(EntityRenderer.class);
        when(mc.getEntityRenderDispatcher()).thenReturn(dispatcher);
        when(dispatcher.getRenderer(display)).thenReturn(renderer);
        try (var minecraft = mockStatic(Minecraft.class);
                var buffers = mockStatic(VertexBuffer.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mc);
            candidates.put(display, batcherField("ticks").getLong(null) - 40);
            sections.put(0L, section);
            baked.put(display, snapshot.newInstance(0L, 0d, 0d, 0d, 0f, 0f, 0));
            assertNull(display.renderState());
            // A late arrival schedules a new bake of a section adopted before entity ticking.
            bake.invoke(null, 0L, List.of(display));
            assertFalse(display.baked);
            assertFalse(rejected.contains(display), "Missing render state is temporary");
            display.tick();
            assertNotNull(display.renderState());
            schedule.invoke(null, false);
            assertTrue(dirty.contains(0L));
            bake.invoke(null, 0L, List.of(display));
            verify(dispatcher).getRenderer(display);
            assertFalse(rejected.contains(display));
        } finally {
            reset.invoke(null, false);
        }
    }

    private static java.lang.reflect.Field batcherField(String name) throws Exception {
        var field = DisplayBatcher.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    @SuppressWarnings("unchecked")
    void resetReleasesOldEntitiesAfterClearingTheirBakedFlags() throws Exception {
        var sectionType = Class.forName(DisplayBatcher.class.getName() + "$Section");
        var constructor = sectionType.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(0L);
        var membersField = sectionType.getDeclaredField("members");
        membersField.setAccessible(true);
        var members = (List<Display>) membersField.get(section);
        class OldDisplay extends Display.TextDisplay implements DisplayBatcher.Flag {
            boolean baked = true;

            OldDisplay() {
                super(EntityType.TEXT_DISPLAY, null);
            }

            @Override
            public boolean blockfield$baked() {
                return baked;
            }

            @Override
            public void blockfield$setBaked(boolean value) {
                assertFalse(members.isEmpty());
                baked = value;
            }
        }
        var display = new OldDisplay();
        members.add(display);
        var sectionsField = DisplayBatcher.class.getDeclaredField("sections");
        sectionsField.setAccessible(true);
        var sections = (Map<Long, Object>) sectionsField.get(null);
        sections.put(0L, section);
        var bakedField = DisplayBatcher.class.getDeclaredField("baked");
        bakedField.setAccessible(true);
        var baked = (Map<Display, Object>) bakedField.get(null);
        var returningField = DisplayBatcher.class.getDeclaredField("returning");
        returningField.setAccessible(true);
        var returning = (Map<Display, Long>) returningField.get(null);
        var reset = DisplayBatcher.class.getDeclaredMethod("reset", boolean.class);
        reset.setAccessible(true);
        try {
            returning.put(display, 0L);
            var collect =
                    DisplayBatcher.class.getDeclaredMethod("collect", Entity.class, List.class);
            collect.setAccessible(true);
            assertFalse((boolean) collect.invoke(null, display, new ArrayList<Display>()));
            var candidatesField = DisplayBatcher.class.getDeclaredField("candidates");
            candidatesField.setAccessible(true);
            assertFalse(((Map<Display, Long>) candidatesField.get(null)).containsKey(display));
            DisplayBatcher.changed(display);
            assertFalse(returning.containsKey(display));
            returning.put(display, 0L);
            baked.put(display, null);
            reset.invoke(null, true);
            assertFalse(display.baked);
            assertTrue(members.isEmpty());
            assertTrue(sections.isEmpty());
            assertTrue(baked.isEmpty());
            assertTrue(returning.isEmpty());
        } finally {
            reset.invoke(null, false);
        }
    }
}
