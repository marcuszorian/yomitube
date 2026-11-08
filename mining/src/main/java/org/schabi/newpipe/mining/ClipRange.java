package org.schabi.newpipe.mining;

/** Validated time range for an audio clip. Pure logic, no Android dependencies. */
public final class ClipRange {
    /** Longest clip we will extract; sentences are much shorter, this guards against UI bugs. */
    public static final long MAX_DURATION_MS = 60_000;

    public final long startMs;
    public final long endMs;

    private ClipRange(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public long durationMs() {
        return endMs - startMs;
    }

    /**
     * Negative start is clamped to 0. Throws {@link IllegalArgumentException} with a
     * human-readable message if the end is not after the start or the clip exceeds
     * {@link #MAX_DURATION_MS}.
     */
    public static ClipRange of(long startMs, long endMs) {
        long start = Math.max(0, startMs);
        if (endMs <= start) {
            throw new IllegalArgumentException("Clip end (" + endMs + " ms) must be after start (" + start + " ms)");
        }
        long duration = endMs - start;
        if (duration > MAX_DURATION_MS) {
            throw new IllegalArgumentException("Clip is too long (" + (duration / 1000) + " s, max "
                    + (MAX_DURATION_MS / 1000) + " s)");
        }
        return new ClipRange(start, endMs);
    }
}
