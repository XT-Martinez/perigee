package com.limelight.binding.video;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.os.Build;

import java.nio.ByteBuffer;

/** Payload-sized AV1 input blocks; no buffer is retained across input calls or codec recovery. */
final class BlockCodecInput {
    static final int MAX_BLOCK_BYTES = 16 * 1024 * 1024;

    interface Block extends AutoCloseable {
        ByteBuffer map();
        void queue(MediaCodec codec, int index, int size, long ptsUs, int flags);
        @Override void close();
    }

    interface Factory {
        Block obtain(int capacity);
    }

    private final Factory factory;

    BlockCodecInput(Factory factory) {
        this.factory = factory;
    }

    static boolean shouldEnable(boolean experiment, boolean adpfEnabled, int sdk, boolean av1,
                                String decoder, String device, String product, String model,
                                boolean async, boolean balanced, boolean upscaling, int width, int height) {
        return experiment && async && balanced && !upscaling && width > 0 && width <= 1920
                && height > 0 && height <= 1080
                && AdpfSubmissionSession.shouldEnable(adpfEnabled, sdk, av1, decoder, device, product, model);
    }

    static BlockCodecInput platform(String decoder) {
        return new BlockCodecInput(new Api30Factory(decoder));
    }

    void queue(MediaCodec codec, int index, byte[] data, int length, long ptsUs, int flags) {
        if (data == null || length > data.length || index < 0) {
            throw new IllegalArgumentException("Invalid block input payload/index");
        }
        int capacity = alignedCapacity(length);
        try (Block block = factory.obtain(capacity)) {
            if (block == null) throw new IllegalStateException("LinearBlock.obtain() returned null");
            ByteBuffer mapped = block.map();
            if (mapped == null || mapped.capacity() < length) {
                throw new IllegalStateException("LinearBlock mapping too small or unavailable");
            }
            mapped.clear();
            mapped.put(data, 0, length);
            block.queue(codec, index, length, ptsUs, flags);
            // recycle() marks the caller's reference disposable; the framework retains queued
            // memory until it is no longer in use. Never reuse a queued mapping or load an
            // OutputFrame: Surface callbacks already provide BufferInfo in block mode.
        }
    }

    static int alignedCapacity(int length) {
        if (length <= 0 || length > MAX_BLOCK_BYTES) {
            throw new IllegalArgumentException("Invalid block input length: " + length);
        }
        return (length + 4095) & ~4095;
    }

    @TargetApi(Build.VERSION_CODES.R)
    private static final class Api30Factory implements Factory {
        private final String[] codecs;

        Api30Factory(String decoder) {
            codecs = new String[] { decoder };
        }

        @Override
        public Block obtain(int capacity) {
            final MediaCodec.LinearBlock block = MediaCodec.LinearBlock.obtain(capacity, codecs);
            return new Block() {
                @Override public ByteBuffer map() { return block.map(); }
                @Override public void queue(MediaCodec codec, int index, int size, long ptsUs, int flags) {
                    codec.getQueueRequest(index).setLinearBlock(block, 0, size)
                            .setPresentationTimeUs(ptsUs).setFlags(flags).queue();
                }
                @Override public void close() { block.recycle(); }
            };
        }
    }
}
