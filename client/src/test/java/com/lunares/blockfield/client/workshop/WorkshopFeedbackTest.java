package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.client.gui.BfTheme;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class WorkshopFeedbackTest {
    @Test
    void serverRefusalsKeepTheirSeverityBehindAGreyTag() {
        assertEquals(BfTheme.DANGER, WorkshopFeedback.color("§c[Мастерская] Нет доступа", null));
        assertEquals(
                BfTheme.WARN,
                WorkshopFeedback.color("§7[Разметка] §eСначала задайте выделение", null));
        assertEquals(
                BfTheme.TEXT_SECONDARY,
                WorkshopFeedback.color("§7[Разметка] Последняя правка отменена.", null));
        // WorldEdit styles its errors instead of embedding legacy codes.
        assertEquals(
                BfTheme.DANGER,
                WorkshopFeedback.color(
                        "Make a region selection first.", ChatFormatting.RED.getColor()));
        var message =
                Component.literal("[WorldEdit] ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("Select a region").withStyle(ChatFormatting.RED));
        assertEquals(ChatFormatting.RED.getColor(), WorkshopFeedback.styled(message));
    }

    @Test
    void theLatestPlainLineIsShownBrieflyThenDisappears() {
        WorkshopFeedback.accept("§c[Мастерская] Игрок уже вышел.", null, 1_000);
        assertEquals("[Мастерская] Игрок уже вышел.", WorkshopFeedback.current(1_000));
        assertEquals(BfTheme.DANGER, WorkshopFeedback.color());
        WorkshopFeedback.accept("§7", null, 2_000);
        assertEquals("[Мастерская] Игрок уже вышел.", WorkshopFeedback.current(2_000));
        assertEquals("", WorkshopFeedback.current(1_001 + WorkshopFeedback.SHOW_NS));
    }

    @Test
    void aiActionsWaitOutTheServerDebounce() {
        assertFalse(WorkshopAiClient.throttled(Long.MIN_VALUE, 0));
        assertTrue(WorkshopAiClient.throttled(0, WorkshopAiClient.SPACING_NS - 1));
        assertFalse(WorkshopAiClient.throttled(0, WorkshopAiClient.SPACING_NS));
    }
}
