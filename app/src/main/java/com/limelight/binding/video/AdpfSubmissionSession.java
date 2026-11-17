package com.limelight.binding.video;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.util.Arrays;
import java.util.Locale;

/**
 * Opt-in submission hints ported from artemis-tcl-av1, independent of Artemide's rendering hints.
 * One session covers the submitting thread plus optional extra pipeline threads chosen by the
 * caller (codec-owned workers, codec callback, renderer and choreographer threads).
 * One work cycle is elapsed input acquisition, frame copy, queueInputBuffer and prefetch, not
 * external codec-service/hardware latency, network receive, stats formatting or vsync pacing.
 * Never add external service threads or unrelated callbacks.
 *
 * The build-configured target is an experimental submission budget, not a proven optimum.
 * Artemide's async 2 ms baseline reports mean 0.99 ms / p95 1.93 ms, with only 4% overruns;
 * the isolated 1 ms candidate tests less headroom using the same genuine work cycle and threads.
 * Installed 1 ms captures report mean submission 0.82-0.85 ms, but codec completion remains
 * 4.19-4.33 ms versus baseline 4.24 ms: no demonstrated decoding-latency improvement.
 * The sibling's 1.5 ms experiment also did not demonstrate a gain. Do not assume lower targets
 * improve full decoding latency. A non-positive configuration falls back to 2 ms.
 * Report every completed cycle; the platform's preferred update interval is diagnostic only.
 * Failures must never disrupt streaming.
 */
final class AdpfSubmissionSession {
    static final long DEFAULT_TARGET_NS = 2_000_000L;
    static final long TARGET_NS = validateTargetNs(BuildConfig.ADPF_SUBMIT_TARGET_NS);
    static final long MAX_WORK_NS = 1_000_000_000L;
    /** Shared empty array meaning "no extra threads". */
    static final int[] NO_EXTRA_TIDS = new int[0];

    interface SessionHandle {
        void reportActualWorkDuration(long actualNs);
        void setThreads(int[] tids);
        void close();
    }

    interface SessionFactory {
        SessionHandle create(int[] tids, long targetNs);
        long getPreferredUpdateRateNanos();
    }

    private final SessionFactory factory;
    private final long targetNs;
    private SessionHandle session;
    private int inputTid;
    private int[] members;          // Sorted, includes inputTid; null without a session.
    private int[] rejected;         // Member list the platform refused, or null.
    private int[] lastExtraTidsRef; // Caller array of the last accepted configuration.
    private volatile boolean disabled;

    AdpfSubmissionSession(SessionFactory factory, long targetNs) {
        this.factory = factory;
        this.targetNs = targetNs;
    }

    static AdpfSubmissionSession create(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        try {
            final PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
                return null;
            }
            return new AdpfSubmissionSession(new SessionFactory() {
                @Override
                public SessionHandle create(int[] tids, long targetNs) {
                    final PerformanceHintManager.Session platform = manager.createHintSession(tids, targetNs);
                    if (platform == null) {
                        return null;
                    }
                    return new SessionHandle() {
                        @Override
                        public void reportActualWorkDuration(long actualNs) {
                            platform.reportActualWorkDuration(actualNs);
                        }

                        @Override
                        public void setThreads(int[] tids) {
                            platform.setThreads(tids);
                        }

                        @Override
                        public void close() {
                            platform.close();
                        }
                    };
                }

                @Override
                public long getPreferredUpdateRateNanos() {
                    return manager.getPreferredUpdateRateNanos();
                }
            }, TARGET_NS);
        } catch (Throwable t) {
            LimeLog.warning("ADPF submission: unavailable: " + t);
            return null;
        }
    }

    static boolean shouldEnable(boolean enabled, int sdk, boolean av1, String decoder,
                                String device, String product, String model) {
        return enabled && sdk >= Build.VERSION_CODES.S && av1 && decoder != null
                && decoder.toLowerCase(Locale.US).startsWith("c2.google.av1")
                && ("tegu".equalsIgnoreCase(device) || "tegu".equalsIgnoreCase(product)
                || "Pixel 9a".equalsIgnoreCase(model));
    }

    static boolean shouldUseWorkerThread(boolean buildEnabled, boolean balanced) {
        return buildEnabled && balanced;
    }

    /**
     * Pure helper: which extra threads belong in the session. Unified selection takes every
     * positive tid; the legacy policy takes only the worker, and only in Balanced mode.
     */
    static int[] selectExtraTids(boolean unified, boolean balanced, int worker, int codecLooper,
                                 int callback, int renderer, int choreographer) {
        int[] candidates = unified
                ? new int[] { worker, codecLooper, callback, renderer, choreographer }
                : balanced ? new int[] { worker } : NO_EXTRA_TIDS;
        int[] tids = new int[candidates.length];
        int count = 0;
        for (int tid : candidates) {
            if (tid > 0) {
                tids[count++] = tid;
            }
        }
        return count == 0 ? NO_EXTRA_TIDS : sortedUnique(tids, count);
    }

    /** Hot path, called once per frame by the submitting thread. extraTids is caller-owned and immutable. */
    synchronized void ensureConfigured(int tid, int[] extraTids) {
        if (disabled || tid <= 0) {
            return;
        }
        if (extraTids == null) {
            extraTids = NO_EXTRA_TIDS;
        }
        if (session != null && tid == inputTid && extraTids == lastExtraTidsRef) {
            return; // No allocation, binder call or logging in the steady-state path.
        }
        int[] desired = desiredMembers(tid, extraTids);
        if (desired.length == 1) {
            rejected = null; // The caller dropped its extras; let a later set try again.
        } else if (Arrays.equals(desired, rejected)) {
            desired = new int[] { tid };
        }
        apply(tid, desired, extraTids);
    }

    /** Make the session input-only now, before extra threads exit, and forget any rejection. */
    synchronized void clearExtraThreads() {
        rejected = null;
        lastExtraTidsRef = NO_EXTRA_TIDS;
        if (!disabled && session != null && members != null && members.length > 1) {
            apply(inputTid, new int[] { inputTid }, NO_EXTRA_TIDS);
        }
    }

    synchronized boolean reportWorkDuration(long workNs) {
        if (disabled || session == null || workNs <= 0 || workNs > MAX_WORK_NS) {
            return false;
        }
        try {
            session.reportActualWorkDuration(workNs);
            return true;
        } catch (Throwable t) {
            LimeLog.warning("ADPF submission: report rejected; disabling hints: " + t);
            close();
            return false;
        }
    }

    boolean isDisabled() {
        return disabled;
    }

    synchronized boolean isActive() {
        return session != null;
    }

    synchronized void close() {
        disabled = true; // A late input callback must never resurrect a stopped session.
        closeSession();
        inputTid = 0;
        rejected = null;
        lastExtraTidsRef = NO_EXTRA_TIDS;
    }

    /** Applies a desired member list: update in place on API 34+, otherwise recreate. */
    private void apply(int tid, int[] desired, int[] extraTids) {
        if (session != null && Arrays.equals(desired, members)) {
            inputTid = tid;
            lastExtraTidsRef = extraTids;
            return;
        }
        if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                session.setThreads(desired);
                inputTid = tid;
                members = desired;
                lastExtraTidsRef = extraTids;
                return;
            } catch (Throwable t) {
                LimeLog.warning("ADPF submission: thread update rejected; input-only: " + t);
                if (desired.length > 1) {
                    rejected = desired;
                    desired = new int[] { tid };
                }
            }
        }
        closeSession();
        recreate(tid, desired);
        if (session != null) {
            lastExtraTidsRef = extraTids;
        }
    }

    private void recreate(int tid, int[] desired) {
        if (tryCreate(tid, desired)) {
            return;
        }
        if (desired.length > 1) {
            rejected = desired;
            LimeLog.warning("ADPF submission: thread set rejected; input-only until cleared");
            if (tryCreate(tid, new int[] { tid })) {
                return;
            }
        }
        disabled = true;
    }

    private boolean tryCreate(int tid, int[] tids) {
        SessionHandle created = null;
        try {
            created = factory.create(tids, targetNs);
            if (created == null) {
                // Unsupported power HAL, or a tid the platform does not accept. The caller
                // latches only after the input-only attempt has failed too.
                session = null;
                return false;
            }
            session = created;
            inputTid = tid;
            members = tids;
            return true;
        } catch (Throwable t) {
            LimeLog.warning("ADPF submission: creation failed: " + t);
            if (created != null) {
                try { created.close(); } catch (Throwable ignored) { }
            }
            session = null;
            return false;
        }
    }

    private void closeSession() {
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) { }
            session = null;
        }
        members = null;
    }

    static long validateTargetNs(int configuredNs) {
        return configuredNs > 0 ? configuredNs : DEFAULT_TARGET_NS;
    }

    /** The input tid plus every other positive extra, sorted and without duplicates. */
    private static int[] desiredMembers(int tid, int[] extraTids) {
        int[] result = new int[extraTids.length + 1];
        result[0] = tid;
        int count = 1;
        for (int extra : extraTids) {
            if (extra > 0 && extra != tid) {
                result[count++] = extra;
            }
        }
        return sortedUnique(result, count);
    }

    /** Sorts the first count values and drops duplicates. May return the same array. */
    private static int[] sortedUnique(int[] values, int count) {
        Arrays.sort(values, 0, count);
        int unique = count == 0 ? 0 : 1;
        for (int i = 1; i < count; i++) {
            if (values[i] != values[unique - 1]) {
                values[unique++] = values[i];
            }
        }
        return unique == values.length ? values : Arrays.copyOf(values, unique);
    }
}
