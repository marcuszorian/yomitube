package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class TokenizerTest {

    /** Tokens cover the input contiguously with correct offsets. */
    static void assertCovers(String s, List<Token> tokens) {
        int pos = 0;
        for (Token t : tokens) {
            assertEquals("gap before " + t, pos, t.start);
            assertEquals(t.surface, s.substring(t.start, t.end));
            pos = t.end;
        }
        assertEquals(s.length(), pos);
    }

    static Token find(List<Token> tokens, String surface) {
        for (Token t : tokens) if (t.surface.equals(surface)) return t;
        throw new AssertionError("no token " + surface + " in " + tokens);
    }

    @Test
    public void basicSentence() {
        String s = "模擬戦を始めてくれ。";
        List<Token> tokens = Tokenizer.get().tokenize(s);
        for (Token t : tokens) System.out.println(t);
        assertCovers(s, tokens);
        Token hajime = find(tokens, "始め");
        assertEquals("始める", hajime.baseForm);
        assertEquals("はじめ", hajime.reading);
        assertEquals("動詞", hajime.partOfSpeech);
        Token kure = find(tokens, "くれ");
        assertEquals("くれる", kure.baseForm);
        assertTrue(find(tokens, "を").isFunctionWord());
        assertTrue(find(tokens, "。").isPunctuationOrSymbol());
        assertEquals(s.length(), tokens.get(tokens.size() - 1).end);
    }

    @Test
    public void autoCaptionLine() {
        String s = "[音楽] えっと今日はですね みんなでゲームやっていこうと思います YouTube 2回目";
        List<Token> tokens = Tokenizer.get().tokenize(s);
        for (Token t : tokens) System.out.println(t);
        assertCovers(s, tokens);
        Token music = tokens.get(0);
        assertEquals("[音楽]", music.surface);
        assertTrue(music.isPunctuationOrSymbol());
        assertEquals("思う", find(tokens, "思い").baseForm);
        assertEquals("げーむ", find(tokens, "ゲーム").reading);
        Token yt = find(tokens, "YouTube");
        assertEquals(yt.surface, s.substring(yt.start, yt.end));
    }

    @Test
    public void readingsAreHiragana() {
        for (Token t : Tokenizer.get().tokenize("コンピューターで東京タワーを見た")) {
            if (t.reading == null) continue;
            for (char c : t.reading.toCharArray()) {
                assertTrue(t + " has katakana", !Kana.isKatakana(c));
            }
        }
    }

    @Test
    public void emptyInput() {
        assertTrue(Tokenizer.get().tokenize("").isEmpty());
        assertTrue(Tokenizer.get().tokenize(null).isEmpty());
    }
}
