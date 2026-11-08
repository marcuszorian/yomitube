package org.schabi.newpipe.mining;

/** One morpheme of a tokenized sentence. Immutable. */
public final class Token {
    /** Text exactly as it appears in the sentence: {@code sentence.substring(start, end)}. */
    public final String surface;
    /** Char offset (inclusive) into the original sentence. */
    public final int start;
    /** Char offset (exclusive) into the original sentence. */
    public final int end;
    /** Dictionary form (始めて → 始める). Equals {@link #surface} if unknown. */
    public final String baseForm;
    /** Reading of the surface in hiragana; null if unknown (e.g. ASCII / unknown words). */
    public final String reading;
    /** IPADIC top-level part of speech in Japanese, e.g. 名詞, 動詞, 助詞, 記号. */
    public final String partOfSpeech;
    /** Full IPADIC POS, comma-joined levels without "*", e.g. 名詞,サ変接続. */
    public final String partOfSpeechDetail;

    public Token(String surface, int start, int end, String baseForm, String reading,
                 String partOfSpeech, String partOfSpeechDetail) {
        this.surface = surface;
        this.start = start;
        this.end = end;
        this.baseForm = baseForm;
        this.reading = reading;
        this.partOfSpeech = partOfSpeech;
        this.partOfSpeechDetail = partOfSpeechDetail;
    }

    /** True for punctuation, symbols and whitespace — not worth offering as a mining target. */
    public boolean isPunctuationOrSymbol() {
        return "記号".equals(partOfSpeech) || surface.trim().isEmpty();
    }

    /** True for particles/auxiliaries (助詞, 助動詞) — usually not what the user wants to mine. */
    public boolean isFunctionWord() {
        return "助詞".equals(partOfSpeech) || "助動詞".equals(partOfSpeech);
    }

    @Override
    public String toString() {
        return surface + "[" + start + "," + end + ") base=" + baseForm + " reading=" + reading
                + " pos=" + partOfSpeechDetail;
    }
}
