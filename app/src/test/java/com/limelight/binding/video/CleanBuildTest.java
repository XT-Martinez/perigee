package com.limelight.binding.video;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CleanBuildTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void optimizedBuildHasQuietConfiguration() {
        assumeTrue("perf".equals(BuildConfig.BUILD_TYPE));
        assertFalse(BuildConfig.DEBUG);
        assertFalse(BuildConfig.LOG_ENABLED);
        assertFalse(BuildConfig.LOG_VERBOSE);
        assertFalse(BuildConfig.AV1_OPERATING_RATE_240);
        assertFalse(BuildConfig.BALANCED_SUBTRACT_APP_VSYNC_OFFSET);
        assertEquals(1_000_000, BuildConfig.ADPF_SUBMIT_TARGET_NS);
    }

    @Test public void quietLoggerDoesNotPublishOrCreateFiles() throws Exception {
        assumeTrue("perf".equals(BuildConfig.BUILD_TYPE));
        AtomicInteger messages = new AtomicInteger();
        Logger logger = Logger.getLogger(LimeLog.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.incrementAndGet(); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            File target = new File(temporary.getRoot(), "disabled.log");
            LimeLog.setFileHandler(target.getAbsolutePath());
            LimeLog.info("info");
            LimeLog.warning("warning");
            LimeLog.severe("severe");
            assertEquals(0, messages.get());
            assertEquals(0, temporary.getRoot().list().length);
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test public void completionDiagnosticsAreNotWiredIntoStreaming() {
        for (Field field : MediaCodecDecoderRenderer.class.getDeclaredFields()) {
            assertFalse(field.getName().contains("adpfCompletion"));
        }
        for (Field field : AsyncCodecAdapter.class.getDeclaredFields()) {
            assertFalse(field.getName().startsWith("completion"));
        }
    }
}
