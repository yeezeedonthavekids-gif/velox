package dev.velox.core.bench;

import java.util.Arrays;

/**
 * Records per-frame durations into a preallocated primitive array.
 *
 * <p>{@link #record(long)} is called once per frame on the render thread, so it must never
 * allocate: an allocating recorder would add the very GC pressure it is trying to measure.
 * When the buffer is full, further frames are counted as dropped rather than resizing.
 */
public final class FrameTimeRecorder {
    private final long[] frameNanos;
    private int count;
    private long dropped;

    public FrameTimeRecorder(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.frameNanos = new long[capacity];
    }

    public void record(long nanos) {
        if (count < frameNanos.length) {
            frameNanos[count++] = nanos;
        } else {
            dropped++;
        }
    }

    public void reset() {
        count = 0;
        dropped = 0;
    }

    public int count() {
        return count;
    }

    public long dropped() {
        return dropped;
    }

    public int capacity() {
        return frameNanos.length;
    }

    /** Copy of the recorded frames; allocates, so call it only when a run ends. */
    public long[] snapshot() {
        return Arrays.copyOf(frameNanos, count);
    }
}
