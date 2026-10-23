package com.limelight.binding.video;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.BuildConfig;

import java.nio.ByteBuffer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scope and configuration tests for the gated Pixel 9a AV1 operating-rate 240 experiment.
 *
 * <p>The pure predicate is exercised independently of the build variant. Runtime tests also check
 * the variant's compile-time gate ({@code BuildConfig.AV1_OPERATING_RATE_240}). This keeps
 * the change narrow: AV1 only (SDR and HDR alike, since the predicate never inspects dynamic
 * range), Google's c2 decoder only, Pixel 9a only, and never when disabled.
 *
 * <p>Robolectric is used because loading {@link MediaCodecHelper} runs Android-dependent static
 * initializers.
 */
@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class MediaCodecHelperAv1OperatingRateTest {

    private static final String AV1 = "video/av01";
    private static final String HEVC = "video/hevc";
    private static final String GOOGLE_AV1 = "c2.google.av1.decoder";

    @Before
    public void pixelIdentity() {
        ShadowBuild.setDevice("tegu");
        ShadowBuild.setProduct("tegu");
        ShadowBuild.setModel("Pixel 9a");
        MediaCodecHelper.initialize(ApplicationProvider.getApplicationContext(), "Mali-G715");
    }

    private static MediaCodecInfo googleAv1() {
        MediaCodecInfo info = mock(MediaCodecInfo.class);
        when(info.getName()).thenReturn(GOOGLE_AV1);
        return info;
    }

    private static boolean match(boolean enabled, String mime, String decoder, String device,
                                 String product, String model) {
        return MediaCodecHelper.shouldApplyAv1OperatingRate240Hint(
                enabled, mime, decoder, device, product, model);
    }

    @Test
    public void disabledGateNeverApplies() {
        assertFalse(match(false, AV1, GOOGLE_AV1, "tegu", "tegu", "Pixel 9a"));
    }

    @Test
    public void matchingAv1GoogleDecoderOnPixel9aApplies() {
        assertTrue(match(true, AV1, GOOGLE_AV1, "tegu", "tegu", "Pixel 9a"));
        // Build.MODEL alone is enough if DEVICE/PRODUCT are unavailable.
        assertTrue(match(true, AV1, GOOGLE_AV1, null, null, "Pixel 9a"));
    }

    @Test
    public void otherCodecsAreExcluded() {
        assertFalse(match(true, HEVC, GOOGLE_AV1, "tegu", "tegu", "Pixel 9a"));
        assertFalse(match(true, "video/avc", GOOGLE_AV1, "tegu", "tegu", "Pixel 9a"));
        assertFalse(match(true, null, GOOGLE_AV1, "tegu", "tegu", "Pixel 9a"));
    }

    @Test
    public void otherAv1DecodersAreExcluded() {
        assertFalse(match(true, AV1, "c2.android.av1.decoder", "tegu", "tegu", "Pixel 9a"));
        assertFalse(match(true, AV1, "c2.exynos.av1.decoder", "tegu", "tegu", "Pixel 9a"));
        assertFalse(match(true, AV1, null, "tegu", "tegu", "Pixel 9a"));
    }

    @Test
    public void otherDevicesAreExcluded() {
        assertFalse(match(true, AV1, GOOGLE_AV1, "komodo", "komodo", "Pixel 9 Pro XL"));
        assertFalse(match(true, AV1, GOOGLE_AV1, "caiman", "caiman", "Pixel 9"));
        assertFalse(match(true, AV1, GOOGLE_AV1, null, null, null));
    }

    @Test
    public void runtimeApplicabilityUsesBuildGateAndRejectsMissingFormat() {
        MediaCodecInfo info = googleAv1();
        MediaFormat format = MediaFormat.createVideoFormat(AV1, 1920, 1080);
        assertEquals(BuildConfig.AV1_OPERATING_RATE_240,
                MediaCodecHelper.isAv1OperatingRate240HintApplicable(format, info));
        assertFalse(MediaCodecHelper.isAv1OperatingRate240HintApplicable(null, info));
        assertFalse(MediaCodecHelper.isAv1OperatingRate240HintApplicable(format, null));
        assertFalse(MediaCodecHelper.isAv1OperatingRate240HintApplicable(new MediaFormat(), info));
    }

    @Test
    public void fallbackFormatsRetainFloatHintWithoutExtendingRetryLoop() {
        MediaCodecInfo info = googleAv1();
        for (int attempt = 0; attempt <= 3; attempt++) {
            MediaFormat format = MediaFormat.createVideoFormat(AV1, 1920, 1080);
            boolean moreOptions = MediaCodecHelper.setDecoderLowLatencyOptions(
                    format, info, true, 120, attempt);
            assertEquals(attempt < 3, moreOptions);
            assertEquals(BuildConfig.AV1_OPERATING_RATE_240,
                    format.containsKey(MediaFormat.KEY_OPERATING_RATE));
            if (BuildConfig.AV1_OPERATING_RATE_240) {
                assertEquals(240f, format.getFloat(MediaFormat.KEY_OPERATING_RATE), 0f);
            }
        }
    }

    @Test
    public void suppressedHintLeavesTerminalFormatClean() {
        MediaFormat format = MediaFormat.createVideoFormat(AV1, 1920, 1080);
        assertFalse(MediaCodecHelper.setDecoderLowLatencyOptions(
                format, googleAv1(), true, 120, 3, false));
        assertFalse(format.containsKey(MediaFormat.KEY_OPERATING_RATE));
    }

    @Test
    public void hdrMetadataAndFrameRateAreUnchanged() {
        MediaFormat format = MediaFormat.createVideoFormat(AV1, 1920, 1080);
        ByteBuffer hdr = ByteBuffer.wrap(new byte[] {1, 2, 3});
        format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdr);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 120);
        MediaCodecHelper.setDecoderLowLatencyOptions(format, googleAv1(), true, 120, 0);
        assertSame(hdr, format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO));
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        assertEquals(120, format.getInteger(MediaFormat.KEY_FRAME_RATE));
        assertEquals(BuildConfig.AV1_OPERATING_RATE_240,
                format.containsKey(MediaFormat.KEY_OPERATING_RATE));
    }
}
