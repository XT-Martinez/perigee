package com.limelight.binding.video;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural tests for the display-pacing hand-off used to reset Balanced/NanoPacer state when
 * the hosting display changes identity or refresh rate.
 *
 * <p>These cover the properties the renderer relies on: transient display queries never clobber a
 * good rate, genuine fractional mode changes survive de-duplication, ordinary reporting noise does
 * not churn pacing state, USB-C 120 -> 60 -> 120 transitions produce one change each, and every
 * pacing-thread consumer observes the latest published state on its own thread.
 */
public class DisplayPacingSignalTest {

    @Test
    public void invalidTransientRatesAreIgnored() {
        DisplayPacingSignal signal = new DisplayPacingSignal();

        assertTrue(signal.update(0, 120f));

        assertFalse(signal.update(0, 0f));
        assertFalse(signal.update(0, 1f));
        assertFalse(signal.update(0, -60f));
        assertFalse(signal.update(0, Float.NaN));
        assertFalse(signal.update(0, 10_000f));

        assertEquals(120f, signal.getState().refreshRateHz, 0.0001f);
        assertEquals(1, signal.getState().generation);
    }

    @Test
    public void identicalRateDoesNotChurn() {
        DisplayPacingSignal signal = new DisplayPacingSignal();

        assertTrue(signal.update(3, 60f));
        assertFalse(signal.update(3, 60f));
        // Sub-epsilon reporting noise only.
        assertFalse(signal.update(3, 60.005f));

        assertEquals(1, signal.getState().generation);
    }

    @Test
    public void fractionalModeChangesArePreserved() {
        DisplayPacingSignal signal = new DisplayPacingSignal();

        // 59.94 (NTSC) and 60.00 are distinct modes that change the pacing ratio; they must not
        // be collapsed as "close enough" values.
        assertTrue(signal.update(0, 59.94f));
        assertTrue(signal.update(0, 60f));
        assertEquals(60f, signal.getState().refreshRateHz, 0.0001f);
        assertEquals(2, signal.getState().generation);

        // Same for the 119.88 / 120.00 pair.
        assertTrue(signal.update(0, 119.88f));
        assertFalse(signal.update(0, 119.885f));
        assertTrue(signal.update(0, 120f));
        assertEquals(120f, signal.getState().refreshRateHz, 0.0001f);
        assertEquals(4, signal.getState().generation);
    }

    @Test
    public void usbC120To60To120BumpsOncePerTransition() {
        DisplayPacingSignal signal = new DisplayPacingSignal();
        DisplayPacingSignal.Consumer rendererConsumer = new DisplayPacingSignal.Consumer();
        DisplayPacingSignal.Consumer choreographerConsumer = new DisplayPacingSignal.Consumer();

        // Clean baseline.
        assertTrue(signal.update(0, 120f));
        assertTrue(rendererConsumer.onNewState(signal));
        assertTrue(choreographerConsumer.onNewState(signal));
        assertFalse(rendererConsumer.onNewState(signal));

        // USB-C monitor attached: built-in panel follows it to 60 Hz.
        assertTrue(signal.update(0, 60f));
        assertTrue(rendererConsumer.onNewState(signal));
        assertTrue(choreographerConsumer.onNewState(signal));
        assertEquals(60f, signal.getState().refreshRateHz, 0.0001f);

        // Monitor removed: panel returns to 120 Hz.
        assertTrue(signal.update(0, 120f));
        assertTrue(rendererConsumer.onNewState(signal));
        assertTrue(choreographerConsumer.onNewState(signal));

        assertEquals(120f, signal.getState().refreshRateHz, 0.0001f);
        assertEquals(3, signal.getState().generation);

        // No further frames report changes without new display state.
        assertFalse(rendererConsumer.onNewState(signal));
        assertFalse(choreographerConsumer.onNewState(signal));
    }

    @Test
    public void consumersAreIndependent() {
        DisplayPacingSignal signal = new DisplayPacingSignal();
        DisplayPacingSignal.Consumer first = new DisplayPacingSignal.Consumer();
        DisplayPacingSignal.Consumer second = new DisplayPacingSignal.Consumer();

        assertTrue(signal.update(1, 120f));

        // One consumer draining the signal must not hide it from the other pacing thread.
        assertTrue(first.onNewState(signal));
        assertFalse(first.onNewState(signal));
        assertTrue(second.onNewState(signal));

        assertTrue(signal.update(1, 90f));
        assertTrue(first.onNewState(signal));
        assertTrue(second.onNewState(signal));
    }

    @Test
    public void displayIdentityChangeBumpsEvenAtTheSameRate() {
        DisplayPacingSignal signal = new DisplayPacingSignal();

        assertTrue(signal.update(0, 60f));
        assertTrue(signal.update(2, 60f));

        assertEquals(2, signal.getState().displayId);
        assertEquals(2, signal.getState().generation);
    }

    @Test
    public void unknownDisplayIdKeepsLastKnownIdentity() {
        DisplayPacingSignal signal = new DisplayPacingSignal();

        assertTrue(signal.update(4, 120f));
        assertTrue(signal.update(-1, 60f));

        assertEquals(4, signal.getState().displayId);
        assertEquals(60f, signal.getState().refreshRateHz, 0.0001f);
    }

    @Test
    public void resolvingAnUnknownDisplayIdIsStoredAndReported() {
        DisplayPacingSignal signal = new DisplayPacingSignal();

        // setup() can publish a valid fallback rate before the hosting display id is known.
        assertTrue(signal.update(-1, 120f));
        assertEquals(-1, signal.getState().displayId);

        // The tracker later resolves the real display at the same rate; the id must be stored.
        assertTrue(signal.update(0, 120f));
        assertEquals(0, signal.getState().displayId);
        assertEquals(2, signal.getState().generation);

        // No further churn once resolved.
        assertFalse(signal.update(0, 120f));
    }

    @Test
    public void concurrentUpdatesPublishAValidSnapshot() throws Exception {
        final DisplayPacingSignal signal = new DisplayPacingSignal();
        final int threads = 4;
        final int updatesPerThread = 250;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final boolean even = (t % 2 == 0);
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        signal.update(0, even ? 60f : 120f);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));

        DisplayPacingSignal.State state = signal.getState();
        assertTrue(DisplayPacingSignal.isValidRate(state.refreshRateHz));
        assertTrue(state.generation >= 1);
        assertTrue(state.generation <= threads * updatesPerThread);
    }
}
