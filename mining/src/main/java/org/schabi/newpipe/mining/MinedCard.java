package org.schabi.newpipe.mining;

import android.net.Uri;

/** Everything needed to create one Anki note. */
public final class MinedCard {
    /** Selected term with furigana in Anki's {@code 漢字[かんじ]} format. */
    public final String front;
    public final String glossary;
    public final String sentence;
    /** Local audio clip of the trimmed sentence, for SentenceAudio. (No word audio is made.) */
    public final Uri sentenceAudio;
    /** Local JPEG of the video frame for the Picture field, or null for none. */
    public final Uri picture;

    public MinedCard(String front, String glossary, String sentence, Uri sentenceAudio) {
        this(front, glossary, sentence, sentenceAudio, null);
    }

    public MinedCard(String front, String glossary, String sentence, Uri sentenceAudio, Uri picture) {
        this.front = front;
        this.glossary = glossary;
        this.sentence = sentence;
        this.sentenceAudio = sentenceAudio;
        this.picture = picture;
    }
}
