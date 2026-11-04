package com.lunares.blockfield.client.workshop;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Numbers and dates of the Workshop UI: «1 240» with a no-break space, «сегодня, 21:40». */
final class WorkshopAiFormat {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private WorkshopAiFormat() {}

    /** Thousands grouped by U+00A0 so a count never breaks across lines (lang has no plurals). */
    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    /** The day part of a sketch date: today/yesterday by key, older ones as «14 окт., 18:03». */
    static String date(long epochMillis, long nowMillis, ZoneId zone, Locale locale) {
        var at = Instant.ofEpochMilli(epochMillis).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        String time = TIME.format(at);
        if (at.toLocalDate().equals(today))
            return Component.translatable("blockfield.workshop.ai.date.today", time).getString();
        if (at.toLocalDate().equals(today.minusDays(1)))
            return Component.translatable("blockfield.workshop.ai.date.yesterday", time)
                    .getString();
        return DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).format(at);
    }
}
