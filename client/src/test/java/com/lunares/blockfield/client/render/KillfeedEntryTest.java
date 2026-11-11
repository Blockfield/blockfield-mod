package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Killfeed entry: cause priority and the FFA cells the shared top bar consumes. */
class KillfeedEntryTest {
    @Test
    void suicideIsNeverAGunKill() {
        assertEquals(
                KillfeedEntry.Cause.ENVIRONMENT, KillfeedEntry.resolve(true, true, true, true));
    }

    @Test
    void knifeBeatsTheHeldGun() {
        assertEquals(KillfeedEntry.Cause.KNIFE, KillfeedEntry.resolve(false, true, false, true));
    }

    @Test
    void explosionBeatsTheHeldGun() {
        assertEquals(
                KillfeedEntry.Cause.EXPLOSIVE, KillfeedEntry.resolve(false, false, true, true));
    }

    @Test
    void gunNeedsAGunDamage() {
        assertEquals(KillfeedEntry.Cause.GUN, KillfeedEntry.resolve(false, false, false, true));
        assertEquals(
                KillfeedEntry.Cause.ENVIRONMENT, KillfeedEntry.resolve(false, false, false, false));
    }

    @Test
    void longNamesShareTheRowWithoutEatingTheIcon() {
        String attacker = "ОченьДлинныйНикнеймАтакующегоИгрока";
        String target = "ОченьДлинныйНикнеймЦелиИгрока";
        for (int maxWidth : new int[] {120, 200, 320}) {
            var row = KillfeedEntry.layout(HudTopBarTest.METRICS, attacker, target, maxWidth);
            assertTrue(row.width() <= maxWidth, "row width at " + maxWidth);
            assertTrue(row.attacker().endsWith("…"));
            assertTrue(row.target().endsWith("…"));
        }
    }

    @Test
    void shortNamesStayWhole() {
        var row = KillfeedEntry.layout(HudTopBarTest.METRICS, "Альфа", "Браво", 320);
        assertEquals("Альфа", row.attacker());
        assertEquals("Браво", row.target());
        assertEquals(
                HudTopBarTest.METRICS.width("Альфа")
                        + 6
                        + KillfeedEntry.ICON_W
                        + 6
                        + HudTopBarTest.METRICS.width("Браво"),
                row.width());
    }

    @Test
    void ffaScoreNeedsBothHalves() {
        var ui = new JsonObject();
        assertEquals(null, HudRenderer.ffaScore(ui));
        ui.addProperty("frags", 7);
        assertEquals(null, HudRenderer.ffaScore(ui));
        ui.addProperty("killLimit", 30);
        assertEquals("7/30", HudRenderer.ffaScore(ui));
    }

    @Test
    void ffaLeaderCoversSoloTiedAndEmpty() {
        var ui = new JsonObject();
        assertEquals(null, HudRenderer.ffaLeader(ui));
        ui.addProperty("leaderName", "Хантер");
        ui.addProperty("leaderKills", 0);
        assertEquals("лидера нет", HudRenderer.ffaLeader(ui));
        ui.addProperty("leaderKills", 11);
        assertEquals("Хантер 11", HudRenderer.ffaLeader(ui));
        ui.addProperty("leaderTied", 3);
        assertEquals("лидеры: 3 по 11", HudRenderer.ffaLeader(ui));
    }
}
