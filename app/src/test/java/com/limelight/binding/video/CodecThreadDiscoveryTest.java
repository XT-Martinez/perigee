package com.limelight.binding.video;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CodecThreadDiscoveryTest {
    private static final String WORKER = CodecThreadDiscovery.WORKER_THREAD_NAME;
    private static final String LOOPER = CodecThreadDiscovery.CODEC_LOOPER_THREAD_NAME;

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

    @Test
    public void findsNewWorkerAndCodecLooperInRequestedOrderWithIdentity() {
        FakeReader r = new FakeReader();
        r.tasks.put(1, "main");
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.tasks.put(2, WORKER);
        r.starts.put(2, 100L);
        r.tasks.put(3, LOOPER);
        r.starts.put(3, 300L);
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture(LOOPER, WORKER);
        assertEquals(2, ids.length);
        assertEquals(3, ids[0].tid);
        assertEquals(300L, ids[0].startTimeTicks);
        assertEquals(LOOPER, ids[0].name);
        assertEquals(2, ids[1].tid);
        assertEquals(100L, ids[1].startTimeTicks);
        assertEquals(WORKER, ids[1].name);
        assertFalse(d.isCapturing());
    }

    @Test
    public void preExistingCodecLooperThreadsAreIgnored() {
        FakeReader r = new FakeReader();
        r.tasks.put(1, LOOPER);
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.tasks.put(2, LOOPER);
        r.starts.put(2, 200L);
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture(LOOPER);
        assertEquals(1, ids.length);
        assertEquals(2, ids[0].tid);
        assertEquals(200L, ids[0].startTimeTicks);
    }

    @Test
    public void twoNewCodecLoopersNullOnlyTheLooperEntry() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.tasks.put(2, WORKER);
        r.starts.put(2, 100L);
        r.tasks.put(3, LOOPER);
        r.starts.put(3, 300L);
        r.tasks.put(4, LOOPER);
        r.starts.put(4, 400L);
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture(WORKER, LOOPER);
        assertEquals(2, ids.length);
        assertNotNull(ids[0]);
        assertEquals(2, ids[0].tid);
        assertNull(ids[1]);
    }

    @Test
    public void missingNullAndNonPositiveEntriesAreNullPerName() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.tasks.put(2, WORKER);
        r.starts.put(2, -5L);
        r.tasks.put(3, LOOPER);
        r.starts.put(3, 300L);
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture((String) null, WORKER, LOOPER, "Missing");
        assertEquals(4, ids.length);
        assertNull(ids[0]);
        assertNull(ids[1]);
        assertEquals(3, ids[2].tid);
        assertNull(ids[3]);
        assertFalse(d.isCapturing());
    }

    @Test
    public void unreadableStartTokenNullsOnlyThatEntry() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.tasks.put(2, WORKER);
        r.starts.put(2, 100L);
        r.tasks.put(3, LOOPER);
        r.starts.put(3, 300L);
        r.throwForTid = 2;
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture(WORKER, LOOPER);
        assertNull(ids[0]);
        assertEquals(3, ids[1].tid);
        assertEquals(300L, ids[1].startTimeTicks);
    }

    @Test
    public void missingBeforeSnapshotOrUnreadableAfterSnapshotGivesAllNullEntries() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        r.tasks.put(2, WORKER);
        r.starts.put(2, 100L);
        r.tasks.put(3, LOOPER);
        r.starts.put(3, 300L);
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture(WORKER, LOOPER);
        assertEquals(2, ids.length);
        assertNull(ids[0]);
        assertNull(ids[1]);
        assertFalse(d.isCapturing());

        assertTrue(d.beginCapture());
        d.cancelCapture();
        r.tasks.put(4, LOOPER);
        r.starts.put(4, 400L);
        ids = d.finishCapture(LOOPER);
        assertEquals(1, ids.length);
        assertNull(ids[0]);
        assertFalse(d.isCapturing());

        r.tasks.clear();
        assertTrue(d.beginCapture());
        r.unavailable = true;
        ids = d.finishCapture(WORKER, LOOPER);
        assertEquals(2, ids.length);
        assertNull(ids[0]);
        assertNull(ids[1]);
        assertFalse(d.isCapturing());
        r.unavailable = false;
    }

    @Test
    public void nullOrEmptyNamesReturnEmptyArrayAndClearSnapshot() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        assertEquals(0, d.finishCapture((String[]) null).length);
        assertFalse(d.isCapturing());
        assertTrue(d.beginCapture());
        assertEquals(0, d.finishCapture(new String[0]).length);
        assertFalse(d.isCapturing());
    }

    @Test
    public void revalidationUsesTheIdentityNameForCodecLooper() {
        FakeReader r = new FakeReader();
        r.tasks.put(2, LOOPER);
        r.starts.put(2, 100L);
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        CodecThreadDiscovery.WorkerIdentity id = new CodecThreadDiscovery.WorkerIdentity(2, 100, LOOPER);
        assertTrue(d.isStillValid(id));
        r.tasks.put(2, WORKER);
        assertFalse(d.isStillValid(id));
        r.tasks.put(2, LOOPER);
        r.starts.put(2, 200L);
        assertFalse(d.isStillValid(id));
        r.starts.put(2, 100L);
        assertFalse(d.isStillValid(new CodecThreadDiscovery.WorkerIdentity(2, 100, null)));
    }

    @Test
    public void finishCaptureReadsTheTaskListOnceForAllNames() {
        FakeReader r = new FakeReader();
        CodecThreadDiscovery d = new CodecThreadDiscovery(r);
        assertTrue(d.beginCapture());
        r.taskReads = 0;
        r.tasks.put(2, WORKER);
        r.starts.put(2, 100L);
        r.tasks.put(3, LOOPER);
        r.starts.put(3, 300L);
        CodecThreadDiscovery.WorkerIdentity[] ids = d.finishCapture(WORKER, LOOPER, "Other");
        assertEquals(1, r.taskReads);
        assertNotNull(ids[0]);
        assertNotNull(ids[1]);
        assertNull(ids[2]);
    }

    private static final class FakeReader implements CodecThreadDiscovery.TaskReader {
        final Map<Integer, String> tasks = new HashMap<>();
        final Map<Integer, Long> starts = new HashMap<>();
        boolean unavailable;
        int taskReads;
        int throwForTid = -1;

        @Override
        public Map<Integer, String> readTaskNames() {
            taskReads++;
            // Deliberately return the mutable map to verify beginCapture owns its snapshot.
            return unavailable ? null : tasks;
        }

        @Override
        public long readStartTimeTicks(int tid) {
            if (tid == throwForTid) {
                throw new SecurityException("denied");
            }
            Long start = starts.get(tid);
            return start != null ? start : 0L;
        }
    }
}
