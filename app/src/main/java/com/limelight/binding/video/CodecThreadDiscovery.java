package com.limelight.binding.video;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Before/after discovery ported from artemis-tcl-av1. Only this app's /proc/self/task is read.
 * For each requested thread name (MediaCodec_loop, CodecLooper), exactly one new thread with that
 * name and a readable positive kernel start-time token is accepted; none, ambiguous or denied reads
 * fall back to input-only hints for that name. One task-list read serves all names. No per-frame scans.
 */
final class CodecThreadDiscovery {
    static final String WORKER_THREAD_NAME = "MediaCodec_loop";
    static final String CODEC_LOOPER_THREAD_NAME = "CodecLooper";

    interface TaskReader {
        /** Keep enumerated TIDs even when their names cannot be read (null value). */
        Map<Integer, String> readTaskNames();
        long readStartTimeTicks(int tid);
    }

    static final class WorkerIdentity {
        final int tid;
        final long startTimeTicks;
        final String name;

        WorkerIdentity(int tid, long startTimeTicks) {
            this(tid, startTimeTicks, WORKER_THREAD_NAME);
        }

        WorkerIdentity(int tid, long startTimeTicks, String name) {
            this.tid = tid;
            this.startTimeTicks = startTimeTicks;
            this.name = name;
        }
    }

    private final TaskReader reader;
    private Map<Integer, String> beforeSnapshot;

    CodecThreadDiscovery(TaskReader reader) {
        this.reader = reader;
    }

    static TaskReader procReader() {
        return new ProcTaskReader();
    }

    boolean beginCapture() {
        try {
            Map<Integer, String> names = reader.readTaskNames();
            beforeSnapshot = names != null ? new HashMap<>(names) : null;
        } catch (Throwable t) {
            beforeSnapshot = null;
        }
        return beforeSnapshot != null;
    }

    boolean isCapturing() {
        return beforeSnapshot != null;
    }

    void cancelCapture() {
        beforeSnapshot = null;
    }

    WorkerIdentity finishCapture() {
        return finishCapture(WORKER_THREAD_NAME)[0];
    }

    /**
     * Ends the capture and returns one entry per requested name, in the same order.
     * An entry is non-null only when exactly one NEW thread (absent from the before-snapshot)
     * has that exact name, its tid is positive, and its start-time token is positive.
     * None, ambiguous or unverifiable -> that entry is null; other names are unaffected.
     * Never returns null and never throws: without a usable before-snapshot, with an unreadable
     * after-snapshot, or on any reader exception, the affected entries are null.
     * Reads the task list once for all names.
     */
    WorkerIdentity[] finishCapture(String... names) {
        Map<Integer, String> before = beforeSnapshot;
        beforeSnapshot = null;
        if (names == null) {
            return new WorkerIdentity[0];
        }
        WorkerIdentity[] out = new WorkerIdentity[names.length];
        if (before == null) {
            return out;
        }
        Map<Integer, String> after;
        try {
            after = reader.readTaskNames();
        } catch (Throwable t) {
            return out;
        }
        if (after == null) {
            return out;
        }
        for (int i = 0; i < names.length; i++) {
            out[i] = findNewThread(before, after, names[i]);
        }
        return out;
    }

    private WorkerIdentity findNewThread(Map<Integer, String> before, Map<Integer, String> after, String name) {
        if (name == null) {
            return null;
        }
        try {
            int candidate = 0;
            for (Map.Entry<Integer, String> entry : after.entrySet()) {
                if (!before.containsKey(entry.getKey()) && name.equals(entry.getValue())) {
                    if (candidate != 0 || entry.getKey() <= 0) {
                        return null;
                    }
                    candidate = entry.getKey();
                }
            }
            if (candidate == 0) {
                return null;
            }
            long start = reader.readStartTimeTicks(candidate);
            return start > 0 ? new WorkerIdentity(candidate, start, name) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    boolean isStillValid(WorkerIdentity identity) {
        if (identity == null || identity.name == null || identity.tid <= 0 || identity.startTimeTicks <= 0) {
            return false;
        }
        try {
            Map<Integer, String> now = reader.readTaskNames();
            return now != null && identity.name.equals(now.get(identity.tid))
                    && reader.readStartTimeTicks(identity.tid) == identity.startTimeTicks;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class ProcTaskReader implements TaskReader {
        @Override
        public Map<Integer, String> readTaskNames() {
            try {
                String[] entries = new File("/proc/self/task").list();
                if (entries == null) {
                    return null;
                }
                Map<Integer, String> out = new HashMap<>(entries.length * 2);
                for (String entry : entries) {
                    int tid;
                    try {
                        tid = Integer.parseInt(entry);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    String comm = readFirstLine("/proc/self/task/" + tid + "/comm");
                    out.put(tid, comm != null ? comm.trim() : null);
                }
                return out;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public long readStartTimeTicks(int tid) {
            String stat = readFirstLine("/proc/self/task/" + tid + "/stat");
            if (stat == null) {
                return 0;
            }
            // comm may contain spaces or ')': field 3 follows the last ')', starttime is field 22.
            int close = stat.lastIndexOf(')');
            if (close < 0 || close + 2 > stat.length()) {
                return 0;
            }
            String[] parts = stat.substring(close + 2).trim().split("\\s+");
            if (parts.length <= 19) {
                return 0;
            }
            try {
                return Long.parseLong(parts[19]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String readFirstLine(String path) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                return reader.readLine();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
