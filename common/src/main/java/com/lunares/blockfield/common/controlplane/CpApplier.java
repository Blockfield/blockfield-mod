package com.lunares.blockfield.common.controlplane;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * Server-side seam: validates a control-plane settings value and applies it at a safe runtime
 * boundary. Implemented in the server repo on top of {@code AdminSettings} (parse, then apply);
 * this package never interprets settings semantics itself.
 */
public interface CpApplier {
    record Outcome(boolean applied, CpAck.Result result, List<String> errors) {
        public static Outcome ok() {
            return new Outcome(true, CpAck.Result.OK, List.of());
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
