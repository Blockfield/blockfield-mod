package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.lunares.blockfield.network.packet.WorkshopAiProgressS2CPacket;
import com.lunares.blockfield.workshop.WorkshopAiProgress;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkshopAiProgressStateTest {
    @Test
    void elapsedDeadlineAndPartsUseServerClockAndRoundRemainingUp() {
        long receipt = 100_000_000;
        var progress =
                new WorkshopAiProgress(WorkshopAiProgress.Phase.PARTS, 2, 4, 90_000, 160_500);
        var packet =
                new WorkshopAiProgressS2CPacket(
                        UUID.randomUUID(), UUID.randomUUID(), progress, 100_000);
        var state = WorkshopAiProgressState.decode(packet, receipt);
        assertTrue(state.busy());
        assertEquals("blockfield.workshop.ai.phase.parts", state.phaseKey());
        assertEquals(12, state.elapsedSeconds(receipt + 2_000_000_000));
        assertEquals(59, state.remainingSeconds(receipt + 2_000_000_000));
        assertEquals("0:12", state.elapsed(receipt + 2_000_000_000));
        assertEquals("0:59", state.remaining(receipt + 2_000_000_000));
        assertEquals("2/4", state.parts());
        assertSame(progress, state.progress());
        assertEquals(0, state.remainingSeconds(receipt + 90_000_000_000L));
        assertEquals(10, state.elapsedSeconds(receipt - 1_000_000_000));
        assertEquals("1:02:03", WorkshopAiProgressState.duration(3723));
        assertEquals("0:00", WorkshopAiProgressState.duration(-1));
    }

    @Test
    void estimateFillsTheBarUpToNinetyFivePercentAndCountsWholeMinutesUp() {
        long receipt = 1_000_000_000L;
        // Started at 10 s server time, typical finish at 310 s, hard bound at 900 s.
        var progress =
                new WorkshopAiProgress(
                        WorkshopAiProgress.Phase.GENERATION,
                        0,
                        0,
                        10_000,
                        900_000,
                        10_000,
                        310_000);
        var state =
                WorkshopAiProgressState.decode(
                        new WorkshopAiProgressS2CPacket(
                                UUID.randomUUID(), UUID.randomUUID(), progress, 160_000),
                        receipt);
        assertTrue(state.estimated());
        assertEquals(0.5f, state.fraction(receipt), 1e-6);
        assertEquals(150, state.elapsedSeconds(receipt), "elapsed counts from the job start");
        assertEquals(
                WorkshopAiProgressState.MAX_FRACTION,
                state.fraction(receipt + 149_000_000_000L),
                1e-6);
        assertEquals(
                WorkshopAiProgressState.MAX_FRACTION,
                state.fraction(receipt + 600_000_000_000L),
                1e-6,
                "past the estimate the bar never claims completion");
        assertEquals(3, WorkshopAiProgressState.etaMinutes(150_000));
        assertEquals(1, WorkshopAiProgressState.etaMinutes(60_000));
        assertEquals(2, WorkshopAiProgressState.etaMinutes(60_001));
        assertEquals(0, WorkshopAiProgressState.etaMinutes(59_999));
        assertEquals(-1, WorkshopAiProgressState.etaMinutes(0));
        assertEquals(
                "blockfield.workshop.ai.progress.eta",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                                state.etaLabel(receipt).getContents())
                        .getKey());
        assertEquals(
                "blockfield.workshop.ai.progress.longer",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                                state.etaLabel(receipt + 151_000_000_000L).getContents())
                        .getKey());
        var plain =
                WorkshopAiProgressState.decode(
                        new WorkshopAiProgressS2CPacket(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                new WorkshopAiProgress(
                                        WorkshopAiProgress.Phase.APPLYING, 0, 0, 10_000, 20_000),
                                15_000),
                        receipt);
        assertFalse(plain.estimated());
        assertEquals(0f, plain.fraction(receipt));
        assertEquals(-1f, WorkshopAiEtaBar.fraction(plain, plain.jobId(), "applying", receipt));
        assertEquals(
                0.5f, WorkshopAiEtaBar.fraction(state, state.jobId(), "planning", receipt), 1e-6);
        assertEquals(-1f, WorkshopAiEtaBar.fraction(state, UUID.randomUUID(), "planning", receipt));
        assertEquals(-1f, WorkshopAiEtaBar.fraction(null, state.jobId(), "planning", receipt));
    }

    @Test
    void noneAndEmptyPartCounterHaveNoBusyCountdown() {
        var state =
                WorkshopAiProgressState.decode(
                        new WorkshopAiProgressS2CPacket(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                WorkshopAiProgress.NONE,
                                1000),
                        0);
        assertFalse(state.busy());
        assertEquals("", state.parts());
        assertEquals(0, state.elapsedSeconds(5_000_000_000L));
        assertEquals(0, state.remainingSeconds(5_000_000_000L));
    }

    @Test
    void everyPhaseAndFormattingKeyExistsInBothLanguages() throws Exception {
        for (String language : new String[] {"ru_ru", "en_us"}) {
            try (var stream =
                    getClass()
                            .getResourceAsStream("/assets/blockfield/lang/" + language + ".json")) {
                assertNotNull(stream);
                var json =
                        JsonParser.parseReader(
                                        new InputStreamReader(stream, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                for (var phase : WorkshopAiProgress.Phase.values())
                    assertTrue(json.has(phase.translationKey()), phase.translationKey());
                for (String key :
                        new String[] {
                            "progress.eta_soon", "progress.longer", "speed_fast", "speed_quality"
                        })
                    assertFalse(
                            json.get("blockfield.workshop.ai." + key).getAsString().isBlank(), key);
                for (String key : new String[] {"elapsed", "remaining", "parts", "eta"})
                    assertTrue(
                            json.get("blockfield.workshop.ai.progress." + key)
                                    .getAsString()
                                    .contains("%s"));
                if (language.equals("ru_ru"))
                    assertEquals(
                            "Не дольше %s",
                            json.get("blockfield.workshop.ai.progress.remaining").getAsString());
            }
        }
    }
}
