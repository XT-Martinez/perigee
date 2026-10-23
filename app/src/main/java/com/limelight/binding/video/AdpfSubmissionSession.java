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
 * One work cycle is elapsed input acquisition, frame copy, queueInputBuffer and prefetch, not
 * external codec-service/hardware latency, network receive, stats formatting or vsync pacing.
 * The app-owned MediaCodec_loop worker participates in the synchronous input handoff and may
 * share that cycle in Balanced mode. Never add external service threads or unrelated callbacks.
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
    private CodecThreadDiscovery.WorkerIdentity worker;
    private CodecThreadDiscovery.WorkerIdentity rejectedWorker;
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

    synchronized void ensureConfigured(int tid, CodecThreadDiscovery.WorkerIdentity desiredWorker) {
        if (disabled || tid <= 0) {
            return;
        }
        if (desiredWorker == null) {
            rejectedWorker = null;
        } else if (desiredWorker.tid <= 0 || desiredWorker.startTimeTicks <= 0
                || desiredWorker.tid == tid || sameWorker(desiredWorker, rejectedWorker)) {
            desiredWorker = null;
        }
        if (session != null && inputTid == tid && sameWorker(worker, desiredWorker)) {
            return; // No allocation, /proc reads or binder updates in the steady-state path.
        }

        if (session != null && desiredWorker != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                session.setThreads(threadIds(tid, desiredWorker));
                inputTid = tid;
                worker = desiredWorker;
                return;
            } catch (Throwable t) {
                LimeLog.warning("ADPF submission: worker update rejected; input-only: " + t);
                rejectedWorker = desiredWorker;
                desiredWorker = null;
            }
        }
        closeSession();
        if (tryCreate(tid, desiredWorker) || disabled) {
            return;
        }
        if (desiredWorker != null) {
            rejectedWorker = desiredWorker;
            LimeLog.warning("ADPF submission: worker rejected; input-only until identity changes");
            if (tryCreate(tid, null) || disabled) {
                return;
            }
        }
        disabled = true;
    }

    /** Remove worker membership before a recovery can destroy or replace that thread. */
    synchronized void clearWorker() {
        if (session != null && worker != null) {
            ensureConfigured(inputTid, null);
        } else {
            rejectedWorker = null;
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
        worker = null;
        rejectedWorker = null;
    }

    private boolean tryCreate(int tid, CodecThreadDiscovery.WorkerIdentity desiredWorker) {
        SessionHandle created = null;
        try {
            int[] tids = threadIds(tid, desiredWorker);
            created = factory.create(tids, targetNs);
            if (created == null) {
                disabled = true; // The documented unsupported power-HAL response.
                return false;
            }
            session = created;
            inputTid = tid;
            worker = desiredWorker;
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
        worker = null;
    }

    static long validateTargetNs(int configuredNs) {
        return configuredNs > 0 ? configuredNs : DEFAULT_TARGET_NS;
    }

    private static boolean sameWorker(CodecThreadDiscovery.WorkerIdentity a,
                                      CodecThreadDiscovery.WorkerIdentity b) {
        return a == b || (a != null && b != null && a.tid == b.tid
                && a.startTimeTicks == b.startTimeTicks);
    }

    private static int[] threadIds(int tid, CodecThreadDiscovery.WorkerIdentity worker) {
        int[] tids = worker == null ? new int[] { tid } : new int[] { tid, worker.tid };
        Arrays.sort(tids);
        return tids;
    }

}
