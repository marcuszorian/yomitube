package org.schabi.newpipe.mining;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SubtitleHitAreaTest {

    // 1000x500 view, 40px text, text bottom 50px above the view bottom → text block y ∈ [396, 450].
    private boolean hit(float x, float y, String text) {
        return SubtitleHitArea.contains(x, y, 1000, 500, 40, 50, text, 10);
    }

    @Test
    public void onTheText() {
        // 5 wide chars = 200px centred → x ∈ [400, 600]
        assertTrue(hit(500, 430, "模擬戦です"));
        assertTrue(hit(405, 400, "模擬戦です"));
    }

    @Test
    public void slackAroundText() {
        assertTrue(hit(392, 430, "模擬戦です"));
        assertTrue(hit(500, 458, "模擬戦です"));
    }

    @Test
    public void elsewhereIsNotHit() {
        assertFalse(hit(500, 200, "模擬戦です"));  // above
        assertFalse(hit(100, 430, "模擬戦です"));  // left of the text
        assertFalse(hit(500, 490, "模擬戦です"));  // below
    }

    @Test
    public void noTextNoHit() {
        assertFalse(hit(500, 430, ""));
        assertFalse(hit(500, 430, null));
    }

    @Test
    public void longLinesWrapUpwards() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append('あ'); // 1600px → 2 lines at 900px max width
        assertTrue(hit(500, 350, sb.toString()));
        assertFalse(hit(500, 350, "短い"));
    }
}
