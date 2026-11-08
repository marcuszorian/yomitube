package org.schabi.newpipe.mining;

import java.util.Collections;
import java.util.List;

/** One JMdict entry as seen through a particular lookup key. Immutable. */
public final class DictEntry {

    /** One sense (meaning) of an entry. */
    public static final class Sense {
        /** JMdict POS codes, e.g. {@code n}, {@code v1}, {@code vt}, {@code adj-i}. */
        public final List<String> partsOfSpeech;
        /** English glosses, e.g. "mock battle". */
        public final List<String> glosses;

        public Sense(List<String> partsOfSpeech, List<String> glosses) {
            this.partsOfSpeech = Collections.unmodifiableList(partsOfSpeech);
            this.glosses = Collections.unmodifiableList(glosses);
        }
    }

    /** JMdict ent_seq. */
    public final long id;
    /** Form to mine/display: kanji form if the word is normally written with kanji, else kana. */
    public final String headword;
    /** Reading of {@link #headword} in hiragana (katakana loanwords are converted too). */
    public final String reading;
    /** Reading exactly as in JMdict (may be katakana). */
    public final String kanaForm;
    /** 0 = common form, 1 = form of a common entry, 2 = uncommon, 3 = rare/irregular form. */
    public final int priority;
    public final List<Sense> senses;

    public DictEntry(long id, String headword, String kanaForm, int priority, List<Sense> senses) {
        this.id = id;
        this.headword = headword;
        this.kanaForm = kanaForm;
        this.reading = Kana.toHiragana(kanaForm);
        this.priority = priority;
        this.senses = Collections.unmodifiableList(senses);
    }

    public boolean isCommon() {
        return priority <= 1;
    }

    /** Anki furigana syntax for this entry, e.g. {@code 始[はじ]める}. */
    public String toFurigana() {
        return Furigana.format(headword, kanaForm);
    }

    /**
     * Parses the packed {@code entry.senses} column written by {@code tools/build_jmdict.py}:
     * senses separated by U+001E; POS list and glosses separated by U+001F; POS codes by ",";
     * glosses by U+001D.
     */
    public static List<Sense> parseSenses(String packed) {
        List<Sense> senses = new java.util.ArrayList<>();
        if (packed == null || packed.isEmpty()) return senses;
        for (String s : packed.split("\u001e")) {
            int sep = s.indexOf('\u001f');
            String pos = sep < 0 ? "" : s.substring(0, sep);
            String gl = sep < 0 ? s : s.substring(sep + 1);
            List<String> posList = pos.isEmpty()
                    ? new java.util.ArrayList<>() : java.util.Arrays.asList(pos.split(","));
            senses.add(new Sense(posList, java.util.Arrays.asList(gl.split("\u001d"))));
        }
        return senses;
    }

    @Override
    public String toString() {
        return headword + "【" + reading + "】#" + id + " p" + priority;
    }
}
