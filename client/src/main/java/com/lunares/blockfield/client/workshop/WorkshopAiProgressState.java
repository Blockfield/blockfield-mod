package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.network.packet.WorkshopAiProgressS2CPacket;
import com.lunares.blockfield.workshop.WorkshopAiProgress;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable presentation data for the radial menu/HUD; no screen lifecycle or rendering work. */
public record WorkshopAiProgressState(
        UUID ownerId,
        UUID jobId,
        WorkshopAiProgress progress,
        long serverNow,
        long receivedAtNanos) {
    public WorkshopAiProgressState {
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(progress);
    }

    public static WorkshopAiProgressState decode(
            WorkshopAiProgressS2CPacket packet, long receivedAtNanos) {
        return new WorkshopAiProgressState(
                packet.ownerId(),
                packet.jobId(),
                packet.progress(),
                packet.serverNow(),
                receivedAtNanos);
    }

    public boolean busy() {
        return progress.busy();
    }

    public String phaseKey() {
        return progress.phase().translationKey();
    }

    private long estimatedServerNow(long nowNanos) {
        long passed = Math.max(0, (nowNanos - receivedAtNanos) / 1_000_000);
        return serverNow > Long.MAX_VALUE - passed ? Long.MAX_VALUE : serverNow + passed;
    }

    /** Whole-job time next to an estimate (it covers the whole job), else the phase time. */
    public long elapsedSeconds(long nowNanos) {
        long from = estimated() ? progress.jobStartedAt() : progress.phaseStartedAt();
        return busy() ? Math.max(0, estimatedServerNow(nowNanos) - from) / 1000 : 0;
    }

    /** The bar stops here until the plan arrives: an estimate never claims completion. */
    public static final float MAX_FRACTION = 0.95f;

    public boolean estimated() {
        return busy() && progress.hasEstimate();
    }

    /** Share of the server's typical duration, capped at {@link #MAX_FRACTION}. */
    public float fraction(long nowNanos) {
        if (!estimated()) return 0;
        double share =
                (double) (estimatedServerNow(nowNanos) - progress.jobStartedAt())
                        / (progress.expectedDoneAt() - progress.jobStartedAt());
        return (float) Math.clamp(share, 0, MAX_FRACTION);
    }

    /** Whole minutes left rounded up, 0 for under a minute, -1 once past the estimate. */
    public static long etaMinutes(long leftMillis) {
        return leftMillis <= 0 ? -1 : leftMillis < 60_000 ? 0 : (leftMillis + 59_999) / 60_000;
    }

    /**
     * Past the server's typical duration: the bar holds at 95% and the UI says «Дольше обычного».
     */
    public boolean overdue(long nowNanos) {
        return estimated()
                && etaMinutes(progress.expectedDoneAt() - estimatedServerNow(nowNanos)) < 0;
    }

    public net.minecraft.network.chat.Component etaLabel(long nowNanos) {
        long minutes = etaMinutes(progress.expectedDoneAt() - estimatedServerNow(nowNanos));
        return minutes < 0
                ? net.minecraft.network.chat.Component.translatable(
                        "blockfield.workshop.ai.progress.longer")
                : minutes == 0
                        ? net.minecraft.network.chat.Component.translatable(
                                "blockfield.workshop.ai.progress.eta_soon")
                        : net.minecraft.network.chat.Component.translatable(
                                "blockfield.workshop.ai.progress.eta", minutes);
    }

    public long remainingSeconds(long nowNanos) {
        if (!busy()) return 0;
        long remaining = Math.max(0, progress.phaseDeadlineAt() - estimatedServerNow(nowNanos));
        return remaining / 1000 + (remaining % 1000 == 0 ? 0 : 1);
    }

    public String elapsed(long nowNanos) {
        return duration(elapsedSeconds(nowNanos));
    }

    public String remaining(long nowNanos) {
        return duration(remainingSeconds(nowNanos));
    }

    public String parts() {
        return progress.partsTotal() == 0 ? "" : progress.partsDone() + "/" + progress.partsTotal();
    }

    public static String duration(long seconds) {
        seconds = Math.max(0, seconds);
        return seconds < 3600
                ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                : String.format(
                        Locale.ROOT,
                        "%d:%02d:%02d",
                        seconds / 3600,
                        seconds / 60 % 60,
                        seconds % 60);
    }

    public net.minecraft.network.chat.Component elapsedLabel(long nowNanos) {
        return net.minecraft.network.chat.Component.translatable(
                "blockfield.workshop.ai.progress.elapsed", elapsed(nowNanos));
    }

    public net.minecraft.network.chat.Component remainingLabel(long nowNanos) {
        return net.minecraft.network.chat.Component.translatable(
                "blockfield.workshop.ai.progress.remaining", remaining(nowNanos));
    }

    public net.minecraft.network.chat.Component partsLabel() {
        return net.minecraft.network.chat.Component.translatable(
                "blockfield.workshop.ai.progress.parts", parts());
    }
}
