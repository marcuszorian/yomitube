package org.schabi.newpipe.mining;

/** Small character-class helpers for Japanese text. Pure Java. */
public final class Kana {
    private Kana() {}

    public static boolean isHiragana(char c) {
        return (c >= 'ぁ' && c <= 'ゖ') || c == 'ゝ' || c == 'ゞ';
    }

    public static boolean isKatakana(char c) {
        return (c >= 'ァ' && c <= 'ヺ') || c == 'ヽ' || c == 'ヾ'
                || (c >= 'ｦ' && c <= 'ﾟ');
    }

    /** Hiragana, katakana, or the prolonged sound mark / middle dot. */
    public static boolean isKana(char c) {
        return isHiragana(c) || isKatakana(c) || c == 'ー' || c == '・';
    }

    /** CJK ideographs plus kanji-like marks that need a reading: 々 〆 ヶ ヵ. */
    public static boolean isKanji(char c) {
        return (c >= '一' && c <= '鿿') || (c >= '㐀' && c <= '䶿')
                || (c >= '豈' && c <= '﫿')
                || c == '々' || c == '〆' || c == 'ヶ' || c == 'ヵ';
    }

    public static boolean containsKanji(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isKanji(s.charAt(i))) return true;
            if (Character.isHighSurrogate(s.charAt(i))) {
                int cp = s.codePointAt(i);
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
            }
        }
        return false;
    }

    public static boolean isAllKana(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!isKana(s.charAt(i))) return false;
        return true;
    }

    /** Full-width katakana → hiragana (ヴ→ゔ, ヵ/ヶ→ゕ/ゖ); everything else unchanged. */
    public static String toHiragana(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') c = (char) (c - 0x60);
            else if (c == 'ヽ' || c == 'ヾ') c = (char) (c - 0x60);
            sb.append(c);
        }
        return sb.toString();
    }
}
