package com.limelight.binding.video;

import android.media.MediaCodec;
import android.os.Handler;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AsyncCodecAdapterTest {
    @Test public void outputDeliveryRetainsInfoAndReadyTimestampWithoutDiagnostics() {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec codec = mock(MediaCodec.class);
        AsyncCodecAdapter.OutputRelease release = (c, index) -> { };
        adapter.attachIfNeeded(codec, 0, null, release);
        try {
            ArgumentCaptor<MediaCodec.Callback> captured = ArgumentCaptor.forClass(MediaCodec.Callback.class);
            verify(codec).setCallback(captured.capture(), any(Handler.class));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, 12, 42, 0);
            captured.getValue().onOutputBufferAvailable(codec, 0, info);
            MediaCodec.BufferInfo delivered = new MediaCodec.BufferInfo();
            assertEquals(0, adapter.dequeueOutputIndex(codec, delivered, 0, release));
            assertEquals(12, delivered.size);
            assertEquals(42, delivered.presentationTimeUs);
            assertTrue(adapter.getLastOutputReadyNs() > 0);
        } finally {
            adapter.detach(codec, release);
        }
    }
}
