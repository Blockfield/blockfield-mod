package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationTypes.AnimatorTag;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Phase-3 animation policy (ADR4 §6): while connected to a Blockfield server every
 * geometry-sensitive animation toggle is forced on for everyone. The stored user values are never
 * rewritten; getter hooks report the forced value and the GUI lock only disables the widgets.
 * Disconnect flips one flag, which restores the effective user values immediately.
 *
 * <p>Forced entries: ParCool {@code EnableAnimation}, {@code EnableFallingAnimation}, {@code
 * EnableLeanAnimationOfFastRun}, every registered {@code AnimatorList} animator (26, via {@code
 * Animation.setAnimator}), and TaCZ Tweaks {@code smootherCrawlAnimation}. Camera and
 * first-person-only settings stay personal.
 *
 * <p>Scope and ownership (U9b): only a Blockfield server arms the policy, by its READY manifest in
 * the configuration phase ({@link ClientAnimationResources}); the effective values are that
 * server's {@link com.lunares.blockfield.common.combat.AnimationPolicy}. Any other server, and
 * singleplayer, keeps the user's values.
 */
public final class AnimationPolicy {
    /** ParCool {@code Booleans} constant names forced on while connected. */
    public static final Set<String> FORCED_PARCOOL_NAMES =
            Set.of("EnableAnimation", "EnableFallingAnimation", "EnableLeanAnimationOfFastRun");

    /**
     * The 26 animator classes registered in pinned ParCool bf7 {@code AnimatorList}, in native
     * order, derived from U1's closed {@link AnimatorTag} registry so the two cannot drift. The two
     * Dive children ride with their host; declared-only {@code ExaggeratedSideDodgeAnimator} is
     * excluded and guarded by the pin test.
     */
    public static final List<String> ANIMATORS =
            com.lunares.blockfield.common.combat.AnimationPolicy.registered().stream()
                    .map(AnimationPolicy::animatorClass)
                    .toList();

    static String animatorClass(AnimatorTag tag) {
        if (tag == AnimatorTag.DIVE_HOST) return "DiveAnimationHostAnimator";
        var name = new StringBuilder();
        for (var word : tag.name().split("_"))
            name.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        return name.append("Animator").toString();
    }

    private static volatile boolean connected;
    // The policy of the Blockfield server whose READY manifest this client reproduced.
    private static volatile com.lunares.blockfield.common.combat.AnimationPolicy armed;

    private AnimationPolicy() {}

    /**
     * Installs at play INIT ({@code ClientPacketListener} construction), not JOIN: pinned ParCool
     * bf7 snapshots and sends its ClientInfo from {@code ClientLevel.addEntity(LocalPlayer)} inside
     * {@code handleLogin}, while Fabric fires JOIN only at {@code handleLogin} RETURN. A JOIN
     * install would let the server admit the stored user values first.
     */
    public static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> install());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> restore());
    }

    /** A validated READY from a Blockfield server arms its policy for the coming play INIT. */
    public static void arm(com.lunares.blockfield.common.combat.AnimationPolicy policy) {
        armed = java.util.Objects.requireNonNull(policy, "policy");
    }

    /** Configuration aborted before play (refused JOIN): nothing is armed any more. */
    public static void disarm() {
        armed = null;
    }

    /** Play INIT: force when this connection's server armed the policy. Idempotent. */
    public static void install() {
        connected = armed != null;
    }

    /** Disconnect: stop forcing, so the stored user values govern again. Idempotent. */
    public static void restore() {
        connected = false;
        armed = null;
    }

    private static boolean forced(String constantName) {
        var policy = armed;
        if (!connected || policy == null) return false;
        return switch (constantName) {
            case "EnableAnimation" -> policy.enableAnimation();
            case "EnableFallingAnimation" -> policy.enableFallingAnimation();
            case "EnableLeanAnimationOfFastRun" -> policy.enableFastRunLean();
            default -> false;
        };
    }

    private static boolean forcedCrawl() {
        var policy = armed;
        return connected && policy != null && policy.smootherCrawlAnimation();
    }

    static boolean connected() {
        return connected;
    }

    /** Whether any policy entry is currently forced (an armed connection is active). */
    public static boolean forcing() {
        return connected && armed != null;
    }

    /** Effective ParCool {@code Booleans} value: forced on while connected for policy entries. */
    public static boolean effectiveParCool(String constantName, boolean stored) {
        return forced(constantName) || stored;
    }

    /** Whether a ParCool {@code Booleans} entry is currently forced (and its widget locked). */
    public static boolean lockedParCool(String constantName) {
        return forced(constantName);
    }

    /** Effective animator gate: every registered animator is allowed while connected. */
    public static boolean effectiveAnimator(boolean stored) {
        return forcing() || stored;
    }

    /** Effective TaCZ Tweaks smoother-crawl value: forced on while connected. */
    public static boolean effectiveSmootherCrawl(boolean stored) {
        return forcedCrawl() || stored;
    }

    /** Whether the Tweaks smoother-crawl toggle is currently forced (and locked). */
    public static boolean lockedSmootherCrawl() {
        return forcedCrawl();
    }
}
