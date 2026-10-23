package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.binding.video.DisplayPacingSignal;

/**
 * Tracks the display that actually hosts the stream view and its live refresh rate.
 *
 * <p>The video renderer caches the hosting display rate once at {@code setup()} to build pacing
 * ratios. That rate can change without the activity being recreated: attaching or removing a
 * USB-C monitor can move the built-in panel between 120 Hz and 60 Hz, and a window can migrate
 * between displays (or its surface can be recreated) while streaming. Pacing computed from the
 * stale setup-time rate produces beat patterns, so this tracker keeps the renderer's snapshot
 * current.
 *
 * <p>Callbacks are delivered on the main thread. Invalid/transient reads (0, NaN, absurd values)
 * are ignored so a momentarily unusable query cannot clobber the last known good rate. Effective
 * changes are de-duplicated by {@link DisplayPacingSignal}, which also gives the renderer a safe
 * wait-free read of the change for its pacing threads.
 *
 * <p>Usage: construct on the main thread, {@link #start()} once the hosting view exists and
 * {@link #stop()} from the activity's teardown. start()/stop() are idempotent.
 */
public final class DisplayRateTracker implements DisplayManager.DisplayListener {

    public interface Listener {
        /**
         * Called on the main thread when the hosting display identity or effective refresh rate
         * changed.
         *
         * @param displayId hosting display id (or the previous known id when unresolved)
         * @param refreshRateHz validated refresh rate in Hz
         * @param identityChanged true when the hosting display itself changed
         */
        void onDisplayPacingChanged(int displayId, float refreshRateHz, boolean identityChanged);
    }

    private final Activity activity;
    private final View hostView;
    private final DisplayManager displayManager;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DisplayPacingSignal signal = new DisplayPacingSignal();

    private boolean started;
    private int lastReportedDisplayId = -1;
    private float lastReportedHz = 0f;

    public DisplayRateTracker(Activity activity, View hostView, Listener listener) {
        this.activity = activity;
        this.hostView = hostView;
        this.listener = listener;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Registers the display listener and reports the first snapshot. Main thread only. */
    public void start() {
        if (started) {
            return;
        }
        started = true;

        if (displayManager != null) {
            try {
                displayManager.registerDisplayListener(this, mainHandler);
            } catch (Throwable t) {
                LimeLog.warning("DisplayRateTracker: registerDisplayListener failed: " + t);
            }
        }

        refresh("start");
    }

    /** Unregisters the display listener. Main thread only. */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;

        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(this);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isStarted() {
        return started;
    }

    public int getDisplayId() {
        return lastReportedDisplayId;
    }

    public float getRefreshRateHz() {
        return lastReportedHz;
    }

    /**
     * Re-resolves the hosting display. Used by activity/surface lifecycle as a safety net for
     * display changes that were not delivered as a callback (for example while migrating).
     *
     * @return true when a new snapshot was reported
     */
    public boolean refresh() {
        return refresh("query");
    }

    private boolean refresh(String reason) {
        // A display callback queued before stop() can still be delivered after unregistering.
        // Never publish from a stopped tracker.
        if (!started) {
            return false;
        }

        final Display display = resolveHostingDisplay();
        if (display == null) {
            return false;
        }

        final int displayId = display.getDisplayId();
        final float refreshRateHz = readRefreshRateHz(display);
        if (!DisplayPacingSignal.isValidRate(refreshRateHz)) {
            // Transient/invalid query. Keep the last good snapshot.
            return false;
        }

        if (!signal.update(displayId, refreshRateHz)) {
            return false;
        }

        final DisplayPacingSignal.State state = signal.getState();
        final boolean identityChanged =
                lastReportedDisplayId >= 0 && lastReportedDisplayId != state.displayId;
        lastReportedDisplayId = state.displayId;
        lastReportedHz = state.refreshRateHz;

        LimeLog.info("Hosting display pacing: display " + state.displayId + " at "
                + state.refreshRateHz + " Hz (" + reason
                + (identityChanged ? ", display changed" : "") + ")");

        if (listener != null) {
            listener.onDisplayPacingChanged(state.displayId, state.refreshRateHz, identityChanged);
        }
        return true;
    }

    private Display resolveHostingDisplay() {
        // The stream view is the most accurate source: it follows the window if the activity
        // moves between displays.
        try {
            if (hostView != null) {
                final Display display = hostView.getDisplay();
                if (display != null) {
                    return display;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null) {
                // API 30+: the display the activity is associated with. May be null while detached.
                final Display display = activity.getDisplay();
                if (display != null) {
                    return display;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (activity != null && activity.getWindowManager() != null) {
                return activity.getWindowManager().getDefaultDisplay();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * Reads the live refresh rate first (the rate the panel is actually running at), falling back
     * to the nominal rate of the current mode. Returns 0 for unusable values.
     */
    private static float readRefreshRateHz(Display display) {
        if (display == null) {
            return 0f;
        }

        try {
            final float liveHz = display.getRefreshRate();
            if (DisplayPacingSignal.isValidRate(liveHz)) {
                return liveHz;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display.getMode() != null) {
                final float modeHz = display.getMode().getRefreshRate();
                if (DisplayPacingSignal.isValidRate(modeHz)) {
                    return modeHz;
                }
            }
        } catch (Throwable ignored) {
        }

        return 0f;
    }

    // DisplayManager.DisplayListener -- all callbacks arrive on the main handler. Unrelated
    // display events only re-resolve the hosting display; the signal filters out non-changes.

    @Override
    public void onDisplayAdded(int displayId) {
        refresh("added " + displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        refresh("removed " + displayId);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        refresh("changed " + displayId);
    }
}
