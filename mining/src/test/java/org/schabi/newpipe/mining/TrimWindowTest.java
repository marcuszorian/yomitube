package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrimWindowTest {

    private final Cue cue = new Cue(10_000, 12_500, "x");

    @Test
    public void paddedAroundLine() {
        TrimWindow w = TrimWindow.around(cue, 2000, -1);
        assertEquals(8_000, w.startMs);
        assertEquals(14_500, w.endMs);
        assertEquals(10_000, w.defaultStart(cue));
        assertEquals(12_500, w.defaultEnd(cue));
    }

    @Test
    public void clampedToZeroAndDuration() {
        TrimWindow w = TrimWindow.around(new Cue(500, 1500, "x"), 2000, 2500);
        assertEquals(0, w.startMs);
        assertEquals(2500, w.endMs);
    }

    @Test
    public void lineEndingAfterDurationStillValid() {
        Cue late = new Cue(9_000, 12_000, "x");
        TrimWindow w = TrimWindow.around(late, 2000, 10_000);
        assertEquals(10_000, w.endMs);
        assertEquals(9_000, w.defaultStart(late));
        assertEquals(10_000, w.defaultEnd(late));
    }

    @Test
    public void handlesKeepMinimumGap() {
        TrimWindow w = TrimWindow.around(cue, 2000, -1);
        assertEquals(12_000 - TrimWindow.MIN_LENGTH_MS, w.clampStart(12_000, 12_000));
        assertEquals(10_000 + TrimWindow.MIN_LENGTH_MS, w.clampEnd(9_000, 10_000));
    }

    @Test
    public void handlesStayInsideWindow() {
        TrimWindow w = TrimWindow.around(cue, 2000, -1);
        assertEquals(8_000, w.clampStart(0, 12_000));
        assertEquals(14_500, w.clampEnd(99_000, 10_000));
    }

    @Test
    public void endPreviewPlaysLeadUp() {
        assertEquals(11_000, TrimWindow.endPreviewStart(12_500, 10_000));
        // Never earlier than the start handle.
        assertEquals(12_000, TrimWindow.endPreviewStart(12_500, 12_000));
    }

    @Test
    public void formatsSecondsAndMinutes() {
        assertEquals("12.3s – 14.1s", TrimWindow.formatRange(12_345, 14_100));
        assertEquals("0:59.5 – 1:02.0", TrimWindow.formatRange(59_500, 62_000));
    }
}
