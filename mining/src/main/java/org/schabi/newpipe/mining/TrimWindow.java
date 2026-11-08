package org.schabi.newpipe.mining;

import java.util.Locale;

/**
 * The time span the trim slider covers for one caption line (the line ±{@link #PADDING_MS}),
 * and the rules for moving its two handles. Pure logic, no Android dependencies.
 */
public final class TrimWindow {

    public static final long PADDING_MS = 2000;
    /** Handles can't get closer than this. */
    public static final long MIN_LENGTH_MS = 200;
    /** Dragging the end handle plays this much audio leading up to it. */
    public static final long END_PREVIEW_MS = 1500;

    public final long startMs;
    public final long endMs;

    private TrimWindow(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }

    /**
     * Window around {@code cue}: [start − padding, end + padding], clamped to 0 and to
     * {@code mediaDurationMs} when known (pass ≤ 0 if unknown).
     */
    public static TrimWindow around(Cue cue, long paddingMs, long mediaDurationMs) {
        long start = Math.max(0, cue.startMs - paddingMs);
        long end = cue.endMs + paddingMs;
        if (mediaDurationMs > 0) end = Math.min(end, mediaDurationMs);
        if (end < start + MIN_LENGTH_MS) end = start + MIN_LENGTH_MS;
        return new TrimWindow(start, end);
    }

    public long lengthMs() {
        return endMs - startMs;
    }

    /** Initial handle position for the start: the line's start, inside the window. */
    public long defaultStart(Cue cue) {
        return clamp(cue.startMs, startMs, endMs - MIN_LENGTH_MS);
    }

    /** Initial handle position for the end: the line's end, inside the window. */
    public long defaultEnd(Cue cue) {
        return clamp(cue.endMs, defaultStart(cue) + MIN_LENGTH_MS, endMs);
    }

    /** Where the start handle may go given the end handle at {@code endHandleMs}. */
    public long clampStart(long requestedMs, long endHandleMs) {
        long lo = Math.max(startMs, endHandleMs - ClipRange.MAX_DURATION_MS);
        return clamp(requestedMs, lo, endHandleMs - MIN_LENGTH_MS);
    }

    /** Where the end handle may go given the start handle at {@code startHandleMs}. */
    public long clampEnd(long requestedMs, long startHandleMs) {
        long hi = Math.min(endMs, startHandleMs + ClipRange.MAX_DURATION_MS);
        return clamp(requestedMs, startHandleMs + MIN_LENGTH_MS, hi);
    }

    /** Start of the audio played while dragging the end handle (seek-before, play-until). */
    public static long endPreviewStart(long endHandleMs, long startHandleMs) {
        return Math.max(startHandleMs, endHandleMs - END_PREVIEW_MS);
    }

    /** "12.3s – 14.1s", or "1:02.3 – 1:04.1" once past a minute. */
    public static String formatRange(long startMs, long endMs) {
        return formatTime(startMs, endMs >= 60_000) + " – " + formatTime(endMs, endMs >= 60_000);
    }

    static String formatTime(long ms, boolean minutes) {
        long tenths = Math.max(0, ms) / 100;
        if (!minutes) {
            return String.format(Locale.US, "%d.%ds", tenths / 10, tenths % 10);
        }
        long totalSec = tenths / 10;
        return String.format(Locale.US, "%d:%02d.%d", totalSec / 60, totalSec % 60, tenths % 10);
    }

    private static long clamp(long v, long lo, long hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
