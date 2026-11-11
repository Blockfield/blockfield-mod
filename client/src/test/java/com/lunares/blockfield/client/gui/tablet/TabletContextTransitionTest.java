package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.client.gui.ClientEventHandler;
import com.lunares.blockfield.client.gui.screen.ClassSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * P1: leaving the firing range with the class menu open must not leave the choice behind. Shared
 * context-transition clears visible/hidden deployment, DEPLOY tab, deferred open and prior-context
 * model, without marking a successful deploy.
 */
class TabletContextTransitionTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.set(net.minecraft.core.registries.BuiltInRegistries.ITEM, false);
        var intrusive =
                net.minecraft.core.MappedRegistry.class.getDeclaredField(
                        "unregisteredIntrusiveHolders");
        intrusive.setAccessible(true);
        intrusive.set(
                net.minecraft.core.registries.BuiltInRegistries.ITEM,
                new java.util.IdentityHashMap<>());
        com.lunares.blockfield.common.ModItems.register();
    }

    private static void setTablet(boolean open, Tablet.Tab tab, Object deployment)
            throws Exception {
        for (var entry :
                new Object[][] {{"open", open}, {"tab", tab}, {"deployment", deployment}}) {
            var field = Tablet.class.getDeclaredField((String) entry[0]);
            field.setAccessible(true);
            field.set(null, entry[1]);
        }
    }

    private static void setHandler(Object pending, String latest) throws Exception {
        var pendingField = ClientEventHandler.class.getDeclaredField("pendingScreen");
        pendingField.setAccessible(true);
        pendingField.set(null, pending);
        var latestField = ClientEventHandler.class.getDeclaredField("latestUiJson");
        latestField.setAccessible(true);
        latestField.set(null, latest);
    }

    private static Object getHandlerPending() throws Exception {
        var field = ClientEventHandler.class.getDeclaredField("pendingScreen");
        field.setAccessible(true);
        return field.get(null);
    }

    @Test
    void rangeExitClearsDeploymentWithoutMarkingDeploy() throws Exception {
        var mc = mock(Minecraft.class);
        mc.player = mock(LocalPlayer.class);
        var deployed = mock(ClassSelectionScreen.class);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mc);
            Tablet.onLogout();
            // Range context: visible class choice on DEPLOY, hub menu behind it.
            setTablet(true, Tablet.Tab.DEPLOY, deployed);
            setHandler(null, "{\"battleCredits\":5}");

            // Hub.send: hideHud, no closeDeploymentMenu.
            ClientEventHandler.handleUpdateUI("{\"showHud\":false,\"tabActive\":false}");

            assertNull(Tablet.deployment());
            assertEquals(Tablet.Tab.MENU, Tablet.tab());
            assertNull(getHandlerPending());
            // Exit must not pretend a successful deploy: put-away guard stays off.
            assertFalse(Tablet.freshDeploy(System.nanoTime()));
        } finally {
            Tablet.onLogout();
        }
    }

    @Test
    void deferredRangeChoiceCancelledOnHubTransition() throws Exception {
        var mc = mock(Minecraft.class);
        mc.player = mock(LocalPlayer.class);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mc);
            Tablet.onLogout();
            setTablet(false, Tablet.Tab.MENU, null);
            // Class menu arrived during ReceivingLevelScreen: deferred open of the range choice.
            Runnable deferred = () -> Tablet.openDeployment(mock(ClassSelectionScreen.class));
            setHandler(deferred, "{\"battleCredits\":5}");

            // Exit to hub while still loading: deferred range open must die, not reopen later.
            ClientEventHandler.handleUpdateUI("{\"showHud\":false,\"tabActive\":false}");

            assertNull(Tablet.deployment());
            assertNull(getHandlerPending());
            assertFalse(Tablet.freshDeploy(System.nanoTime()));
        } finally {
            Tablet.onLogout();
        }
    }
}
