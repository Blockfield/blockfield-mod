package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TabletTabsTest {
    @Test
    void roomsAndLeaderboardMenusSitOnTheirOwnTabs() {
        assertEquals(Tablet.Tab.MATCHES, Tablet.tabForMenu("rooms"));
        assertEquals(Tablet.Tab.STATS, Tablet.tabForMenu("leaderboard"));
        assertEquals(Tablet.Tab.MENU, Tablet.tabForMenu("hub"));
        assertEquals(Tablet.Tab.MENU, Tablet.tabForMenu("match"));
        assertEquals(Tablet.Tab.MENU, Tablet.tabForMenu("range"));
    }

    @Test
    void serverTabsShowOnlyOnHubFamilyMenus() {
        assertTrue(Tablet.serverTabs("hub"));
        assertTrue(Tablet.serverTabs("casual"));
        assertTrue(Tablet.serverTabs("rooms"));
        assertTrue(Tablet.serverTabs("leaderboard"));
        assertFalse(Tablet.serverTabs("match"));
        assertFalse(Tablet.serverTabs("range"));
        assertFalse(Tablet.serverTabs("range_maps"));
        assertFalse(Tablet.serverTabs("vote"));
        assertFalse(Tablet.serverTabs("ready"));
        assertFalse(Tablet.serverTabs(""));
    }

    @Test
    void menuTabAdoptsLiveUpdateFromAnotherPlace() {
        assertTrue(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "hub"));
        assertTrue(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "match"));
        assertTrue(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "range"));
        assertTrue(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "vote"));
        assertTrue(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "ready"));
    }

    @Test
    void backgroundTabsAndClosedTabletIgnoreLiveUpdate() {
        assertFalse(Tablet.adoptMenuUpdate(false, Tablet.Tab.MENU, "hub"));
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.MATCHES, "hub"));
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.STATS, "hub"));
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.MAP, "match"));
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.RESULT, "match"));
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.DEPLOY, "vote"));
        // Списки живут на своих вкладках: чужой live-ответ их не дёргает.
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "rooms"));
        assertFalse(Tablet.adoptMenuUpdate(true, Tablet.Tab.MENU, "leaderboard"));
    }
}
