package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Estimated progress of the current AI generation: a bar that stops at 95% until the plan arrives
 * and the time-left text («≈ N мин осталось», then «Дольше обычного»). Draws nothing when the
 * server sent no estimate (old server, applying, extend).
 */
public final class WorkshopAiEtaBar {
    private WorkshopAiEtaBar() {}

    /** Bar fill for the current job, or -1 when there is no estimate. */
    public static float fraction() {
        var state = WorkshopAiClient.state();
        return fraction(
                WorkshopAiClient.progress(), state.jobId(), state.stage(), System.nanoTime());
    }

    static float fraction(WorkshopAiProgressState progress, UUID job, String stage, long now) {
        return estimated(progress, job, stage) ? progress.fraction(now) : -1;
    }

    /** Time-left text for the current job, or null when there is no estimate. */
    public static Component label() {
        var state = WorkshopAiClient.state();
        var progress = WorkshopAiClient.progress();
        return estimated(progress, state.jobId(), state.stage())
                ? progress.etaLabel(System.nanoTime())
                : null;
    }

    /** Draws the bar; false (and nothing drawn) without an estimate. */
    public static boolean render(GuiGraphics graphics, int x, int y, int width, int height) {
        float fill = fraction();
        if (fill < 0) return false;
        BfTheme.progress(graphics, x, y, width, height, fill, BfTheme.AMBER);
        return true;
    }

    private static boolean estimated(WorkshopAiProgressState progress, UUID job, String stage) {
        return progress != null
                && WorkshopToolsClient.busy(stage)
                && progress.jobId().equals(job)
                && progress.estimated();
    }
}
