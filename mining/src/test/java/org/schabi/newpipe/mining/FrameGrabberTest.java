package org.schabi.newpipe.mining;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class FrameGrabberTest {

    @Test
    public void downscalesToMaxWidthKeepingAspect() {
        assertArrayEquals(new int[]{640, 360}, FrameGrabber.scaledSize(1920, 1080, 640));
        assertArrayEquals(new int[]{640, 1138}, FrameGrabber.scaledSize(1080, 1920, 640));
    }

    @Test
    public void neverUpscales() {
        assertArrayEquals(new int[]{426, 240}, FrameGrabber.scaledSize(426, 240, 640));
    }

    @Test
    public void invalidSizeIsZero() {
        assertArrayEquals(new int[]{0, 0}, FrameGrabber.scaledSize(0, 100, 640));
    }
}
