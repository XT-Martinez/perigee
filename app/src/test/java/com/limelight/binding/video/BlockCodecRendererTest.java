package com.limelight.binding.video;

import android.media.MediaCodec;
import android.content.Context;
import android.preference.PreferenceManager;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Checks actual renderer input methods without initializing a real codec or streaming threads. */
@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class BlockCodecRendererTest {
    private static MediaCodecDecoderRenderer renderer(boolean block) throws Exception {
        MediaCodecDecoderRenderer renderer = mock(MediaCodecDecoderRenderer.class, CALLS_REAL_METHODS);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.fps = 120;
        set(renderer, "prefs", prefs);
        set(renderer, "blockInputActive", block);
        set(renderer, "useAsyncCodec", true);
        set(renderer, "videoDecoder", mock(MediaCodec.class));
        set(renderer, "asyncCodec", mock(AsyncCodecAdapter.class));
        set(renderer, "codecRecoveryType", new AtomicInteger(0));
        set(renderer, "nextInputBufferIndex", -1);
        set(renderer, "decodeLatencyTracker", mock(DecodeLatencyTracker.class));
        return renderer;
    }

    private static void set(Object object, String field, Object value) throws Exception {
        Field f = MediaCodecDecoderRenderer.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(object, value);
    }

    private static Object get(Object object, String field) throws Exception {
        Field f = MediaCodecDecoderRenderer.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(object);
    }

    private static boolean inputCall(Object renderer, String name) throws Exception {
        Method m = MediaCodecDecoderRenderer.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (boolean) m.invoke(renderer);
    }

    @Test
    public void blockPrefetchReservesOneSlotAndFetchKeepsItWithoutMapping() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(true);
        AsyncCodecAdapter async = (AsyncCodecAdapter) get(renderer, "asyncCodec");
        MediaCodec codec = (MediaCodec) get(renderer, "videoDecoder");
        when(async.dequeueInputIndex(0)).thenReturn(4);
        assertTrue(inputCall(renderer, "prefetchNextInputBuffer"));
        assertEquals(4, get(renderer, "nextInputBufferIndex"));
        assertNull(get(renderer, "nextInputBuffer"));
        assertTrue(inputCall(renderer, "fetchNextInputBuffer"));
        assertTrue(inputCall(renderer, "prefetchNextInputBuffer"));
        verify(async, times(1)).dequeueInputIndex(0);
        verifyNoInteractions(codec);
    }

    @Test
    public void blockBlockingFetchUsesAsyncCallbackSlotNotLegacyApi() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(true);
        AsyncCodecAdapter async = (AsyncCodecAdapter) get(renderer, "asyncCodec");
        when(async.dequeueInputIndex(2000)).thenReturn(5);
        assertTrue(inputCall(renderer, "fetchNextInputBuffer"));
        assertEquals(5, get(renderer, "nextInputBufferIndex"));
        assertNull(get(renderer, "nextInputBuffer"));
        verifyNoInteractions((MediaCodec) get(renderer, "videoDecoder"));
    }

    @Test
    public void blockPrefetchMissDoesNotAllocateOrLoseAnOwnedSlot() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(true);
        AsyncCodecAdapter async = (AsyncCodecAdapter) get(renderer, "asyncCodec");
        when(async.dequeueInputIndex(0)).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
        assertTrue(inputCall(renderer, "prefetchNextInputBuffer"));
        assertEquals(-1, get(renderer, "nextInputBufferIndex"));
        assertNull(get(renderer, "nextInputBuffer"));
        verifyNoInteractions((MediaCodec) get(renderer, "videoDecoder"));
    }

    @Test
    public void legacyPrefetchStillGetsAndClearsTheCodecBuffer() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(false);
        AsyncCodecAdapter async = (AsyncCodecAdapter) get(renderer, "asyncCodec");
        MediaCodec codec = (MediaCodec) get(renderer, "videoDecoder");
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.position(10);
        when(async.dequeueInputIndex(0)).thenReturn(2);
        when(codec.getInputBuffer(2)).thenReturn(buffer);
        assertTrue(inputCall(renderer, "prefetchNextInputBuffer"));
        assertSame(buffer, get(renderer, "nextInputBuffer"));
        assertEquals(0, buffer.position());
        verify(codec).getInputBuffer(2);
    }

    @Test
    public void resetDropsTheReservedBlockSlot() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(true);
        set(renderer, "nextInputBufferIndex", 8);
        Method m = MediaCodecDecoderRenderer.class.getDeclaredMethod("resetInputBufferState");
        m.setAccessible(true);
        m.invoke(renderer);
        assertEquals(-1, get(renderer, "nextInputBufferIndex"));
        assertNull(get(renderer, "nextInputBuffer"));
    }

    @Test
    public void fallbackLatchesAndPromotesRecoveryWithoutChangingExecutingApis() throws Exception {
        for (int oldRecovery : new int[] { 0, 1, 2, 3 }) {
            MediaCodecDecoderRenderer renderer = renderer(true);
            AtomicInteger recovery = (AtomicInteger) get(renderer, "codecRecoveryType");
            recovery.set(oldRecovery);
            Method m = MediaCodecDecoderRenderer.class.getDeclaredMethod("requestBlockInputFallback", Throwable.class);
            m.setAccessible(true);
            m.invoke(renderer, new IllegalStateException("test failure"));
            assertTrue((boolean) get(renderer, "blockInputDisabled"));
            assertTrue((boolean) get(renderer, "blockInputActive"));
            assertEquals(3, recovery.get());
        }
    }

    @Test
    public void shutdownDoesNotScheduleNewRecovery() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(true);
        set(renderer, "stopping", true);
        Method m = MediaCodecDecoderRenderer.class.getDeclaredMethod("requestBlockInputFallback", Throwable.class);
        m.setAccessible(true);
        m.invoke(renderer, new IllegalStateException("shutdown"));
        assertEquals(0, ((AtomicInteger) get(renderer, "codecRecoveryType")).get());
    }

    @Test
    public void disablingAsyncPromotesAnyPendingRestartToResetBeforeChangingApis() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_async_decode", false).commit();
        for (int oldRecovery : new int[] { 0, 1, 2, 3 }) {
            MediaCodecDecoderRenderer renderer = renderer(true);
            set(renderer, "context", context);
            set(renderer, "requestedUseAsyncCodec", true);
            AtomicInteger recovery = (AtomicInteger) get(renderer, "codecRecoveryType");
            recovery.set(oldRecovery);
            Method m = MediaCodecDecoderRenderer.class.getDeclaredMethod("maybeApplyRuntimeAsyncDecodingRequest");
            m.setAccessible(true);
            m.invoke(renderer);
            assertFalse((boolean) get(renderer, "requestedUseAsyncCodec"));
            assertTrue((boolean) get(renderer, "useAsyncCodec"));
            assertTrue((boolean) get(renderer, "blockInputActive"));
            assertEquals(3, recovery.get());
        }
    }

    @Test
    public void blockQueueCopiesRecyclesAndPrefetchesOnlyTheNextSlot() throws Exception {
        MediaCodecDecoderRenderer renderer = renderer(true);
        AsyncCodecAdapter async = (AsyncCodecAdapter) get(renderer, "asyncCodec");
        MediaCodec codec = (MediaCodec) get(renderer, "videoDecoder");
        set(renderer, "nextInputBufferIndex", 3);
        when(async.dequeueInputIndex(0)).thenReturn(6);
        boolean[] closed = { false };
        ByteBuffer mapped = ByteBuffer.allocate(4096);
        set(renderer, "blockCodecInput", new BlockCodecInput(capacity -> new BlockCodecInput.Block() {
            @Override public ByteBuffer map() { return mapped; }
            @Override public void queue(MediaCodec c, int index, int size, long ptsUs, int flags) {
                assertSame(codec, c);
                assertEquals(3, index);
                assertEquals(2, size);
                assertEquals(42, ptsUs);
                assertEquals(1, flags);
                assertEquals(11, mapped.get(0));
                assertEquals(22, mapped.get(1));
            }
            @Override public void close() { closed[0] = true; }
        }));
        Method m = MediaCodecDecoderRenderer.class.getDeclaredMethod("queueNextInputBuffer",
                long.class, int.class, byte[].class, int.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(renderer, 42L, 1, new byte[] { 11, 22 }, 2));
        assertTrue(closed[0]);
        assertEquals(6, get(renderer, "nextInputBufferIndex"));
        assertNull(get(renderer, "nextInputBuffer"));
        verifyNoInteractions(codec);
        verify((DecodeLatencyTracker) get(renderer, "decodeLatencyTracker")).onEnqueue(42L);
    }
}
