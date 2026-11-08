package org.schabi.newpipe.mining;

import java.util.List;

/** Character-range selection rules for the word chips. Pure logic, no Android dependencies. */
public final class TextSelection {

    private TextSelection() {
    }

    /**
     * Moves the selection's start by {@code delta} characters (negative = extend left), keeping at
     * least one character selected. Returns {@code {start, end}}.
     */
    public static int[] moveStart(int start, int end, int delta, int length) {
        int s = Math.max(0, Math.min(start + delta, end - 1));
        return new int[]{s, end};
    }

    /**
     * Moves the selection's end by {@code delta} characters (positive = extend right), keeping at
     * least one character selected. Returns {@code {start, end}}.
     */
    public static int[] moveEnd(int start, int end, int delta, int length) {
        int e = Math.min(length, Math.max(end + delta, start + 1));
        return new int[]{start, e};
    }

    /** Whether a token can be tapped as a mining target (not punctuation, [音楽]-style notes or spaces). */
    public static boolean isSelectable(Token t) {
        return !t.isPunctuationOrSymbol();
    }

    /** Index of the token containing char offset {@code offset}, or -1. */
    public static int tokenAt(List<Token> tokens, int offset) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.start <= offset && offset < t.end) return i;
        }
        return -1;
    }
}
