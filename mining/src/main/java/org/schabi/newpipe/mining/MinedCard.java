package org.schabi.newpipe.mining;

import android.net.Uri;

/** Everything needed to create one Anki note. */
public final class MinedCard {
    /** Selected term with furigana in Anki's {@code 漢字[かんじ]} format. */
    public final String front;
    public final String glossary;
    public final String sentence;
    /** Local audio clip of the trimmed sentence; used for both Audio and SentenceAudio in v1. */
    public final Uri sentenceAudio;
    /** Local JPEG of the video frame for the Picture field, or null for none. */
    public final Uri picture;
    /**
     * Text-to-speech of the word for the Audio field, or null. When null the Audio field falls back
     * to {@link #sentenceAudio} (the v1 behaviour).
     */
    public final Uri wordAudio;

    public MinedCard(String front, String glossary, String sentence, Uri sentenceAudio) {
        this(front, glossary, sentence, sentenceAudio, null);
    }

    public MinedCard(String front, String glossary, String sentence, Uri sentenceAudio, Uri picture) {
        this(front, glossary, sentence, sentenceAudio, picture, null);
    }

    public MinedCard(String front, String glossary, String sentence, Uri sentenceAudio, Uri picture,
                     Uri wordAudio) {
        this.wordAudio = wordAudio;
        this.front = front;
        this.glossary = glossary;
        this.sentence = sentence;
        this.sentenceAudio = sentenceAudio;
        this.picture = picture;
    }
}
