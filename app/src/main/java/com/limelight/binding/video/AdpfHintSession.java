package com.limelight.binding.video;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;

import com.limelight.LimeLog;

import java.util.Arrays;

/**
 * Thin wrapper around Android's ADPF ({@link PerformanceHintManager}) hint session.
 *
 * <p>A hint session tells the vendor power HAL "these threads must finish a unit of work
 * within N nanoseconds". The HAL answers by raising CPU frequency / placement for exactly
 * those threads. This is the only supported, unprivileged way to get out from under a lazy
 * cpufreq governor.
 *
 * <p>Motivating case: the TCL G08 (MediaTek mt5879, Android 14) pins all four cores to the
 * {@code conservative} governor with {@code sampling_rate=10ms}, {@code up_threshold=80} and
 * {@code freq_step=5}, and caps {@code scaling_max_freq} below the top hardware bin. Bursty
 * receive+decode work is therefore serviced at the 1.1 GHz floor while the governor creeps
 * upward, producing multi-millisecond jitter. That TV also offers no Game Mode
 * {@code performance} profile, so {@code GameManager} cannot substitute for this.
 *
 * <p>Every operation is best-effort and never throws: ADPF is absent below API 31 and vendor
 * power HALs are known to reject otherwise-valid sessions. A no-op instance is always safe to
 * hold and call. All methods are safe to call from any thread.
 */
final class AdpfHintSession {

    /**
     * Sanity bound for reported work durations — garbage rejection only, deliberately loose.
     *
     * <p>Reporting a duration *above* target is the whole point of the feedback loop: it is how a
     * session says "I am missing the deadline, raise clocks". An earlier version of this class
     * clamped at 4x target to guard against a blocking wait being folded into the measurement;
     * that suppressed precisely the deadline overruns the HAL needs to hear about. Callers are
     * responsible for excluding deliberate sleeps (e.g. frame pacing) from what they report.
     */
    private static final long MAX_PLAUSIBLE_REPORT_NS = 1_000_000_000L;

    private final PerformanceHintManager manager;

    private PerformanceHintManager.Session session;
    private int[] threadIds = new int[0];
    private long targetDurationNs;

    /** Latches once ADPF has refused us, so we stop retrying session creation every frame. */
    private boolean unavailable;

    private AdpfHintSession(PerformanceHintManager manager) {
        this.manager = manager;
    }

    /**
     * Returns a hint-session wrapper, or {@code null} when ADPF is unavailable on this device.
     * A {@code null} return is expected and benign — callers should simply skip hinting.
     */
    static AdpfHintSession create(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context == null) {
            return null;
        }

        try {
            PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
                LimeLog.info("ADPF: PerformanceHintManager unavailable");
                return null;
            }
            return new AdpfHintSession(manager);
        } catch (Throwable t) {
            LimeLog.warning("ADPF: unable to obtain PerformanceHintManager: " + t);
            return null;
        }
    }

    /**
     * Declares the threads doing the frame work and the per-frame deadline, creating or
     * reconfiguring the underlying session as needed.
     *
     * @param tids            OS thread IDs ({@link android.os.Process#myTid()}); zero entries and
     *                        duplicates are ignored. Must belong to this process.
     * @param targetNs        per-frame deadline in nanoseconds; ignored when not positive.
     */
    synchronized void configure(int[] tids, long targetNs) {
        if (unavailable || targetNs <= 0) {
            return;
        }

        int[] cleaned = normalize(tids);
        if (cleaned.length == 0) {
            return;
        }

        boolean threadsChanged = !Arrays.equals(cleaned, threadIds);
        boolean targetChanged = targetNs != targetDurationNs;

        if (session != null && !threadsChanged && !targetChanged) {
            return;
        }

        if (session == null) {
            createSession(cleaned, targetNs);
            return;
        }

        if (threadsChanged) {
            // setThreads() landed in API 34. Before that the thread set is immutable, so a
            // changed set means tearing the session down and building a new one.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    session.setThreads(cleaned);
                    LimeLog.info("ADPF: hint session threads " + Arrays.toString(threadIds)
                            + " -> " + Arrays.toString(cleaned));
                    threadIds = cleaned;
                } catch (Throwable t) {
                    LimeLog.warning("ADPF: setThreads() failed, recreating session: " + t);
                    closeSession();
                    createSession(cleaned, targetNs);
                    return;
                }
            } else {
                closeSession();
                createSession(cleaned, targetNs);
                return;
            }
        }

        if (targetChanged) {
            try {
                session.updateTargetWorkDuration(targetNs);
                targetDurationNs = targetNs;
            } catch (Throwable t) {
                LimeLog.warning("ADPF: updateTargetWorkDuration() failed: " + t);
            }
        }
    }

    /**
     * Reports how long the last frame's work actually took, closing the HAL's feedback loop.
     * Values above target are passed through deliberately — see {@link #MAX_PLAUSIBLE_REPORT_NS}.
     */
    synchronized void reportActualWorkDuration(long actualNs) {
        if (session == null || actualNs <= 0 || actualNs > MAX_PLAUSIBLE_REPORT_NS) {
            return;
        }

        try {
            session.reportActualWorkDuration(actualNs);
        } catch (Throwable t) {
            // A HAL that rejects reports will reject all of them; stop trying.
            LimeLog.warning("ADPF: reportActualWorkDuration() failed, disabling hints: " + t);
            closeSession();
            unavailable = true;
        }
    }

    /** True once a session exists, i.e. there is a registered thread set worth preserving. */
    synchronized boolean isActive() {
        return session != null;
    }

    /** Releases the session. Safe to call repeatedly and after {@link #create} returned a no-op. */
    synchronized void close() {
        unavailable = true; // Do not let late priority updates resurrect a stopped session.
        closeSession();
        threadIds = new int[0];
        targetDurationNs = 0;
    }

    private void createSession(int[] tids, long targetNs) {
        try {
            session = manager.createHintSession(tids, targetNs);
        } catch (Throwable t) {
            // IllegalArgumentException here means the tids were rejected (e.g. a thread exited
            // between collection and this call), which is transient — don't latch on it.
            LimeLog.warning("ADPF: createHintSession() threw: " + t);
            session = null;
            return;
        }

        if (session == null) {
            // Documented "not supported by this device" path.
            LimeLog.info("ADPF: hint sessions not supported by the power HAL");
            unavailable = true;
            return;
        }

        threadIds = tids;
        targetDurationNs = targetNs;
        LimeLog.info("ADPF: hint session active for " + tids.length
                + " thread(s), target " + (targetNs / 1000L) + " us");
    }

    private void closeSession() {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Throwable ignored) {
            // Nothing useful to do; the session is being discarded either way.
        }
        session = null;
    }

    /** Drops zeros and duplicates, then sorts so that set comparison is order-insensitive. */
    private static int[] normalize(int[] tids) {
        if (tids == null) {
            return new int[0];
        }

        int[] out = new int[tids.length];
        int n = 0;
        for (int tid : tids) {
            // 0 = not started yet; HandlerThread.getThreadId() returns -1 before its run().
            if (tid <= 0) {
                continue;
            }
            boolean dup = false;
            for (int i = 0; i < n; i++) {
                if (out[i] == tid) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out[n++] = tid;
            }
        }

        int[] trimmed = Arrays.copyOf(out, n);
        Arrays.sort(trimmed);
        return trimmed;
    }
}
