package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClipRangeTest {

    @Test
    public void validRange() {
        ClipRange r = ClipRange.of(1000, 3500);
        assertEquals(1000, r.startMs);
        assertEquals(3500, r.endMs);
        assertEquals(2500, r.durationMs());
    }

    @Test
    public void negativeStartClampedToZero() {
        ClipRange r = ClipRange.of(-400, 2000);
        assertEquals(0, r.startMs);
        assertEquals(2000, r.durationMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void endEqualToStartRejected() {
        ClipRange.of(1000, 1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void endBeforeStartRejected() {
        ClipRange.of(2000, 1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void endNotAfterClampedStartRejected() {
        ClipRange.of(-500, 0);
    }

    @Test
    public void maxLengthAllowed() {
        assertEquals(ClipRange.MAX_DURATION_MS, ClipRange.of(5000, 5000 + ClipRange.MAX_DURATION_MS).durationMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void tooLongRejected() {
        ClipRange.of(0, ClipRange.MAX_DURATION_MS + 1);
    }
}
