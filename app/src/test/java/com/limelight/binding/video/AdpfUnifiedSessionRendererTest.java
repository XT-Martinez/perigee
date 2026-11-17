package com.limelight.binding.video;

import android.os.HandlerThread;

import com.limelight.BuildConfig;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

/** Renderer wiring of the unified ADPF session: snapshot composition and the per-frame path. */
@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class AdpfUnifiedSessionRendererTest {
    private static final int WORKER_TID = 99991;
    private static final int LOOPER_TID = 99992;
    private static final int RENDERER_TID = 99993;

    private CountingFactory factory;
    private AdpfSubmissionSession session;
    private MediaCodecDecoderRenderer renderer;
    private PreferenceConfiguration prefs;

    @Before
    public void setUp() throws Exception {
        factory = new CountingFactory();
        session = new AdpfSubmissionSession(factory, AdpfSubmissionSession.TARGET_NS);
        // The mock skips the constructor and field initializers, so set what the code reads.
        renderer = mock(MediaCodecDecoderRenderer.class, CALLS_REAL_METHODS);
        prefs = new PreferenceConfiguration();
        prefs.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
        set("prefs", prefs);
        set("asyncCodec", new AsyncCodecAdapter());
        set("adpfSubmissionSession", session);
    }

    @Test
    public void unifiedSnapshotHoldsEveryKnownPipelineThread() throws Exception {
        assumeTrue(BuildConfig.ADPF_UNIFIED_SESSION);
        set("adpfWorkerIdentity", new CodecThreadDiscovery.WorkerIdentity(WORKER_TID, 100));
        set("adpfCodecLooperIdentity", new CodecThreadDiscovery.WorkerIdentity(
                LOOPER_TID, 100, CodecThreadDiscovery.CODEC_LOOPER_THREAD_NAME));
        set("rendererTid", RENDERER_TID);

        invoke("refreshAdpfExtraTids");
        assertArrayEquals(new int[] {WORKER_TID, LOOPER_TID, RENDERER_TID}, extraTids());

        // Leaving Balanced keeps the unified members; only the legacy policy drops the worker.
        prefs.framePacing = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        invoke("refreshAdpfExtraTids");
        assertArrayEquals(new int[] {WORKER_TID, LOOPER_TID, RENDERER_TID}, extraTids());
    }

    @Test
    public void legacySnapshotKeepsOnlyTheWorkerAndOnlyInBalanced() throws Exception {
        assumeFalse(BuildConfig.ADPF_UNIFIED_SESSION);
        set("adpfWorkerIdentity", new CodecThreadDiscovery.WorkerIdentity(WORKER_TID, 100));
        set("adpfCodecLooperIdentity", new CodecThreadDiscovery.WorkerIdentity(
                LOOPER_TID, 100, CodecThreadDiscovery.CODEC_LOOPER_THREAD_NAME));
        set("rendererTid", RENDERER_TID);

        invoke("refreshAdpfExtraTids");
        assertArrayEquals(new int[] {WORKER_TID}, extraTids());
        assertTrue((long) invoke("startAdpfSubmissionWork") > 0L);
        assertEquals(2, factory.tids.length);

        prefs.framePacing = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        invoke("refreshAdpfExtraTids");
        assertEquals(0, extraTids().length);
        assertTrue((long) invoke("startAdpfSubmissionWork") > 0L);
        assertEquals(1, factory.tids.length);
    }

    @Test
    public void submitPathAppliesTheSnapshotOnceAndThenOnlyComparesReferences() throws Exception {
        assumeTrue(BuildConfig.ADPF_UNIFIED_SESSION);
        set("adpfWorkerIdentity", new CodecThreadDiscovery.WorkerIdentity(WORKER_TID, 100));
        set("rendererTid", RENDERER_TID);
        invoke("refreshAdpfExtraTids");

        for (int i = 0; i < 50; i++) {
            assertTrue((long) invoke("startAdpfSubmissionWork") > 0L);
        }
        assertEquals(1, factory.calls);
        assertEquals(3, factory.tids.length);
        assertTrue(contains(factory.tids, WORKER_TID));
        assertTrue(contains(factory.tids, RENDERER_TID));
    }

    @Test
    public void unchangedThreadsKeepTheSameSnapshotInstance() throws Exception {
        assumeTrue(BuildConfig.ADPF_UNIFIED_SESSION);
        set("rendererTid", RENDERER_TID);
        invoke("refreshAdpfExtraTids");
        int[] first = extraTids();
        invoke("refreshAdpfExtraTids");
        assertSame(first, extraTids());
    }

    @Test
    public void submitPathIsInputOnlyBeforeAnySnapshotExists() throws Exception {
        assertTrue((long) invoke("startAdpfSubmissionWork") > 0L);
        assertEquals(1, factory.calls);
        assertEquals(1, factory.tids.length);
    }

    @Test
    public void liveHandlerThreadJoinsTheSnapshotAndLeavesItOnceFinished() throws Exception {
        assumeTrue(BuildConfig.ADPF_UNIFIED_SESSION);
        HandlerThread choreographer = new HandlerThread("test-choreographer");
        choreographer.start();
        try {
            choreographer.getLooper(); // Blocks until the thread has published its tid.
            assumeTrue(choreographer.getThreadId() > 0);
            set("choreographerHandlerThread", choreographer);
            invoke("refreshAdpfExtraTids");
            assertArrayEquals(new int[] {choreographer.getThreadId()}, extraTids());
        } finally {
            choreographer.quit();
            choreographer.join(2000);
        }
        assertFalse(choreographer.isAlive());
        invoke("refreshAdpfExtraTids");
        assertEquals(0, extraTids().length);
    }

    @Test
    public void applyAdpfHintRefreshesTheSnapshotAndLeavesDefaultHintsAlone() throws Exception {
        assumeTrue(BuildConfig.ADPF_UNIFIED_SESSION);
        AdpfHintSession defaultHints = mock(AdpfHintSession.class);
        set("adpfHintSession", defaultHints);
        set("submissionHintsSelected", true);
        set("rendererTid", RENDERER_TID);

        invoke("applyAdpfHint");

        assertArrayEquals(new int[] {RENDERER_TID}, extraTids());
        verifyNoInteractions(defaultHints);
    }

    @Test
    public void closedSessionIsNeverRefreshedOrReconfigured() throws Exception {
        session.close();
        set("rendererTid", RENDERER_TID);
        invoke("refreshAdpfExtraTids");
        assertNull(extraTids());
        assertEquals(0L, invoke("startAdpfSubmissionWork"));
        assertEquals(0, factory.calls);
    }

    private int[] extraTids() throws Exception {
        Field field = MediaCodecDecoderRenderer.class.getDeclaredField("adpfExtraTids");
        field.setAccessible(true);
        return (int[]) field.get(renderer);
    }

    private Object invoke(String name) throws Exception {
        Method method = MediaCodecDecoderRenderer.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(renderer);
    }

    private void set(String name, Object value) throws Exception {
        Field field = MediaCodecDecoderRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(renderer, value);
    }

    private static boolean contains(int[] values, int wanted) {
        return Arrays.stream(values).anyMatch(v -> v == wanted);
    }

    private static final class CountingFactory implements AdpfSubmissionSession.SessionFactory {
        int calls;
        int[] tids;

        @Override
        public AdpfSubmissionSession.SessionHandle create(int[] tids, long targetNs) {
            calls++;
            this.tids = tids;
            return new AdpfSubmissionSession.SessionHandle() {
                @Override
                public void reportActualWorkDuration(long actualNs) { }

                @Override
                public void setThreads(int[] tids) { }

                @Override
                public void close() { }
            };
        }

        @Override
        public long getPreferredUpdateRateNanos() {
            return 83_333_333L;
        }
    }
}
