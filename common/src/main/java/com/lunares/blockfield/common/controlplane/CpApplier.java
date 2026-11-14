package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * Server-side seam: validates a control-plane settings value and applies it at a safe runtime
 * boundary. Implemented in the server repo on top of {@code AdminSettings} (parse, then apply);
 * this package never interprets settings semantics itself.
 */
public interface CpApplier {
    /**
     * {@code output}: command text returned to the control plane in the terminal ack, or null.
     * {@code needsPostAckHook}: true when the handler relies on the post-ack {@code acked} future
     * to complete a side effect after {@code EFFECTIVE} is delivered; declared explicitly before
     * persistence so restarts without the live hook never replay {@code EFFECTIVE}.
     */
    record Outcome(
            boolean applied,
            CpAck.Result result,
            List<String> errors,
            String output,
            boolean needsPostAckHook) {
        public Outcome(boolean applied, CpAck.Result result, List<String> errors) {
            this(applied, result, errors, null, false);
        }

        public Outcome(boolean applied, CpAck.Result result, List<String> errors, String output) {
            this(applied, result, errors, output, false);
        }

        public static Outcome ok() {
            return ok(null);
        }

        public static Outcome ok(String output) {
            return new Outcome(true, CpAck.Result.OK, List.of(), output, false);
        }

        /** Apply succeeded and the handler will finish its effect via the post-ack future. */
        public static Outcome okWithHook() {
            return okWithHook(null);
        }

        /**
         * Apply succeeded with output and the handler will finish its effect via the post-ack
         * future.
         */
        public static Outcome okWithHook(String output) {
            return new Outcome(true, CpAck.Result.OK, List.of(), output, true);
        }

        public static Outcome rejected(CpAck.Result result, String... errors) {
            return new Outcome(false, result, List.of(errors));
        }
    }

    /**
     * Validate {@code value} for {@code namespace}/{@code target} and, only if valid, apply it as
     * {@code revision}. A returned {@code applied=false} must leave the running config untouched.
     */
    Outcome applySettings(String namespace, String target, long revision, JsonObject value);
}
