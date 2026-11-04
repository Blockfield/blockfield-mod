package com.lunares.blockfield.workshop;

/**
 * Server-authored phase snapshot; timestamps are UTC epoch milliseconds, never client guesses.
 * {@code phaseDeadlineAt} is the hard upper bound; {@code jobStartedAt}/{@code expectedDoneAt} are
 * the server's typical-duration estimate for the whole generation, both 0 when it has none.
 */
public record WorkshopAiProgress(
        Phase phase,
        int partsDone,
        int partsTotal,
        long phaseStartedAt,
        long phaseDeadlineAt,
        long jobStartedAt,
        long expectedDoneAt) {
    public enum Phase {
        NONE("none"),
        UPLOADING("uploading"),
        TRANSCRIBING("transcribing"),
        GENERATION("generation"),
        LIBRARY_GENERATION("library_generation"),
        LAYOUT("layout"),
        PARTS("parts"),
        MERGING("merging"),
        POLISHING("polishing"),
        VALIDATING("validating"),
        AWAITING("awaiting"),
        APPLYING("applying"),
        UNDOING("undoing"),
        EXTENDING("extending"),
        EXTENDING_AI("extending_ai");
        private final String key;

        Phase(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public String translationKey() {
            return "blockfield.workshop.ai.phase." + key;
        }

        public static Phase fromKey(String key) {
            for (Phase p : values()) if (p.key.equals(key)) return p;
            throw new IllegalArgumentException("Unknown planning phase");
        }
    }

    public static final WorkshopAiProgress NONE = new WorkshopAiProgress(Phase.NONE, 0, 0, 0, 0);

    public WorkshopAiProgress(
            Phase phase, int partsDone, int partsTotal, long phaseStartedAt, long phaseDeadlineAt) {
        this(phase, partsDone, partsTotal, phaseStartedAt, phaseDeadlineAt, 0, 0);
    }

    public WorkshopAiProgress {
        if (phase == null
                || partsTotal < 0
                || partsTotal > 6
                || partsDone < 0
                || partsDone > partsTotal
                || phaseStartedAt < 0
                || phaseDeadlineAt < phaseStartedAt
                || (jobStartedAt == 0) != (expectedDoneAt == 0)
                || jobStartedAt < 0
                || jobStartedAt != 0 && expectedDoneAt <= jobStartedAt
                || phase == Phase.NONE && jobStartedAt != 0
                || phase == Phase.NONE
                        && (partsDone != 0
                                || partsTotal != 0
                                || phaseStartedAt != 0
                                || phaseDeadlineAt != 0))
            throw new IllegalArgumentException("Invalid AI progress");
    }

    public boolean busy() {
        return phase != Phase.NONE;
    }

    public boolean hasEstimate() {
        return expectedDoneAt != 0;
    }
}
