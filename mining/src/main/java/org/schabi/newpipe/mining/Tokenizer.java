package org.schabi.newpipe.mining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Japanese tokenizer backed by Kuromoji (IPADIC).
 *
 * <h3>Threading / initialisation</h3>
 * Building Kuromoji loads its ~50 MB in-memory dictionary and takes roughly a second (longer on
 * slow phones). The instance is a lazily-built, thread-safe singleton: call {@link #warmUp()} from a
 * background thread early (e.g. when the player opens) so the first {@link #tokenize} from the UI
 * doesn't block. {@link #get()} and {@link #tokenize} block until initialisation has finished; do
 * not call them on the main thread unless {@link #isReady()} is true. Kuromoji's tokenizer is
 * thread-safe once built.
 *
 * <h3>Input</h3>
 * The sentence is tokenized as-is (no normalisation) so offsets always index into the caller's
 * string. Whitespace, ASCII and punctuation become {@code 記号} tokens. Bracketed caption
 * annotations such as {@code [音楽]} / {@code ［拍手］} / {@code （笑）} are emitted as a single
 * symbol token (POS {@code 記号}, detail {@code 記号,注釈}) so the UI can skip them.
 */
public final class Tokenizer {

    private static volatile Tokenizer instance;

    /** Caption annotations: [音楽], ［拍手］, (笑), （笑い）, 【…】. */
    private static final Pattern ANNOTATION =
            Pattern.compile("\\[[^\\[\\]]*\\]|［[^［］]*］|【[^【】]*】|[(（][^()（）]*[)）]");

    private final com.atilika.kuromoji.ipadic.Tokenizer kuromoji;

    private Tokenizer() {
        kuromoji = new com.atilika.kuromoji.ipadic.Tokenizer();
    }

    /** Returns the singleton, building it (slow, ~1 s) on first call. Blocks; avoid on UI thread. */
    public static Tokenizer get() {
        Tokenizer t = instance;
        if (t == null) {
            synchronized (Tokenizer.class) {
                t = instance;
                if (t == null) {
                    t = new Tokenizer();
                    instance = t;
                }
            }
        }
        return t;
    }

    /** Builds the singleton if needed. Intended to be called on a background thread. */
    public static void warmUp() {
        get();
    }

    /** True once the dictionary is loaded and {@link #get()} returns immediately. */
    public static boolean isReady() {
        return instance != null;
    }

    /** Tokenizes {@code sentence}; tokens cover the string contiguously and in order. */
    public List<Token> tokenize(String sentence) {
        if (sentence == null || sentence.isEmpty()) return Collections.emptyList();
        List<Token> out = new ArrayList<>();
        Matcher m = ANNOTATION.matcher(sentence);
        int pos = 0;
        while (m.find()) {
            // Only treat parenthesised text as an annotation if it is short (likely 笑, 拍手, 音楽).
            if (sentence.charAt(m.start()) != '[' && m.end() - m.start() > 8) continue;
            tokenizeRange(sentence, pos, m.start(), out);
            String a = m.group();
            out.add(new Token(a, m.start(), m.end(), a, null, "記号", "記号,注釈"));
            pos = m.end();
        }
        tokenizeRange(sentence, pos, sentence.length(), out);
        return out;
    }

    private void tokenizeRange(String sentence, int from, int to, List<Token> out) {
        if (from >= to) return;
        String part = sentence.substring(from, to);
        for (com.atilika.kuromoji.ipadic.Token k : kuromoji.tokenize(part)) {
            String surface = k.getSurface();
            int start = from + k.getPosition();
            String base = clean(k.getBaseForm());
            String reading = clean(k.getReading());
            String pos1 = clean(k.getPartOfSpeechLevel1());
            StringBuilder detail = new StringBuilder(pos1 == null ? "" : pos1);
            for (String p : new String[]{k.getPartOfSpeechLevel2(), k.getPartOfSpeechLevel3(),
                    k.getPartOfSpeechLevel4()}) {
                if (clean(p) != null) detail.append(',').append(p);
            }
            out.add(new Token(surface, start, start + surface.length(),
                    base != null ? base : surface,
                    reading != null ? Kana.toHiragana(reading) : null,
                    pos1, detail.toString()));
        }
    }

    private static String clean(String s) {
        return s == null || s.isEmpty() || "*".equals(s) ? null : s;
    }
}
