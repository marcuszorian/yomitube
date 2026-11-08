package org.schabi.newpipe.mining;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TextSelectionTest {

    @Test
    public void extendAndShrinkByOneCharacter() {
        // 模擬戦をやる, selection 模擬 [0,2)
        assertArrayEquals(new int[]{0, 3}, TextSelection.moveEnd(0, 2, +1, 6));
        assertArrayEquals(new int[]{1, 2}, TextSelection.moveStart(0, 2, +1, 6));
    }

    @Test
    public void neverEmptyNorOutOfBounds() {
        assertArrayEquals(new int[]{0, 1}, TextSelection.moveEnd(0, 1, -1, 6));
        assertArrayEquals(new int[]{0, 1}, TextSelection.moveStart(0, 1, +1, 6));
        assertArrayEquals(new int[]{0, 2}, TextSelection.moveStart(0, 2, -1, 6));
        assertArrayEquals(new int[]{4, 6}, TextSelection.moveEnd(4, 6, +1, 6));
    }

    @Test
    public void punctuationAndNotesNotSelectable() {
        assertFalse(TextSelection.isSelectable(new Token("。", 0, 1, "。", null, "記号", "記号,句点")));
        assertFalse(TextSelection.isSelectable(new Token("[音楽]", 0, 4, "[音楽]", null, "記号", "記号,注釈")));
        assertFalse(TextSelection.isSelectable(new Token(" ", 0, 1, " ", null, "名詞", "名詞")));
        assertTrue(TextSelection.isSelectable(new Token("模擬", 0, 2, "模擬", "もぎ", "名詞", "名詞,サ変接続")));
    }

    @Test
    public void tokenAtOffset() {
        List<Token> t = Arrays.asList(
                new Token("模擬", 0, 2, "模擬", "もぎ", "名詞", "名詞"),
                new Token("戦", 2, 3, "戦", "せん", "名詞", "名詞,接尾"));
        assertEquals(0, TextSelection.tokenAt(t, 1));
        assertEquals(1, TextSelection.tokenAt(t, 2));
        assertEquals(-1, TextSelection.tokenAt(t, 3));
    }
}
