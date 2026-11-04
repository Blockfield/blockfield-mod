package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.StanceTransition;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import java.util.List;

/** Shared stance overlay math and native animation timing adapters. */
public final class StanceLayers {
    private StanceLayers() {}

    public static StanceTransition.Snapshot snapshot(Object player) {
        if (!(player instanceof StanceTransition.Access access)) return null;
        var stance = access.blockfield$stance();
        return stance == null ? null : stance.snapshot();
    }

    public static float blend(float before, float after, float coefficient) {
        // Preserve native endpoint bits, including signed zero and authored full-strength poses.
        return coefficient == 0
                ? before
                : coefficient == 1 ? after : before + coefficient * (after - before);
    }

    public static boolean retainCrawl(
            boolean eased,
            float flat,
            float toFlat,
            float fromFlat,
            int crawlNotDoingTick,
            int age) {
        if (!eased || flat <= 0) return false;
        if (toFlat == 0) return true;
        if (toFlat != flat || fromFlat != flat) return false;
        // A ceiling hold freezes the exit with the endpoints collapsed onto the frozen flat and
        // the age frozen mid-flight, so keep the node for as long as the hold lasts; release
        // re-targets toFlat to 0 and the exit fades with the shared flat like a normal exit.
        // A settled TaCZ prone also rests collapsed, but its age is settled and its Crawl stop
        // is old, so its stale overlay still drops; a blocked exit that never left full prone
        // stays while its Crawl stop is fresh.
        return age < StanceTransition.TICKS || crawlNotDoingTick < StanceTransition.TICKS;
    }

    /** Initializes the native LR fade from the shared stance phase. */
    public static AbstractFadeModifier lrFade(
            AbstractFadeModifier nativeFade, StanceTransition.Snapshot stance) {
        if (stance == null
                || !stance.eased()
                || stance.age() >= StanceTransition.TICKS
                || stance.fromCrouch() == stance.toCrouch()) return nativeFade;
        // Seed once from the shared phase; syncLrLayer re-samples the coefficient every tick.
        var fade = AbstractFadeModifier.standardFadeIn(StanceTransition.TICKS, Ease.LINEAR);
        try {
            fade.setBeginAnimation((IAnimation) PaGraph.Reflect.FADE_BEGIN.get(nativeFade));
            PaGraph.Reflect.FADE_TIME.setInt(fade, stance.age());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Pinned PA stance fade layout", error);
        }
        return fade;
    }

    private static final Class<?> STANCE_FADE_CLASS =
            AbstractFadeModifier.standardFadeIn(StanceTransition.TICKS, Ease.LINEAR).getClass();

    /**
     * True for an installed LR stance fade: PA's standard 8-tick linear fade, not
     * native/attack/stop.
     */
    public static boolean isStanceFade(AbstractFadeModifier fade) {
        if (fade == null || fade.getClass() != STANCE_FADE_CLASS) return false;
        try {
            return PaGraph.Reflect.FADE_LENGTH.getInt(fade) == StanceTransition.TICKS
                    && PaGraph.Reflect.standardEase(fade) == Ease.LINEAR;
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Pinned PA stance fade layout", error);
        }
    }

    /**
     * Samples the shared crouch coefficient into every installed LR stance fade of one layer. A
     * held stance freezes the fade at its frozen progress; an evolving stance re-seeds it, so held
     * and interrupted transitions track the coefficient instead of a separate clock. Runs after the
     * layer's tick on every runtime (native selector, canonical apply, observer replay).
     */
    public static void syncLrLayer(
            List<AbstractModifier> modifiers, StanceTransition.Snapshot stance) {
        if (stance == null || !stance.eased()) return;
        int time;
        if (stance.fromCrouch() == stance.toCrouch()) {
            // Held or settled: freeze at the frozen progress (a settled fade is complete).
            time = Math.max(0, Math.min(stance.age(), StanceTransition.TICKS));
        } else {
            float span = stance.toCrouch() - stance.fromCrouch();
            float progress = (stance.crouch() - stance.fromCrouch()) / span;
            if (!Float.isFinite(progress)) return;
            time = Math.round(Math.max(0, Math.min(1, progress)) * StanceTransition.TICKS);
        }
        try {
            for (var modifier : modifiers)
                if (modifier instanceof AbstractFadeModifier fade && isStanceFade(fade))
                    PaGraph.Reflect.FADE_TIME.setInt(fade, time);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Pinned PA stance fade layout", error);
        }
    }

    /** Keep 8 ± 1-tick authored FP clips; only retime outliers, including their sound progress. */
    public static long crawlDelta(String name, float seconds, long deltaNs) {
        if (!("crawl_start".equals(name) || "crawl_end".equals(name))) return deltaNs;
        float ticks = seconds * 20;
        int duration = StanceTransition.TICKS;
        if (!Float.isFinite(ticks) || ticks <= 0 || Math.abs(ticks - duration) <= 1) return deltaNs;
        return Math.round(deltaNs * ((double) ticks / duration));
    }
}
