package org.schabi.newpipe.mining;

/** The few player operations the mining panel needs. All calls on the main thread. */
public interface PlaybackControl {
    long getPosition();

    /** Media duration in ms, or ≤ 0 if unknown. */
    long getDuration();

    boolean isPlaying();

    /** Frame-accurate seek. */
    void seekTo(long positionMs);

    void play();

    void pause();

    /** Captures the frame on screen (video only) as a JPEG; callback on the main thread. */
    void grabFrame(FrameGrabber.Callback cb);
}
