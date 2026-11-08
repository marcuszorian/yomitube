package org.schabi.newpipe.mining;

public final class Cue {
    public final long startMs;
    public final long endMs;
    public final String text;

    public Cue(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }

    @Override
    public String toString() {
        return "Cue[" + startMs + "-" + endMs + " " + text + "]";
    }
}
