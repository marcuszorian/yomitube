package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FuriganaTest {

    private static void check(String expected, String expr, String reading) {
        assertEquals(expected, Furigana.format(expr, reading));
    }

    @Test public void allKanji() { check("模擬戦[もぎせん]", "模擬戦", "もぎせん"); }
    @Test public void trailingOkurigana() { check("始[はじ]める", "始める", "はじめる"); }
    @Test public void twoKanjiRuns() { check("取[と]り 扱[あつか]う", "取り扱う", "とりあつかう"); }
    @Test public void leadingKana() { check("お 茶[ちゃ]", "お茶", "おちゃ"); }
    @Test public void leadingAndTrailingKana() { check("お 見舞[みま]い", "お見舞い", "おみまい"); }
    @Test public void kanaOnly() { check("くれる", "くれる", "くれる"); }
    @Test public void katakanaOnly() { check("ゲーム", "ゲーム", "げーむ"); }
    @Test public void katakanaPlusKanji() { check("ゲーム 機[き]", "ゲーム機", "げーむき"); }
    @Test public void katakanaReadingInput() { check("模擬戦[もぎせん]", "模擬戦", "モギセン"); }
    @Test public void iterationMark() { check("時々[ときどき]", "時々", "ときどき"); }
    @Test public void kanjiKanaKanji() { check("見[み]る 目[め]", "見る目", "みるめ"); }
    @Test public void middleKana() { check("食[た]べ 物[もの]", "食べ物", "たべもの"); }

    @Test public void ambiguousFallsBackToWholeWord() {
        // し in the reading could belong to either kanji run.
        check("仕仕し仕[ししししし]", "仕仕し仕", "ししししし");
    }

    @Test public void mismatchedReadingFallsBack() { check("始める[おわる]", "始める", "おわる"); }
    @Test public void nullOrEmptyReading() {
        check("模擬戦", "模擬戦", null);
        check("模擬戦", "模擬戦", "");
    }
    @Test public void emptyExpression() { check("", "", "あ"); }

    @Test public void kanaInExpressionNotInReadingCountsAsMismatch() {
        // Long-vowel mark in katakana vs. hiragana reading with ー is still literal.
        check("ラーメン 屋[や]", "ラーメン屋", "らーめんや");
    }

    @Test public void speechUsesReadings() {
        assertEquals("とりあつかう", Furigana.toSpeech("取[と]り 扱[あつか]う"));
        assertEquals("もぎせん", Furigana.toSpeech("模擬戦[もぎせん]"));
        assertEquals("おちゃ", Furigana.toSpeech("お 茶[ちゃ]"));
        assertEquals("ゲームき", Furigana.toSpeech("ゲーム 機[き]"));
    }

    @Test public void speechWithoutBrackets() {
        assertEquals("テレビ", Furigana.toSpeech("テレビ"));
        assertEquals("ありがとう", Furigana.toSpeech(" ありがとう "));
        assertEquals("模擬戦", Furigana.toSpeech("模擬戦"));
        assertEquals("", Furigana.toSpeech(null));
    }

    @Test public void speechRoundTripsFormat() {
        String[][] cases = {{"始める", "はじめる"}, {"お見舞い", "おみまい"}, {"為る", "する"}};
        for (String[] c : cases) {
            assertEquals(c[1], Furigana.toSpeech(Furigana.format(c[0], c[1])));
        }
    }
}
