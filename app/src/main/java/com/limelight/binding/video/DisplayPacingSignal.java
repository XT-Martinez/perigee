package com.limelight.binding.video;

/**
 * Hand-off for live display-pacing changes with wait-free reads for pacing threads.
 *
 * <p>The UI thread publishes an immutable snapshot whenever the display that actually hosts the
 * stream changes identity or effective refresh rate. Publishing takes a short lock; the
 * renderer/Choreographer threads only read a volatile reference, so no pacing hot path blocks.
 *
 * <p>Pacing state (the Balanced Choreographer ratio and {@link NanoPacer}) must be reset only by
 * the thread that owns it: a display callback runs on the main thread and would otherwise race
 * {@code doFrame()} and {@code waitAndDrainLatest()}. This class therefore never touches pacing
 * state. It only publishes the snapshot plus a monotonically increasing generation. Each pacing
 * thread keeps a {@link Consumer} and observes the latest published state on its own thread, then
 * performs its own reset there. Several changes published between two observations collapse into
 * one observation, which is intentional: only the current display rate matters.
 */
public final class DisplayPacingSignal {

    /** Refresh rates outside this range are transient/invalid and must not be published. */
    private static final float MIN_VALID_HZ = 1f;
    private static final float MAX_VALID_HZ = 1000f;

    /**
     * Ignore sub-0.01 Hz float noise only. Genuine fractional mode changes (for example
     * 59.94 -> 60.00 or 119.88 -> 120.00) must remain visible, because they change the
     * display/stream ratio used for pacing.
     */
    private static final float RATE_EPSILON_HZ = 0.01f;

    /** Immutable published snapshot. */
    public static final class State {
        /** Hosting display id, or the last known id when the current query cannot resolve one. */
        public final int displayId;
        /** Validated refresh rate in Hz. */
        public final float refreshRateHz;
        /** Incremented once per published change; 0 for the initial snapshot. */
        public final int generation;

        State(int displayId, float refreshRateHz, int generation) {
            this.displayId = displayId;
            this.refreshRateHz = refreshRateHz;
            this.generation = generation;
        }
    }

    private static final State INITIAL = new State(-1, 0f, 0);

    private volatile State state = INITIAL;

    /**
     * Publishes a display snapshot.
     *
     * @param displayId hosting display id, or -1 when unknown (keeps the previous id)
     * @param refreshRateHz refresh rate in Hz
     * @return true when the snapshot changed identity or effective refresh rate
     */
    public boolean update(int displayId, float refreshRateHz) {
        if (!isValidRate(refreshRateHz)) {
            // Transient query: keep the last good snapshot.
            return false;
        }

        synchronized (this) {
            final State current = state;
            // Transitioning from an unknown display id to a resolved one is a real change: the
            // resolved id must be stored even when the rate is identical.
            final boolean identityChanged = displayId >= 0 && current.displayId != displayId;
            final boolean rateChanged = !isSameRate(current.refreshRateHz, refreshRateHz);
            if (!identityChanged && !rateChanged) {
                return false;
            }

            state = new State(displayId >= 0 ? displayId : current.displayId,
                    refreshRateHz, current.generation + 1);
            return true;
        }
    }

    public State getState() {
        return state;
    }

    public static boolean isValidRate(float refreshRateHz) {
        return refreshRateHz > MIN_VALID_HZ && refreshRateHz < MAX_VALID_HZ;
    }

    /** True when both values are valid and within the change-detection epsilon. */
    public static boolean isSameRate(float a, float b) {
        return isValidRate(a) && isValidRate(b) && Math.abs(a - b) <= RATE_EPSILON_HZ;
    }

    /**
     * Per-pacing-thread view of the signal.
     *
     * <p>Not thread-safe: create one instance per pacing thread and never share it. A consumer
     * returns true when the signal is newer than its last observation; changes published between
     * two observations collapse into a single observation, so the owning thread resets its pacing
     * state once against the latest display state.
     */
    public static final class Consumer {
        private int seenGeneration = 0;

        /** @return true when this consumer has not yet observed the signal's current state. */
        public boolean onNewState(DisplayPacingSignal signal) {
            final State snapshot = signal.state;
            if (snapshot.generation == seenGeneration) {
                return false;
            }

            seenGeneration = snapshot.generation;
            return true;
        }
    }
}
