package com.limelight.binding.video;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CodecThreadDiscoveryTest {
    @Test
    public void findsExactlyOneNewWorkerWithPositiveStartToken() {
        FakeReader r = new FakeReader();
        r.tasks.put(1, "main");
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.starts.put(2, 100L);
        CodecThreadDiscovery.WorkerIdentity id = d.finishCapture();
        assertEquals(2, id.tid);
        assertEquals(100L, id.startTimeTicks);
        assertFalse(d.isCapturing());
    }

    @Test
    public void ignoresExistingWorkersUnrelatedAndPreviouslyUnreadableTasks() {
        FakeReader r = new FakeReader();
        r.tasks.put(1, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.tasks.put(2, null);
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        d.beginCapture();
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.tasks.put(3, "CodecLooper");
        assertNull(d.finishCapture());
    }

    @Test
    public void ambiguousWorkersAreNeverRegistered() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        d.beginCapture();
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.tasks.put(3, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.starts.put(2, 100L);
        r.starts.put(3, 100L);
        assertNull(d.finishCapture());
    }

    @Test
    public void missingOrNonPositiveStartTokenIsRejected() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        d.beginCapture();
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        assertNull(d.finishCapture());
        r.tasks.clear();
        d.beginCapture();
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.starts.put(2, -100L);
        assertNull(d.finishCapture());
    }

    @Test
    public void cancellationAndMissingSnapshotFallBack() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertNull(d.finishCapture());
        d.beginCapture();
        d.cancelCapture();
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.starts.put(2, 100L);
        assertFalse(d.isCapturing());
        assertNull(d.finishCapture());
    }

    @Test
    public void unavailableTaskListFallsBackBeforeAndAfterCreation() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        r.unavailable = true;
        assertFalse(d.beginCapture());
        assertNull(d.finishCapture());
        r.unavailable = false;
        assertTrue(d.beginCapture());
        r.unavailable = true;
        assertNull(d.finishCapture());
    }

    @Test
    public void throwingReadersNeverBreakDiscoveryOrRevalidation() {
        CodecThreadDiscovery d = new CodecThreadDiscovery(new CodecThreadDiscovery.TaskReader() {
            @Override
            public Map<Integer, String> readTaskNames() {
                throw new SecurityException("denied");
            }

            @Override
            public long readStartTimeTicks(int tid) {
                throw new SecurityException("denied");
            }
        });
        assertFalse(d.beginCapture());
        assertNull(d.finishCapture());
        assertFalse(d.isStillValid(new CodecThreadDiscovery.WorkerIdentity(2, 100)));
    }

    @Test
    public void revalidationRejectsTidReuseNameChangeDeathAndUnreadableToken() {
        FakeReader r = new FakeReader();
        r.tasks.put(2, CodecThreadDiscovery.WORKER_THREAD_NAME);
        r.starts.put(2, 100L);
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        CodecThreadDiscovery.WorkerIdentity id = new CodecThreadDiscovery.WorkerIdentity(2, 100);
        assertTrue(d.isStillValid(id));
        r.starts.put(2, 200L);
        assertFalse(d.isStillValid(id));
        r.starts.put(2, 0L);
        assertFalse(d.isStillValid(id));
        r.starts.put(2, 100L);
        r.tasks.put(2, "CodecLooper");
        assertFalse(d.isStillValid(id));
        r.tasks.clear();
        assertFalse(d.isStillValid(id));
        assertFalse(d.isStillValid(null));
        assertFalse(d.isStillValid(new CodecThreadDiscovery.WorkerIdentity(2, 0)));
    }

    private static final class FakeReader implements CodecThreadDiscovery.TaskReader {
        final Map<Integer, String> tasks = new HashMap<>();
        final Map<Integer, Long> starts = new HashMap<>();
        boolean unavailable;

        @Override
        public Map<Integer, String> readTaskNames() {
            // Deliberately return the mutable map to verify beginCapture owns its snapshot.
            return unavailable ? null : tasks;
        }

        @Override
        public long readStartTimeTicks(int tid) {
            Long start = starts.get(tid);
            return start != null ? start : 0L;
        }
    }
}
