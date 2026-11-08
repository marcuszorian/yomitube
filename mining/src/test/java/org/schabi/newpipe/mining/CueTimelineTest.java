package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CueTimelineTest {

    private final List<Cue> cues = Arrays.asList(
            new Cue(1000, 2000, "a"),
            new Cue(3000, 4000, "b"),
            new Cue(5000, 6000, "c"));

    @Test
    public void emptyListGivesMinusOne() {
        assertEquals(-1, CueTimeline.indexAt(Collections.emptyList(), 1000));
        assertEquals(-1, CueTimeline.indexAt(null, 1000));
    }

    @Test
    public void lineShowingAtPosition() {
        assertEquals(0, CueTimeline.indexAt(cues, 1000));
        assertEquals(1, CueTimeline.indexAt(cues, 3500));
        assertEquals(2, CueTimeline.indexAt(cues, 5999));
    }

    @Test
    public void betweenLinesGivesMostRecent() {
        assertEquals(0, CueTimeline.indexAt(cues, 2500));
        assertEquals(1, CueTimeline.indexAt(cues, 4999));
    }

    @Test
    public void beforeFirstLineGivesFirst() {
        assertEquals(0, CueTimeline.indexAt(cues, 0));
    }

    @Test
    public void afterLastLineGivesLast() {
        assertEquals(2, CueTimeline.indexAt(cues, 999_999));
    }
}
