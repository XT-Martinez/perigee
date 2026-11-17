package com.limelight.binding.video;

import android.os.PerformanceHintManager;

import com.limelight.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class AdpfSubmissionSessionTest {
    private static AdpfSubmissionSession session(FakeFactory factory) {
        return new AdpfSubmissionSession(factory, AdpfSubmissionSession.TARGET_NS);
    }

    @Test
    public void scopeRequiresOptInApi31Av1GoogleDecoderAndPixel9a() {
        assertTrue(AdpfSubmissionSession.shouldEnable(true, 31, true,
                "c2.google.av1.decoder", "tegu", null, null));
        assertTrue(AdpfSubmissionSession.shouldEnable(true, 34, true,
                "c2.google.av1.decoder", null, "tegu", null));
        assertTrue(AdpfSubmissionSession.shouldEnable(true, 34, true,
                "C2.GOOGLE.AV1.DECODER", null, null, "Pixel 9a"));
        assertFalse(AdpfSubmissionSession.shouldEnable(false, 34, true,
                "c2.google.av1.decoder", "tegu", null, null));
        assertFalse(AdpfSubmissionSession.shouldEnable(true, 30, true,
                "c2.google.av1.decoder", "tegu", null, null));
        assertFalse(AdpfSubmissionSession.shouldEnable(true, 34, false,
                "c2.google.av1.decoder", "tegu", null, null));
        assertFalse(AdpfSubmissionSession.shouldEnable(true, 34, true,
                "c2.exynos.hevc.decoder", "tegu", null, null));
        assertFalse(AdpfSubmissionSession.shouldEnable(true, 34, true,
                null, "tegu", null, null));
        assertFalse(AdpfSubmissionSession.shouldEnable(true, 34, true,
                "c2.google.av1.decoder", "G08", "G08_4K_GB", "Smart TV Pro"));
    }

    @Test
    public void workerArmRequiresFlagAndBalanced() {
        assertTrue(AdpfSubmissionSession.shouldUseWorkerThread(true, true));
        assertFalse(AdpfSubmissionSession.shouldUseWorkerThread(false, true));
        assertFalse(AdpfSubmissionSession.shouldUseWorkerThread(true, false));
        assertFalse(AdpfSubmissionSession.shouldUseWorkerThread(false, false));
    }

    @Test
    public void targetUsesBuildConfigurationAndKeepsTwoMsFallback() {
        assertEquals(2_000_000L, AdpfSubmissionSession.DEFAULT_TARGET_NS);
        assertEquals(BuildConfig.ADPF_SUBMIT_TARGET_NS, AdpfSubmissionSession.TARGET_NS);
        assertEquals(2_000_000L, AdpfSubmissionSession.validateTargetNs(0));
        assertEquals(2_000_000L, AdpfSubmissionSession.validateTargetNs(-1));
        assertEquals(1_000_000L, AdpfSubmissionSession.validateTargetNs(1_000_000));
        assertEquals(1_500_000L, AdpfSubmissionSession.validateTargetNs(1_500_000));
    }

    @Test
    public void configuresOnceAndIgnoresInvalidInputTids() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(0, null);
        s.ensureConfigured(-1, null);
        assertEquals(0, f.calls);
        s.ensureConfigured(7, null);
        s.ensureConfigured(7, null);
        assertTrue(s.isActive());
        assertEquals(1, f.calls);
        assertArrayEquals(new int[] {7}, f.tids);
        assertEquals(AdpfSubmissionSession.TARGET_NS, f.targetNs);
    }

    @Test
    public void twoThreadSessionNormalizesOrderAndIgnoresEqualContent() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(42, new int[] {7});
        s.ensureConfigured(42, new int[] {7});
        assertEquals(1, f.calls);
        assertArrayEquals(new int[] {7, 42}, f.tids);
    }

    @Test
    public void invalidDuplicateOrNonPositiveExtrasAreDropped() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {7, 0, -1});
        assertEquals(1, f.calls);
        assertArrayEquals(new int[] {7}, f.tids);
        s.ensureConfigured(7, new int[] {42, 42, 0});
        assertEquals(2, f.calls);
        assertArrayEquals(new int[] {7, 42}, f.tids);
    }

    @Test
    public void unsupportedCreationLatches() {
        FakeFactory f = new FakeFactory();
        f.unsupported = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42});
        s.ensureConfigured(7, null);
        assertTrue(s.isDisabled());
        assertFalse(s.isActive());
        // Null with extras may only mean a refused tid, so input-only is tried before latching.
        assertEquals(2, f.calls);
    }

    @Test
    public void nullForTheExtraSetFallsBackToInputOnlyInsteadOfDisabling() {
        FakeFactory f = new FakeFactory();
        f.nullForExtras = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42, 50});
        assertFalse(s.isDisabled());
        assertTrue(s.isActive());
        assertEquals(2, f.calls);
        assertArrayEquals(new int[] {7}, f.tids);
        for (int i = 0; i < 100; i++) {
            s.ensureConfigured(7, new int[] {42, 50});
        }
        assertEquals(2, f.calls);
    }

    @Test
    public void droppingAllExtrasForgetsTheRejectedSet() {
        FakeFactory f = new FakeFactory();
        f.rejectWorker = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42});
        assertEquals(2, f.calls);
        s.ensureConfigured(7, null);
        f.rejectWorker = false;
        s.ensureConfigured(7, new int[] {42});
        assertEquals(3, f.calls);
        assertArrayEquals(new int[] {7, 42}, f.tids);
    }

    @Test
    public void rejectedInputCreationLatches() {
        FakeFactory f = new FakeFactory();
        f.rejectAll = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        s.ensureConfigured(7, null);
        assertTrue(s.isDisabled());
        assertEquals(1, f.calls);
    }

    @Test
    public void rejectedExtrasFallBackWithoutPerFrameRetries() {
        FakeFactory f = new FakeFactory();
        f.rejectWorker = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42});
        assertTrue(s.isActive());
        assertFalse(s.isDisabled());
        assertArrayEquals(new int[] {7}, f.tids);
        for (int i = 0; i < 100; i++) {
            s.ensureConfigured(7, new int[] {42});
        }
        assertEquals(2, f.calls);
        // A different extra set is genuinely new and is attempted once, then falls back again.
        s.ensureConfigured(7, new int[] {42, 50});
        assertEquals(4, f.calls);
        assertArrayEquals(new int[] {7}, f.tids);
        assertTrue(s.isActive());
    }

    @Test
    public void bothCreationAttemptsFailAndDisable() {
        FakeFactory f = new FakeFactory();
        f.rejectAll = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42});
        s.ensureConfigured(7, new int[] {43});
        assertTrue(s.isDisabled());
        assertEquals(2, f.calls);
    }

    @Test
    public void diagnosticPreferredRateIsNotQueriedWhenCreatingSession() {
        FakeFactory f = new FakeFactory();
        f.rejectPreferred = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        assertFalse(s.isDisabled());
        assertTrue(s.isActive());
        assertFalse(f.handles.get(0).closed);
        s.ensureConfigured(7, null);
        assertEquals(1, f.calls);
    }

    @Test
    public void everyCompletedCycleIsReportedEvenFasterThanPreferredRate() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        assertFalse(s.reportWorkDuration(1_000_000L));
        s.ensureConfigured(7, null);
        assertFalse(s.reportWorkDuration(0));
        assertFalse(s.reportWorkDuration(-1));
        assertFalse(s.reportWorkDuration(AdpfSubmissionSession.MAX_WORK_NS + 1));
        for (int i = 1; i <= 100; i++) {
            assertTrue(s.reportWorkDuration(i * 100_000L));
        }
        assertEquals(100, f.latest().reports);
        assertEquals(10_000_000L, f.latest().lastReportNs); // Over-budget is not clamped.
    }

    @Test
    public void rejectedReportClosesAndLatches() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        f.latest().rejectReport = true;
        assertFalse(s.reportWorkDuration(1_000_000L));
        assertTrue(s.isDisabled());
        assertTrue(f.latest().closed);
        assertFalse(s.reportWorkDuration(1_000_000L));
        s.ensureConfigured(7, new int[] {42});
        assertEquals(1, f.calls);
    }

    @Test
    public void closeIsIdempotentAndNeverResurrects() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        s.close();
        s.close();
        s.ensureConfigured(7, null);
        assertFalse(s.isActive());
        assertTrue(s.isDisabled());
        assertEquals(1, f.calls);
        assertEquals(1, f.latest().closeCalls);
    }

    @Test
    public void preApi34ThreadChangeRecreatesAndClosesOldSession() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        s.ensureConfigured(7, new int[] {42});
        assertEquals(2, f.calls);
        assertTrue(f.handles.get(0).closed);
        assertArrayEquals(new int[] {7, 42}, f.tids);
        s.clearExtraThreads();
        assertEquals(3, f.calls);
        assertTrue(f.handles.get(1).closed);
        assertArrayEquals(new int[] {7}, f.tids);
    }

    @Test
    @Config(sdk = 34)
    public void api34ThreadChangeUpdatesExistingSession() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        s.ensureConfigured(7, new int[] {42});
        assertEquals(1, f.calls);
        assertEquals(1, f.latest().updates);
        assertArrayEquals(new int[] {7, 42}, f.latest().updatedTids);
    }

    @Test
    @Config(sdk = 34)
    public void api34RejectedUpdateFallsBackAndLatchesExtraSet() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, null);
        FakeHandle old = f.latest();
        old.rejectUpdate = true;
        s.ensureConfigured(7, new int[] {42});
        for (int i = 0; i < 100; i++) {
            s.ensureConfigured(7, new int[] {42});
        }
        assertTrue(old.closed);
        assertEquals(1, old.updates);
        assertEquals(2, f.calls);
        assertArrayEquals(new int[] {7}, f.tids);
        assertTrue(s.isActive());
    }

    @Test
    public void clearExtraThreadsAllowsRejectedSetToBeTriedAgain() {
        FakeFactory f = new FakeFactory();
        f.rejectWorker = true;
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42});
        s.clearExtraThreads();
        s.ensureConfigured(7, new int[] {42});
        assertEquals(4, f.calls);
    }

    @Test
    public void rendererDoesNotStartCycleAfterSessionClose() throws Exception {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.close();
        MediaCodecDecoderRenderer renderer = mock(MediaCodecDecoderRenderer.class, CALLS_REAL_METHODS);
        set(renderer, "adpfSubmissionSession", s);
        Method start = MediaCodecDecoderRenderer.class.getDeclaredMethod("startAdpfSubmissionWork");
        start.setAccessible(true);
        assertEquals(0L, start.invoke(renderer));
        assertEquals(0, f.calls);
    }

    @Test
    public void rendererNeverConfiguresDefaultHintsAlongsideSubmissionHintsOrAfterStop() throws Exception {
        MediaCodecDecoderRenderer renderer = mock(MediaCodecDecoderRenderer.class, CALLS_REAL_METHODS);
        AdpfHintSession defaultHints = mock(AdpfHintSession.class);
        set(renderer, "adpfHintSession", defaultHints);
        set(renderer, "submissionHintsSelected", true);
        Method apply = MediaCodecDecoderRenderer.class.getDeclaredMethod("applyAdpfHint");
        apply.setAccessible(true);
        apply.invoke(renderer);
        set(renderer, "submissionHintsSelected", false);
        set(renderer, "stopping", true);
        apply.invoke(renderer);
        verifyNoInteractions(defaultHints);
    }

    @Test
    public void defaultHintSessionAlsoCannotResurrectAfterClose() throws Exception {
        PerformanceHintManager manager = mock(PerformanceHintManager.class);
        PerformanceHintManager.Session platform = mock(PerformanceHintManager.Session.class);
        when(manager.createHintSession(any(int[].class), anyLong())).thenReturn(platform);
        java.lang.reflect.Constructor<AdpfHintSession> constructor =
                AdpfHintSession.class.getDeclaredConstructor(PerformanceHintManager.class);
        constructor.setAccessible(true);
        AdpfHintSession hints = constructor.newInstance(manager);
        hints.configure(new int[] {7}, 2_000_000L);
        assertTrue(hints.isActive());
        hints.close();
        hints.configure(new int[] {7}, 2_000_000L);
        assertFalse(hints.isActive());
        verify(manager, times(1)).createHintSession(any(int[].class), anyLong());
        verify(platform, times(1)).close();
    }

    @Test
    public void selectExtraTidsUnifiedReturnsAllPositiveSortedUniqueTids() {
        assertArrayEquals(new int[] {42, 50, 60},
                AdpfSubmissionSession.selectExtraTids(true, false, 60, 42, 50, 0, 42));
        assertArrayEquals(new int[] {42, 50, 60},
                AdpfSubmissionSession.selectExtraTids(true, true, 60, 42, 50, 0, 42));
    }

    @Test
    public void selectExtraTidsLegacyReturnsOnlyBalancedWorker() {
        assertArrayEquals(new int[] {42},
                AdpfSubmissionSession.selectExtraTids(false, true, 42, 50, 60, 70, 80));
        assertSame(AdpfSubmissionSession.NO_EXTRA_TIDS,
                AdpfSubmissionSession.selectExtraTids(false, false, 42, 50, 60, 70, 80));
    }

    @Test
    public void selectExtraTidsReturnsSharedEmptyInstanceWhenNothingQualifies() {
        assertSame(AdpfSubmissionSession.NO_EXTRA_TIDS,
                AdpfSubmissionSession.selectExtraTids(true, true, 0, -1, 0, 0, -5));
        assertSame(AdpfSubmissionSession.NO_EXTRA_TIDS,
                AdpfSubmissionSession.selectExtraTids(false, true, 0, 50, 60, 70, 80));
    }

    @Test
    public void severalExtraTidsCreateOneNormalizedSession() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {50, 42, 42, 0, -1, 7, 60});
        assertEquals(1, f.calls);
        assertArrayEquals(new int[] {7, 42, 50, 60}, f.tids);
        assertTrue(s.isActive());
    }

    @Test
    @Config(sdk = 34)
    public void sameExtraArrayReferenceSkipsAllWork() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        int[] extras = {42, 50};
        for (int i = 0; i < 100; i++) {
            s.ensureConfigured(7, extras);
        }
        assertEquals(1, f.calls);
        assertEquals(0, f.latest().updates);
    }

    @Test
    @Config(sdk = 34)
    public void equalExtraContentInNewArrayDoesNotReconfigure() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42, 50});
        s.ensureConfigured(7, new int[] {50, 42});
        assertEquals(1, f.calls);
        assertEquals(0, f.latest().updates);
    }

    @Test
    @Config(sdk = 34)
    public void api34RemovingThreadUsesSingleSetThreads() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42, 50});
        assertEquals(1, f.calls);
        s.ensureConfigured(7, new int[] {42});
        assertEquals(1, f.calls);
        assertEquals(1, f.latest().updates);
        assertArrayEquals(new int[] {7, 42}, f.latest().updatedTids);
        assertFalse(f.latest().closed);
    }

    @Test
    public void clearExtraThreadsBeforeApi34RecreatesInputOnly() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42, 50});
        assertEquals(1, f.calls);
        s.clearExtraThreads();
        assertEquals(2, f.calls);
        assertTrue(f.handles.get(0).closed);
        assertArrayEquals(new int[] {7}, f.tids);
        assertTrue(s.isActive());
        assertEquals(0, f.latest().updates);
    }

    @Test
    @Config(sdk = 34)
    public void clearExtraThreadsOnApi34UsesSetThreads() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42, 50});
        s.clearExtraThreads();
        assertEquals(1, f.calls);
        assertEquals(1, f.handles.get(0).updates);
        assertArrayEquals(new int[] {7}, f.handles.get(0).updatedTids);
        assertFalse(f.handles.get(0).closed);
    }

    @Test
    public void inputTidChangeWithSameExtrasRecreatesBeforeApi34() {
        FakeFactory f = new FakeFactory();
        AdpfSubmissionSession s = session(f);
        s.ensureConfigured(7, new int[] {42});
        s.ensureConfigured(8, new int[] {42});
        assertEquals(2, f.calls);
        assertTrue(f.handles.get(0).closed);
        assertArrayEquals(new int[] {8, 42}, f.tids);
        assertTrue(s.isActive());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MediaCodecDecoderRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeFactory implements AdpfSubmissionSession.SessionFactory {
        int calls;
        int[] tids;
        long targetNs;
        boolean unsupported;
        boolean rejectAll;
        boolean rejectWorker;
        boolean nullForExtras;
        boolean rejectPreferred;
        final List<FakeHandle> handles = new ArrayList<>();

        FakeHandle latest() {
            return handles.get(handles.size() - 1);
        }

        @Override
        public AdpfSubmissionSession.SessionHandle create(int[] tids, long targetNs) {
            calls++;
            this.tids = tids;
            this.targetNs = targetNs;
            if (rejectAll || (rejectWorker && tids.length > 1)) {
                throw new IllegalStateException("rejected");
            }
            if (unsupported || (nullForExtras && tids.length > 1)) {
                return null;
            }
            FakeHandle handle = new FakeHandle();
            handles.add(handle);
            return handle;
        }

        @Override
        public long getPreferredUpdateRateNanos() {
            if (rejectPreferred) {
                throw new IllegalStateException("unavailable");
            }
            return 83_333_333L;
        }
    }

    private static final class FakeHandle implements AdpfSubmissionSession.SessionHandle {
        int reports;
        long lastReportNs;
        int updates;
        int[] updatedTids;
        int closeCalls;
        boolean closed;
        boolean rejectReport;
        boolean rejectUpdate;

        @Override
        public void reportActualWorkDuration(long actualNs) {
            if (rejectReport) {
                throw new IllegalStateException("rejected");
            }
            reports++;
            lastReportNs = actualNs;
        }

        @Override
        public void setThreads(int[] tids) {
            updates++;
            if (rejectUpdate) {
                throw new IllegalStateException("rejected");
            }
            updatedTids = tids;
        }

        @Override
        public void close() {
            closed = true;
            closeCalls++;
        }
    }
}
