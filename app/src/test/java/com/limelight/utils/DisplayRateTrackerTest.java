package com.limelight.utils;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Lifecycle tests for the hosting-display tracker: it must report a usable initial snapshot, must
 * not re-report when lifecycle callbacks re-query an unchanged display (Game calls refresh() from
 * every surface/window event), must ignore display callbacks delivered after stop(), and must
 * unregister cleanly.
 */
@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class DisplayRateTrackerTest {

    private static final class RecordingListener implements DisplayRateTracker.Listener {
        int calls;
        int displayId = -1;
        float refreshRateHz;
        boolean identityChanged;

        @Override
        public void onDisplayPacingChanged(int displayId, float refreshRateHz,
                                           boolean identityChanged) {
            calls++;
            this.displayId = displayId;
            this.refreshRateHz = refreshRateHz;
            this.identityChanged = identityChanged;
        }
    }

    @Test
    public void startReportsOnceAndRepeatedRefreshesDoNotChurn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecordingListener listener = new RecordingListener();
        DisplayRateTracker tracker = new DisplayRateTracker(activity, null, listener);

        tracker.start();
        assertTrue(tracker.isStarted());
        assertEquals(1, listener.calls);
        assertEquals(0, listener.displayId);
        assertTrue(tracker.getRefreshRateHz() > 1f);
        assertTrue(tracker.getRefreshRateHz() < 1000f);
        assertEquals(tracker.getRefreshRateHz(), listener.refreshRateHz, 0.0001f);

        // Lifecycle safety net re-queries must be no-ops for an unchanged display.
        assertFalse(tracker.refresh());
        assertFalse(tracker.refresh());
        assertEquals(1, listener.calls);

        tracker.start(); // idempotent
        assertEquals(1, listener.calls);

        tracker.stop();
        assertFalse(tracker.isStarted());
        tracker.stop(); // idempotent
    }

    @Test
    public void callbacksDeliveredAfterStopDoNotPublish() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecordingListener listener = new RecordingListener();
        DisplayRateTracker tracker = new DisplayRateTracker(activity, null, listener);

        tracker.start();
        assertEquals(1, listener.calls);

        tracker.stop();

        // The framework can deliver a display callback that was queued before unregistering.
        tracker.onDisplayChanged(0);
        tracker.onDisplayAdded(1);
        tracker.onDisplayRemoved(1);
        assertFalse(tracker.refresh());

        assertEquals(1, listener.calls);
    }
}
