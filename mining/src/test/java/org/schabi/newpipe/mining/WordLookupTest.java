package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class WordLookupTest {

    /** In-memory stand-in for the JMdict DB; records every queried term. */
    static class FakeDictionary implements DictionarySource {
        final Map<String, List<DictEntry>> map = new HashMap<>();
        final List<String> queries = new ArrayList<>();

        void add(long id, String headword, String kana, int prio, String pos, String... glosses) {
            DictEntry e = new DictEntry(id, headword, kana, prio, Collections.singletonList(
                    new DictEntry.Sense(Arrays.asList(pos.split(",")), Arrays.asList(glosses))));
            for (String key : new String[]{headword, kana}) {
                List<DictEntry> l = map.computeIfAbsent(key, k -> new ArrayList<>());
                if (!l.contains(e)) l.add(e);
                l.sort((a, b) -> a.priority - b.priority);
            }
        }

        @Override
        public List<DictEntry> lookup(String term) {
            queries.add(term);
            List<DictEntry> l = map.get(term);
            return l == null ? Collections.emptyList() : l;
        }
    }

    private FakeDictionary dict;
    private WordLookup lookup;

    @Before
    public void setUp() {
        dict = new FakeDictionary();
        dict.add(1671020, "模擬戦", "もぎせん", 2, "n", "sham battle", "mock battle");
        dict.add(1307550, "始める", "はじめる", 0, "v1,vt", "to start", "to begin");
        dict.add(1342550, "始めて", "はじめて", 0, "adv", "for the first time");
        dict.add(1269130, "くれる", "くれる", 0, "v1-s,vt", "to give");
        dict.add(1514960, "暮れる", "くれる", 0, "v1,vi", "to get dark");
        dict.add(1326540, "取り扱う", "とりあつかう", 0, "v5u,vt", "to handle");
        dict.add(1358280, "食べる", "たべる", 0, "v1,vt", "to eat");
        dict.add(1, "模擬", "もぎ", 1, "n", "imitation", "sham");
        lookup = new WordLookup(dict, Tokenizer.get());
    }

    private WordLookup.Result sel(String sentence, String selected) {
        int i = sentence.indexOf(selected);
        assertTrue(i >= 0);
        return lookup.lookup(sentence, i, i + selected.length());
    }

    @Test
    public void compoundSplitByTokenizer() {
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "模擬戦");
        System.out.println(r);
        assertEquals("模擬戦", r.entry.headword);
        assertEquals("模擬戦[もぎせん]", r.front);
        assertEquals("(n) sham battle; mock battle", r.glossary);
    }

    @Test
    public void inflectedVerbMinesDictionaryForm() {
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "始めて");
        System.out.println(r);
        assertEquals("始める", r.matchedTerm);
        assertEquals("始[はじ]める", r.front);
        assertEquals("(v1, vt) to start; to begin", r.glossary);
    }

    @Test
    public void singleTokenUsesBaseFormFirst() {
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "始め");
        assertEquals("始める", dict.queries.get(0));
        assertEquals("始[はじ]める", r.front);
    }

    @Test
    public void singleKanaTokenPrefersKanaHeadword() {
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "くれ");
        assertEquals("くれる", r.entry.headword);
        assertEquals("くれる", r.front);
        assertEquals(2, r.alternatives.size());
    }

    @Test
    public void partialTokenSelection() {
        // 模擬 is a prefix of whatever Kuromoji produced; looked up exactly.
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "模擬");
        assertEquals("模擬", r.entry.headword);
        assertEquals("模擬[もぎ]", r.front);
    }

    @Test
    public void deinflectsLongerChain() {
        WordLookup.Result r = sel("それを食べさせられた", "食べさせられた");
        assertEquals("食べる", r.matchedTerm);
        assertEquals("食[た]べる", r.front);
    }

    @Test
    public void deinflectsMultiKanjiVerb() {
        WordLookup.Result r = sel("丁寧に取り扱ってください", "取り扱って");
        assertEquals("取り扱う", r.matchedTerm);
        assertEquals("取[と]り 扱[あつか]う", r.front);
    }

    @Test
    public void noMatchUsesTokenizerReading() {
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "戦を");
        assertFalse(r.found());
        assertNull(r.entry);
        assertEquals("", r.glossary);
        assertEquals("戦[せん]を", r.front);
    }

    @Test
    public void noMatchMisalignedRawSelection() {
        WordLookup.Result r = sel("模擬戦を始めてくれ。", "擬戦を始");
        assertFalse(r.found());
        assertEquals("擬戦を始", r.front);
    }

    @Test
    public void whitespaceAndEmptySelection() {
        String s = "えっと 模擬戦 やろう";
        WordLookup.Result r = lookup.lookup(s, 3, 8); // " 模擬戦 " minus outer chars
        assertEquals("模擬戦", r.selection);
        assertTrue(r.found());
        WordLookup.Result empty = lookup.lookup(s, 2, 2);
        assertFalse(empty.found());
        assertEquals("", empty.front);
    }

    @Test
    public void outOfRangeIsClamped() {
        WordLookup.Result r = lookup.lookup("模擬戦", -5, 100);
        assertEquals("模擬戦", r.selection);
        assertTrue(r.found());
    }

    private String tapped(String sentence, String tokenSurface) {
        List<Token> tokens = Tokenizer.get().tokenize(sentence);
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).surface.equals(tokenSurface)) {
                int[] r = lookup.longestMatch(sentence, tokens, i);
                return sentence.substring(r[0], r[1]);
            }
        }
        throw new AssertionError(tokenSurface);
    }

    @Test
    public void longestMatchJoinsCompound() {
        assertEquals("模擬戦", tapped("模擬戦を始めてくれ。", "模擬"));
    }

    @Test
    public void longestMatchIncludesConjugation() {
        assertEquals("始めて", tapped("模擬戦を始めてくれ。", "始め"));
        assertEquals("を", tapped("模擬戦を始めてくれ。", "を"));
        assertEquals("くれ", tapped("模擬戦を始めてくれ。", "くれ"));
    }

    @Test
    public void glossaryEscapesAndCaps() {
        List<DictEntry.Sense> senses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            senses.add(new DictEntry.Sense(Arrays.asList("n"), Arrays.asList("a<b", "c&d")));
        }
        senses.set(1, new DictEntry.Sense(Arrays.asList("v1", "vt"), Arrays.asList("x")));
        String g = Glossary.format(new DictEntry(9, "語", "ご", 0, senses));
        assertEquals("1. (n) a&lt;b; c&amp;d<br>2. (v1, vt) x<br>3. (n) a&lt;b; c&amp;d<br>"
                + "4. a&lt;b; c&amp;d", g);
    }

    @Test
    public void parsesPackedSenses() {
        List<DictEntry.Sense> s = DictEntry.parseSenses(
                "v1,vt\u001fto start\u001dto begin\u001en\u001fbeginning");
        assertEquals(2, s.size());
        assertEquals(Arrays.asList("v1", "vt"), s.get(0).partsOfSpeech);
        assertEquals(Arrays.asList("to start", "to begin"), s.get(0).glosses);
        assertEquals(Arrays.asList("beginning"), s.get(1).glosses);
    }
}
