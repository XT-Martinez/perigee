package com.limelight.binding.video;

import android.media.MediaCodec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class BlockCodecInputTest {
    private static boolean scope(boolean experiment, boolean adpf, int sdk, boolean av1,
                                 String name, String device, boolean async, boolean balanced,
                                 boolean upscale, int width, int height) {
        return BlockCodecInput.shouldEnable(experiment, adpf, sdk, av1, name, device, null, null,
                async, balanced, upscale, width, height);
    }

    @Test
    public void scopeRequiresTheEntireExperimentContract() {
        assertTrue(scope(true, true, 31, true, "c2.google.av1.decoder", "tegu", true, true, false, 1920, 1080));
        assertFalse(scope(false, true, 31, true, "c2.google.av1.decoder", "tegu", true, true, false, 1920, 1080));
        assertFalse(scope(true, false, 31, true, "c2.google.av1.decoder", "tegu", true, true, false, 1920, 1080));
        assertFalse(scope(true, true, 30, true, "c2.google.av1.decoder", "tegu", true, true, false, 1920, 1080));
        assertFalse(scope(true, true, 31, false, "c2.google.av1.decoder", "tegu", true, true, false, 1920, 1080));
        assertFalse(scope(true, true, 31, true, "c2.android.av1.decoder", "tegu", true, true, false, 1920, 1080));
        assertFalse(scope(true, true, 31, true, "c2.google.av1.decoder", "other", true, true, false, 1920, 1080));
        assertFalse(scope(true, true, 31, true, "c2.google.av1.decoder", "tegu", false, true, false, 1920, 1080));
        assertFalse(scope(true, true, 31, true, "c2.google.av1.decoder", "tegu", true, false, false, 1920, 1080));
        assertFalse(scope(true, true, 31, true, "c2.google.av1.decoder", "tegu", true, true, true, 1920, 1080));
        assertFalse(scope(true, true, 31, true, "c2.google.av1.decoder", "tegu", true, true, false, 3840, 2160));
        assertFalse(scope(true, true, 31, true, "c2.google.av1.decoder", "tegu", true, true, false, 0, 1080));
    }

    @Test
    public void capacityIsPayloadSizedPageAlignedAndBounded() {
        assertEquals(4096, BlockCodecInput.alignedCapacity(1));
        assertEquals(4096, BlockCodecInput.alignedCapacity(4096));
        assertEquals(8192, BlockCodecInput.alignedCapacity(4097));
        assertEquals(BlockCodecInput.MAX_BLOCK_BYTES, BlockCodecInput.alignedCapacity(BlockCodecInput.MAX_BLOCK_BYTES));
        for (int invalid : new int[] { 0, -1, Integer.MAX_VALUE, BlockCodecInput.MAX_BLOCK_BYTES + 1 }) {
            assertThrows(IllegalArgumentException.class, () -> BlockCodecInput.alignedCapacity(invalid));
        }
    }

    @Test
    public void copiesOnlyPayloadAndPreservesIndexPtsFlagsThenRecycles() {
        FakeFactory factory = new FakeFactory();
        new BlockCodecInput(factory).queue(null, 7, new byte[] { 1, 2, 3, 99 }, 3, 123456L, 1);
        assertEquals(4096, factory.capacity);
        assertArrayEquals(new byte[] { 1, 2, 3 }, factory.block.queuedBytes);
        assertEquals(7, factory.block.index);
        assertEquals(123456L, factory.block.pts);
        assertEquals(1, factory.block.flags);
        assertEquals(Arrays.asList("obtain", "map", "queue", "close"), factory.events);
    }

    @Test
    public void queueFailureRecyclesExactlyOnceAndPropagates() {
        FakeFactory factory = new FakeFactory();
        factory.block.queueError = new IllegalStateException("queue rejected");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BlockCodecInput(factory).queue(null, 0, new byte[10], 10, 42, 0));
        assertSame(factory.block.queueError, error);
        assertEquals(Arrays.asList("obtain", "map", "queue", "close"), factory.events);
    }

    @Test
    public void mappingFailureRecyclesWithoutQueuing() {
        FakeFactory factory = new FakeFactory();
        factory.block.mapError = new IllegalStateException("map rejected");
        assertThrows(IllegalStateException.class,
                () -> new BlockCodecInput(factory).queue(null, 0, new byte[10], 10, 42, 0));
        assertEquals(Arrays.asList("obtain", "map", "close"), factory.events);
    }

    @Test
    public void smallMappingRecyclesWithoutQueuing() {
        FakeFactory factory = new FakeFactory();
        factory.block.buffer = ByteBuffer.allocate(2);
        assertThrows(IllegalStateException.class,
                () -> new BlockCodecInput(factory).queue(null, 0, new byte[10], 10, 42, 0));
        assertEquals(Arrays.asList("obtain", "map", "close"), factory.events);
    }

    @Test
    public void nullMappingRecyclesWithoutQueuing() {
        FakeFactory factory = new FakeFactory();
        factory.block.buffer = null;
        assertThrows(IllegalStateException.class,
                () -> new BlockCodecInput(factory).queue(null, 0, new byte[10], 10, 42, 0));
        assertEquals(Arrays.asList("obtain", "map", "close"), factory.events);
    }

    @Test
    public void recycleFailureDoesNotHideOriginalQueueFailure() {
        FakeFactory factory = new FakeFactory();
        factory.block.queueError = new IllegalStateException("queue rejected");
        factory.block.closeError = new IllegalStateException("recycle rejected");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BlockCodecInput(factory).queue(null, 0, new byte[10], 10, 42, 0));
        assertSame(factory.block.queueError, error);
        assertArrayEquals(new Throwable[] { factory.block.closeError }, error.getSuppressed());
    }

    @Test
    public void invalidPayloadOrIndexNeverAllocates() {
        FakeFactory factory = new FakeFactory();
        BlockCodecInput input = new BlockCodecInput(factory);
        assertThrows(IllegalArgumentException.class, () -> input.queue(null, 0, null, 10, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> input.queue(null, 0, new byte[1], 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> input.queue(null, -1, new byte[1], 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> input.queue(null, 0, new byte[1], 0, 0, 0));
        assertTrue(factory.events.isEmpty());
    }

    @Test
    public void multiMegabyteFrameIsNotLimitedToTheNormalSmallPayloadSize() {
        FakeFactory factory = new FakeFactory();
        byte[] payload = new byte[2 * 1024 * 1024 + 13];
        payload[payload.length - 1] = 42;
        factory.block.buffer = ByteBuffer.allocate(BlockCodecInput.alignedCapacity(payload.length));
        new BlockCodecInput(factory).queue(null, 0, payload, payload.length, 42, 1);
        assertEquals(2 * 1024 * 1024 + 4096, factory.capacity);
        assertArrayEquals(payload, factory.block.queuedBytes);
        assertEquals(Arrays.asList("obtain", "map", "queue", "close"), factory.events);
    }

    @Test
    public void repeatedQueuesUseFreshBlocksAndHaveNoRetainedMapping() {
        List<FakeFactory> factories = Arrays.asList(new FakeFactory(), new FakeFactory());
        int[] next = { 0 };
        BlockCodecInput input = new BlockCodecInput(cap -> factories.get(next[0]++).obtain(cap));
        input.queue(null, 0, new byte[10], 10, 11, 0);
        input.queue(null, 1, new byte[20], 20, 12, 0);
        for (FakeFactory factory : factories) {
            assertEquals(Arrays.asList("obtain", "map", "queue", "close"), factory.events);
        }
    }

    private static final class FakeFactory implements BlockCodecInput.Factory {
        final List<String> events = new ArrayList<>();
        final FakeBlock block = new FakeBlock(events);
        int capacity;
        @Override public BlockCodecInput.Block obtain(int capacity) {
            this.capacity = capacity;
            events.add("obtain");
            return block;
        }
    }

    private static final class FakeBlock implements BlockCodecInput.Block {
        final List<String> events;
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        RuntimeException mapError, queueError, closeError;
        byte[] queuedBytes;
        int index, flags;
        long pts;
        FakeBlock(List<String> events) { this.events = events; }
        @Override public ByteBuffer map() {
            events.add("map");
            if (mapError != null) throw mapError;
            return buffer;
        }
        @Override public void queue(MediaCodec codec, int index, int size, long ptsUs, int flags) {
            events.add("queue");
            if (queueError != null) throw queueError;
            this.index = index;
            this.pts = ptsUs;
            this.flags = flags;
            queuedBytes = new byte[size];
            ByteBuffer copy = buffer.duplicate();
            copy.flip();
            copy.get(queuedBytes);
        }
        @Override public void close() {
            events.add("close");
            if (closeError != null) throw closeError;
        }
    }
}
