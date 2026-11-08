package org.schabi.newpipe.mining;

import java.util.List;

/** Finds caption lines by playback position. Pure logic, no Android dependencies. */
public final class CueTimeline {

    private CueTimeline() {
    }

    /**
     * Index of the line to mine at {@code positionMs}: the cue showing at that time; if between
     * lines, the most recent one that already started; if before the first line, the first one.
     * Returns -1 if {@code cues} is empty. {@code cues} must be sorted by start time.
     */
    public static int indexAt(List<Cue> cues, long positionMs) {
        if (cues == null || cues.isEmpty()) return -1;
        // Binary search for the last cue with start <= position.
        int lo = 0;
        int hi = cues.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) return 0;
        // With overlapping cues an earlier one may still be showing while a later one started;
        // the later one (found) is what the player shows last, so keep it.
        return found;
    }
}
