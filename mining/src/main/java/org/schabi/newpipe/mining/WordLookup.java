package org.schabi.newpipe.mining;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * High-level entry point for the mining UI: sentence + selected range → dictionary match and the
 * ready-to-use Anki {@code Front} / {@code Glossary} strings.
 *
 * <p>Lookup strategy for a selection {@code [start, end)}:
 * <ol>
 *   <li>Selection is exactly one token: its base form (始めて→始める is a single token in some
 *       analyses, くれ→くれる), then its surface.</li>
 *   <li>Otherwise: the exact selected text (compounds such as 模擬戦 that Kuromoji splits); then
 *       deinflection — the selection up to its "head" (last token starting inside the selection
 *       that is not a particle/auxiliary/suffix verb/symbol), with the head replaced by its base
 *       form (始め|て → 始める, 取り扱っ|て → 取り扱う, 食べ|させ|られ|た → 食べる).
 *       If the selection is token-aligned and the head is a verb/adjective followed by an
 *       inflectional tail, the deinflected candidate is tried <em>first</em> (so 始め|て gives the
 *       verb 始める, not the adverb 始めて).</li>
 * </ol>
 * The first candidate with any dictionary hit wins. Among hits, an entry whose reading equals the
 * tokenizer's reading of the selection is preferred (homograph disambiguation), else the
 * dictionary's own ranking (common first) is kept.
 *
 * <p>Front: the dictionary headword + JMdict reading when matched (始めて mines as
 * {@code 始[はじ]める}); otherwise the raw selection with Kuromoji's reading if the selection is
 * token-aligned, else the raw selection without furigana.
 *
 * <p>Threading: blocks on {@link Tokenizer} / {@link Dictionary} initialisation and does a few DB
 * queries — call from a background thread.
 */
public final class WordLookup {

    public static final class Result {
        /** Trimmed selected text. */
        public final String selection;
        /** Best match, or null if nothing was found. */
        public final DictEntry entry;
        /** Other hits for the winning candidate (includes {@link #entry}), best first. */
        public final List<DictEntry> alternatives;
        /** The string that was actually found in the dictionary (e.g. 始める), or null. */
        public final String matchedTerm;
        /** Anki furigana syntax for the Front field; never null. */
        public final String front;
        /** HTML for the Glossary field; empty if not found. */
        public final String glossary;

        Result(String selection, DictEntry entry, List<DictEntry> alternatives, String matchedTerm,
               String front, String glossary) {
            this.selection = selection;
            this.entry = entry;
            this.alternatives = alternatives;
            this.matchedTerm = matchedTerm;
            this.front = front;
            this.glossary = glossary;
        }

        public boolean found() {
            return entry != null;
        }

        /** Same lookup but with another of {@link #alternatives} chosen (e.g. from a picker). */
        public Result withEntry(DictEntry e) {
            return new Result(selection, e, alternatives, matchedTerm, e.toFurigana(),
                    Glossary.format(e));
        }

        @Override
        public String toString() {
            return "Result[" + selection + " → " + entry + " front=" + front + " glossary=" + glossary + "]";
        }
    }

    private final DictionarySource dictionary;
    private final Tokenizer tokenizer;

    /** {@code tokenizer} may be null if callers always pass tokens explicitly. */
    public WordLookup(DictionarySource dictionary, Tokenizer tokenizer) {
        this.dictionary = dictionary;
        this.tokenizer = tokenizer;
    }

    /** Tokenizes {@code sentence} and delegates to {@link #lookup(String, List, int, int)}. */
    public Result lookup(String sentence, int start, int end) {
        return lookup(sentence, tokenizer.tokenize(sentence), start, end);
    }

    /**
     * @param tokens tokens of {@code sentence} (as returned by {@link Tokenizer#tokenize}); pass the
     *               list the UI already has to avoid re-tokenizing.
     */
    public Result lookup(String sentence, List<Token> tokens, int start, int end) {
        start = Math.max(0, Math.min(start, sentence.length()));
        end = Math.max(start, Math.min(end, sentence.length()));
        // Trim whitespace from the selection.
        while (start < end && Character.isWhitespace(sentence.charAt(start))) start++;
        while (end > start && Character.isWhitespace(sentence.charAt(end - 1))) end--;
        String selection = sentence.substring(start, end);
        if (selection.isEmpty()) return new Result("", null, new ArrayList<>(), null, "", "");

        List<Token> covered = new ArrayList<>();
        for (Token t : tokens) if (t.start < end && t.end > start) covered.add(t);
        boolean aligned = !covered.isEmpty() && covered.get(0).start == start
                && covered.get(covered.size() - 1).end == end;
        String tokenReading = aligned ? joinReadings(covered) : null;

        Set<String> candidates = new LinkedHashSet<>();
        if (aligned && covered.size() == 1) {
            Token t = covered.get(0);
            candidates.add(t.baseForm);
            candidates.add(t.surface);
        } else {
            // Head = last token starting inside the selection that is not part of an
            // inflectional tail (て, た, ない, させ, られ, いる ...).
            int headIdx = -1;
            for (int i = covered.size() - 1; i >= 0; i--) {
                Token t = covered.get(i);
                if (t.start < start) break;
                if (!isInflectionalTail(t) && !t.isPunctuationOrSymbol()) { headIdx = i; break; }
            }
            String deinflected = null;
            boolean preferDeinflected = false;
            if (headIdx >= 0) {
                Token head = covered.get(headIdx);
                deinflected = sentence.substring(start, head.start) + head.baseForm;
                // Tokenizer says "verb/adjective + inflection": trust it over an exact hit, so
                // 始め|て mines 始める rather than the adverb 始めて ("for the first time").
                preferDeinflected = aligned && headIdx < covered.size() - 1
                        && ("動詞".equals(head.partOfSpeech) || "形容詞".equals(head.partOfSpeech));
            }
            if (preferDeinflected) candidates.add(deinflected);
            candidates.add(selection);
            if (deinflected != null) candidates.add(deinflected);
        }

        for (String c : candidates) {
            if (c == null || c.trim().isEmpty()) continue;
            List<DictEntry> hits = dictionary.lookup(c);
            if (hits.isEmpty()) continue;
            DictEntry best = pickBest(c, hits, c.equals(selection) ? tokenReading : null);
            return new Result(selection, best, hits, c, best.toFurigana(), Glossary.format(best));
        }

        String front = tokenReading != null ? Furigana.format(selection, tokenReading) : selection;
        return new Result(selection, null, new ArrayList<>(), null, front, "");
    }

    /** Max tokens {@link #longestMatch} will try to join. */
    public static final int MAX_JOIN = 6;

    /**
     * Initial selection when the user taps token {@code tokenIndex}: the longest run of tokens
     * starting there (up to {@link #MAX_JOIN}, stopping at symbols/whitespace) that has a dictionary
     * match, like Yomitan's scanning. E.g. tapping 模擬 in 模擬|戦|を returns 模擬戦; tapping 始め in
     * 始め|て|くれ returns 始めてくれ only if that is a dictionary word, else 始めて (→ 始める).
     * Falls back to the tapped token alone. Returns {@code {start, end}}.
     */
    public int[] longestMatch(String sentence, List<Token> tokens, int tokenIndex) {
        Token first = tokens.get(tokenIndex);
        int limit = tokenIndex;
        while (limit + 1 < tokens.size() && limit + 1 - tokenIndex < MAX_JOIN
                && !tokens.get(limit + 1).isPunctuationOrSymbol()) {
            limit++;
        }
        for (int j = limit; j > tokenIndex; j--) {
            Token last = tokens.get(j);
            if (isInflectionalTail(last)) continue; // checked via the deinflected candidate below
            String text = sentence.substring(first.start, last.end);
            if (!dictionary.lookup(text).isEmpty()) {
                // Include any inflectional tail that follows (始め|て → 始めて).
                return new int[]{first.start, extendOverTail(tokens, j, limit)};
            }
        }
        return new int[]{first.start, extendOverTail(tokens, tokenIndex, limit)};
    }

    /** Extends past directly-following conjugation tokens of a verb/adjective (て, た, ない...). */
    private static int extendOverTail(List<Token> tokens, int idx, int limit) {
        Token head = tokens.get(idx);
        if (!"動詞".equals(head.partOfSpeech) && !"形容詞".equals(head.partOfSpeech)) return head.end;
        int end = head.end;
        for (int k = idx + 1; k <= limit; k++) {
            Token t = tokens.get(k);
            String d = t.partOfSpeechDetail == null ? "" : t.partOfSpeechDetail;
            boolean conj = "助動詞".equals(t.partOfSpeech) || d.startsWith("助詞,接続助詞")
                    || d.startsWith("動詞,接尾");
            if (!conj) break;
            end = t.end;
        }
        return end;
    }

    /**
     * Dictionary order (common first) unless: the tokenizer's reading of the selection identifies a
     * homograph; or a kana-only term matches an entry usually written in kana (くれる → 呉れる
     * shown as くれる, rather than 暮れる).
     */
    private static DictEntry pickBest(String term, List<DictEntry> hits, String expectedReading) {
        if (expectedReading != null) {
            for (DictEntry e : hits) if (e.reading.equals(expectedReading)) return e;
        }
        if (Kana.isAllKana(term)) {
            int bestPrio = hits.get(0).priority;
            for (DictEntry e : hits) {
                if (e.priority > bestPrio) break;
                if (e.headword.equals(term)) return e;
            }
        }
        return hits.get(0);
    }

    /** Auxiliaries/particles/suffix verbs that attach to a verb or adjective stem. */
    static boolean isInflectionalTail(Token t) {
        if (t.isFunctionWord()) return true;
        String d = t.partOfSpeechDetail == null ? "" : t.partOfSpeechDetail;
        return d.startsWith("動詞,接尾") || d.startsWith("動詞,非自立") || d.startsWith("形容詞,非自立");
    }

    /** Concatenated hiragana readings, or null if any token lacks one. */
    private static String joinReadings(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (t.reading != null) sb.append(t.reading);
            else if (Kana.isAllKana(t.surface)) sb.append(Kana.toHiragana(t.surface));
            else return null;
        }
        return sb.toString();
    }
}
