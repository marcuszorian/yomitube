package org.schabi.newpipe.mining;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats a word in Anki's furigana bracket syntax for the {@code {{furigana:Front}}} filter.
 *
 * <p>Anki's filter matches {@code " ?([^ >]+?)\[(.+?)\]"}: the base text of a bracket is the run of
 * non-space characters immediately before it, and one optional leading space is swallowed (not
 * rendered). So every kanji run that follows other text must be preceded by a space, otherwise the
 * preceding kana would be included in the ruby base:
 * <ul>
 *   <li>模擬戦 / もぎせん → {@code 模擬戦[もぎせん]}</li>
 *   <li>始める / はじめる → {@code 始[はじ]める}</li>
 *   <li>取り扱う / とりあつかう → {@code 取[と]り 扱[あつか]う}</li>
 *   <li>お茶 / おちゃ → {@code お 茶[ちゃ]}</li>
 *   <li>words with no kanji are returned unchanged</li>
 * </ul>
 * Kana in the expression is matched against the reading after katakana→hiragana normalisation.
 * If the kana cannot be aligned unambiguously, the whole word is bracketed: {@code 為る[する]}.
 */
public final class Furigana {
    private Furigana() {}

    private static final class Segment {
        final String text;
        final String normalized;
        final boolean kanji;

        Segment(String text, boolean kanji) {
            this.text = text;
            this.normalized = Kana.toHiragana(text);
            this.kanji = kanji;
        }
    }

    public static String format(String expression, String readingHiragana) {
        if (expression == null) return "";
        expression = expression.trim();
        if (expression.isEmpty()) return "";
        if (readingHiragana == null || !Kana.containsKanji(expression)) return expression;
        String reading = Kana.toHiragana(sanitizeReading(readingHiragana));
        if (reading.isEmpty()) return expression;

        List<Segment> segs = split(expression);
        String[] assignment = new String[segs.size()];
        int solutions = align(segs, 0, reading, 0, assignment, new String[segs.size()]);
        if (solutions != 1) {
            return sanitizeBase(expression) + "[" + reading + "]";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            if (!s.kanji) {
                sb.append(s.text);
            } else {
                if (assignment[i].equals(s.normalized)) {
                    sb.append(s.text); // e.g. a non-kanji symbol read as itself
                } else {
                    // Anki swallows one space before a base; it is needed so preceding
                    // kana are not pulled into this ruby base.
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(sanitizeBase(s.text)).append('[').append(assignment[i]).append(']');
                }
            }
        }
        return sb.toString();
    }

    /** Splits into maximal kana runs and non-kana ("kanji") runs. */
    private static List<Segment> split(String expr) {
        List<Segment> segs = new ArrayList<>();
        int i = 0;
        while (i < expr.length()) {
            boolean kana = Kana.isKana(expr.charAt(i));
            int j = i + 1;
            while (j < expr.length() && Kana.isKana(expr.charAt(j)) == kana) j++;
            segs.add(new Segment(expr.substring(i, j), !kana));
            i = j;
        }
        return segs;
    }

    /**
     * Counts alignments (stopping at 2) of segments[si..] against reading[ri..]. Kana segments must
     * match literally; kanji segments consume at least one reading character. The first solution
     * found is stored in {@code out}.
     */
    private static int align(List<Segment> segs, int si, String reading, int ri,
                             String[] out, String[] work) {
        if (si == segs.size()) {
            if (ri != reading.length()) return 0;
            System.arraycopy(work, 0, out, 0, work.length);
            return 1;
        }
        Segment s = segs.get(si);
        if (!s.kanji) {
            if (!reading.startsWith(s.normalized, ri)) return 0;
            work[si] = s.normalized;
            return align(segs, si + 1, reading, ri + s.normalized.length(), out, work);
        }
        int count = 0;
        // Reserve at least one char for each following kanji segment and all following kana.
        for (int end = ri + 1; end <= reading.length(); end++) {
            work[si] = reading.substring(ri, end);
            String[] tmpOut = count == 0 ? out : new String[out.length];
            count += align(segs, si + 1, reading, end, tmpOut, work);
            if (count >= 2) return count;
        }
        return count;
    }

    /** Anki's furigana filter pattern: optional space, base (no space/'>'), bracketed reading. */
    private static final java.util.regex.Pattern RUBY =
            java.util.regex.Pattern.compile(" ?([^ >]+?)\\[(.+?)\\]");

    /**
     * What to speak for a Front value: each {@code base[reading]} replaced by its reading, spaces
     * removed. {@code 取[と]り 扱[あつか]う} → {@code とりあつかう}; {@code テレビ} → {@code テレビ}.
     * HTML tags are stripped. Kanji without brackets (user-typed) are left as is.
     */
    public static String toSpeech(String front) {
        if (front == null) return "";
        String s = front.replaceAll("<[^>]*>", "");
        java.util.regex.Matcher m = RUBY.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            sb.append(m.group(2));
            last = m.end();
        }
        sb.append(s.substring(last));
        return sb.toString().replaceAll("[\\s\\u3000]+", "");
    }

    private static String sanitizeReading(String r) {
        return r.replaceAll("[\\s\\[\\]]", "");
    }

    /** Spaces/brackets in the base would break Anki's parser. */
    private static String sanitizeBase(String b) {
        return b.replaceAll("[\\s\\[\\]]", "");
    }
}
